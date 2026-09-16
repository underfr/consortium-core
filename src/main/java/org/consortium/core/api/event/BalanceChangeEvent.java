package org.consortium.core.api.event;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

import java.util.UUID;

/**
 * Posted on {@code NeoForge.EVENT_BUS} before an API, admin or starting-capital balance change (never for a
 * delivery). Cancel it to veto the change, or adjust the outcome with {@link #setNewBalance(long)}.
 * Bean getters so KubeJS exposes the fields as properties.
 */
public class BalanceChangeEvent extends Event implements ICancellableEvent {
    private final UUID player;
    private final String name;
    private final long oldBalance;
    private final String reason;
    private final String counterpart;
    private long newBalance;

    public BalanceChangeEvent(UUID player, String name, long oldBalance, long newBalance, String reason, String counterpart) {
        this.player = player;
        this.name = name;
        this.oldBalance = oldBalance;
        this.newBalance = newBalance;
        this.reason = reason;
        this.counterpart = counterpart;
    }

    public UUID getPlayer() {
        return player;
    }

    public String getName() {
        return name;
    }

    /** Balance before the change, in cents. */
    public long getOldBalance() {
        return oldBalance;
    }

    /** Balance the change would produce, in cents. */
    public long getNewBalance() {
        return newBalance;
    }

    /** Signed delta in cents. */
    public long getDelta() {
        return newBalance - oldBalance;
    }

    /** {@code starting_capital}, the admin's reason or the API caller's reason. */
    public String getReason() {
        return reason;
    }

    /** {@code admin:<uuid>}, {@code console}, a sink id or {@code system}. */
    public String getCounterpart() {
        return counterpart;
    }

    /**
     * Overrides the resulting balance. A value below 0 throws {@link IllegalArgumentException}, which the poster
     * catches and treats as a veto.
     */
    public void setNewBalance(long cents) {
        if (cents < 0) {
            throw new IllegalArgumentException("A balance cannot be negative: " + cents);
        }
        this.newBalance = cents;
    }
}
