package be.imgn.json.stream;

import static be.imgn.gaia.core.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.util.Objects.requireNonNull;

import be.imgn.json.JsonMappingException;
import be.imgn.json.JsonType;
import be.imgn.json.JsonValue;
import be.imgn.json.bind.UnknownMembers;
import be.imgn.json.internal.Access;
import be.imgn.json.internal.Numbers;
import be.imgn.json.internal.Strings;
import be.imgn.json.internal.Tape;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/// Reads a document value by value, as typed reads. The parser validated the whole document before the first read, so
/// reading never finds invalid JSON: it fails only on a value of a type other than the one read, with a
/// [JsonMappingException], or on a call out of order, with an [IllegalStateException].
///
/// ```java
/// reader.beginObject();
/// while (reader.hasNextMember()) {
///     switch (reader.name(NAMES)) { // NAMES = JsonNames.of("id", "tags")
///         case 0 -> id = reader.readLong();
///         case 1 -> tags = readTags(reader);
///         default -> reader.skipValue();
///     }
/// }
/// ```
///
/// Skipping a value costs the same whatever its size. A reader is not thread-safe.
///
/// @since 0.1.0
public final class JsonStreamReader {

    private static final int INITIAL_DEPTH = 16;
    // The longest number an error message quotes.
    private static final int MAX_QUOTED = 40;

    static {
        Access.register(new StreamAccess());
    }

    private final Tape tape;
    private final long[] entries;
    private final byte[] input;
    private final UnknownMembers unknownMembers;
    private int index;

    // The open containers, from 1: the tape index of their start, and the tape index of the name of the current member
    // of an object (-1 before the first), or the number of elements of an array started so far.
    private int depth;
    private int[] starts = new int[INITIAL_DEPTH];
    private int[] currents = new int[INITIAL_DEPTH];

    JsonStreamReader(Tape tape, UnknownMembers unknownMembers) {
        this.tape = tape;
        this.entries = tape.entries();
        this.input = tape.input();
        this.unknownMembers = unknownMembers;
    }

    /// Returns the type of the next value, without reading it.
    ///
    /// @return the type of the next value
    /// @throws IllegalStateException if the reader is not at a value
    public JsonType peek() {
        return Access.trees().type(valueEntry());
    }

    /// Reads the start of an object; [#hasNextMember()] then tells whether a member follows.
    ///
    /// @throws JsonMappingException if the next value is not an object
    /// @throws IllegalStateException if the reader is not at a value
    public void beginObject() {
        if (Tape.tag(valueEntry()) != Tape.OBJECT_START) {
            throw mismatch("an object");
        }
        push(-1);
    }

    /// Returns whether the object being read has another member, at whose name the reader then is; at the end of the
    /// object, reads it and returns `false`.
    ///
    /// @return whether a member follows
    /// @throws IllegalStateException if the reader is not in an object, or the value of the previous member was not
    ///     read
    public boolean hasNextMember() {
        checkState(depth > 0 && isObject(depth), "the reader is not in an object");
        var tag = Tape.tag(entries[index]);
        if (tag == Tape.OBJECT_END) {
            depth--;
            index++;
            return false;
        }
        checkState(tag == Tape.NAME, "the value of the previous member was not read");
        currents[depth] = index;
        return true;
    }

    /// Reads a member name, and returns its index among known names.
    ///
    /// @param names the known names
    /// @return the index of the name in `names`, or -1 if it is not one of them:
    ///     [#skipUnknownMember(JsonNames)] then skips its value, or rejects it
    /// @throws IllegalStateException if the reader is not at a member name
    public int name(JsonNames names) {
        requireNonNull(names, "names");
        var number = Tape.low(nameEntry());
        if (!tape.names().isShared(number)) {
            return find(names, index++);
        }
        // A shared name keeps its number in every document: the index it maps to is cached by number.
        var cached = names.cached(number);
        if (cached == JsonNames.UNKNOWN) {
            cached = find(names, index);
            names.cache(number, cached);
        }
        index++;
        return cached;
    }

