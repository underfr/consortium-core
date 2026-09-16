package org.consortium.core.terminal;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.consortium.core.monitoring.Notifier;

import javax.annotation.Nullable;

/**
 * The Delivery Terminal (specification 5, v0.2 2.1): a block with no inventory and no capability, so hoppers, pipes
 * and AE2 find nothing to feed and a delivery is always a human action. Right-clicking opens
 * {@link DeliveryTerminalMenu}, whose 27-slot grid belongs to the menu, not to the block; a fake player or a
 * creative-mode player gets nothing (the latter a chat line). Since v0.2 it is the controller of a Delivery Station:
 * its {@link DeliveryTerminalBlockEntity} keeps the rectangle of Display Panels above it (nothing else in NBT) and
 * the client renderer draws the quota board over that rectangle. Placement rescans the panels; removal, or an
 * in-place facing change, releases them.
 */
public final class DeliveryTerminalBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<DeliveryTerminalBlock> CODEC = simpleCodec(DeliveryTerminalBlock::new);
    private static final Component TITLE = Component.translatable("container.consortium.delivery_terminal");

    public DeliveryTerminalBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DeliveryTerminalBlockEntity(pos, state);
    }

    /**
     * Both placement paths end here: {@code setblock} and {@code Level.setBlock} call it before the chunk creates
     * the block entity (so {@code getBlockEntity} creates it on demand), player placement after (NeoForge defers
     * the call until the place event was not cancelled).
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof DeliveryTerminalBlockEntity terminal) {
            terminal.rescan();
        }
    }

    /** Another block, or the same block with another facing: the panels of the stored rectangle are freed first. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide()) {
            boolean stays = newState.is(this);
            if (!stays || newState.getValue(FACING) != state.getValue(FACING)) {
                if (level.getBlockEntity(pos) instanceof DeliveryTerminalBlockEntity terminal) {
                    terminal.release(stays);
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof FakePlayer) {
            // A Create deployer or similar would otherwise touch the account path with a fake uuid.
            return InteractionResult.FAIL;
        }
        if (player.isCreative()) {
            // Creative items have no marginal cost (rule 4.1): the terminal never opens for a creative player.
            if (!level.isClientSide()) {
                player.sendSystemMessage(Notifier.prefixed(DeliveryService.CREATIVE_REFUSED));
            }
            return InteractionResult.FAIL;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(getMenuProvider(state, level, pos), pos);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return menuProvider(level, pos);
    }

    /** The terminal menu at {@code pos}; also what the shop's Back button reopens (v0.2, 5.2). */
    public static MenuProvider menuProvider(Level level, BlockPos pos) {
        return new SimpleMenuProvider(
                (id, inventory, player) -> new DeliveryTerminalMenu(id, inventory, ContainerLevelAccess.create(level, pos), pos),
                TITLE);
    }
}
