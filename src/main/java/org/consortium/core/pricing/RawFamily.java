package org.consortium.core.pricing;

import java.util.Map;

/**
 * A family as parsed from a datapack file, before the server-config defaults are resolved (the SERVER config is
 * not loaded yet when the first datapack apply runs, so unresolved values stay null until the merge).
 *
 * @param dailyCap null = {@code daily_cap_multiplier x half_volume} from the file or config defaults
 * @param dailyCapMultiplier the file-level default, null = config default
 */
public record RawFamily(String key, String name, long baseCents, double halfVolume, Double floorRatio, Double halfLifeHours,
                        Double dailyCap, Double dailyCapMultiplier, int phase, Map<String, Double> members, String source) {
}
