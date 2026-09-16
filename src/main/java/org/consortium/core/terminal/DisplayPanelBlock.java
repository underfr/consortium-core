package org.consortium.core.terminal;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.consortium.core.ConsortiumCore;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The Display Panel (specification v0.2, 2.1): a plain dark block with no facing that becomes part of a Delivery
 * Station screen when it stands in the rectangle above a terminal. It has no renderer of its own; the terminal's
 * renderer draws the screen over the whole rectangle. Placement and removal ask the terminals that could be affected
 * to rescan (2.2): the terminal in the same column 1..4 blocks below, the owner of each adjacent panel and, on
 * removal, the panel's own owner. A panel that completes or extends a rectangle is always adjacent to a formed panel
 * or stands on the terminal column, so no other terminal needs to hear about it.
 */
public final class DisplayPanelBlock extends Block implements EntityBlock {
    public static final MapCodec<DisplayPanelBlock> CODEC = simpleCodec(DisplayPanelBlock::new);

    public DisplayPanelBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DisplayPanelBlockEntity(pos, state);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide() && !oldState.is(this)) {
            rescan(candidates(level, pos, false));
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (level.isClientSide() || newState.is(this)) {
            super.onRemove(state, level, pos, newState, movedByPiston);
            return;
        }
        List<DeliveryTerminalBlockEntity> candidates = candidates(level, pos, true);
        super.onRemove(state, level, pos, newState, movedByPiston);
        rescan(candidates);
    }

    /**
     * The terminals to rescan for a panel at {@code pos}, in a deterministic order: the terminal below in the same
     * column first (the panel is its column 0), then the other owners by screen size, the larger screen first, so a
     * contested panel goes to the screen that already stands (the position breaks the last ties).
     */
    private static List<DeliveryTerminalBlockEntity> candidates(Level level, BlockPos pos, boolean includeOwner) {
        Set<BlockPos> positions = new LinkedHashSet<>();
        if (includeOwner && level.getBlockEntity(pos) instanceof DisplayPanelBlockEntity own && own.controller() != null) {
            positions.add(own.controller());
        }
        List<DeliveryTerminalBlockEntity> below = new ArrayList<>();
        for (int d = 1; d <= ScreenFormation.MAX_HEIGHT; d++) {
            BlockPos p = pos.below(d);
            if (level.getBlockState(p).is(ConsortiumCore.DELIVERY_TERMINAL.get())) {
                positions.add(p);
                if (level.getBlockEntity(p) instanceof DeliveryTerminalBlockEntity terminal) {
                    below.add(terminal);
                }
            }
        }
        for (Direction side : Direction.values()) {
            if (level.getBlockEntity(pos.relative(side)) instanceof DisplayPanelBlockEntity panel && panel.controller() != null) {
                positions.add(panel.controller());
            }
        }
        List<DeliveryTerminalBlockEntity> others = new ArrayList<>();
        for (BlockPos p : positions) {
            if (level.getBlockState(p).is(ConsortiumCore.DELIVERY_TERMINAL.get())
                    && level.getBlockEntity(p) instanceof DeliveryTerminalBlockEntity terminal && !below.contains(terminal)) {
                others.add(terminal);
            }
        }
        others.sort(Comparator.comparingInt(DeliveryTerminalBlockEntity::screenArea).reversed()
                .thenComparing(DeliveryTerminalBlockEntity::getBlockPos));
        List<DeliveryTerminalBlockEntity> out = new ArrayList<>(below);
        out.addAll(others);
        return out;
    }

    private static void rescan(List<DeliveryTerminalBlockEntity> terminals) {
        for (DeliveryTerminalBlockEntity terminal : terminals) {
            if (!terminal.isRemoved()) {
                terminal.rescan();
            }
        }
    }
}
