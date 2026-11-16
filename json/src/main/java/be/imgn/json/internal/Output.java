package be.imgn.json.internal;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReferenceArray;

/// Writes JSON as UTF-8 bytes into a growing buffer, compact or indented. It places the commas, line breaks and
/// indentation, but trusts its caller with the grammar: [be.imgn.json.JsonStreamWriter] checks it for the public API.
public final class Output {

    private static final int INITIAL_CAPACITY = 256;
    private static final int INITIAL_DEPTH = 16;
    // Strings are written in chunks of chars.
    private static final int CHUNK = 1024;
    private static final int TWO_BYTES = 0x800;
    private static final int SIX_BITS = 6;
    private static final int TWELVE_BITS = 12;
    private static final int EIGHTEEN_BITS = 18;
    private static final int LOW_SIX_BITS = 0x3F;
    private static final int CONTINUATION = 0x80;
    private static final int LEAD_TWO = 0xC0;
    private static final int LEAD_THREE = 0xE0;
    private static final int LEAD_FOUR = 0xF0;
    // The longest escape sequence: \u001F.
    private static final int MAX_ESCAPE = 6;
    // The digits of the longest long, with its sign.
    private static final int MAX_LONG_LENGTH = 20;
    private static final int RADIX = 10;
    private static final int PAIR = RADIX * RADIX;
    // The units and tens digits of the numbers from 0 to 99.
    private static final byte[] UNITS = new byte[PAIR];
    private static final byte[] TENS = new byte[PAIR];
    // 10^n, by n, up to the largest a long holds.
    private static final long[] POWERS_OF_TEN = new long[MAX_LONG_LENGTH - 1];

    static {
        for (var i = 0; i < PAIR; i++) {
            UNITS[i] = (byte) ('0' + i % RADIX);
            TENS[i] = (byte) ('0' + i / RADIX);
        }
        POWERS_OF_TEN[0] = 1;
        for (var i = 1; i < POWERS_OF_TEN.length; i++) {
            POWERS_OF_TEN[i] = POWERS_OF_TEN[i - 1] * RADIX;
        }
    }

    private static final int HEX_DIGIT = 0xF;
    private static final int HEX_BITS = 4;
    private static final int MAX_ARRAY = Integer.MAX_VALUE - 8;
    // The buffers kept for reuse, one slot per thread modulo their number, and the largest kept.
    private static final int POOL_SLOTS = 8;
    private static final int MAX_POOLED = 1 << 20;
    private static final AtomicReferenceArray<byte[]> POOL = new AtomicReferenceArray<>(POOL_SLOTS);
    private static final byte[] HEX = "0123456789abcdef".getBytes(UTF_8);
    private static final int ASCII = 0x80;
    // For each ASCII char: 0 if it is written as it is, or the letter of its escape sequence.
    private static final byte[] ESCAPES = escapes();

    private final int indent;
    private byte[] buffer;
    private final boolean fromPool;
    private int size;
    private int depth;
    private boolean[] hasItems = new boolean[INITIAL_DEPTH];
    private boolean afterName;

    /// Creates an output.
    ///
    /// @param indent the number of spaces per level, or 0 for compact output
    public Output(int indent) {
        this(indent, new byte[INITIAL_CAPACITY], false);
    }

    private Output(int indent, byte[] buffer, boolean fromPool) {
        this.indent = indent;
        this.buffer = buffer;
        this.fromPool = fromPool;
    }

    /// Returns an output whose buffer comes from a pool, as Jackson recycles its buffers: a buffer that grew to the
    /// size of earlier documents spares the next ones its growth. [#release()] gives it back once the output was
    /// read, after which the output must not be used.
    public static Output pooled(int indent) {
        var buffer = POOL.getAndSet(slot(), null);
        return new Output(indent, buffer != null ? buffer : new byte[INITIAL_CAPACITY], true);
    }

    /// Gives the buffer of a pooled output back to the pool, unless it grew too large to keep.
    public void release() {
        if (fromPool && buffer.length <= MAX_POOLED) {
            POOL.set(slot(), buffer);
        }
    }

    private static int slot() {
        return (int) Thread.currentThread().threadId() & (POOL_SLOTS - 1);
    }

    private static byte[] escapes() {
        var escapes = new byte[ASCII];
        for (var c = 0; c < ' '; c++) {
            escapes[c] = 'u';
        }
        escapes['\b'] = 'b';
        escapes['\f'] = 'f';
        escapes['\n'] = 'n';
        escapes['\r'] = 'r';
        escapes['\t'] = 't';
        escapes['"'] = '"';
        escapes['\\'] = '\\';
        return escapes;
    }

