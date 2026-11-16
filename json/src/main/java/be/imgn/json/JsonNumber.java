package be.imgn.json;

import static be.imgn.gaia.core.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.util.Objects.requireNonNull;

import be.imgn.json.internal.Limits;
import be.imgn.json.internal.Numbers;
import be.imgn.json.internal.Tape;
import be.imgn.json.internal.TapeBuilder;
import be.imgn.json.internal.TapeException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/// A JSON number, kept exactly as written: it is converted only when one of the `as` methods is called.
///
/// The exact conversions, [#asInt()], [#asLong()], [#asShort()], [#asByte()] and [#asBigInteger()], accept any
/// number whose value is an integer in their range, such as `1.0` or `1e2`, and throw an [ArithmeticException]
/// otherwise. [#asDouble()] and [#asFloat()] return the nearest value. [#asBigDecimal()] is always exact.
///
/// Two numbers are equal when their values are: `1`, `1.0` and `1e0` are equal. [#toString()] returns the number as
/// written.
///
/// @since 0.1.0
public final class JsonNumber implements JsonValue {

    private final long value;
    // The text of the number, or null when it is the integer in value, written without a fraction or an exponent.
    private final byte @Nullable [] text;

    private JsonNumber(long value, byte @Nullable [] text) {
        this.value = value;
        this.text = text;
    }

    /// Returns a number of an integer value.
    ///
    /// @param value the value
    /// @return the number
    public static JsonNumber of(long value) {
        return new JsonNumber(value, null);
    }

    /// Returns a number of a `double` value, written in its shortest form that reads back to the same value.
    ///
    /// @param value the value
    /// @return the number
    /// @throws IllegalArgumentException if `value` is not finite: JSON has no `NaN` nor infinities
    public static JsonNumber of(double value) {
        checkArgument(Double.isFinite(value), "%s is not a JSON number", value);
        return new JsonNumber(0, Double.toString(value).getBytes(ISO_8859_1));
    }

    /// Returns a number of an exact integer value.
    ///
    /// @param value the value
    /// @return the number
    public static JsonNumber of(BigInteger value) {
        requireNonNull(value, "value");
        return value.bitLength() < Long.SIZE ? of(value.longValue()) : parsed(value.toString());
    }

    /// Returns a number of an exact decimal value.
    ///
    /// @param value the value
    /// @return the number
    public static JsonNumber of(BigDecimal value) {
        requireNonNull(value, "value");
        return parsed(value.toString());
    }

    /// Returns the number written as a text.
    ///
    /// @param text the number, as RFC 8259 writes it: `-12.5e3`, but not `+1`, `.5` or `1.`
    /// @return the number
    /// @throws IllegalArgumentException if `text` is not a JSON number
    public static JsonNumber parse(String text) {
        requireNonNull(text, "text");
        var bytes = text.getBytes(ISO_8859_1);
        try {
            var tape = TapeBuilder.build(bytes, Limits.DEFAULTS);
            var entry = tape.entries()[0];
            checkArgument(
                    Tape.tag(entry) == Tape.NUMBER && tape.length(0) == bytes.length && bytes.length == text.length(),
                    "\"%s\" is not a JSON number",
                    text);
            return written(bytes, 0, bytes.length, Tape.flags(entry));
        } catch (TapeException e) {
            throw new IllegalArgumentException("\"" + text + "\" is not a JSON number", e);
        }
    }

    private static JsonNumber parsed(String text) {
        var bytes = text.getBytes(ISO_8859_1);
        var integral = text.indexOf('.') < 0 && text.indexOf('E') < 0 && text.indexOf('e') < 0;
        return written(bytes, 0, bytes.length, integral ? Tape.INTEGRAL : 0);
    }

    /// Returns the number written in a document, which must follow the grammar.
    static JsonNumber written(byte[] input, int from, int length, int flags) {
        // -0 keeps its text, to be written back as it was.
        if (Numbers.isSmallInteger(input, from, length, flags) && !(input[from] == '-' && input[from + 1] == '0')) {
            return new JsonNumber(Numbers.parseLong(input, from, length), null);
        }
        return new JsonNumber(0, Arrays.copyOfRange(input, from, from + length));
    }

