package be.imgn.json.internal;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class NumbersTest {

    // Digits on both sides of a number, which a conversion must not read.
    private static final String AROUND = "98765432109876543210";

    @ParameterizedTest
    @ValueSource(
            strings = {
                // Halfway between two doubles: the tie goes to the even mantissa.
                "9007199254740993",
                "9007199254740995",
                "2.5e-324",
                "9007199254740993.0",
                "9.007199254740993e15",
                "4503599627370497.5",
                "4503599627370496.5",
                // Around the smallest and largest doubles.
                "4.9e-324",
                "4.9406564584124654e-324",
                "2.4703282292062327e-324",
                "2.4703282292062328e-324",
                "2.2250738585072011e-308",
                "2.2250738585072014e-308",
                "2.2250738585072009e-308",
                "1.7976931348623157e308",
                "1.7976931348623158e308",
                "1.7976931348623159e308",
                "1e308",
                "1e309",
                "1e-342",
                "1e-343",
                "9999999999999999999e-361",
                "1e-325",
                // At the limits of the fast paths: 15 and 16 digits, 10^22 and 10^23.
                "999999999999999",
                "9999999999999999",
                "123456789012345e22",
                "123456789012345e23",
                "123456789012345e-22",
                "123456789012345e-23",
                "1e22",
                "1e23",
                "1E+22",
                "1e-22",
                "1e-23",
                // 17 to 19 significant digits, and their chunks of 8.
                "12345678",
                "123456789",
                "1234567890123456",
                "12345678901234567",
                "1234567890123456789",
                "9999999999999999999",
                "12345678.12345678",
                "0.12345678901234567",
                "1234567.8901234567890",
                // More than 19 digits, and the most digits a double needs.
                "1.00000000000000011102230246251565404236316680908203125",
                "0.1000000000000000055511151231257827021181583404541015625",
                "123456789012345678901234567890e-10",
                "18446744073709551615",
                "18446744073709551616",
                "12345678901234567890",
                // Leading zeros, which are not significant.
                "0.000000000000000000001234567890123456789",
                "0.0000000000000000000012345678901234567890",
                "0.000000000000000000000000000000000000000000000000000000000000000001",
                "0.00000001",
                // Zeros.
                "0",
                "-0",
                "0.0",
                "-0.0",
                "0e400",
                "-0e400",
                "0e-400",
                "0.000000000000000000000000000000",
                "1e-400",
                "-1e-400",
                // Exponents beyond what the fast paths take.
                "1e1000",
                "1e-1000",
                "1e99999999999999999999",
                "1e-99999999999999999999",
                "0.0000000001e1010",
                "100000000000e-1010",
                // Small and ordinary numbers.
                "1",
                "-1",
                "1.5",
                "-65.613616999999977",
                "43.420273000000009",
                "1e2",
                "1E2",
                "1e+2",
                "1e-2",
                "3.14159"
            })
    void parsesAsTheJdk(String text) {
        assertParsed(text);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "1",
                "-1",
                "0",
                "-0.0",
                "1.5",
                "1234567",
                "12345678",
                "16777217",
                "1.17549435E-38",
                "1.4e-45",
                "7.0e-46",
                "3.4028235e38",
                "3.4028236e38",
                "1e10",
                "1e11",
                "1e-10",
                "1e-11",
                "1e39",
                "1e-46",
                "0.1",
                "123456789012345678901234567890"
            })
    void parsesFloatsAsTheJdk(String text) {
        assertParsedFloat(text);
    }

    @Test
    void parsesRandomNumbersAsTheJdk() {
        var random = new Random(7);
        for (var i = 0; i < 2_000_000; i++) {
            var digits = 1 + random.nextInt(19);
            var significand = new StringBuilder();
            significand.append((char) ('1' + random.nextInt(9)));
            for (var d = 1; d < digits; d++) {
                significand.append((char) ('0' + random.nextInt(10)));
            }
            var point = random.nextInt(digits + 1);
            var text = point == digits
                    ? significand.toString()
                    : significand.substring(0, Math.max(point, 1)) + "." + significand.substring(Math.max(point, 1));
            if (text.endsWith(".")) {
                text += "0";
            }
            text += "e" + (random.nextInt(700) - 350);
            assertParsed(text);
            if (i % 8 == 0) {
                assertParsedFloat(text);
            }
        }
    }

    @Test
    void parsesShortestFormOfRandomDoublesAsTheJdk() {
        var random = new Random(11);
        for (var i = 0; i < 1_000_000; i++) {
            var value = Double.longBitsToDouble(random.nextLong());
            if (Double.isFinite(value)) {
                assertParsed(Double.toString(value));
                assertParsed(new BigDecimal(value).round(new MathContext(17)).toString());
                assertParsed(new BigDecimal(value).toPlainString().replace("E", "e"));
            }
        }
    }

    @Test
    void parsesShortestFormOfRandomFloatsAsTheJdk() {
        var random = new Random(13);
        for (var i = 0; i < 200_000; i++) {
            var value = Float.intBitsToFloat(random.nextInt());
            if (Float.isFinite(value)) {
                assertParsedFloat(Float.toString(value));
                assertParsedFloat(
                        new BigDecimal(value).round(new MathContext(9)).toString());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "0",
                "-0",
                "7",
                "-7",
                "12345678",
                "-12345678",
                "123456789",
                "1234567890123456",
                "12345678901234567",
                "999999999999999999",
                "-999999999999999999",
                "100000000000000000",
                "-100000000000000000"
            })
    void parsesSmallIntegersAsTheJdk(String text) {
        var bytes = text.getBytes(ISO_8859_1);
        assertThat(Numbers.isSmallInteger(bytes, 0, bytes.length, Tape.INTEGRAL))
                .as(text)
                .isTrue();
        assertThat(Numbers.parseLong(bytes, 0, bytes.length)).as(text).isEqualTo(Long.parseLong(text));
        var embedded = (AROUND + text + AROUND).getBytes(ISO_8859_1);
        assertThat(Numbers.parseLong(embedded, AROUND.length(), bytes.length))
                .as(text)
                .isEqualTo(Long.parseLong(text));
    }

    @Test
    void parsesRandomSmallIntegersAsTheJdk() {
        var random = new Random(17);
        for (var i = 0; i < 1_000_000; i++) {
            var digits = 1 + random.nextInt(18);
            var value = random.nextLong() % (long) Math.pow(10, digits);
            var text = Long.toString(value);
            var bytes = text.getBytes(ISO_8859_1);
            assertThat(Numbers.parseLong(bytes, 0, bytes.length)).as(text).isEqualTo(value);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "9223372036854775807, 1, 9223372036854775807",
        "-9223372036854775808, 1, -9223372036854775808",
        "1000000000000000000, 1, 1000000000000000000",
        "-1000000000000000000, 1, -1000000000000000000",
        "9999999999999999990e-1, 0, 999999999999999999",
        "1e18, 0, 1000000000000000000",
        "9.223372036854775807e18, 0, 9223372036854775807",
        "1.0, 0, 1",
        "-1e2, 0, -100",
        "0.0, 0, 0",
        "-0, 1, 0"
    })
    void convertsIntegersToLongs(String text, int flags, long expected) {
        var bytes = text.getBytes(ISO_8859_1);
        assertThat(Numbers.toLong(bytes, 0, bytes.length, flags, Long.MIN_VALUE, Long.MAX_VALUE))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "9223372036854775808, 1",
        "-9223372036854775809, 1",
        "99999999999999999999, 1",
        "9.223372036854775808e18, 0",
        "1.5, 0",
        "1e-1, 0",
        "1e100000, 0"
    })
    void rejectsLongsOutOfRange(String text, int flags) {
        var bytes = text.getBytes(ISO_8859_1);
        assertThatExceptionOfType(ArithmeticException.class)
                .isThrownBy(() -> Numbers.toLong(bytes, 0, bytes.length, flags, Long.MIN_VALUE, Long.MAX_VALUE));
    }

    @ParameterizedTest
    @CsvSource({"2147483647, true", "2147483648, false", "-2147483648, true", "-2147483649, false"})
    void boundsLongs(String text, boolean inRange) {
        var bytes = text.getBytes(ISO_8859_1);
        if (inRange) {
            assertThat(Numbers.toLong(bytes, 0, bytes.length, Tape.INTEGRAL, Integer.MIN_VALUE, Integer.MAX_VALUE))
                    .isEqualTo(Long.parseLong(text));
        } else {
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> Numbers.toLong(
                            bytes, 0, bytes.length, Tape.INTEGRAL, Integer.MIN_VALUE, Integer.MAX_VALUE));
        }
    }

    private static void assertParsed(String text) {
        var bytes = text.getBytes(ISO_8859_1);
        var expected = Double.doubleToRawLongBits(Double.parseDouble(text));
        assertThat(Double.doubleToRawLongBits(Numbers.parseDouble(bytes, 0, bytes.length)))
                .as(text)
                .isEqualTo(expected);
        var embedded = (AROUND + text + AROUND).getBytes(ISO_8859_1);
        assertThat(Double.doubleToRawLongBits(Numbers.parseDouble(embedded, AROUND.length(), bytes.length)))
                .as(text)
                .isEqualTo(expected);
    }

    private static void assertParsedFloat(String text) {
        var bytes = text.getBytes(ISO_8859_1);
        var expected = Float.floatToRawIntBits(Float.parseFloat(text));
        assertThat(Float.floatToRawIntBits(Numbers.parseFloat(bytes, 0, bytes.length)))
                .as(text)
                .isEqualTo(expected);
        var embedded = (AROUND + text + AROUND).getBytes(ISO_8859_1);
        assertThat(Float.floatToRawIntBits(Numbers.parseFloat(embedded, AROUND.length(), bytes.length)))
                .as(text)
                .isEqualTo(expected);
    }
}
