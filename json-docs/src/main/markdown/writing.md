# Writing

[JsonWriter](api:be.imgn.json.JsonWriter) writes a value as JSON, in UTF-8: to a `String`, to bytes, to an
`OutputStream`, or to one of gaia's `ByteSink`s.

```java
JsonWriter writer = new JsonWriter();
String text = writer.format(value);
byte[] bytes = writer.toBytes(value);
writer.write(value, out);                       // an OutputStream, which is not closed
writer.write(value, ByteSink.of(path));
```

## Compact or pretty

A writer is compact by default. `JsonWriter.pretty()` indents with 2 spaces, and puts a space after each colon;
`indent` sets another indentation:

```java
new JsonWriter().format(order);
// {"id":42,"tags":["new","priority"],"paid":true,"note":null}

JsonWriter.pretty().format(order);
// {
//   "id": 42,
//   "tags": [
//     "new",
//     "priority"
//   ],
//   "paid": true,
//   "note": null
// }

JsonWriter.builder().indent(4).build().format(order);
```

`toString()` on any value is the same as a compact writer's `format`, except for the `NaN` and infinities of
[JSON5](formats.md#json5): `toString()` writes them `NaN`, `Infinity` and `-Infinity`, where `format` rejects them.

## Strings and numbers

A string is written in UTF-8, with `"`, `\` and the control characters escaped, and nothing else. A string with an
unpaired surrogate, which UTF-8 cannot encode, is an `IllegalArgumentException`; the parser never creates one.

A number is written as it was parsed or created: `1.0e2` stays `1.0e2`, and `0.10` keeps its trailing zero. `NaN` and
the infinities, which [JSON5](formats.md#json5) can read, have no JSON form: writing one is an
`IllegalArgumentException`.

## Records

The writer also writes records, through the code the annotation processor generates: `formatAs`, `toBytesAs` and
`writeAs`. See [Binding records](binding.md#writing-records). A record component that is `null` is omitted, unless the
writer is built with `writeNullMembers()`.

## JSON Lines

`formatLines` and `writeLines` write values as [JSON Lines](formats.md#json-lines): each value compact, whatever the
indentation of the writer, followed by `\n`.

## Value by value

`streamWriter()` returns a [JsonStreamWriter](api:be.imgn.json.stream.JsonStreamWriter), compact or pretty as the
writer, to write a document without building its values first. See [Streaming](streaming.md#writing).
