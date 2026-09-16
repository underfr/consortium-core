package org.consortium.core.monitoring;

import org.consortium.core.compat.SdlinkBridge;
import org.consortium.core.config.CommonConfig;
import org.consortium.core.economy.LedgerLine;
import org.consortium.core.economy.LedgerType;
import org.consortium.core.economy.Money;
import org.consortium.core.economy.Units;
import org.consortium.core.pricing.PriceFamily;
import org.consortium.core.pricing.PriceTable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Suspicious-transaction rules of specification section 7, evaluated after every ledger write. Alert only, never
 * blocking. Thresholds come from the common config and are placeholders until week 1 sets them at 2 x p99.
 */
public final class Alerts {
    private record Sample(long at, double value) {
    }

    private final Notifier notifier;
    private final PriceTable table;
    private final Map<String, Deque<Sample>> balanceJumps = new HashMap<>();
    private final Map<String, Deque<Sample>> floorDumps = new HashMap<>();

    public Alerts(Notifier notifier, PriceTable table) {
        this.notifier = notifier;
        this.table = table;
    }

    /** Evaluates the four rules over the lines just written. */
    public void afterWrite(List<LedgerLine> lines, long now) {
        // Rule 1 and the delivery-related state: one tx at a time.
        Map<String, Long> totalsByTx = new LinkedHashMap<>();
        Map<String, LedgerLine> largestByTx = new LinkedHashMap<>();
        for (LedgerLine line : lines) {
            if (line.type() == LedgerType.DELIVERY && line.tx() != null) {
                totalsByTx.merge(line.tx(), line.total(), Long::sum);
                LedgerLine largest = largestByTx.get(line.tx());
                if (largest == null || line.total() > largest.total()) {
                    largestByTx.put(line.tx(), line);
                }
                floorDump(line, now);
            }
            if (line.type().movesMoney() && line.total() > 0 && line.player() != null) {
                balanceJump(line, now);
            }
            if (line.type().isAdministrative()) {
                administrative(line);
            }
        }
        long threshold = Money.floorToCents(CommonConfig.singleDeliveryCredits());
        for (Map.Entry<String, Long> e : totalsByTx.entrySet()) {
            if (threshold > 0 && e.getValue() > threshold) {
                LedgerLine l = largestByTx.get(e.getKey());
                notifier.alertOps("ALERT delivery: " + l.name() + " delivered " + describeItems(l) + " for "
                        + Money.format(e.getValue()) + " (threshold " + Money.format(threshold) + ") at "
                        + l.field("terminal") + ", tx " + e.getKey());
            }
        }
    }

    private void balanceJump(LedgerLine line, long now) {
        long window = CommonConfig.balanceJumpWindowMinutes() * 60_000L;
        long threshold = Money.floorToCents(CommonConfig.balanceJumpCredits());
        if (threshold <= 0) {
            return;
        }
        Deque<Sample> ring = balanceJumps.computeIfAbsent(line.player(), k -> new ArrayDeque<>());
        ring.addLast(new Sample(now, line.total()));
        while (!ring.isEmpty() && now - ring.peekFirst().at() > window) {
            ring.pollFirst();
        }
        double sum = 0;
        for (Sample s : ring) {
            sum += s.value();
        }
        if (sum > threshold) {
            notifier.alertOps("ALERT balance jump: " + line.name() + " gained " + Money.format((long) sum) + " within "
                    + CommonConfig.balanceJumpWindowMinutes() + " minutes (threshold " + Money.format(threshold) + "), last " + line.type());
            ring.clear();
        }
    }

    private void floorDump(LedgerLine line, long now) {
        String family = line.family();
        if (family == null || line.player() == null) {
            return;
        }
        PriceFamily f = table.family(family);
        if (f == null || f.quotaOnly()) {
            return;
        }
        double paid = line.field("paid_units") instanceof Number n ? n.doubleValue() : 0;
        if (paid <= 0) {
            return;
        }
        double limit = CommonConfig.floorDumpMultiplier() * f.halfVolume();
        Deque<Sample> ring = floorDumps.computeIfAbsent(line.player() + "|" + family, k -> new ArrayDeque<>());
        ring.addLast(new Sample(now, paid));
        while (!ring.isEmpty() && now - ring.peekFirst().at() > 3_600_000L) {
            ring.pollFirst();
        }
        double sum = 0;
        for (Sample s : ring) {
            sum += s.value();
        }
        if (limit > 0 && sum > limit) {
            notifier.alertOps("ALERT floor dump: " + line.name() + " sold " + Units.format(sum) + " paid units of "
                    + table.displayName(family) + " within 60 minutes (limit " + Units.format(limit) + ")");
            ring.clear();
        }
    }

    private void administrative(LedgerLine line) {
        // Ops in game already saw the command broadcast; the Discord custom destination and the console keep the trail.
        String who = line.field("by") != null ? String.valueOf(line.field("by")) : String.valueOf(line.field("counterpart"));
        String text = "ADMIN " + line.type() + (line.family() != null ? " " + line.family() : "")
                + (line.name() != null ? " " + line.name() : "")
                + (line.total() != 0 ? " " + Money.formatSigned(line.total()) : "")
                + " by " + who + (line.field("reason") != null ? ": " + line.field("reason") : "");
        SdlinkBridge.sendCustom(Notifier.PREFIX + text);
    }

    @SuppressWarnings("unchecked")
    private static String describeItems(LedgerLine line) {
        Object items = line.field("items");
        if (items instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            Map<String, Object> m = (Map<String, Object>) first;
            String more = list.size() > 1 ? " and " + (list.size() - 1) + " more" : "";
            return m.get("count") + " x " + m.get("id") + more;
        }
        return Units.format(line.field("units") instanceof Number n ? n.doubleValue() : 0) + " units of " + line.family();
    }
}
