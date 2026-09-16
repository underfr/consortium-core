package org.consortium.core.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.consortium.core.ConsortiumCore;

/**
 * Client to server: the Shop button of the terminal screen (specification v0.2, 5.2). Carries the terminal position
 * the open terminal menu must match; the server also requires an empty grid and the {@code consortium.shop.buy}
 * node before it swaps the terminal menu for the shop menu.
 */
public record ShopOpenPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<ShopOpenPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ConsortiumCore.MOD_ID, "shop_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopOpenPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeBlockPos(payload.pos()),
            buf -> new ShopOpenPayload(buf.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
