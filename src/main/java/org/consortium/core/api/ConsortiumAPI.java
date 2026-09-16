package org.consortium.core.api;

import net.minecraft.world.item.Item;
import org.consortium.core.ConsortiumRuntime;
import org.consortium.core.config.CommonConfig;
import org.consortium.core.economy.Account;
import org.consortium.core.economy.Money;
import org.consortium.core.economy.Units;
import org.consortium.core.pricing.FamilyIndex;
import org.consortium.core.pricing.Market;
import org.consortium.core.pricing.PriceFamily;

import java.util.Optional;
import java.util.UUID;

/**
 * The Java and KubeJS entry point (specification 9): static, server thread only, UUID based, no client classes.
 * Bound in server scripts as {@code ConsortiumCore} through {@code kubejs.bindings.txt} (the pack's phase engine
 * keeps its own script-level {@code Consortium} object). Money methods write the ledger and sync the HUD; every one
 * returns a {@link Result} and never throws for a bad amount. Phases, quotas and stages are not here: the KubeJS
 * engine owns them and learns about deliveries through the {@code contribute_command} follow-up (specification 5).
 */
public final class ConsortiumAPI {
    private ConsortiumAPI() {
    }

    /** True while a server runs and its ledger booted. */
    public static boolean ready() {
        return ConsortiumRuntime.ready();
    }

    private static ConsortiumRuntime rt() {
        return ConsortiumRuntime.get();
    }

    // ---- money ----

    /** Balance in cents, 0 for an unknown player. */
    public static long balance(UUID player) {
        ConsortiumRuntime rt = rt();
        if (rt == null) {
            return 0;
        }
        Account a = rt.economy.account(player);
        return a == null ? 0 : a.balance;
    }

    public static boolean has(UUID player, long cents) {
        return cents <= 0 || balance(player) >= cents;
    }

    /** Credits earned (rank and leaderboard metric), in cents. */
    public static long rankCredit(UUID player) {
        ConsortiumRuntime rt = rt();
        if (rt == null) {
            return 0;
        }
        Account a = rt.economy.account(player);
        return a == null ? 0 : a.rankCredit;
    }

    /** Cents paid by the terminal over the account's life. */
    public static long lifetimeDelivered(UUID player) {
        ConsortiumRuntime rt = rt();
        if (rt == null) {
            return 0;
        }
        Account a = rt.economy.account(player);
        return a == null ? 0 : a.lifetimeDelivered;
    }

    /** Credits a player (an unknown uuid gets an account). Ledger {@code API_CREDIT}. */
    public static Result credit(UUID player, long cents, String reason) {
        ConsortiumRuntime rt = rt();
        if (rt == null) {
            return Result.LEDGER_UNAVAILABLE;
        }
        return rt.transactions.apiCredit(player, cents, reason == null ? "api" : reason).result();
    }

    /** Debits a money sink; never overdrafts. Ledger {@code API_DEBIT} with {@code sinkId} as counterpart. */
    public static Result debit(UUID player, long cents, String sinkId) {
        ConsortiumRuntime rt = rt();
        if (rt == null) {
            return Result.LEDGER_UNAVAILABLE;
        }
        return rt.transactions.apiDebit(player, cents, sinkId == null ? "api" : sinkId).result();
    }

    /** Adds to the rank metric only (the Gap Contract's 50 percent later). Ledger {@code RANK_CREDIT}. */
    public static Result addRankCredit(UUID player, long cents, String reason) {
        ConsortiumRuntime rt = rt();
        if (rt == null) {
            return Result.LEDGER_UNAVAILABLE;
        }
        return rt.transactions.rankCredit(player, cents, reason == null ? "api" : reason).result();
    }

    // ---- market ----

    public static boolean isPriced(Item item) {
        ConsortiumRuntime rt = rt();
        return rt != null && rt.prices.isPriced(item);
    }

    public static Optional<String> familyOf(Item item) {
        ConsortiumRuntime rt = rt();
        return rt == null ? Optional.empty() : rt.prices.familyOf(item);
    }

    /** Weight of an item in family units, 0 when not priced. */
    public static double weightOf(Item item) {
        ConsortiumRuntime rt = rt();
        return rt == null ? 0 : rt.prices.entryOf(item).map(FamilyIndex.Entry::weight).orElse(0.0);
    }

    /** Current unit price of a family in cents (0 for unknown or quota-only). */
    public static long unitPrice(String key) {
        ConsortiumRuntime rt = rt();
        return rt == null ? 0 : rt.market.unitPriceCents(key, rt.now());
    }

    /** What {@code units} of a family would pay right now in cents, ignoring the per-player daily cap. */
    public static long quoteCents(String key, double units) {
        ConsortiumRuntime rt = rt();
        if (rt == null) {
            return 0;
        }
        Market.Quote q = rt.market.quote(key, units, 0, 1, rt.now());
        return q.cents();
    }

    public static Optional<PriceView> price(String key) {
        ConsortiumRuntime rt = rt();
        if (rt == null) {
            return Optional.empty();
        }
        PriceFamily f = rt.prices.family(key);
        if (f == null) {
            return Optional.empty();
        }
        long now = rt.now();
        return Optional.of(new PriceView(f.key(), rt.prices.displayName(f.key()), f.baseCents(), f.halfVolume(), f.floorRatio(),
                f.dailyCap(), Units.round(rt.market.saturation(f.key(), now)), rt.market.unitPriceCents(f.key(), now),
                f.quotaOnly(), f.override(), rt.prices.index().memberNames(f.key())));
    }

    // ---- misc ----

    /** {@code 123456} cents to {@code "1,234.56 CC"} (symbol from the common config). */
    public static String format(long cents) {
        return Money.format(cents, CommonConfig.currencySymbol());
    }

    /** Parses {@code "12.50"} into cents; throws {@link IllegalArgumentException} on bad input. */
    public static long parseCredits(String text) {
        return Money.parseCredits(text);
    }

    /** Broadcast with the gold prefix (players, console, Discord relay). */
    public static void announce(String text) {
        ConsortiumRuntime rt = rt();
        if (rt != null && text != null) {
            rt.notifier.announce(text);
        }
    }
}
