package be.imgn.json;

import static be.imgn.gaia.core.base.Preconditions.checkArgument;
import static be.imgn.gaia.core.base.Preconditions.checkState;
import static java.util.Objects.checkIndex;
import static java.util.Objects.requireNonNull;

import be.imgn.json.internal.Output;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;

/// Writes a document value by value, as UTF-8, compact or pretty as the [JsonWriter] that created it. It checks the
/// grammar: a value where a member name is expected, a second root value, or an end that does not match its start, is
/// an [IllegalStateException].
///
/// ```java
/// var writer = new JsonWriter().streamWriter();
/// writer.beginObject().name("id").value(42).name("tags").beginArray().value("a").endArray().endObject();
/// byte[] json = writer.toByteArray();
/// ```
///
/// A writer is not thread-safe.
///
/// @since 0.1.0
public final class JsonStreamWriter {

    private static final byte OBJECT = 1;
    private static final byte ARRAY = 2;
    private static final int INITIAL_DEPTH = 16;

    private final Output output;
    private final boolean writeNullMembers;
    private byte[] kinds = new byte[INITIAL_DEPTH];
    private int depth;
    private boolean afterName;
    private boolean complete;

    JsonStreamWriter(int indent, boolean writeNullMembers) {
        this(new Output(indent), writeNullMembers);
    }

    JsonStreamWriter(Output output, boolean writeNullMembers) {
        this.output = output;
        this.writeNullMembers = writeNullMembers;
    }

    /// Starts an object.
    ///
    /// @return this writer
    /// @throws IllegalStateException if a value cannot come here
    public JsonStreamWriter beginObject() {
        beforeValue();
        push(OBJECT);
        output.beginObject();
        return this;
    }

    /// Ends the object being written.
    ///
    /// @return this writer
    /// @throws IllegalStateException if no object is being written, or its last name has no value
    public JsonStreamWriter endObject() {
        checkState(depth > 0 && kinds[depth] == OBJECT && !afterName, "no object can end here");
        pop();
        output.endObject();
        return this;
    }

    /// Starts an array.
    ///
    /// @return this writer
    /// @throws IllegalStateException if a value cannot come here
    public JsonStreamWriter beginArray() {
        beforeValue();
        push(ARRAY);
        output.beginArray();
        return this;
    }

    /// Ends the array being written.
    ///
    /// @return this writer
    /// @throws IllegalStateException if no array is being written
    public JsonStreamWriter endArray() {
        checkState(depth > 0 && kinds[depth] == ARRAY, "no array can end here");
        pop();
        output.endArray();
        return this;
    }

    /// Writes a member name.
    ///
    /// @param name the name
    /// @return this writer
    /// @throws IllegalArgumentException if `name` has an unpaired surrogate
    /// @throws IllegalStateException if a member name cannot come here
    public JsonStreamWriter name(String name) {
        requireNonNull(name, "name");
        beforeName();
        output.name(name);
        return this;
    }

    /// Writes a known member name, already encoded.
    ///
    /// @param names the known names
    /// @param index the index of the name
    /// @return this writer
    /// @throws IndexOutOfBoundsException if `index` is not the index of a name
    /// @throws IllegalStateException if a member name cannot come here
    public JsonStreamWriter name(JsonNames names, int index) {
        requireNonNull(names, "names");
        checkIndex(index, names.size());
        beforeName();
        output.name(names.quoted(index));
        return this;
    }

    /// Writes a string.
    ///
    /// @param value the string
    /// @return this writer
    /// @throws IllegalArgumentException if `value` has an unpaired surrogate
    /// @throws IllegalStateException if a value cannot come here
    public JsonStreamWriter value(String value) {
        requireNonNull(value, "value");
        beforeValue();
        output.string(value);
        return afterScalar();
    }

    /// Writes an integer.
    ///
    /// @param value the integer
    /// @return this writer
    /// @throws IllegalStateException if a value cannot come here
    public JsonStreamWriter value(long value) {
        beforeValue();
        output.number(value);
        return afterScalar();
    }

