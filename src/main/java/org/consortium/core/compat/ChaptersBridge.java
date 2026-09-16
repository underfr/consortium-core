package org.consortium.core.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import org.consortium.core.ConsortiumCore;

import java.util.Set;

/**
 * Chapters integration (specification v0.2, section 3): the two guards of PROGRESSION 2 and the stage queries the
 * shop needs. Every Chapters class is referenced only inside {@link ChaptersGuards}, which the JVM resolves only when
 * {@link #available()} is true (the {@link SdlinkBridge} pattern), so this class is safe on a server or client without
 * Chapters: the queries answer "not gated" and {@link #install(IEventBus)} registers nothing.
 *
 * <p>Failure policy: a Chapters call that throws is logged once per minute. The guards then fail open (the placement
 * or the click proceeds, Chapters' own one-second sweep still runs), while the shop queries fail closed
 * ({@link #hasStage} false, {@link #isLocked} true) so a broken stage lookup never sells a locked item.
 */
public final class ChaptersBridge {
    public static final String MOD_ID = "chapters";
    private static final long FAILURE_LOG_INTERVAL_MS = 60_000L;

    private static Boolean present;
    private static final GuardCounters COUNTERS = new GuardCounters();
    private static long lastFailureLog;

    private ChaptersBridge() {
    }

    /** True when the Chapters mod is loaded; evaluated once. */
    public static boolean available() {
        if (present == null) {
            present = ModList.get().isLoaded(MOD_ID);
        }
        return present;
    }

    /** Mod construction: installs the placement guard and the instant audit on the game bus when Chapters is present. */
    public static void install(IEventBus bus) {
        if (!available()) {
            ConsortiumCore.LOGGER.info("Chapters is not present: the placement guard and the instant inventory audit are off");
            return;
        }
        try {
            ChaptersGuards.install(bus);
        } catch (Throwable t) {
            ConsortiumCore.LOGGER.error("Chapters guards could not be installed", t);
        }
    }

    /** Whether the player's effective stages (the FTB team's when FTB Teams is present) hold the stage; false without Chapters. */
    public static boolean hasStage(ServerPlayer player, ResourceLocation stage) {
        if (!available() || player == null || stage == null) {
            return false;
        }
        try {
            return ChaptersGuards.hasStage(player, stage);
        } catch (Throwable t) {
            failure("hasStage", t);
            return false;
        }
    }

    /**
     * Whether Chapters locks the stack for the player (gated by at least one stage the player holds none of); false
     * without Chapters or for an empty stack, true when the lookup throws (fail closed).
     */
    public static boolean isLocked(ServerPlayer player, ItemStack stack) {
        if (!available() || player == null || stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return ChaptersGuards.isLocked(player, stack);
        } catch (Throwable t) {
            failure("isLocked", t);
            return true;
        }
    }

    /** The stages gating the item in Chapters' index (tags already expanded); empty without Chapters or when ungated. */
    public static Set<ResourceLocation> gatingStages(Item item) {
        if (!available() || item == null) {
            return Set.of();
        }
        try {
            return ChaptersGuards.gatingStages(item);
        } catch (Throwable t) {
            failure("gatingStages", t);
            return Set.of();
        }
    }

    /** The guard counters of this server session (the weekly report reads them). */
    public static GuardCounters counters() {
        return COUNTERS;
    }

    /** {@code ServerStoppedEvent}: the next session counts from its own boot. */
    public static void resetCounters() {
        COUNTERS.reset();
    }

    /** One WARN per minute for a failing Chapters call, whatever the caller. */
    static void failure(String what, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastFailureLog > FAILURE_LOG_INTERVAL_MS) {
            lastFailureLog = now;
            ConsortiumCore.LOGGER.warn("Chapters call failed ({}): {}", what, t.toString());
        }
    }
}
