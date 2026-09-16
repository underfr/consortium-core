package org.consortium.core.economy;

/**
 * A hot price change written by {@code /prices set}, layered over the datapack entry of the same family
 * (specification 2.1, {@code overrides[]}). Fields left null keep the datapack value.
 *
 * @param family    family key (or an item id, which then becomes a single-member family)
 * @param baseCents credits per unit in cents, 0 = quota only
 * @param halfVolume units that halve the price, null = datapack value
 * @param floorRatio null = datapack value
 * @param dailyCap  null = datapack value
 * @param by        admin uuid or {@code console}
 * @param at        epoch millis
 * @param reason    mandatory reason typed by the admin
 */
public record PriceOverride(String family, long baseCents, Double halfVolume, Double floorRatio, Double dailyCap,
                            String by, long at, String reason) {
}
