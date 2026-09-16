package org.consortium.core.client;

import org.consortium.core.board.BoardSnapshot;
import org.consortium.core.network.BoardSyncPayload;

import javax.annotation.Nullable;

/**
 * The client's copy of the quota board (specification v0.2, 2.3), fed by {@code BoardSync} and reset when the player
 * logs out so a previous server's board never shows. {@link #generation()} moves on at every update or reset and is
 * the cache key of the renderer. Client thread only.
 */
public final class ClientBoard {
    @Nullable
    private static BoardSyncPayload last;
    private static int generation;

    private ClientBoard() {
    }

    public static void update(BoardSyncPayload payload) {
        last = payload;
        generation++;
    }

    public static void reset() {
        last = null;
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
}
