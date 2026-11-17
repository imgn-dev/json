package be.imgn.json.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class StringsTest {

    private static final String E_ACUTE = Character.toString(0xE9);
    private static final String EURO = Character.toString(0x20AC);
    private static final String GRINNING = Character.toString(0x1F600);
    private static final String NUL = Character.toString(0);
    private static final String DELETE = Character.toString(0x7F);
    private static final String LAST_BMP = Character.toString(0xFFFF);

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '`',
            value = {
                "\\\"|\"",
                "\\\\|\\",
                "\\/|/",
                "\\b|BS",
                "\\f|FF",
                "\\n|LF",
                "\\r|CR",
                "\\t|TAB",
                "\\u0041|A",
                "\\u0000|NUL",
                "\\u007f|DELETE",
                "\\u00E9|E_ACUTE",
                "\\u00e9|E_ACUTE",
                "\\u20ac|EURO",
                "\\uFFFF|LAST_BMP",
                "\\uD83D\\uDE00|GRINNING",
                "\\ud83d\\ude00|GRINNING"
            })
    void decodesEveryEscapeSequence(String escaped, String expected) {
        var decoded = expand(expected);
        assertDecoded(escaped, decoded);
        assertDecoded("a" + escaped, "a" + decoded);
        assertDecoded(escaped + "z", decoded + "z");
        assertDecoded(escaped + escaped, decoded + decoded);
        assertDecoded("before " + escaped + " after", "before " + decoded + " after");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\n", "\\u00e9", "\\uD83D\\uDE00", "\\\""})
    void decodesEscapesAtEveryOffsetOfAChunk(String escape) {
        var expected = decodeOne(escape);
        for (var before = 0; before < 20; before++) {
            for (var after = 0; after < 20; after += 3) {
                assertDecoded(
                        "x".repeat(before) + escape + "y".repeat(after),
                        "x".repeat(before) + expected + "y".repeat(after));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\n", "\\u0041", "\\uD83D\\uDE00"})
    void decodesNonAsciiRunsAroundEscapes(String escape) {
        var expected = decodeOne(escape);
        var runs = E_ACUTE + EURO + GRINNING;
        assertDecoded(runs + escape + runs, runs + expected + runs);
        assertDecoded(E_ACUTE.repeat(9) + escape + EURO.repeat(9), E_ACUTE.repeat(9) + expected + EURO.repeat(9));
        assertDecoded(GRINNING.repeat(5) + escape, GRINNING.repeat(5) + expected);
        assertDecoded(escape + GRINNING.repeat(5), expected + GRINNING.repeat(5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "plain", "a long plain string over several chunks of eight bytes"})
    void decodesStringsWithoutEscapes(String text) {
        assertDecoded(text, text);
        assertDecoded(text + E_ACUTE + GRINNING, text + E_ACUTE + GRINNING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\n", "\\u00e9", "\\uD83D\\uDE00"})
    void decodesNamesWithEscapes(String escape) {
        var input = ("{\"k" + escape + "\":1}").getBytes(UTF_8);
        var tape = TapeBuilder.build(input, Limits.DEFAULTS);
        assertThat(Strings.decode(tape, 1)).isEqualTo("k" + decodeOne(escape));
    }

    private static String decodeOne(String escape) {
        return switch (escape) {
            case "\\n" -> "\n";
            case "\\\"" -> "\"";
            case "\\u0041" -> "A";
            case "\\u00e9" -> E_ACUTE;
            default -> GRINNING;
        };
    }

    private static String expand(String expected) {
        return switch (expected) {
            case "BS" -> "\b";
            case "FF" -> "\f";
            case "LF" -> "\n";
            case "CR" -> "\r";
            case "TAB" -> "\t";
            case "NUL" -> NUL;
            case "DELETE" -> DELETE;
            case "E_ACUTE" -> E_ACUTE;
            case "EURO" -> EURO;
            case "LAST_BMP" -> LAST_BMP;
            case "GRINNING" -> GRINNING;
            default -> expected;
        };
    }

    private static byte[] json(String content) {
        return ("\"" + content + "\"").getBytes(UTF_8);
    }

    private static void assertDecoded(String content, String expected) {
        var tape = TapeBuilder.build(json(content), Limits.DEFAULTS);
        assertThat(Strings.decode(tape, 0)).as(content).isEqualTo(expected);
    }
}
