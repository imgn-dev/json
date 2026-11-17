package be.imgn.json.internal;

/// Parses [JSONC](https://jsonc.org): translates it to JSON, each comment becoming spaces of the same length, then
/// builds its tape. A document without comments is not copied. Every offset of the translation is that of the source,
/// so the positions of the tape builder's errors need no mapping.
///
/// A line comment ends with a line feed or a carriage return; a block comment ends with the first `*/`, and does not
/// nest. Comments are checked to be valid UTF-8, like the rest of a document. Everything else is JSON, checked by the
/// tape builder: trailing commas are rejected, as JSONC's reference parser does by default.
public final class Jsonc {

    private final byte[] source;
    // The translation: the source itself until a comment is found, then a copy of it.
    private byte[] json;
    private boolean copied;
    private final int end;
    private final Limits limits;

    private Jsonc(byte[] source, Limits limits) {
        this.source = source;
        this.json = source;
        this.end = source.length;
        this.limits = limits;
    }

    /// Validates a JSONC document and builds the tape of its translation to JSON.
    ///
    /// @param source the document, in UTF-8
    /// @throws TapeException if the document is not valid JSONC, or exceeds a limit
    public static Tape build(byte[] source, Limits limits) {
        if (source.length > limits.maxDocumentLength()) {
            throw new TapeException(
                    "the document is longer than the maximum of " + limits.maxDocumentLength() + " bytes",
                    limits.maxDocumentLength(),
                    "",
                    Locator.of(source));
        }
        var jsonc = new Jsonc(source, limits);
        jsonc.translate();
        return TapeBuilder.build(jsonc.json, 0, jsonc.end, limits, Syntax.JSONC, Locator.of(source));
    }

    private void translate() {
        var p = Swar.indexOfEither(source, 0, end, (byte) '"', (byte) '/');
        while (p < end) {
            if (source[p] == '"') {
                p = skipString(p);
            } else if (p + 1 < end && source[p + 1] == '/') {
                p = lineComment(p);
            } else if (p + 1 < end && source[p + 1] == '*') {
                p = blockComment(p);
            } else {
                p++;
            }
            p = Swar.indexOfEither(source, p, end, (byte) '"', (byte) '/');
        }
    }

    /// Returns the offset after the string whose quote is at an offset. The tape builder validates the string: this
    /// only has to find where it ends, so as not to take a `//` in it for a comment.
    private int skipString(int quote) {
        var p = Swar.indexOfEither(source, quote + 1, end, (byte) '"', (byte) '\\');
        while (p < end && source[p] == '\\') {
            p = Swar.indexOfEither(source, p + 2, end, (byte) '"', (byte) '\\');
        }
        return p + 1;
    }

    private int lineComment(int start) {
        var p = start + 2;
        while (p < end && source[p] != '\n' && source[p] != '\r') {
            p = commentByte(start, p);
        }
        blank(start, p);
        return p;
    }

    private int blockComment(int start) {
        var p = start + 2;
        while (p + 1 < end && !(source[p] == '*' && source[p + 1] == '/')) {
            p = commentByte(start, p);
        }
        if (p + 1 >= end) {
            throw error(start, end, "expected '*/' to end the comment, found the end of the document");
        }
        blank(start, p + 2);
        return p + 2;
    }

    /// Returns the offset after the character of a comment at an offset, checking it is valid UTF-8.
    private int commentByte(int start, int p) {
        if (source[p] >= 0) {
            return p + 1;
        }
        var after = Utf8.sequenceEnd(source, p, end);
        if (after < 0) {
            var length = Math.max(1, Math.min(Utf8.sequenceLength(Byte.toUnsignedInt(source[p])), end - p));
            throw error(start, p, TapeBuilder.invalidUtf8(source, p, length) + " in a comment");
        }
        return after;
    }

    private void blank(int from, int to) {
        if (!copied) {
            json = source.clone();
            copied = true;
        }
        for (var i = from; i < to; i++) {
            json[i] = ' ';
        }
    }

    /// Returns an error at an offset, in the comment that starts at another, unless the tape builder finds one earlier,
    /// before the comment.
    private TapeException error(int comment, int offset, String description) {
        var locator = Locator.of(source);
        return Translations.error(
                json, comment, limits, Syntax.JSONC, locator, new TapeException(description, offset, "", locator));
    }
}
