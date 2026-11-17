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
        return of(input, 0, 1, offset);
    }

    /// Returns the line and column of an offset of an input, counting lines from an offset where a line starts.
    ///
    /// @param from the offset where the line numbered `line` starts
    /// @param line the number of that line
    /// @param offset the offset, from `from` to the length of the input
    public static JsonPosition of(byte[] input, int from, int line, int offset) {
        var lineStart = from;
        var lines = line;
        for (var i = from; i < offset; i++) {
            if (input[i] == '\n') {
                lines++;
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
        return new JsonPosition(lines, column);
    }
}
