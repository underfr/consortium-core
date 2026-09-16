package org.consortium.core.board;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * {@code world/data/consortium_board.dat} (specification v0.2, 2.5): the last accepted quota board as its canonical
 * JSON string, its revision (+1 per accepted change) and the publish instant. Separate from the economy file so a
 * board publish never dirties the accounts. Boards therefore show the last snapshot right after a reboot, before
 * the engine's first check republishes.
 */
public final class BoardData extends SavedData {
    public static final String NAME = "consortium_board";
    public static final int SCHEMA_VERSION = 1;
    public static final Factory<BoardData> FACTORY = new Factory<>(BoardData::new, BoardData::load);

    private int revision;
    private long publishedAt;
    private String json = "";

    public static BoardData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public int revision() {
        return revision;
    }

    public long publishedAt() {
        return publishedAt;
    }

    /** The canonical JSON of the last accepted snapshot, empty when none was ever accepted. */
    public String json() {
        return json;
    }

    /** Stores a newly accepted snapshot: the revision moves on, the instant is recorded, the data is dirty. */
    public void store(String canonicalJson, long now) {
        this.json = canonicalJson == null ? "" : canonicalJson;
        this.revision++;
        this.publishedAt = now;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", SCHEMA_VERSION);
        tag.putInt("revision", revision);
        tag.putLong("published_at", publishedAt);
        tag.putString("json", json);
        return tag;
    }

    public static BoardData load(CompoundTag tag, HolderLookup.Provider registries) {
        BoardData data = new BoardData();
        data.revision = tag.getInt("revision");
        data.publishedAt = tag.getLong("published_at");
        data.json = tag.getString("json");
        return data;
    }
}
