package org.consortium.core.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link ScreenFormation#scan} with a char grid: the top line is the highest row, the last line is row 1,
 * 13 columns with the terminal column (0) at index 6; {@code #} = available panel, {@code x} = panel owned by
 * another live terminal, {@code .} = anything else. Column c maps to index 6 + c.
 */
class ScreenFormationTest {
    private static ScreenFormation.Probe grid(String... rowsTopDown) {
        return (column, row) -> {
            int r = rowsTopDown.length - row; // row 1 is the last line
            if (r < 0 || r >= rowsTopDown.length) {
                return false;
            }
            int index = 6 + column;
            if (index < 0 || index >= rowsTopDown[r].length()) {
                return false;
            }
            return rowsTopDown[r].charAt(index) == '#';
        };
    }

    @Test
    void noPanelAboveTheTerminalMeansNoScreen() {
        ScreenFormation.Result r = ScreenFormation.scan(grid(
                "......#......",
                "...###.###...",
                "...###.###..."));
        assertEquals(ScreenFormation.Result.NONE, r);
        assertFalse(r.formed());
    }

    @Test
    void centredThreeByTwo() {
        ScreenFormation.Result r = ScreenFormation.scan(grid(
                ".....###.....",
                ".....###....."));
        assertEquals(new ScreenFormation.Result(1, 3, 2), r);
        assertEquals(1, r.right());
        assertTrue(r.formed());
    }

    @Test
    void unevenBottomRowShiftsTheScreen() {
        // Test plan: fill -3..1 on row 1 gives 5 x 1 with the terminal under the fourth column (left 3, right 1).
        ScreenFormation.Result r = ScreenFormation.scan(grid("...#####....."));
        assertEquals(new ScreenFormation.Result(3, 5, 1), r);
    }

    @Test
    void widthIsCappedAtSevenAndStaysCentred() {
        ScreenFormation.Result r = ScreenFormation.scan(grid("#############"));
        assertEquals(new ScreenFormation.Result(3, 7, 1), r);
        // Six to the left, two to the right: the alternation gives 4 left and 2 right.
        ScreenFormation.Result r2 = ScreenFormation.scan(grid("#########...."));
        assertEquals(new ScreenFormation.Result(4, 7, 1), r2);
    }

    @Test
    void incompleteRowStopsTheHeightAndHigherRowsAreIgnored() {
        ScreenFormation.Result r = ScreenFormation.scan(grid(
                ".....###.....",
                ".....#.#.....",
                ".....###....."));
        assertEquals(new ScreenFormation.Result(1, 3, 1), r);
        // A wider row above does not widen the screen; a complete row above an incomplete one does not count.
        ScreenFormation.Result r2 = ScreenFormation.scan(grid(
                "....#####....",
                ".....###.....",
                "......##.....",
                ".....###....."));
        assertEquals(new ScreenFormation.Result(1, 3, 1), r2);
    }

    @Test
    void foreignOwnerBlocksTheExtensionOnThatSide() {
        // The neighbour terminal's panel at column -3 belongs to a live terminal: not available.
        ScreenFormation.Result r = ScreenFormation.scan(grid("...x####....."));
        assertEquals(new ScreenFormation.Result(2, 4, 1), r);
    }

    @Test
    void gapsAreNotBridged() {
        ScreenFormation.Result r = ScreenFormation.scan(grid("..##.###.#..."));
        assertEquals(new ScreenFormation.Result(1, 3, 1), r);
    }

    @Test
    void heightIsCappedAtFour() {
        ScreenFormation.Result r = ScreenFormation.scan(grid(
                ".....###.....",
                ".....###.....",
                ".....###.....",
                ".....###.....",
                ".....###.....",
                ".....###....."));
        assertEquals(new ScreenFormation.Result(1, 3, 4), r);
    }
}
