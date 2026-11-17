package be.imgn.json.internal;

import be.imgn.json.JsonPosition;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/// Maps the offsets of the document a tape was built from to lines and columns of its source: the document itself, a
/// line of a larger input, or the JSONC or JSON5 document it was translated from.
public final class Locator {

    private final byte[] source;
    private final int from;
    private final int line;
    // Where the translation shifts offsets, sorted: the offsets in the translated document, and the offsets of the
    // source they come from; the offsets in between move together. Null when the offsets are those of the source.
    private final int @Nullable [] translated;
    private final int @Nullable [] original;
    private final int shifts;

    private Locator(
            byte[] source, int from, int line, int @Nullable [] translated, int @Nullable [] original, int shifts) {
        this.source = source;
        this.from = from;
        this.line = line;
        this.translated = translated;
        this.original = original;
        this.shifts = shifts;
    }

    /// Returns the locator of a document parsed as it is.
    public static Locator of(byte[] source) {
        return new Locator(source, 0, 1, null, null, 0);
    }

    /// Returns the locator of a line of an input, which starts at an offset.
    ///
    /// @param from the offset where the line starts
    /// @param line the number of the line
    public static Locator ofLine(byte[] source, int from, int line) {
        return new Locator(source, from, line, null, null, 0);
    }

    /// Returns the locator of a translated document.
    ///
    /// @param translated the offsets in the translated document where the translation shifts offsets, sorted
    /// @param original the offsets of the source they come from
    /// @param shifts how many of those offsets are used
    static Locator translated(byte[] source, int[] translated, int[] original, int shifts) {
        return new Locator(source, 0, 1, translated, original, shifts);
    }

    /// Returns the position of an offset of the document.
    public JsonPosition position(int offset) {
        return Positions.of(source, from, line, sourceOffset(offset));
    }

    /// Returns the offset in the source of an offset of the document.
    int sourceOffset(int offset) {
        if (translated == null || original == null) {
            return offset;
        }
        var i = Arrays.binarySearch(translated, 0, shifts, offset);
        if (i < 0) {
            i = -i - 2;
        }
        // Several shifts at the same offset: the last one is where the following bytes come from.
        while (i + 1 < shifts && translated[i + 1] == offset) {
            i++;
        }
        return i < 0 ? offset : Math.min(original[i] + offset - translated[i], source.length);
    }
}
