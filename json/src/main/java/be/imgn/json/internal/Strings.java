package be.imgn.json.internal;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

/// Decodes the strings and names of a [Tape], which the [TapeBuilder] has validated: these methods trust their input.
public final class Strings {

    /// The length of a Unicode escape sequence: a backslash, `u` and 4 hexadecimal digits.
    static final int UNICODE_ESCAPE_LENGTH = 6;
    /// The number of hexadecimal digits of a Unicode escape sequence.
    static final int HEX_DIGITS = 4;

    private static final int HEX_RADIX = 16;

    private Strings() {}

    /// Decodes the string or name at an index of a tape: a name from the table of names, which decodes it once.
    public static String decode(Tape tape, int index) {
        var entry = tape.entries()[index];
        if (Tape.tag(entry) == Tape.NAME) {
            return tape.names().string(Tape.low(entry));
        }
        return decode(tape.input(), Tape.offset(entry) + 1, tape.length(index), Tape.flags(entry));
    }

    /// Decodes the bytes of a validated string, between its quotes.
    ///
    /// @param flags the flags of the string on the tape
    public static String decode(byte[] input, int from, int length, int flags) {
        if ((flags & Tape.ESCAPED) == 0) {
            // ISO-8859-1 copies ASCII bytes straight into a compact string.
            return new String(input, from, length, (flags & Tape.NON_ASCII) == 0 ? ISO_8859_1 : UTF_8);
        }
        return unescape(input, from, from + length);
    }

    private static String unescape(byte[] input, int from, int to) {
        // Each escape sequence and each UTF-8 sequence is at least as long in bytes as in UTF-16 code units.
        var chars = new char[to - from];
        var n = 0;
        var p = from;
        while (p < to) {
            var b = input[p];
            if (b == '\\') {
                if (input[p + 1] == 'u') {
                    chars[n++] = (char) hex4(input, p + 2);
                    p += UNICODE_ESCAPE_LENGTH;
                } else {
                    chars[n++] = unescape(input[p + 1]);
                    p += 2;
                }
            } else if (b >= 0) {
                chars[n++] = (char) b;
                p++;
            } else {
                var length = Utf8.sequenceLength(Byte.toUnsignedInt(b));
                var codePoint = Utf8.codePoint(input, p, length);
                if (length < Utf8.MAX_LENGTH) {
                    chars[n++] = (char) codePoint;
                } else {
                    chars[n++] = Character.highSurrogate(codePoint);
                    chars[n++] = Character.lowSurrogate(codePoint);
                }
                p += length;
            }
        }
        return new String(chars, 0, n);
    }

    private static char unescape(byte escaped) {
        return switch (escaped) {
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            default -> (char) escaped;
        };
    }

    /// Returns the value of the 4 hexadecimal digits at an offset, or -1 if one of them is not.
    static int hex4(byte[] input, int from) {
        var value = 0;
        for (var i = from; i < from + HEX_DIGITS; i++) {
            var digit = Character.digit(input[i], HEX_RADIX);
            if (digit < 0) {
                return -1;
            }
            value = value * HEX_RADIX + digit;
        }
        return value;
    }
}
