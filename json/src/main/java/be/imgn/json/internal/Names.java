package be.imgn.json.internal;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/// The distinct member names of a document, each with a number: the [TapeBuilder] interns each name it meets, and the
/// tape holds its number. A name is compared by its decoded UTF-8 bytes, so that a name and the same name written with
/// escape sequences have the same number.
///
/// The names seen by earlier documents are shared: an immutable snapshot numbers them, with their decoded value, and a
/// document numbers its new names after them. Once a document is parsed, its new names join a new snapshot, up to a
/// size past which no more are shared, so that documents of random names cannot make it grow. Snapshots only append:
/// a number keeps its name in every later snapshot.
///
/// A table is filled by one thread, then read by any: a name decoded on its first use is published with a benign
/// race, as a `String` is immutable.
public final class Names {

    /// The largest number of distinct names of a document, the largest number a tape entry holds.
    public static final int MAX_NAMES = Tape.SATURATED;

    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final int INITIAL_CAPACITY = 16;
    private static final int[] NO_SLOTS = new int[0];
    // The most names shared, and the most bytes they take.
    private static final int MAX_SHARED_NAMES = 8_192;
    private static final long MAX_SHARED_BYTES = 256 * 1024;
    private static final long HASH_MULTIPLIER = 0x9E37_79B9_7F4A_7C15L;
    private static final int HASH_ROTATION = 31;
    private static final long TAIL_MULTIPLIER = 0x0100_0000_01B3L;
    private static final int MIX_SHIFT = 33;
    private static final long MIX_MULTIPLIER = 0xFF51_AFD7_ED55_8CCDL;
    // Seeds the hash, so that a document cannot be crafted to make its names collide.
    private static final long SEED = ThreadLocalRandom.current().nextLong() | 1;
    private static final AtomicReference<Snapshot> SHARED = new AtomicReference<>(Snapshot.EMPTY);

    private final Snapshot shared;
    // The new names of the document, numbered after those of the snapshot: their bytes as ranges of arrays, their
    // hash, and their decoded value once decoded. Open addressing: the local index plus 1; 0 is an empty slot.
    // Created with the first new name: once the names of a kind of document are shared, its documents have none.
    private int[] table = NO_SLOTS;
    private byte[][] keys = new byte[0][];
    private int[] offsets = NO_SLOTS;
    private int[] lengths = NO_SLOTS;
    private int[] hashes = NO_SLOTS;
    private String[] strings = new String[0];
    private int count;

    /// Creates the table of a document, on the names shared so far.
    Names() {
        this.shared = Objects.requireNonNull(SHARED.get());
    }

    /// Returns the number of a name, given by its decoded UTF-8 bytes, which are not copied: a new name is numbered.
    ///
    /// @return the number, or -1 if the document already has [#MAX_NAMES] names
    int intern(byte[] bytes, int from, int length) {
        var hash = hash(bytes, from, length);
        var known = shared.find(hash, bytes, from, length);
        if (known >= 0) {
            return known;
        }
        if (table.length == 0) {
            table = new int[2 * INITIAL_CAPACITY];
        }
        var mask = table.length - 1;
        var slot = hash & mask;
        for (var entry = table[slot]; entry != 0; entry = table[slot]) {
            var local = entry - 1;
            if (hashes[local] == hash && equal(keys[local], offsets[local], bytes, from, length, lengths[local])) {
                return shared.count + local;
            }
            slot = (slot + 1) & mask;
        }
        if (shared.count + count == MAX_NAMES) {
            return -1;
        }
        var local = count++;
        if (local == keys.length) {
            grow();
        }
        keys[local] = bytes;
        offsets[local] = from;
        lengths[local] = length;
        hashes[local] = hash;
        table[slot] = local + 1;
        if (2 * count > table.length) {
            rehash();
        }
        return shared.count + local;
    }

    /// Returns whether a name has a number, written with bytes: false when no name has the number.
    boolean matches(int number, byte[] bytes, int from, int length) {
        if (number < shared.count) {
            return equal(shared.keys[number], 0, bytes, from, length, shared.keys[number].length);
        }
        var local = number - shared.count;
        return local < count && equal(keys[local], offsets[local], bytes, from, length, lengths[local]);
    }

    /// Returns whether a number names a shared name: those keep their numbers in every document.
    public boolean isShared(int number) {
        return number < shared.count;
    }

    /// Returns the number of names.
    int count() {
        return shared.count + count;
    }

    /// Returns the length of a name, in UTF-8 bytes once decoded.
    int length(int number) {
        return number < shared.count ? shared.keys[number].length : lengths[number - shared.count];
    }

    /// Returns a name.
    public String string(int number) {
        if (number < shared.count) {
            return shared.strings[number];
        }
        var local = number - shared.count;
        var string = strings[local];
        if (string == null) {
            string = new String(keys[local], offsets[local], lengths[local], UTF_8);
            strings[local] = string;
        }
        return string;
    }