    /// Returns the exact value of this number as an `int`.
    ///
    /// @return the value
    /// @throws ArithmeticException if the value is not an integer in the range of an `int`
    public int asInt() {
        return (int) exact(Integer.MIN_VALUE, Integer.MAX_VALUE, "an int");
    }

    /// Returns the exact value of this number as a `long`.
    ///
    /// @return the value
    /// @throws ArithmeticException if the value is not an integer in the range of a `long`
    public long asLong() {
        return exact(Long.MIN_VALUE, Long.MAX_VALUE, "a long");
    }

    /// Returns the exact value of this number as a `short`.
    ///
    /// @return the value
    /// @throws ArithmeticException if the value is not an integer in the range of a `short`
    public short asShort() {
        return (short) exact(Short.MIN_VALUE, Short.MAX_VALUE, "a short");
    }

    /// Returns the exact value of this number as a `byte`.
    ///
    /// @return the value
    /// @throws ArithmeticException if the value is not an integer in the range of a `byte`
    public byte asByte() {
        return (byte) exact(Byte.MIN_VALUE, Byte.MAX_VALUE, "a byte");
    }

    /// Returns the exact value of this number as a `BigInteger`.
    ///
    /// @return the value
    /// @throws ArithmeticException if the value is not an integer, or has more than 100 000 digits, which would
    ///     take too long and too much memory to compute (`1e1000000000` is a short text)
    public BigInteger asBigInteger() {
        if (text == null) {
            return BigInteger.valueOf(value);
        }
        try {
            return Numbers.toBigInteger(text, 0, text.length, integralFlag());
        } catch (ArithmeticException e) {
            throw new ArithmeticException(this + " is not a BigInteger: " + e.getMessage());
        }
    }

    /// Returns the exact value of this number as a `BigDecimal`.
    ///
    /// @return the value
    public BigDecimal asBigDecimal() {
        return text == null ? BigDecimal.valueOf(value) : Numbers.toBigDecimal(text, 0, text.length);
    }

    /// Returns the `double` nearest this number.
    ///
    /// @return the value
    /// @throws ArithmeticException if the number is too large for a `double`
    public double asDouble() {
        if (text == null) {
            return value;
        }
        var result = Numbers.parseDouble(text, 0, text.length);
        if (Double.isInfinite(result)) {
            throw new ArithmeticException(this + " is too large for a double");
        }
        return result;
    }

    /// Returns the `float` nearest this number.
    ///
    /// @return the value
    /// @throws ArithmeticException if the number is too large for a `float`
    public float asFloat() {
        if (text == null) {
            return value;
        }
        var result = Numbers.parseFloat(text, 0, text.length);
        if (Float.isInfinite(result)) {
            throw new ArithmeticException(this + " is too large for a float");
        }
        return result;
    }

    @Override
    public JsonType type() {
        return JsonType.NUMBER;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!(other instanceof JsonNumber number)) {
            return false;
        }
        if (text == null && number.text == null) {
            return value == number.value;
        }
        return asBigDecimal().compareTo(number.asBigDecimal()) == 0;
    }

    @Override
    public int hashCode() {
        return asBigDecimal().stripTrailingZeros().hashCode();
    }

    /// Returns the number as written.
    @Override
    public String toString() {
        return text == null ? Long.toString(value) : new String(text, ISO_8859_1);
    }

    /// Returns the text of this number, as written; `null` when it is [#value].
    byte @Nullable [] text() {
        return text;
    }

    long value() {
        return value;
    }

    private long exact(long min, long max, String type) {
        if (text == null) {
            if (value < min || value > max) {
                throw new ArithmeticException(this + " is not " + type);
            }
            return value;
        }
        try {
            return Numbers.toLong(text, 0, text.length, integralFlag(), min, max);
        } catch (ArithmeticException e) {
            throw new ArithmeticException(this + " is not " + type);
        }
    }

    private int integralFlag() {
        for (var b : requireNonNull(text)) {
            if (b == '.' || b == 'e' || b == 'E') {
                return 0;
            }
        }
        return Tape.INTEGRAL;
    }
}
