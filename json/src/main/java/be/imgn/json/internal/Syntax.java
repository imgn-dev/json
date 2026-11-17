package be.imgn.json.internal;

/// What the [TapeBuilder] parses: JSON, a line of JSON Lines, or JSON translated from JSONC or JSON5, which changes
/// what its errors say and whether it accepts `NaN` and the infinities.
public enum Syntax {
    /// JSON, RFC 8259.
    JSON,
    /// A line of JSON Lines: JSON, which ends with the line.
    JSON_LINE,
    /// JSON translated from JSONC.
    JSONC,
    /// JSON translated from JSON5, with `NaN`, `Infinity` and `-Infinity`.
    JSON5
}
