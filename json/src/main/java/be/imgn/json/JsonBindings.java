package be.imgn.json;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/// Reads the values that the code generated for [JsonRecord] types binds from strings, and checks the type of a
/// [JsonValue]. Each method reads the next value, and throws a [JsonMappingException] at its position if it does not
/// bind.
///
/// @since 0.1.0
public final class JsonBindings {

    private static final Map<Class<?>, JsonType> VALUE_TYPES = Map.of(
            JsonObject.class, JsonType.OBJECT,
            JsonArray.class, JsonType.ARRAY,
            JsonString.class, JsonType.STRING,
            JsonNumber.class, JsonType.NUMBER,
            JsonBoolean.class, JsonType.BOOLEAN,
            JsonNull.class, JsonType.NULL);

    private JsonBindings() {}

    /// Reads an instant, in ISO-8601: `2026-11-16T10:15:30Z`.
    ///
    /// @param reader the reader
    /// @return the instant
    /// @throws JsonMappingException if the next value is not such a string
    public static Instant readInstant(JsonStreamReader reader) {
        return parse(reader, "an ISO-8601 instant", Instant::parse);
    }

    /// Reads a date-time with an offset, in ISO-8601: `2026-11-16T10:15:30+01:00`.
    ///
    /// @param reader the reader
    /// @return the date-time
    /// @throws JsonMappingException if the next value is not such a string
    public static OffsetDateTime readOffsetDateTime(JsonStreamReader reader) {
        return parse(reader, "an ISO-8601 date-time with an offset", OffsetDateTime::parse);
    }

    /// Reads a date, in ISO-8601: `2026-11-16`.
    ///
    /// @param reader the reader
    /// @return the date
    /// @throws JsonMappingException if the next value is not such a string
    public static LocalDate readLocalDate(JsonStreamReader reader) {
        return parse(reader, "an ISO-8601 date", LocalDate::parse);
    }

    /// Reads a time, in ISO-8601: `10:15:30`.
    ///
    /// @param reader the reader
    /// @return the time
    /// @throws JsonMappingException if the next value is not such a string
    public static LocalTime readLocalTime(JsonStreamReader reader) {
        return parse(reader, "an ISO-8601 time", LocalTime::parse);
    }

    /// Reads a date-time, in ISO-8601: `2026-11-16T10:15:30`.
    ///
    /// @param reader the reader
    /// @return the date-time
    /// @throws JsonMappingException if the next value is not such a string
    public static LocalDateTime readLocalDateTime(JsonStreamReader reader) {
        return parse(reader, "an ISO-8601 date-time", LocalDateTime::parse);
    }

    /// Reads a duration, in ISO-8601: `PT1M30S`.
    ///
    /// @param reader the reader
    /// @return the duration
    /// @throws JsonMappingException if the next value is not such a string
    public static Duration readDuration(JsonStreamReader reader) {
        return parse(reader, "an ISO-8601 duration", Duration::parse);
    }

    /// Reads a UUID, in its canonical form: 36 characters, 32 hexadecimal digits in groups of 8, 4, 4, 4 and 12.
    ///
    /// @param reader the reader
    /// @return the UUID
    /// @throws JsonMappingException if the next value is not such a string
    public static UUID readUuid(JsonStreamReader reader) {
        return parse(reader, "a UUID", text -> {
            var uuid = UUID.fromString(text);
            // fromString accepts shortened groups, as 1-2-3-4-5.
            if (!uuid.toString().equals(text.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("not the canonical form of a UUID");
            }
            return uuid;
        });
    }

    /// Reads a URI, as [URI#create(String)] parses it.
    ///
    /// @param reader the reader
    /// @return the URI
    /// @throws JsonMappingException if the next value is not such a string
    public static URI readUri(JsonStreamReader reader) {
        return parse(reader, "a URI", URI::create);
    }

    /// Reads a value of a [JsonValue] type.
    ///
    /// @param <T> the type
    /// @param reader the reader
    /// @param type the type: [JsonValue], or one of its subtypes
    /// @return the value
    /// @throws JsonMappingException if the next value is not of that type
    public static <T extends JsonValue> T readValue(JsonStreamReader reader, Class<T> type) {
        requireNonNull(type, "type");
        var mark = reader.mark();
        var value = reader.readValue();
        if (!type.isInstance(value)) {
            var expected = requireNonNull(VALUE_TYPES.get(type), type.getName()).description();
            throw reader.mappingError(
                    mark, "expected " + expected + ", found " + value.type().description());
        }
        return type.cast(value);
    }

    private static <T> T parse(JsonStreamReader reader, String expected, Function<String, T> parser) {
        var mark = reader.mark();
        var text = reader.readString();
        try {
            return parser.apply(text);
        } catch (DateTimeException | IllegalArgumentException e) {
            throw reader.mappingError(mark, "expected " + expected + ", found \"" + text + "\"", e);
        }
    }
}
