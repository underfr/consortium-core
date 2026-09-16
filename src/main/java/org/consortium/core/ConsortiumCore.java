package org.consortium.core;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
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
import org.consortium.core.compat.ChaptersBridge;
import org.consortium.core.compat.FtbTeamsBridge;
import org.consortium.core.compat.LuckPermsBridge;
import org.consortium.core.compat.VanishBridge;
import org.consortium.core.config.CommonConfig;
import org.consortium.core.config.ServerConfig;
import org.consortium.core.identity.LoginHooks;
import org.consortium.core.network.ConsortiumNetwork;
import org.consortium.core.presence.PresenceHooks;
import org.consortium.core.presence.PresenceService;
import org.consortium.core.presence.PresenceTicker;
import org.consortium.core.pricing.PriceTable;
import org.consortium.core.pricing.PriceTableLoader;
import org.consortium.core.shop.ShopCatalog;
import org.consortium.core.shop.ShopCatalogLoader;
import org.consortium.core.shop.ShopMenu;
import org.consortium.core.shop.ShopService;
import org.consortium.core.terminal.DeliveryTerminalBlock;
import org.consortium.core.terminal.DeliveryTerminalBlockEntity;
import org.consortium.core.terminal.DeliveryTerminalItem;
import org.consortium.core.terminal.DeliveryTerminalMenu;
import org.consortium.core.terminal.DisplayPanelBlock;
import org.consortium.core.terminal.DisplayPanelBlockEntity;
import org.consortium.core.terminal.DisplayPanelItem;
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
 * payloads, the Delivery Station (terminal block entity plus Display Panels) and the quota board the engine
 * publishes, the Chapters guards, the party stage copy, the shop (catalogue datapack, menu, purchase) and the presence
 * module of v0.3 (rank prefixes in chat and in the tab list, tab header and footer, MOTD); the screens, the HUD, the
 * board renderer and the client config live behind {@link ConsortiumCoreClient}. Optional integrations (KubeJS, FTB
 * Teams, FTB Quests, Chapters, SDLink, LuckPerms) are compile-time dependencies reached only through bridge classes
 * behind {@link ModList#isLoaded(String)} checks; Vanishmod is reached through one method handle. No class of any of
 * them appears in a signature of this class.
 */
@Mod(ConsortiumCore.MOD_ID)
public final class ConsortiumCore {
    public static final String MOD_ID = "consortium";
    public static final Logger LOGGER = LoggerFactory.getLogger("Consortium Core");

    /** One price table per JVM: the datapack loader fills it, the runtime merges it, commands read it. */
    public static final PriceTable PRICES = new PriceTable();
    /** One shop catalogue per JVM (v0.2, 5.1): the datapack loader fills it, {@code TagsUpdatedEvent} audits it. */
    public static final ShopCatalog SHOP = new ShopCatalog();

    // ---- registries (specification 5) ----
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);

    /** Strength 3.5, blast resistance 1200, pickaxe required, pushes nothing: a fixture, not a machine. */
    public static final DeferredBlock<DeliveryTerminalBlock> DELIVERY_TERMINAL = BLOCKS.registerBlock("delivery_terminal",
            DeliveryTerminalBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(3.5F, 1200.0F).requiresCorrectToolForDrops()
                    .pushReaction(PushReaction.BLOCK).sound(SoundType.METAL));
    public static final DeferredItem<DeliveryTerminalItem> DELIVERY_TERMINAL_ITEM = ITEMS.registerItem("delivery_terminal",
            properties -> new DeliveryTerminalItem(DELIVERY_TERMINAL.get(), properties), new Item.Properties());
    /** The Display Panel of the Delivery Station screen (v0.2, 2.1): strength 2.0 / 6.0, pickaxe, pushes nothing. */
    public static final DeferredBlock<DisplayPanelBlock> DISPLAY_PANEL = BLOCKS.registerBlock("display_panel",
            DisplayPanelBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(2.0F, 6.0F).requiresCorrectToolForDrops()
                    .pushReaction(PushReaction.BLOCK).sound(SoundType.METAL));
    public static final DeferredItem<DisplayPanelItem> DISPLAY_PANEL_ITEM = ITEMS.registerItem("display_panel",
            properties -> new DisplayPanelItem(DISPLAY_PANEL.get(), properties), new Item.Properties());
    public static final DeferredHolder<MenuType<?>, MenuType<DeliveryTerminalMenu>> DELIVERY_TERMINAL_MENU = MENUS.register("delivery_terminal",
            () -> IMenuTypeExtension.create(DeliveryTerminalMenu::new));
    /** The zero-slot shop menu (v0.2, 5.2). */
    public static final DeferredHolder<MenuType<?>, MenuType<ShopMenu>> SHOP_MENU = MENUS.register("shop",
            () -> IMenuTypeExtension.create(ShopMenu::new));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DeliveryTerminalBlockEntity>> DELIVERY_TERMINAL_BE =
            BLOCK_ENTITIES.register("delivery_terminal",
                    () -> BlockEntityType.Builder.of(DeliveryTerminalBlockEntity::new, DELIVERY_TERMINAL.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DisplayPanelBlockEntity>> DISPLAY_PANEL_BE =
            BLOCK_ENTITIES.register("display_panel",
                    () -> BlockEntityType.Builder.of(DisplayPanelBlockEntity::new, DISPLAY_PANEL.get()).build(null));

    public ConsortiumCore(IEventBus modBus, ModContainer container) {
        LOGGER.info("Consortium Core loaded (version {})", container.getModInfo().getVersion());
        container.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
        container.registerConfig(ModConfig.Type.COMMON, CommonConfig.SPEC);

        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        MENUS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(this::onRegisterPayloads);
        modBus.addListener(this::onBuildCreativeTabs);
        modBus.addListener(this::onConfigReloading);
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
        // v0.2 sections 3 and 4: each bridge registers nothing when its mod is absent.
        ChaptersBridge.install(bus);
        FtbTeamsBridge.install();
        // v0.3 presence (5.3, 5.4): NORMAL priority on purpose, Vanishmod wraps the tab name at LOW and FTB Essentials
        // sets its nickname at HIGHEST and its recording marker at LOWEST around this result.
        PresenceHooks presence = new PresenceHooks();
        bus.addListener(EventPriority.NORMAL, false, PlayerEvent.NameFormat.class, presence::onNameFormat);
        bus.addListener(EventPriority.NORMAL, false, PlayerEvent.TabListNameFormat.class, presence::onTabListNameFormat);
        bus.addListener(EventPriority.NORMAL, false, ServerChatEvent.class, presence::onServerChat);
        bus.addListener(presence::onPlayerLoggedIn);
        bus.addListener(presence::onPlayerLoggedOut);
        PresenceTicker ticker = new PresenceTicker();
        bus.addListener(ticker::onServerTickPost);

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
            event.accept(DISPLAY_PANEL_ITEM.get());
        }
    }

    private void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new PriceTableLoader(PRICES));
        // RegistryAccess extends HolderLookup.Provider: the shop loader parses item stacks with registry-aware ops.
        event.addListener(new ShopCatalogLoader(SHOP, event.getRegistryAccess()));
    }

    private void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            return;
        }
        PRICES.tagsBound();
        PRICES.rebuildIndex();
        // v0.2, 5.1: the price-table and Chapters-lock checks need the item index (a no-op at the first boot, where the
        // index only exists after ServerStartedEvent merged the prices: onServerStarted audits again). /reload lands here
        // with the index rebuilt, so viewers get the reloaded catalogue.
        SHOP.audit(PRICES);
        ShopService.refreshViewers();
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        CreditsCommand.register(event.getDispatcher());
        PricesCommand.register(event.getDispatcher());
        CcoreCommand.register(event.getDispatcher());
    }

    private void onServerStarted(ServerStartedEvent event) {
        ModList mods = ModList.get();
        LOGGER.info("Consortium Core starting with the server. Optional mods present: kubejs={}, ftbteams={}, ftbquests={}, chapters={}, sdlink={}, luckperms={}, vmod={}",
                mods.isLoaded("kubejs"), mods.isLoaded("ftbteams"), mods.isLoaded("ftbquests"), mods.isLoaded("chapters"), mods.isLoaded("sdlink"),
                mods.isLoaded(LuckPermsBridge.MOD_ID), mods.isLoaded(VanishBridge.MOD_ID));
        ConsortiumRuntime.start(event.getServer(), PRICES);
        SHOP.audit(PRICES);
        // v0.3: after the runtime, so the first MOTD already reads the board snapshot.
        try {
            PresenceService.start(event.getServer());
        } catch (Throwable t) {
            LOGGER.error("Presence module could not start: vanilla names, tab list and MOTD for this session", t);
        }
    }

    /**
     * Mod bus, any thread (the file watcher reloads a SERVER config off-thread): only a flag is set, the presence
     * ticker re-reads the {@code [presence]} values on the server thread.
     */
    private void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != ServerConfig.SPEC) {
            return;
        }
        PresenceService svc = PresenceService.get();
        if (svc != null) {
            svc.markConfigChanged();
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        ConsortiumRuntime rt = ConsortiumRuntime.get();
        if (rt != null) {
            LOGGER.info("Consortium Core stopping: ledger seq {}, {} accounts", rt.ledger.nextSeq(), rt.economy.accounts().size());
        }
    }

    private void onServerStopped(ServerStoppedEvent event) {
        PresenceService.stop();
        ConsortiumRuntime.stop();
        ChaptersBridge.resetCounters();
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
