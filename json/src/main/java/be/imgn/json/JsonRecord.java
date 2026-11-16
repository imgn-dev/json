package be.imgn.json;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.CLASS;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/// Marks a record, or a sealed interface of records, that documents bind to, with
/// [JsonParser#parseAs(Class, byte[])], and that [JsonWriter#formatAs(Class, Object)] writes.
///
/// ```java
/// @JsonRecord
/// record Order(long id, Customer customer, List<Line> lines, @Nullable Instant shipped) {}
/// record Customer(String name, @JsonName("e-mail") String email) {}
/// record Line(String product, int quantity, BigDecimal price) {}
///
/// Order order = new JsonParser().parseAs(Order.class, body);
/// ```
///
/// The annotation processor of the `be.imgn.json:json-processor` artifact generates the code that reads and writes the
/// type straight from and to the stream, without reflection, and registers it as a [JsonBinder] service; on the module
/// path, the module of the type must declare it: `provides be.imgn.json.JsonBinder with ...`. The processor rejects at
/// compile time a type it cannot bind. The records, sealed interfaces and enums the type refers to are bound the same
/// way, without the annotation.
///
/// Each component of a record is a member of an object, named as the component, or as its [JsonName]. A missing member
/// is an error, unless the component is annotated `@Nullable` (any annotation of that simple name), which makes it
/// `null`, as does a `null` member. A member that no component takes is skipped, or rejected with
/// [UnknownMembers#REJECT]. When written, a `null` component is omitted, unless the writer
/// [writes it][JsonWriter.Builder#writeNullMembers()].
///
/// The types bound, anything else being a compile error:
///
/// - `String`, from a string;
/// - `long`, `int`, `short`, `byte` and their boxes, from a number whose value is an integer in their range, such as
///   `12`, `1.0` or `1e2`;
/// - `double`, `float` and their boxes, from a number, to its nearest value;
/// - `boolean` and `Boolean`, from a boolean;
/// - `BigInteger` and `BigDecimal`, from a number, exactly;
/// - `Instant`, `OffsetDateTime`, `LocalDate`, `LocalTime`, `LocalDateTime` and `Duration`, from a string in ISO-8601;
/// - `UUID` and `URI`, from a string in their canonical form;
/// - an enum, from a string naming one of its constants, or its [JsonName];
/// - a record, from an object;
/// - a sealed interface of records, from an object naming the record, as [JsonTagMember] or [JsonWrapper] lay it out;
/// - `List<T>` and `Set<T>`, from an array of values that bind to `T`, a set rejecting duplicates;
/// - `Map<String, T>`, from an object of values that bind to `T`;
/// - [JsonValue] and its subtypes, as they are: a `@Nullable JsonValue` component is `null` for a missing member, and
///   [JsonNull#INSTANCE] for a `null` one.
///
/// Lists, sets and maps keep the order of the document, and cannot be modified. A record whose constructor throws is
/// an error at the position of its object, with the exception as its cause.
///
/// @since 0.1.0
@Documented
@Retention(CLASS)
@Target(TYPE)
public @interface JsonRecord {}
