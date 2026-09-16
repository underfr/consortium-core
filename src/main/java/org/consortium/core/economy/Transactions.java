package org.consortium.core.economy;

import net.neoforged.neoforge.common.NeoForge;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.ConsortiumRuntime;
import org.consortium.core.api.Result;
import org.consortium.core.api.event.BalanceChangeEvent;
import org.consortium.core.pricing.Market;
import org.consortium.core.pricing.PriceChange;
import org.consortium.core.pricing.PriceFamily;
import org.consortium.core.terminal.ContributeHook;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Every money movement of the mod, in the write-ahead order of specification 2.2: build the ledger lines, write and
 * flush them, only then mutate memory (balances, market, supply aggregates), then follow-ups (alerts, HUD sync,
 * events, the contribute follow-up command) that can never roll money back. Server thread only.
 */
public final class Transactions {
    /** The outcome of a money movement: the result code and the balance after it (when it succeeded). */
    public record Outcome(Result result, long balanceAfter, long delta) {
        public static Outcome fail(Result result) {
            return new Outcome(result, 0, 0);
        }

        public boolean ok() {
            return result == Result.SUCCESS;
        }
    }

    /** An item stack of a delivery line as recorded in the ledger (counts of one item id are merged). */
    public record ItemCount(String id, int count) {
    }

    /** One family of a delivery, quoted and ready to commit. */
    public record DeliveryLine(String family, List<ItemCount> items, Market.Quote quote) {
    }

    /** What the terminal needs to commit a delivery. */
    public record DeliveryRequest(UUID player, String name, List<DeliveryLine> lines, String dimension, String pos) {
    }

    /** A committed delivery: the transaction id, the total paid and the balance after it. */
    public record DeliveryReceipt(String txId, long totalCents, long balanceAfter) {
    }

    /**
     * The outcome of {@link #delivery}: a receipt on {@code SUCCESS}; otherwise {@code LEDGER_UNAVAILABLE} (the
     * write failed, nothing changed) or {@code INVALID_AMOUNT} (nothing to commit, or the balance would pass
     * {@link Money#MAX_BALANCE_CENTS}, alerted to ops). The terminal words its refusal from the result.
     */
    public record DeliveryResult(Result result, DeliveryReceipt receipt) {
        public static DeliveryResult fail(Result result) {
            return new DeliveryResult(result, null);
        }

        public boolean ok() {
            return result == Result.SUCCESS && receipt != null;
        }
    }

    private final ConsortiumRuntime rt;
    private long lastLedgerAlert;

    public Transactions(ConsortiumRuntime rt) {
        this.rt = rt;
    }

    // ---- helpers ----

    public String utcDay(long now) {
        return utcDayOf(now);
    }

