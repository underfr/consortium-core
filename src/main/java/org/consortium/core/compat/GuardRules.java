package org.consortium.core.compat;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * The Minecraft-free rules of the Chapters guards and the party stage copy (specification v0.2, sections 3 and 4),
 * kept apart from the event handlers so the unit tests cover them: which stage ids are the pack's phase stages, the
 * fake-player rule and the filter applied when a personal team's stages are copied onto a new party.
 */
public final class GuardRules {
    /** Namespace of every stage the pack's phase engine grants ({@code consortium:phase_1}, licences, charters). */
    public static final String STAGE_NAMESPACE = "consortium";
    /** The prefix of a stage id string in that namespace. */
    public static final String STAGE_PREFIX = STAGE_NAMESPACE + ":";
    /** Path prefix of the phase stages: {@code phase_1} to {@code phase_5}. */
    public static final String PHASE_PATH_PREFIX = "phase_";
    private static final int MAX_PHASE_DIGITS = 6;

    private GuardRules() {
    }

    /**
     * The phase number of a stage id, {@code consortium:phase_k} giving {@code k}, or 0 when the id is not a phase
     * stage (another namespace, another path, no digits, a zero or a number too long to be a phase).
     */
    public static int phaseOf(String namespace, String path) {
        if (!STAGE_NAMESPACE.equals(namespace) || path == null || !path.startsWith(PHASE_PATH_PREFIX)) {
            return 0;
        }
        String digits = path.substring(PHASE_PATH_PREFIX.length());
        if (digits.isEmpty() || digits.length() > MAX_PHASE_DIGITS) {
            return 0;
        }
        int value = 0;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return 0;
            }
            value = value * 10 + (c - '0');
        }
        return value;
    }

    /**
     * The fake-player rule of section 3: a deployer or another fake player (no team, no stages) may place a gated
     * block only when one of its gating stages is {@code consortium:phase_k} with {@code 1 <= k <= boardPhase},
     * where {@code boardPhase} is the phase of the last quota board snapshot (0 before the first publish, which
     * locks every gated block: fail closed during the boot window).
     */
    public static boolean unlockedByPhase(int stagePhase, int boardPhase) {
        return stagePhase >= 1 && stagePhase <= boardPhase;
    }

    /**
     * The stage ids of the consortium namespace, in a fresh mutable set: what a new party inherits from its creator's
     * personal team (section 4 keeps only the pack's stages, never the ids of other mods or of a datapack).
     */
    public static Set<String> consortiumStages(Collection<String> ids) {
        Set<String> copy = new HashSet<>();
        if (ids == null) {
            return copy;
        }
        for (String id : ids) {
            if (id != null && id.startsWith(STAGE_PREFIX) && id.length() > STAGE_PREFIX.length()) {
                copy.add(id);
            }
        }
        return copy;
    }
}
