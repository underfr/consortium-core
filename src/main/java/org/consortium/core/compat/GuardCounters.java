package org.consortium.core.compat;

/**
 * The two in-memory counters of the Chapters guards (specification v0.2, section 3): placements refused by guard 1
 * and instant audits triggered by guard 2, both since the server booted. Neither guard logs per event; the counters
 * become one line of the weekly report through {@link #summary()}. Server thread only (the guards and the report
 * both run there), reset when the server stops.
 */
public final class GuardCounters {
    private long placementsRefused;
    private long audits;

    public void placementRefused() {
        placementsRefused++;
    }

    public void auditTriggered() {
        audits++;
    }

    public long placementsRefused() {
        return placementsRefused;
    }

    public long audits() {
        return audits;
    }

    public void reset() {
        placementsRefused = 0;
        audits = 0;
    }

    /** The weekly report line: {@code Guards since boot: N placements refused, M instant audits}. */
    public String summary() {
        return "Guards since boot: " + placementsRefused + " placements refused, " + audits + " instant audits";
    }
}
