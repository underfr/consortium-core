package org.consortium.core.terminal;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.network.ConsortiumNetwork;

import java.util.ArrayList;
import java.util.List;

/**
 * The terminal's menu (specification 5), modelled on {@code CraftingMenu}: the 27-slot grid is a transient
 * {@link SimpleContainer} owned by the menu, given back to the player when the menu closes (into the inventory, or
 * dropped at the feet of a disconnected player). Two players on one terminal get two independent grids.
 *
 * <p>Server side, every grid change marks the quote dirty; the quote is recomputed at most once per tick from a
 * snapshot of the grid and pushed to the client as {@code DeliveryQuote} with a fresh nonce. {@link #deliver} is the
 * {@code DeliveryConfirm} handler: it checks the nonce, then hands the grid to {@link DeliveryService#confirm}.
 */
public final class DeliveryTerminalMenu extends AbstractContainerMenu {
    public static final int GRID_SLOTS = 27;
    public static final int GRID_COLUMNS = 9;
    public static final int INVENTORY_SLOTS = 36;

    /** Screen geometry (pixels inside the panel), shared with the client screen. */
    public static final int PANEL_WIDTH = 236;
    public static final int PANEL_HEIGHT = 236;
    public static final int HEADER_HEIGHT = 92;
    public static final int GRID_X = 38;
    public static final int GRID_Y = HEADER_HEIGHT + 4;
    public static final int INVENTORY_Y = GRID_Y + 3 * 18 + 6;
    public static final int HOTBAR_Y = INVENTORY_Y + 3 * 18 + 4;

    public static final String STALE_QUOTE = "Quote updated, click Deliver again";

    private final SimpleContainer grid = new SimpleContainer(GRID_SLOTS) {
        @Override
        public void setChanged() {
            super.setChanged();
            DeliveryTerminalMenu.this.slotsChanged(this);
        }
    };
    private final ContainerLevelAccess access;
    private final Player player;
    private final BlockPos pos;

    // Server side quote state.
    private boolean gridDirty = true;
    private boolean suppressQuotes;
    private long lastQuoteTick = Long.MIN_VALUE;
    private List<ItemStack> quotedSnapshot;
    private Quote currentQuote;
    private long usedNonce;

    // Client side display state, fed by the DeliveryQuote payload.
    private Quote clientQuote;

    /** Client constructor ({@code IContainerFactory}): the extra data carries the terminal position. */
    public DeliveryTerminalMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, playerInventory, ContainerLevelAccess.NULL, extraData == null ? BlockPos.ZERO : extraData.readBlockPos());
    }

    /** Server constructor. */
    public DeliveryTerminalMenu(int containerId, Inventory playerInventory, ContainerLevelAccess access, BlockPos pos) {
        super(ConsortiumCore.DELIVERY_TERMINAL_MENU.get(), containerId);
        this.access = access;
        this.player = playerInventory.player;
        this.pos = pos;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < GRID_COLUMNS; col++) {
                addSlot(new Slot(grid, col + row * GRID_COLUMNS, GRID_X + col * 18, GRID_Y + row * 18));
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, GRID_X + col * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, GRID_X + col * 18, HOTBAR_Y));
        }
    }

    public BlockPos terminalPos() {
        return pos;
    }

    // ---- vanilla hooks ----

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, ConsortiumCore.DELIVERY_TERMINAL.get());
    }

    @Override
    public void slotsChanged(Container container) {
        gridDirty = true;
        super.slotsChanged(container);
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        refreshQuote(false);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        access.execute((level, blockPos) -> clearContainer(player, grid));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (index < GRID_SLOTS) {
                if (!moveItemStackTo(stack, GRID_SLOTS, slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, 0, GRID_SLOTS, false)) {
                return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
            if (stack.getCount() == result.getCount()) {
                return ItemStack.EMPTY;
            }
            slot.onTake(player, stack);
        }
        return result;
    }

    // ---- server side: quote and deliver ----

    private List<ItemStack> snapshot() {
        List<ItemStack> out = new ArrayList<>(GRID_SLOTS);
        for (int i = 0; i < GRID_SLOTS; i++) {
            out.add(grid.getItem(i).copy());
        }
        return out;
    }

    private static boolean sameSnapshot(List<ItemStack> a, List<ItemStack> b) {
        if (a == null || b == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!ItemStack.matches(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Recomputes and sends the quote when the grid changed since the last one, at most once per tick unless forced.
     * Server side only; a no-op on the client.
     */
    private void refreshQuote(boolean force) {
        if (!(player instanceof ServerPlayer serverPlayer) || suppressQuotes || !gridDirty) {
            return;
        }
        long tick = serverPlayer.serverLevel().getGameTime();
        if (!force && tick == lastQuoteTick) {
            return;
        }
        gridDirty = false;
        lastQuoteTick = tick;
        List<ItemStack> snapshot = snapshot();
        if (!force && sameSnapshot(snapshot, quotedSnapshot)) {
            return;
        }
        quotedSnapshot = snapshot;
        currentQuote = DeliveryService.quote(serverPlayer, snapshot);
        ConsortiumNetwork.sendQuote(serverPlayer, currentQuote);
    }

    /**
     * {@code DeliveryConfirm} handler. A reused nonce (click spam, replay) is ignored; a stale one (the grid changed
     * since that quote) gets the current quote back with {@link #STALE_QUOTE}; a matching one runs the transaction.
     */
    public void deliver(ServerPlayer serverPlayer, long nonce) {
        if (serverPlayer != player || !stillValid(serverPlayer)) {
            return;
        }
        if (nonce == usedNonce) {
            return;
        }
        // Bring the quote up to date with the grid as it is right now (a move and the click may share a tick).
        refreshQuote(true);
        if (currentQuote == null || currentQuote.nonce() != nonce) {
            if (currentQuote != null) {
                currentQuote = currentQuote.withMessage(STALE_QUOTE);
                ConsortiumNetwork.sendQuote(serverPlayer, currentQuote);
            }
            return;
        }
        usedNonce = nonce;
        Quote quoted = currentQuote;
        currentQuote = null;
        DeliveryService.Confirmation confirmation;
        suppressQuotes = true;
        try {
            confirmation = DeliveryService.confirm(serverPlayer, grid, quoted, serverPlayer.level().dimension(), pos);
        } finally {
            suppressQuotes = false;
        }
        if (confirmation.delivered()) {
            // The accepted stacks are gone: push the emptied slots and a fresh quote right away.
            gridDirty = true;
            super.broadcastChanges();
            refreshQuote(true);
        } else {
            // Refused (prices moved, nothing accepted, ledger unavailable): the fresh quote carries the reason.
            currentQuote = confirmation.quote();
            quotedSnapshot = snapshot();
            gridDirty = false;
            ConsortiumNetwork.sendQuote(serverPlayer, currentQuote);
        }
    }

    // ---- client side ----

    public void setClientQuote(Quote quote) {
        this.clientQuote = quote;
    }

    /** The last quote received from the server, or null before the first one. */
    public Quote clientQuote() {
        return clientQuote;
    }

    /** The refusal reason of a grid slot in the last quote, or null when the slot is accepted or empty. */
    public String refusalOf(int gridSlot) {
        if (clientQuote == null) {
            return null;
        }
        for (Quote.Refusal r : clientQuote.refused()) {
            if (r.slot() == gridSlot) {
                return r.reason();
            }
        }
        return null;
    }
}
