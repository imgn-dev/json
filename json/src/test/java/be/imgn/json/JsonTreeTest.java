package be.imgn.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class JsonTreeTest {

    private static final String DOCUMENT =
            "{\"b\": [1, \"x\", null, false], \"a\": {\"c\": {}, \"d\": []}, \"\\u00e9\": \"\\n\"}";

    @Test
    void parsesLazyTreeEqualToBuiltOne() {
        var parsed = new JsonParser().parse(DOCUMENT).root();
        var built = JsonObject.builder()
                .put(
                        "a",
                        JsonObject.builder()
                                .put("d", JsonArray.of())
                                .put("c", JsonObject.of())
                                .build())
                .put("é", new JsonString("\n"))
                .put("b", JsonArray.of(JsonNumber.of(1), new JsonString("x"), JsonNull.INSTANCE, JsonBoolean.FALSE))
                .build();

        assertThat(parsed).isEqualTo(built);
        assertThat(built).isEqualTo(parsed);
        assertThat(parsed.hashCode()).isEqualTo(built.hashCode());
        assertThat(((JsonObject) parsed).members()).isEqualTo(built.members());
    }

    @Test
    void keepsOrderOfMembers() {
        var object = (JsonObject) new JsonParser().parse(DOCUMENT).root();

        assertThat(object.members().keySet()).containsExactly("b", "a", "é");
        assertThat(object.size()).isEqualTo(3);
        assertThat(object.get("é")).contains(new JsonString("\n"));
        assertThat(object.get("z")).isEmpty();
        assertThat(object.members().get("b")).isEqualTo(object.get("b").orElseThrow());
    }

    @Test
    void looksUpLargeObjects() {
        var members =
                IntStream.range(0, 50).mapToObj(i -> "\"m" + i + "\": " + i).toList();
        var object = (JsonObject)
                new JsonParser().parse("{" + String.join(", ", members) + "}").root();

        for (var i = 0; i < 50; i++) {
            assertThat(object.get("m" + i)).contains(JsonNumber.of(i));
        }
        assertThat(object.get("m50")).isEmpty();
    }

    @Test
    void treatsArraysAsLists() {
        var array = (JsonArray) new JsonParser().parse("[3, [2], 1]").root();

        assertThat(array.elements())
                .containsExactly(JsonNumber.of(3), JsonArray.of(JsonNumber.of(2)), JsonNumber.of(1));
        assertThat(array)
                .isEqualTo(JsonArray.of(List.of(JsonNumber.of(3), JsonArray.of(JsonNumber.of(2)), JsonNumber.of(1))));
        assertThat(array)
                .isNotEqualTo(JsonArray.of(JsonNumber.of(1), JsonArray.of(JsonNumber.of(2)), JsonNumber.of(3)));
        assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(() -> array.get(3));
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> array.elements().add(JsonNull.INSTANCE));
    }

    @Test
    void copiesWithoutDocument() {
        var parsed = (JsonObject) new JsonParser().parse(DOCUMENT).root();
        var copy = JsonObject.copyOf(parsed);

        assertThat(copy).isEqualTo(parsed);
        assertThat(copy.tape()).isNull();
        assertThat(((JsonObject) copy.get("a").orElseThrow()).tape()).isNull();
        assertThat(((JsonArray) copy.get("b").orElseThrow()).tape()).isNull();
        assertThat(JsonObject.copyOf(copy)).isSameAs(copy);
        var array = JsonArray.of(parsed);
        assertThat(JsonArray.copyOf(array)).isNotSameAs(array).isEqualTo(array);
    }

    @Test
    void rejectsDuplicateNamesInBuilder() {
        var builder = JsonObject.builder().put("a", JsonNull.INSTANCE);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder.put("a", JsonNull.INSTANCE))
                .withMessage("duplicate member name \"a\"");
        Map<String, JsonValue> map = new LinkedHashMap<>();
        map.put("x", JsonBoolean.TRUE);
        assertThat(JsonObject.of(map).members()).isEqualTo(map);
    }

    @Test
    void createsValuesOnceAcrossThreads() throws Exception {
        var members = IntStream.range(0, 200)
                .mapToObj(i -> "\"m" + i + "\": [" + i + "]")
                .toList();
        var object = (JsonObject)
                new JsonParser().parse("{" + String.join(", ", members) + "}").root();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var results = executor.invokeAll(IntStream.range(0, 8)
                    .<Callable<Integer>>mapToObj(t -> () -> {
                        var sum = 0;
                        for (var i = 0; i < 200; i++) {
                            sum += ((JsonNumber)
                                            ((JsonArray) object.get("m" + i).orElseThrow()).get(0))
                                    .asInt();
                        }
                        return sum;
                    })
                    .toList());
            for (var result : results) {
                assertThat(result.get()).isEqualTo(199 * 200 / 2);
            }
        }
    }

    @Test
    void writesItselfAsCompactJson() {
        assertThat(new JsonParser().parse(DOCUMENT).root())
                .hasToString("{\"b\":[1,\"x\",null,false],\"a\":{\"c\":{},\"d\":[]},\"é\":\"\\n\"}");
        assertThat(new JsonString("a\"b")).hasToString("\"a\\\"b\"");
        assertThat(JsonNull.INSTANCE).hasToString("null");
        assertThat(JsonBoolean.of(true)).hasToString("true");
    }
}
