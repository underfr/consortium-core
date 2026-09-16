package org.consortium.core.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.ConsortiumRuntime;
import org.consortium.core.terminal.DeliveryTerminalMenu;
import org.consortium.core.terminal.Quote;

import java.util.UUID;

/**
 * The three payloads of specification 5, registrar version "1", handled on the main thread. This class loads on both
 * sides and never references client classes: the client entry point installs the clientbound handlers through
 * {@link #setClientHandlers} at construction, so a dedicated server only ever sees no-op stubs.
 */
public final class ConsortiumNetwork {
    public static final String VERSION = "1";

    private static volatile IPayloadHandler<BalanceSyncPayload> clientBalanceHandler = (payload, context) -> { };
    private static volatile IPayloadHandler<DeliveryQuotePayload> clientQuoteHandler = (payload, context) -> { };

    private ConsortiumNetwork() {
    }

    /** Called by the client entry point before any payload can arrive. */
    public static void setClientHandlers(IPayloadHandler<BalanceSyncPayload> balance, IPayloadHandler<DeliveryQuotePayload> quote) {
        clientBalanceHandler = balance;
        clientQuoteHandler = quote;
    }

    /** Mod bus: {@link RegisterPayloadHandlersEvent}. */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(BalanceSyncPayload.TYPE, BalanceSyncPayload.STREAM_CODEC,
                (payload, context) -> clientBalanceHandler.handle(payload, context));
        registrar.playToClient(DeliveryQuotePayload.TYPE, DeliveryQuotePayload.STREAM_CODEC,
                (payload, context) -> clientQuoteHandler.handle(payload, context));
        registrar.playToServer(DeliveryConfirmPayload.TYPE, DeliveryConfirmPayload.STREAM_CODEC, ConsortiumNetwork::handleConfirm);
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
}
