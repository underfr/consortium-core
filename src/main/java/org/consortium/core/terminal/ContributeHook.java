package org.consortium.core.terminal;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ContextChain;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.ConsortiumRuntime;
import org.consortium.core.config.ServerConfig;
import org.consortium.core.economy.Transactions;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The bridge from a committed delivery to the KubeJS phase engine of the pack (specification 5, follow-ups): once per
 * delivered item id the SERVER config template {@code contribute_command} (default
 * {@code consortium contribute {item} {count}}) is rendered and run as the console, on the server thread, right after
 * the ledger commit, in its own execution context so it completes before the delivery returns even when the delivery
 * itself runs inside a command. Output is suppressed; a failure (unknown command, bad argument, an exception inside
 * the engine) is logged at WARN once per minute with the failures suppressed in between, and never reaches the
 * delivery: the money and the items already moved together.
 */
public final class ContributeHook {
    private static final long WARN_INTERVAL_MS = 60_000L;

    private static volatile long lastWarning;
    private static int suppressed;

    private ContributeHook() {
    }

    /** Runs the template once per {@link Transactions.ItemCount} of the committed delivery. Never throws. */
    public static void afterDelivery(ConsortiumRuntime rt, Transactions.DeliveryRequest request, String tx) {
        String template;
        try {
            template = ServerConfig.contributeCommand();
        } catch (Throwable t) {
            template = null;
        }
        if (template == null || template.isBlank() || rt == null || rt.server == null) {
            return;
        }
        for (Transactions.DeliveryLine line : request.lines()) {
            for (Transactions.ItemCount ic : line.items()) {
                if (ic.count() <= 0) {
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                values.put("item", ic.id());
                values.put("count", Integer.toString(ic.count()));
                values.put("player", request.name() == null ? "" : request.name());
                values.put("uuid", request.player() == null ? "" : request.player().toString());
                values.put("tx", tx == null ? "" : tx);
                String command;
                try {
                    command = CommandTemplate.render(template, values);
                } catch (Throwable t) {
                    failure(rt, tx, template, "template could not be rendered: " + t);
                    continue;
                }
                if (command.isBlank()) {
                    continue;
                }
                run(rt, command, tx);
            }
        }
    }

    /** Console-like source whose output is discarded, except the first lines kept for the failure log. */
    private static final class Recorder implements CommandSource {
        private final StringBuilder messages = new StringBuilder();
        private int count;

        @Override
        public void sendSystemMessage(Component component) {
            if (component == null || count >= 3) {
                return;
            }
            String text = component.getString().replace('\n', ' ').trim();
            if (text.isEmpty()) {
                return;
            }
            messages.append(count++ == 0 ? "" : " | ").append(text);
        }

        String lastMessage() {
            return count == 0 ? null : messages.toString();
        }

        @Override
        public boolean acceptsSuccess() {
            return false;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }

    private static void run(ConsortiumRuntime rt, String command, String tx) {
        MinecraftServer server = rt.server;
        Recorder recorder = new Recorder();
        boolean[] reported = new boolean[1];
        boolean[] succeeded = new boolean[1];
        try {
            ServerLevel level = server.overworld();
            Vec3 position = level == null ? Vec3.ZERO : Vec3.atLowerCornerOf(level.getSharedSpawnPos());
            CommandSourceStack source = new CommandSourceStack(recorder, position, Vec2.ZERO, level, 4, "Server",
                    Component.literal("Server"), server, null)
                    .withCallback((success, result) -> {
                        reported[0] = true;
                        succeeded[0] = success;
                    });
            ParseResults<CommandSourceStack> parse = server.getCommands().getDispatcher().parse(command, source);
            Commands.validateParseResults(parse);
            ContextChain<CommandSourceStack> chain = ContextChain.tryFlatten(parse.getContext().build(command))
                    .orElseThrow(() -> CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().createWithContext(parse.getReader()));
            // Own execution context, run to completion here: Commands.performCommand would only queue the command
            // behind the outer one when a delivery itself happens inside a command (the self-test, a script), and
            // its catch-all turns a script exception into a silent chat line; this way both reach the log below.
            int chainLimit = Math.max(1, server.getGameRules().getInt(GameRules.RULE_MAX_COMMAND_CHAIN_LENGTH));
            int forkLimit = server.getGameRules().getInt(GameRules.RULE_MAX_COMMAND_FORK_COUNT);
            try (ExecutionContext<CommandSourceStack> context = new ExecutionContext<>(chainLimit, forkLimit, server.getProfiler())) {
                ExecutionContext.queueInitialCommandExecution(context, command, chain, source, CommandResultCallback.EMPTY);
                context.runCommandQueue();
            }
        } catch (CommandSyntaxException e) {
            failure(rt, tx, command, e.getMessage());
            return;
        } catch (Throwable t) {
            failure(rt, tx, command, t.toString());
            return;
        }
        if (!reported[0] || !succeeded[0]) {
            // Reported as failed: a command syntax error raised while executing; not reported at all: the chain
            // ended without running its command (a redirect that produced no source, an execution limit).
            failure(rt, tx, command, recorder.lastMessage() == null ? "no result reported" : recorder.lastMessage());
        }
    }

    private static synchronized void failure(ConsortiumRuntime rt, String tx, String command, String detail) {
        long now = rt == null ? System.currentTimeMillis() : rt.now();
        if (now - lastWarning >= WARN_INTERVAL_MS) {
            String more = suppressed > 0 ? " (" + suppressed + " earlier failure(s) not logged)" : "";
            ConsortiumCore.LOGGER.warn("contribute_command failed for tx {}: '{}': {}{}", tx, command, detail, more);
            lastWarning = now;
            suppressed = 0;
        } else {
            suppressed++;
        }
    }
}
