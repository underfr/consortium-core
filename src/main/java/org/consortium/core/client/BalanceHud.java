package org.consortium.core.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.config.ClientConfig;
import org.consortium.core.config.CommonConfig;
import org.consortium.core.economy.Money;

/**
 * The credits line (specification 8): "Credits: 412.55 CC" in the configured corner (top-left by default), drawn
 * only in game (no screen open, GUI not hidden) and only once the session received its first {@code BalanceSync}.
 * After a change the delta shows next to it in green or red for a few seconds.
 */
public final class BalanceHud {
    public static final ResourceLocation LAYER_ID = ResourceLocation.fromNamespaceAndPath(ConsortiumCore.MOD_ID, "balance");
    private static final int GOLD = 0xFFD700;
    private static final int GREEN = 0x55FF55;
    private static final int RED = 0xFF5555;

    private BalanceHud() {
    }

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.screen != null || mc.getDebugOverlay().showDebugScreen()) {
            return;
        }
        if (!ClientConfig.hudEnabled() || !ClientBalance.synced()) {
            return;
        }
        String symbol = CommonConfig.currencySymbol();
        Component text = Component.translatable("gui.consortium.hud.credits", Money.format(ClientBalance.balance(), symbol));
        int width = mc.font.width(text);
        String deltaText = null;
        long delta = ClientBalance.delta();
        long showFor = ClientConfig.hudDeltaSeconds() * 1000L;
        if (delta != 0 && showFor > 0 && ClientBalance.deltaAge() <= showFor) {
            deltaText = Money.formatSigned(delta);
        }
        int deltaWidth = deltaText == null ? 0 : mc.font.width(deltaText) + 4;
        int y = ClientConfig.hudOffsetY();
        int x;
        if (ClientConfig.hudCorner() == ClientConfig.Corner.TOP_RIGHT) {
            x = graphics.guiWidth() - ClientConfig.hudOffsetX() - width - deltaWidth;
        } else {
            x = ClientConfig.hudOffsetX();
        }
        graphics.drawString(mc.font, text, x, y, GOLD, true);
        if (deltaText != null) {
            graphics.drawString(mc.font, deltaText, x + width + 4, y, delta > 0 ? GREEN : RED, true);
        }
    }

    /** Chat feedback of the toggle key. */
    public static Component toggleMessage(boolean enabled) {
        return Component.literal("Credits HUD " + (enabled ? "shown" : "hidden")).withStyle(ChatFormatting.GRAY);
    }
}
