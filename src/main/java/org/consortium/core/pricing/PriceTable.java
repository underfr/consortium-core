package org.consortium.core.pricing;

import net.minecraft.world.item.Item;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.economy.AppliedPrice;
import org.consortium.core.economy.EconomyData;
import org.consortium.core.economy.PriceOverride;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The merged price table (specification 2.3): datapack families with the config defaults resolved, the SavedData
 * overrides layered on top, and the {@code Item -> family} index. Reads are lock free (volatile snapshots swapped
 * atomically); every rebuild runs on the server thread.
 */
public final class PriceTable {
    private volatile Map<String, RawFamily> datapack = Map.of();
    private volatile Map<String, PriceFamily> merged = Map.of();
    private volatile FamilyIndex index = FamilyIndex.EMPTY;
    private volatile boolean tagsBound;
    private volatile boolean datapackPending;
    private Consumer<PriceTable> datapackListener;

    /** Set by the runtime: called on the server thread after every datapack load. */
    public void setDatapackListener(Consumer<PriceTable> listener) {
        this.datapackListener = listener;
    }

    /** Called by the reload listener; the merge waits for the runtime when the server is not up yet. */
    void onDatapackLoaded(Map<String, RawFamily> families) {
        this.datapack = Map.copyOf(families);
        this.datapackPending = true;
        Consumer<PriceTable> listener = datapackListener;
        if (listener != null) {
            listener.accept(this);
        }
    }

    public boolean datapackPending() {
        return datapackPending;
    }

    public void tagsBound() {
        this.tagsBound = true;
    }

    public boolean areTagsBound() {
        return tagsBound;
    }

    public Map<String, RawFamily> datapackFamilies() {
        return datapack;
    }

    public PriceFamily family(String key) {
        return merged.get(key);
    }

    /** Merged families in key order. */
    public Collection<PriceFamily> families() {
        return Collections.unmodifiableCollection(new TreeMap<>(merged).values());
    }

    public int familyCount() {
        return merged.size();
    }

    public FamilyIndex index() {
        return index;
    }

    public boolean isPriced(Item item) {
        return index.isPriced(item);
    }

    public Optional<FamilyIndex.Entry> entryOf(Item item) {
        return index.entryOf(item);
    }

    public Optional<String> familyOf(Item item) {
        return index.entryOf(item).map(FamilyIndex.Entry::family);
    }

    /** Display name: the family's {@code name}, else the first member's item name, else the key. */
    public String displayName(String key) {
        PriceFamily f = merged.get(key);
        if (f != null && f.name() != null && !f.name().isBlank()) {
            return f.name();
        }
        String resolved = index.displayName(key);
        return resolved != null ? resolved : key;
    }

