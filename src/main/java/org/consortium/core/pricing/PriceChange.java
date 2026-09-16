package org.consortium.core.pricing;

/**
 * One announced change of the merged table (specification 2.3): the old values (null when the family is new) and the
 * new values (null when the family was removed from the table).
 */
public record PriceChange(String family, PriceFamily before, PriceFamily after, String by, String reason) {
    public boolean added() {
        return before == null && after != null;
    }

    public boolean removed() {
        return after == null;
    }
}
