package be.imgn.json.internal;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteOrder;

/// Converts the text of validated numbers: these methods trust their input to follow the grammar of RFC 8259, or to be
/// `NaN`, `Infinity` or `-Infinity`.
public final class Numbers {

    // An integer of up to 18 digits always fits in a long.
    private static final int MAX_LONG_DIGITS = 18;
    private static final int RADIX = 10;

    // Digits are read 8 at a time, as a little-endian `long` whose first byte is the first digit.
    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final long EIGHT_DIGITS = 100_000_000L;
    private static final long ZEROS = 0x3030_3030_3030_3030L;
    private static final long HIGH_NIBBLES = 0xF0F0_F0F0_F0F0_F0F0L;
    private static final long SIXES = 0x0606_0606_0606_0606L;
    private static final long LOW_BYTES = 0x0000_00FF_0000_00FFL;
    // Combine pairs of digits, then pairs of those, with one multiplication each: see eightDigits.
    private static final long TENS = 10;
    private static final long HUNDREDS_AND_MILLIONS = 100 + (1_000_000L << Integer.SIZE);
    private static final long ONES_AND_TEN_THOUSANDS = 1 + (10_000L << Integer.SIZE);

    // The fast path of Clinger: a mantissa and a power of ten both exact in a double give the nearest double with one
    // multiplication or division. 15 digits stay below 2^53; 10^22 is the largest exact power of ten.
    private static final int MAX_DOUBLE_DIGITS = 15;
    private static final double[] DOUBLE_POWERS = {
        1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19,
        1e20, 1e21, 1e22
    };
    // The same for a float: 7 digits stay below 2^24; 10^10 is the largest exact power of ten.
    private static final int MAX_FLOAT_DIGITS = 7;
    private static final float[] FLOAT_POWERS = {1e0f, 1e1f, 1e2f, 1e3f, 1e4f, 1e5f, 1e6f, 1e7f, 1e8f, 1e9f, 1e10f};
    // A significand of up to 19 digits is exact in an unsigned long, for the algorithm of Eisel and Lemire.
    private static final int MAX_EXACT_DIGITS = 19;
    // Beyond this exponent, the slow path decides between zero, infinity and a value.
    private static final int MAX_FAST_EXPONENT = 1_000;

    // A BigInteger of more digits takes too long to compute and too much memory: 1e1000000000 is 40 bytes long.
    private static final int MAX_BIG_INTEGER_DIGITS = 100_000;

    private Numbers() {}

    /// Returns whether a number is an integer of at most 18 digits, which [#parseLong(byte\[\], int, int)] converts.
    ///
    /// @param flags the flags of the number on the tape
    public static boolean isSmallInteger(byte[] input, int from, int length, int flags) {
        return (flags & Tape.INTEGRAL) != 0 && length - (input[from] == '-' ? 1 : 0) <= MAX_LONG_DIGITS;
    }

    /// Returns the value of an integer of at most 18 digits.
    public static long parseLong(byte[] input, int from, int length) {
        var negative = input[from] == '-';
        var value = accumulate(0, input, negative ? from + 1 : from, from + length);
        return negative ? -value : value;
    }

    /// Returns the exact value of a number as a `long`, if it is an integer between two bounds.
    ///
    /// @throws ArithmeticException if the number is not an integer between the bounds
    public static long toLong(byte[] input, int from, int length, int flags, long min, long max) {
        long value;
        if (isSmallInteger(input, from, length, flags)) {
            value = parseLong(input, from, length);
        } else {
            // 1.0 and 1e2 are integers too; longValueExact rejects a huge exponent at once.
            value = toBigDecimal(input, from, length).longValueExact();
        }
        if (value < min || value > max) {
            throw new ArithmeticException("out of range");
        }
        return value;
    }

    /// Returns the exact value of a number as a `BigDecimal`.
    ///
    /// @throws ArithmeticException if the number is `NaN` or an infinity
    public static BigDecimal toBigDecimal(byte[] input, int from, int length) {
        if (isNonFinite(input, from, length)) {
            throw new ArithmeticException("not finite");
        }
        return new BigDecimal(new String(input, from, length, ISO_8859_1));
    }

