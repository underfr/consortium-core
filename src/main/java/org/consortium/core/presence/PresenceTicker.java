package org.consortium.core.presence;

import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.consortium.core.ConsortiumCore;

/**
 * {@code ServerTickEvent.Post} cadence of the presence module (v0.3, presence 5.4 and 5.5): every
 * {@code tab_refresh_ticks} the header and footer of every viewer are re-rendered and sent when their text changed,
 * every {@code motd_refresh_ticks} the MOTD is rebuilt and applied when it changed, and the login catch-up refreshes
 * run when their tick comes. A pending config reload (file watcher) is applied first.
 */
public final class PresenceTicker {
    private long lastErrorLog;

    public void onServerTickPost(ServerTickEvent.Post event) {
        PresenceService svc = PresenceService.get();
        if (svc == null) {
            return;
        }
        try {
            svc.tick(event.getServer().getTickCount());
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastErrorLog > 60_000) {
                lastErrorLog = now;
                ConsortiumCore.LOGGER.error("Presence tick failed", t);
            }
        }
    }
}
