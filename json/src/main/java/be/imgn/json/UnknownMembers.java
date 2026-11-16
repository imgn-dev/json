package be.imgn.json;

/// What binding does with a member of an object that no component of the record takes.
///
/// @since 0.1.0
public enum UnknownMembers {
    /// Skips the member; its value must still be valid JSON.
    IGNORE,
    /// Throws a [JsonMappingException].
    REJECT
}
