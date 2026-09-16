package org.consortium.core.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.neoforged.fml.ModList;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.ConsortiumRuntime;
import org.consortium.core.compat.SdlinkBridge;
import org.consortium.core.config.ServerConfig;
import org.consortium.core.economy.Ledger;
import org.consortium.core.economy.LedgerLine;
import org.consortium.core.monitoring.Scheduler;

import java.io.IOException;
import java.util.List;

import static org.consortium.core.command.CommandSupport.fail;
import static org.consortium.core.command.CommandSupport.grey;
import static org.consortium.core.command.CommandSupport.info;
import static org.consortium.core.command.CommandSupport.ok;
import static org.consortium.core.command.CommandSupport.runtime;

/**
 * {@code /ccore} (specification 4): the mod's own admin tree, deliberately not rooted at {@code /consortium}, which
 * the pack's KubeJS phase engine owns (Brigadier would merge two roots of the same name in undefined order).
 * Subcommands: {@code version}, {@code ledger tail}, {@code report [weekly|daily]}, {@code identity forget|purge},
 * and {@code selftest} only when the JVM runs with {@code -Dconsortium.selftest=true} (test servers).
 */
public final class CcoreCommand {
    /** JVM flag that registers {@code /ccore selftest}; never set it on the production server. */
    public static final String SELFTEST_PROPERTY = "consortium.selftest";

    private CcoreCommand() {
    }

    public static boolean selfTestEnabled() {
        return Boolean.getBoolean(SELFTEST_PROPERTY);
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("ccore")
                .executes(CcoreCommand::version)
                .then(Commands.literal("version").executes(CcoreCommand::version))
                .then(Commands.literal("ledger")
                        .requires(Permissions.require(Permissions.ADMIN_LEDGER, 2))
                        .then(Commands.literal("tail")
                                .executes(ctx -> ledgerTail(ctx, 20, null))
                                .then(Commands.argument("n", IntegerArgumentType.integer(1, 100))
                                        .executes(ctx -> ledgerTail(ctx, IntegerArgumentType.getInteger(ctx, "n"), null))
                                        .then(Commands.argument("filter", StringArgumentType.word())
                                                .executes(ctx -> ledgerTail(ctx, IntegerArgumentType.getInteger(ctx, "n"), StringArgumentType.getString(ctx, "filter")))))))
                .then(Commands.literal("report")
                        .requires(Permissions.require(Permissions.ADMIN_REPORT, 2))
                        .executes(ctx -> report(ctx, 7))
                        .then(Commands.literal("weekly").executes(ctx -> report(ctx, 7)))
                        .then(Commands.literal("daily").executes(ctx -> report(ctx, 1))))
                .then(Commands.literal("identity")
                        .requires(Permissions.require(Permissions.ADMIN_IDENTITY, 4))
                        .then(Commands.literal("forget")
                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                        .suggests(CommandSupport.ACCOUNT_SUGGESTIONS)
                                        .executes(CcoreCommand::identityForget)))
                        .then(Commands.literal("purge").executes(CcoreCommand::identityPurge)));
        if (selfTestEnabled()) {
            ConsortiumCore.LOGGER.warn("/ccore selftest is registered (-D{}=true): it writes real ledger lines, keep it off production", SELFTEST_PROPERTY);
            root.then(Commands.literal("selftest")
                    .requires(src -> src.hasPermission(4))
                    .executes(SelfTestCommand::run));
        }
        dispatcher.register(root);
    }

    private static int version(CommandContext<CommandSourceStack> ctx) {
        ConsortiumRuntime rt = ConsortiumRuntime.get();
        String version = ModList.get().getModContainerById(ConsortiumCore.MOD_ID).map(c -> c.getModInfo().getVersion().toString()).orElse("?");
        ModList mods = ModList.get();
        StringBuilder sb = new StringBuilder("Consortium Core ").append(version);
        if (rt == null) {
            sb.append(": economy not started");
        } else {
            sb.append(": ").append(rt.prices.familyCount()).append(" families, ").append(rt.prices.index().size()).append(" items indexed, ")
                    .append(rt.economy.accounts().size()).append(" accounts, ledger seq ").append(rt.ledger.nextSeq())
                    .append(rt.ledgerReady() ? "" : " (LEDGER UNAVAILABLE)");
            Ledger.RollbackRange rollback = rt.ledger.lastRollback();
            if (rollback != null) {
                sb.append("; ROLLBACK at last boot: ").append(rollback);
            }
            String hook = ServerConfig.contributeCommand();
            sb.append("; contribute command: ").append(hook == null || hook.isBlank() ? "disabled" : "'" + hook + "'");
        }
        sb.append("; optional mods: kubejs=").append(mods.isLoaded("kubejs")).append(", ftbteams=").append(mods.isLoaded("ftbteams"))
                .append(", ftbquests=").append(mods.isLoaded("ftbquests")).append(", sdlink=").append(mods.isLoaded("sdlink"))
                .append(SdlinkBridge.available() ? " (bridge on)" : "").append(", luckperms=").append(mods.isLoaded("luckperms"));
        if (selfTestEnabled()) {
            sb.append("; selftest enabled");
        }
        final String text = sb.toString();
        ctx.getSource().sendSuccess(() -> info(text), false);
        return 1;
    }

    private static int ledgerTail(CommandContext<CommandSourceStack> ctx, int n, String filter) throws CommandSyntaxException {
        ConsortiumRuntime rt = runtime();
        List<String> lines = rt.ledger.tail(n, filter);
        if (lines.isEmpty()) {
            ctx.getSource().sendSuccess(() -> grey("No ledger line" + (filter != null ? " matching " + filter : "") + "."), false);
            return 1;
        }
        ctx.getSource().sendSuccess(() -> info("Ledger tail, newest first (" + lines.size() + " line(s)):"), false);
        for (String line : lines) {
            String type = LedgerLine.typeOf(line);
            boolean rollback = "ROLLBACK".equals(type);
            ctx.getSource().sendSuccess(() -> rollback ? fail(line) : grey(line), false);
        }
        return 1;
    }

    private static int report(CommandContext<CommandSourceStack> ctx, int windowDays) throws CommandSyntaxException {
        ConsortiumRuntime rt = runtime();
        String text = Scheduler.report(rt, windowDays);
        for (String line : text.split("\n")) {
            ctx.getSource().sendSuccess(() -> info(line), false);
        }
        if (SdlinkBridge.sendCustom(text)) {
            ctx.getSource().sendSuccess(() -> grey("Posted to Discord through Simple Discord Link."), false);
        }
        return 1;
    }

    private static int identityForget(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ConsortiumRuntime rt = runtime();
        CommandSupport.Target t = CommandSupport.target(ctx, rt);
        boolean removed = rt.identity.forget(t.uuid());
        ctx.getSource().sendSuccess(() -> ok(removed ? "Connection hash of " + t.name() + " forgotten (the account's grant flag stays)."
                : t.name() + " had no connection hash."), true);
        return 1;
    }

    private static int identityPurge(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ConsortiumRuntime rt = runtime();
        int count = rt.identity.servedCount();
        rt.identity.purge();
        try {
            rt.salt.purge();
        } catch (IOException e) {
            ctx.getSource().sendFailure(fail("Hashes purged but the salt file could not be deleted: " + e.getMessage()));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> ok("Identity purged: " + count + " hash(es) and the salt deleted; a new salt is generated at the next start."), true);
        return 1;
    }
}
