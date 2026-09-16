package org.consortium.core.pricing;

/** Server-config defaults applied to families that do not set the value themselves (specification 2.4, [pricing]). */
public record PriceDefaults(double floorRatio, double halfLifeHours, double dailyCapMultiplier) {
    public static final PriceDefaults FALLBACK = new PriceDefaults(0.10, 6.0, 4);
}
