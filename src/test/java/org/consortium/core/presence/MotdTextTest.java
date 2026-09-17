package org.consortium.core.presence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MotdTextTest {
    private static final String S = String.valueOf(LegacyText.SECTION);

    @Test
    void twoLinesJoinedWithANewline() {
        MotdText.Rendered r = MotdText.render(LegacyText.parse("&6&lThe Consortium &8| &ePhase 2: Steam"),
                LegacyText.parse("&7TPS 20.0 &8| &f12&7/30 online"));
        assertEquals(S + "6" + S + "lThe Consortium " + S + "8| " + S + "ePhase 2: Steam\n"
                + S + "7TPS 20.0 " + S + "8| " + S + "f12" + S + "7/30 online", r.text());
        assertEquals(0, r.droppedHex());
        assertEquals(2, r.text().split("\n", -1).length);
    }

    @Test
    void ampersandAndSectionCodesGiveTheSameOutput() {
        assertEquals(MotdText.render(LegacyText.parse("&aone &ltwo")).text(),
                MotdText.render(LegacyText.parse(S + "aone " + S + "ltwo")).text());
    }

    @Test
    void hexColoursAreDroppedAndCounted() {
        MotdText.Rendered r = MotdText.render(LegacyText.parse("&#ff8800warm &#0000ffcold"), LegacyText.parse("&#00ff00green"));
        assertEquals(S + "rwarm " + S + "rcold\n" + S + "rgreen", r.text());
        assertEquals(3, r.droppedHex());
    }

    @Test
    void plainSpanAfterAStyledOneResets() {
        assertEquals(S + "lbold" + S + "rplain", MotdText.render(LegacyText.parse("&lbold&rplain")).text());
        assertEquals("plain" + S + "6gold", MotdText.render(LegacyText.parse("plain&6gold")).text(), "a leading plain span needs no reset");
        assertEquals("", MotdText.render(List.of()).text());
    }

    @Test
    void everyFormatCodeIsEmittedAfterTheColour() {
        assertEquals(S + "c" + S + "k" + S + "l" + S + "m" + S + "n" + S + "oall",
                MotdText.render(LegacyText.parse("&c&k&l&m&n&oall")).text());
    }

    @Test
    void blankLinesAreDroppedAndTwoLinesKeptAtMost() {
        // The second format expanded to nothing but a colour code: one line left.
        assertEquals(List.of("&6Line one"), MotdText.lines("&6Line one\n&c"));
        assertEquals(S + "6Line one", MotdText.renderLines(MotdText.lines("&6Line one\n&c")).text());
        // A format carrying its own newline: after the drop, the first two lines survive.
        List<String> lines = MotdText.lines("&6one\n&7\n&etwo\n&bthree");
        assertEquals(List.of("&6one", "&etwo"), lines);
        assertEquals(S + "6one\n" + S + "etwo", MotdText.renderLines(lines).text());
        // Nothing at all still yields one empty line, so the server MOTD can be set.
        assertEquals(List.of(""), MotdText.lines("&7\n"));
        assertEquals("", MotdText.renderLines(MotdText.lines("")).text());
        assertEquals(2, MotdText.MAX_LINES);
    }
}
