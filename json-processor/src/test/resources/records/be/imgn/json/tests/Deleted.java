package be.imgn.json.tests;

import be.imgn.json.JsonName;
import org.jspecify.annotations.Nullable;

/// A deletion.
///
/// @param id the identifier of what was deleted
/// @param reason why, if known
@JsonName("deleted")
public record Deleted(long id, @Nullable String reason) implements Event {}
