package be.imgn.json;

import static be.imgn.gaia.core.base.Preconditions.checkArgument;
import static be.imgn.gaia.core.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import be.imgn.gaia.core.io.ByteSource;
import be.imgn.gaia.core.io.CharSource;
import be.imgn.json.bind.UnknownMembers;
import be.imgn.json.internal.Access;
import be.imgn.json.internal.Json5;
import be.imgn.json.internal.Jsonc;
import be.imgn.json.internal.Limits;
import be.imgn.json.internal.Lines;
import be.imgn.json.internal.Locator;
import be.imgn.json.internal.Syntax;
import be.imgn.json.internal.Tape;
import be.imgn.json.internal.TapeBuilder;
import be.imgn.json.internal.TapeException;
import be.imgn.json.stream.JsonStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/// Parses [RFC 8259](https://www.rfc-editor.org/rfc/rfc8259) JSON strictly: a document that is not valid JSON, has an
/// object with two members of the same name, or exceeds a limit, is rejected with a [JsonParseException]. Set to
/// another [JsonFormat], it parses JSONC or JSON5 as strictly.
///
/// ```java
/// JsonDocument document = new JsonParser().parse(bytes);
/// JsonParser strict = JsonParser.builder().maxDepth(64).unknownMembers(UnknownMembers.REJECT).build();
/// ```
///
/// A document is validated whole before anything is read from it. Everything is parsed from UTF-8 bytes: a `String`
/// is encoded first. A byte order mark is rejected.
///
/// A parser is immutable and thread-safe, and holds nothing but its settings: creating one per call costs nothing.
///
/// @since 0.1.0
public final class JsonParser {

    private static final int MAX_ARRAY = Integer.MAX_VALUE - 8;

    static {
        Access.register(new TreeAccess());
    }

    private final Limits limits;
    private final JsonFormat format;
    private final UnknownMembers unknownMembers;

    /// Creates a parser with the default settings: see [Builder].
    public JsonParser() {
        this(new Builder());
    }

    private JsonParser(Builder builder) {
        this.limits = new Limits(
                builder.maxDepth, builder.maxDocumentLength, builder.maxStringLength, builder.maxNumberLength);
        this.format = builder.format;
        this.unknownMembers = builder.unknownMembers;
    }

    /// Returns a builder of a parser, with the default settings.
    ///
    /// @return a new builder
    public static Builder builder() {
        return new Builder();
    }

    /// Parses a document.
    ///
    /// @param input the document, in UTF-8; it is not copied, and must not change while the document is used
    /// @return the document
    /// @throws JsonParseException if the document is not valid JSON, or exceeds a limit
    public JsonDocument parse(byte[] input) {
        return new JsonDocument(tape(input));
    }

    /// Parses a document.
    ///
    /// @param input the document
    /// @return the document
    /// @throws JsonParseException if the document is not valid JSON, has an unpaired surrogate, or exceeds a limit
    public JsonDocument parse(String input) {
        return parse(utf8(input));
    }

    /// Parses a document, read whole, up to the maximum length of a document.
    ///
    /// @param source the document, in UTF-8
    /// @return the document
    /// @throws JsonParseException if the document is not valid JSON, or exceeds a limit
    /// @throws IOException if the source cannot be read
    public JsonDocument parse(ByteSource source) throws IOException {
        return parse(read(source));
    }

    /// Parses a document, read whole.
    ///
    /// @param source the document
    /// @return the document
    /// @throws JsonParseException if the document is not valid JSON, has an unpaired surrogate, or exceeds a limit
    /// @throws IOException if the source cannot be read
    public JsonDocument parse(CharSource source) throws IOException {
        return parse(requireNonNull(source, "source").read());
    }

    /// Returns a reader of a document, which is validated whole first.
    ///
    /// @param input the document, in UTF-8; it is not copied, and must not change while the reader is used
    /// @return the reader, at the root value
    /// @throws JsonParseException if the document is not valid JSON, or exceeds a limit
    public JsonStreamReader reader(byte[] input) {
        return Access.streams().reader(tape(input), unknownMembers);
    }

    /// Returns a reader of a document, which is validated whole first.
    ///
    /// @param input the document
    /// @return the reader, at the root value
    /// @throws JsonParseException if the document is not valid JSON, has an unpaired surrogate, or exceeds a limit
    public JsonStreamReader reader(String input) {
        return reader(utf8(input));
    }

