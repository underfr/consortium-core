package org.consortium.core.monitoring;

import org.consortium.core.economy.Account;
import org.consortium.core.economy.Money;
import org.consortium.core.economy.SupplyDay;
import org.consortium.core.economy.Units;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The weekly and daily money-supply reports of specification section 7, computed from the in-memory aggregates only
 * (never from a ledger file). Pure Java so the unit tests cover it.
 */
public final class Reports {
    /** Ratio at which the weekly report carries the ALERT line (rule 4.2: supply doubling in a week). */
    public static final double SUPPLY_ALERT_RATIO = 2.0;

    private Reports() {
    }

    /**
     * @param days        supply entries, any order
     * @param accounts    every account
     * @param atFloor     family keys currently on their floor
     * @param displayName family key to display name
     * @param today       the UTC date of the report
     * @param now         epoch millis (for "seen in 14 days")
     * @param windowDays  7 for the weekly report, 1 for the daily one
     */
    public static String build(Collection<SupplyDay> days, Collection<Account> accounts, Collection<String> atFloor,
                               Function<String, String> displayName, LocalDate today, long now, int windowDays) {
        List<SupplyDay> sorted = new ArrayList<>(days);
        sorted.sort(Comparator.comparing(d -> d.date));
        LocalDate from = today.minusDays(windowDays - 1L);
        List<SupplyDay> window = new ArrayList<>();
        for (SupplyDay d : sorted) {
            LocalDate date = parse(d.date);
            if (date != null && !date.isBefore(from) && !date.isAfter(today)) {
                window.add(d);
            }
        }

        long supplyNow = 0;
        for (Account a : accounts) {
            supplyNow += a.balance;
        }
        SupplyDay reference = entryAtOrAfter(sorted, today.minusDays(windowDays), today);
        StringBuilder sb = new StringBuilder();
        sb.append(windowDays == 1 ? "Daily report " : "Weekly report ").append(today).append(" (UTC)\n");
        sb.append("Supply: ").append(Money.format(supplyNow)).append(" now");
        if (reference != null) {
            sb.append(", ").append(Money.format(reference.openTotalCents)).append(" at ").append(reference.date);
            if (reference.openTotalCents > 0) {
                double ratio = supplyNow / (double) reference.openTotalCents;
                sb.append(String.format(java.util.Locale.ROOT, " (x%.2f)", ratio));
                if (windowDays >= 7 && ratio >= SUPPLY_ALERT_RATIO) {
                    sb.append("\nALERT: the money supply grew ").append(String.format(java.util.Locale.ROOT, "%.1f", ratio))
                            .append("x in a week: exploit or mis-priced item (rule 4.2)");
                }
            }
        } else {
            sb.append(", no reference entry ").append(windowDays).append(" day(s) back yet");
        }
        sb.append('\n');

        Map<String, Long> created = new LinkedHashMap<>();
        Map<String, Long> destroyed = new LinkedHashMap<>();
        Map<String, long[]> familyCredits = new LinkedHashMap<>();
        Map<String, double[]> familyUnits = new LinkedHashMap<>();
        Set<UUID> deliverers = new LinkedHashSet<>();
        int deliveries = 0;
        SupplyDay largestDay = null;
        for (SupplyDay d : window) {
            d.created.forEach((k, v) -> created.merge(k, v, Long::sum));
            d.destroyed.forEach((k, v) -> destroyed.merge(k, v, Long::sum));
            for (Map.Entry<String, SupplyDay.FamilyStat> e : d.families.entrySet()) {
                familyCredits.computeIfAbsent(e.getKey(), k -> new long[1])[0] += e.getValue().credits;
                familyUnits.computeIfAbsent(e.getKey(), k -> new double[1])[0] += e.getValue().units;
            }
            deliverers.addAll(d.deliverers);
            deliveries += d.deliveries;
            if (d.largestUuid != null && (largestDay == null || d.largestCents > largestDay.largestCents)) {
                largestDay = d;
            }
        }
        sb.append("Created (").append(windowDays).append(" d): ").append(Money.format(sum(created))).append(typeBreakdown(created)).append('\n');
        sb.append("Destroyed (").append(windowDays).append(" d): ").append(Money.format(sum(destroyed))).append(typeBreakdown(destroyed)).append('\n');

        List<Account> seen = new ArrayList<>();
        for (Account a : accounts) {
            if (now - a.lastSeen <= 14L * 24 * 3600 * 1000) {
                seen.add(a);
            }
        }
        if (seen.isEmpty()) {
            sb.append("Balances: no account seen in 14 days\n");
        } else {
            List<Long> balances = new ArrayList<>();
            long total = 0;
            for (Account a : seen) {
                balances.add(a.balance);
                total += a.balance;
            }
            balances.sort(null);
            long median = balances.size() % 2 == 1 ? balances.get(balances.size() / 2)
                    : (balances.get(balances.size() / 2 - 1) + balances.get(balances.size() / 2)) / 2;
            sb.append("Balances (").append(seen.size()).append(" seen in 14 d): median ").append(Money.format(median))
                    .append(", mean ").append(Money.format(total / seen.size()));
            seen.sort((a, b) -> Long.compare(b.balance, a.balance));
            sb.append(", top 3:");
            for (int i = 0; i < Math.min(3, seen.size()); i++) {
                sb.append(i == 0 ? " " : ", ").append(seen.get(i).name).append(' ').append(Money.formatPlain(seen.get(i).balance));
            }
            sb.append('\n');
        }

        sb.append("Top families by credits:").append(topFamilies(familyCredits, displayName, true)).append('\n');
        sb.append("Top families by units:").append(topUnits(familyUnits, displayName)).append('\n');
        sb.append("At floor now: ");
        if (atFloor.isEmpty()) {
            sb.append("none");
        } else {
            boolean first = true;
            for (String f : atFloor) {
                sb.append(first ? "" : ", ").append(displayName.apply(f));
                first = false;
            }
        }
        sb.append('\n');
        sb.append("Active: ").append(deliverers.size()).append(" player(s) delivered, ").append(deliveries).append(" deliveries");
        if (largestDay != null) {
            String who = null;
            for (Account a : accounts) {
                if (a.uuid.equals(largestDay.largestUuid)) {
                    who = a.name;
                    break;
                }
            }
            sb.append(", largest ").append(Money.format(largestDay.largestCents)).append(" by ").append(who != null ? who : largestDay.largestUuid)
                    .append(" (tx ").append(largestDay.largestTx).append(')');
        }
        String text = sb.toString();
        return text.length() > 1990 ? text.substring(0, 1985) + "\n..." : text;
    }

