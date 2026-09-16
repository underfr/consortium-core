package org.consortium.core.economy;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One line of the audit trail. Built in full before the ledger writes it; the sequence number is assigned by
 * {@link Ledger#write} just before the flush. Field order follows the specification: {@code ts, seq, tx, type,
 * player, name, counterpart, total, balance, reason} followed by the type-specific extras.
 *
 * <p>The line never carries an IP address or an IP hash.
 */
public final class LedgerLine {
    private static final Pattern SEQ = Pattern.compile("\"seq\":(\\d+)");
    private static final Pattern TYPE = Pattern.compile("\"type\":\"([A-Z_]+)\"");

    private final LedgerType type;
    private final Map<String, Object> fields = new LinkedHashMap<>();
    private long seq = -1;
    private String encoded;

    private LedgerLine(LedgerType type) {
        this.type = type;
        fields.put("ts", null);
        fields.put("seq", null);
        fields.put("tx", null);
        fields.put("type", type.name());
        fields.put("player", null);
        fields.put("name", null);
        fields.put("counterpart", null);
        fields.put("total", 0L);
        fields.put("balance", null);
        fields.put("reason", null);
    }

    public static LedgerLine of(LedgerType type) {
        return new LedgerLine(type);
    }

    public LedgerLine tx(String tx) {
        fields.put("tx", tx);
        return this;
    }

    public LedgerLine player(UUID uuid, String name) {
        fields.put("player", uuid == null ? null : uuid.toString());
        fields.put("name", name);
        return this;
    }

    public LedgerLine counterpart(String counterpart) {
        fields.put("counterpart", counterpart);
        return this;
    }

    /** Signed cents moved by this line (0 for lines without money). */
    public LedgerLine total(long cents) {
        fields.put("total", cents);
        return this;
    }

    /** Balance after the line, in cents; omitted (null) for lines that do not touch a balance. */
    public LedgerLine balance(long cents) {
        fields.put("balance", cents);
        return this;
    }

    public LedgerLine reason(String reason) {
        fields.put("reason", reason);
        return this;
    }

    /** Type-specific extra field (family, items, units, old and new values, ...). Insertion order is kept. */
    public LedgerLine extra(String key, Object value) {
        fields.put(key, value);
        return this;
    }

    public LedgerType type() {
        return type;
    }

    public long seq() {
        return seq;
    }

    public Object field(String key) {
        return fields.get(key);
    }

    public long total() {
        Object v = fields.get("total");
        return v instanceof Number n ? n.longValue() : 0L;
    }

    public String player() {
        Object v = fields.get("player");
        return v == null ? null : v.toString();
    }

    public String name() {
        Object v = fields.get("name");
        return v == null ? null : v.toString();
    }

    public String family() {
        Object v = fields.get("family");
        return v == null ? null : v.toString();
    }

    public String tx() {
        Object v = fields.get("tx");
        return v == null ? null : v.toString();
    }

    /** Stamps the sequence number and timestamp and freezes the JSON text. Called by the ledger only. */
    String seal(long seq, Instant now) {
        this.seq = seq;
        fields.put("seq", seq);
        fields.put("ts", DateTimeFormatter.ISO_INSTANT.format(now));
        encoded = Json.encode(fields);
        return encoded;
    }

    /** The JSON text, available once the line was sealed. */
    public String encoded() {
        return encoded == null ? Json.encode(fields) : encoded;
    }

    /** Reads the {@code seq} of a raw line, -1 when absent (boot scan helper). */
    public static long seqOf(String rawLine) {
        Matcher m = SEQ.matcher(rawLine);
        return m.find() ? Long.parseLong(m.group(1)) : -1L;
    }

    /** Reads the {@code type} of a raw line, or null. */
    public static String typeOf(String rawLine) {
        Matcher m = TYPE.matcher(rawLine);
        return m.find() ? m.group(1) : null;
    }

    @Override
    public String toString() {
        return encoded();
    }
}
