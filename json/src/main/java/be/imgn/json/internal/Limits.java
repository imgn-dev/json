package be.imgn.json.internal;

/// The limits a document must stay within, each checked by the [TapeBuilder].
///
/// @param maxDepth the maximum number of nested objects and arrays
/// @param maxDocumentLength the maximum length of the document, in bytes
/// @param maxStringLength the maximum length of a string or a name, in bytes, as written between its quotes
/// @param maxNumberLength the maximum length of a number, in bytes, as written
public record Limits(int maxDepth, int maxDocumentLength, int maxStringLength, int maxNumberLength) {

    /// The default limits.
    public static final Limits DEFAULTS = new Limits(1_000, Integer.MAX_VALUE, 20_000_000, 1_000);
}
