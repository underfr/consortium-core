package org.consortium.core.network;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.ConsortiumRuntime;
import org.consortium.core.command.Permissions;
import org.consortium.core.monitoring.Notifier;
import org.consortium.core.shop.ShopMenu;
import org.consortium.core.shop.ShopService;
import org.consortium.core.terminal.DeliveryTerminalBlock;
import org.consortium.core.terminal.DeliveryTerminalMenu;
import org.consortium.core.terminal.Quote;

import java.util.UUID;

/**
 * The payloads of specification 5, of v0.2 2.5 (the quota board) and of v0.2 5.2 (the shop), registrar version
 * "1", handled on the main thread. This class loads on both sides and never references client classes: the client
 * entry point installs the clientbound handlers through {@link #setClientHandlers} at construction, so a dedicated
 * server only ever sees no-op stubs.
 */
public final class ConsortiumNetwork {
    public static final String VERSION = "1";

    private static volatile IPayloadHandler<BalanceSyncPayload> clientBalanceHandler = (payload, context) -> { };
    private static volatile IPayloadHandler<DeliveryQuotePayload> clientQuoteHandler = (payload, context) -> { };
    private static volatile IPayloadHandler<BoardSyncPayload> clientBoardHandler = (payload, context) -> { };
    private static volatile IPayloadHandler<ShopCatalogPayload> clientCatalogHandler = (payload, context) -> { };
    private static volatile IPayloadHandler<ShopResultPayload> clientResultHandler = (payload, context) -> { };

    private ConsortiumNetwork() {
    }

    /** Called by the client entry point before any payload can arrive. */
    public static void setClientHandlers(IPayloadHandler<BalanceSyncPayload> balance, IPayloadHandler<DeliveryQuotePayload> quote,
                                         IPayloadHandler<BoardSyncPayload> board, IPayloadHandler<ShopCatalogPayload> catalog,
                                         IPayloadHandler<ShopResultPayload> result) {
        clientBalanceHandler = balance;
        clientQuoteHandler = quote;
        clientBoardHandler = board;
        clientCatalogHandler = catalog;
        clientResultHandler = result;
    }

