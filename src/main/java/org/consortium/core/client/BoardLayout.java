package org.consortium.core.client;

import java.util.Locale;

/**
 * Layout arithmetic of the quota board (specification v0.2, 2.3), Minecraft-free so the unit test can check the
 * worked examples of the specification table. Everything is in pixels of the screen texture space: 64 px per
 * block, origin at the top-left of the rectangle, y down. A 9 px text line is 0.14 blocks, a 16 px icon 0.25 blocks.
 *
 * <p>Two row variants: <b>compact</b> (24 px rows, screens 1 to 3 blocks wide: icon, label over the counter, a thin
 * bar under both) and <b>wide</b> (18 px rows, screens 4 to 7 wide: icon, label, counter and a 40 x 6 bar on one
 * line; the pitch grows to 28 px when every line fits one page). Lines that do not fit the page cycle through pages
 * every {@link #TICKS_PER_PAGE} ticks.
 */
public final class BoardLayout {
    public static final int PX_PER_BLOCK = 64;
    public static final int PAD = 4;
    public static final int HEADER_H = 24;
    public static final int HEADER_LINE_1_Y = 3;
    public static final int HEADER_LINE_2_Y = 13;
    /** The body spans {@code BODY_TOP .. H - BODY_BOTTOM}. */
    public static final int BODY_TOP = 26;
    public static final int BODY_BOTTOM = 2;
    public static final int COMPACT_MAX_WIDTH = 3;
    public static final int COMPACT_PITCH = 24;
    public static final int WIDE_PITCH = 18;
    public static final int WIDE_PITCH_MAX = 28;
    public static final int ICON = 16;
    public static final int LINE_HEIGHT = 9;
    public static final int TICKS_PER_PAGE = 120;
    /** Text column of a row: icon, then this offset from the left padding. */
    public static final int TEXT_X = PAD + 22;
    /** Wide rows: the counter is right-aligned to {@code W - PAD - COUNTER_RIGHT}, the bar spans the last 40 px. */
    public static final int WIDE_COUNTER_RIGHT = 46;
    public static final int WIDE_BAR_LEFT = 42;
    public static final int WIDE_BAR_RIGHT = 2;
    public static final int WIDE_BAR_TOP = 5;
    public static final int WIDE_BAR_BOTTOM = 11;
    /** Wide rows: the label must keep at least this many pixels, else the counter falls back to the percent. */
    public static final int WIDE_MIN_LABEL = 40;
    public static final int CHECK_BOX = 7;

    public static final int BACKGROUND = 0xFF101820;
    public static final int HEADER = 0xFF1C2A3A;
    public static final int TRACK = 0xFF2A3644;
    public static final int TEXT = 0xFFE8EEF4;
    public static final int MUTED = 0xFFA0AEC0;
    public static final int RED = 0xFFD9534F;
    public static final int AMBER = 0xFFF0AD4E;
    public static final int GREEN = 0xFF5CB85C;
    public static final int WHITE = 0xFFFFFFFF;

    public enum Variant { COMPACT, WIDE }

    /** Colour state of a line or of the whole board: done (check mark), on track (green), behind (amber), late (red). */
    public enum State {
        DONE(GREEN), ON_TRACK(GREEN), BEHIND(AMBER), LATE(RED);

        public final int color;

        State(int color) {
            this.color = color;
        }

        public static State of(double ratio, boolean done) {
            if (done) {
                return DONE;
            }
            if (ratio < 0.60) {
                return LATE;
            }
            if (ratio < 0.90) {
                return BEHIND;
            }
            return ON_TRACK;
        }
    }

    /**
     * @param widthPx     screen width in pixels (64 per block)
     * @param heightPx    screen height in pixels
     * @param bodyHeight  {@code B = H - 28}
     * @param variant     compact or wide
     * @param rowsPerPage rows that fit the body
     * @param pitch       row pitch in pixels
     * @param pages       pages needed for {@code lineCount} lines (at least 1)
     * @param lineCount   lines of the snapshot
     */
    public record Metrics(int widthPx, int heightPx, int bodyHeight, Variant variant, int rowsPerPage, int pitch, int pages, int lineCount) {
        /** Wide rows centre their 16 px content in the pitch; compact rows start at the top. */
        public int contentOffset() {
            return variant == Variant.WIDE ? (pitch - ICON) / 2 : 0;
        }

        /** Top y of row {@code index} of a page. */
        public int rowY(int index) {
            return BODY_TOP + index * pitch;
        }

        public int firstLine(int page) {
            return page * rowsPerPage;
        }

        public int lastLine(int page) {
            return Math.min(lineCount, firstLine(page) + rowsPerPage);
        }

        /** The page shown at a game time: {@code (gameTime / 120) % pages}. */
        public int pageAt(long gameTime) {
            return pages <= 1 ? 0 : (int) (Math.floorMod(gameTime / TICKS_PER_PAGE, (long) pages));
        }
    }

    private BoardLayout() {
    }

    public static Metrics compute(int widthBlocks, int heightBlocks, int lineCount) {
        int w = Math.max(1, widthBlocks) * PX_PER_BLOCK;
        int h = Math.max(1, heightBlocks) * PX_PER_BLOCK;
        int body = h - BODY_TOP - BODY_BOTTOM;
        int n = Math.max(0, lineCount);
        Variant variant = widthBlocks <= COMPACT_MAX_WIDTH ? Variant.COMPACT : Variant.WIDE;
        int pitch;
        int rows;
        if (variant == Variant.COMPACT) {
            pitch = COMPACT_PITCH;
            rows = Math.max(1, body / COMPACT_PITCH);
        } else {
            rows = Math.max(1, body / WIDE_PITCH);
            pitch = WIDE_PITCH;
            if (n > 0 && n <= rows) {
                pitch = Math.min(WIDE_PITCH_MAX, body / n);
            }
        }
        int pages = n == 0 ? 1 : (n + rows - 1) / rows;
        return new Metrics(w, h, body, variant, rows, pitch, pages, n);
    }

    /** {@code floor(ratio * 100)} clamped to 0..100. */
    public static int percent(double ratio) {
        return (int) Math.floor(Math.min(1.0, Math.max(0.0, ratio)) * 100.0);
    }

    /** Filled pixels of a bar of {@code trackWidth} pixels. */
    public static int barFill(int trackWidth, double ratio) {
        return (int) Math.round(trackWidth * Math.min(1.0, Math.max(0.0, ratio)));
    }

    /** {@code "15,000 / 40,000"} in {@code Locale.US}. */
    public static String counter(long current, long target) {
        return String.format(Locale.US, "%,d / %,d", current, target);
    }

    /** The short form when the counter does not fit: {@code "37 %"}. */
    public static String percentText(double ratio) {
        return percent(ratio) + " %";
    }

    /** Compact rows: the width available to the label and the counter. */
    public static int compactTextArea(int widthPx) {
        return widthPx - 2 * PAD - 22;
    }

    /** Wide rows: the x the counter is right-aligned to. */
    public static int wideCounterRight(int widthPx) {
        return widthPx - PAD - WIDE_COUNTER_RIGHT;
    }

    /** Wide rows: the width available to the label once the counter of {@code counterWidth} px stands at its right. */
    public static int wideLabelArea(int widthPx, int counterWidth) {
        return wideCounterRight(widthPx) - counterWidth - 6 - TEXT_X;
    }
}
