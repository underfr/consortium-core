package org.consortium.core.api;

/** Outcome of a money operation of {@link ConsortiumAPI} and of the admin commands. */
public enum Result {
    SUCCESS,
    INSUFFICIENT_FUNDS,
    /** A {@code BalanceChangeEvent} listener cancelled the change (or threw). */
    VETOED,
    UNKNOWN_PLAYER,
    /** Zero, negative, non-finite or overflowing amount. */
    INVALID_AMOUNT,
    /** The ledger could not be written: nothing changed. */
    LEDGER_UNAVAILABLE;

    public boolean ok() {
        return this == SUCCESS;
    }
}
