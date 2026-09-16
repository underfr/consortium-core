package org.consortium.core.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * {@code config/consortium-client.toml}: the credits HUD (specification 8). Registered by the client entry point
 * only; every getter falls back to the default while the file is not loaded so the HUD code never throws.
 */
public final class ClientConfig {
    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.BooleanValue HUD_ENABLED;
    private static final ModConfigSpec.EnumValue<Corner> HUD_CORNER;
    private static final ModConfigSpec.IntValue HUD_OFFSET_X;
    private static final ModConfigSpec.IntValue HUD_OFFSET_Y;
    private static final ModConfigSpec.IntValue HUD_DELTA_SECONDS;

    /** Screen corner the credits line is anchored to. */
    public enum Corner {
        TOP_LEFT,
        TOP_RIGHT
    }

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Credits line drawn in game (also flipped by the toggle key, J by default)").push("hud");
        HUD_ENABLED = b.comment("Show the credits line").define("enabled", true);
        HUD_CORNER = b.comment("Corner the line is anchored to").defineEnum("corner", Corner.TOP_LEFT);
        HUD_OFFSET_X = b.comment("Pixels from the anchored side").defineInRange("offset_x", 4, 0, 500);
        HUD_OFFSET_Y = b.comment("Pixels from the top").defineInRange("offset_y", 4, 0, 500);
        HUD_DELTA_SECONDS = b.comment("Seconds the last change (+87.68) stays visible next to the balance")
                .defineInRange("delta_seconds", 3, 0, 60);
        b.pop();
        SPEC = b.build();
    }

    private ClientConfig() {
    }

    private static boolean loaded() {
        return SPEC.isLoaded();
    }

    public static boolean hudEnabled() {
        return !loaded() || HUD_ENABLED.get();
    }

    /** Flips {@code hud.enabled} and writes the file; a no-op before the config is loaded. */
    public static boolean toggleHud() {
        if (!loaded()) {
            return true;
        }
        boolean next = !HUD_ENABLED.get();
        HUD_ENABLED.set(next);
        SPEC.save();
        return next;
    }

    public static Corner hudCorner() {
        return loaded() ? HUD_CORNER.get() : Corner.TOP_LEFT;
    }

    public static int hudOffsetX() {
        return loaded() ? HUD_OFFSET_X.get() : 4;
    }

    public static int hudOffsetY() {
        return loaded() ? HUD_OFFSET_Y.get() : 4;
    }

    public static int hudDeltaSeconds() {
        return loaded() ? HUD_DELTA_SECONDS.get() : 3;
    }
}
