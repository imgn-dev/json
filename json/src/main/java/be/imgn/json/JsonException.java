package be.imgn.json;

import java.io.Serial;

/// Thrown when a document cannot be read: it is not valid JSON, or it does not bind to the type it is read as.
///
/// The message starts with the position of the error and, below the root, its path, then states what was expected and
/// what was found: `line 3, column 14, at /items/2: expected ',' or ']' after an element, found ':'`.
///
/// @since 0.1.0
public abstract sealed class JsonException extends RuntimeException permits JsonParseException, JsonMappingException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int line;
    private final int column;
    private final String path;

    JsonException(String message, JsonPosition position, String path) {
        super(position + (path.isEmpty() ? "" : ", at " + path) + ": " + message);
        this.line = position.line();
        this.column = position.column();
        this.path = path;
    }

    /// Returns where the error is.
    ///
    /// @return the position of the error
    public JsonPosition position() {
        return new JsonPosition(line, column);
    }

    /// Returns the path to the value the error is in, as a [JSON Pointer](https://www.rfc-editor.org/rfc/rfc6901):
    /// `/items/2/name` is the member `name` of the third element of the member `items` of the root. The root is the
    /// empty string.
    ///
    /// @return the path, empty at the root
    public String path() {
        return path;
    }
}
