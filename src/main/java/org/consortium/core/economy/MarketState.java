package org.consortium.core.economy;

/** Per-family market state: saturation in units and the time it was last advanced (specification 2.1, {@code market[]}). */
public final class MarketState {
    public final String family;
    public double saturation;
    /** Epoch millis; 0 means "never traded". */
    public long updatedAt;

    public MarketState(String family, double saturation, long updatedAt) {
        this.family = family;
        this.saturation = saturation;
        this.updatedAt = updatedAt;
    }
}
