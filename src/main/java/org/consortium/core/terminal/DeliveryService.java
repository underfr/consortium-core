package org.consortium.core.terminal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.ConsortiumRuntime;
import org.consortium.core.api.event.DeliveryEvent;
import org.consortium.core.api.event.DeliveryQuoteEvent;
import org.consortium.core.config.ServerConfig;
import org.consortium.core.economy.Account;
import org.consortium.core.economy.Money;
import org.consortium.core.economy.Transactions;
import org.consortium.core.economy.Units;
import org.consortium.core.monitoring.Notifier;
import org.consortium.core.pricing.FamilyIndex;
import org.consortium.core.pricing.Market;
import org.consortium.core.pricing.PriceFamily;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The server-side half of the Delivery Terminal (specification 5): pricing a grid and committing a delivery. The
 * block, the menu, the screen and the payloads call {@link #quote} on every grid change and {@link #confirm} on
 * {@code DeliveryConfirm}; this class owns the refusal rules (creative players included), the per-family
 * aggregation, the quote event, the tolerance check and the transaction order.
 */
public final class DeliveryService {
    private static final SecureRandom NONCES = new SecureRandom();
    /** Shown to a creative-mode player by the block and by a refused confirm. */
    public static final String CREATIVE_REFUSED = "Switch to survival to deliver";

    /** Outcome of {@link #confirm}. */
    public record Confirmation(boolean delivered, Quote quote, Transactions.DeliveryReceipt receipt, List<String> receiptLines) {
        public static Confirmation refused(Quote fresh) {
            return new Confirmation(false, fresh, null, List.of());
        }
    }

    /** Per-family aggregation of a grid, before pricing. */
    private record Aggregate(String family, List<Transactions.ItemCount> items, double units, List<Integer> slots) {
    }

    /** Quotes are recomputed up to once per tick per player: a broken listener is reported once per minute. */
    private static volatile long lastQuoteListenerWarning;

    private DeliveryService() {
    }

    private static void quoteListenerFailed(ConsortiumRuntime rt, Throwable t) {
        long now = rt.now();
        if (now - lastQuoteListenerWarning < 60_000L) {
            return;
        }
        lastQuoteListenerWarning = now;
        ConsortiumCore.LOGGER.warn("A DeliveryQuoteEvent listener threw; its modifiers are ignored (reported once per minute)", t);
        rt.notifier.informOps("A DeliveryQuoteEvent listener threw (" + t + "); its modifiers were ignored");
    }

    /**
     * Prices the grid without side effects (besides posting {@link DeliveryQuoteEvent}). {@code slots} is the grid
     * content by slot index; refused slots are reported, not removed.
     */
    public static Quote quote(ServerPlayer player, List<ItemStack> slots) {
        ConsortiumRuntime rt = ConsortiumRuntime.get();
        long nonce = NONCES.nextLong();
        if (rt == null) {
            return new Quote(nonce, List.of(), List.of(), List.of(), 0, "The Consortium economy is not ready");
        }
        return price(rt, player, slots, nonce).quote();
    }

    /** A quote plus the per-family market quotes it was built from (kept for the commit). */
    private record Priced(Quote quote, List<Transactions.DeliveryLine> lines) {
    }

    private static Priced price(ConsortiumRuntime rt, ServerPlayer player, List<ItemStack> slots, long nonce) {
        long now = rt.now();
        String day = rt.transactions.utcDay(now);
        Account account = rt.economy.account(player.getUUID());
        List<Quote.Refusal> refused = new ArrayList<>();
        Map<String, Aggregate> aggregates = new LinkedHashMap<>();
        for (int i = 0; i < slots.size(); i++) {
            ItemStack stack = slots.get(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            Optional<FamilyIndex.Entry> entry = rt.prices.entryOf(stack.getItem());
            if (entry.isEmpty()) {
                refused.add(new Quote.Refusal(i, Quote.NOT_PRICED));
                continue;
            }
            if (!stack.getComponentsPatch().isEmpty()) {
                refused.add(new Quote.Refusal(i, Quote.MODIFIED));
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            Aggregate agg = aggregates.get(entry.get().family());
            if (agg == null) {
                agg = new Aggregate(entry.get().family(), new ArrayList<>(), 0, new ArrayList<>());
                aggregates.put(entry.get().family(), agg);
            }
            // Merge counts of the same item id into one entry for the ledger.
            boolean merged = false;
            for (int j = 0; j < agg.items().size(); j++) {
                if (agg.items().get(j).id().equals(id)) {
                    agg.items().set(j, new Transactions.ItemCount(id, agg.items().get(j).count() + stack.getCount()));
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                agg.items().add(new Transactions.ItemCount(id, stack.getCount()));
            }
            agg.slots().add(i);
            aggregates.put(entry.get().family(), new Aggregate(agg.family(), agg.items(), agg.units() + stack.getCount() * entry.get().weight(), agg.slots()));
        }

        // First pass: base quotes (multiplier 1) for the event.
        List<Aggregate> ordered = new ArrayList<>(aggregates.values());
        List<DeliveryQuoteEvent.Line> eventLines = new ArrayList<>();
        List<Market.Quote> baseQuotes = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            Aggregate agg = ordered.get(i);
            double paidToday = account == null ? 0 : account.paidToday(agg.family(), day);
            Market.Quote q = rt.market.quote(agg.family(), agg.units(), paidToday, 1, now);
            baseQuotes.add(q);
            List<String> itemText = new ArrayList<>();
            for (Transactions.ItemCount ic : agg.items()) {
                itemText.add(ic.id() + " x" + ic.count());
            }
            eventLines.add(new DeliveryQuoteEvent.Line(i, agg.family(), itemText, q.units(), q.paidUnits(), q.cents()));
        }
        DeliveryQuoteEvent event = new DeliveryQuoteEvent(player, eventLines);
        try {
            NeoForge.EVENT_BUS.post(event);
        } catch (Throwable t) {
            quoteListenerFailed(rt, t);
            event = new DeliveryQuoteEvent(player, eventLines);
        }

        // Second pass: apply multipliers and refusals.
        List<Quote.Line> lines = new ArrayList<>();
        List<Quote.Warning> warnings = new ArrayList<>();
        List<Transactions.DeliveryLine> commitLines = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < ordered.size(); i++) {
            Aggregate agg = ordered.get(i);
            String refusal = event.getRefusal(i);
            if (refusal != null) {
                for (int slot : agg.slots()) {
                    refused.add(new Quote.Refusal(slot, refusal));
                }
                continue;
            }
            double multiplier = event.getMultiplier(i);
            Market.Quote q = multiplier == 1 ? baseQuotes.get(i)
                    : rt.market.quote(agg.family(), agg.units(), account == null ? 0 : account.paidToday(agg.family(), day), multiplier, now);
            PriceFamily family = rt.prices.family(agg.family());
            boolean quotaOnly = family == null || family.quotaOnly();
            if (!quotaOnly && q.cents() == 0 && q.paidUnits() > 0) {
                warnings.add(new Quote.Warning(agg.family(), Quote.ZERO_VALUE));
            }
            if (!quotaOnly && q.quotaUnits() > 0) {
                warnings.add(new Quote.Warning(agg.family(), Quote.DAILY_CAP));
            }
            List<String> itemText = new ArrayList<>();
            for (Transactions.ItemCount ic : agg.items()) {
                itemText.add(ic.count() + " x " + itemName(ic.id()));
            }
            lines.add(new Quote.Line(agg.family(), rt.prices.displayName(agg.family()), itemText, q.units(), q.paidUnits(), q.quotaUnits(),
                    q.averageUnitCents(), q.cents(), (int) Math.round(q.multiplier() * 100), quotaOnly));
            commitLines.add(new Transactions.DeliveryLine(agg.family(), agg.items(), q));
            total += q.cents();
        }
        return new Priced(new Quote(nonce, lines, refused, warnings, total, null), commitLines);
    }

    /**
     * Executes a delivery: re-validates and re-prices the grid, checks the drift against {@code quoted}, writes the
     * ledger, then removes the accepted stacks and credits the player. Returns a refusal with a fresh quote when the
     * prices moved beyond the tolerance or nothing is accepted.
     *
     * @param grid the menu's transient grid; accepted slots are emptied only after the ledger accepted the lines
     */
    public static Confirmation confirm(ServerPlayer player, Container grid, Quote quoted, ResourceKey<Level> dimension, BlockPos pos) {
        ConsortiumRuntime rt = ConsortiumRuntime.get();
        List<ItemStack> slots = new ArrayList<>(grid.getContainerSize());
        for (int i = 0; i < grid.getContainerSize(); i++) {
            slots.add(grid.getItem(i));
        }
        if (rt == null) {
            return Confirmation.refused(new Quote(NONCES.nextLong(), List.of(), List.of(), List.of(), 0, "The Consortium economy is not ready"));
        }
        Priced priced = price(rt, player, slots, NONCES.nextLong());
        if (player.isCreative()) {
            // Creative items have no marginal cost (rule 4.1); caught here too so a game mode switch while the menu is open is refused.
            return Confirmation.refused(priced.quote().withMessage(CREATIVE_REFUSED));
        }
        if (!priced.quote().hasAccepted()) {
            return Confirmation.refused(priced.quote().withMessage("Nothing to deliver"));
        }
        if (quoted != null && quoted.totalCents() > 0) {
            long tolerance = quoted.totalCents() * ServerConfig.quoteTolerancePercent() / 100;
            if (priced.quote().totalCents() < quoted.totalCents() - tolerance) {
                return Confirmation.refused(priced.quote().withMessage("Prices moved, please check again"));
            }
        }
        String dim = dimension == null ? "unknown" : dimension.location().toString();
        String where = pos == null ? "?" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
        Transactions.DeliveryRequest request = new Transactions.DeliveryRequest(player.getUUID(), player.getGameProfile().getName(),
                priced.lines(), dim, where);
        Transactions.DeliveryResult result = rt.transactions.delivery(request);
        if (!result.ok()) {
            String why = switch (result.result()) {
                case LEDGER_UNAVAILABLE -> "Ledger unavailable, delivery refused";
                case INVALID_AMOUNT -> "Balance limit reached, delivery refused";
                default -> "Delivery refused: " + result.result();
            };
            return Confirmation.refused(priced.quote().withMessage(why));
        }
        Transactions.DeliveryReceipt receipt = result.receipt();
        // Remove the accepted stacks: every slot whose item is in a committed family and was not refused.
        java.util.Set<Integer> refusedSlots = new java.util.HashSet<>();
        for (Quote.Refusal r : priced.quote().refused()) {
            refusedSlots.add(r.slot());
        }
        for (int i = 0; i < grid.getContainerSize(); i++) {
            ItemStack stack = grid.getItem(i);
            if (stack.isEmpty() || refusedSlots.contains(i)) {
                continue;
            }
            grid.setItem(i, ItemStack.EMPTY);
        }
        List<String> receiptLines = receiptLines(rt, priced, receipt);
        for (String line : receiptLines) {
            Notifier.tell(player, line);
        }
        followUps(rt, player, priced, receipt, dimension, pos);
        return new Confirmation(true, priced.quote(), receipt, receiptLines);
    }

    private static List<String> receiptLines(ConsortiumRuntime rt, Priced priced, Transactions.DeliveryReceipt receipt) {
        List<String> out = new ArrayList<>();
        String symbol = org.consortium.core.config.CommonConfig.currencySymbol();
        for (Quote.Line line : priced.quote().lines()) {
            StringBuilder sb = new StringBuilder(line.name()).append(": ");
            if (line.quotaOnly()) {
                sb.append(Units.format(line.units())).append(" units (").append(String.join(", ", line.items())).append("), quota only");
            } else {
                sb.append(Units.format(line.units())).append(" units (").append(String.join(", ", line.items())).append("), ")
                        .append(Units.format(line.paidUnits())).append(" paid (avg ").append(Money.formatPlain(line.unitCents())).append(") = ")
                        .append(Money.format(line.subtotalCents(), symbol));
                if (line.quotaUnits() > 0) {
                    sb.append(", ").append(Units.format(line.quotaUnits())).append(" quota only");
                }
                if (line.multiplierPercent() != 100) {
                    sb.append(" (x").append(line.multiplierPercent()).append(" %)");
                }
            }
            out.add(sb.toString());
        }
        out.add("Total " + Money.format(receipt.totalCents(), symbol) + ". Balance: " + Money.format(receipt.balanceAfter(), symbol) + ".");
        return out;
    }

    private static void followUps(ConsortiumRuntime rt, ServerPlayer player, Priced priced, Transactions.DeliveryReceipt receipt,
                                  ResourceKey<Level> dimension, BlockPos pos) {
        List<DeliveryEvent.Line> lines = new ArrayList<>();
        for (Transactions.DeliveryLine dl : priced.lines()) {
            List<String> items = new ArrayList<>();
            for (Transactions.ItemCount ic : dl.items()) {
                items.add(ic.id() + " x" + ic.count());
            }
            Market.Quote q = dl.quote();
            lines.add(new DeliveryEvent.Line(dl.family(), items, q.units(), q.paidUnits(), q.cents(), q.multiplier()));
        }
        try {
            NeoForge.EVENT_BUS.post(new DeliveryEvent(player, receipt.txId(), lines, receipt.totalCents(), dimension, pos));
        } catch (Throwable t) {
            ConsortiumCore.LOGGER.error("DeliveryEvent listener failed (delivery {} already committed)", receipt.txId(), t);
        }
        // The contribute_command follow-up already ran inside Transactions.delivery, right after the ledger commit.
    }

    private static String itemName(String id) {
        var rl = net.minecraft.resources.ResourceLocation.tryParse(id);
        if (rl == null) {
            return id;
        }
        return BuiltInRegistries.ITEM.getOptional(rl).map(item -> new ItemStack(item).getHoverName().getString()).orElse(id);
    }
}
