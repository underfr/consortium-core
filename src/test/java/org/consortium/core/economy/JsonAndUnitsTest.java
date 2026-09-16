package org.consortium.core.economy;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonAndUnitsTest {

    @Test
    void encodesNestedStructuresInInsertionOrder() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "a\"b\\c\nd");
        m.put("n", 42L);
        m.put("d", 0.1111);
        m.put("i", 9.0);
        m.put("b", true);
        m.put("z", null);
        m.put("l", List.of(Map.of("id", "minecraft:iron_ingot"), 3));
        m.put("e", LedgerType.DELIVERY);
        assertEquals("{\"s\":\"a\\\"b\\\\c\\nd\",\"n\":42,\"d\":0.1111,\"i\":9,\"b\":true,\"z\":null,\"l\":[{\"id\":\"minecraft:iron_ingot\"},3],\"e\":\"DELIVERY\"}",
                Json.encode(m));
    }

    @Test
    void nonFiniteDoublesBecomeNull() {
        assertEquals("null", Json.encode(Double.NaN));
        assertEquals("0.00000010", Json.encode(1e-7), "no exponent notation in the ledger");
        assertEquals("1234567890123456", Json.encode(1.234567890123456E15));
    }

    @Test
    void unitsRoundToThreeDecimalsAndFormatCleanly() {
        assertEquals(1.0, Units.round(9 * 0.1111), 1e-12, "nine nuggets make one unit");
        assertEquals(0.111, Units.round(0.1111));
        assertEquals("4,321", Units.format(4321));
        assertEquals("0.111", Units.format(0.1111));
        assertEquals("1,234.5", Units.format(1234.5));
        assertEquals("0", Units.format(Double.NaN));
    }
}
