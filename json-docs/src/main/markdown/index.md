# json

json is a fast, strict [RFC 8259](https://www.rfc-editor.org/rfc/rfc8259) JSON parser and writer for Java 25 or later,
with no dependency besides [gaia-core](https://imgn-dev.github.io/gaia/). Every class is documented in the
[API documentation](https://imgn-dev.github.io/json/api/latest/).

- It validates a whole document before anything is read from it, and rejects everything that is not JSON. See
  [Errors and limits](errors.md).
- It parses into a lazy, immutable tree, whose numbers stay exact until they are converted. See [The tree](tree.md).
- It writes compact or pretty JSON, in UTF-8. See [Writing](writing.md).
- It reads and writes value by value, with a public streaming API. See [Streaming](streaming.md).
- It binds documents to records, through code an annotation processor generates: no reflection. See
  [Binding records](binding.md).
- It reads JSONC, JSON5 and JSON Lines too, each as strictly as JSON. See [Formats](formats.md).

## Artifacts

| Artifact         | Module                   | What it does                                                    |
|------------------|--------------------------|-----------------------------------------------------------------|
| `json`           | `be.imgn.json`           | [Parses](tree.md), [writes](writing.md), [streams](streaming.md) and [binds](binding.md) documents |
| `json-processor` | `be.imgn.json.processor` | [Generates the code that binds documents to records](binding.md#setting-up-the-processor) |

## Setting up

```xml
<dependency>
  <groupId>be.imgn.json</groupId>
  <artifactId>json</artifactId>
  <version>${json.version}</version>
</dependency>
```

On the module path, require the module: `requires be.imgn.json;`. It requires `be.imgn.gaia.core` transitively, as
gaia's sources and sinks are part of its API.

## A first document

```java
JsonDocument document = new JsonParser().parse("""
        {"id": 42, "tags": ["new", "priority"]}
        """);
JsonObject order = (JsonObject) document.root();
long id = ((JsonNumber) order.get("id").orElseThrow()).asLong(); // 42

String compact = new JsonWriter().format(order); // {"id":42,"tags":["new","priority"]}
```

[JsonParser](api:be.imgn.json.JsonParser) and [JsonWriter](api:be.imgn.json.JsonWriter) are immutable and
thread-safe, and hold nothing but their settings: keep one in a constant, or create one per call, which costs nothing.

## Packages

| Package               | What it holds                                                                     |
|-----------------------|-----------------------------------------------------------------------------------|
| `be.imgn.json`        | the parser and the writer, documents, the tree of values, the exceptions          |
| `be.imgn.json.stream` | the streaming reader and writer                                                   |
| `be.imgn.json.bind`   | the annotations of binding                                                        |
| `be.imgn.json.spi`    | what the code the processor generates calls: nothing to use directly              |

## Nulls

Every package of json is annotated with JSpecify's `@NullMarked`: a parameter or a return value is never null unless
it is annotated `@Nullable`. A method given null where it doesn't accept it throws `NullPointerException`, naming the
parameter.
