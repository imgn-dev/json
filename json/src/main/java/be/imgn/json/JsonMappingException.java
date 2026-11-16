package be.imgn.json;

import java.io.Serial;

/// Thrown when a valid document does not bind to the type it is read as: a value of the wrong type, a number out of
/// range, a missing or unknown member, or a value the type rejects.
///
/// @since 0.1.0
public final class JsonMappingException extends JsonException {

    @Serial
    private static final long serialVersionUID = 1L;

    JsonMappingException(String message, JsonPosition position, String path) {
        super(message, position, path);
    }

    JsonMappingException(String message, JsonPosition position, String path, Throwable cause) {
        super(message, position, path);
        initCause(cause);
    }
}
