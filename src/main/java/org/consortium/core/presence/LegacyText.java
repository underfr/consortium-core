package org.consortium.core.presence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Legacy colour code parser (v0.3, presence 5.1): turns {@code "&6[&eEngineer&6] &#ff8800name"} into a list of
 * {@link Span}s with the legacy semantics of the Minecraft renderer. {@code &x} and section-sign codes are accepted
 * (0 to 9 and a to f colours, k obfuscated, l bold, m strikethrough, n underline, o italic, r reset) plus
 * {@code &#rrggbb} hex colours. A colour code resets every format, {@code &r} resets everything, an unknown code and a
 * trailing marker stay verbatim. No Minecraft class here: the unit tests and {@link MotdText} use the spans directly,
 * {@link SpanComponents} turns them into styled components on the server.
 */
public final class LegacyText {
    /** The section sign Minecraft uses in legacy strings. */
    public static final char SECTION = '\u00a7';
    /** The alternate marker admins type in configs and LuckPerms prefixes. */
    public static final char AMPERSAND = '&';

    /** Legacy colour names ({@code ChatFormatting} names, lower case) to their one-character codes. */
    private static final Map<String, Character> COLOR_NAMES = Map.ofEntries(
            Map.entry("black", '0'), Map.entry("dark_blue", '1'), Map.entry("dark_green", '2'), Map.entry("dark_aqua", '3'),
            Map.entry("dark_red", '4'), Map.entry("dark_purple", '5'), Map.entry("gold", '6'), Map.entry("gray", '7'),
            Map.entry("dark_gray", '8'), Map.entry("blue", '9'), Map.entry("green", 'a'), Map.entry("aqua", 'b'),
            Map.entry("red", 'c'), Map.entry("light_purple", 'd'), Map.entry("yellow", 'e'), Map.entry("white", 'f'));

    /**
     * One run of text with one style. {@code color} is null (default colour), a one-character legacy code
     * ({@code "0"} to {@code "f"}) or a {@code "#rrggbb"} hex value (lower case).
     */
    public record Span(String text, String color, boolean bold, boolean italic, boolean underline, boolean strikethrough,
                       boolean obfuscated) {
        public static final Span EMPTY = new Span("", null, false, false, false, false, false);

        public boolean hexColor() {
            return color != null && color.startsWith("#");
        }

        public boolean legacyColor() {
            return color != null && !color.startsWith("#");
        }

        public boolean anyFormat() {
            return bold || italic || underline || strikethrough || obfuscated;
        }

        /** True when nothing styles this span. */
        public boolean plain() {
            return color == null && !anyFormat();
        }

        Span withText(String newText) {
            return new Span(newText, color, bold, italic, underline, strikethrough, obfuscated);
        }
    }

    private LegacyText() {
    }

    /** Parses a legacy string into spans; a null or empty input gives an empty list. Empty runs are never emitted. */
    public static List<Span> parse(String legacy) {
        List<Span> out = new ArrayList<>();
        if (legacy == null || legacy.isEmpty()) {
            return out;
        }
        Parser p = new Parser();
        int i = 0;
        int n = legacy.length();
        while (i < n) {
            char c = legacy.charAt(i);
            boolean marker = (c == AMPERSAND || c == SECTION) && i + 1 < n;
            if (marker && legacy.charAt(i + 1) == '#' && i + 8 <= n && isHex(legacy, i + 2, i + 8)) {
                p.flush(out);
                p.color = "#" + legacy.substring(i + 2, i + 8).toLowerCase(Locale.ROOT);
                p.resetFormats();
                i += 8;
                continue;
            }
            if (marker) {
                char code = Character.toLowerCase(legacy.charAt(i + 1));
                if (isColorCode(code)) {
                    p.flush(out);
                    p.color = String.valueOf(code);
                    p.resetFormats();
                    i += 2;
                    continue;
                }
                if (isFormatCode(code)) {
                    p.flush(out);
                    switch (code) {
                        case 'k' -> p.obfuscated = true;
                        case 'l' -> p.bold = true;
                        case 'm' -> p.strikethrough = true;
                        case 'n' -> p.underline = true;
                        case 'o' -> p.italic = true;
                        default -> p.resetAll();
                    }
                    i += 2;
                    continue;
                }
            }
            p.run.append(c);
            i++;
        }
        p.flush(out);
        return out;
    }

    /**
     * The style a run of codes ends in, as a span with an empty text: {@code styleOf("&f")} is white, {@code styleOf("")}
     * is plain. Text between the codes is ignored.
     */
    public static Span styleOf(String codes) {
        if (codes == null || codes.isEmpty()) {
            return Span.EMPTY;
        }
        List<Span> spans = parse(codes + "\u0000");
        return spans.isEmpty() ? Span.EMPTY : spans.get(spans.size() - 1).withText("");
    }

    /** The concatenated texts of the spans (every code stripped). */
    public static String plain(List<Span> spans) {
        StringBuilder sb = new StringBuilder();
        for (Span s : spans) {
            sb.append(s.text());
        }
        return sb.toString();
    }

    /** {@code plain(parse(legacy))}. */
    public static String strip(String legacy) {
        return plain(parse(legacy));
    }

    /**
     * Turns a {@code rank.color} meta value into the codes a format can prepend: a colour name such as {@code gold} or
     * {@code light_purple} gives {@code &6} or {@code &d}, a {@code &x} or section-sign code is normalised to {@code &x},
     * {@code #rrggbb} gives {@code &#rrggbb}. Anything else (a format code, an unknown name, a blank) gives an empty
     * string so the format renders without a colour instead of with a stray token.
     */
    public static String colorCodes(String rankColor) {
        if (rankColor == null) {
            return "";
        }
        String s = rankColor.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return "";
        }
        Character named = COLOR_NAMES.get(s);
        if (named != null) {
            return "&" + named;
        }
        if (s.length() == 2 && (s.charAt(0) == AMPERSAND || s.charAt(0) == SECTION) && isColorCode(s.charAt(1))) {
            return "&" + s.charAt(1);
        }
        if (s.length() == 1 && isColorCode(s.charAt(0))) {
            return "&" + s.charAt(0);
        }
        if (s.length() == 7 && s.charAt(0) == '#' && isHex(s, 1, 7)) {
            return "&" + s;
        }
        if (s.length() == 8 && (s.charAt(0) == AMPERSAND || s.charAt(0) == SECTION) && s.charAt(1) == '#' && isHex(s, 2, 8)) {
            return "&" + s.substring(1);
        }
        return "";
    }

    static boolean isColorCode(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }

    static boolean isFormatCode(char c) {
        return c == 'k' || c == 'l' || c == 'm' || c == 'n' || c == 'o' || c == 'r';
    }

    private static boolean isHex(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    /** Mutable parse state: the current style and the text run collected under it. */
    private static final class Parser {
        final StringBuilder run = new StringBuilder();
        String color;
        boolean bold;
        boolean italic;
        boolean underline;
        boolean strikethrough;
        boolean obfuscated;

        void resetFormats() {
            bold = false;
            italic = false;
            underline = false;
            strikethrough = false;
            obfuscated = false;
        }

        void resetAll() {
            color = null;
            resetFormats();
        }

        void flush(List<Span> out) {
            if (run.length() > 0) {
                out.add(new Span(run.toString(), color, bold, italic, underline, strikethrough, obfuscated));
                run.setLength(0);
            }
        }
    }
}
