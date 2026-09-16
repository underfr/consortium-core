package org.consortium.core.economy;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Aggregates of one UTC day, maintained at ledger-write time (specification 2.1, {@code supply[]}). The weekly and
 * daily reports read only these entries, never a ledger file.
 */
public final class SupplyDay {
    /** Credits paid, paid units and total units of one family on that day. */
    public static final class FamilyStat {
        public long credits;
        public double paidUnits;
        public double units;
    }

    public final String date;
    /** Total balance of every account when the entry was created. */
    public long openTotalCents;
    public int openAccounts;
    /** Cents created by ledger type name (DELIVERY, STARTING_CAPITAL, ADMIN_ADD, ADMIN_SET, API_CREDIT). */
    public final Map<String, Long> created = new LinkedHashMap<>();
    /** Cents destroyed by ledger type name (API_DEBIT, ADMIN_TAKE, ADMIN_SET). */
    public final Map<String, Long> destroyed = new LinkedHashMap<>();
    public int deliveries;
    public UUID largestUuid;
    public long largestCents;
    public String largestTx;
    public final Map<String, FamilyStat> families = new LinkedHashMap<>();
    public final Set<UUID> deliverers = new LinkedHashSet<>();

    public SupplyDay(String date) {
        this.date = date;
    }

    public void addCreated(String type, long cents) {
        created.merge(type, cents, Long::sum);
    }

    public void addDestroyed(String type, long cents) {
        destroyed.merge(type, cents, Long::sum);
    }

    public FamilyStat family(String key) {
        return families.computeIfAbsent(key, k -> new FamilyStat());
    }

    public long totalCreated() {
        long sum = 0;
        for (long v : created.values()) {
            sum += v;
        }
        return sum;
    }

    public long totalDestroyed() {
        long sum = 0;
        for (long v : destroyed.values()) {
            sum += v;
        }
        return sum;
    }
}
