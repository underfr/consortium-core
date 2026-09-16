package org.consortium.core.economy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One player account of the ledger (specification 2.1, {@code accounts[]}). Plain data: every mutation happens
 * through {@link EconomyData} so the saved data is marked dirty.
 */
public final class Account {
    /** Starting-capital state. */
    public enum Grant {
        /** Account created before the first login (admin add, API credit): checked at the first login. */
        NONE,
        GRANTED,
        /** Refused by the same-connection check; {@code /credits grant-start} overrides. */
        DENIED
    }

    /** Paid units of one family on one UTC day, for the per-player daily cap. */
    public static final class DailyPaid {
        public final String family;
        public final String utcDay;
        public double units;

        public DailyPaid(String family, String utcDay, double units) {
            this.family = family;
            this.utcDay = utcDay;
            this.units = units;
        }
    }

    public final UUID uuid;
    /** Refreshed at every login; {@code <unknown>} for an account created by the API before any login. */
    public String name;
    public long balance;
    public long lifetimeDelivered;
    /** Rank and leaderboard metric: credits earned (deliveries today, the Gap Contract at 50 percent later). */
    public long rankCredit;
    public double lifetimeUnits;
    /** Epoch millis of the first login, 0 until then. */
    public long firstLogin;
    public long lastSeen;
    public Grant grant = Grant.NONE;
    public final List<DailyPaid> dailyPaid = new ArrayList<>();

    public Account(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    /** Paid units of a family on the given UTC day. */
    public double paidToday(String family, String utcDay) {
        for (DailyPaid d : dailyPaid) {
            if (d.family.equals(family) && d.utcDay.equals(utcDay)) {
                return d.units;
            }
        }
        return 0;
    }

    /** Adds paid units for a family on the given UTC day. */
    public void addPaidToday(String family, String utcDay, double units) {
        for (DailyPaid d : dailyPaid) {
            if (d.family.equals(family) && d.utcDay.equals(utcDay)) {
                d.units = Units.round(d.units + units);
                return;
            }
        }
        dailyPaid.add(new DailyPaid(family, utcDay, Units.round(units)));
    }

    /** Drops the entries of past days. */
    public void pruneDailyPaid(String utcDay) {
        dailyPaid.removeIf(d -> !d.utcDay.equals(utcDay));
    }
}
