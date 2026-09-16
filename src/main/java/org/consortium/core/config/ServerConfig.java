package org.consortium.core.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.consortium.core.economy.Money;
import org.consortium.core.pricing.PriceDefaults;
import org.consortium.core.terminal.CommandTemplate;

/**
 * {@code world/serverconfig/consortium-server.toml}. SERVER configs are synced to every client, so this file only
 * holds values players may know (specification 2.4). Every getter falls back to the default while the config is not
 * loaded yet (the first datapack apply runs before it).
 */
public final class ServerConfig {
    public static final ModConfigSpec SPEC;
    /** The default follow-up template, kept in a Minecraft-free class so the unit tests can render it. */
    public static final String DEFAULT_CONTRIBUTE_COMMAND = CommandTemplate.DEFAULT_CONTRIBUTE_COMMAND;

    private static final ModConfigSpec.DoubleValue STARTING_CAPITAL;
    private static final ModConfigSpec.EnumValue<MultiAccountGrant> MULTI_ACCOUNT_GRANT;
    private static final ModConfigSpec.DoubleValue DEFAULT_FLOOR_RATIO;
    private static final ModConfigSpec.DoubleValue DEFAULT_HALF_LIFE_HOURS;
    private static final ModConfigSpec.DoubleValue DAILY_CAP_MULTIPLIER;
    private static final ModConfigSpec.IntValue QUOTE_TOLERANCE_PERCENT;
    private static final ModConfigSpec.ConfigValue<String> CONTRIBUTE_COMMAND;
    private static final ModConfigSpec.BooleanValue PLACEMENT_GUARD;
    private static final ModConfigSpec.BooleanValue INSTANT_AUDIT;

    /** What happens when a new account's connection already received the starting capital. */
    public enum MultiAccountGrant {
        DENY,
        GRANT_AND_FLAG
    }

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Starting capital and the same-connection check").push("economy");
        STARTING_CAPITAL = b.comment("Credits paid to a new account at its first login (at most two decimals)")
                .defineInRange("starting_capital", 50.00, 0.0, 1_000_000.0);
        MULTI_ACCOUNT_GRANT = b.comment("DENY: a second account on a known connection opens at 0 CC; GRANT_AND_FLAG: it is paid and ops get an alert")
                .defineEnum("multi_account_grant", MultiAccountGrant.DENY);
        b.pop();
        b.comment("Defaults for price families that do not set the value themselves").push("pricing");
        DEFAULT_FLOOR_RATIO = b.comment("Price floor as a fraction of the base price (0.01 to 1)")
                .defineInRange("default_floor_ratio", 0.10, 0.01, 1.0);
        DEFAULT_HALF_LIFE_HOURS = b.comment("Hours for the market to forget half of what it absorbed")
                .defineInRange("default_half_life_hours", 6.0, 0.01, 10_000.0);
        DAILY_CAP_MULTIPLIER = b.comment("Per-player daily paid units = this x half_volume unless the family sets daily_cap; 0 = uncapped")
                .defineInRange("daily_cap_multiplier", 4.0, 0.0, 1_000.0);
        b.pop();
        b.comment("Delivery terminal").push("terminal");
        QUOTE_TOLERANCE_PERCENT = b.comment("A delivery executes when its recomputed total is within this percentage below the quoted total")
                .defineInRange("quote_tolerance_percent", 5, 0, 100);
        CONTRIBUTE_COMMAND = b.comment("Console command run once per delivered item id right after a delivery is committed, so the",
                        "KubeJS phase engine of the pack records the quota progress (the mod keeps no phase counter).",
                        "Placeholders: {item} registry id, {count} integer, {player} name, {uuid}, {tx}. Empty = disabled.",
                        "Output is suppressed; failures are logged once per minute and never affect the delivery.")
                .define("contribute_command", DEFAULT_CONTRIBUTE_COMMAND);
        b.pop();
        b.comment("Chapters guards (v0.2 section 3): both do nothing without the Chapters mod").push("guards");
        PLACEMENT_GUARD = b.comment("Refuse placing a block whose item Chapters locks for the placer's team. A fake player (deployer)",
                        "may place a gated block only when one of its stages is consortium:phase_k with k at most the",
                        "phase of the last quota board snapshot (every gated block is locked before the first publish).")
                .define("placement_guard", true);
        INSTANT_AUDIT = b.comment("Drop a locked item the moment it lands in a player's inventory (same tick as the click or",
                        "the give) instead of waiting for Chapters' one-second sweep.")
                .define("instant_audit", true);
        b.pop();
        SPEC = b.build();
    }

    private ServerConfig() {
    }

    private static boolean loaded() {
        return SPEC.isLoaded();
    }

    public static long startingCapitalCents() {
        return Money.floorToCents(loaded() ? STARTING_CAPITAL.get() : 50.0);
    }

    public static MultiAccountGrant multiAccountGrant() {
        return loaded() ? MULTI_ACCOUNT_GRANT.get() : MultiAccountGrant.DENY;
    }

    public static PriceDefaults priceDefaults() {
        if (!loaded()) {
            return PriceDefaults.FALLBACK;
        }
        return new PriceDefaults(DEFAULT_FLOOR_RATIO.get(), DEFAULT_HALF_LIFE_HOURS.get(), DAILY_CAP_MULTIPLIER.get());
    }

    public static int quoteTolerancePercent() {
        return loaded() ? QUOTE_TOLERANCE_PERCENT.get() : 5;
    }

    /** The follow-up command template (see {@link org.consortium.core.terminal.ContributeHook}). */
    public static String contributeCommand() {
        return loaded() ? CONTRIBUTE_COMMAND.get() : DEFAULT_CONTRIBUTE_COMMAND;
    }

    /** Guard 1 of v0.2 section 3 (needs Chapters). */
    public static boolean placementGuard() {
        return !loaded() || PLACEMENT_GUARD.get();
    }

    /** Guard 2 of v0.2 section 3 (needs Chapters). */
    public static boolean instantAudit() {
        return !loaded() || INSTANT_AUDIT.get();
    }
}
