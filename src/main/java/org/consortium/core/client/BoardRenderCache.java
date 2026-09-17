package org.consortium.core.client;

import net.minecraft.client.gui.Font;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.consortium.core.board.BoardSnapshot;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything the renderer needs for one terminal screen, built once per change (specification v0.2, 2.3) so
 * {@code render()} allocates nothing but pose pushes: the layout metrics, the header sequences with their widths,
 * the page labels, and per line the icon stack, the clipped label, the counter (or the percent when the counter
 * does not fit), the ratio and the colour state. Owned by {@link QuotaBoardRenderer} in a weak map keyed by the
 * client block entity; the {@link Stamp} says when the entry is stale.
 */
final class BoardRenderCache {
    /**
     * What the entry was built from: the client board generation, the entity's sync revision, its rectangle and
     * whether the event band is shown (a countdown that ran out removes the band and moves the body back up).
     */
    record Stamp(int generation, int syncRevision, int width, int height, Direction facing, boolean eventBand) {
        /** True when the entry built for this stamp is still current; avoids allocating a stamp per frame. */
        boolean matches(int generation, int syncRevision, int width, int height, Direction facing, boolean eventBand) {
            return this.generation == generation && this.syncRevision == syncRevision && this.width == width
                    && this.height == height && this.facing == facing && this.eventBand == eventBand;
        }

    }

    /** One quota line ready to draw. */
    record Row(ItemStack icon, FormattedCharSequence label, FormattedCharSequence counter, int counterWidth, double ratio,
               BoardLayout.State state) {
    }

    final Stamp stamp;
    final BoardLayout.Metrics metrics;
    /** Line 1 left: "Phase N: name" (or the title while waiting), already clipped. */
    final FormattedCharSequence title;
    /** Line 1 right: the completion percent or "Complete"; null while waiting. */
    @Nullable
    final FormattedCharSequence status;
    final int statusWidth;
    final int statusColor;
    /** Line 2 left: "Day d of n"; null while waiting. */
    @Nullable
    final FormattedCharSequence day;
    /** Line 2 right, one per page; empty when a single page. */
    final FormattedCharSequence[] pageLabels;
    final int[] pageLabelWidths;
    /** Centred body message ("Waiting for the Consortium", "No open quota"); null when there are rows. */
    @Nullable
    final FormattedCharSequence message;
    final int messageWidth;
    final List<Row> rows;
    /** The event band (v0.3.1): name and detail without the clock; null when no band is shown. */
    @Nullable
    private final BoardSnapshot.Event event;
    /** The band text rendered for {@link #eventSecond}; rebuilt when the countdown second changes. */
    @Nullable
    private FormattedCharSequence eventText;
    private long eventSecond = Long.MIN_VALUE;

    private BoardRenderCache(Stamp stamp, BoardLayout.Metrics metrics, FormattedCharSequence title, @Nullable FormattedCharSequence status,
                             int statusWidth, int statusColor, @Nullable FormattedCharSequence day, FormattedCharSequence[] pageLabels,
                             int[] pageLabelWidths, @Nullable FormattedCharSequence message, int messageWidth, List<Row> rows,
                             @Nullable BoardSnapshot.Event event) {
        this.stamp = stamp;
        this.metrics = metrics;
        this.title = title;
        this.status = status;
        this.statusWidth = statusWidth;
        this.statusColor = statusColor;
        this.day = day;
        this.pageLabels = pageLabels;
        this.pageLabelWidths = pageLabelWidths;
        this.message = message;
        this.messageWidth = messageWidth;
        this.rows = rows;
        this.event = event;
    }

    /**
     * The event band text for the given remaining seconds (-1 = no countdown), clipped to the width; the sequence is
     * rebuilt only when the second changes, so a frame inside the same second allocates nothing. Null without a band.
     */
    @Nullable
    FormattedCharSequence eventText(Font font, long remainingSeconds) {
        if (event == null) {
            return null;
        }
        if (eventText == null || remainingSeconds != eventSecond) {
            eventSecond = remainingSeconds;
            String text = BoardLayout.eventText(event.name(), event.detail(), remainingSeconds, metrics.variant() == BoardLayout.Variant.COMPACT);
            eventText = seq(font.plainSubstrByWidth(text, Math.max(0, metrics.widthPx() - 2 * BoardLayout.PAD)));
        }
        return eventText;
    }

