package org.consortium.core.api.event;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;
import org.consortium.core.pricing.PriceCurve;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Posted once per quote (at quote time and again at confirm time) so charter and event modifiers can scale the
 * credits of a line ({@link #setMultiplier(int, double)}) or refuse it ({@link #refuse(int, String)}). Multipliers
 * never change the stacks reported to the phase engine. Not cancellable: refusing every line is the veto.
 */
public class DeliveryQuoteEvent extends Event {
    /** One family line of the quote as the listeners see it. */
    public static final class Line {
        private final int index;
        private final String family;
        private final List<String> items;
        private final double units;
        private final double paidUnits;
        private final long cents;

        public Line(int index, String family, List<String> items, double units, double paidUnits, long cents) {
            this.index = index;
            this.family = family;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.units = units;
            this.paidUnits = paidUnits;
            this.cents = cents;
        }

        public int getIndex() {
            return index;
        }

        public String getFamily() {
            return family;
        }

        /** Item ids with counts, e.g. {@code minecraft:iron_ingot x64}. */
        public List<String> getItems() {
            return items;
        }

        public double getUnits() {
            return units;
        }

        public double getPaidUnits() {
            return paidUnits;
        }

        /** Credits in cents before any multiplier. */
        public long getCents() {
            return cents;
        }
    }

    private final ServerPlayer player;
    private final List<Line> lines;
    private final double[] multipliers;
    private final String[] refusals;

    public DeliveryQuoteEvent(ServerPlayer player, List<Line> lines) {
        this.player = player;
        this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
        this.multipliers = new double[lines.size()];
        java.util.Arrays.fill(multipliers, 1.0);
        this.refusals = new String[lines.size()];
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public List<Line> getLines() {
        return lines;
    }

    /** Sets the credit multiplier of a line; validated finite and clamped to {@code [0, 10]}. */
    public void setMultiplier(int index, double multiplier) {
        checkIndex(index);
        multipliers[index] = PriceCurve.clampMultiplier(multiplier);
    }

    public double getMultiplier(int index) {
        checkIndex(index);
        return multipliers[index];
    }

    /** Refuses a line with a reason shown to the player. The only veto point of a delivery. */
    public void refuse(int index, String reason) {
        checkIndex(index);
        refusals[index] = reason == null || reason.isBlank() ? "Refused" : reason;
    }

    /** The refusal reason of a line, or null when accepted. */
    public String getRefusal(int index) {
        checkIndex(index);
        return refusals[index];
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= multipliers.length) {
            throw new IndexOutOfBoundsException("No quote line " + index + " (" + multipliers.length + " lines)");
        }
    }
}
