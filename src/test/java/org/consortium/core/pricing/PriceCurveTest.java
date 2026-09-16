package org.consortium.core.pricing;

import org.consortium.core.economy.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceCurveTest {
    /** Iron of the specification table: base 1.50, H 1500, floor 0.10, T_half 6 h, cap 40. */
    private static final PriceCurve.Params IRON = new PriceCurve.Params(150, 1500, 0.10, 6.0, 40);
    private static final long HOUR = 3_600_000L;

    @Test
    void unitPriceFollowsTheHalvingRule() {
        assertEquals(1.50, PriceCurve.unitPrice(IRON, 0), 1e-9);
        assertEquals(0.75, PriceCurve.unitPrice(IRON, 1500), 1e-9);
        assertEquals(0.375, PriceCurve.unitPrice(IRON, 3000), 1e-9);
        // Floor at 10 percent of the base, whatever the saturation.
        assertEquals(0.15, PriceCurve.unitPrice(IRON, 1_000_000), 1e-9);
        assertEquals(150, PriceCurve.unitPriceCents(IRON, 0));
    }

    @Test
    void floorStartIsHalfVolumeTimesLog2OfTheInverseFloor() {
        assertEquals(1500 * Math.log(10) / Math.log(2), IRON.floorStart(), 1e-6);
        assertFalse(PriceCurve.atFloor(IRON, 4000));
        assertTrue(PriceCurve.atFloor(IRON, 5000));
    }

    @Test
    void oneFullGridOfIronPaysTheReferenceAmount() {
        double credits = PriceCurve.integral(IRON, 0, 1728);
        assertEquals(1785.33, credits, 0.5, "specification section 3 reference for 1728 iron");
        long cents = PriceCurve.integralCents(IRON, 0, 1728, 1);
        assertTrue(cents <= Money.floorToCents(credits));
    }

    @Test
    void deliveriesArePathIndependent() {
        long once = PriceCurve.integralCents(IRON, 0, 1728, 1);
        double s = 0;
        double sumCredits = 0;
        for (int i = 0; i < 27; i++) {
            sumCredits += PriceCurve.integral(IRON, s, 64);
            s += 64;
        }
        assertEquals(Money.toCredits(once), sumCredits, 0.01, "27 x 64 pays what 1 x 1728 pays within one cent");
    }

    @Test
    void integralCrossingTheFloorPricesTheFloorPartFlat() {
        double sf = IRON.floorStart();
        double above = PriceCurve.integral(IRON, 0, sf);
        double crossing = PriceCurve.integral(IRON, 0, sf + 1000);
        assertEquals(above + 1000 * 0.15, crossing, 1e-6);
        // Starting on the floor: everything flat.
        assertEquals(500 * 0.15, PriceCurve.integral(IRON, sf + 10, 500), 1e-6);
    }

    @Test
    void recoveryTableOfTheSpecification() {
        // From half price (S = H): 61 percent after 3 h, 71 after 6 h, 84 after 12 h, 95.8 after 24 h, 99.7 after 48 h.
        double[][] table = {{3, 0.61}, {6, 0.71}, {12, 0.84}, {24, 0.958}, {48, 0.997}};
        long t0 = 1_700_000_000_000L;
        for (double[] row : table) {
            double s = PriceCurve.decay(IRON, 1500, t0, t0 + (long) (row[0] * HOUR)).saturation();
            double ratio = PriceCurve.unitPrice(IRON, s) / 1.50;
            assertEquals(row[1], ratio, 0.006, "after " + row[0] + " h");
        }
    }

    @Test
    void clockClampBothWays() {
        long updatedAt = 1_000_000_000_000L;
        PriceCurve.Decayed backwards = PriceCurve.decay(IRON, 1500, updatedAt, updatedAt - HOUR);
        assertTrue(backwards.clamped());
        assertEquals(1500, backwards.saturation(), 1e-9, "a clock stepping back never grows saturation");
        PriceCurve.Decayed farForward = PriceCurve.decay(IRON, 1500, updatedAt, updatedAt + 365L * 24 * HOUR);
        assertTrue(farForward.clamped());
        PriceCurve.Decayed sevenDays = PriceCurve.decay(IRON, 1500, updatedAt, updatedAt + PriceCurve.MAX_DT_MILLIS);
        assertFalse(sevenDays.clamped());
        assertEquals(sevenDays.saturation(), farForward.saturation(), 1e-9, "a far jump decays exactly like 7 days");
        assertEquals(0, PriceCurve.decay(IRON, 0, updatedAt, updatedAt + HOUR).saturation());
    }

    @Test
    void perPlayerCapSplitsPaidAndQuotaUnits() {
        PriceCurve.CapSplit split = PriceCurve.applyCap(64, 40, 0);
        assertEquals(40, split.paidUnits(), 1e-9);
        assertEquals(24, split.quotaUnits(), 1e-9);
        PriceCurve.CapSplit later = PriceCurve.applyCap(64, 40, 30);
        assertEquals(10, later.paidUnits(), 1e-9);
        assertEquals(54, later.quotaUnits(), 1e-9);
        PriceCurve.CapSplit exhausted = PriceCurve.applyCap(64, 40, 40);
        assertEquals(0, exhausted.paidUnits(), 1e-9);
        assertEquals(64, exhausted.quotaUnits(), 1e-9);
        PriceCurve.CapSplit uncapped = PriceCurve.applyCap(64, 0, 1000);
        assertEquals(64, uncapped.paidUnits(), 1e-9);
    }

    @Test
    void quotaOnlyFamilyPaysNothing() {
        PriceCurve.Params cobble = new PriceCurve.Params(0, 1, 0.1, 6, 0);
        assertTrue(cobble.quotaOnly());
        assertEquals(0, PriceCurve.unitPrice(cobble, 0));
        assertEquals(0, PriceCurve.integralCents(cobble, 0, 10_000, 1));
        assertFalse(PriceCurve.atFloor(cobble, 1e9));
    }

    @Test
    void multipliersAreClamped() {
        assertEquals(1, PriceCurve.clampMultiplier(Double.NaN));
        assertEquals(1, PriceCurve.clampMultiplier(Double.POSITIVE_INFINITY));
        assertEquals(10, PriceCurve.clampMultiplier(500));
        assertEquals(0, PriceCurve.clampMultiplier(-3));
        assertEquals(1.15, PriceCurve.clampMultiplier(1.15));
        long base = PriceCurve.integralCents(IRON, 0, 64, 1);
        long boosted = PriceCurve.integralCents(IRON, 0, 64, 1.15);
        assertTrue(boosted > base && boosted <= Math.round(base * 1.15) + 1);
    }

    @Test
    void roundingDownNeverOverpays() {
        for (int n = 1; n < 200; n += 7) {
            double exact = PriceCurve.integral(IRON, n * 3.0, n) * 100;
            long cents = PriceCurve.integralCents(IRON, n * 3.0, n, 1);
            assertTrue(cents <= Math.floor(exact + 1e-6), "n=" + n);
        }
    }

    @Test
    void sanitizedParamsRepairNonsense() {
        PriceCurve.Params bad = new PriceCurve.Params(100, -5, 3, -1, -9).sanitized();
        assertEquals(1, bad.halfVolume());
        assertEquals(0.1, bad.floorRatio());
        assertEquals(6, bad.halfLifeHours());
        assertEquals(0, bad.dailyCap());
    }

    @Test
    void hourlyIncomeCapMatchesTheRulebookFormula() {
        assertEquals(1.5 * 1500 / (Math.E * 6), PriceCurve.maxHourlyIncome(IRON), 1e-9);
    }
}