    static BoardRenderCache build(Font font, Stamp stamp, @Nullable BoardSnapshot snapshot) {
        int lineCount = snapshot == null ? 0 : snapshot.lines().size();
        BoardSnapshot.Event event = snapshot == null || !stamp.eventBand() ? null : snapshot.event();
        BoardLayout.Metrics m = BoardLayout.compute(stamp.width(), stamp.height(), lineCount, event != null);
        int w = m.widthPx();
        int headerArea = w - 2 * BoardLayout.PAD;

        if (snapshot == null) {
            String waiting = Component.translatable("gui.consortium.board.waiting").getString();
            waiting = font.plainSubstrByWidth(waiting, headerArea);
            FormattedCharSequence title = seq(font.plainSubstrByWidth(Component.translatable("gui.consortium.board.title").getString(), headerArea));
            return new BoardRenderCache(stamp, m, title, null, 0, BoardLayout.TEXT, null, new FormattedCharSequence[0], new int[0],
                    seq(waiting), font.width(waiting), List.of(), null);
        }

        // Header line 1: the status on the right, the phase title clipped to what is left.
        String statusText;
        int statusColor;
        if (snapshot.complete()) {
            statusText = Component.translatable("gui.consortium.board.complete").getString();
            statusColor = BoardLayout.GREEN;
        } else {
            statusText = BoardLayout.percentText(snapshot.completion());
            statusColor = BoardLayout.State.of(snapshot.completion(), false).color;
        }
        int statusWidth = font.width(statusText);
        String titleText = Component.translatable("gui.consortium.board.phase", snapshot.phase(), snapshot.name()).getString();
        titleText = font.plainSubstrByWidth(titleText, Math.max(0, headerArea - statusWidth - 6));
        // Header line 2: the day on the left, the page on the right.
        String dayText = Component.translatable("gui.consortium.board.day", snapshot.day(), snapshot.days()).getString();
        FormattedCharSequence[] pageLabels = new FormattedCharSequence[m.pages() > 1 ? m.pages() : 0];
        int[] pageLabelWidths = new int[pageLabels.length];
        int pageWidthMax = 0;
        for (int p = 0; p < pageLabels.length; p++) {
            String text = Component.translatable("gui.consortium.board.page", p + 1, m.pages()).getString();
            pageLabels[p] = seq(text);
            pageLabelWidths[p] = font.width(text);
            pageWidthMax = Math.max(pageWidthMax, pageLabelWidths[p]);
        }
        dayText = font.plainSubstrByWidth(dayText, Math.max(0, headerArea - (pageLabels.length > 0 ? pageWidthMax + 6 : 0)));

        List<Row> rows = new ArrayList<>(lineCount);
        for (BoardSnapshot.Line line : snapshot.lines()) {
            rows.add(row(font, m, line));
        }
        FormattedCharSequence message = null;
        int messageWidth = 0;
        if (rows.isEmpty()) {
            String empty = font.plainSubstrByWidth(Component.translatable("gui.consortium.board.empty").getString(), headerArea);
            message = seq(empty);
            messageWidth = font.width(empty);
        }
        return new BoardRenderCache(stamp, m, seq(titleText), seq(statusText), statusWidth, statusColor, seq(dayText), pageLabels,
                pageLabelWidths, message, messageWidth, List.copyOf(rows), event);
    }

    private static Row row(Font font, BoardLayout.Metrics m, BoardSnapshot.Line line) {
        double ratio = line.ratio();
        boolean done = line.done();
        String counter = BoardLayout.counter(line.current(), line.target());
        int counterWidth = font.width(counter);
        String label;
        if (m.variant() == BoardLayout.Variant.COMPACT) {
            int area = BoardLayout.compactTextArea(m.widthPx());
            if (counterWidth > area) {
                counter = BoardLayout.percentText(ratio);
                counterWidth = font.width(counter);
            }
            label = font.plainSubstrByWidth(line.label(), Math.max(0, area));
        } else {
            if (BoardLayout.wideLabelArea(m.widthPx(), counterWidth) < BoardLayout.WIDE_MIN_LABEL) {
                counter = BoardLayout.percentText(ratio);
                counterWidth = font.width(counter);
            }
            label = font.plainSubstrByWidth(line.label(), Math.max(0, BoardLayout.wideLabelArea(m.widthPx(), counterWidth)));
        }
        return new Row(icon(line.icon()), seq(label), seq(counter), counterWidth, ratio, BoardLayout.State.of(ratio, done));
    }

    private static ItemStack icon(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Item item = rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        return new ItemStack(item == null || item == Items.AIR ? Items.BARRIER : item);
    }

    private static FormattedCharSequence seq(String text) {
        return FormattedCharSequence.forward(text, Style.EMPTY);
    }
}
