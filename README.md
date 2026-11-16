# json

A fast, strict [RFC 8259](https://www.rfc-editor.org/rfc/rfc8259) JSON parser and writer for Java 25+, with no
dependency besides [gaia-core](https://github.com/imgn-dev/gaia).

- Validates a whole document before anything is read from it, and rejects everything that is not JSON: invalid UTF-8,
  unpaired surrogates, duplicate member names, a byte order mark.
- Reports each error with its line, its column and its path: `line 3, column 14, at /items/2: expected ',' or ']'
  after an element, found ':'`.
- Parses into a lazy, immutable tree: a sealed `JsonValue` hierarchy whose values are created on their first access,
  and whose numbers stay exact until they are converted.
- Reads and writes value by value with a public streaming API, on which the tree and binding rest.
- Binds documents to records, sealed interfaces and enums through code an annotation processor generates: no
  reflection.
- Writes compact or pretty JSON, in UTF-8.

API documentation: [snapshot](https://imgn-dev.github.io/json/api/snapshot/), or
[every published version](https://imgn-dev.github.io/json/api/). The design, its trade-offs and how it is tested are
in [docs/design.md](docs/design.md).

## Coordinates

```xml
<dependency>
  <groupId>be.imgn.json</groupId>
  <artifactId>json</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

The module is `be.imgn.json`. To bind records, see [Binding to records](#binding-to-records).

## Usage

```java
JsonDocument document = new JsonParser().parse(bytes); // also a String, a ByteSource or a CharSource
JsonObject order = (JsonObject) document.root();

long id = ((JsonNumber) order.get("id").orElseThrow()).asLong();
JsonPosition where = document.position(order, "id");

String compact = new JsonWriter().format(order);
byte[] pretty = JsonWriter.pretty().toBytes(order);
```

| JSON    | Value         | Java                                     |
|---------|---------------|------------------------------------------|
| object  | `JsonObject`  | `Map<String, JsonValue>`, in order       |
| array   | `JsonArray`   | `List<JsonValue>`                        |
| string  | `JsonString`  | `String`                                 |
| number  | `JsonNumber`  | `asInt()`, `asLong()`, `asDouble()`, `asBigDecimal()`… |
| boolean | `JsonBoolean` | `boolean`                                |
| `null`  | `JsonNull`    |                                          |

A number is kept as it is written: `asInt()`, `asLong()` and `asBigInteger()` are exact, and accept `1.0` or `1e2`;
`asDouble()` returns the nearest `double`. Numbers compare by value: `1`, `1.0` and `1e0` are equal.

A parsed object or array keeps its document in memory while it is referenced. `JsonObject.copyOf` and
`JsonArray.copyOf` make copies that do not.

### Streaming

```java
private static final JsonNames NAMES = JsonNames.of("id", "tags");

JsonStreamReader reader = new JsonParser().reader(bytes);
reader.beginObject();
while (reader.hasNextMember()) {
    switch (reader.name(NAMES)) {
        case 0 -> id = reader.readLong();
        case 1 -> tags = readTags(reader);
        default -> reader.skipValue();
    }
}

JsonStreamWriter writer = new JsonWriter().streamWriter();
writer.beginObject().name("id").value(42).name("tags").beginArray().value("a").endArray().endObject();
byte[] json = writer.toByteArray();
```

Skipping a value costs the same whatever its size. A value of the wrong type is a `JsonMappingException` at its
position; a call out of order, an `IllegalStateException`.

### Limits

```java
JsonParser parser = JsonParser.builder()
        .maxDepth(64)                   // default 1 000 nested objects and arrays
        .maxDocumentLength(1 << 20)     // default: none, besides that of an array
        .maxStringLength(10_000)        // default 20 000 000 bytes
        .maxNumberLength(100)           // default 1 000 bytes
        .unknownMembers(UnknownMembers.REJECT) // default IGNORE, for binding
        .build();
```

Parsers and writers are immutable and thread-safe, and hold nothing but their settings.

## Binding to records

Annotate the root type with `@JsonRecord`:

```java
@JsonRecord
record Order(long id, Customer customer, List<Line> lines, Status status, @Nullable Instant shipped) {}
record Customer(String name, @JsonName("e-mail") String email) {}
record Line(String product, int quantity, BigDecimal price) {}
enum Status { NEW, @JsonName("in-progress") IN_PROGRESS, DONE }

Order order = new JsonParser().parseAs(Order.class, body);
byte[] json = new JsonWriter().toBytesAs(Order.class, order);
```

A member is named after its component, unless `@JsonName` names it. A missing member is an error, unless the component
is `@Nullable` (any annotation of that simple name). A member that no component takes is skipped, or rejected with
`UnknownMembers.REJECT`. A `null` component is not written, unless the writer is built with `writeNullMembers()`.
`@JsonRecord` lists the types bound: primitives and their boxes, `String`, `BigInteger`, `BigDecimal`, enums, records,
sealed interfaces, `List`, `Set`, `Map<String, T>`, `JsonValue` and its subtypes, the `java.time` types, `UUID` and
`URI`. The processor rejects any other type at compile time.

A sealed interface of records is bound with a tag member, the default, or as a wrapper object:

```java
@JsonTagMember("kind")    // {"kind": "circle", "radius": 1}, whatever the position of "kind"
sealed interface Shape permits Circle, Square {}

@JsonWrapper              // {"created": {"id": 1}}
sealed interface Event permits Created, Deleted {}

@JsonName("circle")
record Circle(double radius) implements Shape {}
```

Add the processor to the compiler:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <annotationProcessorPaths combine.children="append">
      <path>
        <groupId>be.imgn.json</groupId>
        <artifactId>json-processor</artifactId>
        <version>0.1.0-SNAPSHOT</version>
      </path>
    </annotationProcessorPaths>
  </configuration>
</plugin>
```

The processor registers each binder as a service. On the module path, the module of the records must declare it, as
the processor reminds you: `provides be.imgn.json.JsonBinder with ...`.

The processor does not claim the annotations it processes, as Error Prone requires: a build with
`-Xlint:processing -Werror` fails on javac's warning that no processor claimed them.

## Benchmarks

JMH benchmarks against Jackson, Gson and fastjson2, on `twitter.json`, `citm_catalog.json`, `canada.json` and a 2 KB
payload:

```sh
./mvnw -pl json-benchmarks -am package exec:exec -DskipTests -Djmh.args="TreeBenchmark -prof gc"
```

`ValidateBenchmark` measures the validation alone, `TreeBenchmark` the tree, `WriteBenchmark` the writer.

## License

[Apache License 2.0](LICENSE).
