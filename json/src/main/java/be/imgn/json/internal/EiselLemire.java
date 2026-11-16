package be.imgn.json.internal;

import java.math.BigInteger;

/// Converts a decimal `w × 10^q`, with `w` of up to 19 digits, to the nearest `double`, with the algorithm of Eisel and
/// Lemire: one 64×128-bit multiplication by a truncated power of five, read from a table built once.
///
/// With `m`, the power of five truncated to 128 bits, the exact product `w × 5^q` lies in `[w × m, w × m + w)` once
/// scaled: below the 64 low bits of the 192-bit product. The 64 high bits are therefore exact unless the 64 middle ones
/// are all ones, and the rounding is exact unless the value may be halfway between two doubles. In those two cases, and
/// for subnormal or infinite results, [#toDouble(long, int, boolean)] declines, and the caller parses the number
/// slowly.
final class EiselLemire {

    /// The smallest decimal exponent in the table: below, every 19-digit significand is a subnormal or zero.
    static final int MIN_EXPONENT = -342;
    /// The largest decimal exponent in the table: above, every significand overflows.
    static final int MAX_EXPONENT = 308;

    private static final int TABLE_BITS = 128;
    private static final int FIVE = 5;
    private static final int MANTISSA_BITS = 52;
    private static final int EXPONENT_BIAS = 1023;
    private static final int MAX_BIASED_EXPONENT = 2047;
    // The bits of the high word of the product below the 54 that make the mantissa and its rounding bit, when the
    // product has 191 bits; one more when it has 192.
    private static final int DROPPED_BITS = 9;
    private static final long MANTISSA_MASK = (1L << MANTISSA_BITS) - 1;
    private static final long OVERFLOWED_MANTISSA = 1L << (MANTISSA_BITS + 1);

    // For each exponent q: the 128 bits of 5^q, normalized, as a high and a low word, and the power of two they are
    // scaled by: 5^q is about (high, low) × 2^-shift.
    private static final long[] HIGH = new long[MAX_EXPONENT - MIN_EXPONENT + 1];
    private static final long[] LOW = new long[MAX_EXPONENT - MIN_EXPONENT + 1];
    private static final int[] SHIFT = new int[MAX_EXPONENT - MIN_EXPONENT + 1];

    static {
        var five = BigInteger.valueOf(FIVE);
        for (var q = MIN_EXPONENT; q <= MAX_EXPONENT; q++) {
            BigInteger truncated;
            int shift;
            if (q >= 0) {
                var power = five.pow(q);
                shift = TABLE_BITS - power.bitLength();
                truncated = shift >= 0 ? power.shiftLeft(shift) : power.shiftRight(-shift);
            } else {
                // floor(2^k / 5^-q), with k such that the quotient has 128 bits: 5^-q is never a power of two.
                var divisor = five.pow(-q);
                shift = divisor.bitLength() + TABLE_BITS - 1;
                truncated = BigInteger.ONE.shiftLeft(shift).divide(divisor);
            }
            HIGH[q - MIN_EXPONENT] = truncated.shiftRight(Long.SIZE).longValue();
            LOW[q - MIN_EXPONENT] = truncated.longValue();
            SHIFT[q - MIN_EXPONENT] = shift;
        }
    }

    private EiselLemire() {}

    /// Returns the `double` nearest `w × 10^q`, or NaN if this algorithm cannot tell it.
    ///
    /// @param significand `w`, an unsigned integer of up to 19 digits, not 0
    /// @param exponent `q`, from [#MIN_EXPONENT] to [#MAX_EXPONENT]
    static double toDouble(long significand, int exponent, boolean negative) {
        var index = exponent - MIN_EXPONENT;
        var leadingZeros = Long.numberOfLeadingZeros(significand);
        var normalized = significand << leadingZeros;
        // The 192-bit product, from the most significant word: high, middle, low.
        var lowProduct = normalized * LOW[index];
        var lowCarry = Math.unsignedMultiplyHigh(normalized, LOW[index]);
        var middle = normalized * HIGH[index];
        var high = Math.unsignedMultiplyHigh(normalized, HIGH[index]);
        middle += lowCarry;
        if (Long.compareUnsigned(middle, lowCarry) < 0) {
            high++;
        }
        if (middle == -1L) {
            // The truncation of the power may carry into the high word.
            return Double.NaN;
        }
        var upper = (int) (high >>> (Long.SIZE - 1));
        var dropped = DROPPED_BITS + upper;
        var mantissa = high >>> dropped;
        var roundBit = mantissa & 1;
        if (roundBit == 1 && (high & ((1L << dropped) - 1)) == 0 && middle == 0 && lowProduct == 0) {
            // Exactly halfway in the product, but the exact value may lie a little above.
            return Double.NaN;
        }
        mantissa = (mantissa >>> 1) + roundBit;
        var binaryExponent = TABLE_BITS + dropped + 1 + exponent - SHIFT[index] - leadingZeros;
        if (mantissa == OVERFLOWED_MANTISSA) {
            mantissa >>>= 1;
            binaryExponent++;
        }
        var biased = binaryExponent + MANTISSA_BITS + EXPONENT_BIAS;
        if (biased <= 0 || biased >= MAX_BIASED_EXPONENT) {
            return Double.NaN;
        }
        var value = Double.longBitsToDouble(((long) biased << MANTISSA_BITS) | (mantissa & MANTISSA_MASK));
        return negative ? -value : value;
    }
}
