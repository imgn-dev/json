package be.imgn.json.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import be.imgn.json.JsonPosition;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TapeBuilderTest {

    private static final String TRAILING_COMMA =
            ": a trailing comma is not allowed in JSON (JsonFormat.JSON5 allows it)";

    @Test
    void buildsTapeOfScalars() {
        assertThat(tape("\"abc\"")).isEqualTo("STRING 0 3 abc");
        assertThat(tape(" -12.5e3 ")).isEqualTo("NUMBER 1 7 -12.5e3");
        assertThat(tape("42")).isEqualTo("NUMBER 0 2 INTEGRAL 42");
        assertThat(tape("true")).isEqualTo("TRUE 0 0");
        assertThat(tape("false")).isEqualTo("FALSE 0 0");
        assertThat(tape("null")).isEqualTo("NULL 0 0");
    }

    @Test
    void buildsTapeOfContainers() {
        assertThat(tape("{\"a\": [1, {}], \"b\": []}")).isEqualTo("""
                        OBJECT_START 10 2
                        NAME 1 # a
                        ARRAY_START 6 2
                        NUMBER 7 1 INTEGRAL 1
                        OBJECT_START 5 0
                        OBJECT_END 10 0
                        ARRAY_END 6 2
                        NAME 15 # b
                        ARRAY_START 9 0
                        ARRAY_END 20 0
                        OBJECT_END 0 2""");
    }

    @Test
    void numbersNamesConsistently() {
        var tape = build("[{\"a\": 1, \"b\": 2}, {\"b\": 3, \"\\u0061\": 4, \"c\": 5}]");
        var entries = tape.entries();
        var a = Tape.low(entries[2]);
        var b = Tape.low(entries[4]);

        assertThat(a).isNotEqualTo(b);
        assertThat(Tape.low(entries[8])).isEqualTo(b);
        assertThat(Tape.low(entries[10])).isEqualTo(a);
        assertThat(Tape.low(entries[12])).isNotIn(a, b);
        assertThat(Strings.decode(tape, 10)).isEqualTo("a");
    }

    @Test
    void sharesNamesAcrossThreads() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = IntStream.range(0, 8)
                    .<Callable<Boolean>>mapToObj(thread -> () -> {
                        for (var index = 0; index < 300; index++) {
                            var document = index;
                            var names = IntStream.range(0, 10)
                                    .mapToObj(k -> k % 2 == 0 ? "common" + k : "n" + thread + "_" + document + "_" + k)
                                    .toList();
                            var json = names.stream()
                                    .map(name -> "\"" + name + "\": 1")
                                    .collect(Collectors.joining(", ", "{", "}"));
                            var tape = TapeBuilder.build(json.getBytes(UTF_8), Limits.DEFAULTS);
                            for (var k = 0; k < names.size(); k++) {
                                if (!Strings.decode(tape, 1 + 2 * k).equals(names.get(k))) {
                                    return false;
                                }
                            }
                        }
                        return true;
                    })
                    .toList();
            for (var result : executor.invokeAll(tasks)) {
                assertThat(result.get()).isTrue();
            }
        }
    }

    @Test
    void flagsStrings() {
        assertThat(tape("[\"a\\nb\", \"é\", \"\\u00e9é\"]")).isEqualTo("""
                        ARRAY_START 4 3
                        STRING 1 4 ESCAPED a\\nb
                        STRING 9 2 NON_ASCII é
                        STRING 15 8 ESCAPED NON_ASCII \\u00e9é
                        ARRAY_END 0 3""");
    }

    @Test
    void scansEveryPositionOfWord() {
        // Each special byte at each position of the 8-byte words, and in the bytes after the last word.
        for (var length = 0; length < 20; length++) {
            assertThat(Tape.low(build("\"" + "a".repeat(length) + "\"").entries()[0]))
                    .isEqualTo(length);
            assertThat(Tape.low(build("\"" + "a".repeat(length) + "\\\"\"").entries()[0]))
                    .isEqualTo(length + 2);
            for (var at = 0; at < length; at++) {
                var before = "a".repeat(at);
                var after = "b".repeat(length - at - 1);
                assertThat(flags("\"" + before + "é" + after + "\"")).isEqualTo(Tape.NON_ASCII);
                assertThat(flags("\"" + before + "\\t" + after + "\"")).isEqualTo(Tape.ESCAPED);
                assertThat(flags("\"" + before + "~" + after + "\"")).isZero();
                var withControl = "\"" + before + "\u0001" + after + "\"";
                assertThatExceptionOfType(TapeException.class)
                        .isThrownBy(() -> build(withControl))
                        .satisfies(e -> assertThat(e.offset()).isEqualTo(1 + before.length()));
            }
        }
    }

    @Test
    void keepsLengthOfHugeString() {
        var text = "x".repeat(Tape.SATURATED + 10);
        var tape = TapeBuilder.build(("[\"" + text + "\", 1]").getBytes(UTF_8), Limits.DEFAULTS);

        assertThat(Tape.low(tape.entries()[1])).isEqualTo(Tape.SATURATED);
        assertThat(tape.length(1)).isEqualTo(text.length());
        assertThat(Strings.decode(tape, 1)).isEqualTo(text);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            ''                   | 1 | 1 | ''        | expected a value, found the end of the document
            ' '                  | 1 | 2 | ''        | expected a value, found the end of the document
            '[1 2]'              | 1 | 4 | /0        | expected ',' or ']' after an element, found '2'
            '{"a" 1}'            | 1 | 6 | /a        | expected ':' after a member name, found '1'
            '{"a": 1 "b": 2}'    | 1 | 9 | /a        | expected ',' or '}' after a member, found '"'
            '{1: 2}'             | 1 | 2 | ''        | expected a member name or '}', found '1'
            '[tru]'              | 1 | 2 | /0        | expected true, found 'tru'
            '[nul'               | 1 | 2 | /0        | expected null, found 'nul'
            '[abc]'              | 1 | 2 | /0        | expected a value, found 'abc'
            '1 2'                | 1 | 3 | ''        | expected the end of the document after the value, found '2'
            '-'                  | 1 | 2 | ''        | expected a digit after '-', found the end of the document
            '[-x]'               | 1 | 3 | /0        | expected a digit after '-', found 'x'
            '01'                 | 1 | 2 | ''        | a number cannot have a leading zero
            '1.'                 | 1 | 3 | ''        | expected a digit after '.', found the end of the document
            '1e+'                | 1 | 4 | ''        | expected a digit in the exponent, found the end of the document
            '"abc'               | 1 | 5 | ''        | expected '"' to end the string, found the end of the document
            '"a\\x"'             | 1 | 4 | ''        | expected an escape sequence after '\\', found 'x'
            '"\\u12g4"'          | 1 | 6 | ''        | expected a hexadecimal digit in a \\u escape sequence, found 'g'
            '"\\uD800"'          | 1 | 2 | ''        | unpaired surrogate \\uD800 in a string
            '"\\uDC00\\uD800"'   | 1 | 2 | ''        | unpaired surrogate \\uDC00 in a string
            '{"a": {"b~/": [0, x]}}' | 1 | 19 | /a/b~0~1/1 | expected a value, found 'x'
            """)
    void reportsError(String document, int line, int column, String path, String message) {
        var input = document.getBytes(UTF_8);

        assertThatExceptionOfType(TapeException.class)
                .isThrownBy(() -> TapeBuilder.build(input, Limits.DEFAULTS))
                .satisfies(e -> {
                    assertThat(e.getMessage()).isEqualTo(message);
                    assertThat(Positions.of(input, e.offset())).isEqualTo(new JsonPosition(line, column));
                    assertThat(e.path()).isEqualTo(path);
                });
    }

    @Test
    void reportsTrailingCommaAsJson5() {
        assertError("[1,]", 1, 4, "/0", "expected a value after ',', found ']'" + TRAILING_COMMA);
        assertError("{\"a\": 1,}", 1, 9, "/a", "expected a member name after ',', found '}'" + TRAILING_COMMA);
    }

    @Test
    void reportsCommentAsJsonc() {
        assertError(
                "[1, /* one */ 2]",
                1,
                5,
                "/1",
                "expected a value, found '/': comments are not allowed in JSON (JsonFormat.JSONC allows them)");
    }

    @Test
    void reportsErrorOnLaterLine() {
        assertError("{\n  \"items\": [\n    1,\n    :\n  ]\n}", 4, 5, "/items/1", "expected a value, found ':'");
    }

    @Test
    void rejectsControlCharacter() {
        assertError("\"a\tb\"", 1, 3, "", "unescaped control character U+0009 in a string");
    }

    @Test
    void rejectsByteOrderMark() {
        assertError("\uFEFF{}", 1, 1, "", "a byte order mark is not allowed");
    }

    @Test
    void rejectsInvalidUtf8() {
        assertBytesError(new byte[] {'"', (byte) 0xC0, (byte) 0xAF, '"'}, 2, "invalid UTF-8 sequence 0xC0");
        assertBytesError(
                new byte[] {'"', (byte) 0xE0, (byte) 0x80, (byte) 0x80, '"'}, 2, "invalid UTF-8 sequence 0xE0 0x80");
        assertBytesError(
                new byte[] {'"', (byte) 0xED, (byte) 0xA0, (byte) 0x80, '"'}, 2, "invalid UTF-8 sequence 0xED 0xA0");
        assertBytesError(
                new byte[] {'"', (byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80, '"'},
                2,
                "invalid UTF-8 sequence 0xF4 0x90");
        assertBytesError(
                new byte[] {'"', (byte) 0xE2, (byte) 0x82, 'x', '"'}, 2, "invalid UTF-8 sequence 0xE2 0x82 0x78");
        assertBytesError(new byte[] {'"', (byte) 0xE2, (byte) 0x82}, 2, "invalid UTF-8 sequence 0xE2 0x82");
        assertBytesError(new byte[] {(byte) 0xFF}, 1, "expected a value, found the byte 0xFF");
    }

    @Test
    void acceptsValidUtf8() {
        var text = "é€😀\u07FF\uFFFD\uD7FF\uE000\uDBFF\uDFFF";

        assertThat(Strings.decode(TapeBuilder.build(("\"" + text + "\"").getBytes(UTF_8), Limits.DEFAULTS), 0))
                .isEqualTo(text);
    }

    @Test
    void describesNonAsciiCharacter() {
        assertError("é", 1, 1, "", "expected a value, found 'é' (U+00E9)");
    }

    @Test
    void rejectsDuplicateName() {
        assertError(
                "{\"a\": 1, \"b\": 2, \"a\": 3}", 1, 18, "", "duplicate member name \"a\", first at line 1, column 2");
    }

    @Test
    void rejectsDuplicateNameWrittenWithEscape() {
        assertError("{\"é\": 1, \"\\u00e9\": 2}", 1, 10, "", "duplicate member name \"é\", first at line 1, column 2");
    }

    @Test
    void rejectsDuplicateNameInLargeObject() {
        for (var members = 9; members < 100; members++) {
            var object = IntStream.range(0, members)
                    .mapToObj(i -> "\"m" + i + "\": " + i)
                    .collect(Collectors.joining(", ", "{", ""));
            assertThat(TapeBuilder.build((object + "}").getBytes(UTF_8), Limits.DEFAULTS)
                            .size())
                    .isEqualTo(2 * members + 2);
            for (var duplicate : new String[] {"m0", "m" + (members - 1), "\\u006d" + (members / 2)}) {
                var input = (object + ", \"" + duplicate + "\": 0}").getBytes(UTF_8);
                assertThatExceptionOfType(TapeException.class)
                        .isThrownBy(() -> TapeBuilder.build(input, Limits.DEFAULTS))
                        .withMessageStartingWith("duplicate member name");
            }
        }
    }

    @Test
    void rejectsDuplicateNameInObjectAfterSimilarOnes() {
        for (var members = 1; members < 20; members++) {
            var names = IntStream.range(0, members)
                    .mapToObj(i -> "\"m" + i + "\": " + i)
                    .toList();
            var shape = "{" + String.join(", ", names) + "}";
            for (var at = 0; at < members; at++) {
                // The same names up to a point, then one of them again.
                var prefix = String.join(", ", names.subList(0, at + 1));
                var input = ("[" + shape + ", " + shape + ", {" + prefix + ", \"m" + at + "\": 0}]").getBytes(UTF_8);
                assertThatExceptionOfType(TapeException.class)
                        .isThrownBy(() -> TapeBuilder.build(input, Limits.DEFAULTS))
                        .withMessageStartingWith("duplicate member name \"m" + at + "\"");
            }
        }
    }

    @Test
    void rejectsDuplicateNameAfterNestedObjectWithSameNames() {
        var document = "[{\"a\": {\"a\": 1, \"c\": 2}, \"b\": 3}, {\"a\": {\"a\": 1, \"c\": 2}, \"c\": 3, \"c\": 4}]";

        assertThatExceptionOfType(TapeException.class)
                .isThrownBy(() -> TapeBuilder.build(document.getBytes(UTF_8), Limits.DEFAULTS))
                .withMessageStartingWith("duplicate member name \"c\"");
    }

    @Test
    void acceptsSameNameInDifferentObjects() {
        var members =
                IntStream.range(0, 20).mapToObj(i -> "\"m" + i + "\": " + i).collect(Collectors.joining(", "));
        var document = "[{" + members + "}, {" + members + ", \"inner\": {" + members + "}}, {\"m0\": {\"m0\": 0}}]";

        assertThat(TapeBuilder.build(document.getBytes(UTF_8), Limits.DEFAULTS).size())
                .isPositive();
    }

    @Test
    void enforcesDepth() {
        var limits = new Limits(3, Integer.MAX_VALUE, 100, 100);

        assertThat(TapeBuilder.build("[[{\"a\": 1}]]".getBytes(UTF_8), limits).size())
                .isEqualTo(8);
        assertThatExceptionOfType(TapeException.class)
                .isThrownBy(() -> TapeBuilder.build("[[{\"a\": []}]]".getBytes(UTF_8), limits))
                .withMessage("the document nests more than 3 objects and arrays");
    }

    @Test
    void enforcesLengths() {
        var limits = new Limits(10, 20, 3, 4);

        assertThat(TapeBuilder.build("[\"abc\", 1234]".getBytes(UTF_8), limits).size())
                .isEqualTo(4);
        assertThatExceptionOfType(TapeException.class)
                .isThrownBy(() -> TapeBuilder.build("[\"abcd\"]".getBytes(UTF_8), limits))
                .withMessage("the string is longer than the maximum of 3 bytes");
        assertThatExceptionOfType(TapeException.class)
                .isThrownBy(() -> TapeBuilder.build("{\"abcd\": 1}".getBytes(UTF_8), limits))
                .withMessage("the string is longer than the maximum of 3 bytes");
        assertThatExceptionOfType(TapeException.class)
                .isThrownBy(() -> TapeBuilder.build("[-1234]".getBytes(UTF_8), limits))
                .withMessage("the number is longer than the maximum of 4 bytes");
        assertThatExceptionOfType(TapeException.class)
                .isThrownBy(() -> TapeBuilder.build("[1, 2, 3, 4, 5, 6, 7, 8]".getBytes(UTF_8), limits))
                .withMessage("the document is longer than the maximum of 20 bytes");
    }

    @Test
    void parsesDeepDocumentWithoutRecursion() {
        var depth = 100_000;
        var document = "[".repeat(depth) + "]".repeat(depth);
        var limits = new Limits(depth, Integer.MAX_VALUE, 100, 100);

        assertThat(TapeBuilder.build(document.getBytes(UTF_8), limits).size()).isEqualTo(2 * depth);
    }

    /// Returns the tape of a document as text, the number of each name as `#`: it depends on the documents parsed
    /// before, whose names are shared.
    private static String tape(String document) {
        return build(document).toString().replaceAll("(?m)^NAME (\\d+) \\d+", "NAME $1 #");
    }

    private static int flags(String document) {
        return Tape.flags(build(document).entries()[0]);
    }

    private static Tape build(String document) {
        return TapeBuilder.build(document.getBytes(UTF_8), Limits.DEFAULTS);
    }

    private static void assertError(String document, int line, int column, String path, String message) {
        var input = document.getBytes(UTF_8);
        assertThatExceptionOfType(TapeException.class)
                .isThrownBy(() -> TapeBuilder.build(input, Limits.DEFAULTS))
                .satisfies(e -> {
                    assertThat(e.getMessage()).isEqualTo(message);
                    assertThat(Positions.of(input, e.offset())).isEqualTo(new JsonPosition(line, column));
                    assertThat(e.path()).isEqualTo(path);
                });
    }

    private static void assertBytesError(byte[] input, int column, String message) {
        assertThatExceptionOfType(TapeException.class)
                .isThrownBy(() -> TapeBuilder.build(input, Limits.DEFAULTS))
                .satisfies(e -> {
                    assertThat(e.getMessage()).isEqualTo(message);
                    assertThat(Positions.of(input, e.offset())).isEqualTo(new JsonPosition(1, column));
                });
    }
}
