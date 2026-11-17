package be.imgn.json;

/// The syntax a [JsonParser] reads, each strictly: a document is rejected as soon as it steps outside its
/// format.
///
/// Each format accepts everything the previous one does. Whatever the format, duplicate member names, unpaired
/// surrogates and invalid UTF-8 are rejected, the limits of the parser apply, and the document is read as JSON:
/// a [JsonWriter] writes it back as JSON.
///
/// @since 0.1.0
public enum JsonFormat {

    /// [RFC 8259](https://www.rfc-editor.org/rfc/rfc8259) JSON, the default.
    JSON,

    /// [JSONC](https://jsonc.org): JSON with `//` and `/* */` comments wherever JSON has whitespace. A line
    /// comment ends with a line feed or a carriage return. Trailing commas, which JSONC lets a parser accept,
    /// are rejected, as by its reference parser.
    JSONC,

    /// [JSON5 1.0.0](https://spec.json5.org): JSON with comments; trailing commas; names written as ECMAScript
    /// 5.1 identifiers; strings in single quotes, with more escape sequences and lines continued by a
    /// backslash; hexadecimal numbers, numbers with a leading or trailing decimal point or a plus sign, `NaN`
    /// and the infinities; and more white space, a leading byte order mark included.
    ///
    /// `NaN` and the infinities have no JSON form: [JsonNumber#asDouble()] returns them, and writing them as
    /// JSON is an [IllegalArgumentException]. The maximum length of a string applies to it as JSON writes it, that
    /// of a number both as written and as JSON writes it.
    JSON5
}
