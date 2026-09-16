package org.consortium.core.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.consortium.core.ConsortiumCore;

/**
 * Server to client: the outcome of a purchase attempt (specification v0.2, 5.2), shown on the shop screen's status
 * line; the same text reaches the chat through the notifier. The message travels as a {@link Component} (a
 * translatable of {@code consortium.shop.*} with its arguments) so the client renders it from the mod's own lang
 * file, exactly like the chat line.
 */
public record ShopResultPayload(boolean ok, Component message) implements CustomPacketPayload {
    public static final Type<ShopResultPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ConsortiumCore.MOD_ID, "shop_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopResultPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBoolean(payload.ok());
                ComponentSerialization.STREAM_CODEC.encode(buf, payload.message());
            },
            buf -> new ShopResultPayload(buf.readBoolean(), ComponentSerialization.STREAM_CODEC.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