    /// Returns the index of the tag that names the type of the next value, an object, from the string value of
    /// its tag member, wherever the member is among the others; the reader stays at the object. The code
    /// generated for a sealed interface laid out with [JsonTagMember][be.imgn.json.bind.JsonTagMember] reads
    /// the object as the record the tag names.
    ///
    /// @param member the name of the tag member, the only one in its [JsonNames]
    /// @param tags the tags
    /// @return the index of the tag in `tags`
    /// @throws JsonMappingException if the next value is not an object, or has no tag member, or one whose value
    ///     is not one of `tags`
    /// @throws IllegalStateException if the reader is not at a value
    public int peekTag(JsonNames member, JsonNames tags) {
        requireNonNull(member, "member");
        requireNonNull(tags, "tags");
        var entry = valueEntry();
        if (Tape.tag(entry) != Tape.OBJECT_START) {
            throw mismatch("an object");
        }
        var end = Tape.offset(entry);
        for (var name = index + 1; name < end; name = tape.next(name + 1)) {
            if (find(member, name) == 0) {
                var value = name + 1;
                if (Tape.tag(entries[value]) != Tape.STRING) {
                    throw tagError("expected a string naming the type", value, member, tags);
                }
                var found = find(tags, value);
                if (found < 0) {
                    throw tagError("unknown type \"" + Strings.decode(tape, value) + "\"", value, member, tags);
                }
                return found;
            }
        }
        throw Access.trees()
                .mappingError(
                        "missing member \"" + member.get(0) + "\" naming the type, as one of " + quoted(tags),
                        tape.position(tape.valueOffset(index)),
                        path());
    }

    private JsonMappingException tagError(String message, int value, JsonNames member, JsonNames tags) {
        var at = path() + "/" + member.get(0).replace("~", "~0").replace("/", "~1");
        return Access.trees()
                .mappingError(
                        message + ", expected one of " + quoted(tags), tape.position(tape.valueOffset(value)), at);
    }

    /// Returns the index among names of the string or name at a tape index, or -1.
    private int find(JsonNames names, int at) {
        var entry = entries[at];
        return (Tape.flags(entry) & Tape.ESCAPED) == 0
                ? names.find(input, Tape.offset(entry) + 1, tape.length(at))
                : names.find(Strings.decode(tape, at));
    }

    private static String quoted(JsonNames names) {
        return IntStream.range(0, names.size())
                .mapToObj(i -> "\"" + names.get(i) + "\"")
                .collect(Collectors.joining(", "));
    }

    /// Reads a member name.
    ///
    /// @return the name
    /// @throws IllegalStateException if the reader is not at a member name
    public String readName() {
        nameEntry();
        return Strings.decode(tape, index++);
    }

    /// Skips the value of a member whose name [#name(JsonNames)] did not know, or rejects it if the parser was built
    /// with [UnknownMembers#REJECT].
    ///
    /// @param names the names that [#name(JsonNames)] knew, for the error message
    /// @throws JsonMappingException if unknown members are rejected
    /// @throws IllegalStateException if the reader is not at the value of a member
    public void skipUnknownMember(JsonNames names) {
        requireNonNull(names, "names");
        checkState(
                depth > 0 && isObject(depth) && currents[depth] == index - 1,
                "the reader is not at the value of a member");
        if (unknownMembers == UnknownMembers.REJECT) {
            throw Access.trees()
                    .mappingError(
                            "unknown member \"" + Strings.decode(tape, index - 1) + "\", expected one of "
                                    + quoted(names),
                            tape.position(Tape.offset(entries[index - 1])),
                            path());
        }
        skipValue();
    }

    /// Reads the start of an array; [#hasNextElement()] then tells whether an element follows.
    ///
    /// @throws JsonMappingException if the next value is not an array
    /// @throws IllegalStateException if the reader is not at a value
    public void beginArray() {
        if (Tape.tag(valueEntry()) != Tape.ARRAY_START) {
            throw mismatch("an array");
        }
        push(0);
    }

    /// Returns whether the array being read has another element, at which the reader then is; at the end of the
    /// array, reads it and returns `false`.
    ///
    /// @return whether an element follows
    /// @throws IllegalStateException if the reader is not in an array
    public boolean hasNextElement() {
        checkState(depth > 0 && !isObject(depth), "the reader is not in an array");
        if (Tape.tag(entries[index]) == Tape.ARRAY_END) {
            depth--;
            index++;
            return false;
        }
        currents[depth]++;
        return true;
    }

    /// Reads a string.
    ///
    /// @return the string
    /// @throws JsonMappingException if the next value is not a string
    /// @throws IllegalStateException if the reader is not at a value
    public String readString() {
        if (Tape.tag(valueEntry()) != Tape.STRING) {
            throw mismatch("a string");
        }
        return Strings.decode(tape, index++);
    }

    /// Reads a number whose value is an integer in the range of a `long`, such as `12`, `1.0` or `1e2`.
    ///
    /// @return the value
    /// @throws JsonMappingException if the next value is not such a number
    /// @throws IllegalStateException if the reader is not at a value
    public long readLong() {
        return readInteger(Long.MIN_VALUE, Long.MAX_VALUE, "a long");
    }

