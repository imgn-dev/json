package be.imgn.json.internal;

import be.imgn.json.JsonPosition;
import java.io.Serial;

/// Thrown by the [TapeBuilder], or by the translation of JSONC or JSON5, when a document is not valid or exceeds a
/// limit. The public API turns it into a `JsonParseException`, with the position computed from the offset; it has no
/// stack trace of its own, as it never leaves the module.
public final class TapeException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String description;
    private final int offset;
    private final String path;
    private final transient Locator locator;

    TapeException(String description, int offset, String path, Locator locator) {
        super(description, null, false, false);
        this.description = description;
        this.offset = offset;
        this.path = path;
        this.locator = locator;
    }

    /// Returns what is wrong, without the position.
    public String description() {
        return description;
    }

    /// Returns the offset of the error in the document, in bytes.
    public int offset() {
        return offset;
    }

    /// Returns this error, at a path.
    TapeException at(String path) {
        return new TapeException(description, offset, path, locator);
    }

    /// Returns the position of the error in the source.
    public JsonPosition position() {
        return locator.position(offset);
    }

    /// Returns the path of the value the error is in, as a JSON Pointer.
    public String path() {
        return path;
    }
}
