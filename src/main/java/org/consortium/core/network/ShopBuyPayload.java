package org.consortium.core.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.shop.ShopJson;

/**
 * Client to server: the Buy button (specification v0.2, 5.2). The nonce is the one of the catalogue the client
 * displays; a mismatch (a second click after the client timeout, a replay) is answered with a fresh catalogue and
 * never with a purchase, so one catalogue view buys at most once.
 */
public record ShopBuyPayload(String key, long nonce) implements CustomPacketPayload {
    public static final Type<ShopBuyPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ConsortiumCore.MOD_ID, "shop_buy"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopBuyPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeUtf(payload.key(), 32);
                buf.writeVarLong(payload.nonce());
            },
            buf -> new ShopBuyPayload(buf.readUtf(32), buf.readVarLong()));

    /** True when the key has the catalogue shape; anything else is dropped without a lookup. */
    public boolean validKey() {
        return ShopJson.validKey(key);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
