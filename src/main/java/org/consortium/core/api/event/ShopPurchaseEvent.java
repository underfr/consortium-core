package org.consortium.core.api.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;

/**
 * Posted on {@code NeoForge.EVENT_BUS} after a shop purchase was committed and its effect ran (ledger written,
 * balance moved, item given or command run; v0.2 section 7, an addition to the brief). Not cancellable: nothing a
 * listener does can roll money back. Posted inside {@code try/catch (Throwable)}: a throwing handler never breaks
 * a purchase. Bean getters so KubeJS exposes the fields as properties.
 */
public class ShopPurchaseEvent extends Event {
    private final ServerPlayer player;
    private final String key;
    private final long cents;
    private final String txId;
    private final ItemStack item;
    private final String command;

    public ShopPurchaseEvent(ServerPlayer player, String key, long cents, String txId, ItemStack item, String command) {
        this.player = player;
        this.key = key;
        this.cents = cents;
        this.txId = txId;
        this.item = item == null ? ItemStack.EMPTY : item.copy();
        this.command = command == null ? "" : command;
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    /** The catalogue key (also the ledger {@code reason}). */
    public String getKey() {
        return key;
    }

    /** The price paid, in cents. */
    public long getCents() {
        return cents;
    }

    public String getTxId() {
        return txId;
    }

    /** A copy of the stack given, {@link ItemStack#EMPTY} for a command entry. */
    public ItemStack getItem() {
        return item;
    }

    /** The rendered command of a command entry, empty for an item entry. */
    public String getCommand() {
        return command;
    }
}
