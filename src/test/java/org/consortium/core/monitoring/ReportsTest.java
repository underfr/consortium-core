package org.consortium.core.monitoring;

import org.consortium.core.economy.Account;
import org.consortium.core.economy.SupplyDay;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportsTest {
    private static final UUID ALEX = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CY = UUID.randomUUID();

    @Test
    void weeklyReportAggregatesTheWindowAndFlagsADoubling() {
        long now = 1_800_000_000_000L;
        LocalDate today = LocalDate.of(2026, 9, 21);
        SupplyDay weekAgo = new SupplyDay("2026-09-14");
        weekAgo.openTotalCents = 100_000;
        weekAgo.openAccounts = 3;
        weekAgo.addCreated("DELIVERY", 50_000);
        weekAgo.deliveries = 4;
        weekAgo.deliverers.add(ALEX);
        weekAgo.family("minecraft:iron_ingot").credits = 50_000;
        weekAgo.family("minecraft:iron_ingot").units = 400;
        SupplyDay yesterday = new SupplyDay("2026-09-20");
        yesterday.openTotalCents = 180_000;
        yesterday.addCreated("DELIVERY", 120_000);
        yesterday.addCreated("STARTING_CAPITAL", 5_000);
        yesterday.addDestroyed("API_DEBIT", 20_000);
        yesterday.deliveries = 10;
        yesterday.deliverers.add(ALEX);
        yesterday.deliverers.add(BOB);
        yesterday.largestUuid = BOB;
        yesterday.largestCents = 70_000;
        yesterday.largestTx = "a3f1beef";
        yesterday.family("minecraft:iron_ingot").credits = 20_000;
        yesterday.family("minecraft:iron_ingot").units = 100;
        yesterday.family("c:ingots/steel").credits = 100_000;
        yesterday.family("c:ingots/steel").units = 900;
        SupplyDay old = new SupplyDay("2026-08-01");
        old.addCreated("DELIVERY", 999_999_999);

        Account alex = new Account(ALEX, "Alex");
        alex.balance = 150_000;
        alex.lastSeen = now;
        Account bob = new Account(BOB, "Bob");
        bob.balance = 60_000;
        bob.lastSeen = now;
        Account cy = new Account(CY, "Cy");
        cy.balance = 10_000;
        cy.lastSeen = now - 30L * 24 * 3600 * 1000;

        String report = Reports.build(List.of(yesterday, weekAgo, old), List.of(alex, bob, cy), List.of("c:ingots/steel"),
                k -> k.equals("c:ingots/steel") ? "Steel" : "Iron", today, now, 7);
        assertTrue(report.startsWith("Weekly report 2026-09-21 (UTC)"), report);
        assertTrue(report.contains("Supply: 2,200.00 CC now, 1,000.00 CC at 2026-09-14 (x2.20)"), report);
        assertTrue(report.contains("ALERT: the money supply grew 2.2x in a week"), report);
        assertTrue(report.contains("Created (7 d): 1,250.00 CC = delivery 1,200.00, starting capital 50.00"), report);
        assertTrue(report.contains("Destroyed (7 d): 200.00 CC = api debit 200.00"), report);
        assertTrue(report.contains("Balances (2 seen in 14 d): median 1,050.00 CC, mean 1,050.00 CC, top 3: Alex 1,500.00, Bob 600.00"), report);
        assertTrue(report.contains("Top families by credits: Steel 1,000.00, Iron 200.00"), report);
        assertTrue(report.contains("Top families by units: Steel 900, Iron 100"), report);
        assertTrue(report.contains("At floor now: Steel"), report);
        assertTrue(report.contains("Active: 2 player(s) delivered, 10 deliveries, largest 700.00 CC by Bob (tx a3f1beef)"), report);
        assertFalse(report.contains("400"), "the reference entry 7 days back is the opening snapshot, not part of the 7-day window");
        assertFalse(report.contains("999"), "entries outside the window are ignored");
        assertTrue(report.length() < 2000);
    }

    @Test
    void dailyReportWithoutReferenceSaysSo() {
        SupplyDay only = new SupplyDay("2026-09-21");
        only.addCreated("DELIVERY", 100);
        String report = Reports.build(List.of(only), List.of(), List.of(), k -> k, LocalDate.of(2026, 9, 21), 0, 1);
        assertTrue(report.startsWith("Daily report"), report);
        assertTrue(report.contains("no reference entry 1 day(s) back yet"), report);
        assertTrue(report.contains("Balances: no account seen in 14 days"), report);
        assertFalse(report.contains("ALERT"), report);
    }
}
