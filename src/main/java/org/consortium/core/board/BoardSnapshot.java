package org.consortium.core.board;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.annotation.Nullable;
import java.util.List;

/**
 * One accepted quota board publish (specification v0.2, 2.4): the phase, its name, the season day, the completion
 * and the quota lines the KubeJS engine pushed, plus (v0.3.1, EVENTS.md 8) the optional running event. Immutable,
 * Minecraft-free, shared by the server state, the saved data, the payload and the client. {@link #toJson()} is the
 * canonical form: two publishes with the same canonical string are the same snapshot, which is how the engine's
 * repeated publishes are deduplicated. A snapshot without an event serialises exactly as a 0.3.0 snapshot did.
 */
public record BoardSnapshot(int phase, String name, int day, int days, double completion, boolean complete, List<Line> lines,
                            @Nullable Event event) {
    /** Upper bounds of the validated fields (2.4); the payload codec and the renderer rely on them. */
    public static final int MAX_LINES = 32;
    public static final int MAX_NAME = 48;
    public static final int MAX_KEY = 64;
    public static final int MAX_LABEL = 48;
    public static final int MAX_ICON = 128;
    public static final long MAX_COUNT = 1L << 53;
    /** Bounds of the event object (v0.3.1): name, detail and the countdown (one week at most). */
    public static final int MAX_EVENT_NAME = 32;
    public static final int MAX_EVENT_DETAIL = 48;
    public static final int MAX_EVENT_SECONDS = 604_800;

    private static final Gson GSON = new Gson();

    public BoardSnapshot {
        lines = List.copyOf(lines);
    }

    /** The 0.3.0 form: no event. */
    public BoardSnapshot(int phase, String name, int day, int days, double completion, boolean complete, List<Line> lines) {
        this(phase, name, day, days, completion, complete, lines, null);
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

    /**
     * The running event the engine published next to the lines (EVENTS.md 8): {@code name} 1..32 characters,
     * {@code detail} 0..48, {@code secondsLeft} 0..604,800 where 0 means "no countdown" (a staff event with no
     * timer shows name and detail only) and any positive value counts down from the publish instant on every
     * client; once it ran out the band and the {@code {event}} token disappear, whatever the snapshot still says.
     */
    public record Event(String name, String detail, int secondsLeft) {
        public boolean hasCountdown() {
            return secondsLeft > 0;
        }

        /**
         * Seconds still to run {@code elapsedMs} after the publish, or -1 when this event has no countdown; 0 when it
         * ran out (the caller hides it then).
         */
        public long remainingSeconds(long elapsedMs) {
            if (!hasCountdown()) {
                return -1;
            }
            long elapsed = Math.max(0L, elapsedMs / 1000L);
            return Math.max(0L, secondsLeft - elapsed);
        }

        /** False when the countdown ran out {@code elapsedMs} after the publish; true for an event without a countdown. */
        public boolean visible(long elapsedMs) {
            return !hasCountdown() || remainingSeconds(elapsedMs) > 0;
        }

        /**
         * The {@code {event}} presence value (EVENTS.md 8): {@code "Ore Rush (41 min)"} while the countdown runs
         * (minutes rounded up), {@code "Ore Rush"} without a countdown, empty once the countdown ran out.
         */
        public String presenceText(long elapsedMs) {
            if (!visible(elapsedMs)) {
                return "";
            }
            if (!hasCountdown()) {
                return name;
            }
            long minutes = (remainingSeconds(elapsedMs) + 59) / 60;
            return name + " (" + minutes + " min)";
        }

        /** {@code "59:59"} or {@code "1:02:03"} for the board band. */
        public static String clock(long seconds) {
            long s = Math.max(0L, seconds);
            long h = s / 3600;
            long m = (s % 3600) / 60;
            long sec = s % 60;
            if (h > 0) {
                return h + ":" + (m < 10 ? "0" : "") + m + ":" + (sec < 10 ? "0" : "") + sec;
            }
            return m + ":" + (sec < 10 ? "0" : "") + sec;
        }
    }

    /** Canonical JSON string (field order fixed, no whitespace); {@code event} only when present. */
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
        if (event != null) {
            JsonObject e = new JsonObject();
            e.addProperty("name", event.name());
            e.addProperty("detail", event.detail());
            e.addProperty("seconds_left", event.secondsLeft());
            o.add("event", e);
        }
        return GSON.toJson(o);
    }
}
