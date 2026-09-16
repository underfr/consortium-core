package org.consortium.core.pricing;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.consortium.core.ConsortiumCore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable {@code Item -> (family, weight)} index built from the merged table once the tags are bound
 * (specification 2.3). An item that resolves to two families is refused in both.
 */
public final class FamilyIndex {
    /** An indexed item: its family key and its weight in family units. */
    public record Entry(String family, double weight) {
    }

    public static final FamilyIndex EMPTY = new FamilyIndex(Map.of(), Map.of(), Map.of());

    private final Map<Item, Entry> byItem;
    private final Map<String, String> displayNames;
    private final Map<String, List<String>> memberNames;

    private FamilyIndex(Map<Item, Entry> byItem, Map<String, String> displayNames, Map<String, List<String>> memberNames) {
        this.byItem = byItem;
        this.displayNames = displayNames;
        this.memberNames = memberNames;
    }

    public Optional<Entry> entryOf(Item item) {
        return Optional.ofNullable(byItem.get(item));
    }

    public boolean isPriced(Item item) {
        return byItem.containsKey(item);
    }

    public int size() {
        return byItem.size();
    }

    /** Display name resolved from the first member, or null when nothing resolved. */
    public String displayName(String family) {
        return displayNames.get(family);
    }

    /** Human readable member list of a family, e.g. {@code Steel Ingot x1, Block of Steel x9}. */
    public List<String> memberNames(String family) {
        return memberNames.getOrDefault(family, List.of());
    }

    /** Sorted item ids of the index (command suggestions). */
    public Set<String> itemIds() {
        Set<String> ids = new TreeSet<>();
        for (Item item : byItem.keySet()) {
            ids.add(BuiltInRegistries.ITEM.getKey(item).toString());
        }
        return Collections.unmodifiableSet(ids);
    }

    /** Builds the index from the merged families. Must run with the tags bound (after {@code TagsUpdatedEvent}). */
    public static FamilyIndex build(Collection<PriceFamily> families) {
        Map<Item, Entry> byItem = new HashMap<>();
        Map<Item, String> firstFamily = new HashMap<>();
        Set<Item> refused = new java.util.HashSet<>();
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, List<String>> members = new LinkedHashMap<>();
        int warnings = 0;
        for (PriceFamily family : families) {
            List<String> memberText = new ArrayList<>();
            for (Map.Entry<String, Double> m : family.members().entrySet()) {
                String id = m.getKey();
                double weight = m.getValue();
                List<Item> items = resolve(id);
                if (items.isEmpty()) {
                    ConsortiumCore.LOGGER.warn("Price table: family '{}' member '{}' resolves to no item (unknown id or empty tag), skipped", family.key(), id);
                    warnings++;
                    continue;
                }
                for (Item item : items) {
                    String owner = firstFamily.get(item);
                    if (owner != null && !owner.equals(family.key())) {
                        if (refused.add(item)) {
                            ConsortiumCore.LOGGER.error("Price table: item '{}' belongs to both family '{}' and family '{}', refused in both",
                                    BuiltInRegistries.ITEM.getKey(item), owner, family.key());
                        }
                        byItem.remove(item);
                        continue;
                    }
                    if (refused.contains(item)) {
                        continue;
                    }
                    firstFamily.put(item, family.key());
                    byItem.put(item, new Entry(family.key(), weight));
                }
                String memberName = new ItemStack(items.get(0)).getHoverName().getString();
                memberText.add(memberName + " x" + org.consortium.core.economy.Units.format(weight));
                if (!names.containsKey(family.key())) {
                    names.put(family.key(), memberName);
                }
            }
            members.put(family.key(), Collections.unmodifiableList(memberText));
        }
        if (warnings > 0) {
            ConsortiumCore.LOGGER.warn("Price table: {} member(s) could not be resolved", warnings);
        }
        return new FamilyIndex(Map.copyOf(byItem), Map.copyOf(names), Map.copyOf(members));
    }

    /** Expands an item id or a {@code #tag} into items; empty when unknown. */
    public static List<Item> resolve(String id) {
        List<Item> out = new ArrayList<>();
        if (id.startsWith("#")) {
            ResourceLocation rl = ResourceLocation.tryParse(id.substring(1));
            if (rl == null) {
                return out;
            }
            for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, rl))) {
                out.add(holder.value());
            }
        } else {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null) {
                BuiltInRegistries.ITEM.getOptional(rl).ifPresent(out::add);
            }
        }
        return out;
    }
}