    /// Shares the new names of this document with the next ones, while the shared names stay within their limits. A
    /// concurrent share may win: these names are then shared by a later document.
    void share() {
        if (count == 0
                || shared.count + count > MAX_SHARED_NAMES
                || shared.bytes
                                + Arrays.stream(lengths, 0, count)
                                        .asLongStream()
                                        .sum()
                        > MAX_SHARED_BYTES) {
            return;
        }
        SHARED.compareAndSet(shared, shared.with(this));
    }

    private void grow() {
        var capacity = Math.max(INITIAL_CAPACITY, keys.length * 2);
        keys = Arrays.copyOf(keys, capacity);
        offsets = Arrays.copyOf(offsets, capacity);
        lengths = Arrays.copyOf(lengths, capacity);
        hashes = Arrays.copyOf(hashes, capacity);
        strings = Arrays.copyOf(strings, capacity);
    }

    private void rehash() {
        table = slots(hashes, count, table.length * 2);
    }

    /// Returns an open-addressing table of indexes plus 1 at the slots of their hashes.
    private static int[] slots(int[] hashes, int count, int size) {
        var slots = new int[size];
        var mask = size - 1;
        for (var i = 0; i < count; i++) {
            var slot = hashes[i] & mask;
            while (slots[slot] != 0) {
                slot = (slot + 1) & mask;
            }
            slots[slot] = i + 1;
        }
        return slots;
    }

    /// Compares two ranges of bytes 8 at a time: names are short, and `Arrays.equals` on ranges costs more in checks
    /// than in comparisons for them.
    private static boolean equal(byte[] key, int offset, byte[] bytes, int from, int length, int keyLength) {
        if (keyLength != length) {
            return false;
        }
        var i = 0;
        for (; i + Long.BYTES <= length; i += Long.BYTES) {
            if ((long) LONGS.get(key, offset + i) != (long) LONGS.get(bytes, from + i)) {
                return false;
            }
        }
        for (; i < length; i++) {
            if (key[offset + i] != bytes[from + i]) {
                return false;
            }
        }
        return true;
    }

    private static int hash(byte[] bytes, int from, int length) {
        var h = SEED ^ length;
        var p = from;
        var to = from + length;
        for (; p + Long.BYTES <= to; p += Long.BYTES) {
            h = Long.rotateLeft((h ^ (long) LONGS.get(bytes, p)) * HASH_MULTIPLIER, HASH_ROTATION);
        }
        for (; p < to; p++) {
            h = (h ^ bytes[p]) * TAIL_MULTIPLIER;
        }
        h ^= h >>> MIX_SHIFT;
        h *= MIX_MULTIPLIER;
        h ^= h >>> MIX_SHIFT;
        return (int) h;
    }

    /// The names shared by the documents parsed so far: immutable, their bytes copied, their values decoded.
    private static final class Snapshot {

        static final Snapshot EMPTY = new Snapshot(new byte[0][], new int[0], new String[0], 0, 0);

        private final byte[][] keys;
        private final int[] hashes;
        private final String[] strings;
        private final int[] table;
        private final int count;
        private final long bytes;

        private Snapshot(byte[][] keys, int[] hashes, String[] strings, int count, long bytes) {
            this.keys = keys;
            this.hashes = hashes;
            this.strings = strings;
            this.count = count;
            this.bytes = bytes;
            this.table = slots(hashes, count, Integer.highestOneBit(Math.max(1, count) * 2) * 2);
        }

        /// Returns the number of a name, or -1.
        int find(int hash, byte[] name, int from, int length) {
            var mask = table.length - 1;
            for (var slot = hash & mask; table[slot] != 0; slot = (slot + 1) & mask) {
                var number = table[slot] - 1;
                if (hashes[number] == hash && equal(keys[number], 0, name, from, length, keys[number].length)) {
                    return number;
                }
            }
            return -1;
        }

        /// Returns a snapshot of these names followed by the new names of a document.
        Snapshot with(Names names) {
            var total = count + names.count;
            var newKeys = Arrays.copyOf(keys, total);
            var newHashes = Arrays.copyOf(hashes, total);
            var newStrings = Arrays.copyOf(strings, total);
            var newBytes = bytes;
            for (var local = 0; local < names.count; local++) {
                var key = Arrays.copyOfRange(
                        names.keys[local], names.offsets[local], names.offsets[local] + names.lengths[local]);
                newKeys[count + local] = key;
                newHashes[count + local] = names.hashes[local];
                newStrings[count + local] = new String(key, UTF_8);
                newBytes += key.length;
            }
            return new Snapshot(newKeys, newHashes, newStrings, total, newBytes);
        }
    }
}
