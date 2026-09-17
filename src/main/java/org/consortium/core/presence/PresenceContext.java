package org.consortium.core.presence;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.consortium.core.ConsortiumRuntime;
import org.consortium.core.api.ConsortiumAPI;
import org.consortium.core.board.BoardSnapshot;
import org.consortium.core.compat.LuckPermsBridge;
import org.consortium.core.compat.VanishBridge;
import org.consortium.core.config.CommonConfig;
import org.consortium.core.economy.Money;

import java.util.HashMap;
import java.util.Map;

/**
 * The placeholder values of one render (v0.3, presence 5.2), for the server (MOTD) or for one viewer (names, header,
 * footer). Built eagerly on the server thread: the board snapshot of {@code org.consortium.core.board}, the viewer's
 * balance, the TPS ring, the online count without vanished players and the LuckPerms rank. Every value is text;
 * {@code {name}} may instead carry a {@link Component} (the display name an earlier {@code NameFormat} listener set),
 * which the renderer splices in through {@link #NAME_MARK} so its own style survives.
 */
public final class PresenceContext {
    /** Private-use character standing for the name component inside the expanded string; stripped from every value. */
    public static final char NAME_MARK = '\uE000';
    private static final String NO_SNAPSHOT_PHASE_NAME = "Preparation";

    private final Map<String, String> values;
    private final Component name;
    private final LuckPermsBridge.Rank rank;
    private final boolean vanished;

    private PresenceContext(Map<String, String> values, Component name, LuckPermsBridge.Rank rank, boolean vanished) {
        this.values = values;
        this.name = name;
        this.rank = rank;
        this.vanished = vanished;
    }

    /** Server-wide values only: {@code {name}}, {@code {prefix}}, {@code {suffix}}, {@code {group}}, {@code {rank_color}} and {@code {credits}} stay verbatim. */
    public static PresenceContext ofServer(MinecraftServer server, PresenceFormats formats) {
        return new PresenceContext(serverValues(server, formats), null, LuckPermsBridge.Rank.NONE, false);
    }

    /**
     * Values for one viewer. {@code name} is the component to splice in for {@code {name}}; null means the profile
     * name as plain text (the tab entry, whose event carries no username).
     */
    public static PresenceContext ofPlayer(MinecraftServer server, PresenceFormats formats, ServerPlayer player, Component name) {
        Map<String, String> v = serverValues(server, formats);
        LuckPermsBridge.Rank rank = LuckPermsBridge.rank(player);
        v.put("name", name != null ? String.valueOf(NAME_MARK) : player.getGameProfile().getName());
        v.put("prefix", clean(rank.prefix()));
        v.put("suffix", clean(rank.suffix()));
        v.put("group", clean(rank.groupDisplayName()));
        v.put("rank_color", LegacyText.colorCodes(rank.rankColor()));
        v.put("credits", Money.formatPlain(ConsortiumAPI.balance(player.getUUID())));
        return new PresenceContext(v, name, rank, VanishBridge.isVanished(player));
    }

    private static Map<String, String> serverValues(MinecraftServer server, PresenceFormats formats) {
        Map<String, String> v = new HashMap<>();
        v.put("server", clean(formats.serverName()));
        BoardSnapshot board = board();
        v.put("phase", board == null ? "0" : Integer.toString(board.phase()));
        v.put("phase_name", board == null ? NO_SNAPSHOT_PHASE_NAME : clean(board.name()));
        v.put("day", board == null ? "0" : Integer.toString(board.day()));
        v.put("days", board == null ? "0" : Integer.toString(board.days()));
        v.put("event", board == null || board.event() == null ? "" : clean(board.event().presenceText(elapsedSincePublish())));
        v.put("currency", clean(CommonConfig.currencySymbol()));
        double mspt = TpsMath.mspt(server.getAverageTickTimeNanos());
        v.put("tps", TpsMath.formatTps(TpsMath.tps(mspt, server.tickRateManager().millisecondsPerTick())));
        v.put("mspt", TpsMath.formatMspt(mspt));
        v.put("online", Integer.toString(onlineCount(server)));
        v.put("max", Integer.toString(server.getMaxPlayers()));
        return v;
    }

    /** Players Vanishmod does not hide (everyone without Vanishmod). */
    public static int onlineCount(MinecraftServer server) {
        int count = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!VanishBridge.isVanished(p)) {
                count++;
            }
        }
        return count;
    }

    private static BoardSnapshot board() {
        ConsortiumRuntime rt = ConsortiumRuntime.get();
        return rt == null ? null : rt.board.current();
    }

    /** Milliseconds since the current snapshot was published (0 without a runtime). */
    private static long elapsedSincePublish() {
        ConsortiumRuntime rt = ConsortiumRuntime.get();
        return rt == null ? 0 : Math.max(0L, rt.now() - rt.board.publishedAt());
    }


    /** A value never carries the name mark: a prefix containing it cannot splice the name component a second time. */
    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        return s.indexOf(NAME_MARK) < 0 ? s : s.replace(String.valueOf(NAME_MARK), "");
    }

    /** The text of a token, or null when the token is unknown (kept verbatim by {@link Placeholders}). */
    public String resolve(String token) {
        return values.get(token);
    }

    /** The component spliced in for {@code {name}}, or null when the name is plain text. */
    public Component name() {
        return name;
    }

    public LuckPermsBridge.Rank rank() {
        return rank;
    }

    public boolean vanished() {
        return vanished;
    }
}
