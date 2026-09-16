package org.consortium.core.client;

import net.minecraft.Util;

/**
 * The client's copy of its own balance (specification 8), fed by {@code BalanceSync} and reset when the player logs
 * out so a previous server's number never shows. Display only: the server never trusts it. Client thread only.
 */
public final class ClientBalance {
    private static boolean synced;
    private static long balance;
    private static long delta;
    private static long deltaAt;
    private static String reason = "";

    private ClientBalance() {
    }

    public static void update(long newBalance, long newDelta, String newReason) {
        synced = true;
        balance = newBalance;
        reason = newReason == null ? "" : newReason;
        if (newDelta != 0) {
            delta = newDelta;
            deltaAt = Util.getMillis();
        }
    }

    public static void reset() {
        synced = false;
        balance = 0;
        delta = 0;
        deltaAt = 0;
        reason = "";
    }

    /** True once this session received its first sync from the current server. */
    public static boolean synced() {
        return synced;
    }

    public static long balance() {
        return balance;
    }

    public static long delta() {
        return delta;
    }

    /** Milliseconds since the last non-zero delta, or a large value when there was none. */
    public static long deltaAge() {
        return deltaAt == 0 ? Long.MAX_VALUE : Util.getMillis() - deltaAt;
    }

    public static String reason() {
        return reason;
    }
}
