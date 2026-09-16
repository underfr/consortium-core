package org.consortium.core.board;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * One accepted quota board publish (specification v0.2, 2.4): the phase, its name, the season day, the completion
 * and the quota lines the KubeJS engine pushed. Immutable, Minecraft-free, shared by the server state, the saved
 * data, the payload and the client. {@link #toJson()} is the canonical form: two publishes with the same canonical
 * string are the same snapshot, which is how the engine's repeated publishes are deduplicated.
 */
public record BoardSnapshot(int phase, String name, int day, int days, double completion, boolean complete, List<Line> lines) {
    /** Upper bounds of the validated fields (2.4); the payload codec and the renderer rely on them. */
    public static final int MAX_LINES = 32;
    public static final int MAX_NAME = 48;
    public static final int MAX_KEY = 64;
    public static final int MAX_LABEL = 48;
    public static final int MAX_ICON = 128;
    public static final long MAX_COUNT = 1L << 53;

    private static final Gson GSON = new Gson();

    public BoardSnapshot {
        lines = List.copyOf(lines);
    }

    /**
     * One quota line: {@code key} is the engine's quota line id, {@code icon} a registered item id (or
     * {@code minecraft:barrier}), {@code current} and {@code target} the delivered and required counts.
     */
    public record Line(String key, String label, String icon, long current, long target) {
        /** Completion ratio, above 1 when more than the target was delivered (the renderer clamps). */
        public double ratio() {
            return target <= 0 ? 1.0 : (double) current / (double) target;
        }

        public boolean done() {
            return current >= target;
        }
    }

    /** Canonical JSON string (field order fixed, no whitespace). */
    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("phase", phase);
        o.addProperty("name", name);
        o.addProperty("day", day);
        o.addProperty("days", days);
        o.addProperty("completion", completion);
        o.addProperty("complete", complete);
        JsonArray array = new JsonArray();
        for (Line line : lines) {
            JsonObject l = new JsonObject();
            l.addProperty("key", line.key());
            l.addProperty("label", line.label());
            l.addProperty("icon", line.icon());
            l.addProperty("current", line.current());
            l.addProperty("target", line.target());
            array.add(l);
        }
        o.add("lines", array);
        return GSON.toJson(o);
    }
}
