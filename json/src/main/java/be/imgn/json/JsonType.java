package be.imgn.json;

/// The type of a JSON value.
///
/// @since 0.1.0
public enum JsonType {
    /// An object: `{"name": value, ...}`.
    OBJECT("an object"),
    /// An array: `[value, ...]`.
    ARRAY("an array"),
    /// A string.
    STRING("a string"),
    /// A number.
    NUMBER("a number"),
    /// `true` or `false`.
    BOOLEAN("a boolean"),
    /// `null`.
    NULL("null");

    private final String description;

    JsonType(String description) {
        this.description = description;
    }

    /// Returns the type with its article, as error messages name it: `"an object"`, `"null"`.
    String description() {
        return description;
    }
}
