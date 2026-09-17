package org.consortium.core.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.ConsortiumRuntime;
import org.consortium.core.api.Result;
import org.consortium.core.config.CommonConfig;
import org.consortium.core.economy.Account;
import org.consortium.core.economy.Money;
import org.consortium.core.economy.Transactions;
import org.consortium.core.economy.Units;
import org.consortium.core.pricing.FamilyIndex;
import org.consortium.core.pricing.PriceFamily;
import org.consortium.core.terminal.DeliveryService;
import org.consortium.core.terminal.Quote;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.consortium.core.command.CommandSupport.fail;
import static org.consortium.core.command.CommandSupport.grey;
import static org.consortium.core.command.CommandSupport.info;
import static org.consortium.core.command.CommandSupport.ok;
import static org.consortium.core.command.CommandSupport.runtime;

/**
 * {@code /ccore selftest} (op level 4, console friendly, registered only with {@code -Dconsortium.selftest=true}):
 * exercises the API deposit and withdraw paths, an overdraft refusal and a full terminal delivery through
 * {@link DeliveryService} on a fake player, then checks the invariants (ledger accepted every movement, balances add
 * up, accepted slots emptied, refused slots kept, market saturation advanced). Every step prints the ledger line it
 * produced. It writes real ledger lines, moves the real market and runs the real {@code contribute_command} follow-up
 * (the KubeJS phase engine records the delivery when it is present), which is why the JVM flag gates it: meant for
 * the dedicated test server where no client can join. The fake account is named {@code SelfTest} with a fixed uuid so
 * repeated runs reuse it.
 */
final class SelfTestCommand {
    static final UUID FAKE_UUID = UUID.nameUUIDFromBytes("consortium:selftest".getBytes(StandardCharsets.UTF_8));
    static final String FAKE_NAME = "SelfTest";
    private static final long DEPOSIT_CENTS = 1000;
    private static final long WITHDRAW_CENTS = 250;

    private SelfTestCommand() {
    }

    /** Collects the assertions of one run. */
    private static final class Checks {
        final List<String> failures = new ArrayList<>();
        int passed;

        void expect(boolean condition, String what) {
            if (condition) {
                passed++;
            } else {
                failures.add(what);
            }
        }
    }

