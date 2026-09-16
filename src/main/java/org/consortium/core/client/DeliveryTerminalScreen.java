package org.consortium.core.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.consortium.core.config.CommonConfig;
import org.consortium.core.economy.Money;
import org.consortium.core.economy.Units;
import org.consortium.core.network.DeliveryConfirmPayload;
import org.consortium.core.terminal.DeliveryTerminalMenu;
import org.consortium.core.terminal.Quote;

import java.util.ArrayList;
import java.util.List;

/**
 * The terminal screen (specification 5): a header with the balance, the quote table (one row per family: units,
 * average unit price, subtotal; grey rows for quota-only families, amber rows carrying a warning), the total and the
 * Deliver / Cancel buttons, above the 27-slot grid and the player inventory. Refused stacks get a red slot and the
 * reason in their tooltip. Everything shown comes from the last {@code DeliveryQuote} payload; the client computes
 * no price itself.
 */
public final class DeliveryTerminalScreen extends AbstractContainerScreen<DeliveryTerminalMenu> {
    private static final int MAX_ROWS = 4;
    private static final int ROW_HEIGHT = 9;
    private static final int ROWS_Y = 27;
    private static final int NOTE_Y = 63;
    private static final int TOTAL_Y = 75;
    private static final int BUTTON_Y = 69;
    private static final int MARGIN = 8;
    private static final int COL_UNITS_RIGHT = 134;
    private static final int COL_UNIT_RIGHT = 176;
    private static final int COL_SUBTOTAL_RIGHT = DeliveryTerminalMenu.PANEL_WIDTH - MARGIN;
    private static final int NAME_WIDTH = 88;

    private static final int PANEL_BODY = 0xFFC6C6C6;
    private static final int PANEL_LIGHT = 0xFFFFFFFF;
    private static final int PANEL_DARK = 0xFF555555;
    private static final int PANEL_EDGE = 0xFF000000;
    private static final int SLOT_BODY = 0xFF8B8B8B;
    private static final int SLOT_DARK = 0xFF373737;
    private static final int SLOT_REFUSED = 0xFFB65050;
    private static final int TEXT = 0xFF1F1F1F;
    private static final int TEXT_DIM = 0xFF404040;
    private static final int TEXT_GREY = 0xFF7A7A7A;
    private static final int TEXT_GOLD = 0xFF7A5A00;
    private static final int TEXT_AMBER = 0xFF8B5A00;
    private static final int TEXT_ALERT = 0xFFB03A00;

    private Button deliverButton;
    private long lastSentNonce = Long.MIN_VALUE;

    public DeliveryTerminalScreen(DeliveryTerminalMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = DeliveryTerminalMenu.PANEL_WIDTH;
        this.imageHeight = DeliveryTerminalMenu.PANEL_HEIGHT;
    }