    private static String typeBreakdown(Map<String, Long> byType) {
        if (byType.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" =");
        boolean first = true;
        for (Map.Entry<String, Long> e : byType.entrySet()) {
            sb.append(first ? " " : ", ").append(e.getKey().toLowerCase(java.util.Locale.ROOT).replace('_', ' '))
                    .append(' ').append(Money.formatPlain(e.getValue()));
            first = false;
        }
        return sb.toString();
    }

    private static String topFamilies(Map<String, long[]> byFamily, Function<String, String> displayName, boolean credits) {
        if (byFamily.isEmpty()) {
            return " none";
        }
        List<Map.Entry<String, long[]>> entries = new ArrayList<>(byFamily.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(5, entries.size()); i++) {
            if (entries.get(i).getValue()[0] <= 0) {
                break;
            }
            sb.append(i == 0 ? " " : ", ").append(displayName.apply(entries.get(i).getKey())).append(' ')
                    .append(Money.formatPlain(entries.get(i).getValue()[0]));
        }
        return sb.isEmpty() ? " none" : sb.toString();
    }

    private static String topUnits(Map<String, double[]> byFamily, Function<String, String> displayName) {
        if (byFamily.isEmpty()) {
            return " none";
        }
        List<Map.Entry<String, double[]>> entries = new ArrayList<>(byFamily.entrySet());
        entries.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(5, entries.size()); i++) {
            sb.append(i == 0 ? " " : ", ").append(displayName.apply(entries.get(i).getKey())).append(' ')
                    .append(Units.format(entries.get(i).getValue()[0]));
        }
        return sb.toString();
    }

    private static long sum(Map<String, Long> m) {
        long s = 0;
        for (long v : m.values()) {
            s += v;
        }
        return s;
    }

    /** The earliest entry dated in {@code [date, before)}: the opening snapshot closest to the window start. */
    private static SupplyDay entryAtOrAfter(List<SupplyDay> sorted, LocalDate date, LocalDate before) {
        for (SupplyDay d : sorted) {
            LocalDate parsed = parse(d.date);
            if (parsed != null && !parsed.isBefore(date) && parsed.isBefore(before)) {
                return d;
            }
        }
        return null;
    }

    private static LocalDate parse(String date) {
        try {
            return LocalDate.parse(date);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
