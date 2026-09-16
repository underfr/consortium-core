package org.consortium.core.identity;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code world/data/consortium_identity.dat}: the connection hashes already served with a starting capital and the
 * hash each player was linked to (specification 2.1). Separate from the economy so it can be purged without touching
 * money. Never contains an address.
 */
public final class IdentityData extends SavedData {
    public static final String NAME = "consortium_identity";
    public static final Factory<IdentityData> FACTORY = new Factory<>(IdentityData::new, IdentityData::load);

    /** A served hash: the first account that received the grant on that connection and when. */
    public record Served(UUID firstUuid, long at) {
    }

    private final Map<String, Served> served = new LinkedHashMap<>();
    private final Map<UUID, String> byPlayer = new LinkedHashMap<>();

    public static IdentityData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public Served served(String hash) {
        return served.get(hash);
    }

    public void markServed(String hash, UUID uuid, long at) {
        served.putIfAbsent(hash, new Served(uuid, at));
        byPlayer.put(uuid, hash);
        setDirty();
    }

    /** Links a player to a hash without marking it served (a denied account keeps the link for the ops). */
    public void link(UUID uuid, String hash) {
        byPlayer.put(uuid, hash);
        setDirty();
    }

    public String hashOf(UUID uuid) {
        return byPlayer.get(uuid);
    }

    /** Deletion request: removes the player's link; the served hash entry stays only if another player owns it. */
    public boolean forget(UUID uuid) {
        String hash = byPlayer.remove(uuid);
        if (hash == null) {
            return false;
        }
        Served s = served.get(hash);
        if (s != null && s.firstUuid().equals(uuid)) {
            served.remove(hash);
        }
        setDirty();
        return true;
    }

    /** Season-end purge: everything goes. */
    public void purge() {
        served.clear();
        byPlayer.clear();
        setDirty();
    }

    public int servedCount() {
        return served.size();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag servedList = new ListTag();
        for (Map.Entry<String, Served> e : served.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putString("hash", e.getKey());
            t.putUUID("first_uuid", e.getValue().firstUuid());
            t.putLong("at", e.getValue().at());
            servedList.add(t);
        }
        tag.put("served", servedList);
        ListTag players = new ListTag();
        for (Map.Entry<UUID, String> e : byPlayer.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("uuid", e.getKey());
            t.putString("hash", e.getValue());
            players.add(t);
        }
        tag.put("by_player", players);
        return tag;
    }

    public static IdentityData load(CompoundTag tag, HolderLookup.Provider registries) {
        IdentityData data = new IdentityData();
        for (Tag raw : tag.getList("served", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            if (t.hasUUID("first_uuid")) {
                data.served.put(t.getString("hash"), new Served(t.getUUID("first_uuid"), t.getLong("at")));
            }
        }
        for (Tag raw : tag.getList("by_player", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            if (t.hasUUID("uuid")) {
                data.byPlayer.put(t.getUUID("uuid"), t.getString("hash"));
            }
        }
        return data;
    }
}
