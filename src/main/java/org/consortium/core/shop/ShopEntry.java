package org.consortium.core.shop;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * One entry of the shop catalogue (specification v0.2, 5.1), resolved: exactly one of {@code item} (the stack sold,
 * never empty for an item entry) and {@code command} (the console template) is set.
 *
 * @param key         {@code [a-z0-9_]{1,32}}, the ledger {@code reason} of every purchase
 * @param name        1..48 characters
 * @param description tooltip, at most 96 characters, empty when absent
 * @param item        the sold stack ({@link ItemStack#EMPTY} for a command entry); never mutated, always copied
 * @param command     the template ({@code {player}}, {@code {uuid}}, {@code {tx}}), null for an item entry
 * @param icon        what the screen shows: the sold stack, or the {@code icon} item
 * @param priceCents  0.01 to 1,000,000.00 credits
 * @param dailyLimit  purchases per player per UTC day, 0 = unlimited
 * @param stage       the Chapters stage the buyer's team must hold, null when ungated
 * @param phase       the board phase from which the entry sells, 0 when ungated
 * @param source      the datapack file that defined it
 */
public record ShopEntry(String key, String name, String description, ItemStack item, @Nullable String command, ItemStack icon,
                        long priceCents, int dailyLimit, @Nullable ResourceLocation stage, int phase, String source) {

    public boolean isItem() {
        return !item.isEmpty();
    }

    public boolean isCommand() {
        return command != null;
    }

    /** {@code <id> x<count>} for the ledger and the log, or the rendered command of a command entry's line. */
    public String itemText() {
        if (!isItem()) {
            return "";
        }
        return BuiltInRegistries.ITEM.getKey(item.getItem()) + " x" + item.getCount();
    }
}
