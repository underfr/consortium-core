package org.consortium.core.pricing;

import org.consortium.core.ConsortiumCore;
import org.consortium.core.economy.EconomyData;
import org.consortium.core.economy.MarketState;
import org.consortium.core.economy.Units;

/**
 * Market state per family on top of {@link EconomyData} (specification section 3): lazy decay on read, exact
 * integral on delivery, clock clamp with a rate-limited {@code MARKET_CLOCK} warning.
 */
public final class Market {
    /** A priced delivery of one family: what is paid, what only counts, and the market after it. */
    public record Quote(String family, double units, double paidUnits, double quotaUnits, long cents, double multiplier,
                        double saturationBefore, double saturationAfter, long unitCentsBefore) {
        /** Average cents per paid unit, 0 when nothing was paid. */
        public long averageUnitCents() {
            return paidUnits > 0 ? (long) Math.floor(cents / paidUnits) : 0;
        }
    }

    private final EconomyData data;
    private final PriceTable table;
    private long lastClockWarning;

    public Market(EconomyData data, PriceTable table) {
        this.data = data;
        this.table = table;
    }

    /** Current (decayed) saturation of a family, without mutating the state. */
    public double saturation(String family, long now) {
        MarketState s = data.marketState(family);
        if (s == null || s.saturation <= 0) {
            return 0;
        }
        PriceFamily f = table.family(family);
        PriceCurve.Params p = f != null ? f.params() : new PriceCurve.Params(0, 1, 0.1, 6, 0);
        PriceCurve.Decayed d = PriceCurve.decay(p, s.saturation, s.updatedAt, now);
        if (d.clamped()) {
            warnClock(family, s.updatedAt, now);
        }
        return d.saturation();
    }

    /** Unit price in cents right now (display). */
    public long unitPriceCents(String family, long now) {
        PriceFamily f = table.family(family);
        if (f == null) {
            return 0;
        }
        return PriceCurve.unitPriceCents(f.params(), saturation(family, now));
    }

    public boolean atFloor(String family, long now) {
        PriceFamily f = table.family(family);
        return f != null && PriceCurve.atFloor(f.params(), saturation(family, now));
    }

    /**
     * Prices {@code units} of a family for a player who already got {@code paidToday} paid units today. Pure: the
     * market is not advanced. Quota-only families pay 0 and skip the cap.
     */
    public Quote quote(String family, double units, double paidToday, double multiplier, long now) {
        PriceFamily f = table.family(family);
        double u = Units.round(units);
        if (f == null) {
            return new Quote(family, u, 0, u, 0, 1, 0, 0, 0);
        }
        PriceCurve.Params p = f.params();
        double s0 = saturation(family, now);
        long unitBefore = PriceCurve.unitPriceCents(p, s0);
        if (p.quotaOnly()) {
            return new Quote(family, u, 0, u, 0, 1, s0, s0, 0);
        }
        PriceCurve.CapSplit split = PriceCurve.applyCap(u, p.dailyCap(), paidToday);
        double paid = Units.round(split.paidUnits());
        double quota = Units.round(u - paid);
        double m = PriceCurve.clampMultiplier(multiplier);
        long cents = PriceCurve.integralCents(p, s0, paid, m);
        return new Quote(family, u, paid, quota, cents, m, s0, Units.round(s0 + paid), unitBefore);
    }

    /** Advances the market by the paid units of a quote. Server thread, after the ledger write. */
    public void advance(String family, double paidUnits, long now) {
        MarketState s = data.marketStateOrCreate(family);
        double current = saturation(family, now);
        s.saturation = Units.round(current + Math.max(0, paidUnits));
        s.updatedAt = now;
        data.touch();
    }

    /** Saturation back to 0 ({@code /prices reset}). Returns the previous decayed saturation. */
    public double reset(String family, long now) {
        double before = saturation(family, now);
        MarketState s = data.marketStateOrCreate(family);
        s.saturation = 0;
        s.updatedAt = now;
        data.touch();
        return before;
    }

    private void warnClock(String family, long updatedAt, long now) {
        if (now - lastClockWarning < 60_000 && lastClockWarning != 0) {
            return;
        }
        lastClockWarning = now;
        ConsortiumCore.LOGGER.warn("MARKET_CLOCK: family '{}' was updated at {} but the clock now reads {}; the decay window was clamped to [0, 7 days]",
                family, updatedAt, now);
    }
}
