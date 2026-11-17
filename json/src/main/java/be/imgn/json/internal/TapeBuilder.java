package be.imgn.json.internal;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReferenceArray;

/// Validates a document and builds its [Tape], in one pass.
///
/// Everything that makes a document invalid is found here: the grammar, the UTF-8 encoding, escape sequences, unpaired
/// surrogates, numbers, duplicate member names, and the [Limits]. Once [#build(byte[], Limits)] returns, the document
/// is known to be valid, and the tape can be read without checks.
///
/// The grammar is parsed iteratively, with explicit stacks: no recursion, whatever the depth. Strings are scanned 8
/// bytes at a time, as a `long`; nothing is allocated per token.
public final class TapeBuilder {

    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INTS = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private static final long ONES = 0x0101_0101_0101_0101L;
    private static final long HIGH_BITS = 0x8080_8080_8080_8080L;
    private static final long QUOTES = ONES * '"';
    private static final long BACKSLASHES = ONES * '\\';
    private static final long CONTROLS = ONES * ' ';
    private static final long SPACES = ONES * ' ';

    // The bytes of the literals, as a little-endian int; false is checked after its 'f'.
    private static final int TRUE = 't' | ('r' << 8) | ('u' << 16) | ('e' << 24);
    private static final int ALSE = 'a' | ('l' << 8) | ('s' << 16) | ('e' << 24);
    private static final int NULL = 'n' | ('u' << 8) | ('l' << 16) | ('l' << 24);

