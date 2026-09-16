package org.consortium.core;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.consortium.core.client.ClientSetup;
import org.consortium.core.config.ClientConfig;
import org.consortium.core.network.ConsortiumNetwork;

/**
 * Client-only entry point (specification 10): the client config, the terminal screen, the HUD layer, the toggle key
 * and the clientbound payload handlers. Never loaded on a dedicated server, so nothing outside this class and the
 * {@code client} package may touch {@code net.minecraft.client}.
 */
@Mod(value = ConsortiumCore.MOD_ID, dist = Dist.CLIENT)
public final class ConsortiumCoreClient {
    public ConsortiumCoreClient(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        ConsortiumNetwork.setClientHandlers(ClientSetup::handleBalance, ClientSetup::handleQuote);

        modBus.addListener(ClientSetup::onRegisterScreens);
        modBus.addListener(ClientSetup::onRegisterLayers);
        modBus.addListener(ClientSetup::onRegisterKeys);

        NeoForge.EVENT_BUS.addListener(ClientSetup::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClientSetup::onLoggingOut);
        ConsortiumCore.LOGGER.info("Consortium Core client wiring ready (terminal screen, credits HUD, toggle key)");
    }
}
