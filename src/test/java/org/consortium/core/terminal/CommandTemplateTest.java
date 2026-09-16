package org.consortium.core.terminal;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandTemplateTest {

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
}
