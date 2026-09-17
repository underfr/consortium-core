package org.consortium.core.terminal;

import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * What the terminal leaves in the grid after a committed delivery (v0.3.1, PRICE_TABLE 1.3): every accepted slot
 * gets the crafting remainder of its stack (the empty bucket of a lava or fuel bucket, nothing for anything else),
 * refused slots are untouched. Generic over the slot type so the rule is unit-tested without an {@code ItemStack}.
 */
public final class GridClear {
    private GridClear() {
    }

    /**
     * @param size      slots in the grid
     * @param refused   slot indexes the quote refused (kept as they are)
     * @param get       the stack of a slot
     * @param empty     true for an empty stack
     * @param remainder the crafting remainder of a stack (an empty stack when it has none)
     * @param set       writes a slot
     * @return the number of slots written
     */
    public static <T> int clearAccepted(int size, Set<Integer> refused, IntFunction<T> get, Predicate<T> empty, Function<T, T> remainder,
                                        BiConsumer<Integer, T> set) {
        int written = 0;
        for (int i = 0; i < size; i++) {
            T stack = get.apply(i);
            if (stack == null || empty.test(stack) || refused.contains(i)) {
                continue;
            }
            set.accept(i, remainder.apply(stack));
            written++;
        }
        return written;
    }
}
