package be.imgn.json.internal;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.ArrayList;
import java.util.List;

/// The tokens of a valid document, one `long` each, and the document they point into.
///
/// An entry holds, from the most significant bits:
///
/// | Bits  | Strings, names, numbers, literals      | Container starts      | Container ends                 |
/// |-------|----------------------------------------|-----------------------|--------------------------------|
/// | 63–60 | tag                                    | tag                   | tag                            |
/// | 59–56 | flags                                  | 0                     | 0                              |
/// | 55–24 | offset of the token in the document    | index of the end      | offset of the `{` or `[`       |
/// | 23–0  | length in bytes, between quotes        | count of its members  | count of its members           |
///
/// Lengths and counts saturate at [#SATURATED]: a string or number that long has its length computed again from the
/// document by [#length(int)]; a container that big has its count found by walking it.
///
/// The root value is the entry at index 0.
public final class Tape {

    /// The tag of an object start.
    public static final int OBJECT_START = 1;
    /// The tag of an object end.
    public static final int OBJECT_END = 2;
    /// The tag of an array start.
    public static final int ARRAY_START = 3;
    /// The tag of an array end.
    public static final int ARRAY_END = 4;
    /// The tag of a member name.
    public static final int NAME = 5;
    /// The tag of a string value.
    public static final int STRING = 6;
    /// The tag of a number.
    public static final int NUMBER = 7;
    /// The tag of `true`.
    public static final int TRUE = 8;
    /// The tag of `false`.
    public static final int FALSE = 9;
    /// The tag of `null`.
    public static final int NULL = 10;

    /// The flag of a string or name with at least one escape sequence.
    public static final int ESCAPED = 1;
    /// The flag of a string or name with at least one non-ASCII character.
    public static final int NON_ASCII = 2;
    /// The flag of a number with neither a fraction nor an exponent.
    public static final int INTEGRAL = 1;

    /// The largest length or count an entry holds: larger ones are stored as this value.
    public static final int SATURATED = 0xFF_FFFF;

    private static final int TAG_SHIFT = 60;
    private static final int FLAGS_SHIFT = 56;
    private static final int FLAGS_MASK = 0xF;
    private static final int OFFSET_SHIFT = 24;
    private static final long OFFSET_MASK = 0xFFFF_FFFFL;
    // The bytes of a string or number that toString() shows.
    private static final int SHOWN = 40;

    private static final String[] TAG_NAMES = {
        "?",
        "OBJECT_START",
        "OBJECT_END",
        "ARRAY_START",
        "ARRAY_END",
        "NAME",
        "STRING",
        "NUMBER",
        "TRUE",
        "FALSE",
        "NULL"
    };

    private final byte[] input;
    private final long[] entries;
    private final int size;
    private final Names names;

    Tape(byte[] input, long[] entries, int size, Names names) {
        this.input = input;
        this.entries = entries;
        this.size = size;
        this.names = names;
    }

    /// Returns the distinct member names of the document, which the low field of a name entry numbers.
    public Names names() {
        return names;
    }

    /// Returns the document, in UTF-8.
    public byte[] input() {
        return input;
    }

    /// Returns the entries; only the first [#size()] are used.
    public long[] entries() {
        return entries;
    }

    /// Returns the number of entries.
    public int size() {
        return size;
    }

    /// Returns the tag of an entry.
    public static int tag(long entry) {
        return (int) (entry >>> TAG_SHIFT);
    }

    /// Returns the flags of an entry.
    public static int flags(long entry) {
        return (int) (entry >>> FLAGS_SHIFT) & FLAGS_MASK;
    }

    /// Returns the middle field of an entry: an offset in the document, or the index of a container's end.
    public static int offset(long entry) {
        return (int) (entry >>> OFFSET_SHIFT);
    }

    /// Returns the low field of an entry, which may be [#SATURATED]: a length, or a count.
    public static int low(long entry) {
        return (int) entry & SATURATED;
    }

