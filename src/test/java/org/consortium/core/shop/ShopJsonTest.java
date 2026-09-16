package org.consortium.core.shop;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopJsonTest {
    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    private static ShopJson.Result parse(String key, String text) {
        return ShopJson.parse(key, json(text));
    }

    @Test
    void itemEntryOfTheStarterCatalogueIsAccepted() {
        ShopJson.Result r = parse("chunk_loader_basic", """
                {"name": "Basic Chunk Loader", "item": {"id": "chunkloaders:basic_chunk_loader", "count": 1},
                 "price": 400.00, "stage": "consortium:phase_3", "description": "Keeps a 3x3 chunk area loaded"}""");
        assertTrue(r.accepted(), r.refusal());
        ShopJson.Raw raw = r.raw();
        assertEquals("chunk_loader_basic", raw.key());
        assertEquals("Basic Chunk Loader", raw.name());
        assertTrue(raw.isItem());
        assertNull(raw.command());
        assertEquals(40_000L, raw.priceCents());
        assertEquals(0, raw.dailyLimit());
        assertEquals("consortium:phase_3", raw.stage());
        assertEquals(0, raw.phase());
        assertEquals("Keeps a 3x3 chunk area loaded", raw.description());
    }

    @Test
    void commandEntryNeedsAnIconAndKnownPlaceholders() {
        ShopJson.Result ok = parse("claim_chunks_10", """
                {"name": "10 extra claim chunks", "command": "ftbchunks admin extra_claim_chunks {player} add 10",
                 "icon": "minecraft:filled_map", "price": 120, "daily_limit": 2}""");
        assertTrue(ok.accepted(), ok.refusal());
        assertFalse(ok.raw().isItem());
        assertEquals(12_000L, ok.raw().priceCents());
        assertEquals(2, ok.raw().dailyLimit());
        assertEquals("minecraft:filled_map", ok.raw().icon());

        ShopJson.Result noIcon = parse("claim_chunks_10", """
                {"name": "10 extra claim chunks", "command": "ftbchunks admin extra_claim_chunks {player} add 10", "price": 120}""");
        assertFalse(noIcon.accepted());
        assertTrue(noIcon.refusal().contains("icon"), noIcon.refusal());

        ShopJson.Result badPlaceholder = parse("claim_chunks_10", """
                {"name": "x", "command": "give {item} 1", "icon": "minecraft:map", "price": 1}""");
        assertFalse(badPlaceholder.accepted());
        assertTrue(badPlaceholder.refusal().contains("{item}"), badPlaceholder.refusal());
    }

    @Test
    void exactlyOneOfItemAndCommand() {
        assertFalse(parse("a", "{\"name\": \"A\", \"price\": 1}").accepted());
        assertFalse(parse("a", "{\"name\": \"A\", \"price\": 1, \"item\": {\"id\": \"minecraft:stone\"}, \"command\": \"say hi\", \"icon\": \"minecraft:stone\"}").accepted());
        assertFalse(parse("a", "{\"name\": \"A\", \"price\": 1, \"item\": \"minecraft:stone\"}").accepted(), "a bare id is not the codec's object form");
    }

    @Test
    void keyRule() {
        assertTrue(ShopJson.validKey("chunk_loader_basic"));
        assertTrue(ShopJson.validKey("a"));
        assertFalse(ShopJson.validKey("Chunk"));
        assertFalse(ShopJson.validKey("chunk-loader"));
        assertFalse(ShopJson.validKey(""));
        assertFalse(ShopJson.validKey("a".repeat(33)));
        assertFalse(parse("Bad Key", "{\"name\": \"A\", \"price\": 1, \"item\": {\"id\": \"minecraft:stone\"}}").accepted());
    }

    @Test
    void priceRules() {
        String item = "\"item\": {\"id\": \"minecraft:stone\"}";
        assertEquals(1L, parse("a", "{\"name\": \"A\", \"price\": 0.01, " + item + "}").raw().priceCents());
        assertEquals(100_000_000L, parse("a", "{\"name\": \"A\", \"price\": 1000000, " + item + "}").raw().priceCents());
        assertEquals(1250L, parse("a", "{\"name\": \"A\", \"price\": \"12.50\", " + item + "}").raw().priceCents());
        assertFalse(parse("a", "{\"name\": \"A\", \"price\": 0, " + item + "}").accepted());
        assertFalse(parse("a", "{\"name\": \"A\", \"price\": 1000000.01, " + item + "}").accepted());
        assertFalse(parse("a", "{\"name\": \"A\", \"price\": 1.234, " + item + "}").accepted(), "three decimals");
        assertFalse(parse("a", "{\"name\": \"A\", \"price\": -5, " + item + "}").accepted());
        assertFalse(parse("a", "{\"name\": \"A\", " + item + "}").accepted(), "missing price");
    }

    @Test
    void nameDescriptionLimitAndPhaseRules() {
        String item = "\"item\": {\"id\": \"minecraft:stone\"}";
        assertFalse(parse("a", "{\"name\": \"\", \"price\": 1, " + item + "}").accepted());
        assertFalse(parse("a", "{\"name\": \"" + "n".repeat(49) + "\", \"price\": 1, " + item + "}").accepted());
        assertTrue(parse("a", "{\"name\": \"" + "n".repeat(48) + "\", \"price\": 1, " + item + "}").accepted());
        assertFalse(parse("a", "{\"name\": \"A\", \"price\": 1, \"description\": \"" + "d".repeat(97) + "\", " + item + "}").accepted());
        assertEquals("", parse("a", "{\"name\": \"A\", \"price\": 1, " + item + "}").raw().description());
        assertFalse(parse("a", "{\"name\": \"A\", \"price\": 1, \"daily_limit\": 0, " + item + "}").accepted());
        assertFalse(parse("a", "{\"name\": \"A\", \"price\": 1, \"daily_limit\": 1000, " + item + "}").accepted());
        assertFalse(parse("a", "{\"name\": \"A\", \"price\": 1, \"daily_limit\": 2.5, " + item + "}").accepted());
        assertEquals(3, parse("a", "{\"name\": \"A\", \"price\": 1, \"daily_limit\": 3.0, " + item + "}").raw().dailyLimit(), "Rhino-style integral double");
        assertFalse(parse("a", "{\"name\": \"A\", \"price\": 1, \"phase\": 0, " + item + "}").accepted());
        assertFalse(parse("a", "{\"name\": \"A\", \"price\": 1, \"phase\": 100, " + item + "}").accepted());
        assertEquals(3, parse("a", "{\"name\": \"A\", \"price\": 1, \"phase\": 3, " + item + "}").raw().phase());
        assertFalse(parse("a", "{\"name\": \"A\", \"price\": 1, \"stage\": \"not a stage\", " + item + "}").accepted());
        // a bare id without namespace would resolve to minecraft:phase_3 and never match (review 2026-09-16)
        assertFalse(parse("a", "{\"name\": \"A\", \"price\": 1, \"stage\": \"phase_3\", " + item + "}").accepted());
        assertFalse(parse("a", "{\"name\": \"A\", \"price\": 1, \"command\": \"say hi\", \"icon\": \"map\"}").accepted());
        assertFalse(parse("a", "[1, 2]").accepted());
    }

    @Test
    void commandLengthAndSlash() {
        assertFalse(parse("a", "{\"name\": \"A\", \"price\": 1, \"command\": \"/\", \"icon\": \"minecraft:map\"}").accepted());
        assertFalse(parse("a", "{\"name\": \"A\", \"price\": 1, \"command\": \"" + "s".repeat(257) + "\", \"icon\": \"minecraft:map\"}").accepted());
        assertTrue(parse("a", "{\"name\": \"A\", \"price\": 1, \"command\": \"/say hello {player}\", \"icon\": \"minecraft:map\"}").accepted());
    }
}
