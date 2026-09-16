package org.consortium.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smoke test that keeps the JUnit 5 wiring alive until real unit tests (pricing, money, ledger) land. */
class ConsortiumCoreTest {

    @Test
    void modIdIsAValidNeoForgeModId() {
        // FML rule: lowercase letters, digits and underscores, 2 to 64 characters, starting with a letter.
        assertTrue(ConsortiumCore.MOD_ID.matches("[a-z][a-z0-9_]{1,63}"), "mod id " + ConsortiumCore.MOD_ID);
    }
}
