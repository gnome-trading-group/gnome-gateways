package group.gnometrading.gateways.feed;

import group.gnometrading.strings.GnomeString;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The markets on a connection, by their venue key (a ticker, coin, asset id, slug or market index). The reader looks a
 * key up as it parses, without allocating; the supervisor adds and removes markets by replacing the whole table, so a
 * lookup always sees one consistent table and never a half-made change.
 */
public final class SubjectTable {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private record Table(byte[][] keys, Subject[] subjects, int mask, int size) {}

    private volatile Table table = build(List.of());

    /** Reader thread: the market for {@code key}, or null when it isn't on this connection. */
    public Subject get(final GnomeString key) {
        return get(key, 0);
    }

    /** Reader thread: the market for the part of {@code key} from {@code from} on, e.g. a channel name's suffix. */
    public Subject get(final GnomeString key, final int from) {
        final Table current = table;
        final int length = key.length() - from;
        if (length < 0) {
            return null;
        }
        long hash = FNV_OFFSET;
        for (int i = 0; i < length; i++) {
            hash = (hash ^ key.byteAt(from + i)) * FNV_PRIME;
        }
        for (int slot = (int) hash & current.mask; ; slot = (slot + 1) & current.mask) {
            final byte[] candidate = current.keys[slot];
            if (candidate == null) {
                return null;
            }
            if (candidate.length == length && matches(candidate, key, from)) {
                return current.subjects[slot];
            }
        }
    }

    public Subject get(final String key) {
        final Table current = table;
        final byte[] bytes = key.getBytes(StandardCharsets.US_ASCII);
        for (int slot = (int) hash(bytes) & current.mask; ; slot = (slot + 1) & current.mask) {
            final byte[] candidate = current.keys[slot];
            if (candidate == null) {
                return null;
            }
            if (Arrays.equals(candidate, bytes)) {
                return current.subjects[slot];
            }
        }
    }

    public int size() {
        return table.size;
    }

    /** Every market on the connection, in no particular order. */
    public List<Subject> subjects() {
        final Table current = table;
        final List<Subject> all = new ArrayList<>(current.size);
        for (Subject subject : current.subjects) {
            if (subject != null) {
                all.add(subject);
            }
        }
        return all;
    }

    /** Supervisor thread. */
    public synchronized void put(final Subject subject) {
        final List<Subject> all = subjects();
        all.removeIf(s -> s.key().equals(subject.key()));
        all.add(subject);
        table = build(all);
    }

    /** Supervisor thread. */
    public synchronized void remove(final Subject subject) {
        final List<Subject> all = subjects();
        all.removeIf(s -> s.key().equals(subject.key()));
        table = build(all);
    }

    private static boolean matches(final byte[] candidate, final GnomeString key, final int from) {
        for (int i = 0; i < candidate.length; i++) {
            if (candidate[i] != key.byteAt(from + i)) {
                return false;
            }
        }
        return true;
    }

    private static long hash(final byte[] bytes) {
        long hash = FNV_OFFSET;
        for (byte b : bytes) {
            hash = (hash ^ b) * FNV_PRIME;
        }
        return hash;
    }

    private static Table build(final List<Subject> subjects) {
        // At most half full, so probes stay short.
        final int capacity = Integer.highestOneBit(Math.max(4, subjects.size() * 2) - 1) << 1;
        final byte[][] keys = new byte[capacity][];
        final Subject[] values = new Subject[capacity];
        final int mask = capacity - 1;
        for (Subject subject : subjects) {
            final byte[] bytes = subject.key().getBytes(StandardCharsets.US_ASCII);
            int slot = (int) hash(bytes) & mask;
            while (keys[slot] != null) {
                slot = (slot + 1) & mask;
            }
            keys[slot] = bytes;
            values[slot] = subject;
        }
        return new Table(keys, values, mask, subjects.size());
    }
}
