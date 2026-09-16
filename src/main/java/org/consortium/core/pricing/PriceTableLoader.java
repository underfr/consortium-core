package org.consortium.core.pricing;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.economy.Money;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Loads {@code data/<namespace>/consortium_prices/*.json} (specification 2.3). Runs on every datapack load,
 * including the first one at boot, which happens before the server, its config and its saved data exist: the
 * parsed families are handed to {@link PriceTable}, which merges overrides and announces changes once it can.
 */
public final class PriceTableLoader extends SimpleJsonResourceReloadListener {
    public static final String DIRECTORY = "consortium_prices";

    private final PriceTable table;

    public PriceTableLoader(PriceTable table) {
        super(new Gson(), DIRECTORY);
        this.table = table;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        Map<String, RawFamily> families = new LinkedHashMap<>();
        Map<String, String> definedIn = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        // Files in key order so "first file wins" is deterministic.
        Map<ResourceLocation, JsonElement> sorted = new TreeMap<>((a, b) -> a.toString().compareTo(b.toString()));
        sorted.putAll(files);
        for (Map.Entry<ResourceLocation, JsonElement> e : sorted.entrySet()) {
            String file = e.getKey().toString();
            if (!e.getValue().isJsonObject()) {
                warnings.add(file + ": not a JSON object, skipped");
                continue;
            }
            JsonObject root = e.getValue().getAsJsonObject();
            Double fileFloor = null;
            Double fileHalfLife = null;
            Double fileCapMultiplier = null;
            if (root.has("defaults") && root.get("defaults").isJsonObject()) {
                JsonObject d = root.getAsJsonObject("defaults");
                fileFloor = optDouble(d, "floor_ratio");
                fileHalfLife = optDouble(d, "half_life_hours");
                fileCapMultiplier = optDouble(d, "daily_cap_multiplier");
            }
            if (!root.has("families") || !root.get("families").isJsonObject()) {
                warnings.add(file + ": no \"families\" object, skipped");
                continue;
            }
            for (Map.Entry<String, JsonElement> fe : root.getAsJsonObject("families").entrySet()) {
                String key = fe.getKey();
                if (ResourceLocation.tryParse(key) == null) {
                    warnings.add(file + ": family key '" + key + "' is not a valid id, skipped");
                    continue;
                }
                if (families.containsKey(key)) {
                    warnings.add("family '" + key + "' is defined in both " + definedIn.get(key) + " and " + file + ": the first wins");
                    continue;
                }
                if (!fe.getValue().isJsonObject()) {
                    warnings.add(file + ": family '" + key + "' is not an object, skipped");
                    continue;
                }
                RawFamily family = parseFamily(key, fe.getValue().getAsJsonObject(), fileFloor, fileHalfLife, fileCapMultiplier, file, warnings);
                if (family != null) {
                    families.put(key, family);
                    definedIn.put(key, file);
                }
            }
        }
        for (String w : warnings) {
            ConsortiumCore.LOGGER.warn("Price table: {}", w);
        }
        ConsortiumCore.LOGGER.info("Price table: parsed {} families from {} datapack files", families.size(), files.size());
        table.onDatapackLoaded(families);
    }

    private static RawFamily parseFamily(String key, JsonObject o, Double fileFloor, Double fileHalfLife, Double fileCapMultiplier,
                                         String file, List<String> warnings) {
        Double base = optDouble(o, "base");
        if (base == null || base < 0 || Double.isNaN(base) || Double.isInfinite(base)) {
            warnings.add(file + ": family '" + key + "' needs a \"base\" >= 0, skipped");
            return null;
        }
        long baseCents = Money.floorToCents(base);
        Double halfVolume = optDouble(o, "half_volume");
        if (baseCents > 0 && (halfVolume == null || halfVolume <= 0)) {
            warnings.add(file + ": paid family '" + key + "' needs a \"half_volume\" > 0, skipped");
            return null;
        }
        if (halfVolume == null || halfVolume <= 0) {
            halfVolume = 1.0;
        }
        Double floor = optDouble(o, "floor_ratio");
        if (floor != null && (floor < 0.01 || floor > 1)) {
            warnings.add(file + ": family '" + key + "' floor_ratio must be 0.01 to 1, using the default");
            floor = null;
        }
        if (floor == null) {
            floor = fileFloor;
        }
        Double halfLife = optDouble(o, "half_life_hours");
        if (halfLife != null && halfLife <= 0) {
            warnings.add(file + ": family '" + key + "' half_life_hours must be > 0, using the default");
            halfLife = null;
        }
        if (halfLife == null) {
            halfLife = fileHalfLife;
        }
        Double cap = optDouble(o, "daily_cap");
        if (cap != null && cap < 0) {
            warnings.add(file + ": family '" + key + "' daily_cap must be >= 0, using the default");
            cap = null;
        }
        String name = o.has("name") && o.get("name").isJsonPrimitive() ? o.get("name").getAsString() : null;
        int phase = 0;
        Double phaseValue = optDouble(o, "phase");
        if (phaseValue != null) {
            phase = phaseValue.intValue();
        }
        Map<String, Double> members = new LinkedHashMap<>();
        if (o.has("members")) {
            if (!o.get("members").isJsonObject()) {
                warnings.add(file + ": family '" + key + "' members must be an object, skipped");
                return null;
            }
            for (Map.Entry<String, JsonElement> m : o.getAsJsonObject("members").entrySet()) {
                String id = m.getKey();
                String plain = id.startsWith("#") ? id.substring(1) : id;
                if (ResourceLocation.tryParse(plain) == null) {
                    warnings.add(file + ": family '" + key + "' member '" + id + "' is not a valid id, skipped");
                    continue;
                }
                double weight;
                try {
                    weight = m.getValue().getAsDouble();
                } catch (RuntimeException ex) {
                    warnings.add(file + ": family '" + key + "' member '" + id + "' weight is not a number, skipped");
                    continue;
                }
                if (!(weight > 0) || Double.isInfinite(weight)) {
                    warnings.add(file + ": family '" + key + "' member '" + id + "' weight must be > 0, skipped");
                    continue;
                }
                members.put(id, weight);
            }
            if (members.isEmpty()) {
                warnings.add(file + ": family '" + key + "' has no valid member, skipped");
                return null;
            }
        } else {
            members.put(key, 1.0);
        }
        return new RawFamily(key, name, baseCents, halfVolume, floor, halfLife, cap, fileCapMultiplier, phase, members, file);
    }

    private static Double optDouble(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) {
            return null;
        }
        try {
            return o.get(key).getAsDouble();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