    static int run(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ConsortiumRuntime rt = runtime();
        CommandSourceStack src = ctx.getSource();
        Checks checks = new Checks();
        String symbol = CommonConfig.currencySymbol();
        long now = rt.now();
        say(src, "Self-test on " + FAKE_NAME + " (" + FAKE_UUID + "), ledger seq before: " + rt.ledger.nextSeq());

        // 1. Deposit (API credit).
        Account before = rt.economy.account(FAKE_UUID);
        long balanceBefore = before == null ? 0 : before.balance;
        long seqBefore = rt.ledger.nextSeq();
        Transactions.Outcome deposit = rt.transactions.apiCredit(FAKE_UUID, DEPOSIT_CENTS, "selftest deposit");
        say(src, "1. deposit " + Money.format(DEPOSIT_CENTS, symbol) + " -> " + deposit.result() + ", balance " + Money.format(deposit.balanceAfter(), symbol));
        lastLedger(src, rt, 1);
        checks.expect(deposit.ok(), "deposit accepted");
        checks.expect(deposit.balanceAfter() == balanceBefore + DEPOSIT_CENTS, "balance after deposit = before + deposit");
        Account account = rt.economy.account(FAKE_UUID);
        if (account != null && !FAKE_NAME.equals(account.name)) {
            account.name = FAKE_NAME;
            rt.economy.touch();
        }

        // 2. Withdraw (API debit to a sink).
        Transactions.Outcome withdraw = rt.transactions.apiDebit(FAKE_UUID, WITHDRAW_CENTS, "selftest:sink");
        say(src, "2. withdraw " + Money.format(WITHDRAW_CENTS, symbol) + " -> " + withdraw.result() + ", balance " + Money.format(withdraw.balanceAfter(), symbol));
        lastLedger(src, rt, 1);
        checks.expect(withdraw.ok(), "withdraw accepted");
        checks.expect(withdraw.balanceAfter() == balanceBefore + DEPOSIT_CENTS - WITHDRAW_CENTS, "balance after withdraw = before + deposit - withdraw");

        // 3. Overdraft refused, nothing written.
        long seqBeforeOverdraft = rt.ledger.nextSeq();
        long tooMuch = withdraw.balanceAfter() + 1;
        Transactions.Outcome overdraft = rt.transactions.apiDebit(FAKE_UUID, tooMuch, "selftest:overdraft");
        say(src, "3. overdraft " + Money.format(tooMuch, symbol) + " -> " + overdraft.result() + " (balance " + Money.format(overdraft.balanceAfter(), symbol) + ")");
        checks.expect(overdraft.result() == Result.INSUFFICIENT_FUNDS, "overdraft refused with INSUFFICIENT_FUNDS");
        checks.expect(rt.ledger.nextSeq() == seqBeforeOverdraft, "overdraft wrote no ledger line");

        // 4. Delivery through the terminal service on a fake player.
        Optional<Pick> pickOpt = pickPaidFamily(rt);
        Optional<Pick> quotaOpt = pickQuotaFamily(rt);
        if (pickOpt.isEmpty()) {
            checks.failures.add("no paid family with an indexed item in the price table, delivery skipped");
        } else {
            Pick paid = pickOpt.get();
            ServerLevel level = rt.server.overworld();
            FakePlayer fake = FakePlayerFactory.get(level, new GameProfile(FAKE_UUID, FAKE_NAME));
            SimpleContainer grid = new SimpleContainer(27);
            grid.setItem(0, new ItemStack(paid.item(), 64));
            int quotaSlot = -1;
            if (quotaOpt.isPresent()) {
                quotaSlot = 1;
                grid.setItem(1, new ItemStack(quotaOpt.get().item(), 16));
            }
            Item unpriced = firstUnpriced(rt);
            int unpricedSlot = -1;
            if (unpriced != null) {
                unpricedSlot = 2;
                grid.setItem(2, new ItemStack(unpriced, 1));
            }
            ItemStack modified = new ItemStack(paid.item(), 3);
            modified.set(DataComponents.CUSTOM_NAME, Component.literal("Renamed"));
            grid.setItem(3, modified);
            List<ItemStack> slots = new ArrayList<>();
            for (int i = 0; i < grid.getContainerSize(); i++) {
                slots.add(grid.getItem(i));
            }

            long priceBefore = rt.market.unitPriceCents(paid.family().key(), now);
            double saturationBefore = rt.market.saturation(paid.family().key(), now);
            String day = rt.transactions.capDay(now);
            double paidToday = account == null ? 0 : account.paidToday(paid.family().key(), day);
            say(src, "4. delivery: 64 x " + paid.id() + " (" + paid.family().name() + ", " + Money.format(priceBefore, symbol) + " per unit, saturation "
                    + Units.format(saturationBefore) + ", paid today " + Units.format(paidToday) + ", cap " + Units.format(paid.family().dailyCap()) + ")"
                    + (quotaSlot >= 0 ? ", 16 x " + quotaOpt.get().id() + " (quota only)" : "")
                    + (unpricedSlot >= 0 ? ", 1 x " + BuiltInRegistries.ITEM.getKey(unpriced) + " (not priced)" : "")
                    + ", 3 x " + paid.id() + " renamed (modified)");

            Quote quote = DeliveryService.quote(fake, slots);
            say(src, "   quote: " + quote.lines().size() + " line(s), " + quote.refused().size() + " refusal(s), " + quote.warnings().size()
                    + " warning(s), total " + Money.format(quote.totalCents(), symbol) + (quote.message() != null ? ", message: " + quote.message() : ""));
            for (Quote.Line line : quote.lines()) {
                say(src, "   - " + line.name() + ": " + Units.format(line.units()) + " units, " + Units.format(line.paidUnits()) + " paid, "
                        + Units.format(line.quotaUnits()) + " quota, avg " + Money.formatPlain(line.unitCents()) + ", subtotal "
                        + Money.format(line.subtotalCents(), symbol) + (line.quotaOnly() ? " (quota only)" : ""));
            }
            for (Quote.Refusal r : quote.refused()) {
                say(src, "   - slot " + r.slot() + " refused: " + r.reason());
            }
            for (Quote.Warning w : quote.warnings()) {
                say(src, "   - warning " + w.family() + ": " + w.text());
            }
            int expectedLines = 1 + (quotaSlot >= 0 ? 1 : 0);
            int expectedRefusals = 1 + (unpricedSlot >= 0 ? 1 : 0);
            checks.expect(quote.lines().size() == expectedLines, "quote has " + expectedLines + " line(s)");
            checks.expect(quote.refused().size() == expectedRefusals, "quote has " + expectedRefusals + " refusal(s)");
            checks.expect(quote.refused().stream().anyMatch(r -> r.slot() == 3 && Quote.MODIFIED.equals(r.reason())), "renamed stack refused as MODIFIED");
            if (unpricedSlot >= 0) {
                final int slot = unpricedSlot;
                checks.expect(quote.refused().stream().anyMatch(r -> r.slot() == slot && Quote.NOT_PRICED.equals(r.reason())), "unpriced stack refused as NOT_PRICED");
            }
            Quote.Line paidLine = quote.lines().stream().filter(l -> l.family().equals(paid.family().key())).findFirst().orElse(null);
            checks.expect(paidLine != null && paidLine.units() == Units.round(64 * paid.weight()), "paid family line counts 64 x weight units");
            double expectedPaid = paidLine == null ? 0 : paidLine.paidUnits();
            if (paidLine != null && paidLine.quotaUnits() > 0) {
                checks.expect(quote.warnings().stream().anyMatch(w -> Quote.DAILY_CAP.equals(w.text())), "daily cap warning present when units exceed the cap");
            }

            long balanceBeforeDelivery = rt.economy.account(FAKE_UUID).balance;
            long seqBeforeDelivery = rt.ledger.nextSeq();
            DeliveryService.Confirmation confirmation = DeliveryService.confirm(fake, grid, quote, Level.OVERWORLD, new BlockPos(0, 100, 0));
            say(src, "   confirm: delivered=" + confirmation.delivered() + (confirmation.quote().message() != null ? ", message: " + confirmation.quote().message() : ""));
            for (String line : confirmation.receiptLines()) {
                say(src, "   receipt: " + line);
            }
            checks.expect(confirmation.delivered(), "delivery committed");
            if (confirmation.delivered()) {
                Transactions.DeliveryReceipt receipt = confirmation.receipt();
                lastLedger(src, rt, (int) (rt.ledger.nextSeq() - seqBeforeDelivery));
                checks.expect(rt.ledger.nextSeq() - seqBeforeDelivery == expectedLines, "one DELIVERY ledger line per family");
                checks.expect(receipt.totalCents() == quote.totalCents(), "receipt total equals the quoted total");
                long balanceAfter = rt.economy.account(FAKE_UUID).balance;
                checks.expect(balanceAfter == balanceBeforeDelivery + receipt.totalCents(), "balance after delivery = before + total");
                checks.expect(receipt.balanceAfter() == balanceAfter, "receipt balance equals the account balance");
                checks.expect(grid.getItem(0).isEmpty(), "accepted slot 0 emptied");
                if (quotaSlot >= 0) {
                    checks.expect(grid.getItem(quotaSlot).isEmpty(), "quota-only slot emptied");
                }
                if (unpricedSlot >= 0) {
                    checks.expect(!grid.getItem(unpricedSlot).isEmpty(), "unpriced slot kept");
                }
                checks.expect(grid.getItem(3).getCount() == 3, "modified slot kept");
                long priceAfter = rt.market.unitPriceCents(paid.family().key(), rt.now());
                double saturationAfter = rt.market.saturation(paid.family().key(), rt.now());
                say(src, "   market: " + paid.family().name() + " " + Money.format(priceBefore, symbol) + " -> " + Money.format(priceAfter, symbol)
                        + " per unit, saturation " + Units.format(saturationBefore) + " -> " + Units.format(saturationAfter));
                String hook = org.consortium.core.config.ServerConfig.contributeCommand();
                say(src, "   follow-up: " + (hook == null || hook.isBlank() ? "contribute_command disabled"
                        : "ran '" + hook + "' once per delivered item id as the console (check the KubeJS log for the engine's answer)"));
                if (expectedPaid > 0) {
                    checks.expect(saturationAfter > saturationBefore, "saturation advanced by the paid units");
                    checks.expect(priceAfter <= priceBefore, "unit price did not rise after a paid delivery");
                    checks.expect(priceAfter < priceBefore || rt.market.atFloor(paid.family().key(), rt.now()),
                            "unit price fell after a paid delivery (or the family is at its floor)");
                } else {
                    say(src, "   (daily cap already reached for " + FAKE_NAME + " today: quota only, no price move expected)");
                }
                // A second confirm with the consumed quote has nothing left to deliver.
                DeliveryService.Confirmation again = DeliveryService.confirm(fake, grid, quote, Level.OVERWORLD, new BlockPos(0, 100, 0));
                say(src, "   confirm again: delivered=" + again.delivered() + ", message: " + again.quote().message());
                checks.expect(!again.delivered() && "Nothing to deliver".equals(again.quote().message()), "second confirm answers Nothing to deliver");
            }
        }

        Account finalAccount = rt.economy.account(FAKE_UUID);
        say(src, "Final: " + FAKE_NAME + " balance " + Money.format(finalAccount == null ? 0 : finalAccount.balance, symbol) + ", ledger seq " + rt.ledger.nextSeq()
                + " (" + (rt.ledger.nextSeq() - seqBefore) + " line(s) written)");
        if (checks.failures.isEmpty()) {
            final String text = "SELFTEST OK: " + checks.passed + " check(s) passed.";
            src.sendSuccess(() -> ok(text), true);
            ConsortiumCore.LOGGER.info(text);
            return 1;
        }
        final String text = "SELFTEST FAILED: " + checks.failures.size() + " of " + (checks.passed + checks.failures.size()) + " check(s): " + String.join("; ", checks.failures);
        src.sendFailure(fail(text));
        ConsortiumCore.LOGGER.error(text);
        return 0;
    }

