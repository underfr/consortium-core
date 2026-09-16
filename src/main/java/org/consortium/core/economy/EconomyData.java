package org.consortium.core.economy;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.consortium.core.ConsortiumCore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code world/data/consortium_economy.dat}: every account, market state, price override, applied price and the
 * daily supply aggregates (specification 2.1). Saved by the vanilla save pass together with the player inventories,
 * so a crash rolls money and items back together. Phases, quotas and stages belong to the KubeJS phase engine of the
 * pack: the mod keeps no phase counter, and a {@code phase} compound left by an earlier build is ignored on load.
 *
 * <p>Every mutation goes through a method of this class that calls {@link #setDirty()}; callers never keep a
 * reference to the internal maps.
 */
public final class EconomyData extends SavedData {
    public static final String NAME = "consortium_economy";
    public static final int SCHEMA_VERSION = 1;
    /** Days of {@code supply[]} kept. */
    public static final int SUPPLY_DAYS_KEPT = 35;

    public static final Factory<EconomyData> FACTORY = new Factory<>(EconomyData::new, EconomyData::load);

    private final Map<UUID, Account> accounts = new LinkedHashMap<>();
    private final Map<String, MarketState> market = new LinkedHashMap<>();
    private final Map<String, PriceOverride> overrides = new LinkedHashMap<>();
    private final Map<String, AppliedPrice> applied = new LinkedHashMap<>();
    private final Map<String, SupplyDay> supply = new LinkedHashMap<>();
    private long lastSeq;
    private String lastWeeklyReport = "";

    public static EconomyData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    // ---- accounts ----

    public Account account(UUID uuid) {
        return accounts.get(uuid);
    }

    /** Finds an account by name, case-insensitively (offline names for admin commands). */
    public Account accountByName(String name) {
        for (Account a : accounts.values()) {
            if (a.name != null && a.name.equalsIgnoreCase(name)) {
                return a;
            }
        }
        return null;
    }

    /** Creates the account when absent ({@code grant NONE}, {@code first_login 0}). */
    public Account getOrCreate(UUID uuid, String name) {
        Account a = accounts.get(uuid);
        if (a == null) {
            a = new Account(uuid, name == null ? "<unknown>" : name);
            accounts.put(uuid, a);
            setDirty();
        }
        return a;
    }

    public Collection<Account> accounts() {
        return Collections.unmodifiableCollection(accounts.values());
    }

    /** Marks the data dirty after a caller mutated an account it obtained from this class. */
    public void touch() {
        setDirty();
    }

    // ---- market ----

    public MarketState marketState(String family) {
        return market.get(family);
    }

    public MarketState marketStateOrCreate(String family) {
        MarketState s = market.get(family);
        if (s == null) {
            s = new MarketState(family, 0, 0);
            market.put(family, s);
            setDirty();
        }
        return s;
    }

    public Collection<MarketState> marketStates() {
        return Collections.unmodifiableCollection(market.values());
    }

    // ---- overrides and applied prices ----

    public PriceOverride override(String family) {
        return overrides.get(family);
    }

    public Map<String, PriceOverride> overrides() {
        return Collections.unmodifiableMap(overrides);
    }

    public void putOverride(PriceOverride override) {
        overrides.put(override.family(), override);
        setDirty();
    }

    public PriceOverride removeOverride(String family) {
        PriceOverride removed = overrides.remove(family);
        if (removed != null) {
            setDirty();
        }
        return removed;
    }

    public AppliedPrice applied(String family) {
        return applied.get(family);
    }

    public Map<String, AppliedPrice> appliedPrices() {
        return Collections.unmodifiableMap(applied);
    }

    public void putApplied(AppliedPrice price) {
        applied.put(price.family(), price);
        setDirty();
    }

    public void removeApplied(String family) {
        if (applied.remove(family) != null) {
            setDirty();
        }
    }

    // ---- supply ----

    /** The entry of the given UTC day, created with the current supply snapshot when absent. */
    public SupplyDay supplyDay(String date) {
        SupplyDay day = supply.get(date);
        if (day == null) {
            day = new SupplyDay(date);
            long total = 0;
            for (Account a : accounts.values()) {
                total += a.balance;
            }
            day.openTotalCents = total;
            day.openAccounts = accounts.size();
            supply.put(date, day);
            pruneSupply();
            setDirty();
        }
        return day;
    }

    public SupplyDay supplyDayIfPresent(String date) {
        return supply.get(date);
    }

    public Collection<SupplyDay> supplyDays() {
        return Collections.unmodifiableCollection(supply.values());
    }

    private void pruneSupply() {
        while (supply.size() > SUPPLY_DAYS_KEPT) {
            String oldest = null;
            for (String d : supply.keySet()) {
                if (oldest == null || d.compareTo(oldest) < 0) {
                    oldest = d;
                }
            }
            supply.remove(oldest);
        }
    }

    // ---- misc ----

    public long lastSeq() {
        return lastSeq;
    }

    public void setLastSeq(long seq) {
        if (seq > lastSeq) {
            lastSeq = seq;
            setDirty();
        }
    }

    public String lastWeeklyReport() {
        return lastWeeklyReport;
    }

    public void setLastWeeklyReport(String isoDate) {
        lastWeeklyReport = isoDate;
        setDirty();
    }

    public long totalSupply() {
        long total = 0;
        for (Account a : accounts.values()) {
            total += a.balance;
        }
        return total;
    }

    // ---- NBT ----

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", SCHEMA_VERSION);
        tag.putLong("last_seq", lastSeq);
        tag.putString("last_weekly_report", lastWeeklyReport);

        ListTag accountList = new ListTag();
        for (Account a : accounts.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("uuid", a.uuid);
            t.putString("name", a.name == null ? "<unknown>" : a.name);
            t.putLong("balance", a.balance);
            t.putLong("lifetime_delivered", a.lifetimeDelivered);
            t.putLong("rank_credit", a.rankCredit);
            t.putDouble("lifetime_units", a.lifetimeUnits);
            t.putLong("first_login", a.firstLogin);
            t.putLong("last_seen", a.lastSeen);
            t.putString("grant", a.grant.name());
            ListTag paid = new ListTag();
            for (Account.DailyPaid d : a.dailyPaid) {
                CompoundTag p = new CompoundTag();
                p.putString("family", d.family);
                p.putString("utc_day", d.utcDay);
                p.putDouble("units", d.units);
                paid.add(p);
            }
            t.put("daily_paid", paid);
            if (!a.dailyBought.isEmpty()) {
                ListTag bought = new ListTag();
                for (Account.DailyBought b : a.dailyBought) {
                    CompoundTag p = new CompoundTag();
                    p.putString("key", b.key);
                    p.putString("utc_day", b.utcDay);
                    p.putInt("count", b.count);
                    bought.add(p);
                }
                t.put("daily_bought", bought);
            }
            accountList.add(t);
        }
        tag.put("accounts", accountList);

        ListTag marketList = new ListTag();
        for (MarketState s : market.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("family", s.family);
            t.putDouble("saturation", s.saturation);
            t.putLong("updated_at", s.updatedAt);
            marketList.add(t);
        }
        tag.put("market", marketList);

        ListTag overrideList = new ListTag();
        for (PriceOverride o : overrides.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("family", o.family());
            t.putLong("base", o.baseCents());
            if (o.halfVolume() != null) {
                t.putDouble("half_volume", o.halfVolume());
            }
            if (o.floorRatio() != null) {
                t.putDouble("floor_ratio", o.floorRatio());
            }
            if (o.dailyCap() != null) {
                t.putDouble("daily_cap", o.dailyCap());
            }
            t.putString("by", o.by());
            t.putLong("at", o.at());
            t.putString("reason", o.reason());
            overrideList.add(t);
        }
        tag.put("overrides", overrideList);

        ListTag appliedList = new ListTag();
        for (AppliedPrice p : applied.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("family", p.family());
            t.putLong("base", p.baseCents());
            t.putDouble("half_volume", p.halfVolume());
            t.putDouble("floor_ratio", p.floorRatio());
            t.putDouble("daily_cap", p.dailyCap());
            appliedList.add(t);
        }
        tag.put("applied", appliedList);

        ListTag supplyList = new ListTag();
        for (SupplyDay d : supply.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("date", d.date);
            t.putLong("open_total_cents", d.openTotalCents);
            t.putInt("open_accounts", d.openAccounts);
            t.put("created", longMapTag(d.created));
            t.put("destroyed", longMapTag(d.destroyed));
            t.putInt("deliveries", d.deliveries);
            if (d.largestUuid != null) {
                t.putUUID("largest_uuid", d.largestUuid);
                t.putLong("largest_cents", d.largestCents);
                t.putString("largest_tx", d.largestTx == null ? "" : d.largestTx);
            }
            ListTag families = new ListTag();
            for (Map.Entry<String, SupplyDay.FamilyStat> e : d.families.entrySet()) {
                CompoundTag f = new CompoundTag();
                f.putString("family", e.getKey());
                f.putLong("credits", e.getValue().credits);
                f.putDouble("paid_units", e.getValue().paidUnits);
                f.putDouble("units", e.getValue().units);
                families.add(f);
            }
            t.put("families", families);
            ListTag deliverers = new ListTag();
            for (UUID u : d.deliverers) {
                deliverers.add(StringTag.valueOf(u.toString()));
            }
            t.put("deliverers", deliverers);
            supplyList.add(t);
        }
        tag.put("supply", supplyList);
        return tag;
    }

    private static CompoundTag longMapTag(Map<String, Long> map) {
        CompoundTag t = new CompoundTag();
        for (Map.Entry<String, Long> e : map.entrySet()) {
            t.putLong(e.getKey(), e.getValue());
        }
        return t;
    }

    public static EconomyData load(CompoundTag tag, HolderLookup.Provider registries) {
        EconomyData data = new EconomyData();
        int version = tag.getInt("version");
        // One-shot migration switch: add a case per schema step when the layout changes.
        switch (version) {
            case 0, SCHEMA_VERSION -> { }
            default -> ConsortiumCore.LOGGER.warn("consortium_economy.dat has schema version {} (this build knows {}); loading as is", version, SCHEMA_VERSION);
        }
        data.lastSeq = tag.getLong("last_seq");
        data.lastWeeklyReport = tag.getString("last_weekly_report");

        for (Tag raw : tag.getList("accounts", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            if (!t.hasUUID("uuid")) {
                continue;
            }
            Account a = new Account(t.getUUID("uuid"), t.getString("name"));
            a.balance = t.getLong("balance");
            a.lifetimeDelivered = t.getLong("lifetime_delivered");
            a.rankCredit = t.getLong("rank_credit");
            a.lifetimeUnits = t.getDouble("lifetime_units");
            a.firstLogin = t.getLong("first_login");
            a.lastSeen = t.getLong("last_seen");
            try {
                a.grant = Account.Grant.valueOf(t.getString("grant"));
            } catch (IllegalArgumentException e) {
                a.grant = Account.Grant.NONE;
            }
            for (Tag rawPaid : t.getList("daily_paid", Tag.TAG_COMPOUND)) {
                CompoundTag p = (CompoundTag) rawPaid;
                a.dailyPaid.add(new Account.DailyPaid(p.getString("family"), p.getString("utc_day"), p.getDouble("units")));
            }
            // Absent in v0.1 saves: an empty list, no schema bump (v0.2, 5.3).
            for (Tag rawBought : t.getList("daily_bought", Tag.TAG_COMPOUND)) {
                CompoundTag p = (CompoundTag) rawBought;
                a.dailyBought.add(new Account.DailyBought(p.getString("key"), p.getString("utc_day"), p.getInt("count")));
            }
            data.accounts.put(a.uuid, a);
        }

        for (Tag raw : tag.getList("market", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            String family = t.getString("family");
            data.market.put(family, new MarketState(family, t.getDouble("saturation"), t.getLong("updated_at")));
        }

        for (Tag raw : tag.getList("overrides", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            String family = t.getString("family");
            data.overrides.put(family, new PriceOverride(family, t.getLong("base"),
                    t.contains("half_volume") ? t.getDouble("half_volume") : null,
                    t.contains("floor_ratio") ? t.getDouble("floor_ratio") : null,
                    t.contains("daily_cap") ? t.getDouble("daily_cap") : null,
                    t.getString("by"), t.getLong("at"), t.getString("reason")));
        }

        for (Tag raw : tag.getList("applied", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            String family = t.getString("family");
            data.applied.put(family, new AppliedPrice(family, t.getLong("base"), t.getDouble("half_volume"),
                    t.getDouble("floor_ratio"), t.getDouble("daily_cap")));
        }

        if (tag.contains("phase", Tag.TAG_COMPOUND)) {
            // Phase counters of a build before the KubeJS engine took them over: dropped, the engine owns phases now.
            ConsortiumCore.LOGGER.info("consortium_economy.dat carries archived phase counters from an earlier build; ignored");
        }

        for (Tag raw : tag.getList("supply", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            SupplyDay d = new SupplyDay(t.getString("date"));
            d.openTotalCents = t.getLong("open_total_cents");
            d.openAccounts = t.getInt("open_accounts");
            CompoundTag created = t.getCompound("created");
            for (String k : created.getAllKeys()) {
                d.created.put(k, created.getLong(k));
            }
            CompoundTag destroyed = t.getCompound("destroyed");
            for (String k : destroyed.getAllKeys()) {
                d.destroyed.put(k, destroyed.getLong(k));
            }
            d.deliveries = t.getInt("deliveries");
            if (t.hasUUID("largest_uuid")) {
                d.largestUuid = t.getUUID("largest_uuid");
                d.largestCents = t.getLong("largest_cents");
                d.largestTx = t.getString("largest_tx");
            }
            for (Tag rawFamily : t.getList("families", Tag.TAG_COMPOUND)) {
                CompoundTag f = (CompoundTag) rawFamily;
                SupplyDay.FamilyStat stat = d.family(f.getString("family"));
                stat.credits = f.getLong("credits");
                stat.paidUnits = f.getDouble("paid_units");
                stat.units = f.getDouble("units");
            }
            for (Tag rawUuid : t.getList("deliverers", Tag.TAG_STRING)) {
                try {
                    d.deliverers.add(UUID.fromString(rawUuid.getAsString()));
                } catch (IllegalArgumentException ignored) {
                    // a corrupt entry is dropped, the report tolerates it
                }
            }
            data.supply.put(d.date, d);
        }
        return data;
    }

    /** Sorted supply days, oldest first (helper for the reports). */
    public List<SupplyDay> supplyDaysSorted() {
        List<SupplyDay> days = new ArrayList<>(supply.values());
        days.sort((a, b) -> a.date.compareTo(b.date));
        return days;
    }
}
