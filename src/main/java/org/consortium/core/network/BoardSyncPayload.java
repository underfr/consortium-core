package org.consortium.core.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.board.BoardSnapshot;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Server to client: the quota board (specification v0.2, 2.5). {@code snapshot} is null when nothing was published
 * ({@code published = false} on the wire), which a client coming from another server needs to reset its board.
 * Sent to every online player on each accepted change and to each player at login, right after the balance.
 * At most 32 lines of short strings: about 6 KiB.
 */
public record BoardSyncPayload(int revision, @Nullable BoardSnapshot snapshot) implements CustomPacketPayload {
    public static final Type<BoardSyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ConsortiumCore.MOD_ID, "board_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BoardSyncPayload> STREAM_CODEC =
            StreamCodec.of(BoardSyncPayload::write, BoardSyncPayload::read);

    public boolean published() {
        return snapshot != null;
    }

    private static void write(RegistryFriendlyByteBuf buf, BoardSyncPayload payload) {
        buf.writeBoolean(payload.snapshot != null);
        buf.writeVarInt(payload.revision);
        BoardSnapshot s = payload.snapshot;
        if (s == null) {
            return;
        }
        buf.writeVarInt(s.phase());
        buf.writeUtf(s.name(), BoardSnapshot.MAX_NAME);
        buf.writeVarInt(s.day());
        buf.writeVarInt(s.days());
        buf.writeFloat((float) s.completion());
        buf.writeBoolean(s.complete());
        buf.writeVarInt(s.lines().size());
        for (BoardSnapshot.Line line : s.lines()) {
            buf.writeUtf(line.key(), BoardSnapshot.MAX_KEY);
            buf.writeUtf(line.label(), BoardSnapshot.MAX_LABEL);
            buf.writeUtf(line.icon(), BoardSnapshot.MAX_ICON);
            buf.writeVarLong(line.current());
            buf.writeVarLong(line.target());
        }
    }

    private static BoardSyncPayload read(RegistryFriendlyByteBuf buf) {
        boolean published = buf.readBoolean();
        int revision = buf.readVarInt();
        if (!published) {
            return new BoardSyncPayload(revision, null);
        }
        int phase = buf.readVarInt();
        String name = buf.readUtf(BoardSnapshot.MAX_NAME);
        int day = buf.readVarInt();
        int days = buf.readVarInt();
        float completion = buf.readFloat();
        boolean complete = buf.readBoolean();
        int count = buf.readVarInt();
        if (count < 0 || count > BoardSnapshot.MAX_LINES) {
            throw new IllegalArgumentException("BoardSync line count out of range: " + count);
        }
        List<BoardSnapshot.Line> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String key = buf.readUtf(BoardSnapshot.MAX_KEY);
            String label = buf.readUtf(BoardSnapshot.MAX_LABEL);
            String icon = buf.readUtf(BoardSnapshot.MAX_ICON);
            long current = buf.readVarLong();
            long target = buf.readVarLong();
            lines.add(new BoardSnapshot.Line(key, label, icon, current, target));
        }
        return new BoardSyncPayload(revision, new BoardSnapshot(phase, name, day, days, completion, complete, lines));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
