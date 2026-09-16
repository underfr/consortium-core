package org.consortium.core;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.consortium.core.board.BoardState;
import org.consortium.core.economy.EconomyData;
import org.consortium.core.economy.Ledger;
import org.consortium.core.economy.Transactions;
import org.consortium.core.identity.IdentityData;
import org.consortium.core.identity.IpSalt;
import org.consortium.core.monitoring.Alerts;
import org.consortium.core.monitoring.Notifier;
import org.consortium.core.monitoring.Scheduler;
import org.consortium.core.pricing.Market;
import org.consortium.core.pricing.PriceChange;
import org.consortium.core.pricing.PriceTable;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Everything that lives for one server session: saved data, ledger, market, transactions, notifier, scheduler.
 * Created in {@code ServerStartedEvent}, torn down in {@code ServerStoppedEvent}. Server thread only, except the
 * static {@link #get()} which just reads a volatile.
 */
public final class ConsortiumRuntime {
    /** Receives every balance change so the network layer can sync the client HUD (part 2 registers here). */
    @FunctionalInterface
    public interface BalanceListener {
        void onBalanceChanged(UUID player, long balance, long delta, String reason);
    }

    private static volatile ConsortiumRuntime current;
    private static final List<BalanceListener> BALANCE_LISTENERS = new CopyOnWriteArrayList<>();

    public final MinecraftServer server;
    public final Clock clock;
    public final EconomyData economy;
    public final IdentityData identity;
    public final Ledger ledger;
    public final PriceTable prices;
    public final Market market;
    public final Notifier notifier;
    public final Alerts alerts;
    public final Transactions transactions;
    public final IpSalt salt;
    public final Scheduler scheduler;
    /** The quota board the KubeJS engine publishes (v0.2, 2.4): snapshot, saved data and client sync. */
    public final BoardState board;
    private boolean ledgerReady;

    private ConsortiumRuntime(MinecraftServer server, PriceTable prices) {
        this.server = server;
        this.clock = Clock.systemUTC();
        this.prices = prices;
        this.economy = EconomyData.get(server);
        this.identity = IdentityData.get(server);
        this.notifier = new Notifier(server);
        this.ledger = new Ledger(server.getWorldPath(LevelResource.ROOT).resolve("consortium").resolve("ledger"), clock,
                text -> ConsortiumCore.LOGGER.warn(text));
        this.market = new Market(economy, prices);
        this.alerts = new Alerts(notifier, prices);
        this.transactions = new Transactions(this);
        this.salt = new IpSalt(server.getServerDirectory());
        this.scheduler = new Scheduler(this);
        this.board = new BoardState(server);
    }

    /** The active runtime, or null while no server runs. */
    public static ConsortiumRuntime get() {
        return current;
    }

    public static boolean ready() {
        ConsortiumRuntime rt = current;
        return rt != null && rt.ledgerReady;
    }

    public static void addBalanceListener(BalanceListener listener) {
        BALANCE_LISTENERS.add(listener);
    }

    public static void removeBalanceListener(BalanceListener listener) {
        BALANCE_LISTENERS.remove(listener);
    }

    public void notifyBalance(UUID player, long balance, long delta, String reason) {
        for (BalanceListener l : BALANCE_LISTENERS) {
            try {
                l.onBalanceChanged(player, balance, delta, reason);
            } catch (Throwable t) {
                ConsortiumCore.LOGGER.error("Balance listener failed", t);
            }
        }
    }

    public boolean ledgerReady() {
        return ledgerReady;
    }

    public long now() {
        return clock.millis();
    }

    /** Boot: ledger scan and rollback marker, salt, pending price merge. */
    static ConsortiumRuntime start(MinecraftServer server, PriceTable prices) {
        ConsortiumRuntime rt = new ConsortiumRuntime(server, prices);
        current = rt;
        try {
            Ledger.RollbackRange rollback = rt.ledger.boot(rt.economy.lastSeq());
            rt.ledgerReady = true;
            rt.economy.setLastSeq(rt.ledger.nextSeq() - 1);
            if (rollback != null) {
                rt.notifier.bufferBootMessage("Ledger rollback: " + rollback + " never took effect (world restored from an earlier save). "
                        + "Review with /ccore ledger tail and compensate with /credits add.", true);
                // v0.2, 5.3: a PURCHASE in the range means the money came back while the effect may have been delivered.
                List<String> purchases = rt.ledger.lastRollbackPurchases();
                if (!purchases.isEmpty()) {
                    StringBuilder sb = new StringBuilder("Rolled-back purchases whose effect may have been delivered: ");
                    for (int i = 0; i < purchases.size(); i++) {
                        sb.append(i == 0 ? "" : "; ").append(Ledger.describePurchase(purchases.get(i)));
                    }
                    sb.append(". Take the credits back with /credits take <player> <amount> <tx> if the item is there.");
                    rt.notifier.bufferBootMessage(sb.toString(), true);
                }
            }
            // Persist the new last_seq now (cheap, synchronous) so a crash before the first autosave cannot make the
            // next boot mistake this boot's ROLLBACK marker for a rolled-back line.
            try {
                server.overworld().getDataStorage().save();
            } catch (RuntimeException e) {
                ConsortiumCore.LOGGER.warn("Saved data could not be written at boot ({}); the next autosave will", e.toString());
            }
        } catch (IOException e) {
            ConsortiumCore.LOGGER.error("Ledger unavailable at boot ({}): every money movement will be refused until the next restart", e.toString());
            rt.ledgerReady = false;
        }
        rt.salt.load();
        rt.mergePrices("datapack", "pack update", true);
        // Boot order of v0.2 2.5: the saved board, then the snapshot the engine published at ServerStartingEvent.
        rt.board.boot(rt.now());
        ConsortiumCore.LOGGER.info("Consortium Core ready: {} accounts, {} families, ledger seq {}, contribute command '{}'",
                rt.economy.accounts().size(), rt.prices.familyCount(), rt.ledger.nextSeq(), org.consortium.core.config.ServerConfig.contributeCommand());
        return rt;
    }

    /**
     * Rebuilds the merged price table from the datapack and the overrides, logs and announces every change.
     *
     * @param boot true when nobody can be online: changes are buffered for login and the Discord relay
     */
    public void mergePrices(String by, String reason, boolean boot) {
        List<PriceChange> changes = prices.rebuild(economy, org.consortium.core.config.ServerConfig.priceDefaults(), by, reason);
        if (!changes.isEmpty()) {
            transactions.priceChanges(changes, boot);
        }
    }

    static void stop() {
        ConsortiumRuntime rt = current;
        current = null;
        BoardState.clearPending();
        if (rt != null) {
            rt.ledger.close();
        }
    }
}