    /**
     * Rebuilds the merged table from the datapack, the defaults and the overrides in {@code data}, folds overrides
     * that equal their datapack entry, diffs against {@code applied[]} and updates it. Returns the changes to
     * announce and log. Runs on the server thread.
     *
     * @param by     {@code datapack}, {@code console} or an admin uuid, stamped on every change
     * @param reason reason stamped on every change (the admin's text or {@code pack update})
     */
    public List<PriceChange> rebuild(EconomyData data, PriceDefaults defaults, String by, String reason) {
        datapackPending = false;
        Map<String, PriceFamily> next = new LinkedHashMap<>();
        for (RawFamily raw : datapack.values()) {
            next.put(raw.key(), resolve(raw, defaults));
        }
        List<PriceChange> changes = new ArrayList<>();
        for (PriceOverride o : new ArrayList<>(data.overrides().values())) {
            PriceFamily base = next.get(o.family());
            if (base != null) {
                PriceFamily overridden = base.withOverride(o.baseCents(), o.halfVolume(), o.floorRatio(), o.dailyCap());
                if (sameValues(base, overridden)) {
                    data.removeOverride(o.family());
                    changes.add(new PriceChange(o.family(), base, base, by, "override folded into the datapack"));
                    ConsortiumCore.LOGGER.info("Price table: override on '{}' equals the datapack entry, dropped", o.family());
                    continue;
                }
                next.put(o.family(), overridden);
            } else {
                // A single-member family created by /prices set on an item absent from the datapack.
                double halfVolume = o.halfVolume() != null ? o.halfVolume() : 1;
                double floor = o.floorRatio() != null ? o.floorRatio() : defaults.floorRatio();
                double cap = o.dailyCap() != null ? o.dailyCap() : (o.baseCents() > 0 ? defaults.dailyCapMultiplier() * halfVolume : 0);
                next.put(o.family(), new PriceFamily(o.family(), null, o.baseCents(), halfVolume, floor, defaults.halfLifeHours(), cap, 0,
                        PriceFamily.NEUTRAL, Map.of(o.family(), 1.0), "override", true));
            }
        }
        // Diff against the applied table.
        for (PriceFamily f : next.values()) {
            AppliedPrice previous = data.applied(f.key());
            AppliedPrice current = new AppliedPrice(f.key(), f.baseCents(), f.halfVolume(), f.floorRatio(), f.dailyCap());
            if (previous == null) {
                changes.add(new PriceChange(f.key(), null, f, by, reason));
                data.putApplied(current);
            } else if (!current.sameValues(previous)) {
                PriceFamily before = new PriceFamily(f.key(), f.name(), previous.baseCents(), previous.halfVolume(), previous.floorRatio(),
                        f.halfLifeHours(), previous.dailyCap(), f.phase(), f.charterFamily(), f.members(), f.source(), false);
                changes.add(new PriceChange(f.key(), before, f, by, reason));
                data.putApplied(current);
            }
        }
        for (String key : new ArrayList<>(data.appliedPrices().keySet())) {
            if (!next.containsKey(key)) {
                AppliedPrice previous = data.applied(key);
                PriceFamily before = new PriceFamily(key, null, previous.baseCents(), previous.halfVolume(), previous.floorRatio(),
                        defaults.halfLifeHours(), previous.dailyCap(), 0, PriceFamily.NEUTRAL, Map.of(key, 1.0), "removed", false);
                changes.add(new PriceChange(key, before, null, by, reason));
                data.removeApplied(key);
            }
        }
        this.merged = Map.copyOf(next);
        if (tagsBound) {
            rebuildIndex();
        }
        return changes;
    }

    /** Rebuilds the item index from the merged families; a no-op until the tags are bound. */
    public void rebuildIndex() {
        if (!tagsBound) {
            return;
        }
        if (merged.isEmpty() && datapackPending) {
            // First boot: the tags are bound before the saved data exists; ServerStartedEvent merges and indexes.
            return;
        }
        FamilyIndex built = FamilyIndex.build(merged.values());
        this.index = built;
        ConsortiumCore.LOGGER.info("Indexed {} families, {} items", merged.size(), built.size());
    }

    private static boolean sameValues(PriceFamily a, PriceFamily b) {
        return a.baseCents() == b.baseCents() && a.halfVolume() == b.halfVolume()
                && a.floorRatio() == b.floorRatio() && a.dailyCap() == b.dailyCap();
    }

    private static PriceFamily resolve(RawFamily raw, PriceDefaults defaults) {
        double floor = raw.floorRatio() != null ? raw.floorRatio() : defaults.floorRatio();
        double halfLife = raw.halfLifeHours() != null ? raw.halfLifeHours() : defaults.halfLifeHours();
        double cap;
        if (raw.dailyCap() != null) {
            cap = raw.dailyCap();
        } else if (raw.baseCents() <= 0) {
            cap = 0;
        } else {
            double multiplier = raw.dailyCapMultiplier() != null ? raw.dailyCapMultiplier() : defaults.dailyCapMultiplier();
            cap = multiplier > 0 ? multiplier * raw.halfVolume() : 0;
        }
        return new PriceFamily(raw.key(), raw.name(), raw.baseCents(), raw.halfVolume(), floor, halfLife, cap, raw.phase(),
                raw.charterFamily(), raw.members(), raw.source(), false);
    }
}
