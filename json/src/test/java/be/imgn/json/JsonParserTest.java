package be.imgn.json;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import be.imgn.gaia.core.io.ByteSource;
import be.imgn.gaia.core.io.CharSource;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class JsonParserTest {

    private final JsonParser parser = new JsonParser();

    @Test
    void parsesEveryInput() throws IOException {
        var expected = JsonObject.builder()
                .put("a", JsonArray.of(JsonNumber.of(1), JsonBoolean.TRUE))
                .build();
        var text = "{\"a\": [1, true]}";

        assertThat(parser.parse(text).root()).isEqualTo(expected);
        assertThat(parser.parse(text.getBytes(UTF_8)).root()).isEqualTo(expected);
        assertThat(parser.parse(ByteSource.wrap(text.getBytes(UTF_8))).root()).isEqualTo(expected);
        assertThat(parser.parse(CharSource.wrap(text)).root()).isEqualTo(expected);
    }

    @Test
    void reportsErrorWithPositionAndPath() {
        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> parser.parse("{\n  \"items\": [1,\n    :]\n}"))
                .withMessage("line 3, column 5, at /items/1: expected a value, found ':'")
                .satisfies(e -> {
                    assertThat(e.position()).isEqualTo(new JsonPosition(3, 5));
                    assertThat(e.path()).isEqualTo("/items/1");
                });
    }

    @Test
    void reportsErrorAtRootWithoutPath() {
        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> parser.parse("[1] 2"))
                .withMessage("line 1, column 5: expected the end of the document after the value, found '2'");
    }

    @Test
    void rejectsUnpairedSurrogateInString() {
        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> parser.parse("[\n\"😀\uD800\"]"))
                .withMessage("line 2, column 4: unpaired surrogate U+D800");
    }

    @Test
    void appliesLimits() {
        var limited = JsonParser.builder()
                .maxDepth(2)
                .maxDocumentLength(30)
                .maxStringLength(5)
                .maxNumberLength(3)
                .build();

        assertThat(limited.parse("[[\"abcde\", 123]]").root()).isInstanceOf(JsonArray.class);
        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> limited.parse("[[[]]]"))
                .withMessage("line 1, column 3, at /0/0: the document nests more than 2 objects and arrays");
        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> limited.parse("[\"abcdef\"]"))
                .withMessageEndingWith("the string is longer than the maximum of 5 bytes");
        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> limited.parse("[1234]"))
                .withMessageEndingWith("the number is longer than the maximum of 3 bytes");
        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> limited.parse(ByteSource.wrap(("[" + "1,".repeat(100) + "1]").getBytes(UTF_8))))
                .withMessageEndingWith("the document is longer than the maximum of 30 bytes");
    }

    @Test
    void rejectsTypeWithoutBinder() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parser.parseAs(String.class, "\"a\""))
                .withMessageStartingWith("no JsonBinder is registered for java.lang.String: annotate it @JsonRecord");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JsonWriter().formatAs(String.class, "a"))
                .withMessageStartingWith("no JsonBinder is registered for java.lang.String");
    }

    @Test
    void checksSettings() {
        var builder = JsonParser.builder();

        assertThatIllegalArgumentException().isThrownBy(() -> builder.maxDepth(0));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.maxDocumentLength(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.maxStringLength(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.maxNumberLength(0));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> builder.unknownMembers(Nulls.nullOf(UnknownMembers.class)));
    }
}
