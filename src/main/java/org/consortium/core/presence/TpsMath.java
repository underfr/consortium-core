package org.consortium.core.presence;

import java.util.Locale;

/**
 * TPS and MSPT arithmetic of the presence placeholders (v0.3, presence 5.2), the same formula as NeoForge's
 * {@code /neoforge tps}: the mean tick time over the server's 100-tick ring, and {@code tps = 1000 / max(mspt,
 * msPerTick)} so an idle server reads the tick rate (20, or the value set with {@code /tick rate}) and never above.
 * Pure: the server hands in nanoseconds and the tick rate, the placeholders get strings.
 */
public final class TpsMath {
    private static final double NANOS_PER_MILLI = 1_000_000.0;
    private static final double MILLIS_PER_SECOND = 1000.0;

    private TpsMath() {
    }

    /** Milliseconds per tick from a mean tick time in nanoseconds. */
    public static double mspt(long averageTickNanos) {
        return Math.max(0L, averageTickNanos) / NANOS_PER_MILLI;
    }

    /** Ticks per second, capped at the tick rate ({@code 1000 / msPerTick}). */
    public static double tps(double msptMillis, double msPerTick) {
        double floor = Math.max(msPerTick, 0.001);
        return MILLIS_PER_SECOND / Math.max(msptMillis, floor);
    }

    /** One decimal, dot separator: {@code 20.0}, {@code 17.3}. */
    public static String formatTps(double tps) {
        return String.format(Locale.ROOT, "%.1f", tps);
    }

    /** Rounded to the nearest integer: {@code 2.4} reads {@code 2}, {@code 2.6} reads {@code 3}. */
    public static String formatMspt(double msptMillis) {
        return Long.toString(Math.round(msptMillis));
    }
}
