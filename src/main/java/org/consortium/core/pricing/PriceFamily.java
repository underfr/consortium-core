package org.consortium.core.pricing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One family of the merged price table (specification 2.3): datapack values with the server-config defaults
 * filled in and any {@code /prices set} override layered on top.
 *
 * @param key        family key, e.g. {@code c:ingots/steel} or {@code minecraft:iron_ingot}
 * @param name       display name, null = display name of the first member
 * @param baseCents  credits per unit in cents; 0 = quota only
 * @param halfVolume units that halve the price (ignored when quota only)
 * @param floorRatio 0.01 to 1
 * @param halfLifeHours recovery half life
 * @param dailyCap   paid units per player per UTC day; 0 = uncapped
 * @param phase      display only
 * @param members    item id or {@code #tag} to weight (family units per item)
 * @param source     datapack file id that defined the family, or {@code override} for a synthesized single-item family
 * @param override   true when an override is layered on the datapack entry
 */
public record PriceFamily(String key, String name, long baseCents, double halfVolume, double floorRatio,
                          double halfLifeHours, double dailyCap, int phase, Map<String, Double> members,
                          String source, boolean override) {

    public PriceFamily {
        members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
    }

    public boolean quotaOnly() {
        return baseCents <= 0;
    }

    public PriceCurve.Params params() {
        return new PriceCurve.Params(baseCents, halfVolume, floorRatio, halfLifeHours, dailyCap).sanitized();
    }

    public PriceFamily withOverride(long newBase, Double newHalfVolume, Double newFloor, Double newCap) {
        return new PriceFamily(key, name, newBase,
                newHalfVolume != null ? newHalfVolume : halfVolume,
                newFloor != null ? newFloor : floorRatio,
                halfLifeHours,
                newCap != null ? newCap : dailyCap,
                phase, members, source, true);
    }

    /** Single-member short form: the key is its own member at weight 1. */
    public static PriceFamily single(String key, long baseCents, double halfVolume, double floorRatio,
                                     double halfLifeHours, double dailyCap, String source) {
        return new PriceFamily(key, null, baseCents, halfVolume, floorRatio, halfLifeHours, dailyCap, 0,
                Map.of(key, 1.0), source, false);
    }
}
