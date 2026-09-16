package org.consortium.core.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The worked examples of specification v0.2, section 2.3 (64 px per block, phase 1 with 8 lines, phase 5 with 9). */
class BoardLayoutTest {

    @Test
    void threeByTwoIsCompactWithFourRowsPerPage() {
        BoardLayout.Metrics m8 = BoardLayout.compute(3, 2, 8);
        assertEquals(192, m8.widthPx());
        assertEquals(128, m8.heightPx());
        assertEquals(100, m8.bodyHeight());
        assertEquals(BoardLayout.Variant.COMPACT, m8.variant());
        assertEquals(4, m8.rowsPerPage());
        assertEquals(24, m8.pitch());
        assertEquals(2, m8.pages());
        assertEquals(3, BoardLayout.compute(3, 2, 9).pages());
        assertEquals(162, BoardLayout.compactTextArea(192));
        assertEquals(0, m8.contentOffset());
    }

    @Test
    void fiveByThreeIsWideOnOnePage() {
        BoardLayout.Metrics m8 = BoardLayout.compute(5, 3, 8);
        assertEquals(320, m8.widthPx());
        assertEquals(192, m8.heightPx());
        assertEquals(164, m8.bodyHeight());
        assertEquals(BoardLayout.Variant.WIDE, m8.variant());
        assertEquals(9, m8.rowsPerPage());
        assertEquals(1, m8.pages());
        assertEquals(20, m8.pitch());
        assertEquals(2, m8.contentOffset());
        BoardLayout.Metrics m9 = BoardLayout.compute(5, 3, 9);
        assertEquals(1, m9.pages());
        assertEquals(18, m9.pitch());
        assertEquals(1, m9.contentOffset());
    }

    @Test
    void sevenByFourGrowsThePitchToTwentyEight() {
        BoardLayout.Metrics m8 = BoardLayout.compute(7, 4, 8);
        assertEquals(448, m8.widthPx());
        assertEquals(256, m8.heightPx());
        assertEquals(228, m8.bodyHeight());
        assertEquals(12, m8.rowsPerPage());
        assertEquals(28, m8.pitch());
        assertEquals(25, BoardLayout.compute(7, 4, 9).pitch());
        assertEquals(1, m8.pages());
    }

    @Test
    void oneByOneShowsOneLinePerPage() {
        BoardLayout.Metrics m8 = BoardLayout.compute(1, 1, 8);
        assertEquals(64, m8.widthPx());
        assertEquals(36, m8.bodyHeight());
        assertEquals(BoardLayout.Variant.COMPACT, m8.variant());
        assertEquals(1, m8.rowsPerPage());
        assertEquals(8, m8.pages());
        assertEquals(9, BoardLayout.compute(1, 1, 9).pages());
        assertEquals(34, BoardLayout.compactTextArea(64));
    }

    @Test
    void pagesCycleEverySixSecondsAndHelpersRound() {
        BoardLayout.Metrics m = BoardLayout.compute(3, 2, 9);
        assertEquals(0, m.pageAt(0));
        assertEquals(0, m.pageAt(119));
        assertEquals(1, m.pageAt(120));
        assertEquals(2, m.pageAt(240));
        assertEquals(0, m.pageAt(360));
        assertEquals(0, m.firstLine(0));
        assertEquals(4, m.lastLine(0));
        assertEquals(8, m.firstLine(2));
        assertEquals(9, m.lastLine(2));
        assertEquals(0, BoardLayout.compute(5, 3, 0).pageAt(1000));
        assertEquals(1, BoardLayout.compute(5, 3, 0).pages());

        assertEquals(37, BoardLayout.percent(0.379));
        assertEquals(100, BoardLayout.percent(3.0));
        assertEquals(0, BoardLayout.percent(-1.0));
        assertEquals("15,000 / 40,000", BoardLayout.counter(15000, 40000));
        assertEquals("37 %", BoardLayout.percentText(0.375));
        assertEquals(40, BoardLayout.barFill(40, 2.0));
        assertEquals(15, BoardLayout.barFill(40, 0.375));
        assertEquals(BoardLayout.State.DONE, BoardLayout.State.of(1.2, true));
        assertEquals(BoardLayout.State.LATE, BoardLayout.State.of(0.59, false));
        assertEquals(BoardLayout.State.BEHIND, BoardLayout.State.of(0.6, false));
        assertEquals(BoardLayout.State.ON_TRACK, BoardLayout.State.of(0.9, false));
        assertEquals(BoardLayout.GREEN, BoardLayout.State.DONE.color);
    }
}
