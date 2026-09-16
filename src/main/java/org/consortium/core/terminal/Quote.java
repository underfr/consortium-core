package org.consortium.core.terminal;

import java.util.Collections;
import java.util.List;

/**
 * A priced snapshot of a terminal grid (specification 5): one line per family, the refused slots with their reason,
 * the non-blocking warnings and the total. Serialised by the network layer as {@code DeliveryQuote}.
 */
public record Quote(long nonce, List<Line> lines, List<Refusal> refused, List<Warning> warnings, long totalCents, String message) {

    /** One family of the grid. */
    public record Line(String family, String name, List<String> items, double units, double paidUnits, double quotaUnits,
                       long unitCents, long subtotalCents, int multiplierPercent, boolean quotaOnly) {
    }

    /** A slot the terminal will not take. */
    public record Refusal(int slot, String reason) {
    }

    /** A hint shown next to a line; never refuses. */
    public record Warning(String family, String text) {
    }

    /** Refusal reasons of specification 5, in evaluation order. */
    public static final String NOT_PRICED = "The Consortium does not buy this";
    public static final String MODIFIED = "Modified items are not accepted";
    public static final String ZERO_VALUE = "0.00 CC, counts for the quota";
    public static final String DAILY_CAP = "Beyond your daily cap: counts for the quota only";

    public Quote {
        lines = Collections.unmodifiableList(lines);
        refused = Collections.unmodifiableList(refused);
        warnings = Collections.unmodifiableList(warnings);
    }

    public boolean hasAccepted() {
        return !lines.isEmpty();
    }

    public Quote withMessage(String text) {
        return new Quote(nonce, lines, refused, warnings, totalCents, text);
    }
}
