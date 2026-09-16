package org.consortium.core.presence;

import org.consortium.core.presence.LegacyText.Span;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceholdersTest {
    private static final Map<String, String> VALUES = Map.ofEntries(
            Map.entry("server", "The Consortium"), Map.entry("phase", "2"), Map.entry("phase_name", "Steam"),
            Map.entry("day", "5"), Map.entry("days", "21"), Map.entry("name", "Alex"), Map.entry("prefix", "&b[Engineer] "),
            Map.entry("suffix", " [Staff]"), Map.entry("group", "Engineer"), Map.entry("rank_color", "&b"),
            Map.entry("credits", "1,234.56"), Map.entry("currency", "CC"), Map.entry("tps", "20.0"), Map.entry("mspt", "3"),
            Map.entry("online", "12"), Map.entry("max", "30"));
    private static final Function<String, String> RESOLVER = VALUES::get;

    @Test
    void everyKnownTokenExpands() {
        for (String token : PresenceFormats.KNOWN_TOKENS) {
            assertEquals(VALUES.get(token), Placeholders.expand("{" + token + "}", RESOLVER), token);
        }
        assertEquals("Phase 2: Steam, day 5/21, TPS 20.0 MSPT 3, 12/30, 1,234.56 CC",
                Placeholders.expand("Phase {phase}: {phase_name}, day {day}/{days}, TPS {tps} MSPT {mspt}, {online}/{max}, {credits} {currency}", RESOLVER));
    }

    @Test
    void adjacentTokens() {
        assertEquals("&b&b[Engineer] Alex [Staff]", Placeholders.expand("{rank_color}{prefix}{name}{suffix}", RESOLVER));
    }

    @Test
    void unknownTokenStaysVerbatim() {
        assertEquals("Alex {nope} {Name} {phase name}", Placeholders.expand("{name} {nope} {Name} {phase name}", RESOLVER));
    }

    @Test
    void braceWithoutClosingIsLiteral() {
        assertEquals("{name Alex {", Placeholders.expand("{name {name} {", RESOLVER));
        assertEquals("}", Placeholders.expand("}", RESOLVER));
        assertEquals("", Placeholders.expand("", RESOLVER));
        assertEquals("", Placeholders.expand(null, RESOLVER));
    }

    @Test
    void valuesAreNotExpandedAgain() {
        Function<String, String> tricky = token -> token.equals("prefix") ? "{name}" : VALUES.get(token);
        assertEquals("{name} Alex", Placeholders.expand("{prefix} {name}", tricky));
    }

    @Test
    void codesInsideValuesAreParsedByLegacyTextWhileLiteralBracesAreNot() {
        String expanded = Placeholders.expand("{rank_color}{prefix}&f{name} {x}", RESOLVER);
        assertEquals("&b&b[Engineer] &fAlex {x}", expanded);
        List<Span> spans = LegacyText.parse(expanded);
        assertEquals(2, spans.size());
        assertEquals(new Span("[Engineer] ", "b", false, false, false, false, false), spans.get(0));
        assertEquals(new Span("Alex {x}", "f", false, false, false, false, false), spans.get(1));
    }

    @Test
    void tokensAreListedInOrderOfFirstUse() {
        assertEquals(List.of("prefix", "name", "suffix"), List.copyOf(Placeholders.tokens("{prefix}{name}{suffix}{name}")));
        assertEquals(Set.of(), Placeholders.tokens(null));
        assertEquals(Set.of(), Placeholders.tokens("no token {here"));
    }

    @Test
    void everyDefaultFormatUsesKnownTokensOnly() {
        PresenceFormats d = PresenceFormats.DEFAULTS;
        for (String format : List.of(d.chatName(), d.tabName(), d.tabHeader(), d.tabFooter(), d.motdLine1(), d.motdLine2())) {
            for (String token : Placeholders.tokens(format)) {
                assertTrue(PresenceFormats.KNOWN_TOKENS.contains(token), format + " uses unknown token " + token);
            }
        }
        assertTrue(Placeholders.tokens(d.chatName()).contains("name"), "the chat name must keep the display name");
        assertTrue(Placeholders.tokens(d.tabName()).contains("name"));
        assertEquals(Set.of(), Placeholders.tokens(d.chatBodyStyle()), "the body style is codes only");
    }

    @Test
    void defaultsAreClampedAndNullSafe() {
        PresenceFormats f = new PresenceFormats(true, null, null, null, null, null, null, 5, true, null, null, 5000);
        assertEquals("", f.serverName());
        assertEquals("", f.motdLine2());
        assertEquals(PresenceFormats.MIN_TAB_REFRESH_TICKS, f.tabRefreshTicks());
        assertEquals(PresenceFormats.MAX_MOTD_REFRESH_TICKS, f.motdRefreshTicks());
        assertEquals(60, PresenceFormats.DEFAULTS.tabRefreshTicks());
        assertEquals(100, PresenceFormats.DEFAULTS.motdRefreshTicks());
    }
}
