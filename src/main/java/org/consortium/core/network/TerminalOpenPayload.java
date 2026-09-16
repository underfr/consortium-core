package org.consortium.core.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.consortium.core.ConsortiumCore;

/**
 * Client to server: the Back button of the shop screen (specification v0.2, 5.2, an addition to the brief). From a
 * shop menu opened at that terminal and still valid, the server reopens the terminal menu (fresh grid, no quote).
 */
public record TerminalOpenPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<TerminalOpenPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ConsortiumCore.MOD_ID, "terminal_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalOpenPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeBlockPos(payload.pos()),
            buf -> new TerminalOpenPayload(buf.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
