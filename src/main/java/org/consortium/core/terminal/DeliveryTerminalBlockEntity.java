package org.consortium.core.terminal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.consortium.core.ConsortiumCore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Delivery Station controller (specification v0.2, 2.1 and 2.2): the terminal's block entity keeps the screen
 * rectangle of Display Panels formed above it ({@code screenLeft}, {@code screenWidth}, {@code screenHeight} and the
 * {@code screenFacing} the rectangle was computed for), the cached render bounds the client renderer culls with, and
 * a {@code syncRevision} bumped by every load so the renderer knows when to rebuild its cache.
 *
 * <p>Minecraft-common only: no field is typed with a client class and no render cache lives here (the renderer owns
 * it), so this class can never turn into a {@code NoClassDefFoundError} on the dedicated server. The formation scan
 * itself is {@link ScreenFormation}; this class is its {@link BlockPos} / {@link Direction} adapter and runs it on the
 * server from the placement and removal hooks of the terminal and of the panels, never per tick.
 */
public final class DeliveryTerminalBlockEntity extends BlockEntity {
    private int screenLeft;
    private int screenWidth;
    private int screenHeight;
    private Direction screenFacing;
    private AABB renderBounds;
    private int syncRevision;

    public DeliveryTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(ConsortiumCore.DELIVERY_TERMINAL_BE.get(), pos, state);
        screenFacing = facingOf(state);
        recomputeBounds();
    }

    // ---- accessors ----

    /** Columns to the viewer's left of the terminal column. */
    public int screenLeft() {
        return screenLeft;
    }

    /** Columns of the screen, 0 when no screen is formed. */
    public int screenWidth() {
        return screenWidth;
    }

    /** Rows of the screen (1..4), 0 when no screen is formed. */
    public int screenHeight() {
        return screenHeight;
    }

    /** The facing the rectangle was computed for (the block's own facing, except during an in-place facing change). */
    public Direction screenFacing() {
        return screenFacing;
    }

    public boolean hasScreen() {
        return screenWidth > 0 && screenHeight > 0;
    }

    /** Panels of the screen, for the candidate order of a contested panel (the larger screen absorbs it). */
    public int screenArea() {
        return screenWidth * screenHeight;
    }

    /** World-space box of the screen rectangle (the terminal block alone when no screen is formed). */
    public AABB renderBounds() {
        return renderBounds;
    }

    /** +1 on every load from NBT or from a data packet: the renderer's cache key. */
    public int syncRevision() {
        return syncRevision;
    }

    /** The viewer's right when looking at the front face. */
    public static Direction rightOf(Direction facing) {
        return facing.getCounterClockWise();
    }

    static Direction facingOf(BlockState state) {
        return state.hasProperty(HorizontalDirectionalBlock.FACING) ? state.getValue(HorizontalDirectionalBlock.FACING) : Direction.NORTH;
    }

    /** Position of column {@code column} (viewer's right positive) at row {@code row} above the terminal. */
    public BlockPos screenPos(Direction facing, int column, int row) {
        return worldPosition.above(row).relative(rightOf(facing), column);
    }

    // ---- formation (server) ----

    /**
     * Recomputes the screen from the panels around the terminal (2.2). The old rectangle comes from the stored ints
     * and facing, never from the current state, so an in-place facing change releases its old panels instead of
     * stranding them; the new one from the block's current facing. Syncs the client when anything changed.
     */
    public void rescan() {
        Level level = this.level;
        if (level == null || level.isClientSide()) {
            return;
        }
        BlockState state = level.getBlockState(worldPosition);
        if (!state.is(ConsortiumCore.DELIVERY_TERMINAL.get())) {
            return;
        }
        Direction facing = facingOf(state);
        List<BlockPos> old = rectangle(screenFacing, screenLeft, screenWidth, screenHeight);
        ScreenFormation.Result result = ScreenFormation.scan((column, row) -> available(level, facing, column, row));
        List<BlockPos> now = rectangle(facing, result.left(), result.width(), result.height());
        Set<BlockPos> kept = new HashSet<>(now);
        for (BlockPos pos : old) {
            if (!kept.contains(pos)) {
                clearPanel(level, pos);
            }
        }
        for (BlockPos pos : now) {
            claimPanel(level, pos);
        }
        apply(facing, result.left(), result.width(), result.height(), true);
    }

    /**
     * Frees every panel of the stored rectangle and forgets it (the terminal is being removed or turned in place).
     *
     * @param sync true when the block stays (facing change): the client entity must drop the old rectangle
     */
    public void release(boolean sync) {
        Level level = this.level;
        if (level == null || level.isClientSide()) {
            return;
        }
        for (BlockPos pos : rectangle(screenFacing, screenLeft, screenWidth, screenHeight)) {
            clearPanel(level, pos);
        }
        apply(screenFacing, 0, 0, 0, sync);
    }

    /** The {@link ScreenFormation.Probe}: an available Display Panel at (column, row) for the given facing. */
    private boolean available(Level level, Direction facing, int column, int row) {
        BlockPos pos = screenPos(facing, column, row);
        if (!level.getBlockState(pos).is(ConsortiumCore.DISPLAY_PANEL.get())) {
            return false;
        }
        if (!(level.getBlockEntity(pos) instanceof DisplayPanelBlockEntity panel)) {
            return true;
        }
        BlockPos owner = panel.controller();
        if (owner == null || owner.equals(worldPosition)) {
            return true;
        }
        // A pointer at a position that no longer holds a terminal is stale: the panel is free.
        return !level.getBlockState(owner).is(ConsortiumCore.DELIVERY_TERMINAL.get());
    }

    private void claimPanel(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof DisplayPanelBlockEntity panel) {
            panel.setController(worldPosition);
        }
    }

    private void clearPanel(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof DisplayPanelBlockEntity panel && worldPosition.equals(panel.controller())) {
            panel.setController(null);
        }
    }

    private List<BlockPos> rectangle(Direction facing, int left, int width, int height) {
        List<BlockPos> out = new ArrayList<>(width * height);
        for (int row = 1; row <= height; row++) {
            for (int column = -left; column <= width - 1 - left; column++) {
                out.add(screenPos(facing, column, row));
            }
        }
        return out;
    }

    private void apply(Direction facing, int left, int width, int height, boolean sync) {
        if (facing == screenFacing && left == screenLeft && width == screenWidth && height == screenHeight) {
            return;
        }
        screenFacing = facing;
        screenLeft = left;
        screenWidth = width;
        screenHeight = height;
        recomputeBounds();
        setChanged();
        if (sync && level != null) {
            BlockState state = level.getBlockState(worldPosition);
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    private void recomputeBounds() {
        if (!hasScreen() || screenFacing == null) {
            renderBounds = new AABB(worldPosition);
            return;
        }
        BlockPos a = screenPos(screenFacing, -screenLeft, 1);
        BlockPos b = screenPos(screenFacing, screenWidth - 1 - screenLeft, screenHeight);
        renderBounds = AABB.encapsulatingFullBlocks(a, b);
    }

    // ---- NBT and sync ----

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("screen_left", screenLeft);
        tag.putInt("screen_width", screenWidth);
        tag.putInt("screen_height", screenHeight);
        tag.putString("screen_facing", screenFacing.getSerializedName());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        int oldLeft = screenLeft;
        int oldWidth = screenWidth;
        int oldHeight = screenHeight;
        Direction oldFacing = screenFacing;

        int width = clamp(tag.getInt("screen_width"), 0, ScreenFormation.MAX_WIDTH);
        int height = clamp(tag.getInt("screen_height"), 0, ScreenFormation.MAX_HEIGHT);
        int left = width == 0 ? 0 : clamp(tag.getInt("screen_left"), 0, width - 1);
        if (width == 0 || height == 0) {
            width = 0;
            height = 0;
            left = 0;
        }
        Direction facing = tag.contains("screen_facing") ? Direction.byName(tag.getString("screen_facing")) : null;
        if (facing == null || !facing.getAxis().isHorizontal()) {
            // Chunks saved before the field existed: the rectangle was computed for the block's own facing.
            facing = facingOf(getBlockState());
        }
        screenLeft = left;
        screenWidth = width;
        screenHeight = height;
        screenFacing = facing;
        syncRevision++;
        recomputeBounds();

        boolean changed = oldLeft != left || oldWidth != width || oldHeight != height || oldFacing != facing;
        if (changed && level != null && level.isClientSide()) {
            // ClientLevel forwards this to LevelRenderer.blockChanged: the sections around the terminal recompile
            // and re-collect this entity with its current shouldRenderOffScreen value. Covers a terminal placed
            // before the block entity existed (created here by the data packet, never collected by any compile
            // until now) and a rectangle change that never touches the terminal's own section.
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 0);
        }
    }

    /** Chunk load sync: the full tag. */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    /** Block update sync: the vanilla packet, which carries {@link #getUpdateTag}; the client runs loadAdditional. */
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Called by the chunk when a state change keeps the entity (an in-place facing change): the box follows. */
    @Override
    @SuppressWarnings("deprecation")
    public void setBlockState(BlockState blockState) {
        super.setBlockState(blockState);
        recomputeBounds();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
