package org.consortium.core.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.time.DayOfWeek;

/**
 * {@code config/consortium-common.toml}: a plain local file on each side (never synced), where the admin tuning lives
 * (specification 2.4). The server's copy comes from {@code server/overlay/config/}.
 */
public final class CommonConfig {
    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.ConfigValue<String> CURRENCY_NAME;
    private static final ModConfigSpec.ConfigValue<String> CURRENCY_SYMBOL;
    private static final ModConfigSpec.BooleanValue IDENTITY_ENABLED;
    private static final ModConfigSpec.IntValue IPV6_PREFIX_BITS;
    private static final ModConfigSpec.DoubleValue SINGLE_DELIVERY_CREDITS;
    private static final ModConfigSpec.DoubleValue BALANCE_JUMP_CREDITS;
    private static final ModConfigSpec.IntValue BALANCE_JUMP_WINDOW_MINUTES;
    private static final ModConfigSpec.DoubleValue FLOOR_DUMP_MULTIPLIER;
    private static final ModConfigSpec.EnumValue<DayOfWeek> WEEKLY_REPORT_DAY;
    private static final ModConfigSpec.IntValue WEEKLY_REPORT_HOUR;
    private static final ModConfigSpec.BooleanValue SDLINK_ENABLED;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("currency");
        CURRENCY_NAME = b.comment("Display name of the currency").define("name", "Consortium Credits");
        CURRENCY_SYMBOL = b.comment("Symbol shown after amounts").define("symbol", "CC");
        b.pop();
        b.comment("Same-connection check for the starting capital").push("identity");
        IDENTITY_ENABLED = b.comment("false = every new account is paid (behind a reverse proxy every player shares one address)")
                .define("enabled", true);
        IPV6_PREFIX_BITS = b.comment("IPv6 addresses are reduced to this prefix before hashing (privacy extensions rotate the host part)")
                .defineInRange("ipv6_prefix_bits", 64, 8, 128);
        b.pop();
        b.comment("Suspicious-transaction thresholds (alert only, never blocking)").push("alerts");
        SINGLE_DELIVERY_CREDITS = b.comment("A single delivery above this many credits is reported")
                .defineInRange("single_delivery_credits", 2000.0, 0.0, 1e9);
        BALANCE_JUMP_CREDITS = b.comment("Positive balance deltas of one player above this within the window are reported")
                .defineInRange("balance_jump_credits", 5000.0, 0.0, 1e9);
        BALANCE_JUMP_WINDOW_MINUTES = b.defineInRange("balance_jump_window_minutes", 10, 1, 1440);
        FLOOR_DUMP_MULTIPLIER = b.comment("One player pushing one family past this x half_volume paid units within 60 minutes is reported")
                .defineInRange("floor_dump_multiplier", 3.0, 0.1, 1000.0);
        b.pop();
        b.comment("Weekly money-supply report (server local time)").push("monitoring");
        WEEKLY_REPORT_DAY = b.defineEnum("weekly_report_day", DayOfWeek.MONDAY);
        WEEKLY_REPORT_HOUR = b.defineInRange("weekly_report_hour", 10, 0, 23);
        b.pop();
        b.comment("Simple Discord Link bridge for alerts and reports (the mod has no Discord client of its own)").push("sdlink");
        SDLINK_ENABLED = b.define("enabled", true);
        b.pop();
        SPEC = b.build();
    }

    private CommonConfig() {
    }

    private static boolean loaded() {
        return SPEC.isLoaded();
    }

    public static String currencyName() {
        return loaded() ? CURRENCY_NAME.get() : "Consortium Credits";
    }

    public static String currencySymbol() {
        return loaded() ? CURRENCY_SYMBOL.get() : "CC";
    }

    public static boolean identityEnabled() {
        return !loaded() || IDENTITY_ENABLED.get();
    }

    public static int ipv6PrefixBits() {
        return loaded() ? IPV6_PREFIX_BITS.get() : 64;
    }

    public static double singleDeliveryCredits() {
        return loaded() ? SINGLE_DELIVERY_CREDITS.get() : 2000.0;
    }

    public static double balanceJumpCredits() {
        return loaded() ? BALANCE_JUMP_CREDITS.get() : 5000.0;
    }

    public static int balanceJumpWindowMinutes() {
        return loaded() ? BALANCE_JUMP_WINDOW_MINUTES.get() : 10;
    }

    public static double floorDumpMultiplier() {
        return loaded() ? FLOOR_DUMP_MULTIPLIER.get() : 3.0;
    }

    public static DayOfWeek weeklyReportDay() {
        return loaded() ? WEEKLY_REPORT_DAY.get() : DayOfWeek.MONDAY;
    }

    public static int weeklyReportHour() {
        return loaded() ? WEEKLY_REPORT_HOUR.get() : 10;
    }

    public static boolean sdlinkEnabled() {
        return !loaded() || SDLINK_ENABLED.get();
    }
}
