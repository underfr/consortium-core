package org.consortium.core.shop;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.ConsortiumRuntime;
import org.consortium.core.api.ConsortiumAPI;
import org.consortium.core.api.event.ShopPurchaseEvent;
import org.consortium.core.compat.ChaptersBridge;
import org.consortium.core.config.CommonConfig;
import org.consortium.core.economy.Account;
import org.consortium.core.economy.Money;
import org.consortium.core.economy.Transactions;
import org.consortium.core.monitoring.Notifier;
import org.consortium.core.network.ConsortiumNetwork;
import org.consortium.core.network.ShopCatalogPayload;
import org.consortium.core.terminal.CommandRunner;
import org.consortium.core.terminal.CommandTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The server side of the shop (specification v0.2, 5.2 and 5.3): the per-player catalogue view, the menu opening
 * and the purchase transaction. Server thread only; every entry point checks the runtime first.
 *
 * <p>A purchase runs in one tick in this order: the refusals (each answered with a {@code shop_result}), the ledger
 * debit through {@link Transactions#purchase} (write-ahead: the line is flushed before the balance moves), the daily
 * counter and the synchronous save of the saved data (money durable first, so a crash can never hand the item and
 * the money back together), then the effect (the item into the inventory, or the command as the console) inside a
 * catch-all, then the follow-ups: {@link ShopPurchaseEvent}, the result line and a fresh catalogue.
 */
public final class ShopService {
    public static final Component TITLE = Component.translatable("container.consortium.shop");
    /** {@code by} of a purchase the player made from the screen; admin purchases carry {@code admin:<uuid>} or {@code console}. */
    public static final String BY_PLAYER = "player";

    /** The outcome of a purchase attempt: what the buyer was told and, on success, the transaction id. */
    public record Outcome(boolean ok, Component message, String tx) {
    }

    private ShopService() {
    }

    // ---- opening ----

    /** The terminal screen's Shop button: the terminal menu closes (grid handed back), the shop menu opens, the catalogue follows. */
    public static void openFromTerminal(ServerPlayer player, BlockPos pos) {
        open(player, pos, ContainerLevelAccess.create(player.level(), pos), true);
    }

    /** {@code /ccore shop open}: no terminal, {@code stillValid} always true, Back closes the screen. */
    public static void openFromCommand(ServerPlayer player) {
        open(player, BlockPos.ZERO, ContainerLevelAccess.NULL, false);
    }

    private static void open(ServerPlayer player, BlockPos pos, ContainerLevelAccess access, boolean fromTerminal) {
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new ShopMenu(id, inventory, access, pos), TITLE),
                buf -> ShopMenu.writeExtraData(buf, pos, fromTerminal));
        if (player.containerMenu instanceof ShopMenu menu) {
            sendCatalog(player, menu);
        }
    }

    // ---- catalogue ----

    /** Rotates the menu's nonce and sends the player's view of the catalogue. */
    public static void sendCatalog(ServerPlayer player, ShopMenu menu) {
        ConsortiumNetwork.sendCatalog(player, catalog(player, menu.rotateNonce()));
    }

    /** Every online player with the shop open gets a fresh catalogue ({@code /reload}, a phase change). */
    public static void refreshViewers() {
        ConsortiumRuntime rt = ConsortiumRuntime.get();
        if (rt == null) {
            return;
        }
        for (ServerPlayer player : rt.server.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof ShopMenu menu) {
                try {
                    sendCatalog(player, menu);
                } catch (Throwable t) {
                    ConsortiumCore.LOGGER.warn("Shop: catalogue refresh for {} failed: {}", player.getGameProfile().getName(), t.toString());
                }
            }
        }
    }

    /** The catalogue as one player sees it: refused entries and, without Chapters, stage-gated entries are hidden. */
    public static ShopCatalogPayload catalog(ServerPlayer player, long nonce) {
        ConsortiumRuntime rt = ConsortiumRuntime.get();
        long now = rt == null ? System.currentTimeMillis() : rt.now();
        String day = Transactions.utcDayOf(now);
        Account account = rt == null ? null : rt.economy.account(player.getUUID());
        int phase = ConsortiumAPI.boardPhase();
        List<ShopCatalogPayload.Entry> out = new ArrayList<>();
        for (ShopEntry entry : ConsortiumCore.SHOP.entries()) {
            if (ConsortiumCore.SHOP.refusal(entry.key()) != null) {
                continue;
            }
            if (entry.stage() != null && !ChaptersBridge.available()) {
                continue;
            }
            if (rt != null && entry.isItem() && rt.prices.isPriced(entry.item().getItem())) {
                // Priced since the load (/prices set): the purchase would be refused, so the row is not offered.
                continue;
            }
            int bought = account == null ? 0 : account.boughtToday(entry.key(), day);
            ShopState state = stateOf(player, entry, phase, bought);
            out.add(new ShopCatalogPayload.Entry(entry.key(), entry.name(), entry.icon().copy(), entry.isItem(), entry.priceCents(),
                    entry.dailyLimit(), bought, state, entry.phase(), entry.description()));
        }
        return new ShopCatalogPayload(nonce, out);
    }

    /** The state of one entry for one player, in the refusal order of 5.3 (stage, Chapters lock, phase, limit). */
    public static ShopState stateOf(ServerPlayer player, ShopEntry entry, int boardPhase, int boughtToday) {
        if (entry.stage() != null && !ChaptersBridge.hasStage(player, entry.stage())) {
            return ShopState.LOCKED_STAGE;
        }
        if (entry.isItem() && ChaptersBridge.isLocked(player, entry.item())) {
            return ShopState.LOCKED_STAGE;
        }
        if (entry.phase() > 0 && boardPhase < entry.phase()) {
            return ShopState.LOCKED_PHASE;
        }
        if (entry.dailyLimit() > 0 && boughtToday >= entry.dailyLimit()) {
            return ShopState.LIMIT_REACHED;
        }
        return ShopState.AVAILABLE;
    }

    // ---- purchase ----

    /**
     * Buys one entry for the player (5.3). {@code by} is {@link #BY_PLAYER} from the screen, else the admin
     * counterpart ({@code admin:<uuid>} or {@code console}) of {@code /ccore shop buy}. The buyer always gets the
     * outcome as a status line and a chat line; a fake player is ignored silently.
     */
    public static Outcome purchase(ServerPlayer player, String key, String by) {
        if (player == null || player instanceof FakePlayer) {
            return new Outcome(false, Component.empty(), null);
        }
        ConsortiumRuntime rt = ConsortiumRuntime.get();
        if (rt == null || !rt.ledgerReady()) {
            return finish(player, false, refused("ledger"), null);
        }
        if (player.isCreative()) {
            return finish(player, false, refused("creative"), null);
        }
        if (!player.isAlive()) {
            return finish(player, false, refused("dead"), null);
        }
        ShopEntry entry = ConsortiumCore.SHOP.entry(key);
        if (entry == null) {
            return finish(player, false, refused("unknown"), null);
        }
        if (entry.stage() != null && !ChaptersBridge.hasStage(player, entry.stage())) {
            return finish(player, false, refused("stage"), null);
        }
        if (entry.isItem() && ChaptersBridge.isLocked(player, entry.item())) {
            return finish(player, false, refused("stage"), null);
        }
        int boardPhase = ConsortiumAPI.boardPhase();
        if (entry.phase() > 0 && boardPhase < entry.phase()) {
            return finish(player, false, refused("phase", entry.phase()), null);
        }
        long now = rt.now();
        String day = Transactions.utcDayOf(now);
        String name = player.getGameProfile().getName();
        Account account = rt.economy.getOrCreate(player.getUUID(), name);
        if (entry.dailyLimit() > 0 && account.boughtToday(entry.key(), day) >= entry.dailyLimit()) {
            return finish(player, false, refused("limit", entry.dailyLimit()), null);
        }
        if (entry.isItem() && rt.prices.isPriced(entry.item().getItem())) {
            ConsortiumCore.LOGGER.warn("Shop: purchase of {} by {} refused: the Consortium buys {} (price table changed since the load)",
                    entry.key(), name, entry.itemText());
            return finish(player, false, refused("priced"), null);
        }
        String tx = Transactions.newTxId();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("player", name);
        values.put("uuid", player.getUUID().toString());
        values.put("tx", tx);
        if (entry.isCommand()) {
            String bad = CommandTemplate.invalidPlaceholder(entry.command(), values);
            if (bad != null) {
                rt.notifier.alertOps("Shop: purchase of " + entry.key() + " by " + name + " refused before the debit: placeholder {"
                        + bad + "} has no usable value ('" + values.get(bad) + "')");
                return finish(player, false, refused("undeliverable"), null);
            }
        }
        String symbol = CommonConfig.currencySymbol();
        if (account.balance < entry.priceCents()) {
            return finish(player, false, refused("funds", entry.name(), Money.format(entry.priceCents(), symbol),
                    Money.format(account.balance, symbol)), null);
        }

        // Step 2: the ledger debit (write-ahead), BalanceChangeEvent inside.
        String rendered = entry.isCommand() ? CommandTemplate.render(entry.command(), values) : null;
        Map<String, Object> extras = new LinkedHashMap<>();
        if (entry.isItem()) {
            extras.put("item", entry.itemText());
        } else {
            extras.put("command", rendered);
        }
        extras.put("terminal", terminalOf(player, by));
        extras.put("by", by == null ? BY_PLAYER : by);
        Transactions.PurchaseResult result = rt.transactions.purchase(player.getUUID(), name, entry.priceCents(), entry.key(),
                entry.name(), tx, extras);
        if (!result.ok()) {
            Component why = switch (result.result()) {
                case INSUFFICIENT_FUNDS -> refused("funds", entry.name(), Money.format(entry.priceCents(), symbol), Money.format(result.balance(), symbol));
                case LEDGER_UNAVAILABLE -> refused("ledger");
                default -> refused("vetoed");
            };
            return finish(player, false, why, null);
        }
        Transactions.PurchaseReceipt receipt = result.receipt();

        // Step 3: the daily counter, the synchronous save (money durable first), then the effect.
        account.addDailyBought(entry.key(), day);
        rt.economy.touch();
        try {
            rt.server.overworld().getDataStorage().save();
        } catch (RuntimeException e) {
            ConsortiumCore.LOGGER.warn("Shop: saved data could not be written after purchase tx {} ({}); the next autosave will", tx, e.toString());
            rt.notifier.alertOps("Shop: the save after purchase tx " + tx + " (" + entry.key() + " by " + name + ") failed: " + e
                    + "; a crash before the next autosave rolls the debit back");
        }
        String effectFailure = null;
        try {
            if (entry.isItem()) {
                // Fills stacks then free slots, drops the overflow at the player, and sends container -2 slot packets so the
                // item shows at once although the zero-slot shop menu is the open container.
                player.getInventory().placeItemBackInInventory(entry.item().copy(), true);
            } else {
                effectFailure = CommandRunner.run(rt, rendered);
            }
        } catch (Throwable t) {
            effectFailure = t.toString();
        }
        if (effectFailure != null) {
            ConsortiumCore.LOGGER.error("Shop: effect of purchase tx {} ({} by {}) failed: {}", tx, entry.key(), name, effectFailure);
            rt.notifier.alertOps("Shop: purchase tx " + tx + " (" + entry.key() + " by " + name + ", " + Money.format(entry.priceCents(), symbol)
                    + ") was recorded but its delivery failed: " + effectFailure + ". Refund with /credits add if needed.");
        } else {
            ConsortiumCore.LOGGER.info("Shop: {} bought {} ({}) for {}, tx {}, balance {}", name, entry.key(), entry.name(),
                    Money.format(entry.priceCents(), symbol), tx, Money.format(receipt.balanceAfter(), symbol));
        }

        // Step 4: follow-ups.
        try {
            NeoForge.EVENT_BUS.post(new ShopPurchaseEvent(player, entry.key(), entry.priceCents(), tx, entry.item(), rendered));
        } catch (Throwable t) {
            ConsortiumCore.LOGGER.error("ShopPurchaseEvent listener failed (purchase {} already committed)", tx, t);
        }
        Component message = effectFailure != null
                ? Component.translatable("consortium.shop.effect_failed", entry.name(), tx)
                : Component.translatable("consortium.shop.bought", entry.name(), Money.format(entry.priceCents(), symbol),
                Money.format(receipt.balanceAfter(), symbol));
        return finish(player, effectFailure == null, message, tx);
    }

    private static String terminalOf(ServerPlayer player, String by) {
        if (by != null && !BY_PLAYER.equals(by)) {
            return "console";
        }
        if (player.containerMenu instanceof ShopMenu menu && menu.fromTerminal()) {
            BlockPos p = menu.terminalPos();
            return player.level().dimension().location() + " " + p.getX() + " " + p.getY() + " " + p.getZ();
        }
        return "console";
    }

    private static Component refused(String what, Object... args) {
        return Component.translatable("consortium.shop.refused." + what, args);
    }

    /** Tells the buyer (status line and chat), refreshes the catalogue when the shop is open, returns the outcome. */
    private static Outcome finish(ServerPlayer player, boolean ok, Component message, String tx) {
        ConsortiumNetwork.sendShopResult(player, ok, message);
        Notifier.tell(player, message);
        if (player.containerMenu instanceof ShopMenu menu) {
            sendCatalog(player, menu);
        }
        return new Outcome(ok, message, tx);
    }
}
