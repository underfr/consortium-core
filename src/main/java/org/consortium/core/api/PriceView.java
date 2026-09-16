package org.consortium.core.api;

import java.util.List;

/**
 * Read-only view of a family for scripts and screens.
 *
 * @param key         family key
 * @param name        display name
 * @param baseCents   credits per unit in cents (0 = quota only)
 * @param halfVolume  units that halve the price
 * @param floorRatio  fraction of the base the price never goes below
 * @param dailyCap    paid units per player per UTC day (0 = uncapped)
 * @param saturation  current decayed saturation in units
 * @param unitCents   current unit price in cents
 * @param quotaOnly   true when the family pays nothing and only counts for the phase engine's quota
 * @param overridden  true when a {@code /prices set} override is active
 * @param charterFamily {@code raw}, {@code power}, {@code transport} or {@code neutral} (PROGRESSION 9.1, v0.2 section 6)
 * @param members     human readable member list
 */
public record PriceView(String key, String name, long baseCents, double halfVolume, double floorRatio, double dailyCap,
                        double saturation, long unitCents, boolean quotaOnly, boolean overridden, String charterFamily,
                        List<String> members) {
}
