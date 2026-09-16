package org.consortium.core.compat;

import com.gabinx.chapters.api.ChaptersAPI;
import com.gabinx.chapters.event.InventoryAuditor;
import com.gabinx.chapters.stage.LockResolver;
import com.gabinx.chapters.stage.StageManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.consortium.core.ConsortiumCore;
import org.consortium.core.api.ConsortiumAPI;
import org.consortium.core.config.ServerConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The only class of the mod that references Chapters (specification v0.2, section 3): loaded through
 * {@link ChaptersBridge} and only when the mod is present. Two guards on the game bus, neither logging per event:
 *
 * <ul>
 * <li><b>Guard 1, placement</b>: {@code BlockEvent.EntityPlaceEvent} (received for {@code EntityMultiPlaceEvent}
 * too). A gated block placed by a real player is refused when {@code LockResolver.isLocked} says so; a
 * {@link FakePlayer} (Create deployers and such: no team, no stages) may place a gated block only when one of its
 * gating stages is {@code consortium:phase_k} with {@code k} at most the phase of the last quota board snapshot
 * ({@link GuardRules#unlockedByPhase}). Refused: the event is cancelled (NeoForge restores the captured blocks and
 * the held stack), the counter moves, a real player reads an action bar line.</li>
 * <li><b>Guard 2, instant audit</b>: one shared {@link ContainerListener} attached to the inventory menu at login and
 * respawn and to every menu a player opens. {@code slotChanged} costs one index read for an ungated item; a locked
 * one triggers {@code InventoryAuditor.auditNow} (Chapters' own drop of every locked stack) inside the same
 * {@code broadcastChanges()} as the click or the {@code /give}, with an action bar line at most once per player per
 * second. The cursor-carried stack is not audited (Chapters' rule and the cross-charter trade path).</li>
 * </ul>
 *
 * <p>The stage index is read through {@code StageManager.get()} at each use: it is an immutable snapshot replaced on
 * every datapack reload and KubeJS {@code defineStage} flush.
 */
final class ChaptersGuards {
    private static final long DROP_NOTICE_INTERVAL_MS = 1000L;
    /** Last "dropped" notice per online player (server thread only), entries removed at logout. */
    private static final Map<UUID, Long> DROP_NOTICES = new HashMap<>();
    private static final ContainerListener AUDIT = new ContainerListener() {
        @Override
        public void slotChanged(AbstractContainerMenu menu, int slotIndex, ItemStack stack) {
            onSlotChanged(menu, slotIndex, stack);
        }

        @Override
        public void dataChanged(AbstractContainerMenu menu, int dataSlotIndex, int value) {
        }
    };

    private ChaptersGuards() {
    }

    static void install(IEventBus bus) {
        bus.addListener(ChaptersGuards::onPlace);
        bus.addListener(ChaptersGuards::onLoggedIn);
        bus.addListener(ChaptersGuards::onRespawn);
        bus.addListener(ChaptersGuards::onContainerOpen);
        bus.addListener(ChaptersGuards::onLoggedOut);
        ConsortiumCore.LOGGER.info("Chapters guards installed: placement guard and instant inventory audit (server config [guards])");
    }

    // ---- queries (the shop) ----

    static boolean hasStage(ServerPlayer player, ResourceLocation stage) {
        return ChaptersAPI.hasStage(player, stage);
    }

    static boolean isLocked(ServerPlayer player, ItemStack stack) {
        return LockResolver.isLocked(player, stack);
    }

    static Set<ResourceLocation> gatingStages(Item item) {
        Set<ResourceLocation> stages = StageManager.get().itemStagesIndexView().get(BuiltInRegistries.ITEM.getKey(item));
        return stages == null ? Set.of() : stages;
    }

    // ---- guard 1: placement ----

    private static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !ServerConfig.placementGuard()) {
            return;
        }
        try {
            // For a Player the placed block is the new live state (the snapshot's own state is the replaced block).
            Item item = event.getPlacedBlock().getBlock().asItem();
            if (item == Items.AIR) {
                return;
            }
            Set<ResourceLocation> stages = StageManager.get().itemStagesIndexView().get(BuiltInRegistries.ITEM.getKey(item));
            if (stages == null || stages.isEmpty()) {
                return;
            }
            boolean fake = player instanceof FakePlayer;
            boolean locked = fake ? !unlockedForFakePlayer(stages) : LockResolver.isLocked(player, new ItemStack(item));
            if (!locked) {
                return;
            }
            event.setCanceled(true);
            ChaptersBridge.counters().placementRefused();
            if (!fake) {
                player.displayClientMessage(Component.translatable("consortium.guard.placement", item.getDescription()), true);
            }
        } catch (Throwable t) {
            ChaptersBridge.failure("placement guard", t);
        }
    }

    /** The fake-player rule: unlocked once a gating phase stage is at or below the board phase (0 before the first publish). */
    private static boolean unlockedForFakePlayer(Set<ResourceLocation> stages) {
        int boardPhase = ConsortiumAPI.boardPhase();
        for (ResourceLocation stage : stages) {
            if (GuardRules.unlockedByPhase(GuardRules.phaseOf(stage.getNamespace(), stage.getPath()), boardPhase)) {
                return true;
            }
        }
        return false;
    }

    // ---- guard 2: instant audit ----

    private static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer)) {
            player.inventoryMenu.addSlotListener(AUDIT);
        }
    }

    /** A respawn builds a new {@code ServerPlayer} with a new inventory menu. */
    private static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer)) {
            player.inventoryMenu.addSlotListener(AUDIT);
        }
    }

    /** Chests, machines, the terminal: the open menu wraps the player's inventory slots. */
    private static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer) && event.getContainer() != null) {
            event.getContainer().addSlotListener(AUDIT);
        }
    }

    private static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        DROP_NOTICES.remove(event.getEntity().getUUID());
    }

    private static void onSlotChanged(AbstractContainerMenu menu, int slotIndex, ItemStack stack) {
        if (stack.isEmpty() || slotIndex < 0 || slotIndex >= menu.slots.size() || !ServerConfig.instantAudit()) {
            return;
        }
        Slot slot = menu.getSlot(slotIndex);
        if (!(slot.container instanceof Inventory inventory) || !(inventory.player instanceof ServerPlayer player)
                || player instanceof FakePlayer) {
            return;
        }
        try {
            // The 99 percent case: one map read. LockResolver builds an EffectiveStages snapshot per call, team lookup
            // included, and must not run for ungated items (a QUICK_CRAFT drag reports 27 slots in one call).
            if (!StageManager.get().itemStagesIndexView().containsKey(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
                return;
            }
            if (!LockResolver.isLocked(player, stack)) {
                return;
            }
            InventoryAuditor.auditNow(player);
            ChaptersBridge.counters().auditTriggered();
            long now = System.currentTimeMillis();
            Long last = DROP_NOTICES.get(player.getUUID());
            if (last == null || now - last >= DROP_NOTICE_INTERVAL_MS) {
                DROP_NOTICES.put(player.getUUID(), now);
                player.displayClientMessage(Component.translatable("consortium.guard.dropped", stack.getHoverName()), true);
            }
        } catch (Throwable t) {
            ChaptersBridge.failure("instant audit", t);
        }
    }
}
