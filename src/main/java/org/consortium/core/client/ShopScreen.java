package org.consortium.core.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.consortium.core.config.CommonConfig;
import org.consortium.core.economy.Money;
import org.consortium.core.network.ShopBuyPayload;
import org.consortium.core.network.ShopCatalogPayload;
import org.consortium.core.network.ShopResultPayload;
import org.consortium.core.network.TerminalOpenPayload;
import org.consortium.core.shop.ShopMenu;
import org.consortium.core.shop.ShopState;

import java.util.ArrayList;
import java.util.List;

/**
 * The shop screen (specification v0.2, 5.2): the title and the synced balance, a hand-rolled scrolled list of the
 * catalogue (six rows of 24 px, icon with count, name, price, a coloured status line; wheel and scrollbar), a status
 * line with the last purchase result, and the Buy and Back buttons. Everything shown comes from the last
 * {@code shop_catalog} and {@code shop_result} payloads; affordability is the only thing the client judges itself
 * (from {@link ClientBalance}), and the server re-checks it.
 */
public final class ShopScreen extends AbstractContainerScreen<ShopMenu> {
    private static final int MARGIN = 8;
    private static final int LIST_Y = 20;
    private static final int ROW_HEIGHT = 24;
    private static final int VISIBLE_ROWS = 6;
    private static final int LIST_HEIGHT = VISIBLE_ROWS * ROW_HEIGHT;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int STATUS_Y = LIST_Y + LIST_HEIGHT + 6;
    /** Result lines shown under the list (two fit between the list and the buttons). */
    private static final int STATUS_LINES = 2;
    private static final int BUTTON_Y = ShopMenu.PANEL_HEIGHT - MARGIN - 18;
    private static final int PENDING_TICKS = 100;

    private static final int PANEL_BODY = 0xFFC6C6C6;
    private static final int PANEL_LIGHT = 0xFFFFFFFF;
    private static final int PANEL_DARK = 0xFF555555;
    private static final int PANEL_EDGE = 0xFF000000;
    private static final int LIST_BODY = 0xFF8B8B8B;
    private static final int ROW_ODD = 0xFF9A9A9A;
    private static final int ROW_HOVER = 0xFFB0B0B0;
    private static final int ROW_SELECTED = 0xFFD8D8A0;
    private static final int TRACK = 0xFF373737;
    private static final int THUMB = 0xFFC6C6C6;
    private static final int TEXT = 0xFF1F1F1F;
    private static final int TEXT_DIM = 0xFF404040;
    private static final int TEXT_GREY = 0xFF5A5A5A;
    private static final int TEXT_MUTED = 0xFF6A6A6A;
    private static final int TEXT_GOLD = 0xFF7A5A00;
    private static final int TEXT_GREEN = 0xFF1E6B2E;
    private static final int TEXT_RED = 0xFFA02A1A;

    private Button buyButton;
    private Button backButton;
    private String selectedKey;
    private int scroll;
    private int pendingTicks;
    private int shownResultGeneration;

    public ShopScreen(ShopMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = ShopMenu.PANEL_WIDTH;
        this.imageHeight = ShopMenu.PANEL_HEIGHT;
    }

    @Override
    protected void init() {
        super.init();
        int buyWidth = 60;
        int backWidth = 50;
        int buyX = leftPos + imageWidth - MARGIN - buyWidth;
        buyButton = addRenderableWidget(Button.builder(Component.translatable("gui.consortium.shop.buy"), b -> sendBuy())
                .bounds(buyX, topPos + BUTTON_Y, buyWidth, 16).build());
        backButton = addRenderableWidget(Button.builder(Component.translatable("gui.consortium.shop.back"), b -> back())
                .bounds(buyX - 4 - backWidth, topPos + BUTTON_Y, backWidth, 16).build());
        buyButton.active = false;
        shownResultGeneration = menu.resultGeneration();
    }

    // ---- state ----

    private ShopCatalogPayload.Entry selected() {
        ShopCatalogPayload catalog = menu.clientCatalog();
        return catalog == null || selectedKey == null ? null : catalog.entry(selectedKey);
    }

