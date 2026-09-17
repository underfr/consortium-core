package org.consortium.core.client;

import org.consortium.core.board.BoardSnapshot;
import org.consortium.core.network.BoardSyncPayload;

import javax.annotation.Nullable;

/**
 * The client's copy of the quota board (specification v0.2, 2.3), fed by {@code BoardSync} and reset when the player
 * logs out so a previous server's board never shows. {@link #generation()} moves on at every update or reset and is
 * the cache key of the renderer. v0.3.1: the receipt instant of the last sync is kept so the event countdown
 * ({@link BoardSnapshot.Event#secondsLeft()} at publish time) runs down on the client without a resync, and the band
 * hides itself once it ran out. Client thread only.
 */
public final class ClientBoard {
    @Nullable
    private static BoardSyncPayload last;
    private static int generation;
    private static long receivedAt;

    private ClientBoard() {
    }

    public static void update(BoardSyncPayload payload) {
        last = payload;
        receivedAt = System.currentTimeMillis();
        generation++;
    }

    public static void reset() {
        last = null;
        receivedAt = 0;
        generation++;
    }

    /** The last published snapshot, or null when nothing was published (or nothing received yet). */
    @Nullable
    public static BoardSnapshot snapshot() {
        BoardSyncPayload p = last;
        return p == null ? null : p.snapshot();
    }

    /** Server-side revision of the last payload, 0 before the first one. */
    public static int revision() {
        BoardSyncPayload p = last;
        return p == null ? 0 : p.revision();
    }

    /** Changes whenever the board changes on this client: the renderer's cache stamp. */
    public static int generation() {
        return generation;
    }

    /** Milliseconds since the last sync arrived (0 before the first one). */
    public static long elapsedMs() {
        return receivedAt == 0 ? 0 : Math.max(0L, System.currentTimeMillis() - receivedAt);
    }

    /** The running event of the snapshot when it should still show (no countdown, or a countdown not yet run out), else null. */
    @Nullable
    public static BoardSnapshot.Event visibleEvent() {
        BoardSnapshot s = snapshot();
        if (s == null || s.event() == null) {
            return null;
        }
        return s.event().visible(elapsedMs()) ? s.event() : null;
    }

    /** Seconds left on the visible event's countdown, or -1 without a countdown. */
    public static long eventRemainingSeconds() {
        BoardSnapshot.Event e = visibleEvent();
        return e == null ? -1 : e.remainingSeconds(elapsedMs());
    }
}
