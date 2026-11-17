package be.imgn.json.bind;

/// What binding does with a member of an object that no component of the record takes.
///
/// @since 0.1.0
public enum UnknownMembers {
    /// Skips the member; its value must still be valid.
    IGNORE,
    /// Throws a [JsonMappingException][be.imgn.json.JsonMappingException].
    REJECT
}
