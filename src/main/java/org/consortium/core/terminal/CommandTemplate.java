package org.consortium.core.terminal;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a console command template: the {@code contribute_command} of the server config, run once per delivered
 * item id, and the {@code command} of a shop entry, run once per purchase. Placeholders: {@code {item}} (registry id),
 * {@code {count}} (integer), {@code {player}} (name), {@code {uuid}}, {@code {tx}}. Unknown placeholders are left as
 * is; a leading slash is stripped because the command is dispatched without one.
 *
 * <p>Placeholder safety (specification v0.2, 5.1): every known placeholder a template uses is validated by
 * {@link #invalidPlaceholder} before it is rendered, so a legacy name with a space or punctuation can never break
 * the argument parsing of the rendered command. Minecraft-free so the unit tests cover it directly.
 */
public final class CommandTemplate {
    /** Matches {@code /consortium contribute <item> <count>} of the pack's KubeJS phase engine (consortium_commands.js). */
    public static final String DEFAULT_CONTRIBUTE_COMMAND = "consortium contribute {item} {count}";

    /** A Minecraft profile name: 1 to 16 letters, digits or underscores (online mode always complies). */
    public static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    /** The canonical {@code 8-4-4-4-12} hex form of {@code UUID.toString()}, either case. */
    public static final Pattern UUID_CANONICAL = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /** A transaction id: the 8-character uuid prefix of {@code Transactions.newTxId()}. */
    public static final Pattern TX_ID = Pattern.compile("[0-9a-f]{8}");
    /** A namespaced registry id such as {@code minecraft:iron_ingot}. */
    public static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    public static final Pattern COUNT = Pattern.compile("[0-9]{1,9}");

    private static final Map<String, Pattern> RULES = Map.of(
            "player", PLAYER_NAME,
            "uuid", UUID_CANONICAL,
            "tx", TX_ID,
            "item", ITEM_ID,
            "count", COUNT);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");

    private CommandTemplate() {
    }

    public static String render(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(sb);
        String out = sb.toString().trim();
        return out.startsWith("/") ? out.substring(1) : out;
    }

    /** The placeholder names the template uses, in order of first appearance. */
    public static Set<String> placeholders(String template) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(template == null ? "" : template);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** True for the five placeholders the mod knows how to fill. */
    public static boolean isKnownPlaceholder(String name) {
        return RULES.containsKey(name);
    }

    /**
     * The first known placeholder the template uses whose value is missing or fails its rule, or {@code null} when
     * the template can be rendered safely. Unknown placeholders are not checked (they are left literal by
     * {@link #render}).
     */
    public static String invalidPlaceholder(String template, Map<String, String> values) {
        for (String name : placeholders(template)) {
            Pattern rule = RULES.get(name);
            if (rule == null) {
                continue;
            }
            String value = values == null ? null : values.get(name);
            if (value == null || !rule.matcher(value).matches()) {
                return name;
            }
        }
        return null;
    }
}
