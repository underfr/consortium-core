package org.consortium.core.presence;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.compat.LuckPermsBridge;
import org.consortium.core.config.ServerConfig;
import org.consortium.core.presence.LegacyText.Span;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The presence module's session state (v0.3, presence 5.8): the {@link PresenceFormats} snapshot, the per-player last
 * header and footer strings, the last MOTD, the LuckPerms subscription and the login refresh queue. Created in
 * {@code ServerStartedEvent}, torn down in {@code ServerStoppedEvent}, server thread only except {@link #get()} and
 * {@link #markConfigChanged()}. The hooks and the ticker are thin: every render goes through here.
 *
 * <p>Rendering pipeline: {@link Placeholders#expand} with a {@link PresenceContext}, {@link LegacyText#parse} on the
 * result (so {@code &} codes inside a LuckPerms prefix are honoured), then {@link SpanComponents} for chat and tab
 * components or {@link MotdText} for the server list string. The expanded strings are also the cache keys: a header
 * whose text did not change is never re-sent.
 */
public final class PresenceService {
    /** Ticks after login at which a player whose rank was missing at format time is refreshed (5.4). */
    public static final int LOGIN_REFRESH_DELAY_TICKS = 20;

    /** A rendered header and footer: the expanded strings (cache keys, preview) and the components sent. */
    public record HeaderFooter(String headerText, String footerText, MutableComponent header, MutableComponent footer) {
    }

    private static volatile PresenceService current;

    private final MinecraftServer server;
    private final LuckPermsBridge luckPerms = new LuckPermsBridge();
    private final Map<UUID, String> lastHeaderFooter = new HashMap<>();
    private final Set<UUID> rankMissing = new HashSet<>();
    private final Map<UUID, Integer> pendingRefresh = new HashMap<>();
    private volatile PresenceFormats formats;
    private volatile boolean configChanged;
    /** The server.properties MOTD found at start, restored when the MOTD feature is switched off by a reload. */
    private final String originalMotd;
    private String lastMotd;
    private boolean warnedHex;

    private PresenceService(MinecraftServer server) {
        this.server = server;
        this.formats = ServerConfig.presence();
        this.originalMotd = server.getMotd() == null ? "" : server.getMotd();
    }

    /** The active service, or null while no server runs. */
    public static PresenceService get() {
        return current;
    }

    /** {@code ServerStartedEvent}: snapshot the config, subscribe to LuckPerms, set the first MOTD. */
    public static PresenceService start(MinecraftServer server) {
        PresenceService svc = new PresenceService(server);
        current = svc;
        svc.luckPerms.subscribe(server, svc::onRankChanged);
        svc.updateMotd();
        ConsortiumCore.LOGGER.info("Presence module {}: tab refresh every {} ticks, MOTD {} every {} ticks, luckperms={}, vmod={}",
                svc.formats.enabled() ? "on" : "off", svc.formats.tabRefreshTicks(), svc.formats.motdEnabled() ? "on" : "off",
                svc.formats.motdRefreshTicks(), LuckPermsBridge.available(), org.consortium.core.compat.VanishBridge.available());
        return svc;
    }

    /** {@code ServerStoppedEvent}. */
    public static void stop() {
        PresenceService svc = current;
        current = null;
        if (svc != null) {
            svc.luckPerms.unsubscribe();
            svc.lastHeaderFooter.clear();
            svc.pendingRefresh.clear();
            svc.rankMissing.clear();
        }
    }

    public MinecraftServer server() {
        return server;
    }

    public PresenceFormats formats() {
        return formats;
    }

    public boolean enabled() {
        return formats.enabled();
    }

    // ---- config ----

    /** Any thread ({@code ModConfigEvent.Reloading} arrives from the file watcher): the next tick reloads. */
    public void markConfigChanged() {
        configChanged = true;
    }

    /**
     * Re-reads the {@code [presence]} values, clears the header and MOTD caches, refreshes every online player's
     * display name, tab name and header, rebuilds the MOTD. Returns the number of players refreshed.
     */
    public int reload() {
        configChanged = false;
        boolean wasMotd = enabled() && formats.motdEnabled();
        formats = ServerConfig.presence();
        lastHeaderFooter.clear();
        lastMotd = null;
        warnedHex = false;
        int count = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            refresh(player);
            if (!enabled()) {
                // Switched off: the vanilla names came back through the refresh, the header and footer are cleared here.
                player.setTabListHeaderFooter(Component.empty(), Component.empty());
            }
            count++;
        }
        if (enabled() && formats.motdEnabled()) {
            updateMotd();
        } else if (wasMotd) {
            server.setMotd(originalMotd);
            server.invalidateStatus();
        }
        ConsortiumCore.LOGGER.info("Presence reloaded: {} player(s) refreshed, tab refresh {} ticks, MOTD {}", count,
                formats.tabRefreshTicks(), formats.motdEnabled() ? "on" : "off");
        return count;
    }

    // ---- rendering ----

    /** The chat display name (5.3): {@code chat_name} with {@code {name}} spliced in as the component the event carries. */
    public MutableComponent chatName(ServerPlayer player, Component eventName) {
        PresenceContext ctx = PresenceContext.ofPlayer(server, formats, player, eventName);
        noteRank(player, ctx);
        return render(withName(formats.chatName()), ctx);
    }

    /** The tab entry (5.4): {@code tab_name} with the profile name as plain text. */
    public MutableComponent tabName(ServerPlayer player) {
        PresenceContext ctx = PresenceContext.ofPlayer(server, formats, player, null);
        noteRank(player, ctx);
        return render(withName(formats.tabName()), ctx);
    }

    /** A name format that forgot {@code {name}} would make players anonymous: the name is appended instead. */
    static String withName(String format) {
        return Placeholders.tokens(format).contains("name") ? format : format + "{name}";
    }

    /** The header and footer for one viewer, rendered but not sent. */
    public HeaderFooter headerFooter(ServerPlayer player) {
        PresenceContext ctx = PresenceContext.ofPlayer(server, formats, player, null);
        String header = Placeholders.expand(formats.tabHeader(), ctx::resolve);
        String footer = Placeholders.expand(formats.tabFooter(), ctx::resolve);
        return new HeaderFooter(header, footer, SpanComponents.toComponent(LegacyText.parse(header)),
                SpanComponents.toComponent(LegacyText.parse(footer)));
    }

    /** The two MOTD lines as one legacy string (5.5); hex colours dropped with one WARN per reload. */
    public String motd() {
        PresenceContext ctx = PresenceContext.ofServer(server, formats);
        List<Span> line1 = LegacyText.parse(Placeholders.expand(formats.motdLine1(), ctx::resolve));
        List<Span> line2 = LegacyText.parse(Placeholders.expand(formats.motdLine2(), ctx::resolve));
        MotdText.Rendered rendered = MotdText.render(line1, line2);
        if (rendered.droppedHex() > 0 && !warnedHex) {
            warnedHex = true;
            ConsortiumCore.LOGGER.warn("MOTD formats use {} hex colour(s): the server list only renders the 16 legacy colours, they were dropped",
                    rendered.droppedHex());
        }
        return rendered.text();
    }

    /**
     * The style of the chat body (5.3): the LuckPerms meta {@code chat.style} of the sender when set, else
     * {@code chat_body_style}; {@link Style#EMPTY} when both are empty (the vanilla body stays untouched).
     */
    public Style chatBodyStyle(ServerPlayer player) {
        String codes = formats.chatBodyStyle();
        LuckPermsBridge.Rank rank = LuckPermsBridge.rank(player);
        if (rank.chatStyle() != null && !rank.chatStyle().isBlank()) {
            codes = rank.chatStyle();
        }
        if (codes == null || codes.isBlank()) {
            return Style.EMPTY;
        }
        return SpanComponents.styleOf(LegacyText.styleOf(codes));
    }

    private MutableComponent render(String format, PresenceContext ctx) {
        String expanded = Placeholders.expand(format, ctx::resolve);
        return SpanComponents.toComponent(LegacyText.parse(expanded), PresenceContext.NAME_MARK, ctx.name());
    }

    /** Remembers players formatted before LuckPerms had their user (the login refresh of 5.4 uses it). */
    private void noteRank(ServerPlayer player, PresenceContext ctx) {
        if (LuckPermsBridge.available() && !ctx.rank().known()) {
            rankMissing.add(player.getUUID());
        } else {
            rankMissing.remove(player.getUUID());
        }
    }

    // ---- sending ----

    /** Sends the header and footer when their text changed since the last send to this viewer (or when forced). */
    public boolean sendHeaderFooter(ServerPlayer player, boolean force) {
        if (!enabled()) {
            return false;
        }
        HeaderFooter hf = headerFooter(player);
        String key = hf.headerText() + "\u0000" + hf.footerText();
        if (!force && key.equals(lastHeaderFooter.get(player.getUUID()))) {
            return false;
        }
        lastHeaderFooter.put(player.getUUID(), key);
        // NeoForge helper: one ClientboundTabListPacket, itself skipped when both components are equal to the last ones.
        player.setTabListHeaderFooter(hf.header(), hf.footer());
        return true;
    }

    /** Sends the header and footer to every online player whose text changed. */
    public void sendHeaderFooterToAll() {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                sendHeaderFooter(player, false);
            } catch (Throwable t) {
                ConsortiumCore.LOGGER.error("Tab header render failed for {}", player.getGameProfile().getName(), t);
            }
        }
    }

    /** Renders the MOTD and applies it when it changed; {@code motd_enabled = false} leaves server.properties alone. */
    public boolean updateMotd() {
        if (!enabled() || !formats.motdEnabled()) {
            return false;
        }
        String text;
        try {
            text = motd();
        } catch (Throwable t) {
            ConsortiumCore.LOGGER.error("MOTD render failed", t);
            return false;
        }
        if (text.equals(lastMotd)) {
            return false;
        }
        lastMotd = text;
        server.setMotd(text);
        // lastServerStatus = 0: buildServerStatus() runs at the next tick instead of within the 5 s vanilla window.
        server.invalidateStatus();
        return true;
    }

    /**
     * Rank change, reload or login catch-up: re-fires {@code NameFormat} and {@code TabListNameFormat} through the
     * NeoForge helpers (the tab packet only leaves when the name changed) and re-sends the header.
     */
    public void refresh(ServerPlayer player) {
        player.refreshDisplayName();
        player.refreshTabListName();
        sendHeaderFooter(player, false);
    }

    private void onRankChanged(ServerPlayer player) {
        if (enabled()) {
            refresh(player);
        }
    }

    // ---- lifecycle of one player ----

    /** {@code PlayerLoggedInEvent}: header now, and a catch-up refresh in 20 ticks when the rank was not loaded yet. */
    public void onLogin(ServerPlayer player) {
        if (!enabled()) {
            return;
        }
        sendHeaderFooter(player, true);
        if (rankMissing.contains(player.getUUID())) {
            pendingRefresh.put(player.getUUID(), server.getTickCount() + LOGIN_REFRESH_DELAY_TICKS);
        }
    }

    /** {@code PlayerLoggedOutEvent}: forget the caches of this viewer. */
    public void onLogout(UUID uuid) {
        lastHeaderFooter.remove(uuid);
        rankMissing.remove(uuid);
        pendingRefresh.remove(uuid);
    }

    // ---- ticking (called by PresenceTicker on the server thread) ----

    /** One server tick: config reload, header cadence, MOTD cadence, login catch-ups. */
    public void tick(int tick) {
        if (configChanged) {
            reload();
        }
        if (!enabled()) {
            return;
        }
        if (!pendingRefresh.isEmpty()) {
            pendingRefresh.entrySet().removeIf(entry -> {
                if (entry.getValue() > tick) {
                    return false;
                }
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) {
                    refresh(player);
                }
                return true;
            });
        }
        if (tick % formats.tabRefreshTicks() == 0) {
            sendHeaderFooterToAll();
        }
        if (formats.motdEnabled() && tick % formats.motdRefreshTicks() == 0) {
            updateMotd();
        }
    }
}