    /// Returns the bytes of a string as JSON: quoted, escaped, in UTF-8.
    ///
    /// @throws IllegalArgumentException if the string has an unpaired surrogate
    public static byte[] quote(String value) {
        var output = new Output(0);
        output.writeString(value);
        return output.toByteArray();
    }

    /// Starts an object.
    public void beginObject() {
        beforeValue();
        open('{');
    }

    /// Ends an object.
    public void endObject() {
        close('}');
    }

    /// Starts an array.
    public void beginArray() {
        beforeValue();
        open('[');
    }

    /// Ends an array.
    public void endArray() {
        close(']');
    }

    /// Writes a member name.
    public void name(String name) {
        beforeItem();
        writeString(name);
        colon();
    }

    /// Writes a member name already quoted and escaped, as [#quote(String)] returns it.
    public void name(byte[] quoted) {
        name(quoted, 0, quoted.length);
    }

    /// Writes a member name already quoted and escaped, from a range of bytes, such as a name without escape sequences
    /// as written in a document.
    public void name(byte[] quoted, int from, int length) {
        beforeItem();
        writeBytes(quoted, from, length);
        colon();
    }

    /// Writes a string.
    ///
    /// @throws IllegalArgumentException if the string has an unpaired surrogate
    public void string(String value) {
        beforeValue();
        writeString(value);
    }

    /// Writes bytes that are already a whole JSON value, such as the text of a number or a string without escapes as
    /// written in a document.
    public void raw(byte[] bytes, int from, int length) {
        beforeValue();
        writeBytes(bytes, from, length);
    }

    /// Writes an integer.
    public void number(long value) {
        beforeValue();
        writeLong(value);
    }

    /// Writes a finite `double`, in its shortest form that reads back to the same value.
    public void number(double value) {
        beforeValue();
        writeAscii(Double.toString(value));
    }

    /// Writes a finite `float`, in its shortest form that reads back to the same value.
    public void number(float value) {
        beforeValue();
        writeAscii(Float.toString(value));
    }

    /// Writes the text of a number, which must follow the grammar of RFC 8259.
    public void number(String text) {
        beforeValue();
        writeAscii(text);
    }

    /// Writes `true` or `false`.
    public void bool(boolean value) {
        beforeValue();
        writeAscii(value ? "true" : "false");
    }

    /// Writes `null`.
    public void nullValue() {
        beforeValue();
        writeAscii("null");
    }

    /// Returns the bytes written.
    public byte[] toByteArray() {
        return Arrays.copyOf(buffer, size);
    }

    /// Returns the text written.
    @Override
    public String toString() {
        return new String(buffer, 0, size, UTF_8);
    }

    /// Writes the bytes written to a stream, and returns their number.
    public int writeTo(OutputStream out) throws IOException {
        out.write(buffer, 0, size);
        return size;
    }

    private void open(char bracket) {
        ensure(1);
        buffer[size++] = (byte) bracket;
        depth++;
        if (depth == hasItems.length) {
            hasItems = Arrays.copyOf(hasItems, depth * 2);
        }
        hasItems[depth] = false;
    }

    private void close(char bracket) {
        var hadItems = hasItems[depth];
        depth--;
        if (hadItems) {
            newline();
        }
        ensure(1);
        buffer[size++] = (byte) bracket;
    }

    private void beforeValue() {
        if (afterName) {
            afterName = false;
        } else {
            beforeItem();
        }
    }

    private void beforeItem() {
        if (depth > 0) {
            if (hasItems[depth]) {
                ensure(1);
                buffer[size++] = ',';
            }
            hasItems[depth] = true;
            newline();
        }
    }

    private void colon() {
        ensure(2);
        buffer[size++] = ':';
        if (indent > 0) {
            buffer[size++] = ' ';
        }
        afterName = true;
    }

    private void newline() {
        if (indent > 0) {
            var spaces = depth * indent;
            ensure(1 + spaces);
            buffer[size++] = '\n';
            Arrays.fill(buffer, size, size + spaces, (byte) ' ');
            size += spaces;
        }
    }

    private void writeAscii(String text) {
        var length = text.length();
        ensure(length);
        for (var i = 0; i < length; i++) {
            buffer[size++] = (byte) text.charAt(i);
        }
    }

    private void writeBytes(byte[] bytes, int from, int length) {
        ensure(length);
        System.arraycopy(bytes, from, buffer, size, length);
        size += length;
    }