    /// Parses a document and binds it to a [JsonRecord][be.imgn.json.bind.JsonRecord] type, through the code
    /// the annotation processor generated.
    ///
    /// @param <T> the type
    /// @param type the type
    /// @param input the document, in UTF-8
    /// @return the value
    /// @throws JsonParseException if the document is not valid JSON, or exceeds a limit
    /// @throws JsonMappingException if the document does not bind to the type
    /// @throws IllegalArgumentException if no binder is registered for the type: see
    ///     [JsonRecord][be.imgn.json.bind.JsonRecord]
    public <T> T parseAs(Class<T> type, byte[] input) {
        requireNonNull(type, "type");
        return Binders.read(type, reader(input));
    }

    /// Parses a document and binds it to a [JsonRecord][be.imgn.json.bind.JsonRecord] type, through the code
    /// the annotation processor generated.
    ///
    /// @param <T> the type
    /// @param type the type
    /// @param input the document
    /// @return the value
    /// @throws JsonParseException if the document is not valid JSON, has an unpaired surrogate, or exceeds a limit
    /// @throws JsonMappingException if the document does not bind to the type
    /// @throws IllegalArgumentException if no binder is registered for the type: see
    ///     [JsonRecord][be.imgn.json.bind.JsonRecord]
    public <T> T parseAs(Class<T> type, String input) {
        return parseAs(type, utf8(input));
    }

    /// Parses a document, read whole, and binds it to a [JsonRecord][be.imgn.json.bind.JsonRecord] type.
    ///
    /// @param <T> the type
    /// @param type the type
    /// @param source the document, in UTF-8
    /// @return the value
    /// @throws JsonParseException if the document is not valid JSON, or exceeds a limit
    /// @throws JsonMappingException if the document does not bind to the type
    /// @throws IllegalArgumentException if no binder is registered for the type: see
    ///     [JsonRecord][be.imgn.json.bind.JsonRecord]
    /// @throws IOException if the source cannot be read
    public <T> T parseAs(Class<T> type, ByteSource source) throws IOException {
        return parseAs(type, read(source));
    }

    /// Parses a document, read whole, and binds it to a [JsonRecord][be.imgn.json.bind.JsonRecord] type.
    ///
    /// @param <T> the type
    /// @param type the type
    /// @param source the document
    /// @return the value
    /// @throws JsonParseException if the document is not valid JSON, has an unpaired surrogate, or exceeds a limit
    /// @throws JsonMappingException if the document does not bind to the type
    /// @throws IllegalArgumentException if no binder is registered for the type: see
    ///     [JsonRecord][be.imgn.json.bind.JsonRecord]
    /// @throws IOException if the source cannot be read
    public <T> T parseAs(Class<T> type, CharSource source) throws IOException {
        return parseAs(type, requireNonNull(source, "source").read());
    }

    /// Parses [JSON Lines](https://jsonlines.org): one JSON value per line, each a document of its own.
    ///
    /// Lines end with `\n`, the last one optionally; the `\r` of a `\r\n` is whitespace. A blank line, or a value
    /// that does not end on its line, is an error, thrown when the stream reaches that line. The limits apply to
    /// each line, and positions are those in the whole input.
    ///
    /// @param input the lines, in UTF-8; it is not copied, and must not change while the documents are used
    /// @return the documents, one per line, parsed as the stream is consumed
    /// @throws IllegalStateException if this parser reads JSONC or JSON5: JSON Lines are JSON only
    public Stream<JsonDocument> parseLines(byte[] input) {
        requireNonNull(input, "input");
        checkLines();
        return lines(Lines.of(input, limits), JsonDocument::new);
    }

    /// Parses [JSON Lines](https://jsonlines.org), as [#parseLines(byte\[\])] does.
    ///
    /// @param input the lines
    /// @return the documents, one per line, parsed as the stream is consumed
    /// @throws JsonParseException if the input has an unpaired surrogate
    /// @throws IllegalStateException if this parser reads JSONC or JSON5: JSON Lines are JSON only
    public Stream<JsonDocument> parseLines(String input) {
        checkLines();
        return parseLines(utf8(input));
    }

    /// Parses [JSON Lines](https://jsonlines.org), as [#parseLines(byte\[\])] does, read as the stream is consumed.
    ///
    /// The source stays open until the stream is closed: close it, with a `try`-with-resources statement. An error
    /// reading the source is an [UncheckedIOException], thrown by the stream.
    ///
    /// @param source the lines, in UTF-8
    /// @return the documents, one per line, parsed as the stream is consumed
    /// @throws IllegalStateException if this parser reads JSONC or JSON5: JSON Lines are JSON only
    /// @throws IOException if the source cannot be opened
    public Stream<JsonDocument> parseLines(ByteSource source) throws IOException {
        requireNonNull(source, "source");
        checkLines();
        var in = source.openStream();
        return lines(Lines.of(in, limits), JsonDocument::new).onClose(() -> close(in));
    }

