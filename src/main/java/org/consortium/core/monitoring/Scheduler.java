package org.consortium.core.monitoring;

import org.consortium.core.ConsortiumCore;
import org.consortium.core.ConsortiumRuntime;
import org.consortium.core.compat.SdlinkBridge;
import org.consortium.core.config.CommonConfig;
import org.consortium.core.economy.Account;
import org.consortium.core.pricing.PriceFamily;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * The minute tick (specification 7): relays buffered boot messages once SDLink's bot may be ready, prunes stale
 * daily-cap entries, and posts the weekly report at the configured local day and hour (once per calendar week,
 * remembered in the saved data so a restart never double-posts).
 */
public final class Scheduler {
    private static final int TICKS_PER_MINUTE = 1200;

    private final ConsortiumRuntime rt;
    private int ticks;

    public Scheduler(ConsortiumRuntime rt) {
        this.rt = rt;
    }

    public void tick() {
        ticks++;
        if (ticks % TICKS_PER_MINUTE != 0) {
            return;
        }
        try {
            rt.notifier.flushPendingRelay();
            weeklyReport();
        } catch (Throwable t) {
            ConsortiumCore.LOGGER.error("Scheduler minute tick failed", t);
        }
    }

    private void weeklyReport() {
        LocalDateTime local = LocalDateTime.now(ZoneId.systemDefault());
        if (local.getDayOfWeek() != CommonConfig.weeklyReportDay() || local.getHour() != CommonConfig.weeklyReportHour()) {
            return;
        }
        String stamp = local.toLocalDate().toString();
        if (stamp.equals(rt.economy.lastWeeklyReport())) {
            return;
        }
        rt.economy.setLastWeeklyReport(stamp);
        String text = report(rt, 7);
        rt.notifier.informOps("Weekly money-supply report posted (" + stamp + ")");
        ConsortiumCore.LOGGER.info("Weekly report:\n{}", text);
        if (!SdlinkBridge.sendCustom(text)) {
            for (String line : text.split("\n")) {
                rt.notifier.informOps(line);
            }
        }
        String[] lines = text.split("\n");
        if (lines.length > 1) {
            rt.notifier.announce("Monday summary: " + lines[1]);
        }
    }

    /** Builds the weekly (7) or daily (1) report from the in-memory aggregates. */
    public static String report(ConsortiumRuntime rt, int windowDays) {
        long now = rt.now();
        List<String> atFloor = new ArrayList<>();
        for (PriceFamily f : rt.prices.families()) {
            if (!f.quotaOnly() && rt.market.atFloor(f.key(), now)) {
                atFloor.add(f.key());
            }
        }
        List<Account> accounts = new ArrayList<>(rt.economy.accounts());
        LocalDate today = LocalDate.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC);
        return Reports.build(rt.economy.supplyDays(), accounts, atFloor, rt.prices::displayName, today, now, windowDays);
    }
}