    /** A family with the item chosen to represent it. */
    private record Pick(PriceFamily family, Item item, String id, double weight) {
    }

    /** Iron ingot when it is priced (its cap makes 64 units exercise the daily cap), else any paid indexed item. */
    private static Optional<Pick> pickPaidFamily(ConsortiumRuntime rt) {
        Optional<Pick> iron = pick(rt, Items.IRON_INGOT);
        if (iron.isPresent() && !iron.get().family().quotaOnly()) {
            return iron;
        }
        for (String id : rt.prices.index().itemIds()) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            Optional<Item> item = rl == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(rl);
            if (item.isEmpty()) {
                continue;
            }
            Optional<Pick> p = pick(rt, item.get());
            if (p.isPresent() && !p.get().family().quotaOnly() && p.get().weight() == 1) {
                return p;
            }
        }
        return Optional.empty();
    }

    private static Optional<Pick> pickQuotaFamily(ConsortiumRuntime rt) {
        Optional<Pick> cobble = pick(rt, Items.COBBLESTONE);
        if (cobble.isPresent() && cobble.get().family().quotaOnly()) {
            return cobble;
        }
        for (String id : rt.prices.index().itemIds()) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            Optional<Item> item = rl == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(rl);
            if (item.isEmpty()) {
                continue;
            }
            Optional<Pick> p = pick(rt, item.get());
            if (p.isPresent() && p.get().family().quotaOnly()) {
                return p;
            }
        }
        return Optional.empty();
    }

    private static Optional<Pick> pick(ConsortiumRuntime rt, Item item) {
        Optional<FamilyIndex.Entry> entry = rt.prices.entryOf(item);
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        PriceFamily family = rt.prices.family(entry.get().family());
        if (family == null) {
            return Optional.empty();
        }
        return Optional.of(new Pick(family, item, BuiltInRegistries.ITEM.getKey(item).toString(), entry.get().weight()));
    }

    private static Item firstUnpriced(ConsortiumRuntime rt) {
        for (Item candidate : List.of(Items.DIAMOND, Items.STICK, Items.DIRT, Items.APPLE, Items.OAK_PLANKS)) {
            if (!rt.prices.isPriced(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static void lastLedger(CommandSourceStack src, ConsortiumRuntime rt, int n) {
        if (n <= 0) {
            return;
        }
        List<String> lines = rt.ledger.tail(n, null);
        for (int i = lines.size() - 1; i >= 0; i--) {
            final String line = lines.get(i);
            src.sendSuccess(() -> grey("   ledger " + line), false);
        }
    }

    private static void say(CommandSourceStack src, String text) {
        src.sendSuccess(() -> info(text), false);
    }
}
