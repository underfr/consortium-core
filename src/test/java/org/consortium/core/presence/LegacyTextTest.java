package org.consortium.core.presence;

import org.consortium.core.presence.LegacyText.Span;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyTextTest {
    private static final String S = String.valueOf(LegacyText.SECTION);

    @Test
    void ampersandColourCode() {
        List<Span> spans = LegacyText.parse("&6gold");
        assertEquals(1, spans.size());
        assertEquals("gold", spans.get(0).text());
        assertEquals("6", spans.get(0).color());
        assertTrue(spans.get(0).legacyColor());
        assertFalse(spans.get(0).anyFormat());
    }

    @Test
    void sectionSignColourCodeAndUpperCase() {
        List<Span> spans = LegacyText.parse(S + "Agreen");
        assertEquals(1, spans.size());
        assertEquals("green", spans.get(0).text());
        assertEquals("a", spans.get(0).color());
    }

    @Test
    void hexColour() {
        List<Span> spans = LegacyText.parse("&#FF8800name");
        assertEquals(1, spans.size());
        assertEquals("name", spans.get(0).text());
        assertEquals("#ff8800", spans.get(0).color());
        assertTrue(spans.get(0).hexColor());
        // fewer than six hex digits: not a colour, kept verbatim
        assertEquals("&#ff88name", LegacyText.strip("&#ff88name"));
        assertEquals("&#ff88name", LegacyText.parse("&#ff88name").get(0).text());
    }

    @Test
    void colourResetsFormatsButFormatsStack() {
        List<Span> spans = LegacyText.parse("&lbold&6gold&nunder");
        assertEquals(3, spans.size());
        assertEquals(new Span("bold", null, true, false, false, false, false), spans.get(0));
        assertEquals(new Span("gold", "6", false, false, false, false, false), spans.get(1));
        assertEquals(new Span("under", "6", false, false, true, false, false), spans.get(2));
        List<Span> hex = LegacyText.parse("&l&#112233x");
        assertEquals(new Span("x", "#112233", false, false, false, false, false), hex.get(0));
    }

    @Test
    void resetClearsEverything() {
        List<Span> spans = LegacyText.parse("&6&lx&ry");
        assertEquals(2, spans.size());
        assertEquals(new Span("x", "6", true, false, false, false, false), spans.get(0));
        assertEquals(new Span("y", null, false, false, false, false, false), spans.get(1));
        assertTrue(spans.get(1).plain());
    }

    @Test
    void everyFormatCode() {
        Span s = LegacyText.parse("&k&l&m&n&oall").get(0);
        assertTrue(s.obfuscated() && s.bold() && s.strikethrough() && s.underline() && s.italic());
        assertNull(s.color());
    }

    @Test
    void unknownCodeAndTrailingMarkerStayVerbatim() {
        List<Span> spans = LegacyText.parse("&zkeep &g end&");
        assertEquals(1, spans.size());
        assertEquals("&zkeep &g end&", spans.get(0).text());
        assertEquals("end" + S, LegacyText.parse("end" + S).get(0).text());
        assertEquals("&", LegacyText.parse("&").get(0).text());
    }

    @Test
    void emptyAndNullInputs() {
        assertTrue(LegacyText.parse("").isEmpty());
        assertTrue(LegacyText.parse(null).isEmpty());
        assertTrue(LegacyText.parse("&6&l").isEmpty(), "codes without text produce no span");
        assertEquals("", LegacyText.strip(null));
    }

    @Test
    void stripRemovesEveryCode() {
        assertEquals("[Engineer] Alex says hi", LegacyText.strip("&b[Engineer] &f&lAlex" + S + "r says &#ff0000hi"));
    }

    @Test
    void styleOfGivesTheFinalStyleWithoutText() {
        Span white = LegacyText.styleOf("&f");
        assertEquals("", white.text());
        assertEquals("f", white.color());
        Span boldGold = LegacyText.styleOf("&6&l");
        assertEquals("6", boldGold.color());
        assertTrue(boldGold.bold());
        assertEquals(Span.EMPTY, LegacyText.styleOf(""));
        assertEquals(Span.EMPTY, LegacyText.styleOf(null));
        assertTrue(LegacyText.styleOf("plain text").plain());
    }

    @Test
    void rankColourMetaToCodes() {
        assertEquals("&6", LegacyText.colorCodes("gold"));
        assertEquals("&d", LegacyText.colorCodes("light_purple"));
        assertEquals("&7", LegacyText.colorCodes(" Gray "));
        assertEquals("&b", LegacyText.colorCodes("&b"));
        assertEquals("&b", LegacyText.colorCodes(S + "B"));
        assertEquals("&b", LegacyText.colorCodes("b"));
        assertEquals("&#ff8800", LegacyText.colorCodes("#FF8800"));
        assertEquals("&#ff8800", LegacyText.colorCodes("&#ff8800"));
        assertEquals("", LegacyText.colorCodes("nope"));
        assertEquals("", LegacyText.colorCodes("&l"), "a format code is not a colour");
        assertEquals("", LegacyText.colorCodes(""));
        assertEquals("", LegacyText.colorCodes(null));
    }
}
