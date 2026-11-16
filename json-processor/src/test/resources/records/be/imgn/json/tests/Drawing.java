package be.imgn.json.tests;

import be.imgn.json.JsonObject;
import be.imgn.json.JsonRecord;
import be.imgn.json.JsonValue;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// A drawing: sealed interfaces in a record, and JSON values.
///
/// @param shapes the shapes
/// @param last the last event, if any
/// @param extra anything, which tells a missing member from a null one
/// @param meta an object
@JsonRecord
public record Drawing(
        List<Shape> shapes, @Nullable Event last, @Nullable JsonValue extra, JsonObject meta) {}
