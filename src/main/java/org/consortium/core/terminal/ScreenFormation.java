package org.consortium.core.terminal;

/**
 * The Delivery Station screen rectangle (specification v0.2, 2.2): Display Panels in the vertical plane through the
 * terminal, perpendicular to its facing, rows 1..4 above it, up to 7 columns. Minecraft-free: the block and block
 * entity checks come through {@link Probe}, so the unit test drives the scan with a char grid.
 *
 * <p>Column {@code c} at row {@code r} is {@code T.above(r).relative(right, c)} with {@code right = F.getCounterClockWise()}
 * (the viewer's right), {@code c < 0} to the viewer's left. The scan is deterministic and runs only on placement and
 * removal events, never per tick:
 * <ol>
 * <li>column 0, row 1 must be an available panel, else no screen;</li>
 * <li>the bottom row extends left and right alternately so a symmetric build stays centred, at most 7 wide;</li>
 * <li>rows 2..4 count only while every column of the row is available.</li>
 * </ol>
 */
public final class ScreenFormation {
    public static final int MAX_WIDTH = 7;
    public static final int MAX_HEIGHT = 4;
    /** Furthest column tried on either side (a 7-wide screen with the terminal at one end). */
    public static final int MAX_SIDE = MAX_WIDTH - 1;

    /** What the scan asks about the world: is there an available panel at (column, row)? */
    @FunctionalInterface
    public interface Probe {
        /**
         * @param column offset to the viewer's right of the terminal column (negative = left)
         * @param row    blocks above the terminal, 1..4
         * @return true for a Display Panel that is unowned, owned by this terminal, or whose owner is gone
         */
        boolean available(int column, int row);
    }

    /**
     * @param left   columns to the viewer's left of the terminal column (0..6)
     * @param width  columns in total (0 = no screen, else 1..7)
     * @param height rows (0 = no screen, else 1..4)
     */
    public record Result(int left, int width, int height) {
        public static final Result NONE = new Result(0, 0, 0);

        public boolean formed() {
            return width > 0 && height > 0;
        }

        /** Columns to the viewer's right of the terminal column. */
        public int right() {
            return width == 0 ? 0 : width - 1 - left;
        }
    }

    private ScreenFormation() {
    }

    public static Result scan(Probe probe) {
        if (!probe.available(0, 1)) {
            return Result.NONE;
        }
        int left = 0;
        int right = 0;
        boolean leftOpen = true;
        boolean rightOpen = true;
        for (int k = 1; k <= MAX_SIDE; k++) {
            if (leftOpen) {
                if (left + right + 1 < MAX_WIDTH && probe.available(-k, 1)) {
                    left = k;
                } else {
                    leftOpen = false;
                }
            }
            if (rightOpen) {
                if (left + right + 1 < MAX_WIDTH && probe.available(k, 1)) {
                    right = k;
                } else {
                    rightOpen = false;
                }
            }
            if ((!leftOpen && !rightOpen) || left + right + 1 >= MAX_WIDTH) {
                break;
            }
        }
        int width = left + right + 1;
        int height = 1;
        for (int r = 2; r <= MAX_HEIGHT; r++) {
            boolean complete = true;
            for (int c = -left; c <= right; c++) {
                if (!probe.available(c, r)) {
                    complete = false;
                    break;
                }
            }
            if (!complete) {
                break;
            }
            height = r;
        }
        return new Result(left, width, height);
    }
}