    /// Parses [JSON Lines](https://jsonlines.org), as [#parseLines(byte\[\])] does, and binds each line to a
    /// [JsonRecord][be.imgn.json.bind.JsonRecord] type.
    ///
    /// @param <T> the type
    /// @param type the type
    /// @param input the lines, in UTF-8; it is not copied, and must not change while the values are used
    /// @return the values, one per line, parsed and bound as the stream is consumed: a line that does not bind is
    ///     a [JsonMappingException], thrown by the stream
    /// @throws IllegalStateException if this parser reads JSONC or JSON5: JSON Lines are JSON only
    public <T> Stream<T> parseLinesAs(Class<T> type, byte[] input) {
        requireNonNull(type, "type");
        requireNonNull(input, "input");
        checkLines();
        return lines(Lines.of(input, limits), tape -> bind(type, tape));
    }

    /// Parses [JSON Lines](https://jsonlines.org), as [#parseLines(byte\[\])] does, and binds each line to a
    /// [JsonRecord][be.imgn.json.bind.JsonRecord] type.
    ///
    /// @param <T> the type
    /// @param type the type
    /// @param input the lines
    /// @return the values, one per line, parsed and bound as the stream is consumed: a line that does not bind is
    ///     a [JsonMappingException], thrown by the stream
    /// @throws JsonParseException if the input has an unpaired surrogate
    /// @throws IllegalStateException if this parser reads JSONC or JSON5: JSON Lines are JSON only
    public <T> Stream<T> parseLinesAs(Class<T> type, String input) {
        requireNonNull(type, "type");
        checkLines();
        return parseLinesAs(type, utf8(input));
    }

    /// Parses [JSON Lines](https://jsonlines.org), as [#parseLines(ByteSource)] does, and binds each line to a
    /// [JsonRecord][be.imgn.json.bind.JsonRecord] type.
    ///
    /// @param <T> the type
    /// @param type the type
    /// @param source the lines, in UTF-8
    /// @return the values, one per line, parsed and bound as the stream is consumed: a line that does not bind is
    ///     a [JsonMappingException], thrown by the stream; close it, as it holds the source open
    /// @throws IllegalStateException if this parser reads JSONC or JSON5: JSON Lines are JSON only
    /// @throws IOException if the source cannot be opened
    public <T> Stream<T> parseLinesAs(Class<T> type, ByteSource source) throws IOException {
        requireNonNull(type, "type");
        requireNonNull(source, "source");
        checkLines();
        var in = source.openStream();
        return lines(Lines.of(in, limits), tape -> bind(type, tape)).onClose(() -> close(in));
    }

    private void checkLines() {
        checkState(format == JsonFormat.JSON, "JSON Lines are JSON only, and this parser reads %s", format);
    }

    private <T> T bind(Class<T> type, Tape tape) {
        return Binders.read(type, Access.streams().reader(tape, unknownMembers));
    }

