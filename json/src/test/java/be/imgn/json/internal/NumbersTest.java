package be.imgn.json.internal;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NumbersTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                // Halfway between two doubles: the tie goes to the even mantissa.
                "9007199254740993",
                "9007199254740995",
                "2.5e-324",
                // Around the smallest and largest doubles.
                "4.9e-324",
                "2.2250738585072011e-308",
                "2.2250738585072014e-308",
                "1.7976931348623157e308",
                "1.7976931348623158e308",
                "1.7976931348623159e308",
                // More than 19 digits, and the most digits a double needs.
                "1.00000000000000011102230246251565404236316680908203125",
                "0.1000000000000000055511151231257827021181583404541015625",
                "123456789012345678901234567890e-10",
                "9999999999999999999",
                "18446744073709551615",
                "-0.0",
                "0e400",
                "1e-400",
                "-65.613616999999977",
                "43.420273000000009"
            })
    void parsesAsTheJdk(String text) {
        assertParsed(text);
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
            assertParsed(text + "e" + (random.nextInt(700) - 350));
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
            }
        }
    }

    private static void assertParsed(String text) {
        var bytes = text.getBytes(ISO_8859_1);
        assertThat(Numbers.parseDouble(bytes, 0, bytes.length)).as(text).isEqualTo(Double.parseDouble(text));
    }
}
