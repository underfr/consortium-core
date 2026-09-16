package org.consortium.core.terminal;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/** The block item of the Display Panel: a one-line tooltip telling where it goes (above a Delivery Terminal). */
public final class DisplayPanelItem extends BlockItem {
    public DisplayPanelItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.consortium.display_panel.tooltip").withStyle(ChatFormatting.GOLD));
    }
}
