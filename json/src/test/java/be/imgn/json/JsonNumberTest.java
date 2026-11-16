package be.imgn.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonNumberTest {

    @Test
    void keepsTextAsWritten() {
        for (var text : new String[] {"0", "-0", "12", "-12.50", "1e2", "1E+2", "123456789012345678901234567890"}) {
            assertThat(number(text)).hasToString(text);
            assertThat(JsonNumber.parse(text)).hasToString(text);
        }
    }

    @Test
    void convertsExactly() {
        assertThat(number("42").asInt()).isEqualTo(42);
        assertThat(number("1.0").asInt()).isEqualTo(1);
        assertThat(number("1e2").asLong()).isEqualTo(100);
        assertThat(number("-128").asByte()).isEqualTo((byte) -128);
        assertThat(number("32767").asShort()).isEqualTo((short) 32767);
        assertThat(number("9223372036854775807").asLong()).isEqualTo(Long.MAX_VALUE);
        assertThat(number("123456789012345678901234567890").asBigInteger())
                .isEqualTo(new BigInteger("123456789012345678901234567890"));
        assertThat(number("0.1").asBigDecimal()).isEqualTo(new BigDecimal("0.1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "2147483648", "-2147483649", "1e10", "1e1000000000", "0.1e1000"})
    void rejectsInexactInt(String text) {
        assertThatExceptionOfType(ArithmeticException.class)
                .isThrownBy(() -> number(text).asInt())
                .withMessage(text + " is not an int");
    }

    @Test
    void refusesHugeBigInteger() {
        assertThatExceptionOfType(ArithmeticException.class)
                .isThrownBy(() -> number("1e1000000000").asBigInteger());
        assertThat(number("1e1000").asBigInteger()).isEqualTo(BigInteger.TEN.pow(1000));
    }

    @Test
    void convertsToNearestDouble() {
        assertThat(number("0.1").asDouble()).isEqualTo(0.1);
        assertThat(number("-12.5e-3").asDouble()).isEqualTo(-12.5e-3);
        assertThat(number("1e-400").asDouble()).isZero();
        assertThat(number("123456789012345678901234567890").asDouble()).isEqualTo(1.2345678901234568E29);
        assertThat(number("3.4028235e38").asFloat()).isEqualTo(Float.MAX_VALUE);
        assertThat(number("-0.0").asDouble()).isEqualTo(-0.0);
        assertThatExceptionOfType(ArithmeticException.class)
                .isThrownBy(() -> number("1e400").asDouble());
        assertThatExceptionOfType(ArithmeticException.class)
                .isThrownBy(() -> number("1e39").asFloat());
    }

    @Test
    void parsesDoublesAsTheJdk() {
        var random = new Random(42);
        for (var i = 0; i < 100_000; i++) {
            var value = switch (i % 3) {
                case 0 -> random.nextDouble() * Math.pow(10, random.nextInt(40) - 20);
                case 1 -> Double.longBitsToDouble(random.nextLong());
                default -> random.nextInt(1_000_000) / 1000.0;
            };
            if (Double.isFinite(value)) {
                var text = Double.toString(value);
                assertThat(number(text).asDouble()).as(text).isEqualTo(value);
                var expected = Float.parseFloat(text);
                if (Float.isFinite(expected)) {
                    assertThat(number(text).asFloat()).as(text).isEqualTo(expected);
                } else {
                    assertThatExceptionOfType(ArithmeticException.class)
                            .isThrownBy(() -> number(text).asFloat());
                }
            }
        }
    }

    @Test
    void comparesValues() {
        assertThat(number("1"))
                .isEqualTo(number("1.0"))
                .isEqualTo(number("1e0"))
                .isEqualTo(JsonNumber.of(1));
        assertThat(number("1").hashCode())
                .isEqualTo(number("1.00").hashCode())
                .isEqualTo(number("0.1e1").hashCode());
        assertThat(number("0")).isEqualTo(number("-0"));
        assertThat(number("1")).isNotEqualTo(number("1.5"));
    }

    @Test
    void createsNumbers() {
        assertThat(JsonNumber.of(0.5)).hasToString("0.5");
        assertThat(JsonNumber.of(new BigDecimal("1E+3")).asInt()).isEqualTo(1000);
        assertThat(JsonNumber.of(BigInteger.TWO.pow(70))).hasToString("1180591620717411303424");
        assertThatIllegalArgumentException().isThrownBy(() -> JsonNumber.of(Double.NaN));
        assertThatIllegalArgumentException().isThrownBy(() -> JsonNumber.of(Double.POSITIVE_INFINITY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "+1", ".5", "1.", "01", " 1", "1 ", "NaN", "1e", "0x10", "1é"})
    void rejectsInvalidText(String text) {
        assertThatIllegalArgumentException().isThrownBy(() -> JsonNumber.parse(text));
    }

    private static JsonNumber number(String text) {
        return (JsonNumber)
                ((JsonArray) new JsonParser().parse("[" + text + "]").root()).get(0);
    }
}
