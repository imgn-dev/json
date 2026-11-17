# Streaming

The [tree](tree.md) and [binding](binding.md) both rest on a public streaming API, in `be.imgn.json.stream`, which reads
and writes a document value by value. Use it to read a few members of a large document without creating the others,
or to write a document without building it first.

## Reading

`JsonParser.reader` returns a [JsonStreamReader](api:be.imgn.json.stream.JsonStreamReader), at the root value. The
parser validates the whole document first, as it does for the tree, so reading never finds an invalid document.

```java
private static final JsonNames NAMES = JsonNames.of("id", "tags");

JsonStreamReader reader = new JsonParser().reader(bytes);
long id = 0;
List<String> tags = new ArrayList<>();
reader.beginObject();
while (reader.hasNextMember()) {
    switch (reader.name(NAMES)) {
        case 0 -> id = reader.readLong();
        case 1 -> {
            reader.beginArray();
            while (reader.hasNextElement()) {
                tags.add(reader.readString());
            }
        }
        default -> reader.skipValue();
    }
}
```

- `beginObject` and `hasNextMember` walk the members of an object, `beginArray` and `hasNextElement` the elements of an
  array; `hasNextMember` and `hasNextElement` read the end of their object or array, and return `false`.
- `name` matches a member name against [JsonNames](api:be.imgn.json.stream.JsonNames), known in advance, without
  creating a `String`, and returns its index, or -1. `readName` returns the name instead.
- `readString`, `readLong`, `readInt`, `readDouble`, `readBigDecimal`, `readBoolean`… read a value of their type. A
  number is read as an integer type when its value is an integer in its range, as in the tree.
- `peek` returns the type of the next value, and `readNull` reads it only if it is `null`.
- `readValue` reads any value as a [JsonValue](api:be.imgn.json.JsonValue); `skipValue` skips it, at a cost that does
  not depend on its size.

A value of another type than the one read is a [JsonMappingException](api:be.imgn.json.JsonMappingException), at
the position and path of the value: `line 1, column 8, at /id: expected a long, found a string`. A call out of order,
such as reading a value where the reader is at a member name, is an `IllegalStateException`.

A reader is not thread-safe.

## Writing

`JsonWriter.streamWriter` returns a [JsonStreamWriter](api:be.imgn.json.stream.JsonStreamWriter), compact or pretty as
the writer. Its methods chain:

```java
JsonStreamWriter writer = new JsonWriter().streamWriter();
writer.beginObject()
        .name("id").value(42)
        .name("total").value(new BigDecimal("19.90"))
        .name("tags").beginArray().value("a").endArray()
        .name("note").nullValue()
        .endObject();
byte[] json = writer.toByteArray(); // {"id":42,"total":19.90,"tags":["a"],"note":null}
```

`writeTo` writes the document to an `OutputStream` instead. `name(names, index)` writes one of the
[JsonNames](api:be.imgn.json.stream.JsonNames), already encoded, and `value(JsonValue)` a whole value of the tree.

The writer checks the grammar: a value where a member name is expected, a second root value, an end that does not match
its start, or a document taken before it is complete, is an `IllegalStateException`.

A writer is not thread-safe.
