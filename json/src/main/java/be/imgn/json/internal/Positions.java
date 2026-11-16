package be.imgn.json.internal;

import be.imgn.json.JsonPosition;

/// Computes positions in a document from offsets, when they are asked for: nothing is recorded while parsing.
public final class Positions {

    private Positions() {}

    /// Returns the line and column of an offset of a document. The column counts the UTF-16 code units before the
    /// offset on its line; past invalid UTF-8, it is approximate.
    ///
    /// @param offset the offset, from 0 to the length of the document
    public static JsonPosition of(byte[] input, int offset) {
        var line = 1;
        var lineStart = 0;
        for (var i = 0; i < offset; i++) {
            if (input[i] == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        var column = 1;
        for (var i = lineStart; i < offset; i++) {
            var b = Byte.toUnsignedInt(input[i]);
            if (!Utf8.isContinuation(b)) {
                column += Utf8.sequenceLength(b) == Utf8.MAX_LENGTH ? 2 : 1;
            }
        }
        return new JsonPosition(line, column);
    }
}
