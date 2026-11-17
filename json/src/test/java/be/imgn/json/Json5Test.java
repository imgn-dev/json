package be.imgn.json;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class Json5Test {

    private static final JsonParser PARSER =
            JsonParser.builder().format(JsonFormat.JSON5).build();
    private static final JsonWriter WRITER = new JsonWriter();
    private static final String LINE_SEPARATOR = Character.toString(0x2028);
    private static final String PARAGRAPH_SEPARATOR = Character.toString(0x2029);

    @Test
    void readsTheExampleOfTheSpecification() {
        var document = """
                {
                  // comments
                  unquoted: 'and you can quote me on that',
                  singleQuotes: 'I can use "double quotes" here',
                  lineBreaks: "Look, Mom! \\
                No \\\\n's!",
                  hexadecimal: 0xdecaf,
                  leadingDecimalPoint: .8675309, andTrailing: 8675309.,
                  positiveSign: +1,
                  trailingComma: 'in objects', andIn: ['arrays',],
                  "backwardsCompatible": "with JSON",
                }
                """;

        assertThat(json(document))
                .isEqualTo("{\"unquoted\":\"and you can quote me on that\","
                        + "\"singleQuotes\":\"I can use \\\"double quotes\\\" here\","
                        + "\"lineBreaks\":\"Look, Mom! No \\\\n's!\","
                        + "\"hexadecimal\":912559,"
                        + "\"leadingDecimalPoint\":0.8675309,\"andTrailing\":8675309.0,"
                        + "\"positiveSign\":1,"
                        + "\"trailingComma\":\"in objects\",\"andIn\":[\"arrays\"],"
                        + "\"backwardsCompatible\":\"with JSON\"}");
    }

    @Test
    void readsEscapeSequences() {
        var root = PARSER.parse("'\\x41\\u0042\\v\\0\\a\\/\\'\\\"\\\\'").root();

        assertThat(root).isEqualTo(new JsonString("AB\u000B\u0000a/'\"\\"));
    }

    @Test
    void readsLineContinuations() {
        var root = PARSER.parse("['a\\\nb', 'c\\\r\nd', 'e\\\rf', 'g\\" + LINE_SEPARATOR + "h', 'i\\"
                        + PARAGRAPH_SEPARATOR + "j']")
                .root();

        assertThat(WRITER.format(root)).isEqualTo("[\"ab\",\"cd\",\"ef\",\"gh\",\"ij\"]");
    }

    @Test
    void escapesControlCharactersAsJson() {
        var root = PARSER.parse("'a\tb\u0001c" + LINE_SEPARATOR + "d'").root();

        assertThat(root).isEqualTo(new JsonString("a\tb\u0001c" + LINE_SEPARATOR + "d"));
        assertThat(WRITER.format(root)).isEqualTo("\"a\\tb\\u0001c" + LINE_SEPARATOR + "d\"");
    }

    @Test
    void readsIdentifiersAsNames() {
        var root = PARSER.parse("{$a_1: 1, \u00FCn\u00EFc\u00F6d\u00E9: 2, \\u0061b: 3, true: 4, a\u0301ccent: 5, "
                        + "\u3042\u0661: 6}")
                .root();

        assertThat(((JsonObject) root).members().keySet())
                .containsExactly("$a_1", "\u00FCn\u00EFc\u00F6d\u00E9", "ab", "true", "a\u0301ccent", "\u3042\u0661");
    }

    @Test
    void readsNumbers() {
        var root = (JsonArray) PARSER.parse("[+1, -.5, 5., 0x1F, -0X1f, 1e3, .5e-1, 0, -0, 5.e2]")
                .root();

        assertThat(WRITER.format(root)).isEqualTo("[1,-0.5,5.0,31,-31,1e3,0.5e-1,0,-0,5.0e2]");
        assertThat(((JsonNumber) root.get(3)).asInt()).isEqualTo(31);
        assertThat(((JsonNumber) root.get(1)).asDouble()).isEqualTo(-0.5);
    }

    @Test
    void readsNonFiniteNumbers() {
        var root = (JsonArray) PARSER.parse("[Infinity, -Infinity, +Infinity, NaN, -NaN, +NaN]")
                .root();

        assertThat(root.elements().stream().map(value -> ((JsonNumber) value).asDouble()))
                .containsExactly(
                        Double.POSITIVE_INFINITY,
                        Double.NEGATIVE_INFINITY,
                        Double.POSITIVE_INFINITY,
                        Double.NaN,
                        Double.NaN,
                        Double.NaN);
        assertThat(((JsonNumber) root.get(1)).asFloat()).isEqualTo(Float.NEGATIVE_INFINITY);
        assertThat(root.get(3)).isEqualTo(root.get(4)).hasSameHashCodeAs(root.get(4));
        assertThat(root.get(0)).isEqualTo(root.get(2)).isNotEqualTo(root.get(1));
        assertThat(root.get(0)).isNotEqualTo(JsonNumber.of(1));
    }

    @Test
    void rejectsExactConversionsOfNonFiniteNumbers() {
        var infinity = (JsonNumber) PARSER.parse("Infinity").root();

        assertThatExceptionOfType(ArithmeticException.class)
                .isThrownBy(infinity::asBigDecimal)
                .withMessage("Infinity is not a BigDecimal: not finite");
        assertThatExceptionOfType(ArithmeticException.class).isThrownBy(infinity::asLong);
        assertThatExceptionOfType(ArithmeticException.class).isThrownBy(infinity::asBigInteger);
    }

    @Test
    void refusesToWriteNonFiniteNumbersAsJson() {
        var document = PARSER.parse("[1, NaN]");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> WRITER.format(document.root()))
                .withMessage("NaN is not a JSON number");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> WRITER.format(JsonArray.of(((JsonArray) document.root()).get(1))))
                .withMessage("NaN is not a JSON number");
    }

    @Test
    void writesNonFiniteNumbersAsWrittenInToString() {
        var parsed = (JsonObject) PARSER.parse("{a: [NaN, -Infinity], b: {c: +Infinity, d: -NaN, e: +NaN}}")
                .root();
        var built = JsonObject.of(Map.of("a", JsonArray.of(parsed.get("a").orElseThrow(), JsonNumber.of(1))));

        assertThat(parsed).hasToString("{\"a\":[NaN,-Infinity],\"b\":{\"c\":Infinity,\"d\":NaN,\"e\":NaN}}");
        assertThat(built).hasToString("{\"a\":[[NaN,-Infinity],1]}");
        assertThat(JsonArray.of(((JsonArray) parsed.get("a").orElseThrow()).get(0)))
                .hasToString("[NaN]");
    }

    @Test
    void readsNonFiniteNumbersWithTheStreamingReader() {
        var reader = PARSER.reader("[-Infinity, NaN, Infinity]".getBytes(UTF_8));

        reader.beginArray();
        assertThat(reader.hasNextElement()).isTrue();
        assertThat(reader.readDouble()).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(reader.hasNextElement()).isTrue();
        assertThat(reader.readFloat()).isNaN();
        assertThat(reader.hasNextElement()).isTrue();
        assertThatExceptionOfType(JsonMappingException.class)
                .isThrownBy(reader::readBigDecimal)
                .withMessage("line 1, column 18, at /2: expected a BigDecimal, found Infinity");
    }

    @Test
    void acceptsByteOrderMarkAndUnicodeWhiteSpace() {
        var spaces = Character.toString(0xFEFF) + "{" + Character.toString(0xA0) + "a" + Character.toString(0x2003)
                + ":" + Character.toString(0x3000) + "[1," + LINE_SEPARATOR + " 2" + PARAGRAPH_SEPARATOR
                + "]\u000B\u000C}";

        assertThat(json(spaces)).isEqualTo("{\"a\":[1,2]}");
    }

    @Test
    void keepsValuesApart() {
        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> PARSER.parse("[1 2]"))
                .withMessage("line 1, column 4, at /0: expected ',' or ']' after an element, found '2'");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            {a: '\\1'}                  | 1 | 7  | /a   | expected an escape sequence after '\\', found '1'
            {a: '\\0' , b: '\\01'}      | 1 | 18 | /b   | a digit cannot follow \\0 in a string
            ['abc                      | 1 | 6  | /0   | expected "'" to end the string, found the end of the document
            [1, 2,,]                   | 1 | 7  | /2   | expected a value, found ','
            [,]                        | 1 | 2  | /0   | expected a value, found ','
            {,}                        | 1 | 2  | ''   | expected a member name or '}', found ','
            [foo]                      | 1 | 2  | /0   | expected a value, found 'foo'
            [01]                       | 1 | 3  | /0   | a number cannot have a leading zero
            [0x]                       | 1 | 4  | /0   | expected a hexadecimal digit after '0x', found ']'
            [1a]                       | 1 | 3  | /0   | expected the end of the number, found 'a'
            [+]                        | 1 | 3  | /0   | expected a digit after '+', found ']'
            [.]                        | 1 | 3  | /0   | expected a digit after '.', found ']'
            [1e]                       | 1 | 4  | /0   | expected a digit in the exponent, found ']'
            ['\\x4']               | 1 | 6  | /0   | expected a hexadecimal digit in a \\x escape sequence, found "'"
            {a\\u0020b: 1}             | 1 | 3  | ''   | \\u0020 is not a character of an identifier
            [1 2, '\\1']               | 1 | 4  | /0   | expected ',' or ']' after an element, found '2'
            {a: 1, 'a': 2}             | 1 | 8  | ''   | duplicate member name "a", first at line 1, column 2
            [1] /* open                | 1 | 12 | ''   | expected '*/' to end the comment, found the end of the document
            """)
    void reportsError(String document, int line, int column, String path, String message) {
        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> PARSER.parse(document))
                .satisfies(e -> {
                    assertThat(e.getMessage())
                            .isEqualTo("line " + line + ", column " + column + (path.isEmpty() ? "" : ", at " + path)
                                    + ": " + message);
                    assertThat(e.position()).isEqualTo(new JsonPosition(line, column));
                    assertThat(e.path()).isEqualTo(path);
                });
    }

    @Test
    void reportsErrorAtItsPositionInTheSource() {
        var document = "/* c */\n{a: [1, \n // x\n x]}";

        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> PARSER.parse(document))
                .withMessage("line 4, column 2, at /a/1: expected a value, found 'x'");
    }

    @Test
    void reportsUnescapedLineTerminator() {
        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> PARSER.parse("['a\nb']"))
                .withMessage("line 1, column 4, at /0: unescaped line terminator U+000A in a string");
    }

    @Test
    void reportsInvalidUtf8InComment() {
        var document = new byte[] {'1', ' ', '/', '/', (byte) 0xFF};

        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> PARSER.parse(document))
                .withMessage("line 1, column 5: invalid UTF-8 sequence 0xFF");
    }

    @Test
    void positionsValuesInTheSource() {
        var document = PARSER.parse("// c\n{a: /* x */ 1, b: 'c'}");

        assertThat(document.position((JsonObject) document.root(), "b")).isEqualTo(new JsonPosition(2, 19));
    }

    @Test
    void appliesLimitsToTheNumbersAsWritten() {
        var limited =
                JsonParser.builder().format(JsonFormat.JSON5).maxNumberLength(5).build();

        assertThat(json(limited, "0xFFF")).isEqualTo("4095");
        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> limited.parse("0xFFFFF"))
                .withMessage("line 1, column 1: the number is longer than the maximum of 5 bytes");
    }

    @Test
    void refusesJsonLines() {
        assertThatIllegalStateException()
                .isThrownBy(() -> PARSER.parseLines("1"))
                .withMessage("JSON Lines are JSON only, and this parser reads JSON5");
    }

    private static String json(String document) {
        return json(PARSER, document);
    }

    private static String json(JsonParser parser, String document) {
        return WRITER.format(parser.parse(document).root());
    }
}