    /// Returns the exact value of a number as a `BigInteger`.
    ///
    /// @throws ArithmeticException if the number is not an integer, or has more than 100 000 digits
    public static BigInteger toBigInteger(byte[] input, int from, int length, int flags) {
        if (isSmallInteger(input, from, length, flags)) {
            return BigInteger.valueOf(parseLong(input, from, length));
        }
        var decimal = toBigDecimal(input, from, length);
        if ((long) decimal.precision() - decimal.scale() > MAX_BIG_INTEGER_DIGITS) {
            throw new ArithmeticException("more than " + MAX_BIG_INTEGER_DIGITS + " digits");
        }
        return decimal.toBigIntegerExact();
    }

    /// Returns whether a number is `NaN`, `Infinity` or `-Infinity`, which only JSON5 has: the only numbers that do
    /// not end with a digit.
    public static boolean isNonFinite(byte[] input, int from, int length) {
        var last = input[from + length - 1];
        return last == 'y' || last == 'N';
    }

    /// Returns the `double` nearest a number, which is infinite when the number is too large; `NaN` and the
    /// infinities are themselves.
    public static double parseDouble(byte[] input, int from, int length) {
        if (isNonFinite(input, from, length)) {
            return parseSlowly(input, from, length, false);
        }
        return parse(input, from, length, false);
    }

    /// Returns the `float` nearest a number, which is infinite when the number is too large; `NaN` and the
    /// infinities are themselves.
    public static float parseFloat(byte[] input, int from, int length) {
        if (isNonFinite(input, from, length)) {
            return (float) parseSlowly(input, from, length, true);
        }
        // A float widened to a double, then narrowed back, is the same float.
        return (float) parse(input, from, length, true);
    }

    /// Returns the nearest `double`, or the nearest `float` widened to a `double`, of a finite number: from its
    /// significand and exponent when a fast path can tell it, from the JDK otherwise.
    private static double parse(byte[] input, int from, int length, boolean single) {
        var decimal = new Decimal(input, from, from + length);
        var value = single
                ? toFloat(decimal.significand, decimal.digits, decimal.exponent, decimal.negative)
                : toDouble(decimal.significand, decimal.digits, decimal.exponent, decimal.negative);
        return Double.isNaN(value) ? parseSlowly(input, from, length, single) : value;
    }

    /// Returns the `double` nearest `significand × 10^exponent`, or NaN if the fast paths cannot tell it.
    ///
    /// @param digits the significant digits of the number, the significand being exact if there are at most 19
    private static double toDouble(long significand, int digits, int exponent, boolean negative) {
        if (digits <= MAX_DOUBLE_DIGITS && Math.abs(exponent) < DOUBLE_POWERS.length) {
            double value = significand;
            value = exponent >= 0 ? value * DOUBLE_POWERS[exponent] : value / DOUBLE_POWERS[-exponent];
            return negative ? -value : value;
        }
        if (digits <= MAX_EXACT_DIGITS
                && significand != 0
                && exponent >= EiselLemire.MIN_EXPONENT
                && exponent <= EiselLemire.MAX_EXPONENT) {
            return EiselLemire.toDouble(significand, exponent, negative);
        }
        return Double.NaN;
    }

    /// Returns the `float` nearest `significand × 10^exponent`, widened to a `double`, or NaN if the fast path cannot
    /// tell it.
    private static double toFloat(long significand, int digits, int exponent, boolean negative) {
        if (digits <= MAX_FLOAT_DIGITS && Math.abs(exponent) < FLOAT_POWERS.length) {
            float value = significand;
            value = exponent >= 0 ? value * FLOAT_POWERS[exponent] : value / FLOAT_POWERS[-exponent];
            return negative ? -value : value;
        }
        return Double.NaN;
    }

    private static double parseSlowly(byte[] input, int from, int length, boolean single) {
        var text = new String(input, from, length, ISO_8859_1);
        return single ? Float.parseFloat(text) : Double.parseDouble(text);
    }

