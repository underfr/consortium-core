package org.consortium.core.compat;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardRulesTest {
    @Test
    void phaseOfReadsTheConsortiumPhaseStagesOnly() {
        assertEquals(1, GuardRules.phaseOf("consortium", "phase_1"));
        assertEquals(5, GuardRules.phaseOf("consortium", "phase_5"));
        assertEquals(12, GuardRules.phaseOf("consortium", "phase_12"));
        assertEquals(0, GuardRules.phaseOf("consortium", "phase_0"));
        assertEquals(0, GuardRules.phaseOf("consortium", "phase_"));
        assertEquals(0, GuardRules.phaseOf("consortium", "phase_x"));
        assertEquals(0, GuardRules.phaseOf("consortium", "phase_1a"));
        assertEquals(0, GuardRules.phaseOf("consortium", "phase_-1"));
        assertEquals(0, GuardRules.phaseOf("consortium", "phase_1234567"));
        assertEquals(0, GuardRules.phaseOf("consortium", "extraction_p1"));
        assertEquals(0, GuardRules.phaseOf("consortium", "staff"));
        assertEquals(0, GuardRules.phaseOf("othermod", "phase_1"));
        assertEquals(0, GuardRules.phaseOf(null, "phase_1"));
        assertEquals(0, GuardRules.phaseOf("consortium", null));
    }

    @Test
    void fakePlayersUnlockAtTheBoardPhaseAndAreLockedBeforeTheFirstPublish() {
        assertTrue(GuardRules.unlockedByPhase(1, 1));
        assertTrue(GuardRules.unlockedByPhase(2, 3));
        assertFalse(GuardRules.unlockedByPhase(3, 2));
        // board phase 0 = no snapshot yet: every gated block stays locked (fail closed)
        assertFalse(GuardRules.unlockedByPhase(1, 0));
        // a non-phase stage (0) never unlocks anything for a fake player
        assertFalse(GuardRules.unlockedByPhase(0, 5));
        assertFalse(GuardRules.unlockedByPhase(-1, 5));
    }

    @Test
    void partyCopyKeepsTheConsortiumStagesInAFreshMutableSet() {
        List<String> raw = Arrays.asList("consortium:phase_1", "consortium:phase_2", "consortium:extraction_p1",
                "othermod:whatever", "consortium:", "", null);
        Set<String> copy = GuardRules.consortiumStages(raw);
        assertEquals(Set.of("consortium:phase_1", "consortium:phase_2", "consortium:extraction_p1"), copy);
        copy.add("consortium:phase_3");
        assertEquals(4, copy.size());
        assertTrue(GuardRules.consortiumStages(null).isEmpty());
        assertTrue(GuardRules.consortiumStages(List.of()).isEmpty());
        assertTrue(GuardRules.consortiumStages(List.of("ftbquests:chapter")).isEmpty());
    }
}