    /** Mod bus: {@link RegisterPayloadHandlersEvent}. */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(BalanceSyncPayload.TYPE, BalanceSyncPayload.STREAM_CODEC,
                (payload, context) -> clientBalanceHandler.handle(payload, context));
        registrar.playToClient(DeliveryQuotePayload.TYPE, DeliveryQuotePayload.STREAM_CODEC,
                (payload, context) -> clientQuoteHandler.handle(payload, context));
        registrar.playToClient(BoardSyncPayload.TYPE, BoardSyncPayload.STREAM_CODEC,
                (payload, context) -> clientBoardHandler.handle(payload, context));
        registrar.playToClient(ShopCatalogPayload.TYPE, ShopCatalogPayload.STREAM_CODEC,
                (payload, context) -> clientCatalogHandler.handle(payload, context));
        registrar.playToClient(ShopResultPayload.TYPE, ShopResultPayload.STREAM_CODEC,
                (payload, context) -> clientResultHandler.handle(payload, context));
        registrar.playToServer(DeliveryConfirmPayload.TYPE, DeliveryConfirmPayload.STREAM_CODEC, ConsortiumNetwork::handleConfirm);
        registrar.playToServer(ShopOpenPayload.TYPE, ShopOpenPayload.STREAM_CODEC, ConsortiumNetwork::handleShopOpen);
        registrar.playToServer(ShopBuyPayload.TYPE, ShopBuyPayload.STREAM_CODEC, ConsortiumNetwork::handleShopBuy);
        registrar.playToServer(TerminalOpenPayload.TYPE, TerminalOpenPayload.STREAM_CODEC, ConsortiumNetwork::handleTerminalOpen);
    }

    private static void handleConfirm(DeliveryConfirmPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.containerMenu instanceof DeliveryTerminalMenu menu)) {
            // No terminal open: a late click after the screen closed, or a forged packet. Nothing to do.
            return;
        }
        try {
            menu.deliver(player, payload.nonce());
        } catch (Throwable t) {
            ConsortiumCore.LOGGER.error("Delivery request of {} failed", player.getGameProfile().getName(), t);
        }
    }

    /** The Shop button (v0.2, 5.2): a terminal menu at that position, an empty grid and the {@code shop.buy} node. */
    private static void handleShopOpen(ShopOpenPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.containerMenu instanceof DeliveryTerminalMenu menu) || !menu.terminalPos().equals(payload.pos())
                || !menu.stillValid(player) || !menu.gridEmpty()) {
            return;
        }
        if (!Permissions.has(player, Permissions.SHOP_BUY, 0)) {
            Notifier.tell(player, Component.translatable("consortium.shop.refused.permission"));
            return;
        }
        try {
            ShopService.openFromTerminal(player, payload.pos());
        } catch (Throwable t) {
            ConsortiumCore.LOGGER.error("Shop open for {} failed", player.getGameProfile().getName(), t);
        }
    }

    /** The Buy button (v0.2, 5.2): the shop menu, a matching nonce (else a fresh catalogue, never a purchase). */
    private static void handleShopBuy(ShopBuyPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.containerMenu instanceof ShopMenu menu) || !menu.stillValid(player) || !payload.validKey()) {
            return;
        }
        if (!Permissions.has(player, Permissions.SHOP_BUY, 0)) {
            Notifier.tell(player, Component.translatable("consortium.shop.refused.permission"));
            return;
        }
        try {
            if (payload.nonce() != menu.nonce()) {
                ShopService.sendCatalog(player, menu);
                return;
            }
            menu.rotateNonce();
            ShopService.purchase(player, payload.key(), ShopService.BY_PLAYER);
        } catch (Throwable t) {
            ConsortiumCore.LOGGER.error("Shop purchase of {} by {} failed", payload.key(), player.getGameProfile().getName(), t);
        }
    }

    /** The Back button (v0.2, 5.2): from a shop menu opened at that terminal, reopen the terminal (fresh grid). */
    private static void handleTerminalOpen(TerminalOpenPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.containerMenu instanceof ShopMenu menu) || !menu.fromTerminal() || !menu.terminalPos().equals(payload.pos())
                || !menu.stillValid(player)) {
            return;
        }
        try {
            if (!player.level().getBlockState(payload.pos()).is(ConsortiumCore.DELIVERY_TERMINAL.get())) {
                return;
            }
            player.openMenu(DeliveryTerminalBlock.menuProvider(player.level(), payload.pos()), payload.pos());
        } catch (Throwable t) {
            ConsortiumCore.LOGGER.error("Terminal reopen for {} failed", player.getGameProfile().getName(), t);
        }
    }

    // ---- sending ----

    /** Sends the balance to one online player; silently skipped when the client has not negotiated the channel. */
    public static void sendBalance(ServerPlayer player, long balance, long delta, String reason) {
        if (player == null || player.hasDisconnected()) {
            return;
        }
        if (!player.connection.hasChannel(BalanceSyncPayload.TYPE)) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new BalanceSyncPayload(balance, delta, reason == null ? "" : reason));
    }

    /** {@link ConsortiumRuntime.BalanceListener}: every balance movement of the runtime ends up here. */
    public static void onBalanceChanged(UUID uuid, long balance, long delta, String reason) {
        ConsortiumRuntime rt = ConsortiumRuntime.get();
        if (rt == null) {
            return;
        }
        ServerPlayer player = rt.server.getPlayerList().getPlayer(uuid);
        if (player != null) {
            sendBalance(player, balance, delta, reason);
        }
    }

    /** Resync without a movement (respawn, dimension change): the HUD must never show a stale value. */
    public static void resync(ServerPlayer player, String reason) {
        ConsortiumRuntime rt = ConsortiumRuntime.get();
        if (rt == null) {
            return;
        }
        var account = rt.economy.account(player.getUUID());
        sendBalance(player, account == null ? 0 : account.balance, 0, reason);
    }

    public static void sendQuote(ServerPlayer player, Quote quote) {
        if (player == null || player.hasDisconnected() || quote == null) {
            return;
        }
        if (!player.connection.hasChannel(DeliveryQuotePayload.TYPE)) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new DeliveryQuotePayload(quote));
    }

    /** The quota board to one player (every accepted change, and login); skipped for a client without the channel. */
    public static void sendBoard(ServerPlayer player, BoardSyncPayload payload) {
        if (player == null || player.hasDisconnected() || payload == null) {
            return;
        }
        if (!player.connection.hasChannel(BoardSyncPayload.TYPE)) {
            return;
        }
        PacketDistributor.sendToPlayer(player, payload);
    }

    /** The shop catalogue to the player viewing it. */
    public static void sendCatalog(ServerPlayer player, ShopCatalogPayload payload) {
        if (player == null || player.hasDisconnected() || payload == null) {
            return;
        }
        if (!player.connection.hasChannel(ShopCatalogPayload.TYPE)) {
            return;
        }
        PacketDistributor.sendToPlayer(player, payload);
    }

    /** The outcome of a purchase attempt to the buyer's screen (the chat line is the notifier's job). */
    public static void sendShopResult(ServerPlayer player, boolean ok, Component message) {
        if (player == null || player.hasDisconnected() || message == null) {
            return;
        }
        if (!player.connection.hasChannel(ShopResultPayload.TYPE)) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new ShopResultPayload(ok, message));
    }
}