    private static boolean affordable(ShopCatalogPayload.Entry entry) {
        return ClientBalance.synced() && ClientBalance.balance() >= entry.priceCents();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (menu.resultGeneration() != shownResultGeneration) {
            shownResultGeneration = menu.resultGeneration();
            pendingTicks = 0;
        } else if (pendingTicks > 0) {
            pendingTicks--;
        }
        ShopCatalogPayload.Entry entry = selected();
        buyButton.active = entry != null && entry.available() && affordable(entry) && pendingTicks == 0;
        int max = maxScroll();
        if (scroll > max) {
            scroll = max;
        }
    }

    private void sendBuy() {
        ShopCatalogPayload catalog = menu.clientCatalog();
        ShopCatalogPayload.Entry entry = selected();
        if (catalog == null || entry == null || !entry.available() || !affordable(entry) || pendingTicks > 0) {
            return;
        }
        pendingTicks = PENDING_TICKS;
        buyButton.active = false;
        PacketDistributor.sendToServer(new ShopBuyPayload(entry.key(), catalog.nonce()));
    }

    private void back() {
        if (menu.fromTerminal()) {
            PacketDistributor.sendToServer(new TerminalOpenPayload(menu.terminalPos()));
        } else {
            onClose();
        }
    }

    // ---- list geometry ----

    private int listLeft() {
        return leftPos + MARGIN;
    }

    private int listRight() {
        return leftPos + imageWidth - MARGIN;
    }

    private int listTop() {
        return topPos + LIST_Y;
    }

    private int listBottom() {
        return topPos + LIST_Y + LIST_HEIGHT;
    }

    private int rowWidth() {
        return listRight() - listLeft() - (maxScroll() > 0 ? SCROLLBAR_WIDTH + 2 : 0);
    }

    private int entryCount() {
        ShopCatalogPayload catalog = menu.clientCatalog();
        return catalog == null ? 0 : catalog.entries().size();
    }

    private int maxScroll() {
        return Math.max(0, entryCount() * ROW_HEIGHT - LIST_HEIGHT);
    }

