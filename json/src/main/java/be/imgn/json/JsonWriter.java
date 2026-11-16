package be.imgn.json;

import static be.imgn.gaia.core.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import be.imgn.gaia.core.io.ByteSink;
import be.imgn.json.internal.Output;
import java.io.IOException;
import java.io.OutputStream;

/// Writes values as JSON, in UTF-8: compact, the default, or pretty, indented with 2 spaces.
///
/// ```java
/// String compact = new JsonWriter().format(value);  // {"id":42,"tags":["a"]}
/// String pretty = JsonWriter.pretty().format(value);
/// ```
///
/// Strings escape `"`, `\` and the control characters, and are written in UTF-8 otherwise; one with an unpaired
/// surrogate, which UTF-8 cannot encode, is an [IllegalArgumentException]. Numbers are written as they were parsed or
/// created.
///
/// A writer is immutable and thread-safe, and holds nothing but its settings: creating one per call costs nothing.
///
/// @since 0.1.0
public final class JsonWriter {

    private static final int PRETTY_INDENT = 2;

    private final int indent;
    private final boolean writeNullMembers;

    /// Creates a writer of compact JSON, which omits the `null` components of records.
    public JsonWriter() {
        this(new Builder());
    }

    private JsonWriter(Builder builder) {
        this.indent = builder.indent;
        this.writeNullMembers = builder.writeNullMembers;
    }

    /// Returns a writer of pretty JSON: indented with 2 spaces, with a space after each colon.
    ///
    /// @return the writer
    public static JsonWriter pretty() {
        return builder().pretty().build();
    }

    /// Returns a builder of a writer, with the default settings.
    ///
    /// @return a new builder
    public static Builder builder() {
        return new Builder();
    }

    /// Returns a value as JSON.
    ///
    /// @param value the value
    /// @return the JSON
    /// @throws IllegalArgumentException if a string of `value` has an unpaired surrogate
    public String format(JsonValue value) {
        return written(value, Output::toString);
    }

    /// Returns a value as JSON, in UTF-8.
    ///
    /// @param value the value
    /// @return the JSON
    /// @throws IllegalArgumentException if a string of `value` has an unpaired surrogate
    public byte[] toBytes(JsonValue value) {
        return written(value, Output::toByteArray);
    }

    /// Writes a value as JSON, in UTF-8, to a stream.
    ///
    /// @param value the value
    /// @param out the stream, which is not closed
    /// @throws IllegalArgumentException if a string of `value` has an unpaired surrogate
    /// @throws IOException if the stream cannot be written
    public void write(JsonValue value, OutputStream out) throws IOException {
        requireNonNull(out, "out");
        written(value, output -> output.writeTo(out));
    }

    /// Writes a value as JSON, in UTF-8, to a sink.
    ///
    /// @param value the value
    /// @param sink the sink
    /// @throws IllegalArgumentException if a string of `value` has an unpaired surrogate
    /// @throws IOException if the sink cannot be written
    public void write(JsonValue value, ByteSink sink) throws IOException {
        requireNonNull(sink, "sink");
        written(value, output -> writeTo(output, sink));
    }

    /// Returns a value of a [JsonRecord] type as JSON, through the code the annotation processor generated.
    ///
    /// @param <T> the type
    /// @param type the type, which may be a sealed interface of the record
    /// @param value the value
    /// @return the JSON
    /// @throws NullPointerException if a component of `value` that is not `@Nullable` is null
    /// @throws IllegalArgumentException if no binder is registered for the type (see [JsonRecord]), or a string of
    ///     `value` has an unpaired surrogate
    public <T> String formatAs(Class<T> type, T value) {
        return writtenAs(type, value, Output::toString);
    }

    /// Returns a value of a [JsonRecord] type as JSON, in UTF-8.
    ///
    /// @param <T> the type
    /// @param type the type, which may be a sealed interface of the record
    /// @param value the value
    /// @return the JSON
    /// @throws NullPointerException if a component of `value` that is not `@Nullable` is null
    /// @throws IllegalArgumentException if no binder is registered for the type (see [JsonRecord]), or a string of
    ///     `value` has an unpaired surrogate
    public <T> byte[] toBytesAs(Class<T> type, T value) {
        return writtenAs(type, value, Output::toByteArray);
    }

