package org.consortium.core.shop;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.consortium.core.ConsortiumCore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Loads {@code data/<namespace>/consortium_shop/*.json} (specification v0.2, 5.1) on every datapack load. The plain
 * fields go through {@link ShopJson}; the sold stack is parsed with {@code ItemStack.STRICT_CODEC} through the
 * registry-aware ops of the reload (components that reference dynamic registries decode too), the icon against the
 * item registry. An invalid entry is skipped with {@code WARN "Shop: <file>: <reason>"}, the file's other entries
 * load; a key present in two files keeps the first file in id order and warns naming both.
 */
public final class ShopCatalogLoader extends SimpleJsonResourceReloadListener {
    public static final String DIRECTORY = "consortium_shop";

    private final ShopCatalog catalog;
    private final HolderLookup.Provider registries;

    public ShopCatalogLoader(ShopCatalog catalog, HolderLookup.Provider registries) {
        super(new Gson(), DIRECTORY);
        this.catalog = catalog;
        this.registries = registries;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        Map<String, ShopEntry> entries = new LinkedHashMap<>();
        Map<String, String> definedIn = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        RegistryOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
        Map<ResourceLocation, JsonElement> sorted = new TreeMap<>((a, b) -> a.toString().compareTo(b.toString()));
        sorted.putAll(files);
        for (Map.Entry<ResourceLocation, JsonElement> e : sorted.entrySet()) {
            String file = e.getKey().toString();
            if (!e.getValue().isJsonObject()) {
                warnings.add("Shop: " + file + ": not a JSON object, skipped");
                continue;
            }
            JsonObject root = e.getValue().getAsJsonObject();
            if (!root.has("entries") || !root.get("entries").isJsonObject()) {
                warnings.add("Shop: " + file + ": no \"entries\" object, skipped");
                continue;
            }
            for (Map.Entry<String, JsonElement> fe : root.getAsJsonObject("entries").entrySet()) {
                String key = fe.getKey();
                ShopJson.Result parsed = ShopJson.parse(key, fe.getValue());
                if (!parsed.accepted()) {
                    warnings.add("Shop: " + file + ": " + parsed.refusal());
                    continue;
                }
                if (entries.containsKey(key)) {
                    warnings.add("Shop: entry '" + key + "' is defined in both " + definedIn.get(key) + " and " + file + ": the first wins");
                    continue;
                }
                if (entries.size() >= ShopJson.MAX_ENTRIES) {
                    warnings.add("Shop: " + file + ": entry '" + key + "' exceeds the " + ShopJson.MAX_ENTRIES + " entry limit, skipped");
                    continue;
                }
                ShopEntry entry = resolve(parsed.raw(), file, ops, warnings);
                if (entry != null) {
                    entries.put(key, entry);
                    definedIn.put(key, file);
                }
            }
        }
        for (String w : warnings) {
            ConsortiumCore.LOGGER.warn(w);
        }
        ConsortiumCore.LOGGER.info("Shop: loaded {} entries from {} datapack files", entries.size(), files.size());
        catalog.onLoaded(entries, warnings, files.size());
    }

    private static ShopEntry resolve(ShopJson.Raw raw, String file, RegistryOps<JsonElement> ops, List<String> warnings) {
        String at = "Shop: " + file + ": entry '" + raw.key() + "'";
        ItemStack item = ItemStack.EMPTY;
        if (raw.isItem()) {
            List<String> errors = new ArrayList<>();
            Optional<ItemStack> parsed = ItemStack.STRICT_CODEC.parse(ops, raw.item()).resultOrPartial(errors::add);
            if (parsed.isEmpty() || parsed.get().isEmpty()) {
                warnings.add(at + " \"item\" is invalid: " + (errors.isEmpty() ? "unknown item" : errors.get(0)));
                return null;
            }
            item = parsed.get();
        }
        ItemStack icon = item.copy();
        if (raw.icon() != null) {
            ResourceLocation id = ResourceLocation.tryParse(raw.icon());
            Optional<Item> iconItem = id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id);
            if (iconItem.isEmpty()) {
                warnings.add(at + " \"icon\" names no registered item: " + raw.icon());
                return null;
            }
            icon = new ItemStack(iconItem.get());
        }
        ResourceLocation stage = null;
        if (raw.stage() != null) {
            stage = ResourceLocation.tryParse(raw.stage());
            if (stage == null) {
                warnings.add(at + " \"stage\" is not a valid id: " + raw.stage());
                return null;
            }
        }
        return new ShopEntry(raw.key(), raw.name(), raw.description(), item, raw.command(), icon, raw.priceCents(), raw.dailyLimit(),
                stage, raw.phase(), file);
    }
}