    /** The row index under the mouse, or -1. */
    private int rowAt(double mouseX, double mouseY) {
        if (mouseX < listLeft() || mouseX >= listLeft() + rowWidth() || mouseY < listTop() || mouseY >= listBottom()) {
            return -1;
        }
        int row = (int) ((mouseY - listTop() + scroll) / ROW_HEIGHT);
        return row >= 0 && row < entryCount() ? row : -1;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= listLeft() && mouseX < listRight() && mouseY >= listTop() && mouseY < listBottom()) {
            scroll = (int) Math.max(0, Math.min(maxScroll(), scroll - scrollY * ROW_HEIGHT / 2));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * The list is hit-tested before the widgets: {@code AbstractContainerScreen.mouseClicked} returns true for every
     * click in 1.21.1 (it ends with {@code lastClickButton = button; return true}), so a delegation-first order would
     * make row selection and the scrollbar unreachable. The list area holds no widget, so checking it first cannot
     * shadow Buy or Back.
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int row = rowAt(mouseX, mouseY);
            if (row >= 0) {
                selectedKey = menu.clientCatalog().entries().get(row).key();
                return true;
            }
            if (maxScroll() > 0 && mouseX >= listRight() - SCROLLBAR_WIDTH && mouseX < listRight() && mouseY >= listTop() && mouseY < listBottom()) {
                scrollTo(mouseY);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && maxScroll() > 0 && mouseX >= listRight() - SCROLLBAR_WIDTH - 4 && mouseX < listRight() + 4) {
            scrollTo(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    private void scrollTo(double mouseY) {
        double ratio = (mouseY - listTop()) / LIST_HEIGHT;
        scroll = (int) Math.max(0, Math.min(maxScroll(), Math.round(ratio * maxScroll())));
    }

    // ---- rendering ----

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int row = rowAt(mouseX, mouseY);
        if (row >= 0) {
            renderRowTooltip(graphics, menu.clientCatalog().entries().get(row), mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        drawPanel(graphics, leftPos, topPos, imageWidth, imageHeight);
        int left = listLeft();
        int top = listTop();
        int right = listRight();
        int bottom = listBottom();
        graphics.fill(left - 1, top - 1, right + 1, bottom + 1, PANEL_DARK);
        graphics.fill(left, top, right, bottom, LIST_BODY);
        ShopCatalogPayload catalog = menu.clientCatalog();
        if (catalog == null || catalog.entries().isEmpty()) {
            return;
        }
        int width = rowWidth();
        int hovered = rowAt(mouseX, mouseY);
        graphics.enableScissor(left, top, left + width, bottom);
        List<ShopCatalogPayload.Entry> entries = catalog.entries();
        for (int i = 0; i < entries.size(); i++) {
            int y = top + i * ROW_HEIGHT - scroll;
            if (y + ROW_HEIGHT <= top || y >= bottom) {
                continue;
            }
            ShopCatalogPayload.Entry entry = entries.get(i);
            boolean selected = entry.key().equals(selectedKey);
            int background = selected ? ROW_SELECTED : i == hovered ? ROW_HOVER : (i % 2 == 1 ? ROW_ODD : LIST_BODY);
            graphics.fill(left, y, left + width, y + ROW_HEIGHT, background);
            graphics.fill(left, y + ROW_HEIGHT - 1, left + width, y + ROW_HEIGHT, PANEL_DARK);
            if (!entry.icon().isEmpty()) {
                graphics.renderFakeItem(entry.icon(), left + 3, y + 4);
                graphics.renderItemDecorations(font, entry.icon(), left + 3, y + 4);
            }
            int textX = left + 24;
            String price = Money.format(entry.priceCents(), CommonConfig.currencySymbol());
            int priceWidth = font.width(price);
            int nameWidth = left + width - 4 - priceWidth - 4 - textX;
            graphics.drawString(font, font.plainSubstrByWidth(entry.name(), nameWidth), textX, y + 3, TEXT, false);
            graphics.drawString(font, price, left + width - 4 - priceWidth, y + 3, entry.available() && affordable(entry) ? TEXT_GOLD : TEXT_GREY, false);
            Component status = statusOf(entry);
            graphics.drawString(font, font.plainSubstrByWidth(status.getString(), left + width - 4 - textX), textX, y + 13, statusColor(entry), false);
        }
        graphics.disableScissor();
        int max = maxScroll();
        if (max > 0) {
            int trackX = right - SCROLLBAR_WIDTH;
            graphics.fill(trackX, top, right, bottom, TRACK);
            int thumbHeight = Math.max(8, LIST_HEIGHT * LIST_HEIGHT / (entries.size() * ROW_HEIGHT));
            int thumbY = top + (int) ((long) (LIST_HEIGHT - thumbHeight) * scroll / max);
            graphics.fill(trackX + 1, thumbY, right - 1, thumbY + thumbHeight, THUMB);
            graphics.fill(trackX + 1, thumbY + thumbHeight - 1, right - 1, thumbY + thumbHeight, PANEL_DARK);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        String symbol = CommonConfig.currencySymbol();
        graphics.drawString(font, title, MARGIN, 6, TEXT_DIM, false);
        Component balance = ClientBalance.synced()
                ? Component.translatable("gui.consortium.terminal.balance", Money.format(ClientBalance.balance(), symbol))
                : Component.translatable("gui.consortium.shop.balance_unknown");
        graphics.drawString(font, balance, imageWidth - MARGIN - font.width(balance), 6, ClientBalance.synced() ? TEXT_GOLD : TEXT_GREY, false);
        ShopCatalogPayload catalog = menu.clientCatalog();
        if (catalog == null) {
            drawCentered(graphics, Component.translatable("gui.consortium.terminal.waiting"), LIST_Y + LIST_HEIGHT / 2 - 4, TEXT_MUTED);
        } else if (catalog.entries().isEmpty()) {
            drawCentered(graphics, Component.translatable("gui.consortium.shop.empty"), LIST_Y + LIST_HEIGHT / 2 - 4, TEXT_MUTED);
        }
        // The status line: a pending purchase, else the last result (wrapped on two lines), else the entry count.
        ShopResultPayload result = menu.clientResult();
        if (pendingTicks > 0) {
            graphics.drawString(font, Component.translatable("gui.consortium.shop.pending"), MARGIN, STATUS_Y, TEXT_MUTED, false);
        } else if (result != null) {
            List<FormattedCharSequence> lines = font.split(result.message(), imageWidth - 2 * MARGIN);
            for (int i = 0; i < Math.min(STATUS_LINES, lines.size()); i++) {
                graphics.drawString(font, lines.get(i), MARGIN, STATUS_Y + i * font.lineHeight, result.ok() ? TEXT_GREEN : TEXT_RED, false);
            }
        } else if (catalog != null && !catalog.entries().isEmpty()) {
            graphics.drawString(font, Component.translatable("gui.consortium.shop.entries", catalog.entries().size()), MARGIN, STATUS_Y, TEXT_MUTED, false);
        }
    }

    /** The second line of a row: the server's state, or the client's affordability for an available entry. */
    private Component statusOf(ShopCatalogPayload.Entry entry) {
        return switch (entry.state()) {
            case LOCKED_STAGE -> Component.translatable("gui.consortium.shop.locked_stage");
            case LOCKED_PHASE -> Component.translatable("gui.consortium.shop.locked_phase", entry.phase());
            case LIMIT_REACHED -> Component.translatable("gui.consortium.shop.limit_reached", entry.dailyLimit());
            case AVAILABLE -> {
                if (ClientBalance.synced() && !affordable(entry)) {
                    yield Component.translatable("gui.consortium.shop.unaffordable");
                }
                yield entry.dailyLimit() > 0
                        ? Component.translatable("gui.consortium.shop.limit_left", entry.boughtToday(), entry.dailyLimit())
                        : Component.translatable("gui.consortium.shop.available");
            }
        };
    }

    private int statusColor(ShopCatalogPayload.Entry entry) {
        if (entry.state() != ShopState.AVAILABLE) {
            return TEXT_GREY;
        }
        if (!ClientBalance.synced()) {
            return TEXT_MUTED;
        }
        if (!affordable(entry)) {
            return TEXT_RED;
        }
        return entry.dailyLimit() > 0 ? TEXT_MUTED : TEXT_GREEN;
    }

    /**
     * Item entries show the sold stack's tooltip; command entries their name. Both add the description, the price and
     * the status line in full (the row clips it to its width), plus the reset note for entries with a daily limit.
     */
    private void renderRowTooltip(GuiGraphics graphics, ShopCatalogPayload.Entry entry, int mouseX, int mouseY) {
        List<Component> tip = new ArrayList<>();
        if (entry.sellsItem() && !entry.icon().isEmpty()) {
            tip.addAll(Screen.getTooltipFromItem(minecraft, entry.icon()));
            if (entry.icon().getCount() > 1) {
                tip.add(Component.literal("x" + entry.icon().getCount()).withStyle(ChatFormatting.GRAY));
            }
        } else {
            tip.add(Component.literal(entry.name()).withStyle(ChatFormatting.WHITE));
        }
        if (!entry.description().isEmpty()) {
            tip.add(Component.literal(entry.description()).withStyle(ChatFormatting.GRAY));
        }
        tip.add(Component.literal(Money.format(entry.priceCents(), CommonConfig.currencySymbol())).withStyle(ChatFormatting.GOLD));
        tip.add(statusOf(entry).copy().withStyle(ChatFormatting.GRAY));
        if (entry.dailyLimit() > 0) {
            tip.add(Component.translatable("gui.consortium.shop.limit_reset").withStyle(ChatFormatting.DARK_GRAY));
        }
        graphics.renderComponentTooltip(font, tip, mouseX, mouseY);
    }

    private void drawCentered(GuiGraphics graphics, Component text, int y, int color) {
        graphics.drawString(font, text, (imageWidth - font.width(text)) / 2, y, color, false);
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
}