    /// Writes a value of a [JsonRecord] type as JSON, in UTF-8, to a stream.
    ///
    /// @param <T> the type
    /// @param type the type, which may be a sealed interface of the record
    /// @param value the value
    /// @param out the stream, which is not closed
    /// @throws NullPointerException if a component of `value` that is not `@Nullable` is null
    /// @throws IllegalArgumentException if no binder is registered for the type (see [JsonRecord]), or a string of
    ///     `value` has an unpaired surrogate
    /// @throws IOException if the stream cannot be written
    public <T> void writeAs(Class<T> type, T value, OutputStream out) throws IOException {
        requireNonNull(out, "out");
        writtenAs(type, value, output -> output.writeTo(out));
    }

    /// Writes a value of a [JsonRecord] type as JSON, in UTF-8, to a sink.
    ///
    /// @param <T> the type
    /// @param type the type, which may be a sealed interface of the record
    /// @param value the value
    /// @param sink the sink
    /// @throws NullPointerException if a component of `value` that is not `@Nullable` is null
    /// @throws IllegalArgumentException if no binder is registered for the type (see [JsonRecord]), or a string of
    ///     `value` has an unpaired surrogate
    /// @throws IOException if the sink cannot be written
    public <T> void writeAs(Class<T> type, T value, ByteSink sink) throws IOException {
        requireNonNull(sink, "sink");
        writtenAs(type, value, output -> writeTo(output, sink));
    }

    /// Writes a record to a pooled output, finishes with it, and gives its buffer back.
    private <T, R, X extends Exception> R writtenAs(Class<T> type, T value, Finisher<R, X> finisher) throws X {
        requireNonNull(type, "type");
        requireNonNull(value, "value");
        var output = Output.pooled(indent);
        try {
            Binders.write(type, new JsonStreamWriter(output, writeNullMembers), value);
            return finisher.finish(output);
        } finally {
            output.release();
        }
    }

    /// Returns a writer of a document, value by value, with the settings of this writer.
    ///
    /// @return a new stream writer
    public JsonStreamWriter streamWriter() {
        return new JsonStreamWriter(indent, writeNullMembers);
    }

    /// Writes a value to a pooled output, finishes with it, and gives its buffer back.
    private <R, X extends Exception> R written(JsonValue value, Finisher<R, X> finisher) throws X {
        requireNonNull(value, "value");
        var output = Output.pooled(indent);
        try {
            TreeWriter.write(output, value);
            return finisher.finish(output);
        } finally {
            output.release();
        }
    }

    private static int writeTo(Output output, ByteSink sink) throws IOException {
        try (var out = sink.openStream()) {
            return output.writeTo(out);
        }
    }

    /// What a writer does with an output once a value is written to it, before its buffer goes back to the pool.
    @FunctionalInterface
    private interface Finisher<R, X extends Exception> {
        R finish(Output output) throws X;
    }

    /// Builds a writer. Its defaults: compact, and `null` components of records omitted.
    ///
    /// @since 0.1.0
    public static final class Builder {

        private int indent;
        private boolean writeNullMembers;

        private Builder() {}

        /// Makes the writer pretty: indented with 2 spaces, with a space after each colon.
        ///
        /// @return this builder
        public Builder pretty() {
            return indent(PRETTY_INDENT);
        }

        /// Sets the indentation: pretty with a positive number of spaces, compact with 0.
        ///
        /// @param spaces the number of spaces per level
        /// @return this builder
        /// @throws IllegalArgumentException if `spaces` is negative
        public Builder indent(int spaces) {
            checkArgument(spaces >= 0, "the indentation %s is negative", spaces);
            this.indent = spaces;
            return this;
        }

        /// Makes the writer write a record component that is `null` as a member whose value is `null`, rather than
        /// omit it. Reading binds both to `null`.
        ///
        /// @return this builder
        public Builder writeNullMembers() {
            this.writeNullMembers = true;
            return this;
        }

        /// Builds the writer.
        ///
        /// @return the writer
        public JsonWriter build() {
            return new JsonWriter(this);
        }
    }
}
