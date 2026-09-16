package org.consortium.core.shop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.consortium.core.economy.Money;
import org.consortium.core.terminal.CommandTemplate;

import java.math.BigDecimal;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation of one shop catalogue entry (specification v0.2, 5.1), Minecraft-free: everything that is a plain
 * value is checked here and returned as a {@link Raw}; the loader then resolves the Minecraft parts (the sold
 * {@code ItemStack} through its codec, the icon item, the stage id). An entry that breaks a rule is skipped with the
 * reason, the other entries of its file load.
 */
public final class ShopJson {
    public static final Pattern KEY = Pattern.compile("[a-z0-9_]{1,32}");
    /** Loose registry id shape; the loader checks the registry itself. */
    /** Namespaced ids only: a bare "phase_3" would silently become minecraft:phase_3 and lock the entry forever. */
    public static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    public static final int MAX_NAME = 48;
    public static final int MAX_DESCRIPTION = 96;
    public static final int MAX_COMMAND = 256;
    public static final int MAX_DAILY_LIMIT = 999;
    public static final int MAX_PHASE = 99;
    /** Entries per catalogue payload (and per catalogue). */
    public static final int MAX_ENTRIES = 128;
    public static final long MIN_PRICE_CENTS = 1L;
    /** 1,000,000.00 credits. */
    public static final long MAX_PRICE_CENTS = 100_000_000L;
    /** The placeholders a shop command may use ({@code {item}} and {@code {count}} belong to the delivery follow-up). */
    public static final Set<String> COMMAND_PLACEHOLDERS = Set.of("player", "uuid", "tx");

    /**
     * A validated entry before the Minecraft parts are resolved. Exactly one of {@code item} (the JSON object of the
     * stack) and {@code command} is present; {@code icon} and {@code stage} are optional ids as written;
     * {@code dailyLimit} and {@code phase} are 0 when absent.
     */
    public record Raw(String key, String name, String description, JsonElement item, String command, String icon,
                      long priceCents, int dailyLimit, String stage, int phase) {
        public boolean isItem() {
            return item != null;
        }
    }

    /** Either a raw entry or the reason it was refused (never both). */
    public record Result(Raw raw, String refusal) {
        public boolean accepted() {
            return raw != null;
        }

        static Result refused(String reason) {
            return new Result(null, reason);
        }
    }

    private ShopJson() {
    }

    public static boolean validKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    /** Validates one {@code "key": {...}} pair of the {@code entries} object. */
    public static Result parse(String key, JsonElement value) {
        if (!validKey(key)) {
            return Result.refused("key '" + key + "' must match [a-z0-9_]{1,32}");
        }
        String at = "entry '" + key + "'";
        if (value == null || !value.isJsonObject()) {
            return Result.refused(at + " is not an object");
        }
        JsonObject o = value.getAsJsonObject();

        String name = string(o, "name");
        if (name == null || name.isEmpty() || name.length() > MAX_NAME) {
            return Result.refused(at + " needs a \"name\" of 1 to " + MAX_NAME + " characters");
        }
        String description = string(o, "description");
        if (o.has("description") && (description == null || description.length() > MAX_DESCRIPTION)) {
            return Result.refused(at + " \"description\" must be a string of at most " + MAX_DESCRIPTION + " characters");
        }
        if (description == null) {
            description = "";
        }

        boolean hasItem = o.has("item");
        boolean hasCommand = o.has("command");
        if (hasItem == hasCommand) {
            return Result.refused(at + " needs exactly one of \"item\" and \"command\"");
        }
        JsonElement item = null;
        String command = null;
        if (hasItem) {
            item = o.get("item");
            if (!item.isJsonObject()) {
                return Result.refused(at + " \"item\" must be an object with \"id\" (and optional \"count\", \"components\")");
            }
        } else {
            command = string(o, "command");
            if (command == null || command.isEmpty() || command.length() > MAX_COMMAND) {
                return Result.refused(at + " \"command\" must be a string of 1 to " + MAX_COMMAND + " characters");
            }
            String stripped = command.startsWith("/") ? command.substring(1).trim() : command;
            if (stripped.isEmpty()) {
                return Result.refused(at + " \"command\" is empty");
            }
            for (String placeholder : CommandTemplate.placeholders(command)) {
                if (!COMMAND_PLACEHOLDERS.contains(placeholder)) {
                    return Result.refused(at + " \"command\" uses the unknown placeholder {" + placeholder + "} (allowed: {player}, {uuid}, {tx})");
                }
            }
        }

        String icon = string(o, "icon");
        if (o.has("icon") && (icon == null || !RESOURCE_ID.matcher(icon).matches())) {
            return Result.refused(at + " \"icon\" must be a namespaced item id such as minecraft:map");
        }
        if (command != null && icon == null) {
            return Result.refused(at + " is a command entry and needs an \"icon\" item id");
        }

        long priceCents;
        JsonElement price = o.get("price");
        if (price == null || !price.isJsonPrimitive()) {
            return Result.refused(at + " needs a \"price\" in credits with at most two decimals");
        }
        try {
            priceCents = Money.parseCredits(price.getAsString());
        } catch (IllegalArgumentException e) {
            return Result.refused(at + " \"price\" " + e.getMessage());
        }
        if (priceCents < MIN_PRICE_CENTS || priceCents > MAX_PRICE_CENTS) {
            return Result.refused(at + " \"price\" must be between 0.01 and 1,000,000.00");
        }

        int dailyLimit = 0;
        if (o.has("daily_limit")) {
            Integer limit = integer(o.get("daily_limit"));
            if (limit == null || limit < 1 || limit > MAX_DAILY_LIMIT) {
                return Result.refused(at + " \"daily_limit\" must be an integer from 1 to " + MAX_DAILY_LIMIT);
            }
            dailyLimit = limit;
        }

        String stage = string(o, "stage");
        if (o.has("stage") && (stage == null || !RESOURCE_ID.matcher(stage).matches())) {
            return Result.refused(at + " \"stage\" must be a namespaced stage id such as consortium:phase_3");
        }

        int phase = 0;
        if (o.has("phase")) {
            Integer p = integer(o.get("phase"));
            if (p == null || p < 1 || p > MAX_PHASE) {
                return Result.refused(at + " \"phase\" must be an integer from 1 to " + MAX_PHASE);
            }
            phase = p;
        }
        return new Result(new Raw(key, name, description, item, command, icon, priceCents, dailyLimit, stage, phase), null);
    }

    private static String string(JsonObject o, String field) {
        JsonElement e = o.get(field);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            return null;
        }
        return e.getAsString().trim();
    }

    /** An integral JSON number ({@code 2}, {@code 2.0}) as an int, null otherwise. */
    private static Integer integer(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (!p.isNumber()) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(p.getAsString());
            if (value.stripTrailingZeros().scale() > 0) {
                return null;
            }
            return value.intValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            return null;
        }
    }
}
