package be.imgn.json;

/// Tells and makes values that do not keep the document they were parsed from.
final class Detached {

    private Detached() {}

    /// Returns whether a value keeps no document: scalars never do, objects and arrays when they were built from
    /// values that keep none.
    static boolean isDetached(JsonValue value) {
        return switch (value) {
            case JsonObject object -> object.isDetached();
            case JsonArray array -> array.isDetached();
            default -> true;
        };
    }

    /// Returns a value that keeps no document, equal to a value.
    static JsonValue copyOf(JsonValue value) {
        return switch (value) {
            case JsonObject object -> JsonObject.copyOf(object);
            case JsonArray array -> JsonArray.copyOf(array);
            default -> value;
        };
    }
}
