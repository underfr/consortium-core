package org.consortium.core.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.consortium.core.ConsortiumCore;

/**
 * Server to client: the player's balance in cents, the delta of the movement that caused the sync (0 for a plain
 * resync) and its reason. Sent on login, respawn, dimension change and after every balance change (specification 5).
 */
public record BalanceSyncPayload(long balance, long delta, String reason) implements CustomPacketPayload {
    public static final Type<BalanceSyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ConsortiumCore.MOD_ID, "balance_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BalanceSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, BalanceSyncPayload::balance,
            ByteBufCodecs.VAR_LONG, BalanceSyncPayload::delta,
            ByteBufCodecs.STRING_UTF8, BalanceSyncPayload::reason,
            BalanceSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
