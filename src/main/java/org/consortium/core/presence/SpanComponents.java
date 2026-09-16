package org.consortium.core.presence;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.consortium.core.presence.LegacyText.Span;

import java.util.List;

/**
 * {@link LegacyText} spans to a {@link MutableComponent} with real styles (v0.3, presence 5.8): legacy colours through
 * {@link ChatFormatting#getByCode(char)}, hex colours through {@link TextColor#fromRgb(int)}, formats as style flags.
 * Hover and click events are never added here: NeoForge's {@code decorateDisplayNameComponent} puts the entity hover
 * and the {@code /tell} click on the display name, and a tab entry has neither.
 */
public final class SpanComponents {
    private SpanComponents() {
    }

    /** Every span as a styled literal sibling of an empty root. */
    public static MutableComponent toComponent(List<Span> spans) {
        return toComponent(spans, '\0', null);
    }

    /**
     * Same, with every occurrence of {@code mark} inside a span replaced by a copy of {@code replacement} carrying the
     * span's style underneath its own (an earlier listener's colour on the name wins, the format's colour fills in).
     */
    public static MutableComponent toComponent(List<Span> spans, char mark, Component replacement) {
        MutableComponent out = Component.empty();
        for (Span span : spans) {
            Style style = styleOf(span);
            String text = span.text();
            if (replacement == null || text.indexOf(mark) < 0) {
                out.append(Component.literal(text).withStyle(style));
                continue;
            }
            int from = 0;
            int at;
            while ((at = text.indexOf(mark, from)) >= 0) {
                if (at > from) {
                    out.append(Component.literal(text.substring(from, at)).withStyle(style));
                }
                MutableComponent named = replacement.copy();
                named.setStyle(named.getStyle().applyTo(style)); // the name's own style wins, the span fills the gaps
                out.append(named);
                from = at + 1;
            }
            if (from < text.length()) {
                out.append(Component.literal(text.substring(from)).withStyle(style));
            }
        }
        return out;
    }

    /** The {@link Style} of one span; {@link Style#EMPTY} for a plain one. */
    public static Style styleOf(Span span) {
        Style style = Style.EMPTY;
        if (span.legacyColor()) {
            ChatFormatting colour = ChatFormatting.getByCode(span.color().charAt(0));
            if (colour != null && colour.isColor()) {
                style = style.withColor(colour);
            }
        } else if (span.hexColor()) {
            try {
                style = style.withColor(TextColor.fromRgb(Integer.parseInt(span.color().substring(1), 16)));
            } catch (NumberFormatException ignored) {
                // The parser only produces six hex digits; keep the default colour if that ever changes.
            }
        }
        if (span.bold()) {
            style = style.withBold(true);
        }
        if (span.italic()) {
            style = style.withItalic(true);
        }
        if (span.underline()) {
            style = style.withUnderlined(true);
        }
        if (span.strikethrough()) {
            style = style.withStrikethrough(true);
        }
        if (span.obfuscated()) {
            style = style.withObfuscated(true);
        }
        return style;
    }
}
