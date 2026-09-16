package org.consortium.core.shop;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.compat.ChaptersBridge;
import org.consortium.core.pricing.PriceTable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shop catalogue (specification v0.2, 5.1): the entries the datapack loader parsed, swapped atomically on every
 * reload, plus the load warnings and the refusals of {@link #audit} for {@code /ccore shop list}. One per JVM, like
 * the price table; reads are lock free.
 *
 * <p>{@link #audit(PriceTable)} runs on the server thread once the item index exists (after
 * {@code PriceTable.rebuildIndex} in {@code TagsUpdatedEvent}, and once more after the runtime merged the prices at
 * the first boot): an item entry the Consortium buys is refused (buy-then-sell loops are forbidden, CONSORTIUM_CORE
 * 2.3), and an item entry Chapters locks without a matching {@code stage} gets a warning (it loads: the datapack may
 * gate it by {@code phase} on purpose, and the purchase refuses locked buyers anyway).
 */
public final class ShopCatalog {
    private volatile Map<String, ShopEntry> entries = Map.of();
    private volatile List<String> loadWarnings = List.of();
    private volatile Map<String, String> refusals = Map.of();
    private volatile List<String> auditWarnings = List.of();
    private volatile int fileCount;

    /** Called by the reload listener with the parsed entries in file then entry order. */
    void onLoaded(Map<String, ShopEntry> loaded, List<String> warnings, int files) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(loaded));
        this.loadWarnings = List.copyOf(warnings);
        this.refusals = Map.of();
        this.auditWarnings = List.of();
        this.fileCount = files;
    }

    /** Every loaded entry, refused ones included, in catalogue order. */
    public Collection<ShopEntry> entries() {
        return entries.values();
    }

    public ShopEntry entry(String key) {
        return key == null ? null : entries.get(key);
    }

    public int size() {
        return entries.size();
    }

    public int fileCount() {
        return fileCount;
    }

    /** The reason an entry is refused by the last audit (a priced item), or null when it sells. */
    public String refusal(String key) {
        return refusals.get(key);
    }

    /** Load warnings, then audit refusals and warnings: what {@code /ccore shop list} prints after the entries. */
    public List<String> warnings() {
        List<String> out = new ArrayList<>(loadWarnings);
        for (Map.Entry<String, String> e : refusals.entrySet()) {
            out.add(e.getValue());
        }
        out.addAll(auditWarnings);
        return out;
    }

    /**
     * The price-table and Chapters-lock checks of 5.1. A no-op while the item index is not built yet (the first
     * {@code TagsUpdatedEvent} of a boot runs before the saved data and the merged table exist).
     */
    public void audit(PriceTable prices) {
        if (!prices.areTagsBound() || (prices.familyCount() == 0 && prices.datapackPending())) {
            return;
        }
        Map<String, String> refused = new LinkedHashMap<>();
        List<String> warned = new ArrayList<>();
        for (ShopEntry entry : entries.values()) {
            if (!entry.isItem()) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(entry.item().getItem());
            if (prices.isPriced(entry.item().getItem())) {
                refused.put(entry.key(), "Shop: " + entry.key() + " sells " + id + ", which the Consortium buys: buy-then-sell loops are forbidden");
                continue;
            }
            if (!ChaptersBridge.available()) {
                continue;
            }
            Set<ResourceLocation> stages = ChaptersBridge.gatingStages(entry.item().getItem());
            if (!stages.isEmpty() && (entry.stage() == null || !stages.contains(entry.stage()))) {
                warned.add("Shop: " + entry.key() + " sells " + id + ", which Chapters locks behind " + stages
                        + "; buyers who hold none of them are refused");
            }
        }
        this.refusals = Collections.unmodifiableMap(refused);
        this.auditWarnings = List.copyOf(warned);
        for (String r : refused.values()) {
            ConsortiumCore.LOGGER.warn(r);
        }
        for (String w : warned) {
            ConsortiumCore.LOGGER.warn(w);
        }
    }
}
