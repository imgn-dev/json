package be.imgn.json;

/// The JSON `null`.
///
/// @since 0.1.0
public enum JsonNull implements JsonValue {
    /// The only `null`.
    INSTANCE;

    @Override
    public JsonType type() {
        return JsonType.NULL;
    }

    @Override
    public String toString() {
        return "null";
    }
}
