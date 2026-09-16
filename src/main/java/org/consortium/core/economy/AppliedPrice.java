package org.consortium.core.economy;

/**
 * The values of a family as last applied from the merged table (specification 2.1, {@code applied[]}): the diff
 * base for the "Market update" announcements after a datapack reload or an override change.
 */
public record AppliedPrice(String family, long baseCents, double halfVolume, double floorRatio, double dailyCap) {
    public boolean sameValues(AppliedPrice other) {
        return other != null && baseCents == other.baseCents && halfVolume == other.halfVolume
                && floorRatio == other.floorRatio && dailyCap == other.dailyCap;
    }
}
