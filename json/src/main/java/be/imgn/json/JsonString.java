package be.imgn.json;

import static java.util.Objects.requireNonNull;

/// A JSON string.
///
/// The value may hold any `String`, but [JsonWriter] refuses to write one with an unpaired surrogate, which UTF-8
/// cannot encode; the parser never creates one.
///
/// @param value the value
/// @since 0.1.0
public record JsonString(String value) implements JsonValue {

    /// Creates a string.
    ///
    /// @throws NullPointerException if `value` is null
    public JsonString {
        requireNonNull(value, "value");
    }

    @Override
    public JsonType type() {
        return JsonType.STRING;
    }

    /// Returns the string as JSON, quoted and escaped.
    ///
    /// @throws IllegalArgumentException if the string has an unpaired surrogate
    @Override
    public String toString() {
        return new JsonWriter().format(this);
    }
}
