package org.consortium.core.presence;

import java.util.Set;

/**
 * One immutable snapshot of the {@code [presence]} server config (v0.3, presence 5.1). The service takes a snapshot at
 * server start and again on {@code ccore presence reload} or when the NeoForge file watcher reloads the config, so a
 * half-edited file never renders a header with one old and one new line. Pure: the defaults live here so the unit
 * tests can check every default format against the known placeholder set without a Minecraft class.
 */
public record PresenceFormats(boolean enabled, String serverName, String chatName, String chatBodyStyle, String tabName,
                              String tabHeader, String tabFooter, int tabRefreshTicks, boolean motdEnabled,
                              String motdLine1, String motdLine2, int motdRefreshTicks) {
    public static final int MIN_TAB_REFRESH_TICKS = 20;
    public static final int MAX_TAB_REFRESH_TICKS = 200;
    public static final int MIN_MOTD_REFRESH_TICKS = 20;
    public static final int MAX_MOTD_REFRESH_TICKS = 1200;

    public static final String DEFAULT_SERVER_NAME = "The Consortium";
    public static final String DEFAULT_CHAT_NAME = "{rank_color}{prefix}&f{name}{suffix}";
    public static final String DEFAULT_CHAT_BODY_STYLE = "";
    public static final String DEFAULT_TAB_NAME = "{rank_color}{prefix}&f{name}{suffix}";
    public static final String DEFAULT_TAB_HEADER = "&6&l{server}\n&7Phase {phase}: &f{phase_name} &8| &7Day {day}/{days}\n&7You are &f{group} &8| &e{credits} {currency}";
    public static final String DEFAULT_TAB_FOOTER = "&7TPS &a{tps} &8| &7MSPT &a{mspt} &8| &7Online &f{online}&7/{max}";
    public static final int DEFAULT_TAB_REFRESH_TICKS = 60;
    public static final String DEFAULT_MOTD_LINE1 = "&6&l{server} &8| &ePhase {phase}: {phase_name}";
    public static final String DEFAULT_MOTD_LINE2 = "&7TPS {tps} &8| &f{online}&7/{max} online";
    public static final int DEFAULT_MOTD_REFRESH_TICKS = 100;

    /** The placeholders {@code PresenceContext} resolves (5.2); anything else stays verbatim. */
    public static final Set<String> KNOWN_TOKENS = Set.of("server", "phase", "phase_name", "day", "days", "name", "prefix",
            "suffix", "group", "rank_color", "credits", "currency", "tps", "mspt", "online", "max");

    /** The values of 5.1 before the config is loaded. */
    public static final PresenceFormats DEFAULTS = new PresenceFormats(true, DEFAULT_SERVER_NAME, DEFAULT_CHAT_NAME,
            DEFAULT_CHAT_BODY_STYLE, DEFAULT_TAB_NAME, DEFAULT_TAB_HEADER, DEFAULT_TAB_FOOTER, DEFAULT_TAB_REFRESH_TICKS, true,
            DEFAULT_MOTD_LINE1, DEFAULT_MOTD_LINE2, DEFAULT_MOTD_REFRESH_TICKS);

    public PresenceFormats {
        serverName = orEmpty(serverName);
        chatName = orEmpty(chatName);
        chatBodyStyle = orEmpty(chatBodyStyle);
        tabName = orEmpty(tabName);
        tabHeader = orEmpty(tabHeader);
        tabFooter = orEmpty(tabFooter);
        motdLine1 = orEmpty(motdLine1);
        motdLine2 = orEmpty(motdLine2);
        tabRefreshTicks = clamp(tabRefreshTicks, MIN_TAB_REFRESH_TICKS, MAX_TAB_REFRESH_TICKS);
        motdRefreshTicks = clamp(motdRefreshTicks, MIN_MOTD_REFRESH_TICKS, MAX_MOTD_REFRESH_TICKS);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
