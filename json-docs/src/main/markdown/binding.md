# Binding records

json binds documents to records, and records to documents, through code that an annotation processor generates at
compile time: no reflection, and a type that cannot be bound is a compile error rather than a surprise at run time.

```java
@JsonRecord
record Order(long id, Customer customer, List<Line> lines, Status status, @Nullable Instant shipped) {}
record Customer(String name, @JsonName("e-mail") String email) {}
record Line(String product, int quantity, BigDecimal price) {}
enum Status { NEW, @JsonName("in-progress") IN_PROGRESS, DONE }

Order order = new JsonParser().parseAs(Order.class, body);
String json = new JsonWriter().formatAs(Order.class, order);
```

The annotations are in `be.imgn.json.bind`.

## Setting up the processor

Add `json-processor` to the compiler's annotation processors:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <annotationProcessorPaths>
      <path>
        <groupId>be.imgn.json</groupId>
        <artifactId>json-processor</artifactId>
        <version>${json.version}</version>
      </path>
    </annotationProcessorPaths>
  </configuration>
</plugin>
```

For each type annotated [@JsonRecord](api:be.imgn.json.bind.JsonRecord), the processor generates a
[JsonBinder](api:be.imgn.json.spi.JsonBinder) beside it, such as `$Order$$JsonBinder` for `Order`, and registers it as
a service. On the module path, `ServiceLoader` only sees the services a module declares: the module of the records
must provide it, and the processor warns when it does not.

```java
module com.example.orders {
    requires be.imgn.json;
    provides be.imgn.json.spi.JsonBinder with com.example.orders.$Order$$JsonBinder;
}
```

The processor does not claim the annotations it processes: a build with `-Xlint:processing -Werror` fails on javac's
warning that no processor claimed them.

## Reading and writing records

Annotate the types you read or write: the records, sealed interfaces and enums they refer to are bound the same way,
without the annotation. `JsonParser.parseAs` reads a document as one of them, from the same inputs as `parse`, and
`JsonWriter.formatAs`, `toBytesAs` and `writeAs` write one.

```java
Order order = parser.parseAs(Order.class, body);
byte[] bytes = writer.toBytesAs(Order.class, order);
```

Binding reads straight from the [stream](streaming.md), after the whole document is validated: no tree is built.

## Writing records

Each component is a member, in the order of the record:

```json
{"id":42,"customer":{"name":"Ada","e-mail":"ada@example.com"},"lines":[{"product":"pen","quantity":2,"price":1.50}],"status":"in-progress"}
```

A component that is `null` is omitted, as `shipped` above, unless the writer is built with `writeNullMembers()`, which
writes it as `"shipped":null`. Reading binds both to `null`.

## Members and names

- A member is named after its component, unless [@JsonName](api:be.imgn.json.bind.JsonName) names it. `@JsonName` also
  names the constants of an enum, and the records of a sealed interface.
- A missing member is an error, unless the component is annotated `@Nullable`, any annotation of that simple name, which
  makes it `null`; so does a `null` member. A component of a primitive type cannot be `@Nullable`: use its boxed type.
- A member that no component takes is skipped, by default. A parser built with `UnknownMembers.REJECT` rejects it:

```java
JsonParser strict = JsonParser.builder().unknownMembers(UnknownMembers.REJECT).build();
strict.parseAs(Point.class, "{\"x\": 1, \"y\": 2, \"z\": 3}");
// JsonMappingException: line 1, column 18, at /z: unknown member "z", expected one of "x", "y"
```

## Types

| Java                                                          | JSON                                               |
|---------------------------------------------------------------|----------------------------------------------------|
| `String`                                                      | a string                                           |
| `long`, `int`, `short`, `byte` and their boxes                | a number whose value is an integer in their range, such as `12`, `1.0` or `1e2` |
| `double`, `float` and their boxes                             | a number, to its nearest value                     |
| `boolean`, `Boolean`                                          | a boolean                                          |
| `BigInteger`, `BigDecimal`                                    | a number, exactly                                  |
| `Instant`, `OffsetDateTime`, `LocalDate`, `LocalTime`, `LocalDateTime`, `Duration` | a string in ISO-8601          |
| `UUID`, `URI`                                                 | a string in their canonical form                   |
| an enum                                                       | a string naming a constant, or its `@JsonName`     |
| a record                                                      | an object                                          |
| a sealed interface of records                                 | an object naming the record: see [Sealed interfaces](#sealed-interfaces) |
| `List<T>`, `Set<T>`                                           | an array; a set rejects duplicates                 |
| `Map<String, T>`                                              | an object                                          |
| [JsonValue](api:be.imgn.json.JsonValue) and its subtypes      | the value, as it is                                |

Lists, sets and maps keep the order of the document, and cannot be modified. A `@Nullable JsonValue` component is
`null` for a missing member, and `JsonNull.INSTANCE` for a `null` one. The processor rejects any other type, and generic
records, at compile time.

## Sealed interfaces

A sealed interface of records is laid out as an object whose tag member names its record: `type`, by default, or the
name [@JsonTagMember](api:be.imgn.json.bind.JsonTagMember) gives. The tag member is written first, and read wherever
it is among the members.

```java
@JsonRecord
@JsonTagMember("kind")
sealed interface Shape permits Circle, Square {}

@JsonName("circle")
record Circle(double radius) implements Shape {}

@JsonName("square")
record Square(double side) implements Shape {}

writer.formatAs(Shape.class, new Circle(1));                     // {"kind":"circle","radius":1.0}
parser.parseAs(Shape.class, "{\"radius\": 2, \"kind\": \"circle\"}"); // Circle[radius=2.0]
```

[@JsonWrapper](api:be.imgn.json.bind.JsonWrapper) lays it out as an object with one member, named after the record,
instead:

```java
@JsonRecord
@JsonWrapper
sealed interface Event permits Created, Deleted {}

@JsonName("created")
record Created(long id) implements Event {}

writer.formatAs(Event.class, new Created(1));                    // {"created":{"id":1}}
```

The tag of a record is its `@JsonName`, or its simple name. A missing or unknown tag is an error:
`unknown type "triangle", expected one of "circle", "square"`.

## Validation

A record whose constructor throws is an error at the position of its object, with the exception as its cause, so that
a compact constructor validates what binding reads:

```java
@JsonRecord
record Range(int from, int to) {
    Range {
        if (from > to) {
            throw new IllegalArgumentException("from > to");
        }
    }
}

parser.parseAs(Range.class, "{\"from\": 2, \"to\": 1}");
// JsonMappingException: line 1, column 1: Range rejects the members: from > to
```

Every other mismatch is a [JsonMappingException](api:be.imgn.json.JsonMappingException) too, at the position and path
of the value: `line 2, column 43, at /lines/0/quantity: expected an int, found a string`. See
[Errors and limits](errors.md).

## JSON Lines

`parseLinesAs` and `formatLinesAs` bind each line of [JSON Lines](formats.md#json-lines) to a record.
