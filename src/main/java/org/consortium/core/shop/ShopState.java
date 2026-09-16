package org.consortium.core.shop;

/**
 * The availability of one catalogue entry for one player, as the {@code shop_catalog} payload carries it
 * (specification v0.2, 5.2). Affordability is not a state: the client compares its synced balance and the server
 * re-checks at purchase time.
 */
public enum ShopState {
    AVAILABLE,
    /** The entry's {@code stage} is not held, or Chapters locks the sold item for the player whatever the field says. */
    LOCKED_STAGE,
    /** The entry's {@code phase} is above the phase of the last quota board snapshot. */
    LOCKED_PHASE,
    /** The per-player daily limit was reached today (UTC). */
    LIMIT_REACHED;

    private static final ShopState[] VALUES = values();

    public byte id() {
        return (byte) ordinal();
    }

    /** The state of a wire byte; an unknown value reads as {@link #LOCKED_STAGE} (never as available). */
    public static ShopState fromId(byte id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : LOCKED_STAGE;
    }
}
