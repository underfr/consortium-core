package org.consortium.core.terminal;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CommandTemplateTest {
    private static final Map<String, String> GOOD = Map.of("item", "minecraft:iron_ingot", "count", "64", "player", "Alex",
            "uuid", "11111111-2222-3333-4444-555555555555", "tx", "a1b2c3d4");
    /** Verbatim the {@code command} of the starter {@code claim_chunks_10} entry; keep both in step. */
    private static final String STARTER_CLAIM_COMMAND = "ftbchunks admin extra_claim_chunks {player} add 10";

    @Test
    void rendersPlaceholdersAndStripsTheSlash() {
        String out = CommandTemplate.render("/consortium contribute {item} {count} {player} {uuid} {tx} {unknown}",
                Map.of("item", "minecraft:iron_ingot", "count", "64", "player", "Alex", "uuid", "1-2-3", "tx", "a3f1"));
        assertEquals("consortium contribute minecraft:iron_ingot 64 Alex 1-2-3 a3f1 {unknown}", out);
    }

    @Test
    void defaultTemplateMatchesTheEngineCommand() {
        String out = CommandTemplate.render(CommandTemplate.DEFAULT_CONTRIBUTE_COMMAND,
                Map.of("item", "minecraft:cobblestone", "count", "16", "player", "Alex", "uuid", "1-2-3", "tx", "a3f1"));
        assertEquals("consortium contribute minecraft:cobblestone 16", out);
    }

    @Test
    void valuesWithDollarSignsAreLiteral() {
        assertEquals("say $1 \\x", CommandTemplate.render("say {player}", Map.of("player", "$1 \\x")));
    }

    @Test
    void placeholdersAreListedInOrderOfFirstUse() {
        assertEquals(List.of("uuid", "tx", "player"), List.copyOf(CommandTemplate.placeholders("a {uuid} b {tx} {uuid} {player} {no_rule}")).subList(0, 3));
        assertEquals(Set.of(), CommandTemplate.placeholders(null));
    }

    @Test
    void goodValuesPassEveryRule() {
        assertNull(CommandTemplate.invalidPlaceholder("consortium contribute {item} {count} {player} {uuid} {tx}", GOOD));
        assertNull(CommandTemplate.invalidPlaceholder(STARTER_CLAIM_COMMAND, GOOD));
        assertNull(CommandTemplate.invalidPlaceholder("say nothing to check", Map.of()));
    }

    /**
     * The starter claim entry (pack/kubejs/data/consortium/consortium_shop/starter.json, specification v0.2 5.4)
     * must address the buyer by {@code {player}}: FTB Chunks takes an {@code EntityArgument.player()}, and that
     * argument refuses a UUID string ({@code EntitySelectorParser.parseNameOrUUID} flags {@code includesEntities}
     * when {@code UUID.fromString} succeeds, then {@code EntityArgument.parse} throws "Only players may be affected").
     * A {@code {uuid}} template would debit every buyer and deliver nothing.
     */
    @Test
    void starterClaimTemplateAddressesTheBuyerByName() {
        assertEquals(Set.of("player"), CommandTemplate.placeholders(STARTER_CLAIM_COMMAND));
        assertEquals("ftbchunks admin extra_claim_chunks Alex add 10", CommandTemplate.render(STARTER_CLAIM_COMMAND, GOOD));
        assertEquals("player", CommandTemplate.invalidPlaceholder(STARTER_CLAIM_COMMAND, with("player", "Legacy Name")));
    }

    @Test
    void playerNamesWithSpacesOrPunctuationAreRefused() {
        assertEquals("player", CommandTemplate.invalidPlaceholder("say {player}", with("player", "Legacy Name")));
        assertEquals("player", CommandTemplate.invalidPlaceholder("say {player}", with("player", "Alex!")));
        assertEquals("player", CommandTemplate.invalidPlaceholder("say {player}", with("player", "")));
        assertEquals("player", CommandTemplate.invalidPlaceholder("say {player}", with("player", "a".repeat(17))));
        assertNull(CommandTemplate.invalidPlaceholder("say {player}", with("player", "a".repeat(16))));
    }

    @Test
    void uuidTxItemAndCountRules() {
        assertEquals("uuid", CommandTemplate.invalidPlaceholder("x {uuid}", with("uuid", "1-2-3")));
        assertNull(CommandTemplate.invalidPlaceholder("x {uuid}", with("uuid", "11111111-2222-3333-4444-55555555555A")));
        assertEquals("tx", CommandTemplate.invalidPlaceholder("x {tx}", with("tx", "A1B2C3D4")));
        assertEquals("tx", CommandTemplate.invalidPlaceholder("x {tx}", with("tx", "a1b2c3d")));
        assertEquals("item", CommandTemplate.invalidPlaceholder("x {item}", with("item", "iron ingot")));
        assertEquals("item", CommandTemplate.invalidPlaceholder("x {item}", with("item", "iron_ingot")), "a namespace is required");
        assertEquals("count", CommandTemplate.invalidPlaceholder("x {count}", with("count", "-1")));
        assertEquals("count", CommandTemplate.invalidPlaceholder("x {count}", with("count", "64 64")));
    }

    @Test
    void missingValuesAreInvalidAndUnknownPlaceholdersAreIgnored() {
        assertEquals("tx", CommandTemplate.invalidPlaceholder("x {player} {tx}", Map.of("player", "Alex")));
        assertNull(CommandTemplate.invalidPlaceholder("x {player} {custom}", Map.of("player", "Alex")));
        assertEquals("player", CommandTemplate.invalidPlaceholder("x {player}", null));
    }

    private static Map<String, String> with(String key, String value) {
        Map<String, String> m = new HashMap<>(GOOD);
        m.put(key, value);
        return m;
    }
}