    /// Writes a `double`, in its shortest form that reads back to the same value.
    ///
    /// @param value the number
    /// @return this writer
    /// @throws IllegalArgumentException if `value` is not finite: JSON has no `NaN` nor infinities
    /// @throws IllegalStateException if a value cannot come here
    public JsonStreamWriter value(double value) {
        checkArgument(Double.isFinite(value), "%s is not a JSON number", value);
        beforeValue();
        output.number(value);
        return afterScalar();
    }

    /// Writes a `float`, in its shortest form that reads back to the same value.
    ///
    /// @param value the number
    /// @return this writer
    /// @throws IllegalArgumentException if `value` is not finite: JSON has no `NaN` nor infinities
    /// @throws IllegalStateException if a value cannot come here
    public JsonStreamWriter value(float value) {
        checkArgument(Float.isFinite(value), "%s is not a JSON number", value);
        beforeValue();
        output.number(value);
        return afterScalar();
    }

    /// Writes an integer.
    ///
    /// @param value the integer
    /// @return this writer
    /// @throws IllegalStateException if a value cannot come here
    public JsonStreamWriter value(BigInteger value) {
        requireNonNull(value, "value");
        beforeValue();
        output.number(value.toString());
        return afterScalar();
    }

    /// Writes a decimal number.
    ///
    /// @param value the number
    /// @return this writer
    /// @throws IllegalStateException if a value cannot come here
    public JsonStreamWriter value(BigDecimal value) {
        requireNonNull(value, "value");
        beforeValue();
        output.number(value.toString());
        return afterScalar();
    }

    /// Writes a boolean.
    ///
    /// @param value the boolean
    /// @return this writer
    /// @throws IllegalStateException if a value cannot come here
    public JsonStreamWriter value(boolean value) {
        beforeValue();
        output.bool(value);
        return afterScalar();
    }

    /// Writes a value of any type.
    ///
    /// @param value the value
    /// @return this writer
    /// @throws IllegalArgumentException if a string of `value` has an unpaired surrogate
    /// @throws IllegalStateException if a value cannot come here
    public JsonStreamWriter value(JsonValue value) {
        requireNonNull(value, "value");
        beforeValue();
        TreeWriter.write(output, value);
        return afterScalar();
    }

    /// Writes `null`.
    ///
    /// @return this writer
    /// @throws IllegalStateException if a value cannot come here
    public JsonStreamWriter nullValue() {
        beforeValue();
        output.nullValue();
        return afterScalar();
    }

    /// Returns whether a record component that is `null` is written as `null`, rather than omitted, as the
    /// [JsonWriter] that created this writer says.
    ///
    /// @return whether `null` components are written
    public boolean writesNullMembers() {
        return writeNullMembers;
    }

    /// Returns the document written.
    ///
    /// @return the document, in UTF-8
    /// @throws IllegalStateException if the document is not complete
    public byte[] toByteArray() {
        checkState(complete, "the document is not complete");
        return output.toByteArray();
    }

    /// Writes the document written to a stream.
    ///
    /// @param out the stream, which is not closed
    /// @throws IOException if the stream cannot be written
    /// @throws IllegalStateException if the document is not complete
    public void writeTo(OutputStream out) throws IOException {
        requireNonNull(out, "out");
        checkState(complete, "the document is not complete");
        output.writeTo(out);
    }

    Output output() {
        return output;
    }

    private void beforeName() {
        checkState(depth > 0 && kinds[depth] == OBJECT && !afterName, "a member name cannot come here");
        afterName = true;
    }

    private void beforeValue() {
        if (depth == 0) {
            checkState(!complete, "the document already has its root value");
        } else if (kinds[depth] == OBJECT) {
            checkState(afterName, "a member name must come before a value in an object");
            afterName = false;
        }
    }

    private JsonStreamWriter afterScalar() {
        if (depth == 0) {
            complete = true;
        }
        return this;
    }

    private void push(byte kind) {
        depth++;
        if (depth == kinds.length) {
            kinds = Arrays.copyOf(kinds, depth * 2);
        }
        kinds[depth] = kind;
    }

    private void pop() {
        depth--;
        afterScalar();
    }
}
