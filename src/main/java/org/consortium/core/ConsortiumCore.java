package org.consortium.core;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.consortium.core.command.CcoreCommand;
import org.consortium.core.command.CreditsCommand;
import org.consortium.core.command.Permissions;
import org.consortium.core.command.PricesCommand;
import org.consortium.core.config.CommonConfig;
import org.consortium.core.config.ServerConfig;
import org.consortium.core.identity.LoginHooks;
import org.consortium.core.network.ConsortiumNetwork;
import org.consortium.core.pricing.PriceTable;
import org.consortium.core.pricing.PriceTableLoader;
import org.consortium.core.terminal.DeliveryTerminalBlock;
import org.consortium.core.terminal.DeliveryTerminalItem;
import org.consortium.core.terminal.DeliveryTerminalMenu;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of Consortium Core, the server-authoritative economy kernel of The Consortium pack.
 *
 * <p>Division of responsibilities: the mod owns money, prices, the terminal, the ledger and the HUD; the KubeJS phase
 * engine of the pack owns phases, quotas, stages, charters and quests, and learns about deliveries through the
 * {@code contribute_command} follow-up. Here: ledger, accounts, identity and starting capital, price datapack and
 * market, the command trees ({@code /credits}, {@code /prices}, {@code /ccore}), the API and its events, monitoring
 * and the SDLink bridge, the server-side delivery service, the Delivery Terminal block, its menu and the network
 * payloads; the screen, the HUD and the client config live behind {@link ConsortiumCoreClient}. Optional
 * integrations (KubeJS, FTB Teams, FTB Quests, SDLink) are compile-time dependencies reached only through bridge
 * classes behind {@link ModList#isLoaded(String)} checks.
 */
@Mod(ConsortiumCore.MOD_ID)
public final class ConsortiumCore {
    public static final String MOD_ID = "consortium";
    public static final Logger LOGGER = LoggerFactory.getLogger("Consortium Core");

    /** One price table per JVM: the datapack loader fills it, the runtime merges it, commands read it. */
    public static final PriceTable PRICES = new PriceTable();

    // ---- registries (specification 5) ----
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MOD_ID);

    /** Strength 3.5, blast resistance 1200, pickaxe required, pushes nothing: a fixture, not a machine. */
    public static final DeferredBlock<DeliveryTerminalBlock> DELIVERY_TERMINAL = BLOCKS.registerBlock("delivery_terminal",
            DeliveryTerminalBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(3.5F, 1200.0F).requiresCorrectToolForDrops()
                    .pushReaction(PushReaction.BLOCK).sound(SoundType.METAL));
    public static final DeferredItem<DeliveryTerminalItem> DELIVERY_TERMINAL_ITEM = ITEMS.registerItem("delivery_terminal",
            properties -> new DeliveryTerminalItem(DELIVERY_TERMINAL.get(), properties), new Item.Properties());
    public static final DeferredHolder<MenuType<?>, MenuType<DeliveryTerminalMenu>> DELIVERY_TERMINAL_MENU = MENUS.register("delivery_terminal",
            () -> IMenuTypeExtension.create(DeliveryTerminalMenu::new));

    public ConsortiumCore(IEventBus modBus, ModContainer container) {
        LOGGER.info("Consortium Core loaded (version {})", container.getModInfo().getVersion());
        container.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
        container.registerConfig(ModConfig.Type.COMMON, CommonConfig.SPEC);

        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        MENUS.register(modBus);
        modBus.addListener(this::onRegisterPayloads);
        modBus.addListener(this::onBuildCreativeTabs);
        ConsortiumRuntime.addBalanceListener(ConsortiumNetwork::onBalanceChanged);

        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener(this::onAddReloadListeners);
        bus.addListener(this::onTagsUpdated);
        bus.addListener(this::onRegisterCommands);
        bus.addListener(Permissions::gather);
        bus.addListener(this::onServerStarted);
        bus.addListener(this::onServerStopping);
        bus.addListener(this::onServerStopped);
        bus.addListener(this::onPlayerLoggedIn);
        bus.addListener(this::onPlayerRespawn);
        bus.addListener(this::onPlayerChangedDimension);
        bus.addListener(this::onServerTickPost);

        PRICES.setDatapackListener(table -> {
            ConsortiumRuntime rt = ConsortiumRuntime.get();
            if (rt != null && rt.server.isSameThread()) {
                // /reload during play: merge and announce right away. Tags are rebound right after this apply,
                // TagsUpdatedEvent rebuilds the item index then.
                rt.mergePrices("datapack", "pack update", false);
            }
            // Otherwise the first apply of the boot: ServerStartedEvent merges once the saved data exists.
        });
    }

    private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        ConsortiumNetwork.register(event);
    }

    private void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(DELIVERY_TERMINAL_ITEM.get());
        }
    }

    private void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new PriceTableLoader(PRICES));
    }

    private void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            return;
        }
        PRICES.tagsBound();
        PRICES.rebuildIndex();
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        CreditsCommand.register(event.getDispatcher());
        PricesCommand.register(event.getDispatcher());
        CcoreCommand.register(event.getDispatcher());
    }

    private void onServerStarted(ServerStartedEvent event) {
        ModList mods = ModList.get();
        LOGGER.info("Consortium Core starting with the server. Optional mods present: kubejs={}, ftbteams={}, ftbquests={}, sdlink={}",
                mods.isLoaded("kubejs"), mods.isLoaded("ftbteams"), mods.isLoaded("ftbquests"), mods.isLoaded("sdlink"));
        ConsortiumRuntime.start(event.getServer(), PRICES);
    }

    private void onServerStopping(ServerStoppingEvent event) {
        ConsortiumRuntime rt = ConsortiumRuntime.get();
        if (rt != null) {
            LOGGER.info("Consortium Core stopping: ledger seq {}, {} accounts", rt.ledger.nextSeq(), rt.economy.accounts().size());
        }
    }

    private void onServerStopped(ServerStoppedEvent event) {
        ConsortiumRuntime.stop();
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        ConsortiumRuntime rt = ConsortiumRuntime.get();
        if (rt == null || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        try {
            LoginHooks.onLogin(rt, player);
        } catch (Throwable t) {
            LOGGER.error("Login hook failed for {}", player.getGameProfile().getName(), t);
        }
    }

    private void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ConsortiumNetwork.resync(player, "respawn");
        }
    }

    private void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ConsortiumNetwork.resync(player, "dimension");
        }
    }

    private void onServerTickPost(ServerTickEvent.Post event) {
        ConsortiumRuntime rt = ConsortiumRuntime.get();
        if (rt != null) {
            rt.scheduler.tick();
        }
    }
}
