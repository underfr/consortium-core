package org.consortium.core.presence;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code {token}} expansion of the presence formats (v0.3, presence 5.2). Pure: the resolver maps a token name to its
 * text (or null), unknown tokens stay verbatim, and the expansion is a single pass, so a value that itself contains
 * {@code {something}} is never expanded again. Colour codes are not interpreted here: the expanded string goes
 * through {@link LegacyText#parse(String)} afterwards, which is how {@code &} codes inside a LuckPerms prefix work.
 */
public final class Placeholders {
    /** Token names are lower-case words: {@code {phase_name}}, never {@code {Phase Name}}. */
    private static final Pattern TOKEN = Pattern.compile("\\{([a-z_]+)}");

    private Placeholders() {
    }

    /** Expands every known token; a null resolver value keeps the token text as typed. */
    public static String expand(String format, Function<String, String> resolver) {
        if (format == null || format.isEmpty()) {
            return "";
        }
        Matcher m = TOKEN.matcher(format);
        StringBuilder out = new StringBuilder(format.length() + 32);
        int last = 0;
        while (m.find()) {
            out.append(format, last, m.start());
            String value = resolver.apply(m.group(1));
            out.append(value != null ? value : m.group());
            last = m.end();
        }
        out.append(format, last, format.length());
        return out.toString();
    }

    /** The token names a format uses, in order of first appearance. */
    public static Set<String> tokens(String format) {
        Set<String> out = new LinkedHashSet<>();
        if (format == null) {
            return out;
        }
        Matcher m = TOKEN.matcher(format);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