    static long entry(int tag, int flags, int offset, int low) {
        return ((long) tag << TAG_SHIFT)
                | ((long) flags << FLAGS_SHIFT)
                | ((offset & OFFSET_MASK) << OFFSET_SHIFT)
                | low;
    }

    /// Returns the length of the string, name or number at an index, in bytes, as written (between the quotes of a
    /// string), computing it again from the document when the entry saturates.
    public int length(int index) {
        var entry = entries[index];
        var tag = tag(entry);
        if (tag == NAME) {
            // The low field numbers the name: without escape sequences, its decoded length is the written one.
            return (flags(entry) & ESCAPED) == 0 ? names.length(low(entry)) : scannedLength(offset(entry));
        }
        var length = low(entry);
        if (length < SATURATED) {
            return length;
        }
        var start = offset(entry);
        if (tag(entry) == NUMBER) {
            var p = start;
            while (p < input.length && isNumberByte(input[p])) {
                p++;
            }
            return p - start;
        }
        return scannedLength(start);
    }

    /// Returns the length of the string whose opening quote is at an offset, between its quotes. The document is
    /// valid: the first quote that is not escaped ends the string.
    private int scannedLength(int quote) {
        var p = quote + 1;
        while (input[p] != '"') {
            p += input[p] == '\\' ? 2 : 1;
        }
        return p - quote - 1;
    }

    /// Returns the index after the value at an index: after the end of a container, or the next entry.
    public int next(int index) {
        var entry = entries[index];
        return isContainerStart(entry) ? offset(entry) + 1 : index + 1;
    }

    /// Returns the offset in the document of the value at an index: of the `{` or `[` of a container.
    public int valueOffset(int index) {
        var entry = entries[index];
        return isContainerStart(entry) ? offset(entries[offset(entry)]) : offset(entry);
    }

    /// Returns the count of the members or elements of the container that starts at an index.
    public int count(int index) {
        var count = low(entries[index]);
        if (count < SATURATED) {
            return count;
        }
        var end = offset(entries[index]);
        var step = tag(entries[index]) == OBJECT_START ? 1 : 0;
        count = 0;
        for (var i = index + 1; i < end; i = next(i + step)) {
            count++;
        }
        return count;
    }

    private static boolean isContainerStart(long entry) {
        var tag = tag(entry);
        return tag == OBJECT_START || tag == ARRAY_START;
    }

    private static boolean isNumberByte(byte b) {
        return (b >= '0' && b <= '9') || b == '-' || b == '+' || b == '.' || b == 'e' || b == 'E';
    }

    /// Returns the entries as text, one per line, for tests and debugging: `NAME 1 3 ESCAPED`, `OBJECT_START 4 1`.
    @Override
    public String toString() {
        var lines = new ArrayList<String>();
        for (var i = 0; i < size; i++) {
            lines.add(describe(i));
        }
        return String.join("\n", lines);
    }

    private String describe(int index) {
        var entry = entries[index];
        var tag = tag(entry);
        var text = new StringBuilder(TAG_NAMES[tag])
                .append(' ')
                .append(offset(entry))
                .append(' ')
                .append(low(entry));
        var flags = flags(entry);
        List<String> names = switch (tag) {
            case NAME, STRING -> {
                var list = new ArrayList<String>();
                if ((flags & ESCAPED) != 0) {
                    list.add("ESCAPED");
                }
                if ((flags & NON_ASCII) != 0) {
                    list.add("NON_ASCII");
                }
                yield list;
            }
            case NUMBER -> (flags & INTEGRAL) != 0 ? List.of("INTEGRAL") : List.of();
            default -> List.of();
        };
        names.forEach(name -> text.append(' ').append(name));
        if (tag == NAME || tag == STRING || tag == NUMBER) {
            var start = offset(entry) + (tag == NUMBER ? 0 : 1);
            text.append(" ").append(new String(input, start, Math.min(length(index), SHOWN), UTF_8));
        }
        return text.toString();
    }
}
