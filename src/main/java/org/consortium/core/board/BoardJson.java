package org.consortium.core.board;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Validation of a quota board publish (specification v0.2, 2.4). Minecraft-free: the item check is an
 * {@link IconResolver} the caller supplies (the registry on the server, a fixed set in the unit tests).
 *
 * <p>The engine data is trusted, so a publish is <b>refused</b> only when its structure is wrong (not an object,
 * too long, a phase, day or days outside its range) and every line field <b>fails soft</b>: a bad label is
 * truncated, a bad icon becomes the barrier, a line without a usable key or target is dropped with one warning. One
 * bad line must never freeze the board on a stale snapshot for the rest of the season.
 */
public final class BoardJson {
    /** Longest publish accepted, in characters (about 6 KiB is the realistic maximum for 32 lines). */
    public static final int MAX_LENGTH = 16 * 1024;
    public static final int MAX_PHASE = 99;
    public static final int MAX_DAY = 9999;

    /** Maps an icon id to a registered item id, or to the fallback when unknown or unparseable. */
    @FunctionalInterface
    public interface IconResolver {
        String FALLBACK = "minecraft:barrier";

        /** The canonical id of a registered item, or {@code null} when {@code icon} names none. */
        String resolve(String icon);
    }

    /** Either a snapshot or the reason of the refusal (never both). */
    public record Result(BoardSnapshot snapshot, String refusal) {
        public boolean accepted() {
            return snapshot != null;
        }

        static Result refused(String reason) {
            return new Result(null, reason);
        }
    }

    private BoardJson() {
    }

    /**
     * @param json  the engine's payload
     * @param icons the item check
     * @param warn  receives one message per soft failure (the caller deduplicates and rate-limits)
     */
    public static Result parse(String json, IconResolver icons, Consumer<String> warn) {
        if (json == null) {
            return Result.refused("null payload");
        }
        if (json.length() > MAX_LENGTH) {
            return Result.refused("payload of " + json.length() + " characters exceeds " + MAX_LENGTH);
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return Result.refused("not valid JSON: " + e.getMessage());
        }
        if (root == null || !root.isJsonObject()) {
            return Result.refused("payload is not a JSON object");
        }
        JsonObject o = root.getAsJsonObject();

        Long phase = integer(o.get("phase"));
        if (phase == null || phase < 0 || phase > MAX_PHASE) {
            return Result.refused("phase must be an integer in 0.." + MAX_PHASE + ", got " + describe(o.get("phase")));
        }
        Long day = integer(o.get("day"));
        if (day == null || day < 0 || day > MAX_DAY) {
            return Result.refused("day must be an integer in 0.." + MAX_DAY + ", got " + describe(o.get("day")));
        }
        Long days = integer(o.get("days"));
        if (days == null || days < 1 || days > MAX_DAY) {
            return Result.refused("days must be an integer in 1.." + MAX_DAY + ", got " + describe(o.get("days")));
        }

        String name = text(o.get("name"), BoardSnapshot.MAX_NAME);
        if (name.isEmpty()) {
            name = "Phase " + phase;
        }
        double completion = 0;
        Double c = number(o.get("completion"));
        if (c != null) {
            completion = Math.max(0.0, Math.min(1.0, c));
        }
        boolean complete = bool(o.get("complete"));

        List<BoardSnapshot.Line> lines = new ArrayList<>();
        JsonElement rawLines = o.get("lines");
        if (rawLines != null && !rawLines.isJsonNull()) {
            if (!rawLines.isJsonArray()) {
                warn.accept("lines is not an array, no line kept");
            } else {
                JsonArray array = rawLines.getAsJsonArray();
                if (array.size() > BoardSnapshot.MAX_LINES) {
                    warn.accept(array.size() + " lines published, only the first " + BoardSnapshot.MAX_LINES + " are kept");
                }
                int count = Math.min(array.size(), BoardSnapshot.MAX_LINES);
                for (int i = 0; i < count; i++) {
                    BoardSnapshot.Line line = parseLine(array.get(i), i, icons, warn);
                    if (line != null) {
                        lines.add(line);
                    }
                }
            }
        }
        BoardSnapshot.Event event = parseEvent(o.get("event"), warn);
        return new Result(new BoardSnapshot((int) (long) phase, name, (int) (long) day, (int) (long) days, completion, complete, lines, event), null);
    }

