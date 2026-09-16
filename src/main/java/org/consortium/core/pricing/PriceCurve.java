package org.consortium.core.pricing;

import org.consortium.core.economy.Money;

/**
 * The degressive price curve of specification section 3, pure functions over doubles so the unit tests cover them.
 *
 * <pre>
 * price(S)   = base x max(floor_ratio, 2^(-S / H))                      H = half_volume, S = saturation in units
 * decay      = S(t) = S0 x 2^(-dt / T_half)                              dt clamped to [0, 7 days]
 * delivery n = exact integral of price over [S0, S0 + n], the floor part priced flat
 * </pre>
 */
public final class PriceCurve {
    private static final double LN2 = Math.log(2);
    /** Clock clamp: a jump beyond this is treated as exactly this (and logged by the caller). */
    public static final long MAX_DT_MILLIS = 7L * 24 * 3600 * 1000;
    public static final double MIN_MULTIPLIER = 0;
    public static final double MAX_MULTIPLIER = 10;

    /** The four numbers that define a family's curve. Cents for the base, units for the volumes. */
    public record Params(long baseCents, double halfVolume, double floorRatio, double halfLifeHours, double dailyCap) {
        public boolean quotaOnly() {
            return baseCents <= 0;
        }

        /** Saturation at which the price reaches the floor: {@code H x log2(1 / floor_ratio)}. */
        public double floorStart() {
            if (floorRatio >= 1) {
                return 0;
            }
            return halfVolume * (Math.log(1 / floorRatio) / LN2);
        }

        /** Sanity: the curve is only evaluated with a positive half volume and a floor in (0, 1]. */
        public Params sanitized() {
            double h = halfVolume > 0 && Double.isFinite(halfVolume) ? halfVolume : 1;
            double f = floorRatio > 0 && floorRatio <= 1 && Double.isFinite(floorRatio) ? floorRatio : 0.1;
            double t = halfLifeHours > 0 && Double.isFinite(halfLifeHours) ? halfLifeHours : 6;
            double c = dailyCap >= 0 && Double.isFinite(dailyCap) ? dailyCap : 0;
            return new Params(Math.max(0, baseCents), h, f, t, c);
        }
    }

    /** Result of a decay step: the new saturation and whether the clock clamp fired. */
    public record Decayed(double saturation, boolean clamped) {
    }

    /** Split of a delivery into paid units and quota-only units (per-player daily cap). */
    public record CapSplit(double paidUnits, double quotaUnits) {
    }

    private PriceCurve() {
    }

    /** Unit price in credits (double) at saturation {@code s}. */
    public static double unitPrice(Params p, double s) {
        if (p.quotaOnly()) {
            return 0;
        }
        double ratio = Math.max(p.floorRatio(), Math.pow(2, -Math.max(0, s) / p.halfVolume()));
        return Money.toCredits(p.baseCents()) * ratio;
    }

    /** Unit price in cents, rounded down (display only; deliveries use the integral). */
    public static long unitPriceCents(Params p, double s) {
        return Money.floorToCents(unitPrice(p, s));
    }

    /** True when the family currently sits on its floor. */
    public static boolean atFloor(Params p, double s) {
        return !p.quotaOnly() && s >= p.floorStart() - 1e-9;
    }

    /**
     * Lazy decay from {@code updatedAt} to {@code now}. {@code dt} is clamped to {@code [0, 7 days]}: a clock stepping
     * back would otherwise grow saturation, a clock stepping far forward would reset every market at once.
     */
    public static Decayed decay(Params p, double s0, long updatedAt, long now) {
        if (s0 <= 0) {
            return new Decayed(0, false);
        }
        if (updatedAt <= 0) {
            return new Decayed(s0, false);
        }
        long dt = now - updatedAt;
        boolean clamped = false;
        if (dt < 0) {
            dt = 0;
            clamped = true;
        } else if (dt > MAX_DT_MILLIS) {
            dt = MAX_DT_MILLIS;
            clamped = true;
        }
        double hours = dt / 3_600_000.0;
        double s = s0 * Math.pow(2, -hours / p.halfLifeHours());
        if (s < 1e-6) {
            s = 0;
        }
        return new Decayed(s, clamped);
    }

    /**
     * Exact integral of the price over {@code [s0, s0 + n]} in credits (double). Path independent: splitting a
     * delivery never changes the total. The part on the floor is priced flat at {@code base x floor_ratio}.
     */
    public static double integral(Params p, double s0, double n) {
        if (p.quotaOnly() || n <= 0) {
            return 0;
        }
        double base = Money.toCredits(p.baseCents());
        double h = p.halfVolume();
        double sf = p.floorStart();
        double start = Math.max(0, s0);
        double end = start + n;
        double curveEnd = Math.min(end, sf);
        double above = 0;
        if (curveEnd > start) {
            above = base * (h / LN2) * (Math.pow(2, -start / h) - Math.pow(2, -curveEnd / h));
        }
        double floorPart = base * p.floorRatio() * Math.max(0, end - Math.max(start, sf));
        return above + floorPart;
    }

    /** {@link #integral} times a clamped multiplier, rounded down to cents. */
    public static long integralCents(Params p, double s0, double n, double multiplier) {
        return Money.floorToCents(integral(p, s0, n) * clampMultiplier(multiplier));
    }

    /** Multipliers from quote listeners are validated finite and clamped to {@code [0, 10]}. */
    public static double clampMultiplier(double m) {
        if (Double.isNaN(m) || Double.isInfinite(m)) {
            return 1;
        }
        return Math.max(MIN_MULTIPLIER, Math.min(MAX_MULTIPLIER, m));
    }

    /**
     * Per-player daily cap: {@code paid = min(units, max(0, cap - paidToday))}, the rest counts for the quota only.
     * A cap of 0 means uncapped.
     */
    public static CapSplit applyCap(double units, double cap, double paidToday) {
        if (units <= 0) {
            return new CapSplit(0, 0);
        }
        if (cap <= 0) {
            return new CapSplit(units, 0);
        }
        double room = Math.max(0, cap - paidToday);
        double paid = Math.min(units, room);
        return new CapSplit(paid, units - paid);
    }

    /** Continuous-selling income cap of the rulebook: {@code base x H / (e x T_half)} credits per hour. */
    public static double maxHourlyIncome(Params p) {
        if (p.quotaOnly()) {
            return 0;
        }
        return Money.toCredits(p.baseCents()) * p.halfVolume() / (Math.E * p.halfLifeHours());
    }
}
