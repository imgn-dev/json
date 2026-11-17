package be.imgn.json;

import static java.util.Objects.checkIndex;
import static java.util.Objects.requireNonNull;

import be.imgn.json.internal.Tape;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;
import org.jspecify.annotations.Nullable;

/// A JSON array: values, in order.
///
/// An array parsed from a document is lazy: it creates each element on its first access, and keeps the document in
/// memory while it is referenced. [#copyOf(JsonArray)] makes a copy that does not.
///
/// Two arrays are equal when they have equal elements in the same order, as two [List]s are.
///
/// @since 0.1.0
public final class JsonArray implements JsonValue {

    private static final VarHandle VALUES = MethodHandles.arrayElementVarHandle(JsonValue[].class);
    private static final JsonArray EMPTY = new JsonArray(new JsonValue[0]);

    // The tape of a parsed array, with the index of its start and of each element; null when built.
    private final @Nullable Tape tape;
    private final int start;
    private final int[] indices;
    // The elements of a built array; for a parsed one, those created so far, set with release semantics.
    private final JsonValue[] values;

    private JsonArray(JsonValue[] values) {
        this.tape = null;
        this.start = -1;
        this.indices = new int[0];
        this.values = values;
    }

    JsonArray(Tape tape, int start) {
        this.tape = tape;
        this.start = start;
        var size = tape.count(start);
        this.indices = new int[size];
        for (int i = 0, element = start + 1; i < size; i++, element = tape.next(element)) {
            indices[i] = element;
        }
        this.values = new JsonValue[size];
    }

    /// Returns an array of values.
    ///
    /// @param values the values
    /// @return the array
    /// @throws NullPointerException if a value is null
    public static JsonArray of(JsonValue... values) {
        return of(List.of(values));
    }

    /// Returns an array of the values of a list.
    ///
    /// @param values the values
    /// @return the array
    /// @throws NullPointerException if a value is null
    public static JsonArray of(List<? extends JsonValue> values) {
        return values.isEmpty() ? EMPTY : new JsonArray(List.copyOf(values).toArray(JsonValue[]::new));
    }

    /// Returns a copy of an array that does not keep the document it was parsed from: the copies of its objects and
    /// arrays do not either.
    ///
    /// @param array the array
    /// @return the copy, or `array` if it was built rather than parsed, as were all the objects and arrays it holds
    public static JsonArray copyOf(JsonArray array) {
        if (array.isDetached()) {
            return array;
        }
        return of(array.elements().stream().map(Detached::copyOf).toList());
    }

    /// Returns the number of elements.
    ///
    /// @return the number of elements
    public int size() {
        return values.length;
    }

    /// Returns whether this array has no element.
    ///
    /// @return whether this array has no element
    public boolean isEmpty() {
        return values.length == 0;
    }

    /// Returns an element.
    ///
    /// @param index the index of the element, from 0
    /// @return the element
    /// @throws IndexOutOfBoundsException if `index` is negative, or not less than [#size()]
    public JsonValue get(int index) {
        checkIndex(index, values.length);
        if (tape == null) {
            return values[index];
        }
        var value = (JsonValue) VALUES.getAcquire(values, index);
        if (value == null) {
            value = TapeValues.at(requireNonNull(tape), indices[index]);
            VALUES.setRelease(values, index, value);
        }
        return value;
    }

    /// Returns the elements, as an unmodifiable list.
    ///
    /// @return the elements
    public List<JsonValue> elements() {
        return new Elements();
    }

    @Override
    public JsonType type() {
        return JsonType.ARRAY;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof JsonArray array && elements().equals(array.elements());
    }

    @Override
    public int hashCode() {
        return elements().hashCode();
    }

    /// Returns this array as compact JSON, except for JSON5's `NaN` and infinities, which JSON has no way
    /// to write: they are written `NaN`, `Infinity` and `-Infinity`, where [JsonWriter] rejects them.
    ///
    /// @throws IllegalArgumentException if a string of this array has an unpaired surrogate
    @Override
    public String toString() {
        return TreeWriter.toString(this);
    }

    @Nullable
    Tape tape() {
        return tape;
    }

    boolean isDetached() {
        if (tape != null) {
            return false;
        }
        for (var value : values) {
            if (!Detached.isDetached(value)) {
                return false;
            }
        }
        return true;
    }

    int start() {
        return start;
    }

    /// Returns the tape index of an element of a parsed array.
    int valueIndex(int element) {
        return indices[element];
    }

    /// The elements of this array, as a list.
    private final class Elements extends AbstractList<JsonValue> implements RandomAccess {

        @Override
        public JsonValue get(int index) {
            return JsonArray.this.get(index);
        }

        @Override
        public int size() {
            return JsonArray.this.size();
        }
    }
}