    /// Reads a number whose value is an integer in the range of an `int`.
    ///
    /// @return the value
    /// @throws JsonMappingException if the next value is not such a number
    /// @throws IllegalStateException if the reader is not at a value
    public int readInt() {
        return (int) readInteger(Integer.MIN_VALUE, Integer.MAX_VALUE, "an int");
    }

    /// Reads a number whose value is an integer in the range of a `short`.
    ///
    /// @return the value
    /// @throws JsonMappingException if the next value is not such a number
    /// @throws IllegalStateException if the reader is not at a value
    public short readShort() {
        return (short) readInteger(Short.MIN_VALUE, Short.MAX_VALUE, "a short");
    }

    /// Reads a number whose value is an integer in the range of a `byte`.
    ///
    /// @return the value
    /// @throws JsonMappingException if the next value is not such a number
    /// @throws IllegalStateException if the reader is not at a value
    public byte readByte() {
        return (byte) readInteger(Byte.MIN_VALUE, Byte.MAX_VALUE, "a byte");
    }

    /// Reads a number, as the nearest `double`, or `NaN` or an infinity of JSON5.
    ///
    /// @return the value
    /// @throws JsonMappingException if the next value is not a number, or is too large for a `double`
    /// @throws IllegalStateException if the reader is not at a value
    public double readDouble() {
        var entry = numberEntry("a double");
        var value = Numbers.parseDouble(input, Tape.offset(entry), tape.length(index));
        if (Double.isInfinite(value) && (Tape.flags(entry) & Tape.NON_FINITE) == 0) {
            throw outOfRange("a double");
        }
        index++;
        return value;
    }

    /// Reads a number, as the nearest `float`, or `NaN` or an infinity of JSON5.
    ///
    /// @return the value
    /// @throws JsonMappingException if the next value is not a number, or is too large for a `float`
    /// @throws IllegalStateException if the reader is not at a value
    public float readFloat() {
        var entry = numberEntry("a float");
        var value = Numbers.parseFloat(input, Tape.offset(entry), tape.length(index));
        if (Float.isInfinite(value) && (Tape.flags(entry) & Tape.NON_FINITE) == 0) {
            throw outOfRange("a float");
        }
        index++;
        return value;
    }

    /// Reads a number whose value is an integer, exactly.
    ///
    /// @return the value
    /// @throws JsonMappingException if the next value is not an integer, or has more than 100 000 digits
    /// @throws IllegalStateException if the reader is not at a value
    public BigInteger readBigInteger() {
        var entry = numberEntry("a BigInteger");
        try {
            var value = Numbers.toBigInteger(input, Tape.offset(entry), tape.length(index), Tape.flags(entry));
            index++;
            return value;
        } catch (ArithmeticException e) {
            throw outOfRange("a BigInteger");
        }
    }

    /// Reads a number, exactly.
    ///
    /// @return the value
    /// @throws JsonMappingException if the next value is not a number, or is `NaN` or an infinity of JSON5
    /// @throws IllegalStateException if the reader is not at a value
    public BigDecimal readBigDecimal() {
        var entry = numberEntry("a BigDecimal");
        try {
            var value = Numbers.toBigDecimal(input, Tape.offset(entry), tape.length(index));
            index++;
            return value;
        } catch (ArithmeticException e) {
            throw outOfRange("a BigDecimal");
        }
    }

    /// Reads a boolean.
    ///
    /// @return the value
    /// @throws JsonMappingException if the next value is not a boolean
    /// @throws IllegalStateException if the reader is not at a value
    public boolean readBoolean() {
        var tag = Tape.tag(valueEntry());
        if (tag != Tape.TRUE && tag != Tape.FALSE) {
            throw mismatch("a boolean");
        }
        index++;
        return tag == Tape.TRUE;
    }

    /// Reads the next value if it is `null`.
    ///
    /// @return whether the next value was `null`, and was read; otherwise, the reader is still at it
    /// @throws IllegalStateException if the reader is not at a value
    public boolean readNull() {
        if (Tape.tag(valueEntry()) == Tape.NULL) {
            index++;
            return true;
        }
        return false;
    }

    /// Reads a value of any type, as a [JsonValue]. An object or an array is lazy, and keeps the document in memory.
    ///
    /// @return the value
    /// @throws IllegalStateException if the reader is not at a value
    public JsonValue readValue() {
        valueEntry();
        var value = Access.trees().value(tape, index);
        index = tape.next(index);
        return value;
    }

