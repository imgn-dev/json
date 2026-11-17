package be.imgn.json.internal;

/// What the translations of JSONC and JSON5 to JSON share.
final class Translations {

    private Translations() {}

    /// Returns an error of a translation, unless the tape builder finds an earlier one in what was translated before
    /// it: the error reported is the first one of the document, and it has the path the tape builder knows.
    ///
    /// @param json the translation so far
    /// @param length the length of the translation before the error
    /// @param locator the positions of the translation so far
    /// @param error the error of the translation, without a path
    static TapeException error(
            byte[] json, int length, Limits limits, Syntax syntax, Locator locator, TapeException error) {
        try {
            TapeBuilder.build(json, 0, length, limits, syntax, locator);
            return error;
        } catch (TapeException e) {
            // The translation stops before the error: the tape builder fails at its end, where the path is that of
            // the error, unless it found an error before.
            return e.offset() < length ? e : error.at(e.path());
        }
    }
}
