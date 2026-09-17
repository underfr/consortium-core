package org.consortium.core.terminal;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The bucket return of v0.3.1 (PRICE_TABLE 1.3): the empty bucket comes back into the slot, other stacks vanish, refused slots stay. */
class GridClearTest {
    private static final String EMPTY = "";

    private static String remainder(String stack) {
        return stack.endsWith("_bucket") ? "bucket" : EMPTY;
    }

    @Test
    void oneBucketGrid() {
        Map<Integer, String> grid = new HashMap<>(Map.of(0, "lava_bucket", 1, EMPTY, 2, "iron_ingot", 3, "netherite_scrap"));
        int written = GridClear.clearAccepted(4, Set.of(3), grid::get, String::isEmpty, GridClearTest::remainder, grid::put);
        assertEquals(2, written);
        assertEquals("bucket", grid.get(0), "the empty bucket is returned");
        assertEquals(EMPTY, grid.get(1));
        assertEquals(EMPTY, grid.get(2), "an accepted ingot stack is removed");
        assertEquals("netherite_scrap", grid.get(3), "a refused slot is untouched");
    }
}
