package org.consortium.core.board;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.ConsortiumRuntime;
import org.consortium.core.network.BoardSyncPayload;
import org.consortium.core.network.ConsortiumNetwork;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server's quota board (specification v0.2, 2.4 and 2.5): the last accepted {@link BoardSnapshot}, its saved
 * copy ({@link BoardData}) and the sync to the clients. The mod keeps no phase state of its own: the KubeJS engine
 * publishes what the boards display through {@code ConsortiumAPI.publishBoard} and this class only validates,
 * stores and forwards it.
 *
 * <p>Boot order: the engine's {@code ServerEvents.loaded} fires at {@code ServerStartingEvent}, before the mod's
 * runtime exists, so a publish made then is kept in the static {@link #pending} snapshot and folded in by
 * {@link #boot(long)} over the snapshot loaded from disk (it is newer). {@code ServerStoppedEvent} clears it.
 * Server thread only, apart from the volatile reads of the static accessors.
 */
public final class BoardState {
    /** How often the same refusal reason is logged, in milliseconds. */
    private static final long REFUSAL_WARN_INTERVAL_MS = 60_000L;
    private static final int WARNED_CAP = 512;

    /** Registry-backed icon check: the canonical id of a registered item, or null. */
    public static final BoardJson.IconResolver ICONS = icon -> {
        ResourceLocation id = ResourceLocation.tryParse(icon);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return null;
        }
        return id.toString();
    };

    private static volatile BoardSnapshot pending;
    private static volatile long lastRefusalWarnAt;
    /** Soft-failure messages already logged this session (one line per distinct message). */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private final MinecraftServer server;
    private final BoardData data;
    private BoardSnapshot current;
    private String canonical = "";

    public BoardState(MinecraftServer server) {
        this.server = server;
        this.data = BoardData.get(server);
    }

    // ---- static entry points (the API, the guards) ----

    /**
     * {@code ConsortiumAPI.publishBoard}: validates the payload, stores it and syncs the players. Before the runtime
     * exists the snapshot is kept for {@link #boot(long)}. Returns true when accepted.
     */
    public static boolean publish(String json) {
        ConsortiumRuntime rt = ConsortiumRuntime.get();
        if (rt != null) {
            return rt.board.accept(json, rt.now());
        }
        BoardJson.Result result = BoardJson.parse(json, ICONS, BoardState::warnOnce);
        if (!result.accepted()) {
            warnRefusal(result.refusal());
            return false;
        }
        pending = result.snapshot();
        return true;
    }

    /** Phase of the last accepted snapshot, 0 before the first publish. */
    public static int currentPhase() {
        ConsortiumRuntime rt = ConsortiumRuntime.get();
        if (rt != null) {
            BoardSnapshot s = rt.board.current;
            return s == null ? 0 : s.phase();
        }
        BoardSnapshot p = pending;
        return p == null ? 0 : p.phase();
    }

    /** {@code ServerStoppedEvent}: a snapshot published during a session never leaks into the next one. */
    public static void clearPending() {
        pending = null;
        WARNED.clear();
    }

    // ---- instance ----

    /**
     * Boot order of 2.5: load the saved snapshot, fold the pending one over it (revision + 1, published_at = now),
     * clear the pending slot, broadcast to the players already online.
     */
    public void boot(long now) {
        String saved = data.json();
        if (!saved.isEmpty()) {
            BoardJson.Result result = BoardJson.parse(saved, ICONS, BoardState::warnOnce);
            if (result.accepted()) {
                current = result.snapshot();
                canonical = current.toJson();
            } else {
                ConsortiumCore.LOGGER.warn("Saved quota board ignored ({}): boards show nothing until the engine publishes", result.refusal());
            }
        }
        BoardSnapshot p = pending;
        pending = null;
        if (p != null) {
            store(p, now);
        }
        ConsortiumCore.LOGGER.info("Quota board: revision {}, {}", data.revision(),
                current == null ? "no snapshot" : "phase " + current.phase() + " with " + current.lines().size() + " line(s)");
        broadcast();
    }

    /** The runtime path of {@link #publish(String)}. */
    public boolean accept(String json, long now) {
        BoardJson.Result result = BoardJson.parse(json, ICONS, BoardState::warnOnce);
        if (!result.accepted()) {
            warnRefusal(result.refusal());
            return false;
        }
        if (store(result.snapshot(), now)) {
            broadcast();
        }
        return true;
    }

    /** Stores the snapshot when its canonical form differs from the current one; returns true when it changed. */
    private boolean store(BoardSnapshot snapshot, long now) {
        String json = snapshot.toJson();
        if (json.equals(canonical)) {
            return false;
        }
        current = snapshot;
        canonical = json;
        data.store(json, now);
        return true;
    }

    /** The last accepted snapshot, or null. */
    public BoardSnapshot current() {
        return current;
    }

    public int revision() {
        return data.revision();
    }

    public long publishedAt() {
        return data.publishedAt();
    }

    public BoardSyncPayload payload() {
        return new BoardSyncPayload(data.revision(), current);
    }

    /** Login sync: the current board, or an explicit "nothing published" so a client from another server resets. */
    public void sendTo(ServerPlayer player) {
        ConsortiumNetwork.sendBoard(player, payload());
    }

    private void broadcast() {
        BoardSyncPayload payload = payload();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ConsortiumNetwork.sendBoard(player, payload);
        }
    }

    // ---- logging ----

    private static void warnOnce(String message) {
        if (WARNED.size() > WARNED_CAP) {
            WARNED.clear();
        }
        if (WARNED.add(message)) {
            ConsortiumCore.LOGGER.warn("Quota board: {}", message);
        }
    }

    private static void warnRefusal(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastRefusalWarnAt < REFUSAL_WARN_INTERVAL_MS) {
            return;
        }
        lastRefusalWarnAt = now;
        ConsortiumCore.LOGGER.warn("Quota board publish refused ({}); the previous snapshot stays", reason);
    }
}
