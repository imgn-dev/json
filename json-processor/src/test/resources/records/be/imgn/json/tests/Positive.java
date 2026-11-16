package be.imgn.json.tests;

import be.imgn.json.JsonRecord;

/// A record whose constructor rejects some values.
///
/// @param value the value, positive
@JsonRecord
public record Positive(int value) {

    /// Creates a value.
    ///
    /// @throws IllegalArgumentException if `value` is not positive
    public Positive {
        if (value <= 0) {
            throw new IllegalArgumentException("not positive: " + value);
        }
    }
}
