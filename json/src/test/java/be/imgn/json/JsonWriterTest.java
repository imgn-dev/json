package be.imgn.json;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import be.imgn.json.stream.JsonNames;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class JsonWriterTest {

    private static final JsonObject VALUE = JsonObject.builder()
            .put("id", JsonNumber.of(42))
            .put("tags", JsonArray.of(new JsonString("a"), new JsonString("b")))
            .put("empty", JsonObject.of())
            .put("none", JsonArray.of())
            .put("nested", JsonObject.builder().put("x", JsonNull.INSTANCE).build())
            .build();

    @Test
    void writesCompact() {
        assertThat(new JsonWriter().format(VALUE))
                .isEqualTo("{\"id\":42,\"tags\":[\"a\",\"b\"],\"empty\":{},\"none\":[],\"nested\":{\"x\":null}}");
    }

    @Test
    void writesPretty() {
        assertThat(JsonWriter.pretty().format(VALUE)).isEqualTo("""
                {
                  "id": 42,
                  "tags": [
                    "a",
                    "b"
                  ],
                  "empty": {},
                  "none": [],
                  "nested": {
                    "x": null
                  }
                }""");
        assertThat(JsonWriter.builder().indent(4).build().format(JsonArray.of(JsonNumber.of(1))))
                .isEqualTo("[\n    1\n]");
    }

    @Test
    void writesParsedValuesAsBuiltOnes() {
        var text = "{\"a\" : [1.50, \"x\\ty\", \"\\u00e9\", true, null, {\"\\n\": -0}], \"b\": {}}";
        var parsed = new JsonParser().parse(text).root();
        var copy = JsonObject.copyOf((JsonObject) parsed);

        for (var writer : new JsonWriter[] {new JsonWriter(), JsonWriter.pretty()}) {
            assertThat(writer.format(parsed)).isEqualTo(writer.format(copy));
        }
        assertThat(new JsonWriter().format(parsed))
                .isEqualTo("{\"a\":[1.50,\"x\\ty\",\"é\",true,null,{\"\\n\":-0}],\"b\":{}}");
    }

    @Test
    void escapesStrings() {
        var text = "\"\\/\b\f\n\r\t\u0000\u001f\u007fé€😀";

        assertThat(new JsonWriter().format(new JsonString(text)))
                .isEqualTo("\"\\\"\\\\/\\b\\f\\n\\r\\t\\u0000\\u001f\u007fé€😀\"");
        assertThat(new JsonParser()
                        .parse(new JsonWriter().toBytes(new JsonString(text)))
                        .root())
                .isEqualTo(new JsonString(text));
        var longText = "é😀\"".repeat(2000);
        assertThat(new JsonParser()
                        .parse(new JsonWriter().format(new JsonString(longText)))
                        .root())
                .isEqualTo(new JsonString(longText));
    }

    @Test
    void rejectsUnpairedSurrogate() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JsonWriter().format(new JsonString("a\uD800b")))
                .withMessage("unpaired surrogate U+D800 at index 1 of the string");
        assertThatIllegalArgumentException().isThrownBy(() -> new JsonWriter().format(new JsonString("\uDC00")));
    }

    @Test
    void writesToStream() throws IOException {
        var out = new ByteArrayOutputStream();

        new JsonWriter().write(JsonArray.of(new JsonString("é")), out);

        assertThat(out.toString(UTF_8)).isEqualTo("[\"é\"]");
    }

    @Test
    void streamsDocument() {
        var names = JsonNames.of("id", "big");
        var writer = JsonWriter.pretty().streamWriter();

        writer.beginObject()
                .name(names, 0)
                .value(42)
                .name(names, 1)
                .value(new BigInteger("123456789012345678901234567890"))
                .name("list")
                .beginArray()
                .value(1.5)
                .value(2.5f)
                .value(new BigDecimal("1E+3"))
                .value(true)
                .nullValue()
                .value(JsonObject.of())
                .endArray()
                .endObject();

        assertThat(new String(writer.toByteArray(), UTF_8)).isEqualTo("""
                {
                  "id": 42,
                  "big": 123456789012345678901234567890,
                  "list": [
                    1.5,
                    2.5,
                    1E+3,
                    true,
                    null,
                    {}
                  ]
                }""");
    }

    @Test
    void checksGrammarOfStream() {
        var writer = new JsonWriter().streamWriter();

        assertThatIllegalStateException().isThrownBy(writer::endObject);
        assertThatIllegalStateException().isThrownBy(() -> writer.name("a"));
        writer.beginObject();
        assertThatIllegalStateException().isThrownBy(() -> writer.value(1));
        assertThatIllegalStateException().isThrownBy(writer::endArray);
        writer.name("a");
        assertThatIllegalStateException().isThrownBy(() -> writer.name("b"));
        assertThatIllegalStateException().isThrownBy(writer::endObject);
        assertThatIllegalStateException().isThrownBy(writer::toByteArray);
        writer.value(1).endObject();
        assertThatIllegalStateException().isThrownBy(() -> writer.value(2));
        assertThat(new String(writer.toByteArray(), UTF_8)).isEqualTo("{\"a\":1}");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JsonWriter().streamWriter().value(Double.NaN));
    }
}
