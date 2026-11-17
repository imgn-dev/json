# Formats

Besides JSON, the parser reads [JSONC](https://jsonc.org) and [JSON5](https://spec.json5.org), and both the parser and
the writer handle [JSON Lines](https://jsonlines.org).

## JSONC and JSON5

A parser reads one [JsonFormat](api:be.imgn.json.JsonFormat), chosen when it is built:

```java
JsonParser jsonc = JsonParser.builder().format(JsonFormat.JSONC).build();
JsonParser json5 = JsonParser.builder().format(JsonFormat.JSON5).build();
```

Each format is read as strictly as JSON: it is a choice of syntax, not a set of leniencies, and a document is rejected
as soon as it steps outside its format. Each format accepts everything the previous one does. Whatever the format,
duplicate member names, unpaired surrogates and invalid UTF-8 are rejected, the [limits](errors.md#limits) apply, and
the document is read as JSON, which the [writer](writing.md) writes back.

An error that another format would accept says so:

```
line 1, column 9, at /a: expected ',' or '}' after a member, found '/': comments are not allowed in JSON
(JsonFormat.JSONC allows them)
```

### JSONC

JSONC is JSON with `//` and `/* */` comments wherever JSON has white space. A line comment ends with a line feed or a
carriage return. Trailing commas, which JSONC lets a parser accept, are rejected, as by its reference parser.

```java
jsonc.parse("""
        {
          // the port to listen on
          "port": 8080 /* the default */
        }
        """);
```

### JSON5

JSON5 adds to JSONC: trailing commas; names written as ECMAScript 5.1 identifiers; strings in single quotes, with more
escape sequences and lines continued by a backslash; hexadecimal numbers, numbers with a leading or trailing decimal
point or a plus sign, `NaN` and the infinities; and more white space, a leading byte order mark included.

```java
JsonDocument settings = json5.parse("{name: 'app', retries: 3, timeout: Infinity, mask: 0xFF,}");
```

`NaN`, `Infinity` and `-Infinity` have no JSON form. `asDouble()` and `asFloat()` return them; the exact conversions,
such as `asInt()`, throw an `ArithmeticException`; and writing them as JSON is an `IllegalArgumentException`,
while `toString()` writes them `NaN`, `Infinity` and `-Infinity`, so that it never throws on them. A hexadecimal
number reads as its value: `0xFF` is 255.

## JSON Lines

JSON Lines are one JSON value per line, each a document of its own. `parseLines` returns them as a `Stream`, parsed as
it is consumed, and `parseLinesAs` binds each line to a [record](binding.md):

```java
try (Stream<JsonDocument> lines = new JsonParser().parseLines(ByteSource.of(path))) {
    lines.forEach(line -> handle(line.root()));
}

try (Stream<Event> events = new JsonParser().parseLinesAs(Event.class, ByteSource.of(path))) {
    events.forEach(this::handle);
}
```

- Lines end with `\n`, the last one optionally; the `\r` of a `\r\n` is white space.
- A blank line, or a value that does not end on its line, is an error, thrown when the stream reaches that line:
  `line 2, column 1: expected a value, found the end of the line`.
- Positions are those in the whole input, and the limits apply to each line.
- From a `ByteSource`, the source is read as the stream is consumed, and stays open until the stream is closed: close
  it, with a `try`-with-resources statement. An error reading it is an `UncheckedIOException`, thrown by the stream.
- JSON Lines are JSON only: a parser built for JSONC or JSON5 throws an `IllegalStateException`.

The writer writes each value compact, whatever its indentation, followed by `\n`, the last one included:

```java
String lines = new JsonWriter().formatLines(values);
new JsonWriter().writeLinesAs(Event.class, events, out); // written as they come, not held in memory
```
