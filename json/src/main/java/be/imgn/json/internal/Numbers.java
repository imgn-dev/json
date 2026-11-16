package be.imgn.json.internal;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.math.BigDecimal;
import java.math.BigInteger;

/// Converts the text of validated numbers: these methods trust their input to follow the grammar of RFC 8259.
public final class Numbers {

    // An integer of up to 18 digits always fits in a long.
    private static final int MAX_LONG_DIGITS = 18;
    private static final int RADIX = 10;

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

    /// Returns whether a number is an integer of at most 18 digits, which [#parseLong(byte[], int, int)] converts.
    ///
    /// @param flags the flags of the number on the tape
    public static boolean isSmallInteger(byte[] input, int from, int length, int flags) {
        return (flags & Tape.INTEGRAL) != 0 && length - (input[from] == '-' ? 1 : 0) <= MAX_LONG_DIGITS;
    }

    /// Returns the value of an integer of at most 18 digits.
    public static long parseLong(byte[] input, int from, int length) {
        var negative = input[from] == '-';
        var value = 0L;
        for (var p = negative ? from + 1 : from; p < from + length; p++) {
            value = value * RADIX + (input[p] - '0');
        }
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
    public static BigDecimal toBigDecimal(byte[] input, int from, int length) {
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

    /// Returns the `double` nearest a number, which is infinite when the number is too large.
    public static double parseDouble(byte[] input, int from, int length) {
        var parsed = new Decimal(input, from, length);
        if (parsed.digits <= MAX_DOUBLE_DIGITS && Math.abs(parsed.exponent) < DOUBLE_POWERS.length) {
            double value = parsed.mantissa;
            value = parsed.exponent >= 0
                    ? value * DOUBLE_POWERS[parsed.exponent]
                    : value / DOUBLE_POWERS[-parsed.exponent];
            return parsed.negative ? -value : value;
        }
        if (parsed.digits <= MAX_EXACT_DIGITS
                && parsed.mantissa != 0
                && parsed.exponent >= EiselLemire.MIN_EXPONENT
                && parsed.exponent <= EiselLemire.MAX_EXPONENT) {
            var value = EiselLemire.toDouble(parsed.mantissa, parsed.exponent, parsed.negative);
            if (!Double.isNaN(value)) {
                return value;
            }
        }
        return Double.parseDouble(new String(input, from, length, ISO_8859_1));
    }

    /// Returns the `float` nearest a number, which is infinite when the number is too large.
    public static float parseFloat(byte[] input, int from, int length) {
        var parsed = new Decimal(input, from, length);
        if (parsed.digits <= MAX_FLOAT_DIGITS && Math.abs(parsed.exponent) < FLOAT_POWERS.length) {
            float value = parsed.mantissa;
            value = parsed.exponent >= 0
                    ? value * FLOAT_POWERS[parsed.exponent]
                    : value / FLOAT_POWERS[-parsed.exponent];
            return parsed.negative ? -value : value;
        }
        return Float.parseFloat(new String(input, from, length, ISO_8859_1));
    }

    private static boolean isDigit(byte b) {
        return b >= '0' && b <= '9';
    }

    /// A number as a mantissa of significant digits and a power of ten, as far as the fast paths need: more digits
    /// than they take only count, and leave the mantissa inexact.
    private static final class Decimal {

        private final boolean negative;
        private long mantissa;
        // The significant digits: those after the leading zeros.
        private int digits;
        private int exponent;

        Decimal(byte[] input, int from, int length) {
            var to = from + length;
            var p = from;
            negative = input[p] == '-';
            if (negative) {
                p++;
            }
            for (; p < to && isDigit(input[p]); p++) {
                add(input[p]);
            }
            if (p < to && input[p] == '.') {
                for (p++; p < to && isDigit(input[p]); p++) {
                    add(input[p]);
                    exponent--;
                }
            }
            if (p < to) {
                var written = exponent(input, p + 1, to);
                if (Math.abs(written) >= MAX_FAST_EXPONENT) {
                    // Capped: only the slow path gives the right value.
                    digits = Integer.MAX_VALUE;
                }
                exponent += written;
            }
        }

        private void add(byte digit) {
            if (digits < MAX_EXACT_DIGITS) {
                mantissa = mantissa * RADIX + (digit - '0');
            }
            if (digits > 0 || digit != '0') {
                digits++;
            }
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
    }
}
