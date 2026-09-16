package org.consortium.core.terminal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.consortium.core.ConsortiumCore;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Server data of a Display Panel (specification v0.2, 2.1): the position of the terminal whose screen the panel
 * belongs to, absent while unformed. No update tag and no renderer: formed or not, the panel is the plain dark
 * block, and the owning terminal's renderer draws the screen over the whole rectangle.
 */
public final class DisplayPanelBlockEntity extends BlockEntity {
    @Nullable
    private BlockPos controller;

    public DisplayPanelBlockEntity(BlockPos pos, BlockState state) {
        super(ConsortiumCore.DISPLAY_PANEL_BE.get(), pos, state);
    }

    /** The owning terminal, or null when the panel is not part of a screen. */
    @Nullable
    public BlockPos controller() {
        return controller;
    }

    public void setController(@Nullable BlockPos pos) {
        BlockPos next = pos == null ? null : pos.immutable();
        if (!Objects.equals(controller, next)) {
            controller = next;
            setChanged();
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (controller != null) {
            tag.putInt("controller_x", controller.getX());
            tag.putInt("controller_y", controller.getY());
            tag.putInt("controller_z", controller.getZ());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("controller_x") && tag.contains("controller_y") && tag.contains("controller_z")) {
            controller = new BlockPos(tag.getInt("controller_x"), tag.getInt("controller_y"), tag.getInt("controller_z"));
        } else {
            controller = null;
        }
    }
}
