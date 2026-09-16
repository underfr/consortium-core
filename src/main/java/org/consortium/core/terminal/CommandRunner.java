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

import java.util.Map;

/**
 * Runs a console command template on the server thread with its output suppressed (specification 5 follow-ups,
 * v0.2 5.1 and 5.3): the {@code contribute_command} after a delivery and a shop entry's {@code command} after a
 * purchase. Shared rules:
 *
 * <ul>
 * <li><b>Placeholder safety</b>: every known placeholder the template uses is validated by
 * {@link CommandTemplate#invalidPlaceholder} before rendering; a value that fails (a legacy name with a space, a
 * malformed uuid) is never rendered and the run is refused with the placeholder's name as the detail.</li>
 * <li><b>Own execution context</b>: the command completes before the call returns even when the caller itself
 * runs inside a command ({@code Commands.performCommand} would only queue it behind the outer one), and an
 * exception thrown inside the command reaches the caller as a failure detail instead of a silent chat line.</li>
 * <li><b>One WARN per minute</b> through {@link #warnFailure}, the failures in between counted; a failure never
 * reaches the transaction that triggered the command: the money already moved.</li>
 * </ul>
 */
public final class CommandRunner {
    private static final long WARN_INTERVAL_MS = 60_000L;

    private static long lastWarning;
    private static int suppressed;

    private CommandRunner() {
    }

    /** What happened to one template run: the rendered command (null when refused before rendering) and the failure detail, null on success. */
    public record Run(String command, String failure) {
        public boolean ok() {
            return failure == null;
        }
    }

    /**
     * Validates, renders and runs the template as the console. Never throws.
     *
     * @param values placeholder values ({@code item}, {@code count}, {@code player}, {@code uuid}, {@code tx})
     */
    public static Run execute(ConsortiumRuntime rt, String template, Map<String, String> values) {
        if (template == null || template.isBlank()) {
            return new Run(null, "empty template");
        }
        String bad = CommandTemplate.invalidPlaceholder(template, values);
        if (bad != null) {
            return new Run(null, "placeholder {" + bad + "} has no usable value (" + describe(values.get(bad)) + ")");
        }
        String command;
        try {
            command = CommandTemplate.render(template, values);
        } catch (RuntimeException e) {
            return new Run(null, "template could not be rendered: " + e);
        }
        if (command.isBlank()) {
            return new Run(command, "rendered command is empty");
        }
        return new Run(command, run(rt, command));
    }

    private static String describe(String value) {
        if (value == null) {
            return "missing";
        }
        return "'" + (value.length() > 40 ? value.substring(0, 40) + "..." : value) + "'";
    }

    /** Console-like source whose output is discarded, except the first lines kept for the failure detail. */
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

    /** Runs an already rendered command; returns the failure detail, or null when it reported success. */
    public static String run(ConsortiumRuntime rt, String command) {
        if (rt == null || rt.server == null) {
            return "no server";
        }
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
            int chainLimit = Math.max(1, server.getGameRules().getInt(GameRules.RULE_MAX_COMMAND_CHAIN_LENGTH));
            int forkLimit = server.getGameRules().getInt(GameRules.RULE_MAX_COMMAND_FORK_COUNT);
            try (ExecutionContext<CommandSourceStack> context = new ExecutionContext<>(chainLimit, forkLimit, server.getProfiler())) {
                ExecutionContext.queueInitialCommandExecution(context, command, chain, source, CommandResultCallback.EMPTY);
                context.runCommandQueue();
            }
        } catch (CommandSyntaxException e) {
            return e.getMessage();
        } catch (Throwable t) {
            return t.toString();
        }
        if (!reported[0] || !succeeded[0]) {
            // Reported as failed: a command syntax error raised while executing; not reported at all: the chain
            // ended without running its command (a redirect that produced no source, an execution limit).
            return recorder.lastMessage() == null ? "no result reported" : recorder.lastMessage();
        }
        return null;
    }

    /** One WARN per minute for a failed template run, whatever the caller; the failures in between are counted. */
    public static synchronized void warnFailure(ConsortiumRuntime rt, String what, String tx, String command, String detail) {
        long now = rt == null ? System.currentTimeMillis() : rt.now();
        if (now - lastWarning >= WARN_INTERVAL_MS) {
            String more = suppressed > 0 ? " (" + suppressed + " earlier failure(s) not logged)" : "";
            ConsortiumCore.LOGGER.warn("{} failed for tx {}: '{}': {}{}", what, tx, command, detail, more);
            lastWarning = now;
            suppressed = 0;
        } else {
            suppressed++;
        }
    }
}
