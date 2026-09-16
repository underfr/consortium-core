package org.consortium.core.compat;

import org.consortium.core.monitoring.Reports;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardCountersTest {
    @Test
    void countersFormatTheWeeklyReportLineAndReset() {
        GuardCounters counters = new GuardCounters();
        assertEquals("Guards since boot: 0 placements refused, 0 instant audits", counters.summary());
        counters.placementRefused();
        counters.placementRefused();
        counters.auditTriggered();
        assertEquals(2, counters.placementsRefused());
        assertEquals(1, counters.audits());
        assertEquals("Guards since boot: 2 placements refused, 1 instant audits", counters.summary());
        counters.reset();
        assertEquals(0, counters.placementsRefused());
        assertEquals(0, counters.audits());
    }

    @Test
    void appendedLineEndsTheReportAndStaysUnderTheDiscordLimit() {
        String report = "Weekly report 2026-09-21 (UTC)\nSupply: 1.00 CC now";
        String line = new GuardCounters().summary();
        String joined = Reports.appendLine(report, line);
        assertTrue(joined.endsWith("\n" + line));
        assertEquals(report, Reports.appendLine(report, ""));
        assertEquals(report, Reports.appendLine(report, null));
        String huge = Reports.appendLine("x".repeat(1989), line);
        assertTrue(huge.length() <= 1990);
        assertTrue(huge.endsWith("\n..."));
    }
}
