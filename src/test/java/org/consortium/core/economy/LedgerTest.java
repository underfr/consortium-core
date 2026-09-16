package org.consortium.core.economy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-16T10:15:30Z"), ZoneOffset.UTC);
    private static final UUID ALEX = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @TempDir
    Path dir;

    private final List<String> warnings = new ArrayList<>();

    private Ledger open() throws IOException {
        Ledger ledger = new Ledger(dir.resolve("ledger"), CLOCK, warnings::add);
        return ledger;
    }

    @Test
    void linesAreSealedInOrderAndEncodedOnOneLine() throws IOException {
        Ledger ledger = open();
        assertNull(ledger.boot(0));
        LedgerLine a = LedgerLine.of(LedgerType.ADMIN_ADD).player(ALEX, "Alex").counterpart("console").total(10_000).balance(10_000).reason("event \"prize\"");
        LedgerLine b = LedgerLine.of(LedgerType.MARKET_RESET).counterpart("admin").extra("family", "minecraft:iron_ingot").extra("units", 64.0);
        ledger.write(List.of(a, b));
        assertEquals(1, a.seq());
        assertEquals(2, b.seq());
        assertEquals(3, ledger.nextSeq());
        String json = a.encoded();
        assertTrue(json.startsWith("{\"ts\":\"2026-09-16T10:15:30Z\",\"seq\":1,\"tx\":null,\"type\":\"ADMIN_ADD\",\"player\":\"" + ALEX + "\",\"name\":\"Alex\""), json);
        assertTrue(json.contains("\"reason\":\"event \\\"prize\\\"\""), json);
        assertTrue(b.encoded().contains("\"units\":64"), b.encoded());
        List<String> file = Files.readAllLines(dir.resolve("ledger").resolve("2026-09-16.jsonl"), StandardCharsets.UTF_8);
        assertEquals(2, file.size());
        assertEquals(1, LedgerLine.seqOf(file.get(0)));
        assertEquals("MARKET_RESET", LedgerLine.typeOf(file.get(1)));
        ledger.close();
    }

    @Test
    void bootUsesTheHighestSeqAndDetectsARolledBackTail() throws IOException {
        Ledger first = open();
        first.boot(0);
        for (int i = 0; i < 5; i++) {
            first.write(List.of(LedgerLine.of(LedgerType.API_CREDIT).player(ALEX, "Alex").total(100).balance(100 * (i + 1)).reason("quest")));
        }
        first.close();
        // The saved data only remembers seq 3: lines 4 and 5 never took effect.
        Ledger second = open();
        Ledger.RollbackRange range = second.boot(3);
        assertNotNull(range);
        assertEquals(4, range.from());
        assertEquals(5, range.to());
        assertEquals(2, range.count());
        assertEquals(7, second.nextSeq(), "ROLLBACK marker took seq 6, the next line gets 7");
        assertEquals(1, warnings.size());
        List<String> tail = second.tail(1, null);
        assertEquals("ROLLBACK", LedgerLine.typeOf(tail.get(0)));
        assertTrue(tail.get(0).contains("\"seq_from\":4") && tail.get(0).contains("\"seq_to\":5"), tail.get(0));
        second.close();
        // A restored .dat that remembers a higher seq than the file never reuses numbers.
        Ledger third = open();
        assertNull(third.boot(40));
        assertEquals(41, third.nextSeq());
        third.close();
    }

    @Test
    void anEarlierRollbackMarkerNeverNestsIntoTheNextRange() throws IOException {
        Ledger first = open();
        first.boot(0);
        for (int i = 0; i < 5; i++) {
            first.write(List.of(LedgerLine.of(LedgerType.API_CREDIT).player(ALEX, "Alex").total(100).balance(100 * (i + 1)).reason("quest")));
        }
        first.close();
        Ledger second = open();
        assertEquals(new Ledger.RollbackRange(4, 5), second.boot(3), "marker written as seq 6");
        second.close();
        // The server died again before the saved data persisted last_seq = 6: the marker is above last_seq but
        // carries no money, so the range names the original lines only and the marker still counts for the seq.
        Ledger third = open();
        Ledger.RollbackRange again = third.boot(3);
        assertNotNull(again);
        assertEquals(4, again.from());
        assertEquals(5, again.to());
        assertEquals(8, third.nextSeq(), "second marker took seq 7");
        third.close();
        // Once last_seq caught up with the marker nothing is above it.
        Ledger fourth = open();
        assertNull(fourth.boot(7));
        assertEquals(8, fourth.nextSeq());
        fourth.close();
    }

    @Test
    void tailFiltersNewestFirstFromTheRing() throws IOException {
        Ledger ledger = open();
        ledger.boot(0);
        for (int i = 0; i < 250; i++) {
            ledger.write(List.of(LedgerLine.of(LedgerType.DELIVERY).tx("t" + i).player(ALEX, i % 2 == 0 ? "Alex" : "Bob").total(1)
                    .balance(i).extra("family", i % 3 == 0 ? "c:ingots/steel" : "minecraft:coal")));
        }
        assertEquals(Ledger.RING_SIZE, ledger.ringSnapshot().size());
        List<String> last = ledger.tail(3, null);
        assertEquals(3, last.size());
        assertEquals(250, LedgerLine.seqOf(last.get(0)));
        assertEquals(248, LedgerLine.seqOf(last.get(2)));
        List<String> bob = ledger.tail(5, "Bob");
        assertEquals(5, bob.size());
        for (String line : bob) {
            assertTrue(line.contains("\"name\":\"Bob\""));
        }
        List<String> steel = ledger.tail(100, "c:ingots/steel");
        assertTrue(steel.size() > 0 && steel.stream().allMatch(l -> l.contains("c:ingots/steel")));
        ledger.close();
    }

    @Test
    void writingBeforeBootIsRefused() {
        Ledger ledger = new Ledger(dir.resolve("x"), CLOCK, warnings::add);
        try {
            ledger.write(List.of(LedgerLine.of(LedgerType.ADMIN_ADD)));
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("not booted"));
            return;
        }
        throw new AssertionError("expected an IOException");
    }
}
