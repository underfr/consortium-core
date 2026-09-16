package org.consortium.core.terminal;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the {@code contribute_command} template of the server config, run once per delivered item id:
 * {@code {item}} (registry id), {@code {count}} (integer), {@code {player}} (name), {@code {uuid}}, {@code {tx}}.
 * Unknown placeholders are left as is; a leading slash is stripped because the command is dispatched without one.
 */
public final class CommandTemplate {
    /** Matches {@code /consortium contribute <item> <count>} of the pack's KubeJS phase engine (consortium_commands.js). */
    public static final String DEFAULT_CONTRIBUTE_COMMAND = "consortium contribute {item} {count}";

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
}
