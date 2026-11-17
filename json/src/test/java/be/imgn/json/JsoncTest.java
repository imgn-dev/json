package be.imgn.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;

class JsoncTest {

    private static final JsonParser PARSER =
            JsonParser.builder().format(JsonFormat.JSONC).build();
    private static final JsonWriter WRITER = new JsonWriter();

    @Test
    void readsCommentsWhereverJsonHasWhitespace() {
        var document = "// first\n{ /* a */ \"a\" /* b */ : /* c */ [1, // d\n 2] /* e */ } // end";

        assertThat(json(document)).isEqualTo("{\"a\":[1,2]}");
    }

    @Test
    void endsLineCommentsWithCarriageReturn() {
        assertThat(json("[1, // one\r2]")).isEqualTo("[1,2]");
    }

    @Test
    void leavesStringsAlone() {
        assertThat(json("{\"a\": \"// not /* a comment */\", \"b\\\"//\": 1}"))
                .isEqualTo("{\"a\":\"// not /* a comment */\",\"b\\\"//\":1}");
    }

    @Test
    void endsBlockCommentsWithTheFirstClose() {
        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> PARSER.parse("[/* a /* b */ c */ 1]"))
                .withMessage("line 1, column 15, at /0: expected a value, found 'c'");
    }

    @Test
    void rejectsTrailingCommas() {
        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> PARSER.parse("// list\n[1,\n /* x */ ]"))
                .withMessage("line 3, column 10, at /0: expected a value after ',', found ']': a trailing comma is not"
                        + " allowed in JSONC (JsonFormat.JSON5 allows it)");
    }

    @Test
    void rejectsUnterminatedBlockComment() {
        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> PARSER.parse("[1, /* x"))
                .withMessage(
                        "line 1, column 9, at /1: expected '*/' to end the comment, found the end of the document");
    }

    @Test
    void reportsTheFirstError() {
        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> PARSER.parse("[1 2 /* x"))
                .withMessage("line 1, column 4, at /0: expected ',' or ']' after an element, found '2'");
    }

    @Test
    void rejectsInvalidUtf8InComment() {
        var document = new byte[] {'1', ' ', '/', '*', (byte) 0xC3, '*', '/'};

        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> PARSER.parse(document))
                .withMessage("line 1, column 5: invalid UTF-8 sequence 0xC3 0x2A in a comment");
    }

    @Test
    void rejectsByteOrderMark() {
        var document = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '1'};

        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> PARSER.parse(document))
                .withMessage("line 1, column 1: a byte order mark is not allowed");
    }

    @Test
    void rejectsWhatOnlyJson5Has() {
        assertThatExceptionOfType(JsonParseException.class)
                .isThrownBy(() -> PARSER.parse("{a: 1}"))
                .withMessage("line 1, column 2: expected a member name or '}', found 'a'");
    }

    @Test
    void positionsValuesInTheSource() {
        var document = PARSER.parse("/* a\n b */ {\"a\": /* x */ 1}");

        assertThat(document.position((JsonObject) document.root(), "a")).isEqualTo(new JsonPosition(2, 21));
    }

    @Test
    void refusesJsonLines() {
        assertThatIllegalStateException()
                .isThrownBy(() -> PARSER.parseLines("1"))
                .withMessage("JSON Lines are JSON only, and this parser reads JSONC");
    }

    private static String json(String document) {
        return WRITER.format(PARSER.parse(document).root());
    }
}