    /// Returns the values of lines, as a stream that turns the errors of the tape builder into parse errors.
    private static <T> Stream<T> lines(Lines lines, Function<Tape, T> value) {
        var values = new Iterator<T>() {
            @Override
            public boolean hasNext() {
                try {
                    return lines.hasNext();
                } catch (TapeException e) {
                    throw new JsonParseException(e.description(), e.position(), e.path());
                }
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return value.apply(lines.next());
            }
        };
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(values, Spliterator.ORDERED | Spliterator.NONNULL), false);
    }

    private static void close(InputStream in) {
        try {
            in.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Tape tape(byte[] input) {
        requireNonNull(input, "input");
        try {
            return switch (format) {
                case JSON -> TapeBuilder.build(input, 0, input.length, limits, Syntax.JSON, Locator.of(input));
                case JSONC -> Jsonc.build(input, limits);
                case JSON5 -> Json5.build(input, limits);
            };
        } catch (TapeException e) {
            throw new JsonParseException(e.description(), e.position(), e.path());
        }
    }

    byte[] read(ByteSource source) throws IOException {
        requireNonNull(source, "source");
        try (var in = source.openStream()) {
            // One byte more than the maximum, for the tape builder to report the document as too long.
            var max = limits.maxDocumentLength();
            return in.readNBytes(max >= MAX_ARRAY ? MAX_ARRAY : max + 1);
        }
    }

    /// Encodes a document to UTF-8, rejecting an unpaired surrogate, which [String#getBytes] would replace silently.
    static byte[] utf8(String input) {
        requireNonNull(input, "input");
        var line = 1;
        var column = 1;
        var length = input.length();
        var i = 0;
        while (i < length) {
            var c = input.charAt(i++);
            if (c == '\n') {
                line++;
                column = 1;
            } else if (!Character.isSurrogate(c)) {
                column++;
            } else if (Character.isHighSurrogate(c) && i < length && Character.isLowSurrogate(input.charAt(i))) {
                i++;
                column += 2;
            } else {
                throw new JsonParseException(
                        "unpaired surrogate U+%04X".formatted((int) c), new JsonPosition(line, column), "");
            }
        }
        return input.getBytes(UTF_8);
    }

    /// Builds a parser. Its defaults: JSON, a depth of 1 000 nested objects and arrays, no limit on the length of a
    /// document besides that of an array, strings of up to 20 000 000 bytes, numbers of up to 1 000 bytes, and
    /// unknown members ignored by binding.
    ///
    /// @since 0.1.0
    public static final class Builder {

        private int maxDepth = Limits.DEFAULTS.maxDepth();
        private int maxDocumentLength = Limits.DEFAULTS.maxDocumentLength();
        private int maxStringLength = Limits.DEFAULTS.maxStringLength();
        private int maxNumberLength = Limits.DEFAULTS.maxNumberLength();
        private JsonFormat format = JsonFormat.JSON;
        private UnknownMembers unknownMembers = UnknownMembers.IGNORE;

        private Builder() {}

        /// Sets the syntax of the documents.
        ///
        /// @param format [JsonFormat#JSON], [JsonFormat#JSONC] or [JsonFormat#JSON5]
        /// @return this builder
        public Builder format(JsonFormat format) {
            this.format = requireNonNull(format, "format");
            return this;
        }

        /// Sets the maximum number of nested objects and arrays. Binding recurses with the document, so this limit
        /// also bounds the stack it uses.
        ///
        /// @param maxDepth the maximum depth
        /// @return this builder
        /// @throws IllegalArgumentException if `maxDepth` is less than 1
        public Builder maxDepth(int maxDepth) {
            checkArgument(maxDepth >= 1, "the maximum depth %s is less than 1", maxDepth);
            this.maxDepth = maxDepth;
            return this;
        }

        /// Sets the maximum length of a document, in bytes.
        ///
        /// @param maxDocumentLength the maximum length
        /// @return this builder
        /// @throws IllegalArgumentException if `maxDocumentLength` is negative
        public Builder maxDocumentLength(int maxDocumentLength) {
            checkArgument(maxDocumentLength >= 0, "the maximum document length %s is negative", maxDocumentLength);
            this.maxDocumentLength = maxDocumentLength;
            return this;
        }

        /// Sets the maximum length of a string or a member name, in bytes, as written between its quotes.
        ///
        /// @param maxStringLength the maximum length
        /// @return this builder
        /// @throws IllegalArgumentException if `maxStringLength` is negative
        public Builder maxStringLength(int maxStringLength) {
            checkArgument(maxStringLength >= 0, "the maximum string length %s is negative", maxStringLength);
            this.maxStringLength = maxStringLength;
            return this;
        }

        /// Sets the maximum length of a number, in bytes, as written. Converting a long number to a `BigDecimal` or a
        /// `BigInteger` takes more than linear time.
        ///
        /// @param maxNumberLength the maximum length
        /// @return this builder
        /// @throws IllegalArgumentException if `maxNumberLength` is less than 1
        public Builder maxNumberLength(int maxNumberLength) {
            checkArgument(maxNumberLength >= 1, "the maximum number length %s is less than 1", maxNumberLength);
            this.maxNumberLength = maxNumberLength;
            return this;
        }

        /// Sets what binding does with a member that no component of a record takes.
        ///
        /// @param unknownMembers [UnknownMembers#IGNORE] or [UnknownMembers#REJECT]
        /// @return this builder
        public Builder unknownMembers(UnknownMembers unknownMembers) {
            this.unknownMembers = requireNonNull(unknownMembers, "unknownMembers");
            return this;
        }

        /// Builds the parser.
        ///
        /// @return the parser
        public JsonParser build() {
            return new JsonParser(this);
        }
    }
}
