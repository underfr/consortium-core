package org.consortium.core.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.consortium.core.ConsortiumCore;

/**
 * Client to server: "deliver the grid I was quoted as {@code nonce}". The server checks the open menu, its validity
 * and the nonce (a stale one gets the current quote back, a reused one is ignored) and re-prices everything itself;
 * nothing in this payload is trusted for money.
 */
public record DeliveryConfirmPayload(long nonce) implements CustomPacketPayload {
    public static final Type<DeliveryConfirmPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ConsortiumCore.MOD_ID, "delivery_confirm"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DeliveryConfirmPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, DeliveryConfirmPayload::nonce,
            DeliveryConfirmPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
