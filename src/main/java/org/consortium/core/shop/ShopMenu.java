package org.consortium.core.shop;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.network.ShopCatalogPayload;
import org.consortium.core.network.ShopResultPayload;

import java.security.SecureRandom;

/**
 * The shop menu (specification v0.2, 5.2): zero slots, opened from the terminal screen's Shop button (the terminal
 * menu closes first and hands the grid back, which is why the button needs an empty grid) or by
 * {@code /ccore shop open} with {@link ContainerLevelAccess#NULL} (then {@link #stillValid} is always true and the
 * Back button just closes the screen).
 *
 * <p>Server state: the current nonce, fresh for every {@code shop_catalog} sent and rotated before every purchase
 * attempt, exactly like the terminal's {@code DeliveryConfirm}. Client state: the last catalogue and result
 * payloads, read by the screen.
 */
public final class ShopMenu extends AbstractContainerMenu {
    /** Screen geometry (pixels inside the panel), shared with the client screen. */
    public static final int PANEL_WIDTH = 236;
    public static final int PANEL_HEIGHT = 222;

    private static final SecureRandom NONCES = new SecureRandom();

    private final ContainerLevelAccess access;
    private final Player player;
    private final BlockPos pos;
    private final boolean fromTerminal;

    // Server side.
    private long nonce = NONCES.nextLong();

    // Client side display state, fed by the payloads.
    private ShopCatalogPayload clientCatalog;
    private ShopResultPayload clientResult;
    private int resultGeneration;

    /** Client constructor ({@code IContainerFactory}): the extra data carries the terminal position and whether one exists. */
    public ShopMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, playerInventory, ContainerLevelAccess.NULL,
                extraData == null ? BlockPos.ZERO : extraData.readBlockPos(),
                extraData != null && extraData.readBoolean());
    }

    /** Server constructor: {@code access} is {@link ContainerLevelAccess#NULL} when no terminal is involved. */
    public ShopMenu(int containerId, Inventory playerInventory, ContainerLevelAccess access, BlockPos pos) {
        this(containerId, playerInventory, access, pos, access != ContainerLevelAccess.NULL);
    }

    private ShopMenu(int containerId, Inventory playerInventory, ContainerLevelAccess access, BlockPos pos, boolean fromTerminal) {
        super(ConsortiumCore.SHOP_MENU.get(), containerId);
        this.access = access;
        this.player = playerInventory.player;
        this.pos = pos;
        this.fromTerminal = fromTerminal;
    }

    /** What the server writes as extra data so the client constructor above can read it. */
    public static void writeExtraData(RegistryFriendlyByteBuf buf, BlockPos pos, boolean fromTerminal) {
        buf.writeBlockPos(pos);
        buf.writeBoolean(fromTerminal);
    }

    public BlockPos terminalPos() {
        return pos;
    }

    /** True when a Delivery Terminal opened this menu (the Back button can return to it). */
    public boolean fromTerminal() {
        return fromTerminal;
    }

    public Player player() {
        return player;
    }

    // ---- vanilla hooks ----

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, ConsortiumCore.DELIVERY_TERMINAL.get());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    // ---- server side ----

    public long nonce() {
        return nonce;
    }

    /** A fresh nonce: called before every catalogue send and before every purchase attempt. */
    public long rotateNonce() {
        nonce = NONCES.nextLong();
        return nonce;
    }

    // ---- client side ----

    public void setClientCatalog(ShopCatalogPayload catalog) {
        this.clientCatalog = catalog;
    }

    /** The last catalogue received, or null before the first one. */
    public ShopCatalogPayload clientCatalog() {
        return clientCatalog;
    }

    public void setClientResult(ShopResultPayload result) {
        this.clientResult = result;
        this.resultGeneration++;
    }

    /** The last purchase result received, or null. */
    public ShopResultPayload clientResult() {
        return clientResult;
    }

    /** Incremented on every result received, so the screen can tell a new one from the last it showed. */
    public int resultGeneration() {
        return resultGeneration;
    }
}
