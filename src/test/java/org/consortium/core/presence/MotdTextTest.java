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
}
