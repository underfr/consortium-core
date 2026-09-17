package org.consortium.core.economy;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** The 06:00 market day of v0.3.1 (PRICE_TABLE 7): a delivery at 05:59 and one at 06:01 land on different cap days. */
class DayKeyTest {
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private static long at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(PARIS).toInstant().toEpochMilli();
    }

    @Test
    void boundaryAtSixServerTime() {
        String before = DayKey.of(at(2026, 9, 17, 5, 59), PARIS, 6);
        String after = DayKey.of(at(2026, 9, 17, 6, 1), PARIS, 6);
        assertEquals("2026-09-16", before);
        assertEquals("2026-09-17", after);
        assertNotEquals(before, after);
        // The evening and the small hours of the next morning share the cap day.
        assertEquals("2026-09-17", DayKey.of(at(2026, 9, 17, 23, 30), PARIS, 6));
        assertEquals("2026-09-17", DayKey.of(at(2026, 9, 18, 2, 5), PARIS, 6));
        assertEquals("2026-09-17", DayKey.of(at(2026, 9, 17, 6, 0), PARIS, 6));
        // Hour 0 is the plain calendar day; the hour is clamped to 0..23.
        assertEquals("2026-09-17", DayKey.of(at(2026, 9, 17, 0, 0), PARIS, 0));
        assertEquals("2026-09-16", DayKey.of(at(2026, 9, 17, 5, 59), PARIS, 99));
        assertEquals("06:00", DayKey.boundaryText(6));
        assertEquals("00:00", DayKey.boundaryText(0));
        assertEquals("23:00", DayKey.boundaryText(23));
        assertEquals("23:00", DayKey.boundaryText(40));
        assertEquals(6, DayKey.DEFAULT_BOUNDARY_HOUR);
    }

    @Test
    void utcAndCapDaysDifferAroundTheBoundary() {
        // 05:30 Paris on the 17th is 03:30 UTC on the 17th: the ledger day is the 17th, the cap day the 16th.
        long t = at(2026, 9, 17, 5, 30);
        assertEquals("2026-09-17", java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(t), java.time.ZoneOffset.UTC).toString());
        assertEquals("2026-09-16", DayKey.of(t, PARIS, 6));
    }
}
