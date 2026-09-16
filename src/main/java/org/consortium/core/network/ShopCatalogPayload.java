package org.consortium.core.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.shop.ShopJson;
import org.consortium.core.shop.ShopState;

import java.util.ArrayList;
import java.util.List;

/**
 * Server to client: the catalogue as one player sees it (specification v0.2, 5.2): the nonce a purchase must quote
 * back, then at most 128 entries with their icon (the sold stack, or the icon item of a command entry), price,
 * daily limit and today's count, availability state and description. Sent on open, after every purchase attempt,
 * on a nonce mismatch and to every viewer on {@code /reload}.
 */
public record ShopCatalogPayload(long nonce, List<Entry> entries) implements CustomPacketPayload {
    public static final Type<ShopCatalogPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ConsortiumCore.MOD_ID, "shop_catalog"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopCatalogPayload> STREAM_CODEC =
            StreamCodec.of(ShopCatalogPayload::write, ShopCatalogPayload::read);

    /**
     * One catalogue row. {@code icon} is a display copy (the sold stack when {@code sellsItem}, else the icon item of a
     * command entry): the client never sends it back. {@code phase} is the entry gate (0 = none) so the locked line can
     * name it.
     */
    public record Entry(String key, String name, ItemStack icon, boolean sellsItem, long priceCents, int dailyLimit, int boughtToday,
                        ShopState state, int phase, String description) {
        public boolean available() {
            return state == ShopState.AVAILABLE;
        }
    }

    public ShopCatalogPayload {
        entries = List.copyOf(entries);
    }

    /** The entry with that key, or null. */
    public Entry entry(String key) {
        for (Entry e : entries) {
            if (e.key().equals(key)) {
                return e;
            }
        }
        return null;
    }

    private static void write(RegistryFriendlyByteBuf buf, ShopCatalogPayload payload) {
        buf.writeVarLong(payload.nonce());
        buf.writeVarInt(payload.entries().size());
        for (Entry e : payload.entries()) {
            buf.writeUtf(e.key(), 32);
            buf.writeUtf(e.name(), ShopJson.MAX_NAME);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, e.icon());
            buf.writeBoolean(e.sellsItem());
            buf.writeVarLong(e.priceCents());
            buf.writeVarInt(e.dailyLimit());
            buf.writeVarInt(e.boughtToday());
            buf.writeByte(e.state().id());
            buf.writeVarInt(e.phase());
            buf.writeUtf(e.description(), ShopJson.MAX_DESCRIPTION);
        }
    }

    private static ShopCatalogPayload read(RegistryFriendlyByteBuf buf) {
        long nonce = buf.readVarLong();
        int count = buf.readVarInt();
        if (count < 0 || count > ShopJson.MAX_ENTRIES) {
            throw new IllegalArgumentException("ShopCatalog entry count out of range: " + count);
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String key = buf.readUtf(32);
            String name = buf.readUtf(ShopJson.MAX_NAME);
            ItemStack icon = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            boolean sellsItem = buf.readBoolean();
            long price = buf.readVarLong();
            int limit = buf.readVarInt();
            int bought = buf.readVarInt();
            ShopState state = ShopState.fromId(buf.readByte());
            int phase = buf.readVarInt();
            String description = buf.readUtf(ShopJson.MAX_DESCRIPTION);
            entries.add(new Entry(key, name, icon, sellsItem, price, limit, bought, state, phase, description));
        }
        return new ShopCatalogPayload(nonce, entries);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
