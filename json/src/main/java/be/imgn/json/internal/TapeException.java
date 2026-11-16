package be.imgn.json.internal;

import java.io.Serial;

/// Thrown by the [TapeBuilder] when a document is not valid JSON or exceeds a limit. The public API turns it into a
/// `JsonParseException`, with the position computed from the offset; it has no stack trace of its own, as it never
/// leaves the module.
public final class TapeException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String description;
    private final int offset;
    private final String path;

    TapeException(String description, int offset, String path) {
        super(description, null, false, false);
        this.description = description;
        this.offset = offset;
        this.path = path;
    }

    /// Returns what is wrong, without the position.
    public String description() {
        return description;
    }

    /// Returns the offset of the error in the document, in bytes.
    public int offset() {
        return offset;
    }

    /// Returns the path of the value the error is in, as a JSON Pointer.
    public String path() {
        return path;
    }
}
