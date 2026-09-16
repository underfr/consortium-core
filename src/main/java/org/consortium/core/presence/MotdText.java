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
}
