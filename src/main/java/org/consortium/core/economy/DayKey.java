package org.consortium.core.economy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * The day key of the per-player daily caps (v0.3.1, PRICE_TABLE 7 and 9, SHOP 6): one function for the family daily
 * cap ({@link Account.DailyPaid}) and the shop {@code daily_limit} ({@link Account.DailyBought}), keyed by the
 * {@code [market] day_boundary_hour} of the server config in the server's zone, so both reset at 06:00 server time
 * like the engine's season day. Pure: the zone and the hour are arguments, the unit test passes fixed ones. The
 * ledger files and the supply days keep the UTC calendar day ({@link Transactions#utcDayOf}).
 */
public final class DayKey {
    public static final int DEFAULT_BOUNDARY_HOUR = 6;

    private DayKey() {
    }

    /**
     * {@code YYYY-MM-DD} of the "cap day" an instant belongs to: the calendar day in {@code zone} once
     * {@code boundaryHour} hours are subtracted, so 05:59 belongs to the previous day and 06:00 starts a new one.
     */
    public static String of(long epochMs, ZoneId zone, int boundaryHour) {
        int hour = Math.max(0, Math.min(23, boundaryHour));
        LocalDateTime local = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone).minusHours(hour);
        return local.toLocalDate().toString();
    }

    /** {@code "06:00"} for the refusal texts. */
    public static String boundaryText(int boundaryHour) {
        int hour = Math.max(0, Math.min(23, boundaryHour));
        return (hour < 10 ? "0" : "") + hour + ":00";
    }
}
