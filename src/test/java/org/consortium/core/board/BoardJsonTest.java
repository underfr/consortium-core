package org.consortium.core.board;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardJsonTest {
    private static final Set<String> KNOWN = Set.of("minecraft:cobblestone", "minecraft:iron_ingot", "minecraft:barrier");
    private static final BoardJson.IconResolver ICONS = icon -> {
        String id = icon.contains(":") ? icon : "minecraft:" + icon;
        return KNOWN.contains(id) ? id : null;
    };

    private static final String GOOD = "{\"phase\":1,\"name\":\"Groundbreaking\",\"day\":12,\"days\":84,\"completion\":0.37,\"complete\":false,"
            + "\"lines\":[{\"key\":\"minecraft:cobblestone\",\"label\":\"Cobblestone\",\"icon\":\"minecraft:cobblestone\",\"current\":15000,\"target\":40000}]}";

    private final List<String> warnings = new ArrayList<>();

    private BoardJson.Result parse(String json) {
        return BoardJson.parse(json, ICONS, warnings::add);
    }

    @Test
    void acceptsTheSpecificationExampleAndReserialisesCanonically() {
        BoardJson.Result r = parse(GOOD);
        assertTrue(r.accepted(), r.refusal());
        BoardSnapshot s = r.snapshot();
        assertEquals(1, s.phase());
        assertEquals("Groundbreaking", s.name());
        assertEquals(12, s.day());
        assertEquals(84, s.days());
        assertEquals(0.37, s.completion(), 1e-9);
        assertFalse(s.complete());
        assertEquals(1, s.lines().size());
        BoardSnapshot.Line line = s.lines().get(0);
        assertEquals("minecraft:cobblestone", line.key());
        assertEquals(15000, line.current());
        assertEquals(40000, line.target());
        assertEquals(GOOD, s.toJson());
        assertTrue(warnings.isEmpty(), warnings.toString());
        // Unknown fields are ignored and the canonical form is stable.
        BoardJson.Result again = parse("{\"extra\":true," + GOOD.substring(1));
        assertEquals(GOOD, again.snapshot().toJson());
    }

    @Test
    void refusesStructuralErrorsOnly() {
        assertNull(parse("[1,2]").snapshot());
        assertNull(parse("not json").snapshot());
        assertNull(parse(null).snapshot());
        assertNotNull(parse("{\"phase\":120,\"day\":1,\"days\":84}").refusal());
        assertNotNull(parse("{\"phase\":-1,\"day\":1,\"days\":84}").refusal());
        assertNotNull(parse("{\"phase\":1.5,\"day\":1,\"days\":84}").refusal());
        assertNotNull(parse("{\"phase\":\"1\",\"day\":1,\"days\":84}").refusal());
        assertNotNull(parse("{\"phase\":1,\"days\":84}").refusal());
        assertNotNull(parse("{\"phase\":1,\"day\":1,\"days\":0}").refusal());
        assertNotNull(parse("{\"phase\":1,\"day\":10000,\"days\":84}").refusal());
        StringBuilder big = new StringBuilder("{\"phase\":1,\"day\":1,\"days\":84,\"name\":\"");
        big.append("x".repeat(BoardJson.MAX_LENGTH));
        big.append("\"}");
        assertNotNull(parse(big.toString()).refusal());
    }

    @Test
    void failsSoftOnEveryLineField() {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 33; i++) {
            if (i > 0) {
                lines.append(',');
            }
            lines.append("{\"key\":\"k").append(i).append("\",\"target\":10,\"current\":").append(i).append("}");
        }
        String json = "{\"phase\":0,\"day\":0,\"days\":1,\"name\":\"  \",\"completion\":7,\"complete\":\"yes\",\"lines\":[" + lines + "]}";
        BoardJson.Result r = parse(json);
        assertTrue(r.accepted(), r.refusal());
        BoardSnapshot s = r.snapshot();
        assertEquals("Phase 0", s.name());
        assertEquals(1.0, s.completion(), 1e-9);
        assertFalse(s.complete());
        assertEquals(BoardSnapshot.MAX_LINES, s.lines().size());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("33 lines")), warnings.toString());
        // No icon at all: barrier, line kept.
        assertEquals("minecraft:barrier", s.lines().get(0).icon());

        warnings.clear();
        String bad = "{\"phase\":2,\"day\":3,\"days\":84,\"completion\":\"x\",\"lines\":["
                + "{\"label\":\"no key\",\"target\":5},"
                + "{\"key\":\"minecraft:iron_ingot\",\"target\":0},"
                + "{\"key\":\"minecraft:iron_ingot\",\"label\":\"" + "L".repeat(60) + "\",\"icon\":\"minecraft:nothing\",\"current\":-4,\"target\":2500},"
                + "{\"key\":\"minecraft:cobblestone\",\"icon\":\"cobblestone\",\"current\":1e300,\"target\":1e300},"
                + "{\"key\":\"x\",\"label\":\"\",\"icon\":\"iron_ingot\",\"current\":7.9,\"target\":3}"
                + "]}";
        BoardJson.Result r2 = parse(bad);
        assertTrue(r2.accepted(), r2.refusal());
        BoardSnapshot s2 = r2.snapshot();
        assertEquals(0.0, s2.completion(), 1e-9);
        assertEquals(3, s2.lines().size());
        BoardSnapshot.Line iron = s2.lines().get(0);
        assertEquals(BoardSnapshot.MAX_LABEL, iron.label().length());
        assertEquals("minecraft:barrier", iron.icon());
        assertEquals(0, iron.current());
        assertEquals(2500, iron.target());
        BoardSnapshot.Line cobble = s2.lines().get(1);
        assertEquals("minecraft:cobblestone", cobble.icon());
        assertEquals(BoardSnapshot.MAX_COUNT, cobble.target());
        assertEquals(BoardSnapshot.MAX_COUNT, cobble.current());
        BoardSnapshot.Line x = s2.lines().get(2);
        assertEquals("x", x.label());
        assertEquals("minecraft:iron_ingot", x.icon());
        assertEquals(7, x.current());
        assertTrue(x.done());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("line 0")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("no target")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("minecraft:nothing")), warnings.toString());
    }

    @Test
    void missingLinesAndNameFallbacks() {
        BoardJson.Result r = parse("{\"phase\":3,\"day\":40,\"days\":84,\"complete\":true}");
        assertTrue(r.accepted());
        assertEquals("Phase 3", r.snapshot().name());
        assertTrue(r.snapshot().complete());
        assertTrue(r.snapshot().lines().isEmpty());
        assertEquals(0.0, r.snapshot().completion(), 1e-9);
        BoardJson.Result trimmed = parse("{\"phase\":3,\"day\":40,\"days\":84,\"name\":\"  " + "N".repeat(60) + "  \"}");
        assertEquals(BoardSnapshot.MAX_NAME, trimmed.snapshot().name().length());
    }

    @Test
    void eventObjectIsOptionalValidatedAndCanonical() {
        // No event: the canonical form is byte-identical to 0.3.0 (payload dedupe stays intact).
        assertNull(parse(GOOD).snapshot().event());
        assertNull(parse("{\"event\":null," + GOOD.substring(1)).snapshot().event());
        assertEquals(GOOD, parse("{\"event\":null," + GOOD.substring(1)).snapshot().toJson());
        assertTrue(warnings.isEmpty(), warnings.toString());

        String withEvent = GOOD.substring(0, GOOD.length() - 1)
                + ",\"event\":{\"name\":\"Ore Rush\",\"detail\":\"x2 on raw deliveries\",\"seconds_left\":2460}}";
        BoardJson.Result r = parse(withEvent);
        assertTrue(r.accepted(), r.refusal());
        BoardSnapshot.Event e = r.snapshot().event();
        assertNotNull(e);
        assertEquals("Ore Rush", e.name());
        assertEquals("x2 on raw deliveries", e.detail());
        assertEquals(2460, e.secondsLeft());
        assertTrue(e.hasCountdown());
        assertEquals(withEvent, r.snapshot().toJson());
        assertTrue(warnings.isEmpty(), warnings.toString());

        // Not an object: one warning, no event, the publish is still accepted.
        BoardJson.Result notObject = parse(GOOD.substring(0, GOOD.length() - 1) + ",\"event\":\"Ore Rush\"}");
        assertTrue(notObject.accepted());
        assertNull(notObject.snapshot().event());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("event is not an object")), warnings.toString());
        warnings.clear();

        // Empty name: dropped with a warning. Missing detail and seconds: empty detail, no countdown.
        assertNull(parse(GOOD.substring(0, GOOD.length() - 1) + ",\"event\":{\"name\":\"   \",\"seconds_left\":10}}").snapshot().event());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("no usable name")), warnings.toString());
        warnings.clear();
        BoardSnapshot.Event bare = parse(GOOD.substring(0, GOOD.length() - 1) + ",\"event\":{\"name\":\"Blood Moon\"}}").snapshot().event();
        assertNotNull(bare);
        assertEquals("", bare.detail());
        assertEquals(0, bare.secondsLeft());
        assertFalse(bare.hasCountdown());
        assertTrue(warnings.isEmpty(), warnings.toString());

        // Truncation and clamping: name 32, detail 48, seconds 0..604,800 (a fraction or a string means 0).
        BoardSnapshot.Event big = parse(GOOD.substring(0, GOOD.length() - 1) + ",\"event\":{\"name\":\"" + "N".repeat(50)
                + "\",\"detail\":\"" + "D".repeat(70) + "\",\"seconds_left\":9999999}}").snapshot().event();
        assertEquals(BoardSnapshot.MAX_EVENT_NAME, big.name().length());
        assertEquals(BoardSnapshot.MAX_EVENT_DETAIL, big.detail().length());
        assertEquals(BoardSnapshot.MAX_EVENT_SECONDS, big.secondsLeft());
        assertEquals(0, parse(GOOD.substring(0, GOOD.length() - 1) + ",\"event\":{\"name\":\"x\",\"seconds_left\":-5}}").snapshot().event().secondsLeft());
        assertEquals(0, parse(GOOD.substring(0, GOOD.length() - 1) + ",\"event\":{\"name\":\"x\",\"seconds_left\":1.5}}").snapshot().event().secondsLeft());
        assertEquals(0, parse(GOOD.substring(0, GOOD.length() - 1) + ",\"event\":{\"name\":\"x\",\"seconds_left\":\"12\"}}").snapshot().event().secondsLeft());
    }

    @Test
    void eventCountdownRunsOutOnTheClientAndInPresence() {
        BoardSnapshot.Event e = new BoardSnapshot.Event("Ore Rush", "x2 on raw deliveries", 2460);
        assertEquals(2460, e.remainingSeconds(0));
        assertEquals(2400, e.remainingSeconds(60_000));
        assertEquals(0, e.remainingSeconds(2_460_000));
        assertTrue(e.visible(2_459_000));
        assertFalse(e.visible(2_460_000));
        assertEquals("Ore Rush (41 min)", e.presenceText(0));
        assertEquals("Ore Rush (40 min)", e.presenceText(60_000));
        assertEquals("Ore Rush (1 min)", e.presenceText(2_459_000));
        assertEquals("", e.presenceText(2_460_000));
        assertEquals("41:00", BoardSnapshot.Event.clock(e.remainingSeconds(0)));
        assertEquals("0:05", BoardSnapshot.Event.clock(5));
        assertEquals("1:02:03", BoardSnapshot.Event.clock(3723));
        // No countdown: always visible, no timer in the texts.
        BoardSnapshot.Event staff = new BoardSnapshot.Event("Friday Zone", "", 0);
        assertEquals(-1, staff.remainingSeconds(999_999_999));
        assertTrue(staff.visible(999_999_999));
        assertEquals("Friday Zone", staff.presenceText(999_999_999));
    }
}
