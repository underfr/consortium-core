package org.consortium.core.monitoring;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.compat.SdlinkBridge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Every outbound message of the mod: public announcements (chat plus the SDLink chat relay), op alerts (online ops
 * plus the SDLink custom destination) and the boot-time buffer (messages found while nobody was online are shown at
 * login for 24 hours and relayed at the first minute tick, because SDLink drops messages while its bot is not ready).
 */
public final class Notifier {
    public static final String PREFIX = "[Consortium] ";
    private static final long BOOT_MESSAGE_TTL = 24L * 3600 * 1000;

    /** A buffered boot-time message; {@code opsOnly} ones are shown to ops only. */
    public record Buffered(long at, String text, boolean opsOnly) {
    }

    private final MinecraftServer server;
    private final Deque<Buffered> bootMessages = new ArrayDeque<>();
    private final List<String> pendingRelay = new ArrayList<>();

    public Notifier(MinecraftServer server) {
        this.server = server;
    }

    public static MutableComponent prefixed(String text) {
        return Component.literal(PREFIX).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(text).withStyle(ChatFormatting.WHITE));
    }

    public static MutableComponent alertComponent(String text) {
        return Component.literal(PREFIX).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(text).withStyle(ChatFormatting.RED));
    }

    /** Public announcement: every player, the console, and Discord through the SDLink chat relay. */
    public void announce(String text) {
        ConsortiumCore.LOGGER.info("[announce] {}", text);
        server.getPlayerList().broadcastSystemMessage(prefixed(text), false);
        if (!SdlinkBridge.sendChat(PREFIX + text)) {
            // no relay: nothing else to do, the console already has it
        }
    }

    /** Alert: online ops (permission level 2), the console at WARN, and the SDLink custom destination. */
    public void alertOps(String text) {
        ConsortiumCore.LOGGER.warn("[alert] {}", text);
        Component component = alertComponent(text);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.hasPermissions(2)) {
                p.sendSystemMessage(component);
            }
        }
        SdlinkBridge.sendCustom(PREFIX + text);
    }

    /** Information for ops only (no alert colour): online ops and the console. */
    public void informOps(String text) {
        ConsortiumCore.LOGGER.info("[ops] {}", text);
        Component component = prefixed(text);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.hasPermissions(2)) {
                p.sendSystemMessage(component);
            }
        }
    }

    /** Sends a plain message to one player. */
    public static void tell(ServerPlayer player, String text) {
        player.sendSystemMessage(prefixed(text));
    }

    /**
     * Records a message detected at boot (price changes, rollback): shown to each player (or op) at login for 24 h
     * and relayed to Discord at the next minute tick.
     */
    public void bufferBootMessage(String text, boolean opsOnly) {
        long now = System.currentTimeMillis();
        bootMessages.addLast(new Buffered(now, text, opsOnly));
        while (bootMessages.size() > 50) {
            bootMessages.pollFirst();
        }
        pendingRelay.add(text);
    }

    /** Login hook: shows the buffered boot messages still within their 24 h window. */
    public void showBootMessages(ServerPlayer player) {
        long now = System.currentTimeMillis();
        bootMessages.removeIf(b -> now - b.at() > BOOT_MESSAGE_TTL);
        boolean op = player.hasPermissions(2);
        for (Buffered b : bootMessages) {
            if (!b.opsOnly() || op) {
                player.sendSystemMessage(b.opsOnly() ? alertComponent(b.text()) : prefixed(b.text()));
            }
        }
    }

    /** Minute tick: relays the buffered boot messages once. */
    public void flushPendingRelay() {
        if (pendingRelay.isEmpty()) {
            return;
        }
        List<String> copy = new ArrayList<>(pendingRelay);
        pendingRelay.clear();
        for (String text : copy) {
            SdlinkBridge.sendCustom(PREFIX + text);
        }
    }
}