    private static final byte[] BYTE_ORDER_MARK = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] INFINITY = "Infinity".getBytes(UTF_8);
    private static final byte[] NAN = "NaN".getBytes(UTF_8);
    private static final int DELETE = 0x7F;

    // The initial capacities. A token takes 8 bytes of a document or more, except in arrays of one-digit numbers.
    private static final int BYTES_PER_TOKEN = 8;
    private static final int MIN_TAPE = 16;
    private static final int INITIAL_DEPTH = 32;
    private static final int INITIAL_NAMES = 64;
    // The largest arrays given back to the pool, so that a huge document does not keep its memory.
    private static final int MAX_POOLED_DEPTH = 1024;
    private static final int MAX_POOLED_NAMES = 1 << 16;

    // Up to this many members, a new name is compared with each previous one; above, they go into a hash table, which
    // is kept at most half full.
    private static final int SMALL_OBJECT = 8;
    private static final int INITIAL_TABLE = 4 * SMALL_OBJECT;
    private static final int HASH_BITS = 32;
    private static final long HASH_MULTIPLIER = 0x9E37_79B9_7F4A_7C15L;

    // The longest word that an error message quotes.
    private static final int MAX_WORD = 20;

    private final byte[] input;
    private final int begin;
    private final int end;
    private final Limits limits;
    private final Syntax syntax;
    private final Locator locator;
    // The distinct member names, numbered.
    private final Names nameTable = new Names();
    // By number of name, the number plus 1 of the name that followed it last time; 0 when none did.
    private int[] nextNames;
    // The arrays below, taken from a pool and given back, as their allocation weighs on small documents.
    private final Scratch scratch;

    private int pos;
    private long[] tape;
    private int size;
    // The flags of the last string scanned.
    private int flags;

    // The open containers, indexed by depth, from 1.
    private int depth;
    private int[] starts;
    private int[] opens;
    private int[] counts;
    private boolean[] objects;
    // The tape index of the name of the member being parsed, or -1.
    private int[] currentNames;
    // Where the names of each open object start on the names stack.
    private int[] nameBases;
    // The hash tables of the open objects with more than SMALL_OBJECT members, kept for reuse at each depth.
    private int[][] tables;
    // The number plus 1 of the first name of the last object at each depth; 0 when none.
    private int[] firstNames;

    // The tape indices of the names of the open objects.
    private int[] names;
    private int namesTop;

    private TapeBuilder(
            byte[] input, int from, int to, Limits limits, Syntax syntax, Locator locator, Scratch scratch) {
        this.input = input;
        this.begin = from;
        this.end = to;
        this.limits = limits;
        this.syntax = syntax;
        this.locator = locator;
        this.pos = from;
        this.tape = new long[Math.max(MIN_TAPE, (to - from) / BYTES_PER_TOKEN)];
        this.scratch = scratch;
        this.nextNames = scratch.nextNames;
        this.starts = scratch.starts;
        this.opens = scratch.opens;
        this.counts = scratch.counts;
        this.objects = scratch.objects;
        this.currentNames = scratch.currentNames;
        this.nameBases = scratch.nameBases;
        this.tables = scratch.tables;
        this.firstNames = scratch.firstNames;
        this.names = scratch.names;
        // No first name to predict: those of an earlier document number other names.
        Arrays.fill(firstNames, 0);
    }

    /// Validates a JSON document and builds its tape.
    ///
    /// @param input the document, in UTF-8; it is not copied, and must not change while the tape is used
    /// @throws TapeException if the document is not valid JSON, or exceeds a limit
    public static Tape build(byte[] input, Limits limits) {
        return build(input, 0, input.length, limits, Syntax.JSON, Locator.of(input));
    }

    /// Validates a document, written between two offsets of an input, and builds its tape.
    ///
    /// @param input the input, in UTF-8; it is not copied, and must not change while the tape is used
    /// @param from the offset where the document starts
    /// @param to the offset where the document ends
    /// @param locator the positions of the offsets of the input, for the errors that quote one
    /// @throws TapeException if the document is not valid, or exceeds a limit
    public static Tape build(byte[] input, int from, int to, Limits limits, Syntax syntax, Locator locator) {
        var builder = new TapeBuilder(input, from, to, limits, syntax, locator, Scratch.acquire());
        try {
            return builder.buildTape();
        } finally {
            builder.release();
        }
    }

    /// Gives the arrays back to the pool, as they grew, unless they grew too large to keep.
    private void release() {
        if (starts.length <= MAX_POOLED_DEPTH
                && names.length <= MAX_POOLED_NAMES
                && nextNames.length <= MAX_POOLED_NAMES) {
            scratch.nextNames = nextNames;
            scratch.starts = starts;
            scratch.opens = opens;
            scratch.counts = counts;
            scratch.objects = objects;
            scratch.currentNames = currentNames;
            scratch.nameBases = nameBases;
            scratch.tables = tables;
            scratch.firstNames = firstNames;
            scratch.names = names;
            Scratch.release(scratch);
        }
    }

    private Tape buildTape() {
        if (end - begin > limits.maxDocumentLength()) {
            var what = syntax == Syntax.JSON_LINE ? "the line" : "the document";
            throw error(
                    begin + limits.maxDocumentLength(),
                    what + " is longer than the maximum of " + limits.maxDocumentLength() + " bytes");
        }
        if (Arrays.equals(
                input,
                begin,
                Math.min(end, begin + BYTE_ORDER_MARK.length),
                BYTE_ORDER_MARK,
                0,
                BYTE_ORDER_MARK.length)) {
            throw error(begin, "a byte order mark is not allowed");
        }
        var more = true;
        while (more) {
            more = value() || next();
        }
        if (skipWhitespace() >= 0) {
            throw error(pos, "expected " + endOfInput() + " after the value, found " + describe(pos));
        }
        nameTable.share();
        return new Tape(input, tape, size, nameTable, locator);
    }

    // The grammar.

    /// Parses the start of a value: a whole scalar or empty container, or the opening of a container. Returns whether
    /// a value is expected next: the first element of an array, or the value of the first member of an object.
    private boolean value() {
        var c = skipWhitespace();
        switch (c) {
            case '{' -> {
                return openObject();
            }
            case '[' -> {
                return openArray();
            }
            case '"' -> string();
            case 't' -> literal(TRUE, 0, Tape.TRUE, "true");
            case 'f' -> literal(ALSE, 1, Tape.FALSE, "false");
            case 'n' -> literal(NULL, 0, Tape.NULL, "null");
            case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> number();
            default -> {
                if (syntax != Syntax.JSON5 || (c != 'I' && c != 'N')) {
                    throw error(pos, "expected a value, found " + describeWord(pos));
                }
                nonFinite(pos);
            }
        }
        return false;
    }

    /// Continues after a complete value: closes the containers it completes, up to the separator of the next value.
    /// Returns whether a value is expected next, or `false` at the end of the root value.
    private boolean next() {
        while (depth > 0) {
            var c = skipWhitespace();
            if (c == ',') {
                pos++;
                return objects[depth] ? nextMember() : nextElement();
            }
            if (objects[depth]) {
                if (c != '}') {
                    throw error(pos, "expected ',' or '}' after a member, found " + describe(pos));
                }
                pos++;
                close(Tape.OBJECT_START, Tape.OBJECT_END);
            } else {
                if (c != ']') {
                    throw error(pos, "expected ',' or ']' after an element, found " + describe(pos));
                }
                pos++;
                close(Tape.ARRAY_START, Tape.ARRAY_END);
            }
        }
        return false;
    }

    private boolean openObject() {
        open(true);
        var c = skipWhitespace();
        if (c == '"') {
            name();
            return true;
        }
        if (c != '}') {
            throw error(pos, "expected a member name or '}', found " + describe(pos));
        }
        pos++;
        close(Tape.OBJECT_START, Tape.OBJECT_END);
        return false;
    }

    private boolean openArray() {
        open(false);
        if (skipWhitespace() == ']') {
            pos++;
            close(Tape.ARRAY_START, Tape.ARRAY_END);
            return false;
        }
        counts[depth] = 1;
        return true;
    }

    private boolean nextMember() {
        if (skipWhitespace() != '"') {
            throw error(
                    pos,
                    "expected a member name after ',', found " + describe(pos)
                            + (pos < end && input[pos] == '}' ? trailingComma() : ""));
        }
        name();
        return true;
    }

    private boolean nextElement() {
        if (skipWhitespace() == ']') {
            throw error(pos, "expected a value after ',', found ']'" + trailingComma());
        }
        counts[depth]++;
        return true;
    }

    /// Returns the byte at the first position that is not whitespace, from [#pos], which it moves there; or -1 at the
    /// end of the document.
    private int skipWhitespace() {
        var p = pos;
        while (p < end) {
            var b = input[p];
            if (b > ' ') {
                pos = p;
                return b;
            }
            if (b == ' ' && p + Long.BYTES <= end) {
                // Indentation comes in runs of spaces: skip those of the next 8 bytes at once.
                var others = (long) LONGS.get(input, p) ^ SPACES;
                p += others == 0 ? Long.BYTES : Long.numberOfTrailingZeros(others) / Byte.SIZE;
            } else if (b == ' ' || b == '\n' || b == '\r' || b == '\t') {
                p++;
            } else {
                pos = p;
                return Byte.toUnsignedInt(b);
            }
        }
        pos = p;
        return -1;
    }

    private void open(boolean object) {
        if (depth == limits.maxDepth()) {
            throw error(pos, "the document nests more than " + limits.maxDepth() + " objects and arrays");
        }
        depth++;
        if (depth == starts.length) {
            growDepth();
        }
        starts[depth] = size;
        opens[depth] = pos;
        counts[depth] = 0;
        objects[depth] = object;
        currentNames[depth] = -1;
        nameBases[depth] = namesTop;
        append(0);
        pos++;
    }

    private void close(int startTag, int endTag) {
        var count = Math.min(counts[depth], Tape.SATURATED);
        tape[starts[depth]] = Tape.entry(startTag, 0, size, count);
        append(Tape.entry(endTag, 0, opens[depth], count));
        namesTop = nameBases[depth];
        depth--;
    }

    private void growDepth() {
        var length = starts.length * 2;
        starts = Arrays.copyOf(starts, length);
        opens = Arrays.copyOf(opens, length);
        counts = Arrays.copyOf(counts, length);
        objects = Arrays.copyOf(objects, length);
        currentNames = Arrays.copyOf(currentNames, length);
        nameBases = Arrays.copyOf(nameBases, length);
        tables = Arrays.copyOf(tables, length);
        firstNames = Arrays.copyOf(firstNames, length);
    }

    private void append(long entry) {
        if (size == tape.length) {
            tape = Arrays.copyOf(tape, tape.length * 2);
        }
        tape[size++] = entry;
    }

    // Strings.

    private void name() {
        var quote = pos;
        var close = scanString(quote);
        var index = size;
        append(Tape.entry(Tape.NAME, flags, quote, nameNumber(quote, close)));
        counts[depth]++;
        // The error of a duplicate is about the object, not about the previous member.
        currentNames[depth] = -1;
        checkDuplicate(index);
        currentNames[depth] = index;
        pos = close + 1;
        if (skipWhitespace() != ':') {
            throw error(pos, "expected ':' after a member name, found " + describe(pos));
        }
        pos++;
    }

    /// Returns the number of the name between two quotes, interned by its decoded bytes: a name with escape sequences
    /// is decoded first.
    private int nameNumber(int quote, int close) {
        var known = nameTable.count();
        var number = numberOf(quote, close);
        if (number < 0) {
            throw error(quote, "the document has more than " + Names.MAX_NAMES + " distinct member names");
        }
        if (number >= known && number < nextNames.length) {
            // A new name: what followed this number in an earlier document followed another name.
            nextNames[number] = 0;
        }
        return number;
    }

    private int numberOf(int quote, int close) {
        int number;
        if ((flags & Tape.ESCAPED) == 0) {
            // The name that followed the previous one last time, or that came first at this depth: in an array of
            // similar objects, the names come in the same order, and a comparison saves hashing.
            var previous = currentNames[depth];
            var predicted = previous >= 0 ? predict(Tape.low(tape[previous])) : firstNames[depth] - 1;
            var length = close - quote - 1;
            number = predicted >= 0 && nameTable.matches(predicted, input, quote + 1, length)
                    ? predicted
                    : nameTable.intern(input, quote + 1, length);
            if (number >= 0) {
                learn(previous, number);
            }
        } else {
            var bytes =
                    Strings.decode(input, quote + 1, close - quote - 1, flags).getBytes(UTF_8);
            number = nameTable.intern(bytes, 0, bytes.length);
        }
        return number;
    }

    /// Returns the number of the name that followed a name last time, or -1.
    private int predict(int number) {
        return number < nextNames.length ? nextNames[number] - 1 : -1;
    }

    /// Remembers that a name followed the name at a tape index, or came first in its object when the index is -1.
    private void learn(int previous, int number) {
        if (previous < 0) {
            firstNames[depth] = number + 1;
            return;
        }
        var before = Tape.low(tape[previous]);
        if (before >= nextNames.length) {
            nextNames = Arrays.copyOf(nextNames, Math.max(before + 1, nextNames.length * 2));
        }
        nextNames[before] = number + 1;
    }

    private void string() {
        var quote = pos;
        var close = scanString(quote);
        append(Tape.entry(Tape.STRING, flags, quote, Math.min(close - quote - 1, Tape.SATURATED)));
        pos = close + 1;
    }

    /// Validates the string that starts with the quote at an offset, sets [#flags], and returns the offset of its
    /// closing quote.
    private int scanString(int quote) {
        var found = 0;
        var p = skipPlainBytes(quote + 1);
        while (p >= end || input[p] != '"') {
            if (p >= end) {
                throw error(end, "expected '\"' to end the string, found " + endOfInput());
            }
            var b = Byte.toUnsignedInt(input[p]);
            if (b == '\\') {
                found |= Tape.ESCAPED;
                p = escape(p);
            } else if (b < ' ') {
                throw error(p, "unescaped control character U+%04X in a string".formatted(b));
            } else {
                found |= Tape.NON_ASCII;
                // Non-ASCII characters come in runs, as in most scripts other than Latin: no word scan between them.
                do {
                    p = utf8Fast(p);
                } while (p < end && input[p] < 0);
            }
            p = skipPlainBytes(p);
        }
        if (p - quote - 1 > limits.maxStringLength()) {
            throw error(quote, "the string is longer than the maximum of " + limits.maxStringLength() + " bytes");
        }
        flags = found;
        return p;
    }

    /// Returns the offset of the first byte from an offset that is `"`, `\`, below U+0020 or not ASCII, or the end of
    /// the document.
    private int skipPlainBytes(int from) {
        var p = from;
        while (p + Long.BYTES <= end) {
            var mask = specialBytes((long) LONGS.get(input, p));
            if (mask != 0) {
                return p + Long.numberOfTrailingZeros(mask) / Byte.SIZE;
            }
            p += Long.BYTES;
        }
        while (p < end) {
            var b = input[p];
            if (b == '"' || b == '\\' || b < ' ') {
                return p;
            }
            p++;
        }
        return p;
    }

    /// Returns a mask with the high bit set in each byte of a word that is `"`, `\`, below U+0020 or not ASCII. Only
    /// the lowest set bit is exact: a borrow can set bits above it.
    static long specialBytes(long word) {
        var quotes = word ^ QUOTES;
        var backslashes = word ^ BACKSLASHES;
        return (((quotes - ONES) & ~quotes)
                        | ((backslashes - ONES) & ~backslashes)
                        | ((word - CONTROLS) & ~word)
                        | word)
                & HIGH_BITS;
    }

    /// Validates the escape sequence at an offset, and returns the offset after it.
    private int escape(int backslash) {
        if (backslash + 1 >= end) {
            throw error(end, "expected '\"' to end the string, found " + endOfInput());
        }
        return switch (input[backslash + 1]) {
            case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> backslash + 2;
            case 'u' -> unicodeEscape(backslash);
            default ->
                throw error(backslash + 1, "expected an escape sequence after '\\', found " + describe(backslash + 1));
        };
    }

    /// Validates a Unicode escape sequence, and the one after it if it is a high surrogate, which must be a low one.
    private int unicodeEscape(int backslash) {
        var unit = (char) hex4(backslash + 2);
        if (Character.isLowSurrogate(unit)) {
            throw error(backslash, "unpaired surrogate \\u%04X in a string".formatted((int) unit));
        }
        var after = backslash + Strings.UNICODE_ESCAPE_LENGTH;
        if (!Character.isHighSurrogate(unit)) {
            return after;
        }
        if (after + 1 < end
                && input[after] == '\\'
                && input[after + 1] == 'u'
                && Character.isLowSurrogate((char) hex4(after + 2))) {
            return after + Strings.UNICODE_ESCAPE_LENGTH;
        }
        throw error(backslash, "unpaired surrogate \\u%04X in a string".formatted((int) unit));
    }

    private int hex4(int from) {
        for (var p = from; p < from + Strings.HEX_DIGITS; p++) {
            if (p >= end || !isHexDigit(input[p])) {
                throw error(p, "expected a hexadecimal digit in a \\u escape sequence, found " + describe(p));
            }
        }
        return Strings.hex4(input, from);
    }

    private static boolean isHexDigit(byte b) {
        return (b >= '0' && b <= '9') || (b >= 'a' && b <= 'f') || (b >= 'A' && b <= 'F');
    }

    /// Validates the UTF-8 sequence that starts with the non-ASCII byte at an offset, and returns the offset after it:
    /// the common 2- and 3-byte sequences are checked whole in one 4-byte read, the others by [#utf8(int)].
    private int utf8Fast(int p) {
        if (p + Integer.BYTES <= end) {
            var word = (int) INTS.get(input, p);
            var lead = word & Utf8.LEAD_MASK;
            // A 3-byte sequence whose second byte only needs to be a continuation byte: not after E0 nor ED.
            if ((lead & Utf8.THREE_MASK) == Utf8.THREE_TAG
                    && lead != Utf8.THREE_OVERLONG
                    && lead != Utf8.THREE_SURROGATE
                    && (word & Utf8.TWO_CONTINUATIONS_MASK) == Utf8.TWO_CONTINUATIONS) {
                return p + Utf8.THREE;
            }
            if ((lead & Utf8.TWO_MASK) == Utf8.TWO_TAG
                    && lead >= Utf8.TWO_MIN
                    && (word & Utf8.ONE_CONTINUATION_MASK) == Utf8.ONE_CONTINUATION) {
                return p + 2;
            }
        }
        return utf8(p);
    }

    /// Validates the UTF-8 sequence that starts with the non-ASCII byte at an offset, and returns the offset after it.
    private int utf8(int p) {
        var info = Utf8.info(Byte.toUnsignedInt(input[p]));
        var length = Utf8.length(info);
        if (length == 0) {
            throw invalidUtf8(p, 1);
        }
        if (p + length > end) {
            throw invalidUtf8(p, end - p);
        }
        if (!Utf8.isValidSecond(info, Byte.toUnsignedInt(input[p + 1]))) {
            throw invalidUtf8(p, 2);
        }
        if (length > 2 && !Utf8.isContinuation(input[p + 2])) {
            throw invalidUtf8(p, Utf8.MAX_LENGTH - 1);
        }
        if (length == Utf8.MAX_LENGTH && !Utf8.isContinuation(input[p + length - 1])) {
            throw invalidUtf8(p, length);
        }
        return p + length;
    }

    private TapeException invalidUtf8(int p, int length) {
        return error(p, invalidUtf8(input, p, length));
    }

    /// Describes an invalid UTF-8 sequence, for an error message: `invalid UTF-8 sequence 0xE2 0x28`.
    static String invalidUtf8(byte[] input, int p, int length) {
        var hex = HexFormat.of().withUpperCase();
        var bytes = new StringBuilder();
        for (var i = p; i < p + length; i++) {
            bytes.append(i == p ? "0x" : " 0x").append(hex.toHexDigits(input[i]));
        }
        return "invalid UTF-8 sequence " + bytes;
    }

    // Numbers and literals.

    private void number() {
        var start = pos;
        var p = integer(pos);
        if (p < 0) {
            nonFinite(start);
            return;
        }
        var integral = true;
        if (p < end && input[p] == '.') {
            integral = false;
            p = digits(p + 1, "after '.'");
        }
        if (p < end && (input[p] == 'e' || input[p] == 'E')) {
            integral = false;
            p++;
            if (p < end && (input[p] == '+' || input[p] == '-')) {
                p++;
            }
            p = digits(p, "in the exponent");
        }
        var length = p - start;
        if (length > limits.maxNumberLength()) {
            throw error(start, "the number is longer than the maximum of " + limits.maxNumberLength() + " bytes");
        }
        append(Tape.entry(Tape.NUMBER, integral ? Tape.INTEGRAL : 0, start, Math.min(length, Tape.SATURATED)));
        pos = p;
    }

    /// Validates the sign and integer part of a number, and returns the offset after them, or -1 at the `-Infinity`
    /// of JSON5.
    private int integer(int start) {
        var p = start;
        if (input[p] == '-') {
            p++;
            if (p >= end || !isDigit(input[p])) {
                if (syntax == Syntax.JSON5 && startsWith(p, INFINITY)) {
                    return -1;
                }
                throw error(p, "expected a digit after '-', found " + describe(p));
            }
        }
        if (input[p] == '0') {
            if (p + 1 < end && isDigit(input[p + 1])) {
                throw error(p + 1, "a number cannot have a leading zero");
            }
            return p + 1;
        }
        return digits(p, "");
    }

    /// Validates one digit or more from an offset, and returns the offset after them.
    private int digits(int from, String where) {
        if (from >= end || !isDigit(input[from])) {
            throw error(from, "expected a digit " + where + ", found " + describe(from));
        }
        var p = from + 1;
        while (p < end && isDigit(input[p])) {
            p++;
        }
        return p;
    }

    private static boolean isDigit(byte b) {
        return b >= '0' && b <= '9';
    }

    /// Validates `NaN`, `Infinity` or `-Infinity`, which only JSON translated from JSON5 has.
    private void nonFinite(int start) {
        var p = input[start] == '-' ? start + 1 : start;
        if (startsWith(p, INFINITY)) {
            p += INFINITY.length;
        } else if (p == start && startsWith(p, NAN)) {
            p += NAN.length;
        } else {
            throw error(start, "expected a value, found " + describeWord(start));
        }
        append(Tape.entry(Tape.NUMBER, Tape.NON_FINITE, start, p - start));
        pos = p;
    }

    private boolean startsWith(int p, byte[] word) {
        return p + word.length <= end && Arrays.equals(input, p, p + word.length, word, 0, word.length);
    }

    /// Validates a literal: the 4 bytes after a skip of 0 or 1 byte (for the `f` of `false`) must be `expected`.
    private void literal(int expected, int skip, int tag, String literal) {
        var p = pos + skip;
        if (p + Integer.BYTES > end || (int) INTS.get(input, p) != expected) {
            throw error(pos, "expected " + literal + ", found " + describeWord(pos));
        }
        append(Tape.entry(tag, 0, pos, 0));
        pos = p + Integer.BYTES;
    }

    // Duplicate names, compared by their numbers in the table of names.

    private void checkDuplicate(int index) {
        var base = nameBases[depth];
        var count = namesTop - base;
        var number = Tape.low(tape[index]);
        if (count < SMALL_OBJECT) {
            for (var i = base; i < namesTop; i++) {
                if (Tape.low(tape[names[i]]) == number) {
                    throw duplicate(names[i], index);
                }
            }
        } else {
            insert(table(base, count), index);
        }
        if (namesTop == names.length) {
            names = Arrays.copyOf(names, names.length * 2);
        }
        names[namesTop++] = index;
    }

    /// Returns the hash table of the object at the current depth, with room for one more name: created from its first
    /// names when they no longer fit a linear scan, and enlarged when half full.
    private int[] table(int base, int count) {
        var table = tables[depth];
        if (count == SMALL_OBJECT) {
            if (table == null) {
                table = new int[INITIAL_TABLE];
            } else {
                Arrays.fill(table, 0);
            }
            for (var i = base; i < namesTop; i++) {
                insert(table, names[i]);
            }
        } else if (2 * (count + 1) > table.length) {
            table = rehash(table);
        }
        tables[depth] = table;
        return table;
    }

    /// Inserts the name at a tape index into a hash table of the numbers of names, or throws if the table has that
    /// number. An entry is the tape index plus 1; 0 is an empty slot.
    private void insert(int[] table, int index) {
        var number = Tape.low(tape[index]);
        var mask = table.length - 1;
        var slot = slot(number, mask);
        while (table[slot] != 0) {
            if (Tape.low(tape[table[slot] - 1]) == number) {
                throw duplicate(table[slot] - 1, index);
            }
            slot = (slot + 1) & mask;
        }
        table[slot] = index + 1;
    }

    private int[] rehash(int[] table) {
        var larger = new int[table.length * 2];
        var mask = larger.length - 1;
        for (var entry : table) {
            if (entry != 0) {
                var slot = slot(Tape.low(tape[entry - 1]), mask);
                while (larger[slot] != 0) {
                    slot = (slot + 1) & mask;
                }
                larger[slot] = entry;
            }
        }
        return larger;
    }

    private static int slot(int number, int mask) {
        return (int) ((number * HASH_MULTIPLIER) >>> HASH_BITS) & mask;
    }

    private String decodeName(int index) {
        return nameTable.string(Tape.low(tape[index]));
    }

    private TapeException duplicate(int first, int second) {
        return error(
                Tape.offset(tape[second]),
                "duplicate member name \"" + decodeName(first) + "\", first at "
                        + locator.position(Tape.offset(tape[first])));
    }

    // Errors.

    private TapeException error(int offset, String message) {
        return new TapeException(message + commentHint(offset), offset, path(), locator);
    }

    /// Returns why a comment at an offset is an error, for a JSON document; nothing otherwise, as JSON Lines are
    /// always JSON and JSONC and JSON5 have no comment left once translated.
    private String commentHint(int offset) {
        if (syntax == Syntax.JSON
                && offset + 1 < end
                && input[offset] == '/'
                && (input[offset + 1] == '/' || input[offset + 1] == '*')) {
            return ": comments are not allowed in JSON (JsonFormat.JSONC allows them)";
        }
        return "";
    }

    /// Returns why a trailing comma is an error, unless JSON Lines, where no other format could accept it.
    private String trailingComma() {
        return switch (syntax) {
            case JSON -> ": a trailing comma is not allowed in JSON (JsonFormat.JSON5 allows it)";
            case JSONC -> ": a trailing comma is not allowed in JSONC (JsonFormat.JSON5 allows it)";
            case JSON_LINE, JSON5 -> "";
        };
    }

    /// Returns the path of the value being parsed, as a JSON Pointer.
    private String path() {
        var path = new StringBuilder();
        for (var d = 1; d <= depth; d++) {
            if (objects[d] ? currentNames[d] < 0 : counts[d] == 0) {
                break;
            }
            var segment = objects[d] ? decodeName(currentNames[d]) : String.valueOf(counts[d] - 1);
            path.append('/').append(segment.replace("~", "~0").replace("/", "~1"));
        }
        return path.toString();
    }

    /// Describes the byte at an offset, for an error message: `'x'`, `'é' (U+00E9)`, `the control character U+0000`,
    /// `the byte 0xFF`, `the end of the document`.
    private String describe(int p) {
        return describe(input, p, end, endOfInput());
    }

    /// Describes the byte at an offset of an input, as [#describe(int)] does, `endOfInput` past its end.
    static String describe(byte[] input, int p, int end, String endOfInput) {
        if (p >= end) {
            return endOfInput;
        }
        var b = Byte.toUnsignedInt(input[p]);
        var length = Utf8.sequenceLength(b);
        String description;
        if (b == '\'') {
            description = "\"'\"";
        } else if (b >= ' ' && b < DELETE) {
            description = "'" + (char) b + "'";
        } else if (b <= DELETE) {
            description = "the control character U+%04X".formatted(b);
        } else if (length > 0 && Utf8.sequenceEnd(input, p, end) == p + length) {
            var codePoint = Utf8.codePoint(input, p, length);
            description = "'%s' (U+%04X)".formatted(Character.toString(codePoint), codePoint);
        } else {
            description = "the byte 0x%02X".formatted(b);
        }
        return description;
    }

    /// Returns what ends the input: `the end of the document`, or `the end of the line` of JSON Lines.
    private String endOfInput() {
        return syntax == Syntax.JSON_LINE ? "the end of the line" : "the end of the document";
    }

    /// Describes the word at an offset, for an error message: `'tru'`, or as [#describe(int)] when the byte there is
    /// not an ASCII letter or digit.
    private String describeWord(int p) {
        return describeWord(input, p, end, endOfInput());
    }

    /// Describes the word at an offset of an input, as [#describeWord(int)] does.
    static String describeWord(byte[] input, int p, int end, String endOfInput) {
        var q = p;
        while (q < end && q - p < MAX_WORD && input[q] >= 0 && Character.isLetterOrDigit(input[q])) {
            q++;
        }
        return q == p ? describe(input, p, end, endOfInput) : "'" + new String(input, p, q - p, UTF_8) + "'";
    }

    /// The arrays a build uses and throws away, kept in a small pool, one slot per thread modulo its size: a build
    /// takes them and gives them back, and two threads of the same slot at once only allocate.
    private static final class Scratch {

        private static final int SLOTS = 16;
        private static final AtomicReferenceArray<Scratch> POOL = new AtomicReferenceArray<>(SLOTS);

        private int[] nextNames = new int[INITIAL_NAMES];
        private int[] starts = new int[INITIAL_DEPTH];
        private int[] opens = new int[INITIAL_DEPTH];
        private int[] counts = new int[INITIAL_DEPTH];
        private boolean[] objects = new boolean[INITIAL_DEPTH];
        private int[] currentNames = new int[INITIAL_DEPTH];
        private int[] nameBases = new int[INITIAL_DEPTH];
        private int[][] tables = new int[INITIAL_DEPTH][];
        private int[] firstNames = new int[INITIAL_DEPTH];
        private int[] names = new int[INITIAL_NAMES];

        static Scratch acquire() {
            var scratch = POOL.getAndSet(slot(), null);
            return scratch != null ? scratch : new Scratch();
        }

        static void release(Scratch scratch) {
            POOL.set(slot(), scratch);
        }

        private static int slot() {
            return (int) Thread.currentThread().threadId() & (SLOTS - 1);
        }
    }
}
