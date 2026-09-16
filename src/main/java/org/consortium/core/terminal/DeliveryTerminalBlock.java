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
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.consortium.core.monitoring.Notifier;

/**
 * The Delivery Terminal (specification 5): a plain block with no block entity, no inventory and nothing in NBT.
 * Hoppers, pipes and AE2 find no container and no item handler capability, so a delivery is always a human action.
 * Right-clicking opens {@link DeliveryTerminalMenu}, whose 27-slot grid belongs to the menu, not to the block; a
 * fake player or a creative-mode player gets nothing (the latter a chat line).
 */
public final class DeliveryTerminalBlock extends HorizontalDirectionalBlock {
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
        return new SimpleMenuProvider(
                (id, inventory, player) -> new DeliveryTerminalMenu(id, inventory, ContainerLevelAccess.create(level, pos), pos),
                TITLE);
    }
}
