package be.imgn.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class JsonDocumentTest {

    private final JsonDocument document = new JsonParser().parse("""
             {
              "name": "é😀",
              "items": [1, {"a": null}],
              "nested": {"deep": [true]}
            }""");

    @Test
    void locatesValues() {
        var root = (JsonObject) document.root();
        var items = (JsonArray) root.get("items").orElseThrow();
        var nested = (JsonObject) root.get("nested").orElseThrow();

        assertThat(document.rootPosition()).isEqualTo(new JsonPosition(1, 2));
        assertThat(document.position(root, "name")).isEqualTo(new JsonPosition(2, 11));
        assertThat(document.position(root, "items")).isEqualTo(new JsonPosition(3, 12));
        assertThat(document.position(items, 1)).isEqualTo(new JsonPosition(3, 16));
        assertThat(document.position((JsonObject) items.get(1), "a")).isEqualTo(new JsonPosition(3, 22));
        assertThat(document.position(nested, "deep")).isEqualTo(new JsonPosition(4, 22));
    }

    @Test
    void rejectsValuesOfOtherDocuments() {
        var root = (JsonObject) document.root();
        var other = (JsonObject) new JsonParser().parse("{\"name\": 1}").root();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> document.position(other, "name"))
                .withMessage("the object is not one of this document");
        assertThatIllegalArgumentException().isThrownBy(() -> document.position(JsonObject.copyOf(root), "name"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> document.position(root, "missing"))
                .withMessage("the object has no member \"missing\"");
        assertThatExceptionOfType(IndexOutOfBoundsException.class)
                .isThrownBy(
                        () -> document.position((JsonArray) root.get("items").orElseThrow(), 2));
    }
}
