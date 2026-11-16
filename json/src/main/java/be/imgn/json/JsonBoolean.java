package be.imgn.json;

/// A JSON boolean.
///
/// @since 0.1.0
public enum JsonBoolean implements JsonValue {
    /// `true`.
    TRUE,
    /// `false`.
    FALSE;

    /// Returns the JSON boolean of a value.
    ///
    /// @param value the value
    /// @return [#TRUE] or [#FALSE]
    public static JsonBoolean of(boolean value) {
        return value ? TRUE : FALSE;
    }

    /// Returns the value of this boolean.
    ///
    /// @return `true` for [#TRUE]
    public boolean value() {
        return this == TRUE;
    }

    @Override
    public JsonType type() {
        return JsonType.BOOLEAN;
    }

    @Override
    public String toString() {
        return this == TRUE ? "true" : "false";
    }
}
