package org.consortium.core.terminal;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/** The block item of the terminal: no recipe (staff places it with {@code /give}), a one-line tooltip. */
public final class DeliveryTerminalItem extends BlockItem {
    public DeliveryTerminalItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.consortium.delivery_terminal.tooltip").withStyle(ChatFormatting.GOLD));
    }
}
