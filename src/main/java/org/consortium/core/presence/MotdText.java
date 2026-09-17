package org.consortium.core.presence;

import java.util.List;

import org.consortium.core.presence.LegacyText.Span;

/**
 * Spans to the legacy section-sign string {@code MinecraftServer.setMotd} takes (v0.3, presence 5.5). The server
 * list renders a plain string: the 16 legacy colours, the format codes and two lines, nothing else. Hex colours are
 * dropped (the span falls back to the default colour through a reset) and counted so the service can warn once per
 * reload. Pure, no Minecraft class.
 */
public final class MotdText {
    /** The rendered text and how many hex colours were dropped on the way. */
    public record Rendered(String text, int droppedHex) {
    }

    private MotdText() {
    }

    /** Renders one line. Every span carries its full style prefix, so the output never depends on the previous line. */
    public static Rendered render(List<Span> spans) {
        StringBuilder sb = new StringBuilder();
        int dropped = 0;
        boolean first = true;
        for (Span span : spans) {
            if (span.text().isEmpty()) {
                continue;
            }
            if (span.legacyColor()) {
                sb.append(LegacyText.SECTION).append(span.color());
            } else if (span.hexColor()) {
                dropped++;
                sb.append(LegacyText.SECTION).append('r');
            } else if (!first) {
                sb.append(LegacyText.SECTION).append('r');
            }
            if (span.obfuscated()) {
                sb.append(LegacyText.SECTION).append('k');
            }
            if (span.bold()) {
                sb.append(LegacyText.SECTION).append('l');
            }
            if (span.strikethrough()) {
                sb.append(LegacyText.SECTION).append('m');
            }
            if (span.underline()) {
                sb.append(LegacyText.SECTION).append('n');
            }
            if (span.italic()) {
                sb.append(LegacyText.SECTION).append('o');
            }
            sb.append(span.text());
            first = false;
        }
        return new Rendered(sb.toString(), dropped);
    }

    /** Two rendered lines joined with the newline the server list splits on. */
    public static Rendered render(List<Span> line1, List<Span> line2) {
        Rendered a = render(line1);
        Rendered b = render(line2);
        return new Rendered(a.text() + "\n" + b.text(), a.droppedHex() + b.droppedHex());
    }

    /** The server list renders two lines: the rest is cut. */
    public static final int MAX_LINES = 2;

    /**
     * The lines of an expanded MOTD (v0.3.1): the two formats joined with a newline, every blank line dropped
     * ({@link LegacyText#dropBlankLines}), then capped at {@link #MAX_LINES}. Returns at least one (possibly empty)
     * line so the caller always has something to set.
     */
    public static List<String> lines(String expanded) {
        String kept = LegacyText.dropBlankLines(expanded);
        List<String> out = new java.util.ArrayList<>();
        for (String line : kept.split("\n", -1)) {
            if (out.size() == MAX_LINES) {
                break;
            }
            out.add(line);
        }
        if (out.isEmpty()) {
            out.add("");
        }
        return out;
    }

    /** Renders the lines of {@link #lines} as one legacy string (one or two lines). */
    public static Rendered renderLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        int dropped = 0;
        for (int i = 0; i < lines.size() && i < MAX_LINES; i++) {
            Rendered r = render(LegacyText.parse(lines.get(i)));
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(r.text());
            dropped += r.droppedHex();
        }
        return new Rendered(sb.toString(), dropped);
    }
}