    /// Skips a value of any type, at a cost that does not depend on its size.
    ///
    /// @throws IllegalStateException if the reader is not at a value
    public void skipValue() {
        valueEntry();
        index = tape.next(index);
    }

    /// Returns a mark of the next value, for [#mappingError(int, String)] to report an error at its position once it
    /// was read.
    ///
    /// @return the mark
    /// @throws IllegalStateException if the reader is not at a value
    public int mark() {
        valueEntry();
        return index;
    }

    /// Returns an error about a value, such as a missing member of an object or a value a record rejects, to throw.
    ///
    /// @param mark the mark of the value, from [#mark()]
    /// @param message what is wrong
    /// @return the exception, at the position of the value and the path of the reader
    /// @throws IllegalArgumentException if `mark` is not a mark of this reader
    public JsonMappingException mappingError(int mark, String message) {
        requireNonNull(message, "message");
        if (mark < 0 || mark >= tape.size()) {
            throw new IllegalArgumentException("not a mark of this reader: " + mark);
        }
        return Access.trees().mappingError(message, tape.position(tape.valueOffset(mark)), path());
    }

    /// Returns an error about a value, caused by an exception.
    ///
    /// @param mark the mark of the value, from [#mark()]
    /// @param message what is wrong
    /// @param cause the exception
    /// @return the exception, at the position of the value and the path of the reader
    /// @throws IllegalArgumentException if `mark` is not a mark of this reader
    public JsonMappingException mappingError(int mark, String message, Throwable cause) {
        requireNonNull(cause, "cause");
        var error = mappingError(mark, message);
        error.initCause(cause);
        return error;
    }

    /// Returns whether the whole document was read.
    boolean isDone() {
        return index == tape.size();
    }

    private void push(int current) {
        depth++;
        if (depth == starts.length) {
            starts = Arrays.copyOf(starts, depth * 2);
            currents = Arrays.copyOf(currents, depth * 2);
        }
        starts[depth] = index;
        currents[depth] = current;
        index++;
    }

    private boolean isObject(int level) {
        return Tape.tag(entries[starts[level]]) == Tape.OBJECT_START;
    }

    private long valueEntry() {
        checkState(index < tape.size(), "the document was read whole");
        var entry = entries[index];
        var tag = Tape.tag(entry);
        checkState(tag != Tape.NAME, "the reader is at a member name: read it first");
        checkState(
                tag != Tape.OBJECT_END && tag != Tape.ARRAY_END,
                "no value is left: hasNextMember() or hasNextElement() would return false");
        return entry;
    }

    private long nameEntry() {
        checkState(
                index < tape.size() && Tape.tag(entries[index]) == Tape.NAME,
                "the reader is not at a member name: call hasNextMember() first");
        return entries[index];
    }

    private long numberEntry(String expected) {
        var entry = valueEntry();
        if (Tape.tag(entry) != Tape.NUMBER) {
            throw mismatch(expected);
        }
        return entry;
    }

    private long readInteger(long min, long max, String expected) {
        var entry = numberEntry(expected);
        try {
            var value = Numbers.toLong(input, Tape.offset(entry), tape.length(index), Tape.flags(entry), min, max);
            index++;
            return value;
        } catch (ArithmeticException e) {
            throw outOfRange(expected);
        }
    }

    private JsonMappingException mismatch(String expected) {
        var found = Access.trees().description(Access.trees().type(entries[index]));
        return Access.trees()
                .mappingError(
                        "expected " + expected + ", found " + found, tape.position(tape.valueOffset(index)), path());
    }

    private JsonMappingException outOfRange(String expected) {
        var offset = Tape.offset(entries[index]);
        var length = tape.length(index);
        var text =
                new String(input, offset, Math.min(length, MAX_QUOTED), ISO_8859_1) + (length > MAX_QUOTED ? "…" : "");
        return Access.trees().mappingError("expected " + expected + ", found " + text, tape.position(offset), path());
    }

    /// Returns the path of the reader, as a JSON Pointer.
    private String path() {
        var path = new StringBuilder();
        for (var level = 1; level <= depth; level++) {
            var current = currents[level];
            if (isObject(level) ? current < 0 : current == 0) {
                break;
            }
            var segment = isObject(level) ? Strings.decode(tape, current) : String.valueOf(current - 1);
            path.append('/').append(segment.replace("~", "~0").replace("/", "~1"));
        }
        return path.toString();
    }
}
