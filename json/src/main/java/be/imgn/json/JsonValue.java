package be.imgn.json;

/// A JSON value. Values are immutable and thread-safe.
///
/// Two values are equal when they are of the same type and hold equal contents: objects with the same members,
/// whatever their order; arrays with the same elements, in the same order; numbers with the same numeric value, so
/// that `1`, `1.0` and `1e0` are equal. [Object#toString()] returns the value as compact JSON.
///
/// @since 0.1.0
public sealed interface JsonValue permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull {

    /// Returns the type of this value.
    ///
    /// @return the type
    JsonType type();
}
