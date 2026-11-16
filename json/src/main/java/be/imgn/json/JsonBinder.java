package be.imgn.json;

/// Reads a type from a stream and writes it to one. The annotation processor generates one for each [JsonRecord]
/// type, and registers it as a service, which [JsonParser#parseAs(Class, byte[])] and
/// [JsonWriter#formatAs(Class, Object)] load with [java.util.ServiceLoader].
///
/// @param <T> the type
/// @since 0.1.0
public interface JsonBinder<T> {

    /// Returns the type.
    ///
    /// @return the type
    Class<T> type();

    /// Reads a value of the type.
    ///
    /// @param reader the reader, at the value
    /// @return the value
    /// @throws JsonMappingException if the value does not bind to the type
    T read(JsonStreamReader reader);

    /// Writes a value of the type.
    ///
    /// @param writer the writer, where a value can come
    /// @param value the value
    /// @throws NullPointerException if a component of `value` that is not `@Nullable` is null
    /// @throws IllegalArgumentException if a string of `value` has an unpaired surrogate
    void write(JsonStreamWriter writer, T value);
}
