package org.consortium.core.api.event;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.Event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Posted after a delivery was committed (ledger written, balance and counters updated). Not cancellable: nothing a
 * listener does can roll money back. Posted inside {@code try/catch (Throwable)}: a throwing handler never breaks a
 * delivery.
 */
public class DeliveryEvent extends Event {
    /** One family line of the committed delivery. */
    public static final class Line {
        private final String family;
        private final List<String> items;
        private final double units;
        private final double paidUnits;
        private final long cents;
        private final double multiplier;

        public Line(String family, List<String> items, double units, double paidUnits, long cents, double multiplier) {
            this.family = family;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.units = units;
            this.paidUnits = paidUnits;
            this.cents = cents;
            this.multiplier = multiplier;
        }

        public String getFamily() {
            return family;
        }

        public List<String> getItems() {
            return items;
        }

        public double getUnits() {
            return units;
        }

        public double getPaidUnits() {
            return paidUnits;
        }

        public long getCents() {
            return cents;
        }

        public double getMultiplier() {
            return multiplier;
        }
    }

    private final ServerPlayer player;
    private final String txId;
    private final List<Line> lines;
    private final long totalCents;
    private final ResourceKey<Level> dimension;
    private final BlockPos pos;

    public DeliveryEvent(ServerPlayer player, String txId, List<Line> lines, long totalCents, ResourceKey<Level> dimension, BlockPos pos) {
        this.player = player;
        this.txId = txId;
        this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
        this.totalCents = totalCents;
        this.dimension = dimension;
        this.pos = pos;
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public String getTxId() {
        return txId;
    }

    public List<Line> getLines() {
        return lines;
    }

    public long getTotalCents() {
        return totalCents;
    }

    public ResourceKey<Level> getDimension() {
        return dimension;
    }

    public BlockPos getPos() {
        return pos;
    }
}
