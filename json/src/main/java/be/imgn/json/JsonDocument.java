package be.imgn.json;

import static be.imgn.gaia.core.base.Preconditions.checkArgument;
import static java.util.Objects.checkIndex;
import static java.util.Objects.requireNonNull;

import be.imgn.json.internal.Tape;

/// A parsed document: its root value, and where each of its values is written.
///
/// The root and the objects and arrays it holds are lazy: their values are created on their first access. The
/// document keeps its bytes and its index in memory, to create them and to compute positions.
///
/// @since 0.1.0
public final class JsonDocument {

    private final Tape tape;
    private final JsonValue root;

    JsonDocument(Tape tape) {
        this.tape = tape;
        this.root = TapeValues.at(tape, 0);
    }

    /// Returns the root value.
    ///
    /// @return the root value
    public JsonValue root() {
        return root;
    }

    /// Returns where the root value starts.
    ///
    /// @return the position of the first character of the root value
    public JsonPosition rootPosition() {
        return position(0);
    }

    /// Returns where the value of a member starts.
    ///
    /// @param object an object of this document, as this document returned it
    /// @param name the name of the member
    /// @return the position of the first character of the value
    /// @throws IllegalArgumentException if `object` is not an object of this document, or has no such member
    public JsonPosition position(JsonObject object, String name) {
        requireNonNull(object, "object");
        requireNonNull(name, "name");
        checkArgument(object.tape() == tape, "the object is not one of this document");
        var member = object.indexOf(name);
        checkArgument(member >= 0, "the object has no member \"%s\"", name);
        return position(object.valueIndex(member));
    }

    /// Returns where an element of an array starts.
    ///
    /// @param array an array of this document, as this document returned it
    /// @param index the index of the element
    /// @return the position of the first character of the element
    /// @throws IllegalArgumentException if `array` is not an array of this document
    /// @throws IndexOutOfBoundsException if `index` is negative, or not less than the size of the array
    public JsonPosition position(JsonArray array, int index) {
        requireNonNull(array, "array");
        checkArgument(array.tape() == tape, "the array is not one of this document");
        checkIndex(index, array.size());
        return position(array.valueIndex(index));
    }

    private JsonPosition position(int index) {
        return tape.position(tape.valueOffset(index));
    }
}
