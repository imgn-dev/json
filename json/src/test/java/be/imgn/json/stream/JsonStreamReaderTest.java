package be.imgn.json.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import be.imgn.json.JsonMappingException;
import be.imgn.json.JsonObject;
import be.imgn.json.JsonParser;
import be.imgn.json.JsonType;
import be.imgn.json.bind.UnknownMembers;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class JsonStreamReaderTest {

    private static final JsonNames NAMES = JsonNames.of("id", "name", "tags", "é");

    @Test
    void readsTypedValues() {
        var reader = new JsonParser().reader("""
                {"id": 42, "name": "x", "skipped": {"a": [1, 2]}, "tags": ["a", "b"], "\\u00e9": 1.5}""");
        var tags = new ArrayList<String>();
        long id = 0;
        String name = null;
        double accent = 0;

        reader.beginObject();
        while (reader.hasNextMember()) {
            switch (reader.name(NAMES)) {
                case 0 -> id = reader.readLong();
                case 1 -> name = reader.readString();
                case 2 -> {
                    reader.beginArray();
                    while (reader.hasNextElement()) {
                        tags.add(reader.readString());
                    }
                }
                case 3 -> accent = reader.readDouble();
                default -> reader.skipUnknownMember(NAMES);
            }
        }

        assertThat(id).isEqualTo(42);
        assertThat(name).isEqualTo("x");
        assertThat(tags).containsExactly("a", "b");
        assertThat(accent).isEqualTo(1.5);
        assertThat(reader.isDone()).isTrue();
    }

    @Test
    void readsEveryType() {
        var reader =
                new JsonParser().reader("[1, 2, 3, 4, 5.5, 6.5, 70000000000000000000, 0.1, true, null, \"s\", {}]");

        reader.beginArray();
        reader.hasNextElement();
        assertThat(reader.readInt()).isEqualTo(1);
        reader.hasNextElement();
        assertThat(reader.readShort()).isEqualTo((short) 2);
        reader.hasNextElement();
        assertThat(reader.readByte()).isEqualTo((byte) 3);
        reader.hasNextElement();
        assertThat(reader.peek()).isEqualTo(JsonType.NUMBER);
        assertThat(reader.readLong()).isEqualTo(4);
        reader.hasNextElement();
        assertThat(reader.readFloat()).isEqualTo(5.5f);
        reader.hasNextElement();
        assertThat(reader.readDouble()).isEqualTo(6.5);
        reader.hasNextElement();
        assertThat(reader.readBigInteger()).isEqualTo(new BigInteger("70000000000000000000"));
        reader.hasNextElement();
        assertThat(reader.readBigDecimal()).isEqualTo(new BigDecimal("0.1"));
        reader.hasNextElement();
        assertThat(reader.readBoolean()).isTrue();
        reader.hasNextElement();
        assertThat(reader.readNull()).isTrue();
        reader.hasNextElement();
        assertThat(reader.readNull()).isFalse();
        assertThat(reader.readString()).isEqualTo("s");
        reader.hasNextElement();
        assertThat(reader.readValue()).isEqualTo(JsonObject.of());
        assertThat(reader.hasNextElement()).isFalse();
    }

    @Test
    void reportsTypeMismatchWithPath() {
        var reader = new JsonParser().reader("{\"a\": [1, \"x\"]}");
        reader.beginObject();
        reader.hasNextMember();
        reader.readName();
        reader.beginArray();
        reader.hasNextElement();
        reader.readInt();
        reader.hasNextElement();

        assertThatExceptionOfType(JsonMappingException.class)
                .isThrownBy(reader::readInt)
                .withMessage("line 1, column 11, at /a/1: expected an int, found a string");
    }

    @Test
    void reportsNumberOutOfRange() {
        var reader = new JsonParser().reader("[3000000000, 1.5, 1e400]");
        reader.beginArray();
        reader.hasNextElement();

        assertThatExceptionOfType(JsonMappingException.class)
                .isThrownBy(reader::readInt)
                .withMessage("line 1, column 2, at /0: expected an int, found 3000000000");
        reader.skipValue();
        reader.hasNextElement();
        assertThatExceptionOfType(JsonMappingException.class)
                .isThrownBy(reader::readLong)
                .withMessage("line 1, column 14, at /1: expected a long, found 1.5");
        reader.skipValue();
        reader.hasNextElement();
        assertThatExceptionOfType(JsonMappingException.class)
                .isThrownBy(reader::readDouble)
                .withMessage("line 1, column 19, at /2: expected a double, found 1e400");
    }

    @Test
    void rejectsUnknownMemberWhenAsked() {
        var reader = JsonParser.builder()
                .unknownMembers(UnknownMembers.REJECT)
                .build()
                .reader("{\"other\": 1}");
        reader.beginObject();
        reader.hasNextMember();

        assertThat(reader.name(NAMES)).isEqualTo(-1);
        assertThatExceptionOfType(JsonMappingException.class)
                .isThrownBy(() -> reader.skipUnknownMember(NAMES))
                .withMessage("line 1, column 2, at /other: unknown member \"other\", expected one of \"id\", \"name\","
                        + " \"tags\", \"é\"");
    }

    @Test
    void reportsErrorAtMark() {
        var reader = new JsonParser().reader("{\"user\": {\"id\": 1}}");
        reader.beginObject();
        reader.hasNextMember();
        reader.readName();
        var mark = reader.mark();
        reader.skipValue();

        assertThat(reader.mappingError(mark, "missing member \"name\" of User"))
                .hasMessage("line 1, column 10, at /user: missing member \"name\" of User");
    }

    @Test
    void rejectsCallsOutOfOrder() {
        var reader = new JsonParser().reader("{\"a\": 1}");

        assertThatIllegalStateException().isThrownBy(reader::hasNextMember);
        reader.beginObject();
        assertThatIllegalStateException().isThrownBy(reader::readInt);
        reader.hasNextMember();
        assertThatIllegalStateException().isThrownBy(reader::readString).withMessageContaining("member name");
        reader.readName();
        assertThatIllegalStateException().isThrownBy(reader::hasNextMember);
        assertThatIllegalStateException().isThrownBy(reader::readName);
        reader.readInt();
        assertThat(reader.hasNextMember()).isFalse();
        assertThatIllegalStateException().isThrownBy(reader::readInt).withMessage("the document was read whole");
    }

    @Test
    void sizesTheContainerBeingRead() {
        var reader = new JsonParser().reader("{\"a\": [1, [2, 3], {}], \"b\": {\"c\": 1, \"d\": 2}, \"e\": []}");

        reader.beginObject();
        assertThat(reader.size()).isEqualTo(3);
        reader.hasNextMember();
        reader.readName();
        reader.beginArray();
        assertThat(reader.size()).isEqualTo(3);
        reader.hasNextElement();
        reader.readInt();
        assertThat(reader.size()).isEqualTo(3);
        reader.hasNextElement();
        reader.beginArray();
        assertThat(reader.size()).isEqualTo(2);
        while (reader.hasNextElement()) {
            reader.skipValue();
        }
        assertThat(reader.size()).isEqualTo(3);
        reader.hasNextElement();
        reader.beginObject();
        assertThat(reader.size()).isZero();
        assertThat(reader.hasNextMember()).isFalse();
        assertThat(reader.hasNextElement()).isFalse();
        assertThat(reader.size()).isEqualTo(3);
        reader.hasNextMember();
        reader.readName();
        reader.beginObject();
        assertThat(reader.size()).isEqualTo(2);
        while (reader.hasNextMember()) {
            reader.readName();
            reader.skipValue();
        }
        reader.hasNextMember();
        reader.readName();
        reader.beginArray();
        assertThat(reader.size()).isZero();
    }

    @Test
    void sizesLargeArrays() {
        var reader = new JsonParser().reader("[" + "0,".repeat(99_999) + "0]");

        reader.beginArray();
        assertThat(reader.size()).isEqualTo(100_000);
    }

    @Test
    void rejectsSizeOutsideAContainer() {
        var reader = new JsonParser().reader("[1]");

        assertThatIllegalStateException()
                .isThrownBy(reader::size)
                .withMessage("the reader is not in an array or an object");
        reader.beginArray();
        reader.hasNextElement();
        reader.readInt();
        assertThat(reader.hasNextElement()).isFalse();
        assertThatIllegalStateException().isThrownBy(reader::size);
    }
}
