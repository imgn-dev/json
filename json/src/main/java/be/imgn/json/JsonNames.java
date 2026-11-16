package be.imgn.json;

import static be.imgn.gaia.core.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.checkIndex;
import static java.util.Objects.requireNonNull;

import be.imgn.json.internal.Output;
import java.util.Arrays;
import java.util.HashSet;

/// Member names known in advance, for [JsonStreamReader#name(JsonNames)] to match without creating a `String`, and for
/// [JsonStreamWriter#name(JsonNames, int)] to write already encoded. Create them once, in a constant.
///
/// @since 0.1.0
public final class JsonNames {

    /// What [#cached(int)] returns for a number not cached yet.
    static final int UNKNOWN = -2;

    private static final int MULTIPLIER = 31;

    private final String[] names;
    private final byte[][] encoded;
    private final byte[][] quoted;
    // Open addressing: the index of a name plus 1, at the slot of its hash; 0 is an empty slot.
    private final int[] table;
    // By number of shared name: the index of the name plus 2, 1 for none of these names, 0 when not cached yet.
    private int[] byNumber = new int[0];

    private JsonNames(String[] names) {
        this.names = names;
        this.encoded = new byte[names.length][];
        this.quoted = new byte[names.length][];
        this.table = new int[Integer.highestOneBit(Math.max(1, names.length) * 2) * 2];
        var mask = table.length - 1;
        for (var i = 0; i < names.length; i++) {
            quoted[i] = Output.quote(names[i]);
            encoded[i] = names[i].getBytes(UTF_8);
            var slot = hash(encoded[i], 0, encoded[i].length) & mask;
            while (table[slot] != 0) {
                slot = (slot + 1) & mask;
            }
            table[slot] = i + 1;
        }
    }

    /// Returns names.
    ///
    /// @param names the names
    /// @return the names, in that order: [JsonStreamReader#name(JsonNames)] returns their index
    /// @throws NullPointerException if a name is null
    /// @throws IllegalArgumentException if two names are equal, or a name has an unpaired surrogate
    public static JsonNames of(String... names) {
        var distinct = new HashSet<String>();
        for (var name : names) {
            requireNonNull(name, "name");
            checkArgument(distinct.add(name), "duplicate name \"%s\"", name);
        }
        return new JsonNames(names.clone());
    }

    /// Returns the number of names.
    ///
    /// @return the number of names
    public int size() {
        return names.length;
    }

    /// Returns a name.
    ///
    /// @param index the index of the name
    /// @return the name
    /// @throws IndexOutOfBoundsException if `index` is negative, or not less than [#size()]
    public String get(int index) {
        checkIndex(index, names.length);
        return names[index];
    }

    @Override
    public String toString() {
        return Arrays.toString(names);
    }

    /// Returns the index of the shared name of a number, -1 if it is none of these names, or [#UNKNOWN] if not cached.
    int cached(int number) {
        var cache = byNumber;
        return number < cache.length ? cache[number] - 2 : UNKNOWN;
    }

    /// Caches the index of the shared name of a number. The cache is shared by every thread: an index is set in an
    /// `int` and read whole, and a thread that misses one, or the whole array when it grows, finds the name again.
    void cache(int number, int found) {
        var cache = byNumber;
        if (number >= cache.length) {
            cache = Arrays.copyOf(cache, Math.max(number + 1, cache.length * 2));
            byNumber = cache;
        }
        cache[number] = found + 2;
    }

    /// Returns the index of the name written with UTF-8 bytes, or -1.
    int find(byte[] input, int from, int length) {
        var mask = table.length - 1;
        for (var slot = hash(input, from, length) & mask; table[slot] != 0; slot = (slot + 1) & mask) {
            var candidate = encoded[table[slot] - 1];
            if (Arrays.equals(candidate, 0, candidate.length, input, from, from + length)) {
                return table[slot] - 1;
            }
        }
        return -1;
    }

    /// Returns the index of a name, or -1.
    int find(String name) {
        var bytes = name.getBytes(UTF_8);
        return find(bytes, 0, bytes.length);
    }

    /// Returns a name, quoted and escaped, in UTF-8.
    byte[] quoted(int index) {
        return quoted[index];
    }

    private static int hash(byte[] bytes, int from, int length) {
        var hash = length;
        if (length > 0) {
            hash = (hash * MULTIPLIER + bytes[from]) * MULTIPLIER + bytes[from + length - 1];
        }
        return hash ^ (hash >>> Short.SIZE);
    }
}
