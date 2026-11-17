package be.imgn.json;

import java.io.Serial;

/// Thrown when a document is not valid in the format of the parser, or exceeds a limit of the parser.
///
/// @since 0.1.0
public final class JsonParseException extends JsonException {

    @Serial
    private static final long serialVersionUID = 1L;

    JsonParseException(String message, JsonPosition position, String path) {
        super(message, position, path);
    }
}
