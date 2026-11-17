# Design

A strict RFC 8259 JSON parser and writer for data exchange and APIs, in Java 25, where speed comes first.

## Goals

- Throughput and low allocation on API-sized payloads (1–100 KB) and on large documents.
- Strict RFC 8259: everything that is not valid JSON is rejected, with an explicit error.
- Three ways to read: an immutable tree, a public streaming cursor, and binding to records through code an annotation
  processor generates.
- A writer with compact and pretty output.

Also read: JSONC, JSON5 and JSON Lines, each strictly (see [Formats](#formats)).

Not included: the Vector API (planned, see [Vector API](#vector-api)), JSON Pointer, Patch, Path or Schema.

## Modules

| Module            | Contents                                                                    | Published |
|-------------------|-----------------------------------------------------------------------------|-----------|
| `json`            | `be.imgn.json` (exported), `be.imgn.json.internal` (not exported: the core) | yes       |
| `json-processor`  | the annotation processor, on gaia-processing                                | yes       |
| `json-benchmarks` | JMH benchmarks                                                              | no        |

`json` depends on gaia-core only.

## Architecture

```
 byte[] (UTF-8)
     │
     ▼
┌──────────────────────────┐   be.imgn.json.internal: private, trusted, no allocation per token
│ TapeBuilder (stage 1)    │   one pass: validates everything, writes the tape
└──────────────────────────┘
     │ Tape: long[] + the input
     ▼
┌──────────────────────────┐   be.imgn.json: public
│ JsonStreamReader         │   cursor over the tape: typed reads, O(1) skip, lazy decoding
├──────────────────────────┤
│ JsonDocument / JsonValue │   tree built from the tape
│ generated binders        │   records read through JsonStreamReader
└──────────────────────────┘
```

The tape is the contract between the private core and everything above it. How it is built (SWAR now, the Vector API
later) can change without touching the public API.

### Stage 1: the tape builder

One pass over the bytes, single-threaded. It does all the validation, so that once it returns, the document is known to
be valid JSON:

- the grammar, iteratively, with a depth stack (no recursion, so no `StackOverflowError` whatever the depth);
- strings: UTF-8 (overlong forms, surrogates and code points above U+10FFFF rejected), escapes, unescaped control
  characters, and `\u` escapes that leave an unpaired surrogate;
- numbers: the RFC 8259 grammar (no leading zeros, digits after `.` and in the exponent);
- duplicate member names (see below);
- the limits of the parser.

Speed:

- Strings are scanned 8 bytes at a time (SWAR on a `long` read through a `VarHandle`): one test finds the first byte
  that is `"`, `\`, below U+0020 or non-ASCII. Pure ASCII runs cost about one operation per byte.
- Non-ASCII characters are validated in runs, without a word scan between them; a 2- or 3-byte sequence whose second
  byte only needs to be a continuation byte is checked whole, in one 4-byte read.
- Runs of spaces are skipped 8 bytes at a time, the count of the last word's spaces taken from its trailing zero bits.
- Literals (`true`, `false`, `null`) are compared as one `int`.
- Nothing is allocated per token: the tape and the stacks are primitive arrays that grow by doubling.
- Measured and dropped: remembering the member names of the objects validated earlier, so that an object with the same
  names in the same order would need no hashing. It paid on nothing the benchmarks parse.

No parsing on another thread: a handoff between threads costs more than parsing a typical payload, and a server has no
idle core to give. Pipelining a huge document chunk by chunk is the only case where it could help, and it is not
planned.

#### Member names

Stage 1 numbers each distinct member name, and the tape holds the number of each name in place of its length:

- names are compared as their decoded UTF-8 bytes: a name without escapes is its raw bytes, a name with escapes is
  decoded first, so that a name written with escape sequences has the number of the same name written without;
- the names seen by earlier documents are shared, as Jackson's canonical names are: an immutable snapshot numbers
  them, with their decoded `String`, and a document numbers its new names after them. Once parsed, a document's new
  names join a new snapshot, published atomically, up to 8 192 names and 256 KB, so that documents of random names
  cannot make it grow. Snapshots only append: a number keeps its name in every later snapshot;
- the name that followed each name last time, and the first name of the last object at each depth, predict the next
  one: in an array of similar objects, the names come in the same order, and one comparison of bytes replaces a hash;
- a name of the tree is a `String` of the table, decoded once for all the objects of all the documents;
- duplicates are found by comparing numbers: with the previous names of the object up to 8 members, through an
  open-addressing table of numbers above;
- the stacks and tables of stage 1 come from a small pool, one slot per thread modulo its size, and go back to it,
  so that a small document does not allocate them;
- a document may have up to 2²⁴−2 distinct names.

### The tape

One `long` per token:

```
 63      60 59     56 55                              24 23                 0
┌─────────┬─────────┬──────────────────────────────────┬────────────────────┐
│ tag (4) │flags (4)│ offset in the input (32 unsigned)│ length/number (24) │  strings, numbers; names
└─────────┴─────────┴──────────────────────────────────┴────────────────────┘
┌─────────┬─────────┬──────────────────────────────────┬────────────────────┐
│ tag (4) │flags (4)│ index of the matching end/start  │ count (24)         │  object and array starts/ends
└─────────┴─────────┴──────────────────────────────────┴────────────────────┘
```

- Tags: object start/end, array start/end, name, string, number, true, false, null.
- A container's start holds the index of its end: skipping a value is a jump. It holds the count of its members or
  elements too, so lists and arrays are allocated at their final size. A count of 2²⁴−1 or more is saturated.
- A string or number of 2²⁴−1 bytes or more stores that length as a marker, and its length is computed again from the
  document. A name holds its number: its length is that of the name of that number.
- String flags: has escapes, has non-ASCII bytes. Number flags: integral (no `.` or exponent). Number flag: not finite
  (`NaN` and the infinities of JSON5).
- Every value keeps its offset, so positions and error locations cost nothing at parse time: line and column are
  computed from the input only when asked for.

Strings and numbers are not decoded in stage 1: they are decoded when they are read, from their flags:

- ASCII without escapes: `new String(bytes, offset, length, ISO_8859_1)`, a copy into a compact string;
- non-ASCII without escapes: `new String(..., UTF_8)`, safe because stage 1 validated the bytes;
- with escapes: a custom decoder into a reused `char[]`.

Memory: about 8 bytes per token, often as much as the input or more, and the input must be in memory. Every input ends up
as a `byte[]` anyway (see [Inputs](#inputs)).

### Errors

Syntax and limit errors come from stage 1; type errors come from the cursor and the binders. Both have the position of
the error and its path as a JSON Pointer:

```
line 3, column 14, at /items/2: expected ',' or ']' after an element, found ':'
line 1, column 9, at /id: expected a long, found a string
line 7, column 3, at /user: missing member "name" of User
```

- `JsonException` (sealed, unchecked) has `position()` and `path()`; its subclasses are `JsonParseException` (not valid
  JSON, or a limit exceeded) and `JsonMappingException` (valid JSON that does not bind).
- What was found is named precisely: `'x'`, `U+00E9`, `the byte 0xFF`, `the end of the document`.
- Stage 1 builds the path only when it throws, from its depth stack (the last name or the element count of each open
  container). The cursor does the same from the tape.
- Misusing the streaming API (reading a value where a member name is) is an `IllegalStateException`, not a
  `JsonException`: it is a bug in the caller, not in the document.

## Public API

```java
JsonParser parser = JsonParser.builder()       // or new JsonParser(), with the defaults
        .maxDepth(500)
        .unknownMembers(UnknownMembers.REJECT)
        .build();

JsonDocument document = parser.parse(bytes);   // also String, ByteSource, CharSource
JsonValue root = document.root();
JsonPosition position = document.position(object, "name");
Order order = parser.parseAs(Order.class, bytes);

JsonStreamReader reader = parser.reader(bytes);

JsonWriter writer = JsonWriter.pretty();       // or new JsonWriter() (compact), or builder()
String text = writer.format(root);             // also toBytes, write to a ByteSink or an OutputStream
byte[] body = writer.toBytesAs(Order.class, order);
```

`JsonParser` and `JsonWriter` are immutable and thread-safe. `JsonStreamReader` and `JsonStreamWriter` are not.

`new JsonParser()` and `new JsonWriter()` delegate to their private constructor taking a builder, with a default
builder; each builder's `build()` uses that constructor too. The default writer is compact; `JsonWriter.pretty()` is the
shortcut for the pretty one. A parser or writer holds only its settings, and every cache (the binders, per record type)
is static, so creating one per call costs nothing.

### Tree

- `JsonValue` is sealed: `JsonObject`, `JsonArray`, `JsonString` (record), `JsonNumber`, `JsonBoolean` (enum),
  `JsonNull` (enum). `type()` returns a `JsonType`.
- `JsonObject` keeps document order; `members()` is an unmodifiable ordered `Map` view; `get(name)` returns an
  `Optional`. Lookups scan up to 8 members, and use a hash index above. Equality ignores order.
- `JsonArray`: `elements()` is an unmodifiable `List` view, `get(index)`, `size()`.
- `JsonNumber` is lossless: it holds a `long` when the value is an integer of up to 18 digits, its text otherwise.
  Conversions happen on the call: `asInt()`, `asLong()`, `asShort()`, `asByte()` and `asBigInteger()` are exact, and
  throw `ArithmeticException` otherwise (`1.0` is an exact `1`); `asDouble()` and `asFloat()` return the nearest value,
  and throw when it overflows; `asBigDecimal()` is exact. `asBigInteger()` refuses results of more than 100 000 digits,
  as `1e1000000000` would otherwise exhaust memory. Equality is numeric: `1`, `1.0` and `1e0` are equal.
- `JsonDocument` holds the root, and gives the position of each value: `position(object, name)`, `position(array,
  index)`, `rootPosition()`. It keeps the input and the tape to compute them.

#### Lazy tree

`parse()` runs stage 1 and returns: no value is created until it is accessed.

- `JsonObject` and `JsonArray` have two representations behind the same final class: backed by the tape (the document
  and the index of their start), or built in memory (by their factories and builders). The public API does not show
  which.
- A tape-backed container creates each member or element on its first access, and caches it in an array. The cache is
  filled with benign races, through `VarHandle` release and acquire: an entry is `null` until it is set, two threads
  may create the same immutable value, and either result is correct.
- Strings and numbers are created from the tape the same way, and are independent of it once created.
- `JsonObject` finds the names of its members once, jumping over their values, and decodes each name on its first use.
  `get(name)` scans up to 8 members; from 9, the first lookup builds a hash index.
- `equals`, `hashCode` and `toString` work on both representations, and create what they need.
- A tape-backed container keeps the whole input and tape alive, even when it is the only part of the document still
  referenced. `JsonObject.copyOf` and `JsonArray.copyOf` make an in-memory copy that does not.

### Streaming reader

A cursor over the tape. The checks a public API needs are tag comparisons it makes anyway to report type errors, so the
wrapper over the core costs close to nothing.

```java
reader.beginObject();
while (reader.hasNextMember()) {
    switch (reader.name(NAMES)) {          // NAMES = JsonNames.of("id", "items")
        case 0 -> id = reader.readLong();
        case 1 -> items = readItems(reader);
        default -> reader.skipValue();     // O(1)
    }
}
```

- `peek()` returns the `JsonType` of the next value.
- Containers: `beginObject()`, `hasNextMember()`, `beginArray()`, `hasNextElement()`; the `hasNext` methods return
  `false` at the end and leave the container.
- Names: `name(JsonNames)` returns the index of the name, or -1, with no `String` created: for a shared name, from a
  cache by number in the `JsonNames`, filled on first use and shared by all threads; otherwise, by comparing the raw
  bytes of the name with the precomputed UTF-8 bytes of the candidates. `readName()` returns the name as a `String`.
- Values: `readString()`, `readInt()`, `readLong()`, `readShort()`, `readByte()`, `readDouble()`, `readFloat()`,
  `readBigInteger()`, `readBigDecimal()`, `readBoolean()`, `readNull()` (returns whether the value is `null`, and reads
  it if it is), `readValue()` (a `JsonValue`), `skipValue()`.
- Number reads follow `JsonNumber` (`readInt()` on `1.5` is a `JsonMappingException`). `readDouble()` uses the Clinger
  fast path (mantissa below 2⁵³ and a power of ten up to 22: one exact multiplication or division) and falls back on
  `Double.parseDouble`.
- For binders: `skipUnknownMember(JsonNames)` skips the value of a member that `name` did not know, or rejects it under
  `UnknownMembers.REJECT`; `peekTag(member, tags)` finds the tag of an object laid out with `@JsonTagMember`, wherever
  the member is, without moving; `mark()` and `mappingError(mark, message)` report an error at a value read earlier.

### Streaming writer

`JsonStreamWriter` writes UTF-8 bytes into a growing buffer, compact or pretty: `beginObject()`, `name(String)`,
`name(JsonNames, index)` (pre-encoded, escaped bytes copied as they are), `value(...)` for each type, `nullValue()`,
`endObject()`, and the same for arrays. A small state stack enforces the grammar (`IllegalStateException` otherwise).
`JsonWriter` uses a private writer without those checks.

- Strings: `"`, `\` and controls are escaped (`\n`, `\t`, … or `\u00XX`); everything else is written as UTF-8; an
  unpaired surrogate is an `IllegalArgumentException`.
- Speed: the start of a string that is ASCII without escapes, most strings whole, is copied in a loop of its own,
  read from the `String` as it is, with room for one byte per char; the rest is written in chunks, with room for an
  escape sequence per char. Integers are written two digits at a time, from a table, without a `String`. A parsed tree
  is written from its tape: names and strings without escapes, and numbers, are copied as they were written.
- The one-shot methods of `JsonWriter` take their buffer from a small pool, one slot per thread modulo its size, and
  give it back, as Jackson recycles its buffers: a buffer that grew to the size of earlier documents spares the next
  ones its growth. Buffers above 1 MB are not kept. A `JsonStreamWriter` the caller holds has a buffer of its own.
- A tree is written by switching on `JsonValue.type()`, a table jump: a pattern switch over the sealed hierarchy
  dispatches each value dynamically, and made writing a built tree about 20% slower.
- `double` and `float` are written with `Double.toString` and `Float.toString` (shortest representation that reads back
  to the same value); `NaN` and infinities are an `IllegalArgumentException`.
- Pretty: 2 spaces by default, `"name": value`, empty containers as `{}` and `[]`.
- A record component that is `null` is omitted by default; `JsonWriter.Builder.writeNullMembers()` writes it as `null`
  instead. Reading binds both forms to `null`.

### Inputs

Everything is parsed from a `byte[]` holding UTF-8:

- `byte[]`: used as is, not copied;
- `String`: encoded to UTF-8, rejecting unpaired surrogates (`getBytes` would replace them silently);
- `ByteSource`: read whole, up to `maxDocumentLength`;
- `CharSource`: read as a `String`.

A byte order mark is rejected (RFC 8259: implementations must not add one, and may ignore one), except in JSON5, where
U+FEFF is white space.

### Limits

On `JsonParser.Builder`, all checked in stage 1, each failure a `JsonParseException` naming the limit:

| Limit               | Default          | Why                                                      |
|---------------------|------------------|----------------------------------------------------------|
| `maxDepth`          | 1 000            | binders recurse with the document; bounds that recursion |
| `maxDocumentLength` | none (2³¹−1)     | memory                                                   |
| `maxStringLength`   | 20 000 000 bytes | memory                                                   |
| `maxNumberLength`   | 1 000 bytes      | `BigDecimal` and `BigInteger` parsing is superlinear     |

## Binding

- `@JsonRecord` marks a record, or a sealed interface (see [Polymorphism](#polymorphism)), that documents bind to. The
  records, sealed interfaces and enums it refers to are bound too, without the annotation. `@JsonName("...")` renames a
  component, an enum constant, or the tag of a record; the default is its Java name.
- The processor generates `$Order$$JsonBinder implements JsonBinder<Order>`, with a `read(JsonStreamReader)` and a
  `write(JsonStreamWriter, Order)`, one method per record and enum, and one static `JsonNames` per record. It uses
  gaia-processing (`StepProcessor`, `GeneratedSources`, `ServiceFiles`, `Diagnostics`) and rejects at compile time any
  type it cannot bind.
- Discovery: `ServiceLoader`, cached per type in a `ClassValue`. On the class path, the processor writes
  `META-INF/services`; on the module path, the record's module needs `provides be.imgn.json.JsonBinder with ...`, which
  the processor reports when it is missing.
- Generated reading: the loop shown above, a `long` bitmask of the components seen (several for more than 64), and a
  check that every required component was seen. No duplicate check: stage 1 already did it.
- Missing members and `null`: a component annotated `@Nullable` (any annotation of that simple name) is `null` when its
  member is missing or `null`; any other component must be present and not `null`.
- Unknown members: skipped (`UnknownMembers.IGNORE`, the default, a tolerant reader as APIs need), or rejected.
- A record whose canonical constructor throws is a `JsonMappingException` at the position of its object, with the
  exception as its cause.
- Types: `String`; primitives and their boxes; `BigInteger`, `BigDecimal`; enums (by name); records; `List<T>`,
  `Set<T>` (duplicates rejected), `Map<String, T>`; `JsonValue` and its subtypes; `Instant`, `OffsetDateTime`,
  `LocalDate`, `LocalTime`, `LocalDateTime` and `Duration` from ISO-8601 strings; `UUID` and `URI` from their canonical
  strings. Collections are unmodifiable, in document order.
- A component of type `@Nullable JsonValue` tells a missing member (`null`) from a `null` member (`JsonNull.INSTANCE`),
  which a PATCH needs.

### Polymorphism

A sealed interface binds to the record its document names, in one of two layouts, chosen per interface by the user, who
knows the format their API expects:

```java
@JsonTagMember("type")                      // the default, also without the annotation
sealed interface Shape permits Circle, Rectangle {}

@JsonWrapper
sealed interface Event permits Created, Deleted {}

@JsonName("circle") record Circle(double radius) implements Shape {}
```

| Layout     | Document                          | Annotation             |
|------------|-----------------------------------|------------------------|
| Tag member | `{"type": "circle", "radius": 1}` | `@JsonTagMember(name)` |
| Wrapper    | `{"circle": {"radius": 1}}`       | `@JsonWrapper`         |

- The layout is decided at compile time, per interface: two interfaces of the same API may differ, and the generated
  code is specialized for each. A sealed interface with neither annotation uses a tag member named `"type"`; both
  annotations on one interface are a compile error.
- Only `sealed` interfaces whose permitted subtypes are records or sealed interfaces; nested sealed interfaces are
  flattened, and the layout of the interface being bound applies to all its records. Any other hierarchy is rejected at
  compile time.
- The tag of a record is its `@JsonName`, or its simple name. Tags must be distinct (compile time).
- Tag member, reading: the generated code scans the names of the object on the tape, jumping over the values, compares
  the raw bytes with the tag member's name, reads the tag, then reads the object from its start as that record, which
  skips the tag member. Nothing is buffered, whatever the position of the tag member. No component of a subtype may have
  the tag member's name (compile time).
- Wrapper, reading: the object must have exactly one member; its name is the tag, and its value is the record.
- Writing: the tag member first, then the record's members; or the wrapper object around the record.
- A missing tag, an unknown tag, or a wrapper with zero or several members is a `JsonMappingException` that lists the
  expected tags.
- The tag-and-value layout (`{"type": "circle", "value": {...}}`) can be added later as a third annotation, without
  changing the others.

## Formats

Three syntaxes, chosen on the parser, each strict within its own specification, and one framing:

| Format     | Specification                                                    | On top of JSON                                 |
|------------|------------------------------------------------------------------|------------------------------------------------|
| JSON       | [RFC 8259](https://www.rfc-editor.org/rfc/rfc8259), the default  |                                                |
| JSONC      | [JSONC](https://jsonc-spec.codeberg.page)                        | `//` and `/* */` comments, where JSON has whitespace |
| JSON5      | [JSON5 1.0.0](https://spec.json5.org)                            | see below                                      |
| JSON Lines | [JSON Lines](https://jsonlines.org)                              | one JSON value per line                        |

```java
JsonDocument settings = JsonParser.builder().format(JsonFormat.JSONC).build().parse(bytes);
try (Stream<Event> events = new JsonParser().parseLinesAs(Event.class, ByteSource.of(path))) { ... }
```

- One choice, `JsonParser.Builder.format(JsonFormat)`, rather than a flag per feature (`allowComments()`,
  `allowTrailingCommas()`, …): a mix of flags is a format no specification defines, and "strict within the format"
  stops meaning anything.
- JSON ⊂ JSONC ⊂ JSON5: each accepts everything the previous one does.
- The rules that are not grammar hold in every format: duplicate member names are rejected (JSON5, like RFC 8259, says
  names should be unique), so are unpaired surrogates and invalid UTF-8, and the limits apply.
- Trailing commas are not JSONC: its specification lets a parser accept them, and its reference parser rejects them by
  default. JSON5 has them.
- An error in strict JSON that another format would accept says so: `comments are not allowed in JSON (JsonFormat.JSONC
  allows them)`, `a trailing comma is not allowed in JSON (JsonFormat.JSON5 allows it)`.

### JSONC and JSON5: translated to JSON

A JSONC or JSON5 document is translated to JSON first, in one pass, then built by the same tape builder as JSON:

```
 byte[] JSONC or JSON5 ──▶ Jsonc / Json5 ──▶ byte[] JSON + positions ──▶ TapeBuilder ──▶ Tape
```

- The tape builder, its tape and everything above them stay as they are: one validated core, and JSON pays nothing
  measurable for the other formats (their cases sit in error branches, but for one predicted branch on numbers). The
  price is a pass for JSONC (and a copy, once it has a comment), and a pass and a copy for JSON5, which are mostly
  configuration files: stage 1 runs at 60–85% of its JSON speed on JSONC, 40% on JSON5.
- The translator checks what is particular to its format (comments, quotes, identifiers, escapes, number forms) and
  leaves the structure (brackets, commas, colons, duplicate names, nesting) to the tape builder.
- Positions stay those of the source: the tape keeps a locator from the offsets of the translated document to the
  lines and columns of the original. JSONC keeps every offset, comments becoming spaces; JSON5 records where the
  translation shifts them.
- A parsed tree is written as JSON, as it is copied from the translated document: no JSON5 syntax can leak into the
  output.

JSONC: comments become spaces of the same length, and nothing else changes.

JSON5, translated as follows:

| JSON5                                                      | JSON                                       |
|------------------------------------------------------------|--------------------------------------------|
| comments; whitespace beyond JSON's (U+00A0, U+FEFF, `Zs`, …) | dropped                                  |
| a trailing comma in an object or array                     | dropped                                    |
| `name: 1`, an ECMAScript 5.1 identifier                    | `"name": 1`                                |
| `'it\'s'`, single quotes                                   | `"it's"`                                   |
| `\v`, `\0`, `\xHH`, `\a` (any other character)             | `\u000B`, `\u0000`, `\u00HH`, `a`          |
| a backslash before a line terminator                       | dropped, with the line terminator          |
| control characters, unescaped in a string                  | escaped                                    |
| `+1`, `.5`, `5.`, `0x1F`                                   | `1`, `0.5`, `5.0`, `31`                    |
| `Infinity`, `-Infinity`, `NaN`, signed or not              | kept, and flagged on the tape              |

- `NaN` and the infinities have no JSON form. The tape builder accepts them only behind a JSON5 option, and flags them
  on the tape: `JsonNumber.asDouble()` and `asFloat()` return them, the exact conversions throw an
  `ArithmeticException`, and writing them as JSON is an `IllegalArgumentException`, as for `JsonNumber.of(Double.NaN)`.
- `maxNumberLength` applies to a number as written, a hexadecimal one before it is converted, and to its translation;
  `maxStringLength` to a string as translated; `maxDocumentLength` to the source.
- A leading U+FEFF is whitespace in JSON5, so it is accepted there; JSON and JSONC reject it.

### JSON Lines

One JSON value per line: a framing, not a syntax, so it is defined for JSON only, and a parser set to JSONC or JSON5
refuses it.

- `parseLines(…)` returns a `Stream<JsonDocument>`, and `parseLinesAs(type, …)` a `Stream<T>`, from a `byte[]`, a
  `String` or a `ByteSource`. A stream on a `ByteSource` reads it as it goes, holds its input open until it is closed,
  and wraps an `IOException` in an `UncheckedIOException`, as `Files.lines` does.
- Strict: lines end with `\n` (the `\r` of `\r\n` is whitespace in JSON); the last `\n` is optional; a blank line is an
  error; a value must end on the line it starts.
- Each line is a document of its own, built from a range of the input: the tape builder takes a start and an end. Errors
  give the line in the whole input, and say `the end of the line` rather than `the end of the document`.
- The limits apply to each line; `maxDocumentLength` bounds the length of a line, checked before the line is read whole.
- `JsonWriter.writeLines(…)`, `formatLines(…)` and their `…As(type, …)` forms write each value compact, followed by
  `\n`, the last one included, whatever the indentation of the writer.
- Reading a `ByteSource`: chunks of 64 KB, never reused, since a lazy document keeps its input; a line split between
  two chunks is carried over to the next one.

## Vector API

It is still incubating in Java 25 (`jdk.incubator.vector`), waiting for Valhalla's value classes before it can go to
preview, then final. Until it is final, SWAR only.

When it is final, two steps, each behind the same tape:

1. Kernels: string scanning, whitespace skipping and UTF-8 validation on 32 or 64 bytes at once, in place of the SWAR
   ones, chosen once in a `static final` field.
2. A simdjson-style stage 1: a structural index (bitmaps of quotes, backslashes, structural characters per 64-byte
   block, prefix-XOR for the string mask), then the tape built by walking the index.

## Benchmarks

JMH, in `json-benchmarks`, built and run with
`./mvnw -pl json-benchmarks -am package exec:exec -DskipTests -Djmh.args="TreeBenchmark -prof gc"`.

- Data: `twitter.json` (strings, non-ASCII text), `citm_catalog.json` (structure, integers), `canada.json` (doubles),
  and `status.json`, an API-sized payload of about 2 KB: the first status of `twitter.json`, written compact.
- Comparisons: Jackson 3, Gson, fastjson2.
- Cases: `ValidateBenchmark`, stage 1 alone against Jackson's streaming parser, and the same documents read as JSONC and
  JSON5; `TreeBenchmark`, the lazy tree alone,
  and parsing then reading every value with each library; `WriteBenchmark`, a parsed tree (written from its document)
  and a built one, against the trees of the other libraries.
- Binding is not in the module: compiling records with the processor under the parent's `-Xlint:all -Werror` fails
  on the processing lint, as gaia's `StepProcessor` never claims the annotations it processes, while Error Prone's
  `DoNotClaimAnnotations` forbids claiming them. It was measured outside the build, on partial records of
  `twitter.json` and full records of `canada.json`.

## Testing

- [JSONTestSuite](https://github.com/nst/JSONTestSuite): every `y_` parses, except the two with duplicate names, rejected
  by design; every `n_` is rejected; every `i_` has an expected outcome written down (rejected: BOM, unpaired surrogates,
  invalid UTF-8, UTF-16; accepted: huge numbers, and deep nesting within the limits).
- [json5-tests](https://github.com/json5/json5-tests): every `.json` and `.json5` parses as JSON5, except the one with
  duplicate names, rejected by design; every `.js` and `.txt` is rejected; its JSON documents read the same as JSON and
  as JSON5. Every `y_` document of JSONTestSuite builds the same tape as JSONC and as JSON5.
- JSON Lines: blank lines, values over two lines, positions in the whole input, a source read a few bytes at a time,
  lines longer than a chunk, a line too long rejected before it is read whole, the source closed with the stream.
- Every error message: position, path and text.
- Round trips: parse, write, parse again, equal; written from the tape and from a copy, the same text.
- The processor: compile errors on unsupported types, generated code without warnings under `-Xlint:all -Werror` (the
  processing lint off), and binding through the generated code, compiled with `TestCompiler` and loaded in a class
  loader of its own.

## Order of work

1. Tape builder, with the JSONTestSuite and the error tests.
2. Benchmark harness: stage 1 against Jackson's streaming parser.
3. Streaming reader, tree, `JsonDocument`.
4. Streaming writer, `JsonWriter`.
5. Processor and binding.
6. All benchmarks.