    /// Writes a string. As most strings are ASCII without escapes, the start of a string is copied in a loop of
    /// its own, read from the string as it is; the rest is written in chunks, each with room for the longest
    /// form of each char: an escape sequence.
    private void writeString(String value) {
        var length = value.length();
        ensure(length + 2);
        var out = buffer;
        var n = size;
        out[n++] = '"';
        var i = 0;
        while (i < length) {
            var c = value.charAt(i);
            if (c >= ASCII || ESCAPES[c] != 0) {
                break;
            }
            out[n++] = (byte) c;
            i++;
        }
        size = n;
        while (i < length) {
            var chunkEnd = Math.min(length, i + CHUNK);
            // One char more: the low surrogate of a pair that straddles the end of the chunk.
            ensure((chunkEnd - i + 1) * MAX_ESCAPE + 1);
            i = writeChars(value, i, chunkEnd);
        }
        ensure(1);
        buffer[size++] = '"';
    }

    /// Writes the chars of a string from an index up to the end of a chunk, and returns the index after the last one
    /// written.
    private int writeChars(String value, int from, int chunkEnd) {
        var i = from;
        var out = buffer;
        var n = size;
        while (i < chunkEnd) {
            var c = value.charAt(i++);
            if (c < ASCII) {
                var escape = ESCAPES[c];
                if (escape == 0) {
                    out[n++] = (byte) c;
                } else {
                    n = escape(c, n);
                }
            } else if (c < TWO_BYTES) {
                out[n++] = (byte) (LEAD_TWO | (c >>> SIX_BITS));
                out[n++] = (byte) (CONTINUATION | (c & LOW_SIX_BITS));
            } else if (!Character.isSurrogate(c)) {
                out[n++] = (byte) (LEAD_THREE | (c >>> TWELVE_BITS));
                out[n++] = (byte) (CONTINUATION | ((c >>> SIX_BITS) & LOW_SIX_BITS));
                out[n++] = (byte) (CONTINUATION | (c & LOW_SIX_BITS));
            } else if (Character.isHighSurrogate(c)
                    && i < value.length()
                    && Character.isLowSurrogate(value.charAt(i))) {
                var codePoint = Character.toCodePoint(c, value.charAt(i++));
                out[n++] = (byte) (LEAD_FOUR | (codePoint >>> EIGHTEEN_BITS));
                out[n++] = (byte) (CONTINUATION | ((codePoint >>> TWELVE_BITS) & LOW_SIX_BITS));
                out[n++] = (byte) (CONTINUATION | ((codePoint >>> SIX_BITS) & LOW_SIX_BITS));
                out[n++] = (byte) (CONTINUATION | (codePoint & LOW_SIX_BITS));
            } else {
                throw unpairedSurrogate(c, i - 1);
            }
        }
        size = n;
        return i;
    }

    /// Writes the digits of a long straight into the buffer.
    private void writeLong(long value) {
        if (value == Long.MIN_VALUE) {
            writeAscii(Long.toString(value));
            return;
        }
        ensure(MAX_LONG_LENGTH);
        var rest = value;
        if (rest < 0) {
            buffer[size++] = '-';
            rest = -rest;
        }
        var digits = 1;
        while (digits < POWERS_OF_TEN.length && rest >= POWERS_OF_TEN[digits]) {
            digits++;
        }
        var end = size + digits;
        var p = end;
        // Two digits at a time, from a table: half the divisions.
        while (rest >= PAIR) {
            var quotient = rest / PAIR;
            var pair = (int) (rest - quotient * PAIR);
            buffer[--p] = UNITS[pair];
            buffer[--p] = TENS[pair];
            rest = quotient;
        }
        if (rest >= RADIX) {
            buffer[--p] = UNITS[(int) rest];
            buffer[--p] = TENS[(int) rest];
        } else {
            buffer[--p] = (byte) ('0' + rest);
        }
        size = end;
    }

    private static IllegalArgumentException unpairedSurrogate(char c, int index) {
        return new IllegalArgumentException(
                "unpaired surrogate U+%04X at index %d of the string".formatted((int) c, index));
    }

    /// Writes the escape sequence of an ASCII char at an index of the buffer, and returns the index after it.
    private int escape(char c, int at) {
        var n = at;
        var letter = ESCAPES[c];
        buffer[n++] = '\\';
        buffer[n++] = letter;
        if (letter == 'u') {
            buffer[n++] = '0';
            buffer[n++] = '0';
            buffer[n++] = HEX[c >>> HEX_BITS];
            buffer[n++] = HEX[c & HEX_DIGIT];
        }
        return n;
    }

    private void ensure(int more) {
        var needed = (long) size + more;
        if (needed > buffer.length) {
            if (needed > MAX_ARRAY) {
                throw new OutOfMemoryError("the output is larger than an array can hold");
            }
            buffer = Arrays.copyOf(buffer, (int) Math.max(needed, Math.min(MAX_ARRAY, buffer.length * 2L)));
        }
    }
}
