package org.consortium.core.compat;

import net.neoforged.fml.ModList;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.config.CommonConfig;

/**
 * Simple Discord Link relay (specification 7). The SDLink classes are only touched inside {@link SdlinkCalls}, which
 * is loaded only when the mod is present, so this class is safe on a server or client without SDLink.
 */
public final class SdlinkBridge {
    private static Boolean present;
    private static long lastFailureLog;

    private SdlinkBridge() {
    }

    public static boolean available() {
        if (present == null) {
            present = ModList.get().isLoaded("sdlink");
        }
        return present && CommonConfig.sdlinkEnabled();
    }

    /** Alerts and reports: SDLink's {@code CUSTOM} destination (routed by its own [messageDestinations.custom]). */
    public static boolean sendCustom(String text) {
        return send(text, true);
    }

    /** Public announcements: SDLink's {@code CHAT} destination, next to player chat. */
    public static boolean sendChat(String text) {
        return send(text, false);
    }

    private static boolean send(String text, boolean custom) {
        if (!available()) {
            return false;
        }
        try {
            if (!SdlinkCalls.botReady()) {
                // SDLink is installed but its bot is off (no token, still connecting): nothing reaches Discord.
                return false;
            }
            SdlinkCalls.send(text, custom);
            return true;
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastFailureLog > 60_000) {
                lastFailureLog = now;
                ConsortiumCore.LOGGER.warn("SDLink relay failed: {}", t.toString());
            }
            return false;
        }
    }

    /** Isolated so the JVM only resolves the SDLink classes when this class is loaded. */
    private static final class SdlinkCalls {
        static boolean botReady() {
            var controller = com.hypherionmc.sdlink.core.discord.BotController.INSTANCE;
            return controller != null && controller.isBotReady();
        }

        static void send(String text, boolean custom) {
            var type = custom ? com.hypherionmc.sdlink.api.messaging.MessageType.CUSTOM
                    : com.hypherionmc.sdlink.api.messaging.MessageType.CHAT;
            new com.hypherionmc.sdlink.api.messaging.discord.DiscordMessageBuilder(type)
                    .author(com.hypherionmc.sdlink.api.accounts.DiscordAuthor.getServer())
                    .message(text)
                    .build()
                    .sendMessage();
        }
    }
}
