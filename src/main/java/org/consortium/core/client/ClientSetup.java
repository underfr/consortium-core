package org.consortium.core.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.config.ClientConfig;
import org.consortium.core.network.BalanceSyncPayload;
import org.consortium.core.network.DeliveryQuotePayload;
import org.consortium.core.terminal.DeliveryTerminalMenu;
import org.lwjgl.glfw.GLFW;

/**
 * Client wiring (specification 8 and 10): the terminal screen, the HUD layer, the toggle key and the clientbound
 * payload handlers. Only ever loaded by {@code ConsortiumCoreClient}.
 */
public final class ClientSetup {
    public static final KeyMapping TOGGLE_HUD = new KeyMapping("key.consortium.toggle_hud", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.consortium");

    private ClientSetup() {
    }

    // ---- mod bus ----

    public static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(ConsortiumCore.DELIVERY_TERMINAL_MENU.get(), DeliveryTerminalScreen::new);
    }

    public static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, BalanceHud.LAYER_ID, BalanceHud::render);
    }

    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_HUD);
    }

    // ---- game bus ----

    public static void onClientTick(ClientTickEvent.Post event) {
        while (TOGGLE_HUD.consumeClick()) {
            boolean enabled = ClientConfig.toggleHud();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(BalanceHud.toggleMessage(enabled), true);
            }
        }
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientBalance.reset();
    }

    // ---- payload handlers (main thread) ----

    public static void handleBalance(BalanceSyncPayload payload, IPayloadContext context) {
        ClientBalance.update(payload.balance(), payload.delta(), payload.reason());
    }

    public static void handleQuote(DeliveryQuotePayload payload, IPayloadContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.containerMenu instanceof DeliveryTerminalMenu menu) {
            menu.setClientQuote(payload.quote());
        }
    }
}
