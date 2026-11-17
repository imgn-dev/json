package be.imgn.json;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import be.imgn.gaia.core.io.ByteSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class JsonLinesTest {

    private static final JsonParser PARSER = new JsonParser();
    private static final JsonWriter WRITER = new JsonWriter();

    @Test
    void parsesOneDocumentPerLine() {
        assertThat(roots("{\"a\": 1}\n[true, null]\r\n\"x\"")).containsExactly("{\"a\":1}", "[true,null]", "\"x\"");
    }

    @Test
    void ignoresTheLastNewline() {
        assertThat(roots("1\n2\n")).containsExactly("1", "2");
    }

    @Test
    void parsesNothingFromNothing() {
        assertThat(roots("")).isEmpty();
    }

    @Test
    void rejectsBlankLine() {
        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> roots("1\n\n2"))
                .withMessage("line 2, column 1: expected a value, found the end of the line");
        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> roots("1\n  \r\n2"))
                .withMessage("line 2, column 4: expected a value, found the end of the line");
    }

    @Test
    void rejectsValueOverSeveralLines() {
        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> roots("[1,\n2]"))
                .withMessage("line 1, column 4, at /1: expected a value, found the end of the line");
    }

    @Test
    void reportsErrorsAtTheirLine() {
        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> roots("1\n2\n[x]"))
                .withMessage("line 3, column 2, at /0: expected a value, found 'x'");
    }

    @Test
    void positionsValuesInTheWholeInput() {
        var documents = PARSER.parseLines("1\n {\"a\": 2}").toList();

        assertThat(documents.get(1).rootPosition()).isEqualTo(new JsonPosition(2, 2));
        assertThat(documents.get(1).position((JsonObject) documents.get(1).root(), "a"))
                .isEqualTo(new JsonPosition(2, 8));
    }

    @Test
    void parsesAsTheStreamIsConsumed() {
        try (var documents = PARSER.parseLines("1\n2\nx")) {
            var iterator = documents.iterator();

            assertThat(WRITER.format(iterator.next().root())).isEqualTo("1");
            assertThat(WRITER.format(iterator.next().root())).isEqualTo("2");
            assertThatExceptionOfType(JsonParseException.class).isThrownBy(iterator::next);
        }
    }

    @Test
    void appliesTheMaximumLengthToEachLine() {
        var limited = JsonParser.builder().maxDocumentLength(5).build();

        assertThat(limited.parseLines("12345\n1").count()).isEqualTo(2);
        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> limited.parseLines("1\n123456").toList())
                .withMessage("line 2, column 6: the line is longer than the maximum of 5 bytes");
    }

    @Test
    void readsSourceInChunks() throws IOException {
        var lines = IntStream.range(0, 10_000)
                .mapToObj(i -> "{\"id\": " + i + ", \"name\": \"item " + i + "\"}")
                .toList();
        var input = (String.join("\n", lines) + "\n").getBytes(UTF_8);

        try (var documents = PARSER.parseLines(trickling(input, 7))) {
            assertThat(documents.map(document -> WRITER.format(document.root())))
                    .containsExactlyElementsOf(lines.stream()
                            .map(line -> line.replace(" ", "").replace("item", "item "))
                            .toList());
        }
    }

    @Test
    void readsLinesLongerThanAChunk() throws IOException {
        var text = "x".repeat(200_000);
        var input = ("\"" + text + "\"\n[1]").getBytes(UTF_8);

        try (var documents = PARSER.parseLines(ByteSource.wrap(input))) {
            assertThat(documents.map(document -> document.root().toString()))
                    .containsExactly("\"" + text + "\"", "[1]");
        }
    }

    @Test
    void rejectsLongLineBeforeReadingItWhole() throws IOException {
        var limited = JsonParser.builder().maxDocumentLength(100_000).build();
        var input = ("\"" + "x".repeat(1_000_000) + "\"").getBytes(UTF_8);
        var stream = new CountingStream(input);

        try (var documents = limited.parseLines(() -> stream)) {
            assertThatExceptionOfType(JsonParseException.class)
                    .isThrownBy(documents::toList)
                    .withMessage("line 1, column 100001: the line is longer than the maximum of 100000 bytes");
        }
        assertThat(stream.read).isLessThan(input.length);
    }

    @Test
    void closesTheSourceWithTheStream() throws IOException {
        var stream = new CountingStream("1\n2".getBytes(UTF_8));

        try (var documents = PARSER.parseLines(() -> stream)) {
            assertThat(documents.count()).isEqualTo(2);
            assertThat(stream.closed).isFalse();
        }
        assertThat(stream.closed).isTrue();
    }

    @Test
    void reportsReadErrorsAsUnchecked() throws IOException {
        var failure = new IOException("disk");
        ByteSource source = () -> new InputStream() {
            @Override
            public int read() throws IOException {
                throw failure;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                throw failure;
            }
        };

        try (var documents = PARSER.parseLines(source)) {
            assertThatExceptionOfType(UncheckedIOException.class)
                    .isThrownBy(documents::toList)
                    .withCause(failure);
        }
    }

    @Test
    void writesOneCompactValuePerLine() {
        var values = List.of(
                JsonObject.builder().put("a", JsonArray.of(JsonNumber.of(1))).build(),
                JsonNumber.of(2),
                JsonNull.INSTANCE);

        assertThat(JsonWriter.pretty().formatLines(values)).isEqualTo("{\"a\":[1]}\n2\nnull\n");
        assertThat(WRITER.formatLines(List.of())).isEmpty();
    }

    @Test
    void writesLinesToAStream() throws IOException {
        var values = IntStream.range(0, 20_000)
                .mapToObj(i -> (JsonValue) JsonArray.of(JsonNumber.of(i), new JsonString("value " + i)))
                .toList();
        var out = new ByteArrayOutputStream();

        WRITER.writeLines(values, out);

        assertThat(out.toString(UTF_8)).isEqualTo(WRITER.formatLines(values));
        assertThat(PARSER.parseLines(out.toByteArray()).map(JsonDocument::root).toList())
                .containsExactlyElementsOf(values);
    }

    @Test
    void writesParsedLinesBackAsTheyWere() {
        var lines = "{\"a\":\"caf\u00e9\"}\n[1.50,-0,1e2]\n";

        assertThat(WRITER.formatLines(
                        PARSER.parseLines(lines).map(JsonDocument::root).toList()))
                .isEqualTo(lines);
    }

    @Test
    void rejectsNullValue() {
        var values = Arrays.asList((JsonValue) JsonNumber.of(1), null);

        assertThatNullPointerException().isThrownBy(() -> WRITER.formatLines(values));
    }

    private static List<String> roots(String lines) {
        var roots = new ArrayList<String>();
        PARSER.parseLines(lines).forEach(document -> roots.add(WRITER.format(document.root())));
        return roots;
    }

    /// Returns a source whose streams return at most some bytes at a time.
    private static ByteSource trickling(byte[] input, int most) {
        return () -> new ByteArrayInputStream(input) {
            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                return super.read(buffer, offset, Math.min(length, most));
            }
        };
    }

    /// A stream that counts the bytes read from it, and tells whether it was closed.
    private static final class CountingStream extends ByteArrayInputStream {

        private int read;
        private boolean closed;

        CountingStream(byte[] input) {
            super(input);
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            var count = super.read(buffer, offset, length);
            read += Math.max(0, count);
            return count;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
