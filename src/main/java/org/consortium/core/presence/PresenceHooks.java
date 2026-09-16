package org.consortium.core.presence;

import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.consortium.core.ConsortiumCore;

/**
 * The NeoForge event listeners of the presence module (v0.3, presence 5.3 and 5.4), registered on
 * {@code NeoForge.EVENT_BUS} at {@code EventPriority.NORMAL}. Every handler is a no-op without a running
 * {@link PresenceService} or with {@code [presence] enabled = false}, and never lets an exception escape into the
 * login or chat path.
 *
 * <h2>Chat: decorate, never rewrite</h2>
 * A chat line is {@code <sender> body}: the sender part is {@code player.getDisplayName()} (bound through
 * {@code ChatType.CHAT}), the body part is the {@code PlayerChatMessage}. The rank prefix therefore goes on the display
 * name ({@link #onNameFormat}), which also prefixes join, leave, death, advancement, {@code /say}, {@code /me} and
 * {@code /tell} lines and is the only part that reaches clients with "Only Show Secure Chat" on (they drop unsigned
 * content). The body is only ever styled through {@link ServerChatEvent#setMessage}: that fills
 * {@code PlayerChatMessage.unsignedContent} and nothing else, so the signature, the signed body, the last-seen chain and
 * the client report log are untouched, {@code PlayerList.verifyChatTrusted} stays true and, because the raw text stays a
 * substring rendered with the default font, {@code ChatTrustLevel.evaluate} returns SECURE with no "Modified" tag.
 *
 * <p><b>Forbidden</b> (the LPChatPrefix, Re-LPChatPrefix and PlayerName Styler pattern, rejected by the pack): calling
 * {@code event.setCanceled(true)} and re-sending a formatted line with {@code PlayerList.broadcastSystemMessage} or
 * {@code sendSystemMessage}. That turns signed player chat into {@code ClientboundSystemChatPacket}: the line leaves the
 * client's report log (it is stored as a system message and cannot be selected in a player report), secure-chat-only
 * clients see an unsigned line, and the pack's safety rule (no change to chat signing or player reporting, DECISIONS
 * 2026-09-14) is broken. Rewording the body text or giving it a font shows the grey "Modified" tag: never touch the text,
 * only its style.
 */
public final class PresenceHooks {
    private long lastErrorLog;

    /**
     * Fired lazily by {@code Player.getDisplayName()} (cached per player instance) and by {@code refreshDisplayName()};
     * on the client too, so only {@link ServerPlayer}s are handled. {@code {name}} is {@link PlayerEvent.NameFormat#getDisplayname()},
     * not the username: a value set by an earlier listener (FTB Essentials' nickname at HIGHEST) survives, and later
     * listeners wrap this result (its recording marker at LOWEST).
     */
    public void onNameFormat(PlayerEvent.NameFormat event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        PresenceService svc = PresenceService.get();
        if (svc == null || !svc.enabled()) {
            return;
        }
        try {
            event.setDisplayname(svc.chatName(player, event.getDisplayname()));
        } catch (Throwable t) {
            logError("Chat name render failed for " + player.getGameProfile().getName(), t);
        }
    }

    /**
     * Fired by {@code ServerPlayer.getTabListDisplayName()} (cached, read when player info packets are built) and by
     * {@code refreshTabListName()}. NORMAL priority on purpose: Vanishmod appends its {@code [Vanished]} marker to
     * {@code getDisplayName()} at LOW, i.e. after this listener, so the marker wraps the rank-formatted name; a LOWEST
     * registration here would overwrite it.
     */
    public void onTabListNameFormat(PlayerEvent.TabListNameFormat event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        PresenceService svc = PresenceService.get();
        if (svc == null || !svc.enabled()) {
            return;
        }
        try {
            event.setDisplayName(svc.tabName(player));
        } catch (Throwable t) {
            logError("Tab name render failed for " + player.getGameProfile().getName(), t);
        }
    }

    /**
     * Body styling only (see the class Javadoc): the message component is copied and given the configured style, its
     * text is never changed and the event is never cancelled.
     */
    public void onServerChat(ServerChatEvent event) {
        PresenceService svc = PresenceService.get();
        if (svc == null || !svc.enabled()) {
            return;
        }
        try {
            Style style = svc.chatBodyStyle(event.getPlayer());
            if (style.isEmpty()) {
                return;
            }
            event.setMessage(event.getMessage().copy().withStyle(style));
        } catch (Throwable t) {
            logError("Chat body style failed for " + event.getUsername(), t);
        }
    }

    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        PresenceService svc = PresenceService.get();
        if (svc == null) {
            return;
        }
        try {
            svc.onLogin(player);
        } catch (Throwable t) {
            logError("Presence login hook failed for " + player.getGameProfile().getName(), t);
        }
    }

    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        PresenceService svc = PresenceService.get();
        if (svc != null) {
            svc.onLogout(player.getUUID());
        }
    }

    private void logError(String text, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLog > 60_000) {
            lastErrorLog = now;
            ConsortiumCore.LOGGER.error(text, t);
        }
    }
}
