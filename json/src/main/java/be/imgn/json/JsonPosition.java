package be.imgn.json;

import static be.imgn.gaia.core.base.Preconditions.checkArgument;

/// A position in a document: a line and a column.
///
/// A line ends with `\n`; a `\r` before it belongs to the line it ends.
///
/// @param line the line, from 1
/// @param column the column, from 1, counted in UTF-16 code units, as in a [String]
/// @since 0.1.0
public record JsonPosition(int line, int column) {

    /// Creates a position.
    ///
    /// @throws IllegalArgumentException if `line` or `column` is less than 1
    public JsonPosition {
        checkArgument(line >= 1, "the line %s is less than 1", line);
        checkArgument(column >= 1, "the column %s is less than 1", column);
    }

    @Override
    public String toString() {
        return "line " + line + ", column " + column;
    }
}