    /**
     * The optional {@code event} object (v0.3.1, EVENTS.md 8): missing or null = no event; not an object = one warning
     * and no event; an empty name drops the object with a warning; {@code detail} is optional; {@code seconds_left}
     * is clamped to 0..{@link BoardSnapshot#MAX_EVENT_SECONDS} (missing or not an integer = 0, no countdown).
     */
    static BoardSnapshot.Event parseEvent(JsonElement element, Consumer<String> warn) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonObject()) {
            warn.accept("event is not an object, no event shown");
            return null;
        }
        JsonObject e = element.getAsJsonObject();
        String name = text(e.get("name"), BoardSnapshot.MAX_EVENT_NAME);
        if (name.isEmpty()) {
            warn.accept("event has no usable name (a string of 1.." + BoardSnapshot.MAX_EVENT_NAME + " characters), no event shown");
            return null;
        }
        String detail = text(e.get("detail"), BoardSnapshot.MAX_EVENT_DETAIL);
        int seconds = 0;
        Long raw = integer(e.get("seconds_left"));
        if (raw != null) {
            seconds = (int) Math.max(0L, Math.min((long) BoardSnapshot.MAX_EVENT_SECONDS, raw));
        }
        return new BoardSnapshot.Event(name, detail, seconds);
    }

    private static BoardSnapshot.Line parseLine(JsonElement element, int index, IconResolver icons, Consumer<String> warn) {
        if (element == null || !element.isJsonObject()) {
            warn.accept("line " + index + " is not an object, dropped");
            return null;
        }
        JsonObject l = element.getAsJsonObject();
        String key = string(l.get("key"));
        if (key == null || key.isEmpty() || key.length() > BoardSnapshot.MAX_KEY) {
            warn.accept("line " + index + " has no usable key (a string of 1.." + BoardSnapshot.MAX_KEY + " characters), dropped");
            return null;
        }
        Long target = integer(l.get("target"));
        if (target == null || target < 1) {
            warn.accept("line '" + key + "' has no target >= 1, dropped");
            return null;
        }
        long targetCapped = Math.min(target, BoardSnapshot.MAX_COUNT);
        long current = 0;
        Double rawCurrent = number(l.get("current"));
        if (rawCurrent != null) {
            current = (long) Math.max(0.0, Math.min((double) BoardSnapshot.MAX_COUNT, Math.floor(rawCurrent)));
        }
        String label = text(l.get("label"), BoardSnapshot.MAX_LABEL);
        if (label.isEmpty()) {
            label = key;
        }
        String icon = string(l.get("icon"));
        String resolved = null;
        if (icon != null && !icon.isEmpty() && icon.length() <= BoardSnapshot.MAX_ICON) {
            resolved = icons.resolve(icon);
        }
        if (resolved == null) {
            warn.accept("line '" + key + "' icon '" + (icon == null ? "" : icon) + "' is not a registered item, showing a barrier");
            resolved = IconResolver.FALLBACK;
        }
        return new BoardSnapshot.Line(key, label, resolved, current, targetCapped);
    }

    // ---- lenient accessors ----

    private static final BigDecimal LONG_MIN = BigDecimal.valueOf(Long.MIN_VALUE);
    private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);

    /** A JSON number with no fractional part, else null; a value beyond the long range saturates (the caller caps). */
    static Long integer(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (!p.isNumber()) {
            return null;
        }
        try {
            BigDecimal d = p.getAsBigDecimal();
            if (d.stripTrailingZeros().scale() > 0) {
                return null;
            }
            if (d.compareTo(LONG_MAX) > 0) {
                return Long.MAX_VALUE;
            }
            if (d.compareTo(LONG_MIN) < 0) {
                return Long.MIN_VALUE;
            }
            return d.longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            return null;
        }
    }

    /** A finite JSON number, else null. */
    static Double number(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            double d = e.getAsDouble();
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static boolean bool(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    /** A JSON string as is, else null. */
    static String string(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            return null;
        }
        return e.getAsString();
    }

    /** A JSON string trimmed and truncated, empty when missing or not a string. */
    static String text(JsonElement e, int max) {
        String s = string(e);
        if (s == null) {
            return "";
        }
        s = s.trim();
        return s.length() > max ? s.substring(0, max).trim() : s;
    }

    private static String describe(JsonElement e) {
        return e == null ? "nothing" : e.toString();
    }
}
