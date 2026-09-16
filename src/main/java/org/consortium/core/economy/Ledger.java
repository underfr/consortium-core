package org.consortium.core.economy;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Append-only audit trail: {@code <dir>/YYYY-MM-DD.jsonl} (UTC day), one JSON object per line, flushed after every
 * write. Write-ahead rule: callers build their lines, call {@link #write(List)} and mutate memory only when it
 * returned; an {@link IOException} means nothing was recorded and nothing may change.
 *
 * <p>The ledger is never replayed. {@link #boot(long)} only compares the newest file with the {@code last_seq} the
 * saved data remembers, to mark a rolled-back tail (specification 2.2), and seeds the in-memory tail ring so
 * {@code /ccore ledger tail} never touches a file during play.
 */
public final class Ledger implements AutoCloseable {
    /** Lines kept in memory for the tail command. */
    public static final int RING_SIZE = 200;

    /** A range of sequence numbers found in the newest file but unknown to the saved data. */
    public record RollbackRange(long from, long to) {
        public long count() {
            return to - from + 1;
        }

        @Override
        public String toString() {
            return from == to ? "seq " + from : "seq " + from + " to " + to;
        }
    }

    private final Path dir;
    private final Clock clock;
    private final ArrayDeque<String> ring = new ArrayDeque<>(RING_SIZE);
    private final Consumer<String> warn;

    private long nextSeq = 1;
    private String openDay;
    private Writer writer;
    private RollbackRange lastRollback;
    private boolean bootDone;

    public Ledger(Path dir, Clock clock, Consumer<String> warn) {
        this.dir = dir;
        this.clock = clock;
        this.warn = warn;
    }

    public Path directory() {
        return dir;
    }

    /** The sequence number the next written line will carry. */
    public synchronized long nextSeq() {
        return nextSeq;
    }

    /** The rollback range detected by the last {@link #boot(long)}, or null. */
    public synchronized RollbackRange lastRollback() {
        return lastRollback;
    }

    /**
     * Boot sequence: {@code seq = max(lastSeq, highest seq in the newest file) + 1}; lines above {@code lastSeq}
     * in that file are the rolled-back tail. Returns the range (already appended as a {@code ROLLBACK} line) or null.
     * Earlier {@code ROLLBACK} markers never carry money, so they count for the next sequence number but never for
     * the range: a boot that died before the saved data persisted the marker's seq finds the original range again
     * instead of nesting markers.
     *
     * @param lastSeq the {@code last_seq} the saved data remembers (0 for a fresh world)
     */
    public synchronized RollbackRange boot(long lastSeq) throws IOException {
        Files.createDirectories(dir);
        Path newest = newestFile();
        long highest = 0;
        long highestEffect = 0;
        long lowestAbove = Long.MAX_VALUE;
        List<String> tail = new ArrayList<>();
        if (newest != null) {
            List<String> lines = Files.readAllLines(newest, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                long seq = LedgerLine.seqOf(line);
                if (seq > highest) {
                    highest = seq;
                }
                if (!LedgerType.ROLLBACK.name().equals(LedgerLine.typeOf(line))) {
                    if (seq > highestEffect) {
                        highestEffect = seq;
                    }
                    if (seq > lastSeq && seq < lowestAbove) {
                        lowestAbove = seq;
                    }
                }
                tail.add(line);
            }
        }
        ring.clear();
        int from = Math.max(0, tail.size() - RING_SIZE);
        for (int i = from; i < tail.size(); i++) {
            ring.addLast(tail.get(i));
        }
        nextSeq = Math.max(lastSeq, highest) + 1;
        bootDone = true;
        lastRollback = null;
        if (highestEffect > lastSeq && lowestAbove != Long.MAX_VALUE) {
            RollbackRange range = new RollbackRange(lowestAbove, highestEffect);
            LedgerLine marker = LedgerLine.of(LedgerType.ROLLBACK)
                    .counterpart("boot")
                    .reason("lines written after the last save never took effect: the world rolled back")
                    .extra("seq_from", range.from())
                    .extra("seq_to", range.to());
            write(List.of(marker));
            lastRollback = range;
            warn.accept("Ledger rollback detected: " + range + " in " + newest.getFileName()
                    + " never took effect (world restored from an earlier save). Review with /ccore ledger tail.");
        }
        return lastRollback;
    }

    /**
     * Seals, writes and flushes the lines in order (consecutive sequence numbers). Nothing is kept when the write
     * fails; the sequence counter advances only after a successful flush.
     */
    public synchronized void write(List<LedgerLine> lines) throws IOException {
        if (!bootDone) {
            throw new IOException("Ledger not booted");
        }
        if (lines.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        String day = LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
        Writer out = writerFor(day);
        StringBuilder chunk = new StringBuilder(lines.size() * 200);
        List<String> encoded = new ArrayList<>(lines.size());
        long seq = nextSeq;
        for (LedgerLine line : lines) {
            String text = line.seal(seq++, now);
            encoded.add(text);
            chunk.append(text).append('\n');
        }
        out.write(chunk.toString());
        out.flush();
        nextSeq = seq;
        for (String text : encoded) {
            if (ring.size() >= RING_SIZE) {
                ring.pollFirst();
            }
            ring.addLast(text);
        }
    }

    /** Last {@code n} lines, newest first, optionally filtered by a substring of the raw JSON (uuid, name, family, type). */
    public synchronized List<String> tail(int n, String filter) {
        List<String> out = new ArrayList<>();
        var it = ring.descendingIterator();
        String needle = filter == null || filter.isBlank() ? null : filter;
        while (it.hasNext() && out.size() < n) {
            String line = it.next();
            if (needle == null || matches(line, needle)) {
                out.add(line);
            }
        }
        return out;
    }

    private static boolean matches(String line, String needle) {
        // Quoted so a filter of "iron" does not match "iron_block" through the type or a reason; both plain and
        // quoted forms are accepted so an admin can still filter by a partial player name.
        return line.contains("\"" + needle + "\"") || line.contains(needle);
    }

    /** Current ring content, oldest first (used by tests and the version command). */
    public synchronized List<String> ringSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(ring));
    }

    private Writer writerFor(String day) throws IOException {
        if (writer != null && day.equals(openDay)) {
            return writer;
        }
        closeWriter();
        Files.createDirectories(dir);
        Path file = dir.resolve(day + ".jsonl");
        writer = new BufferedWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE));
        openDay = day;
        return writer;
    }

    private Path newestFile() throws IOException {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.jsonl"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        }
    }

    private void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                warn.accept("Ledger file close failed: " + e.getMessage());
            }
            writer = null;
            openDay = null;
        }
    }

    @Override
    public synchronized void close() {
        closeWriter();
        bootDone = false;
    }
}
