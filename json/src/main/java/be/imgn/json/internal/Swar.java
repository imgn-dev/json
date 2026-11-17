package be.imgn.json.internal;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/// Finds bytes 8 at a time, as a `long`, for the translations of JSONC and JSON5.
final class Swar {

    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final long ONES = 0x0101_0101_0101_0101L;
    private static final long HIGH_BITS = 0x8080_8080_8080_8080L;
    private static final long CONTROLS = ONES * ' ';
    private static final long QUOTES = ONES * '"';
    private static final long BACKSLASHES = ONES * '\\';

    private Swar() {}

    /// Returns the offset of the first byte from an offset that is one of two, or `end` if none is.
    static int indexOfEither(byte[] input, int from, int end, byte first, byte second) {
        var firsts = ONES * Byte.toUnsignedLong(first);
        var seconds = ONES * Byte.toUnsignedLong(second);
        var p = from;
        while (p + Long.BYTES <= end) {
            var word = (long) LONGS.get(input, p);
            var mask = zeroBytes(word ^ firsts) | zeroBytes(word ^ seconds);
            if (mask != 0) {
                return p + Long.numberOfTrailingZeros(mask) / Byte.SIZE;
            }
            p += Long.BYTES;
        }
        while (p < end && input[p] != first && input[p] != second) {
            p++;
        }
        return p;
    }

    /// Returns the offset of the first byte from an offset that is a quote of either kind, a backslash, or below
    /// U+0020, or `end` if none is: the first one that the translation of a JSON5 string does not copy as it is.
    static int indexOfStringSpecial(byte[] input, int from, int end, byte quote) {
        var quotes = ONES * Byte.toUnsignedLong(quote);
        var p = from;
        while (p + Long.BYTES <= end) {
            var word = (long) LONGS.get(input, p);
            var mask = zeroBytes(word ^ quotes)
                    | zeroBytes(word ^ QUOTES)
                    | zeroBytes(word ^ BACKSLASHES)
                    | ((word - CONTROLS) & ~word & HIGH_BITS);
            if (mask != 0) {
                return p + Long.numberOfTrailingZeros(mask) / Byte.SIZE;
            }
            p += Long.BYTES;
        }
        while (p < end) {
            var b = input[p];
            if (b == quote || b == '"' || b == '\\' || (b >= 0 && b < ' ')) {
                return p;
            }
            p++;
        }
        return p;
    }

    /// Returns a mask with the high bit set in each byte of a word that is zero. Only the lowest set bit is exact: a
    /// borrow can set bits above it.
    private static long zeroBytes(long word) {
        return (word - ONES) & ~word & HIGH_BITS;
    }
}
