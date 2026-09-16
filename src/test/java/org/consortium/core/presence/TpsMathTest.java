package org.consortium.core.presence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TpsMathTest {
    private static final double MS_PER_TICK_20 = 50.0;

    @Test
    void meanTickTimeToMspt() {
        assertEquals(50.0, TpsMath.mspt(50_000_000L), 1e-9);
        assertEquals(2.4, TpsMath.mspt(2_400_000L), 1e-9);
        assertEquals(0.0, TpsMath.mspt(-1L), 1e-9, "a negative ring value never gives a negative time");
    }

    @Test
    void fiftyMillisecondsIsTwentyAndAHundredIsTen() {
        assertEquals(20.0, TpsMath.tps(50.0, MS_PER_TICK_20), 1e-9);
        assertEquals(10.0, TpsMath.tps(100.0, MS_PER_TICK_20), 1e-9);
        assertEquals(12.5, TpsMath.tps(80.0, MS_PER_TICK_20), 1e-9);
    }

    @Test
    void cappedAtTheTickRate() {
        assertEquals(20.0, TpsMath.tps(3.0, MS_PER_TICK_20), 1e-9, "an idle server reads 20, never more");
        assertEquals(20.0, TpsMath.tps(0.0, MS_PER_TICK_20), 1e-9);
        // /tick rate 10 = 100 ms per tick: idle reads 10
        assertEquals(10.0, TpsMath.tps(3.0, 100.0), 1e-9);
        assertEquals(10.0, TpsMath.tps(100.0, 100.0), 1e-9);
        assertEquals(5.0, TpsMath.tps(200.0, 100.0), 1e-9);
    }

    @Test
    void tpsHasOneDecimalWithADot() {
        assertEquals("20.0", TpsMath.formatTps(20.0));
        assertEquals("19.7", TpsMath.formatTps(19.74));
        assertEquals("10.0", TpsMath.formatTps(TpsMath.tps(100.0, MS_PER_TICK_20)));
        assertEquals("20.0", TpsMath.formatTps(TpsMath.tps(TpsMath.mspt(50_000_000L), MS_PER_TICK_20)));
    }

    @Test
    void msptIsRoundedToAnInteger() {
        assertEquals("2", TpsMath.formatMspt(2.4));
        assertEquals("3", TpsMath.formatMspt(2.6));
        assertEquals("3", TpsMath.formatMspt(2.5));
        assertEquals("0", TpsMath.formatMspt(0.0));
        assertEquals("50", TpsMath.formatMspt(TpsMath.mspt(50_000_000L)));
    }
}