    /// Returns a significand followed by the digits between two offsets, modulo 2^64.
    private static long accumulate(long significand, byte[] input, int from, int to) {
        var value = significand;
        var p = from;
        for (; p + Long.BYTES <= to; p += Long.BYTES) {
            value = value * EIGHT_DIGITS + eightDigits((long) LONGS.get(input, p));
        }
        for (; p < to; p++) {
            value = value * RADIX + (input[p] - '0');
        }
        return value;
    }

    /// Returns a mask of the high nibble of each of 8 bytes that is not an ASCII digit. The bytes of a number are
    /// ASCII, so adding 6 to one never carries into the next.
    private static long nonDigits(long chunk) {
        return ((chunk & HIGH_NIBBLES) ^ ZEROS) | (((chunk + SIXES) & HIGH_NIBBLES) ^ ZEROS);
    }

    /// Returns the value of 8 ASCII digits, the first one in the lowest byte: each step combines neighbours, with
    /// one multiplication for all of them.
    private static long eightDigits(long chunk) {
        var digits = chunk - ZEROS;
        var pairs = digits * TENS + (digits >>> Byte.SIZE);
        return ((pairs & LOW_BYTES) * HUNDREDS_AND_MILLIONS
                        + ((pairs >>> Short.SIZE) & LOW_BYTES) * ONES_AND_TEN_THOUSANDS)
                >>> Integer.SIZE;
    }

    /// Returns the digits of a number after its leading zeros, which JSON writes only as `0.000…`.
    private static int significantDigits(byte[] input, int start, int integerEnd, int end) {
        if (input[start] != '0') {
            return end - start - (end == integerEnd ? 0 : 1);
        }
        var p = integerEnd + 1;
        while (p < end && input[p] == '0') {
            p++;
        }
        return end - p;
    }

    /// Returns the exponent after the `e`, capped where only the slow path can tell the result.
    private static int exponent(byte[] input, int from, int to) {
        var p = from;
        var negative = input[p] == '-';
        if (negative || input[p] == '+') {
            p++;
        }
        var value = 0;
        for (; p < to && value < MAX_FAST_EXPONENT; p++) {
            value = value * RADIX + (input[p] - '0');
        }
        return negative ? -value : value;
    }

    private static boolean isDigit(byte b) {
        return b >= '0' && b <= '9';
    }

    /// A number as a significand and a power of ten, as far as the fast paths need: the significand is exact for up to
    /// 19 significant digits, and more digits only count. Its digits are read 8 at a time while they last. Compiled
    /// with its caller, it is replaced by its fields: reading a number allocates nothing.
    private static final class Decimal {

        private final boolean negative;
        private long significand;
        // The significant digits: those after the leading zeros.
        private int digits;
        private int exponent;

        Decimal(byte[] input, int from, int to) {
            var start = input[from] == '-' ? from + 1 : from;
            negative = start != from;
            var integerEnd = digits(input, start, to);
            var end = integerEnd;
            if (end < to && input[end] == '.') {
                end = digits(input, end + 1, to);
                exponent = integerEnd + 1 - end;
            }
            digits = end - start - (end == integerEnd ? 0 : 1);
            if (digits > MAX_EXACT_DIGITS) {
                digits = significantDigits(input, start, integerEnd, end);
            }
            if (end < to) {
                var written = exponent(input, end + 1, to);
                if (Math.abs(written) >= MAX_FAST_EXPONENT) {
                    // Capped: only the slow path gives the right value.
                    digits = Integer.MAX_VALUE;
                }
                exponent += written;
            }
        }

        /// Adds the digits from an offset to the significand, and returns the offset after them.
        private int digits(byte[] input, int from, int to) {
            var value = significand;
            var p = from;
            for (; p + Long.BYTES <= to; p += Long.BYTES) {
                var chunk = (long) LONGS.get(input, p);
                if (nonDigits(chunk) != 0) {
                    break;
                }
                value = value * EIGHT_DIGITS + eightDigits(chunk);
            }
            for (; p < to && isDigit(input[p]); p++) {
                value = value * RADIX + (input[p] - '0');
            }
            significand = value;
            return p;
        }
    }
}
