package org.consortium.core.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.terminal.Quote;

import java.util.ArrayList;
import java.util.List;

/**
 * Server to client: the current quote of the player's open terminal grid (specification 5). Carries the
 * {@link Quote} record as is: one line per family, the refused slots with their reason, the warnings, the total and
 * an optional message. A full 27-slot grid stays far below the payload limits.
 */
public record DeliveryQuotePayload(Quote quote) implements CustomPacketPayload {
    public static final Type<DeliveryQuotePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ConsortiumCore.MOD_ID, "delivery_quote"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DeliveryQuotePayload> STREAM_CODEC =
            StreamCodec.of(DeliveryQuotePayload::write, DeliveryQuotePayload::read);

    /** Longest string accepted from the wire (names and reasons are short; the cap only bounds a hostile peer). */
    private static final int MAX_TEXT = 256;
    private static final int MAX_ENTRIES = 512;

    private static void write(RegistryFriendlyByteBuf buf, DeliveryQuotePayload payload) {
        Quote q = payload.quote();
        buf.writeVarLong(q.nonce());
        buf.writeVarInt(q.lines().size());
        for (Quote.Line line : q.lines()) {
            buf.writeUtf(line.family(), MAX_TEXT);
            buf.writeUtf(line.name(), MAX_TEXT);
            buf.writeVarInt(line.items().size());
            for (String item : line.items()) {
                buf.writeUtf(item, MAX_TEXT);
            }
            buf.writeDouble(line.units());
            buf.writeDouble(line.paidUnits());
            buf.writeDouble(line.quotaUnits());
            buf.writeVarLong(line.unitCents());
            buf.writeVarLong(line.subtotalCents());
            buf.writeVarInt(line.multiplierPercent());
            buf.writeBoolean(line.quotaOnly());
        }
        buf.writeVarInt(q.refused().size());
        for (Quote.Refusal r : q.refused()) {
            buf.writeVarInt(r.slot());
            buf.writeUtf(r.reason(), MAX_TEXT);
        }
        buf.writeVarInt(q.warnings().size());
        for (Quote.Warning w : q.warnings()) {
            buf.writeUtf(w.family(), MAX_TEXT);
            buf.writeUtf(w.text(), MAX_TEXT);
        }
        buf.writeVarLong(q.totalCents());
        buf.writeUtf(q.message() == null ? "" : q.message(), MAX_TEXT);
    }

    private static DeliveryQuotePayload read(RegistryFriendlyByteBuf buf) {
        long nonce = buf.readVarLong();
        int lineCount = bounded(buf.readVarInt());
        List<Quote.Line> lines = new ArrayList<>(lineCount);
        for (int i = 0; i < lineCount; i++) {
            String family = buf.readUtf(MAX_TEXT);
            String name = buf.readUtf(MAX_TEXT);
            int itemCount = bounded(buf.readVarInt());
            List<String> items = new ArrayList<>(itemCount);
            for (int j = 0; j < itemCount; j++) {
                items.add(buf.readUtf(MAX_TEXT));
            }
            double units = buf.readDouble();
            double paidUnits = buf.readDouble();
            double quotaUnits = buf.readDouble();
            long unitCents = buf.readVarLong();
            long subtotalCents = buf.readVarLong();
            int multiplierPercent = buf.readVarInt();
            boolean quotaOnly = buf.readBoolean();
            lines.add(new Quote.Line(family, name, items, units, paidUnits, quotaUnits, unitCents, subtotalCents, multiplierPercent, quotaOnly));
        }
        int refusedCount = bounded(buf.readVarInt());
        List<Quote.Refusal> refused = new ArrayList<>(refusedCount);
        for (int i = 0; i < refusedCount; i++) {
            refused.add(new Quote.Refusal(buf.readVarInt(), buf.readUtf(MAX_TEXT)));
        }
        int warningCount = bounded(buf.readVarInt());
        List<Quote.Warning> warnings = new ArrayList<>(warningCount);
        for (int i = 0; i < warningCount; i++) {
            warnings.add(new Quote.Warning(buf.readUtf(MAX_TEXT), buf.readUtf(MAX_TEXT)));
        }
        long total = buf.readVarLong();
        String message = buf.readUtf(MAX_TEXT);
        return new DeliveryQuotePayload(new Quote(nonce, lines, refused, warnings, total, message.isEmpty() ? null : message));
    }

    private static int bounded(int count) {
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IllegalArgumentException("DeliveryQuote entry count out of range: " + count);
        }
        return count;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
