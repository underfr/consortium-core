package org.consortium.core.compat;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import org.consortium.core.ConsortiumCore;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * LuckPerms integration of the presence module (v0.3, presence 5.6). The LuckPerms API classes are only touched inside
 * {@link LuckPermsCalls}, which is loaded only when the mod is present, so this class is safe on a server or client
 * without LuckPerms; nothing here appears in a mod entry class signature. {@code net.luckperms:api} is a compile-only
 * dependency: the pinned LuckPerms 5.4.150 jar ships the API classes unshaded at its root, so they are on the runtime
 * classpath exactly when the mod is loaded.
 *
 * <p>Threading: {@code UserDataRecalculateEvent} is posted on LuckPerms' async scheduler, so {@link #subscribe} hands
 * the work to the server thread with {@code server.execute} before anything touches a player.
 */
public final class LuckPermsBridge {
    public static final String MOD_ID = "luckperms";
    /** LuckPerms meta key of the rank colour ({@code lp group engineer meta set rank.color aqua}). */
    public static final String META_RANK_COLOR = "rank.color";
    /** LuckPerms meta key of the per-group chat body style ({@code lp group staff meta set chat.style "&f"}). */
    public static final String META_CHAT_STYLE = "chat.style";

    /**
     * The rank data of one player, raw LuckPerms strings ({@code &} codes allowed). {@code known} is false when
     * LuckPerms is absent, not enabled yet or has not loaded the user (then everything else is empty and the group is
     * {@code default}).
     */
    public record Rank(String prefix, String suffix, String group, String groupDisplayName, String rankColor, String chatStyle,
                       boolean known) {
        public static final Rank NONE = new Rank("", "", "default", "default", "", "", false);
    }

    private static Boolean present;
    private static long lastFailureLog;
    /** The open {@code EventSubscription} (an {@code AutoCloseable}, kept untyped so this class never loads it). */
    private AutoCloseable subscription;

    /** True when LuckPerms is loaded; evaluated once. */
    public static boolean available() {
        if (present == null) {
            present = ModList.get().isLoaded(MOD_ID);
        }
        return present;
    }

    /** The rank of an online player, {@link Rank#NONE} without LuckPerms or while its user is not loaded. */
    public static Rank rank(ServerPlayer player) {
        if (!available()) {
            return Rank.NONE;
        }
        try {
            return LuckPermsCalls.rank(player.getUUID());
        } catch (Throwable t) {
            warn("LuckPerms rank lookup failed: " + t);
            return Rank.NONE;
        }
    }

    /** Server start: listens for rank changes; {@code onRankChanged} runs on the server thread for online players only. */
    public void subscribe(MinecraftServer server, Consumer<ServerPlayer> onRankChanged) {
        unsubscribe();
        if (!available()) {
            return;
        }
        try {
            this.subscription = LuckPermsCalls.subscribe(server, onRankChanged);
            ConsortiumCore.LOGGER.info("LuckPerms bridge installed (UserDataRecalculateEvent refreshes names and tab entries)");
        } catch (Throwable t) {
            ConsortiumCore.LOGGER.warn("LuckPerms bridge could not subscribe to rank changes ({}); ranks refresh on login and reload only", t.toString());
        }
    }

    /** Server stop: a closed subscription never fires again. */
    public void unsubscribe() {
        AutoCloseable s = this.subscription;
        this.subscription = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception e) {
                ConsortiumCore.LOGGER.warn("LuckPerms subscription close failed: {}", e.toString());
            }
        }
    }

    private static void warn(String text) {
        long now = System.currentTimeMillis();
        if (now - lastFailureLog > 60_000) {
            lastFailureLog = now;
            ConsortiumCore.LOGGER.warn(text);
        }
    }

    /** Isolated so the JVM only resolves the LuckPerms classes when this class is loaded. */
    private static final class LuckPermsCalls {
        static Rank rank(UUID uuid) {
            net.luckperms.api.LuckPerms api;
            try {
                api = net.luckperms.api.LuckPermsProvider.get();
            } catch (IllegalStateException e) {
                // Thrown before LuckPerms registered its API (server still starting).
                return Rank.NONE;
            }
            // UserManager.getUser(UUID) is null-safe; PlayerAdapter.getUser(player) would throw for an unloaded user.
            net.luckperms.api.model.user.User user = api.getUserManager().getUser(uuid);
            if (user == null) {
                return Rank.NONE;
            }
            net.luckperms.api.cacheddata.CachedMetaData meta = user.getCachedData().getMetaData();
            String group = meta.getPrimaryGroup();
            if (group == null || group.isEmpty()) {
                group = user.getPrimaryGroup();
            }
            if (group == null || group.isEmpty()) {
                group = "default";
            }
            String display = group;
            net.luckperms.api.model.group.Group g = api.getGroupManager().getGroup(group);
            if (g != null && g.getDisplayName() != null && !g.getDisplayName().isEmpty()) {
                display = g.getDisplayName();
            }
            return new Rank(orEmpty(meta.getPrefix()), orEmpty(meta.getSuffix()), group, display,
                    orEmpty(meta.getMetaValue(META_RANK_COLOR)), orEmpty(meta.getMetaValue(META_CHAT_STYLE)), true);
        }

        static AutoCloseable subscribe(MinecraftServer server, Consumer<ServerPlayer> onRankChanged) {
            net.luckperms.api.LuckPerms api = net.luckperms.api.LuckPermsProvider.get();
            return api.getEventBus().subscribe(net.luckperms.api.event.user.UserDataRecalculateEvent.class, event -> {
                UUID id = event.getUser().getUniqueId();
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(id);
                    if (player != null) {
                        try {
                            onRankChanged.accept(player);
                        } catch (Throwable t) {
                            ConsortiumCore.LOGGER.error("Rank change refresh failed for {}", player.getGameProfile().getName(), t);
                        }
                    }
                });
            });
        }

        private static String orEmpty(String s) {
            return s == null ? "" : s;
        }
    }
}
