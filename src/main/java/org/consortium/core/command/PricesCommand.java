package org.consortium.core.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.consortium.core.ConsortiumRuntime;
import org.consortium.core.api.Result;
import org.consortium.core.config.CommonConfig;
import org.consortium.core.economy.Account;
import org.consortium.core.economy.Money;
import org.consortium.core.economy.PriceOverride;
import org.consortium.core.economy.Units;
import org.consortium.core.pricing.PriceFamily;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.consortium.core.command.CommandSupport.fail;
import static org.consortium.core.command.CommandSupport.grey;
import static org.consortium.core.command.CommandSupport.info;
import static org.consortium.core.command.CommandSupport.ok;
import static org.consortium.core.command.CommandSupport.runtime;

/** {@code /prices} (specification 4): view the table, hot-adjust a family, reset a market. */
public final class PricesCommand {
    private static final int PAGE_SIZE = 10;

    private PricesCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("prices")
                .requires(Permissions.require(Permissions.PRICES_VIEW, 0))
                .executes(ctx -> list(ctx, 1))
                .then(Commands.literal("get")
                        .then(Commands.argument("key", ResourceLocationArgument.id())
                                .suggests(CommandSupport.KEY_SUGGESTIONS)
                                .executes(PricesCommand::get)))
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> list(ctx, IntegerArgumentType.getInteger(ctx, "page")))))
                .then(Commands.literal("set")
                        .requires(Permissions.require(Permissions.ADMIN_PRICES, 2))
                        .then(Commands.argument("key", ResourceLocationArgument.id())
                                .suggests(CommandSupport.KEY_OR_ITEM_SUGGESTIONS)
                                .then(Commands.argument("base", StringArgumentType.word())
                                        .then(Commands.argument("half_volume", DoubleArgumentType.doubleArg(0.001))
                                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                        .executes(ctx -> set(ctx, DoubleArgumentType.getDouble(ctx, "half_volume")))))
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(ctx -> set(ctx, null))))))
                .then(Commands.literal("clear")
                        .requires(Permissions.require(Permissions.ADMIN_PRICES, 2))
                        .then(Commands.argument("key", ResourceLocationArgument.id())
                                .suggests(CommandSupport.KEY_SUGGESTIONS)
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(PricesCommand::clear))))
                .then(Commands.literal("reset")
                        .requires(Permissions.require(Permissions.ADMIN_PRICES, 2))
                        .then(Commands.argument("key", ResourceLocationArgument.id())
                                .suggests(CommandSupport.KEY_SUGGESTIONS)
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(PricesCommand::reset)))));
    }

    private static int get(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ConsortiumRuntime rt = runtime();
        ResourceLocation id = CommandSupport.key(ctx);
        Optional<CommandSupport.KeyTarget> target = CommandSupport.resolveKey(rt, id);
        if (target.isEmpty()) {
            ctx.getSource().sendSuccess(() -> grey("The Consortium does not buy this."), false);
            return 1;
        }
        PriceFamily f = target.get().family();
        String name = rt.prices.displayName(f.key());
        String symbol = CommonConfig.currencySymbol();
        long now = rt.now();
        StringBuilder sb = new StringBuilder(name);
        if (f.quotaOnly()) {
            sb.append(": quota only (0 ").append(symbol).append("), counts for the phase");
        } else {
            sb.append(": ").append(Money.format(rt.market.unitPriceCents(f.key(), now), symbol)).append(" per unit now (base ")
                    .append(Money.formatPlain(f.baseCents())).append(", floor ").append(Money.formatPlain(Money.floorToCents(Money.toCredits(f.baseCents()) * f.floorRatio())))
                    .append(", half volume ").append(Units.format(f.halfVolume()))
                    .append(", saturation ").append(Units.format(rt.market.saturation(f.key(), now)))
                    .append(", daily cap ").append(f.dailyCap() <= 0 ? "none" : Units.format(f.dailyCap()) + " units");
            ServerPlayer player = ctx.getSource().getPlayer();
            if (player != null) {
                Account a = rt.economy.account(player.getUUID());
                double paid = a == null ? 0 : a.paidToday(f.key(), rt.transactions.capDay(now));
                sb.append(", you: ").append(Units.format(paid)).append(" paid today");
            }
            sb.append(')');
        }
        if (!PriceFamily.NEUTRAL.equals(f.charterFamily())) {
            sb.append("; charter family ").append(f.charterFamily());
        }
        if (target.get().item() != null) {
            sb.append("; ").append(new net.minecraft.world.item.ItemStack(target.get().item()).getHoverName().getString())
                    .append(" counts ").append(Units.format(target.get().weight())).append(" unit(s)");
        }
        List<String> members = rt.prices.index().memberNames(f.key());
        if (!members.isEmpty()) {
            sb.append("; members ").append(String.join(", ", members));
        }
        PriceOverride o = rt.economy.override(f.key());
        if (o != null) {
            sb.append("; override since ").append(LocalDate.ofInstant(Instant.ofEpochMilli(o.at()), ZoneOffset.UTC))
                    .append(" by ").append(describeBy(rt, o.by())).append(": ").append(o.reason());
        }
        final String text = sb.toString();
        ctx.getSource().sendSuccess(() -> info(text), false);
        return 1;
    }

    private static String describeBy(ConsortiumRuntime rt, String by) {
        if (by == null || by.equals("console") || by.equals("datapack")) {
            return String.valueOf(by);
        }
        String raw = by.startsWith("admin:") ? by.substring(6) : by;
        try {
            Account a = rt.economy.account(java.util.UUID.fromString(raw));
            return a != null ? a.name : raw;
        } catch (IllegalArgumentException e) {
            return raw;
        }
    }

    private static int list(CommandContext<CommandSourceStack> ctx, int page) throws CommandSyntaxException {
        ConsortiumRuntime rt = runtime();
        List<PriceFamily> families = new ArrayList<>(rt.prices.families());
        int pages = Math.max(1, (families.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int p = Math.min(page, pages);
        String symbol = CommonConfig.currencySymbol();
        long now = rt.now();
        ctx.getSource().sendSuccess(() -> info("Price table, page " + p + " of " + pages + " (" + families.size() + " families, * = override)"), false);
        for (int i = (p - 1) * PAGE_SIZE; i < Math.min(families.size(), p * PAGE_SIZE); i++) {
            PriceFamily f = families.get(i);
            String row = (f.override() ? "* " : "  ") + rt.prices.displayName(f.key()) + " [" + f.key() + "]: "
                    + (f.quotaOnly() ? "quota only" : Money.format(rt.market.unitPriceCents(f.key(), now), symbol) + " (base " + Money.formatPlain(f.baseCents()) + ")");
            ctx.getSource().sendSuccess(() -> info(row), false);
        }
        if (families.isEmpty()) {
            ctx.getSource().sendSuccess(() -> grey("No family loaded: add data/<namespace>/consortium_prices/*.json to the pack."), false);
        }
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> ctx, Double halfVolume) throws CommandSyntaxException {
        ConsortiumRuntime rt = runtime();
        ResourceLocation id = CommandSupport.key(ctx);
        String reason = StringArgumentType.getString(ctx, "reason").trim();
        if (reason.isEmpty()) {
            ctx.getSource().sendFailure(fail("A reason is mandatory."));
            return 0;
        }
        long baseCents;
        try {
            baseCents = Money.parseCredits(StringArgumentType.getString(ctx, "base"));
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(fail(e.getMessage()));
            return 0;
        }
        String key = id.toString();
        PriceFamily existing = rt.prices.family(key);
        boolean newFamily = false;
        if (existing == null) {
            Optional<CommandSupport.KeyTarget> byItem = CommandSupport.resolveKey(rt, id);
            if (byItem.isPresent()) {
                ctx.getSource().sendFailure(fail(key + " belongs to family " + byItem.get().family().key() + ": set that family instead."));
                return 0;
            }
            if (BuiltInRegistries.ITEM.getOptional(id).isEmpty()) {
                ctx.getSource().sendFailure(fail(key + " is neither a family nor an item id."));
                return 0;
            }
            if (baseCents > 0 && halfVolume == null) {
                ctx.getSource().sendFailure(fail(key + " is not in the price table yet: give a half volume, e.g. /prices set " + key + " "
                        + StringArgumentType.getString(ctx, "base") + " 1000 " + reason));
                return 0;
            }
            newFamily = true;
        }
        PriceOverride previous = rt.economy.override(key);
        if (existing != null && existing.quotaOnly() && baseCents > 0 && halfVolume == null
                && (previous == null || previous.halfVolume() == null)) {
            // A quota-only family carries a placeholder half volume: promoting it to a paid family needs a real one.
            ctx.getSource().sendFailure(fail(key + " is quota only: give a half volume to make it paid, e.g. /prices set " + key + " "
                    + StringArgumentType.getString(ctx, "base") + " 1000 " + reason));
            return 0;
        }
        PriceOverride override = new PriceOverride(key, baseCents,
                halfVolume != null ? halfVolume : (previous != null ? previous.halfVolume() : null),
                previous != null ? previous.floorRatio() : null,
                previous != null ? previous.dailyCap() : null,
                CommandSupport.counterpart(ctx.getSource()), rt.now(), reason);
        rt.economy.putOverride(override);
        rt.mergePrices(CommandSupport.counterpart(ctx.getSource()), reason, false);
        PriceFamily after = rt.prices.family(key);
        String symbol = CommonConfig.currencySymbol();
        final String name = rt.prices.displayName(key);
        final boolean created = newFamily;
        ctx.getSource().sendSuccess(() -> ok(name + (created ? " is now bought at " : " set to ")
                + (after == null || after.quotaOnly() ? "quota only (0 " + symbol + ")" : Money.format(after.baseCents(), symbol) + " per unit")
                + (after != null && !after.quotaOnly() ? ", half volume " + Units.format(after.halfVolume()) : "") + "."), true);
        if (after != null && !after.quotaOnly() && Money.floorToCents(Money.toCredits(after.baseCents()) * after.floorRatio()) == 0) {
            // Rule 4.2 wants a floor above zero for paid families; two-decimal money cannot express base x floor_ratio here.
            ctx.getSource().sendSuccess(() -> grey("Warning: the price floor of " + name + " rounds to 0.00 " + symbol
                    + " (base " + Money.formatPlain(after.baseCents()) + " x floor ratio " + after.floorRatio()
                    + "): raise the base or the floor ratio, or make the family quota only."), false);
        }
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ConsortiumRuntime rt = runtime();
        String key = CommandSupport.key(ctx).toString();
        String reason = StringArgumentType.getString(ctx, "reason").trim();
        if (rt.economy.override(key) == null) {
            ctx.getSource().sendFailure(fail("No override on " + key + "."));
            return 0;
        }
        rt.economy.removeOverride(key);
        rt.mergePrices(CommandSupport.counterpart(ctx.getSource()), reason.isEmpty() ? "override cleared" : reason, false);
        final String name = rt.prices.displayName(key);
        ctx.getSource().sendSuccess(() -> ok("Override on " + name + " cleared."), true);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ConsortiumRuntime rt = runtime();
        ResourceLocation id = CommandSupport.key(ctx);
        String reason = StringArgumentType.getString(ctx, "reason").trim();
        Optional<CommandSupport.KeyTarget> target = CommandSupport.resolveKey(rt, id);
        if (target.isEmpty()) {
            ctx.getSource().sendFailure(fail("The Consortium does not buy " + id + "."));
            return 0;
        }
        String key = target.get().family().key();
        Result result = rt.transactions.marketReset(key, reason.isEmpty() ? "market reset" : reason, CommandSupport.counterpart(ctx.getSource()));
        if (result == Result.SUCCESS) {
            final String name = rt.prices.displayName(key);
            ctx.getSource().sendSuccess(() -> ok("Market of " + name + " reset."), true);
            return 1;
        }
        ctx.getSource().sendFailure(fail(result == Result.LEDGER_UNAVAILABLE ? "Ledger unavailable, nothing changed." : "Reset failed: " + result));
        return 0;
    }
}