    @Override
    protected void init() {
        super.init();
        int deliverWidth = 60;
        int cancelWidth = 50;
        int deliverX = leftPos + imageWidth - MARGIN - deliverWidth;
        deliverButton = addRenderableWidget(Button.builder(Component.translatable("gui.consortium.terminal.deliver"), b -> sendConfirm())
                .bounds(deliverX, topPos + BUTTON_Y, deliverWidth, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.consortium.terminal.cancel"), b -> onClose())
                .bounds(deliverX - 4 - cancelWidth, topPos + BUTTON_Y, cancelWidth, 16).build());
        deliverButton.active = false;
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        Quote quote = menu.clientQuote();
        deliverButton.active = quote != null && quote.hasAccepted() && quote.nonce() != lastSentNonce;
    }

    private void sendConfirm() {
        Quote quote = menu.clientQuote();
        if (quote == null || !quote.hasAccepted() || quote.nonce() == lastSentNonce) {
            return;
        }
        lastSentNonce = quote.nonce();
        deliverButton.active = false;
        PacketDistributor.sendToServer(new DeliveryConfirmPayload(quote.nonce()));
    }

    // ---- rendering ----

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        if (hoveredSlot == null) {
            renderRowTooltip(graphics, mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        drawPanel(graphics, leftPos, topPos, imageWidth, imageHeight);
        // Header separator.
        graphics.fill(leftPos + 4, topPos + DeliveryTerminalMenu.HEADER_HEIGHT, leftPos + imageWidth - 4, topPos + DeliveryTerminalMenu.HEADER_HEIGHT + 1, PANEL_DARK);
        graphics.fill(leftPos + 4, topPos + DeliveryTerminalMenu.HEADER_HEIGHT + 1, leftPos + imageWidth - 4, topPos + DeliveryTerminalMenu.HEADER_HEIGHT + 2, PANEL_LIGHT);
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            boolean refused = i < DeliveryTerminalMenu.GRID_SLOTS && menu.refusalOf(i) != null;
            drawSlot(graphics, leftPos + slot.x, topPos + slot.y, refused);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        String symbol = CommonConfig.currencySymbol();
        graphics.drawString(font, title, MARGIN, 6, TEXT_DIM, false);
        if (ClientBalance.synced()) {
            Component balance = Component.translatable("gui.consortium.terminal.balance", Money.format(ClientBalance.balance(), symbol));
            graphics.drawString(font, balance, imageWidth - MARGIN - font.width(balance), 6, TEXT_GOLD, false);
        }
        Quote quote = menu.clientQuote();
        if (quote == null) {
            graphics.drawString(font, Component.translatable("gui.consortium.terminal.waiting"), MARGIN, ROWS_Y, TEXT_GREY, false);
            return;
        }
        if (quote.lines().isEmpty() && quote.refused().isEmpty()) {
            graphics.drawString(font, Component.translatable("gui.consortium.terminal.empty"), MARGIN, ROWS_Y, TEXT_GREY, false);
        } else {
            drawRight(graphics, Component.translatable("gui.consortium.terminal.column.units"), COL_UNITS_RIGHT, 17, TEXT_DIM);
            drawRight(graphics, Component.translatable("gui.consortium.terminal.column.unit"), COL_UNIT_RIGHT, 17, TEXT_DIM);
            drawRight(graphics, Component.translatable("gui.consortium.terminal.column.subtotal"), COL_SUBTOTAL_RIGHT, 17, TEXT_DIM);
            graphics.drawString(font, Component.translatable("gui.consortium.terminal.column.family"), MARGIN, 17, TEXT_DIM, false);
            int shown = Math.min(MAX_ROWS, quote.lines().size());
            for (int i = 0; i < shown; i++) {
                Quote.Line line = quote.lines().get(i);
                int y = ROWS_Y + i * ROW_HEIGHT;
                int color = line.quotaOnly() ? TEXT_GREY : hasWarning(quote, line.family()) ? TEXT_AMBER : TEXT;
                graphics.drawString(font, font.plainSubstrByWidth(line.name(), NAME_WIDTH), MARGIN, y, color, false);
                drawRight(graphics, Units.format(line.units()), COL_UNITS_RIGHT, y, color);
                if (line.quotaOnly()) {
                    drawRight(graphics, Component.translatable("gui.consortium.terminal.quota_only"), COL_SUBTOTAL_RIGHT, y, color);
                } else {
                    drawRight(graphics, Money.formatPlain(line.unitCents()), COL_UNIT_RIGHT, y, color);
                    drawRight(graphics, Money.formatPlain(line.subtotalCents()), COL_SUBTOTAL_RIGHT, y, color);
                }
            }
        }
        // The note line: the server's message when there is one, else the overflow hint.
        if (quote.message() != null && !quote.message().isEmpty()) {
            graphics.drawString(font, font.plainSubstrByWidth(quote.message(), imageWidth - 2 * MARGIN), MARGIN, NOTE_Y, TEXT_ALERT, false);
        } else if (quote.lines().size() > MAX_ROWS) {
            graphics.drawString(font, Component.translatable("gui.consortium.terminal.more", quote.lines().size() - MAX_ROWS), MARGIN, NOTE_Y, TEXT_GREY, false);
        }
        graphics.drawString(font, Component.translatable("gui.consortium.terminal.total", Money.format(quote.totalCents(), symbol)), MARGIN, TOTAL_Y, TEXT, false);
    }

    @Override
    protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
        List<Component> lines = super.getTooltipFromContainerItem(stack);
        if (hoveredSlot != null && hoveredSlot.index < DeliveryTerminalMenu.GRID_SLOTS) {
            String reason = menu.refusalOf(hoveredSlot.index);
            if (reason != null) {
                lines = new ArrayList<>(lines);
                lines.add(Component.translatable("gui.consortium.terminal.refused", reason).withStyle(ChatFormatting.RED));
            }
        }
        return lines;
    }

    private void renderRowTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        Quote quote = menu.clientQuote();
        if (quote == null || quote.lines().isEmpty()) {
            return;
        }
        int relX = mouseX - leftPos;
        int relY = mouseY - topPos;
        if (relX < MARGIN || relX > imageWidth - MARGIN || relY < ROWS_Y || relY >= ROWS_Y + MAX_ROWS * ROW_HEIGHT) {
            return;
        }
        int row = (relY - ROWS_Y) / ROW_HEIGHT;
        if (row >= quote.lines().size()) {
            return;
        }
        Quote.Line line = quote.lines().get(row);
        String symbol = CommonConfig.currencySymbol();
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(line.name()).withStyle(ChatFormatting.WHITE));
        for (String item : line.items()) {
            tip.add(Component.literal(item).withStyle(ChatFormatting.GRAY));
        }
        if (line.quotaOnly()) {
            tip.add(Component.literal(Units.format(line.units()) + " units, quota only").withStyle(ChatFormatting.GRAY));
        } else {
            tip.add(Component.literal(Units.format(line.paidUnits()) + " paid at " + Money.formatPlain(line.unitCents()) + " avg = "
                    + Money.format(line.subtotalCents(), symbol)).withStyle(ChatFormatting.GOLD));
            if (line.quotaUnits() > 0) {
                tip.add(Component.literal(Units.format(line.quotaUnits()) + " quota only").withStyle(ChatFormatting.GRAY));
            }
            if (line.multiplierPercent() != 100) {
                tip.add(Component.translatable("gui.consortium.terminal.multiplier", line.multiplierPercent()).withStyle(ChatFormatting.AQUA));
            }
        }
        for (Quote.Warning warning : quote.warnings()) {
            if (warning.family().equals(line.family())) {
                tip.add(Component.literal(warning.text()).withStyle(ChatFormatting.YELLOW));
            }
        }
        graphics.renderComponentTooltip(font, tip, mouseX, mouseY);
    }

    private static boolean hasWarning(Quote quote, String family) {
        for (Quote.Warning warning : quote.warnings()) {
            if (warning.family().equals(family)) {
                return true;
            }
        }
        return false;
    }

    private void drawRight(GuiGraphics graphics, String text, int right, int y, int color) {
        graphics.drawString(font, text, right - font.width(text), y, color, false);
    }

    private void drawRight(GuiGraphics graphics, Component text, int right, int y, int color) {
        graphics.drawString(font, text, right - font.width(text), y, color, false);
    }

    /** A vanilla-looking bevelled panel drawn with plain fills, so no background texture is needed. */
    private static void drawPanel(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, PANEL_EDGE);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL_BODY);
        graphics.fill(x + 1, y + 1, x + w - 2, y + 3, PANEL_LIGHT);
        graphics.fill(x + 1, y + 1, x + 3, y + h - 2, PANEL_LIGHT);
        graphics.fill(x + 2, y + h - 3, x + w - 1, y + h - 1, PANEL_DARK);
        graphics.fill(x + w - 3, y + 2, x + w - 1, y + h - 1, PANEL_DARK);
    }

    /** An 18x18 slot around the 16x16 item area at (x, y). */
    private static void drawSlot(GuiGraphics graphics, int x, int y, boolean refused) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, refused ? SLOT_REFUSED : SLOT_BODY);
        graphics.fill(x - 1, y - 1, x + 17, y, SLOT_DARK);
        graphics.fill(x - 1, y - 1, x, y + 17, SLOT_DARK);
        graphics.fill(x - 1, y + 16, x + 17, y + 17, PANEL_LIGHT);
        graphics.fill(x + 16, y - 1, x + 17, y + 17, PANEL_LIGHT);
    }
}
