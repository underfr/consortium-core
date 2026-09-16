package org.consortium.core.compat;

import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;
import org.consortium.core.ConsortiumCore;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Vanishmod awareness of the presence module (v0.3, presence 5.6): {@code {online}} counts players for which
 * {@code VanishUtil.isVanished(Player)} is false and the preview says whether a player is vanished. Vanishmod is
 * All Rights Reserved, so there is no compile dependency: one {@link MethodHandle} on
 * {@code redstonedubstep.mods.vanishmod.VanishUtil.isVanished(Player)} is resolved once behind
 * {@code ModList.isLoaded("vmod")}. Vanishmod itself removes vanished players from the tab list and the status ping.
 */
public final class VanishBridge {
    public static final String MOD_ID = "vmod";
    private static final String UTIL_CLASS = "redstonedubstep.mods.vanishmod.VanishUtil";

    private static Boolean present;
    private static boolean resolved;
    private static MethodHandle isVanished;
    private static long lastFailureLog;

    private VanishBridge() {
    }

    /** True when Vanishmod is loaded; evaluated once. */
    public static boolean available() {
        if (present == null) {
            present = ModList.get().isLoaded(MOD_ID);
        }
        return present;
    }

    /** True when Vanishmod hides this player; false without Vanishmod or when the call fails. */
    public static boolean isVanished(Player player) {
        if (player == null || !available()) {
            return false;
        }
        MethodHandle handle = handle();
        if (handle == null) {
            return false;
        }
        try {
            return (boolean) handle.invoke(player);
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastFailureLog > 60_000) {
                lastFailureLog = now;
                ConsortiumCore.LOGGER.warn("Vanishmod isVanished call failed for {}: {}", player.getGameProfile().getName(), t.toString());
            }
            return false;
        }
    }

    private static synchronized MethodHandle handle() {
        if (!resolved) {
            resolved = true;
            try {
                Class<?> util = Class.forName(UTIL_CLASS, true, VanishBridge.class.getClassLoader());
                isVanished = MethodHandles.publicLookup().findStatic(util, "isVanished", MethodType.methodType(boolean.class, Player.class));
                ConsortiumCore.LOGGER.info("Vanishmod bridge installed ({}.isVanished)", UTIL_CLASS);
            } catch (Throwable t) {
                ConsortiumCore.LOGGER.warn("Vanishmod is loaded but {}.isVanished(Player) could not be resolved ({}): vanished players count as online",
                        UTIL_CLASS, t.toString());
            }
        }
        return isVanished;
    }
}