    /** {@code YYYY-MM-DD} of an epoch instant in UTC: the day boundary of the ledger files, the daily caps and the shop limits. */
    public static String utcDayOf(long now) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC).toString();
    }

    public static String newTxId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private boolean write(List<LedgerLine> lines) {
        if (!rt.ledgerReady()) {
            ledgerAlert("Ledger unavailable (boot failed): nothing changed");
            return false;
        }
        try {
            rt.ledger.write(lines);
        } catch (IOException e) {
            ledgerAlert("Ledger unavailable (" + e.getMessage() + "): nothing changed");
            return false;
        }
        long last = lines.get(lines.size() - 1).seq();
        rt.economy.setLastSeq(last);
        return true;
    }

    private void ledgerAlert(String text) {
        long now = rt.now();
        ConsortiumCore.LOGGER.error(text);
        if (now - lastLedgerAlert > 60_000) {
            lastLedgerAlert = now;
            rt.notifier.alertOps("ALERT " + text);
        }
    }

    private void followUps(List<LedgerLine> lines, long now) {
        try {
            rt.alerts.afterWrite(lines, now);
        } catch (Throwable t) {
            ConsortiumCore.LOGGER.error("Alert evaluation failed", t);
        }
    }

    private static long checkedAdd(long a, long b) {
        return Math.addExact(a, b);
    }

    // ---- balance changes (admin, API, starting capital) ----

    /**
     * Applies a balance change through {@link BalanceChangeEvent} (unless {@code postEvent} is false), the ledger and
     * the account. {@code newBalance} is the proposed balance after the change.
     */
    private Outcome changeBalance(Account account, long newBalance, LedgerType type, String reason, String counterpart,
                                  boolean postEvent, Map<String, Object> extras) {
        return changeBalance(account, newBalance, type, null, reason, counterpart, postEvent, extras, Long.MAX_VALUE);
    }

    /**
     * The full form: {@code tx} stamps the line (null for the untagged admin and API lines) and {@code ceiling} is
     * the highest balance a listener may leave after the event (v0.2, 5.3: a purchase lets a listener take more,
     * never less, so a value above {@code oldBalance - price} is a veto).
     */
    private Outcome changeBalance(Account account, long newBalance, LedgerType type, String tx, String reason, String counterpart,
                                  boolean postEvent, Map<String, Object> extras, long ceiling) {
        long now = rt.now();
        long oldBalance = account.balance;
        if (newBalance < 0 || newBalance > Money.MAX_BALANCE_CENTS) {
            return Outcome.fail(Result.INVALID_AMOUNT);
        }
        if (postEvent) {
            BalanceChangeEvent event = new BalanceChangeEvent(account.uuid, account.name, oldBalance, newBalance, reason, counterpart);
            try {
                NeoForge.EVENT_BUS.post(event);
                if (event.isCanceled()) {
                    return Outcome.fail(Result.VETOED);
                }
                newBalance = event.getNewBalance();
            } catch (Throwable t) {
                ConsortiumCore.LOGGER.warn("BalanceChangeEvent listener threw for {} ({}): treated as a veto", account.name, t.toString());
                return Outcome.fail(Result.VETOED);
            }
            if (newBalance < 0 || newBalance > Money.MAX_BALANCE_CENTS) {
                return Outcome.fail(Result.INVALID_AMOUNT);
            }
            if (newBalance > ceiling) {
                ConsortiumCore.LOGGER.warn("BalanceChangeEvent listener raised the balance of {} for {} ({}) from {} to {} cents, above the {} cents the change allows: treated as a veto",
                        account.name, type, reason, oldBalance, newBalance, ceiling);
                return Outcome.fail(Result.VETOED);
            }
        }
        long delta = newBalance - oldBalance;
        LedgerLine line = LedgerLine.of(type).tx(tx).player(account.uuid, account.name).counterpart(counterpart)
                .total(delta).balance(newBalance).reason(reason);
        if (extras != null) {
            extras.forEach(line::extra);
        }
        if (!write(List.of(line))) {
            return Outcome.fail(Result.LEDGER_UNAVAILABLE);
        }
        // The day entry is created (with its opening snapshot) before the balance moves, like delivery() does.
        SupplyDay day = rt.economy.supplyDay(utcDay(now));
        account.balance = newBalance;
        if (delta > 0) {
            day.addCreated(type.name(), delta);
        } else if (delta < 0) {
            day.addDestroyed(type.name(), -delta);
        }
        rt.economy.touch();
        followUps(List.of(line), now);
        rt.notifyBalance(account.uuid, newBalance, delta, reason);
        return new Outcome(Result.SUCCESS, newBalance, delta);
    }

    /** {@code /credits add}: creates the account when unknown. */
    public Outcome adminAdd(UUID uuid, String name, long cents, String reason, String counterpart) {
        if (cents <= 0) {
            return Outcome.fail(Result.INVALID_AMOUNT);
        }
        Account account = rt.economy.getOrCreate(uuid, name);
        long target;
        try {
            target = checkedAdd(account.balance, cents);
        } catch (ArithmeticException e) {
            rt.notifier.alertOps("ALERT overflow: admin add of " + cents + " cents to " + account.name + " refused");
            return Outcome.fail(Result.INVALID_AMOUNT);
        }
        return changeBalance(account, target, LedgerType.ADMIN_ADD, reason, counterpart, true, null);
    }

    /** {@code /credits take}: never overdrafts. */
    public Outcome adminTake(UUID uuid, long cents, String reason, String counterpart) {
        if (cents <= 0) {
            return Outcome.fail(Result.INVALID_AMOUNT);
        }
        Account account = rt.economy.account(uuid);
        if (account == null) {
            return Outcome.fail(Result.UNKNOWN_PLAYER);
        }
        if (account.balance < cents) {
            return new Outcome(Result.INSUFFICIENT_FUNDS, account.balance, 0);
        }
        return changeBalance(account, account.balance - cents, LedgerType.ADMIN_TAKE, reason, counterpart, true, null);
    }

    /** {@code /credits set}: bounded to {@code [0, 10^12 cents]}. */
    public Outcome adminSet(UUID uuid, String name, long cents, String reason, String counterpart) {
        if (cents < 0 || cents > Money.MAX_BALANCE_CENTS) {
            return Outcome.fail(Result.INVALID_AMOUNT);
        }
        Account account = rt.economy.getOrCreate(uuid, name);
        return changeBalance(account, cents, LedgerType.ADMIN_SET, reason, counterpart, true, null);
    }

    /** API credit: an unknown uuid gets an account ({@code grant NONE}, name {@code <unknown>}). */
    public Outcome apiCredit(UUID uuid, long cents, String reason) {
        if (cents <= 0) {
            return Outcome.fail(Result.INVALID_AMOUNT);
        }
        Account account = rt.economy.getOrCreate(uuid, null);
        long target;
        try {
            target = checkedAdd(account.balance, cents);
        } catch (ArithmeticException e) {
            rt.notifier.alertOps("ALERT overflow: API credit of " + cents + " cents to " + account.name + " refused");
            return Outcome.fail(Result.INVALID_AMOUNT);
        }
        return changeBalance(account, target, LedgerType.API_CREDIT, reason, "api", true, null);
    }

    /** API debit for a money sink; {@code sinkId} is the counterpart (e.g. {@code shop:chunkloader}). */
    public Outcome apiDebit(UUID uuid, long cents, String sinkId) {
        if (cents <= 0) {
            return Outcome.fail(Result.INVALID_AMOUNT);
        }
        Account account = rt.economy.account(uuid);
        if (account == null) {
            return Outcome.fail(Result.UNKNOWN_PLAYER);
        }
        if (account.balance < cents) {
            return new Outcome(Result.INSUFFICIENT_FUNDS, account.balance, 0);
        }
        return changeBalance(account, account.balance - cents, LedgerType.API_DEBIT, sinkId, sinkId, true, null);
    }

    /** A committed shop purchase: the transaction id, the price paid and the balance after it. */
    public record PurchaseReceipt(String txId, long cents, long balanceAfter) {
    }

    /** The outcome of {@link #purchase}: a receipt on {@code SUCCESS}, else the refusal code. */
    public record PurchaseResult(Result result, PurchaseReceipt receipt, long balance) {
        public static PurchaseResult fail(Result result, long balance) {
            return new PurchaseResult(result, null, balance);
        }

        public boolean ok() {
            return result == Result.SUCCESS && receipt != null;
        }
    }

    /**
     * A shop purchase (v0.2, 5.3, step 2): ledger {@code PURCHASE} with {@code reason} = the catalogue key,
     * {@code counterpart} = {@code shop:<key>}, the display name in the extra {@code entry_name} and the caller's
     * extras (item or command, terminal, by) after it. {@link BalanceChangeEvent} is posted; a listener may take
     * more, never less. Never overdrafts. The caller gives the item or runs the command only on {@code ok()}.
     */
    public PurchaseResult purchase(UUID uuid, String name, long cents, String key, String entryName, String tx, Map<String, Object> extras) {
        if (cents <= 0) {
            return PurchaseResult.fail(Result.INVALID_AMOUNT, 0);
        }
        Account account = rt.economy.getOrCreate(uuid, name);
        if (account.balance < cents) {
            return PurchaseResult.fail(Result.INSUFFICIENT_FUNDS, account.balance);
        }
        if (tx == null || tx.isBlank()) {
            tx = newTxId();
        }
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("entry_name", entryName);
        if (extras != null) {
            all.putAll(extras);
        }
        long target = account.balance - cents;
        Outcome outcome = changeBalance(account, target, LedgerType.PURCHASE, tx, key, "shop:" + key, true, all, target);
        if (!outcome.ok()) {
            return PurchaseResult.fail(outcome.result(), account.balance);
        }
        return new PurchaseResult(Result.SUCCESS, new PurchaseReceipt(tx, cents, outcome.balanceAfter()), outcome.balanceAfter());
    }

    /** Rank metric only: no balance change, ledger {@code RANK_CREDIT}. */
    public Outcome rankCredit(UUID uuid, long cents, String reason) {
        if (cents <= 0) {
            return Outcome.fail(Result.INVALID_AMOUNT);
        }
        Account account = rt.economy.getOrCreate(uuid, null);
        long target;
        try {
            target = Math.min(Money.MAX_BALANCE_CENTS, checkedAdd(account.rankCredit, cents));
        } catch (ArithmeticException e) {
            rt.notifier.alertOps("ALERT overflow: rank credit of " + cents + " cents to " + account.name + " refused");
            return Outcome.fail(Result.INVALID_AMOUNT);
        }
        LedgerLine line = LedgerLine.of(LedgerType.RANK_CREDIT).player(account.uuid, account.name).counterpart("api")
                .total(0).reason(reason).extra("rank_cents", cents).extra("rank_after", target);
        if (!write(List.of(line))) {
            return Outcome.fail(Result.LEDGER_UNAVAILABLE);
        }
        account.rankCredit = target;
        rt.economy.touch();
        followUps(List.of(line), rt.now());
        return new Outcome(Result.SUCCESS, account.balance, 0);
    }

    /**
     * Starting capital: posts {@link BalanceChangeEvent} (a veto opens the account at 0 with a 0 line, reason
     * {@code vetoed}) and marks the account {@code GRANTED}.
     */
    public Outcome grantStart(Account account, long cents, String reason, String counterpart, LedgerType type) {
        long target;
        try {
            target = checkedAdd(account.balance, Math.max(0, cents));
        } catch (ArithmeticException e) {
            return Outcome.fail(Result.INVALID_AMOUNT);
        }
        Outcome outcome = changeBalance(account, target, type, reason, counterpart, true, null);
        if (outcome.result() == Result.VETOED) {
            Outcome zero = changeBalance(account, account.balance, type, "vetoed", counterpart, false, null);
            if (!zero.ok()) {
                return zero;
            }
            account.grant = Account.Grant.GRANTED;
            rt.economy.touch();
            return new Outcome(Result.VETOED, account.balance, 0);
        }
        if (outcome.ok()) {
            account.grant = Account.Grant.GRANTED;
            rt.economy.touch();
        }
        return outcome;
    }

    /** Same-connection refusal: the account opens at 0 with {@code grant DENIED} and a {@code START_GRANT_DENIED} line. */
    public Outcome denyStart(Account account, String reason) {
        LedgerLine line = LedgerLine.of(LedgerType.START_GRANT_DENIED).player(account.uuid, account.name)
                .counterpart("system").total(0).balance(account.balance).reason(reason);
        if (!write(List.of(line))) {
            return Outcome.fail(Result.LEDGER_UNAVAILABLE);
        }
        account.grant = Account.Grant.DENIED;
        rt.economy.touch();
        followUps(List.of(line), rt.now());
        return new Outcome(Result.SUCCESS, account.balance, 0);
    }

    // ---- market ----

    /** {@code /prices reset}: saturation to 0, announced. */
    public Result marketReset(String family, String reason, String by) {
        long now = rt.now();
        PriceFamily f = rt.prices.family(family);
        if (f == null) {
            return Result.UNKNOWN_PLAYER;
        }
        double before = rt.market.saturation(family, now);
        LedgerLine line = LedgerLine.of(LedgerType.MARKET_RESET).counterpart(by).total(0).reason(reason)
                .extra("by", by).extra("family", family).extra("saturation_before", Units.round(before));
        if (!write(List.of(line))) {
            return Result.LEDGER_UNAVAILABLE;
        }
        rt.market.reset(family, now);
        followUps(List.of(line), now);
        rt.notifier.announce("Market reset: " + rt.prices.displayName(family) + " is back at " + Money.format(f.baseCents()) + ": " + reason);
        return Result.SUCCESS;
    }

    /** Logs and announces the changes a table rebuild produced (datapack reload, {@code /prices set|clear}). */
    public void priceChanges(List<PriceChange> changes, boolean boot) {
        List<LedgerLine> lines = new ArrayList<>();
        List<String> announcements = new ArrayList<>();
        for (PriceChange c : changes) {
            LedgerLine line = LedgerLine.of(LedgerType.PRICE_CHANGE).counterpart(c.by()).total(0).reason(c.reason())
                    .extra("by", c.by()).extra("family", c.family());
            if (c.before() != null) {
                line.extra("old_base", c.before().baseCents()).extra("old_half_volume", c.before().halfVolume())
                        .extra("old_floor_ratio", c.before().floorRatio()).extra("old_daily_cap", c.before().dailyCap());
            }
            if (c.after() != null) {
                line.extra("new_base", c.after().baseCents()).extra("new_half_volume", c.after().halfVolume())
                        .extra("new_floor_ratio", c.after().floorRatio()).extra("new_daily_cap", c.after().dailyCap());
            }
            lines.add(line);
            String text = describe(c);
            if (text != null) {
                announcements.add(text);
            }
        }
        if (!write(lines)) {
            ConsortiumCore.LOGGER.error("Price changes could not be logged; the table was still updated in memory");
        } else {
            followUps(lines, rt.now());
        }
        if (announcements.isEmpty()) {
            return;
        }
        if (announcements.size() > 10) {
            String reason = changes.get(0).reason();
            String summary = "Market update: " + announcements.size() + " prices changed (" + reason + "), see /prices list";
            if (boot) {
                rt.notifier.bufferBootMessage(summary, false);
            } else {
                rt.notifier.announce(summary);
            }
            for (String a : announcements) {
                ConsortiumCore.LOGGER.info("[price] {}", a);
            }
            return;
        }
        for (String a : announcements) {
            if (boot) {
                rt.notifier.bufferBootMessage(a, false);
            } else {
                rt.notifier.announce(a);
            }
        }
    }

    private String describe(PriceChange c) {
        String name = rt.prices.displayName(c.family());
        if ("override folded into the datapack".equals(c.reason())) {
            return null;
        }
        if (c.removed()) {
            return "Market update: " + name + " is no longer bought: " + c.reason();
        }
        PriceFamily after = c.after();
        String now = after.quotaOnly() ? "quota only (0 CC)" : Money.format(after.baseCents()) + " per unit";
        if (c.added()) {
            return "Market update: " + name + " now " + now + " (new): " + c.reason();
        }
        PriceFamily before = c.before();
        StringBuilder sb = new StringBuilder("Market update: ").append(name).append(" now ").append(now);
        if (before.baseCents() != after.baseCents()) {
            sb.append(" (was ").append(before.quotaOnly() ? "quota only" : Money.format(before.baseCents())).append(')');
        }
        List<String> details = new ArrayList<>();
        if (before.halfVolume() != after.halfVolume()) {
            details.add("half volume " + Units.format(after.halfVolume()) + " (was " + Units.format(before.halfVolume()) + ")");
        }
        if (before.floorRatio() != after.floorRatio()) {
            details.add("floor " + Math.round(after.floorRatio() * 100) + " % (was " + Math.round(before.floorRatio() * 100) + " %)");
        }
        if (before.dailyCap() != after.dailyCap()) {
            details.add("daily cap " + (after.dailyCap() <= 0 ? "none" : Units.format(after.dailyCap()) + " units")
                    + " (was " + (before.dailyCap() <= 0 ? "none" : Units.format(before.dailyCap())) + ")");
        }
        if (!details.isEmpty()) {
            sb.append(", ").append(String.join(", ", details));
        }
        return sb.append(": ").append(c.reason()).toString();
    }

    // ---- deliveries ----

    /**
     * Commits a delivery in one tick: ledger lines under one tx, then balance, market and aggregates, then the
     * follow-ups (alerts, HUD sync, and the {@code contribute_command} that feeds the KubeJS phase engine once per
     * delivered item id). The caller (the terminal) removes the items from the grid only when this returned a receipt.
     * Every value the memory mutation needs is computed before the write, so nothing after it can throw.
     */
    public DeliveryResult delivery(DeliveryRequest request) {
        long now = rt.now();
        Account account = rt.economy.getOrCreate(request.player(), request.name());
        String tx = newTxId();
        String day = utcDay(now);
        long balance = account.balance;
        long total = 0;
        long lifetimeAfter;
        long rankAfter;
        List<LedgerLine> lines = new ArrayList<>();
        try {
            for (DeliveryLine dl : request.lines()) {
                Market.Quote q = dl.quote();
                balance = checkedAdd(balance, q.cents());
                total = checkedAdd(total, q.cents());
                List<Map<String, Object>> items = new ArrayList<>();
                for (ItemCount ic : dl.items()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", ic.id());
                    m.put("count", ic.count());
                    items.add(m);
                }
                lines.add(LedgerLine.of(LedgerType.DELIVERY).tx(tx).player(account.uuid, account.name).counterpart("terminal")
                        .total(q.cents()).balance(balance).reason("delivery")
                        .extra("family", dl.family()).extra("items", items).extra("units", q.units())
                        .extra("paid_units", q.paidUnits()).extra("quota_units", q.quotaUnits())
                        .extra("unit", q.averageUnitCents()).extra("multiplier", q.multiplier())
                        .extra("sat_after", q.saturationAfter())
                        .extra("terminal", request.dimension() + " " + request.pos()));
            }
            // Capped like the balance so the numbers stay meaningful; computed here so nothing after the write throws.
            lifetimeAfter = Math.min(Money.MAX_BALANCE_CENTS, checkedAdd(account.lifetimeDelivered, total));
            rankAfter = Math.min(Money.MAX_BALANCE_CENTS, checkedAdd(account.rankCredit, total));
        } catch (ArithmeticException e) {
            rt.notifier.alertOps("ALERT overflow: delivery of " + request.name() + " refused");
            return DeliveryResult.fail(Result.INVALID_AMOUNT);
        }
        if (lines.isEmpty()) {
            return DeliveryResult.fail(Result.INVALID_AMOUNT);
        }
        if (balance > Money.MAX_BALANCE_CENTS) {
            rt.notifier.alertOps("ALERT balance cap: delivery of " + Money.format(total) + " by " + request.name()
                    + " refused, the balance would pass " + Money.format(Money.MAX_BALANCE_CENTS) + " (rule 4.2 anomaly)");
            return DeliveryResult.fail(Result.INVALID_AMOUNT);
        }
        if (!write(lines)) {
            return DeliveryResult.fail(Result.LEDGER_UNAVAILABLE);
        }
        // Mutate memory: everything in the same tick, nothing below can throw.
        SupplyDay supply = rt.economy.supplyDay(day);
        for (DeliveryLine dl : request.lines()) {
            Market.Quote q = dl.quote();
            rt.market.advance(dl.family(), q.paidUnits(), now);
            account.addPaidToday(dl.family(), day, q.paidUnits());
            account.lifetimeUnits = Units.round(account.lifetimeUnits + q.units());
            SupplyDay.FamilyStat stat = supply.family(dl.family());
            stat.credits += q.cents();
            stat.paidUnits = Units.round(stat.paidUnits + q.paidUnits());
            stat.units = Units.round(stat.units + q.units());
        }
        account.balance = balance;
        account.lifetimeDelivered = lifetimeAfter;
        account.rankCredit = rankAfter;
        supply.deliveries++;
        supply.deliverers.add(account.uuid);
        if (total > 0) {
            supply.addCreated(LedgerType.DELIVERY.name(), total);
        }
        if (total > supply.largestCents) {
            supply.largestCents = total;
            supply.largestUuid = account.uuid;
            supply.largestTx = tx;
        }
        rt.economy.touch();
        // Follow-ups, none of which can roll money back.
        DeliveryReceipt receipt = new DeliveryReceipt(tx, total, balance);
        ContributeHook.afterDelivery(rt, request, tx);
        followUps(lines, now);
        rt.notifyBalance(account.uuid, balance, total, "delivery");
        return new DeliveryResult(Result.SUCCESS, receipt);
    }
}
