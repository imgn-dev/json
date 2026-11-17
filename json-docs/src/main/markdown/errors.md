# Errors and limits

## Strict parsing

The parser validates a whole document before anything is read from it, as a tree, a stream or a record, and rejects
everything that is not [RFC 8259](https://www.rfc-editor.org/rfc/rfc8259) JSON:

- a grammar error, such as a missing comma, a trailing comma, a comment, or a second root value;
- a number that JSON does not allow, such as `01`, `.5` or `+1`;
- invalid UTF-8, an unpaired surrogate, whether encoded or escaped as `\uD800`, and a byte order mark;
- an object with two members of the same name, which RFC 8259 leaves to each parser and json rejects, as the meaning of
  such an object is ambiguous.

A document given as a `String` is encoded in UTF-8 first: one with an unpaired surrogate is rejected too.

## Exceptions

Every error in a document is a [JsonException](api:be.imgn.json.JsonException), unchecked and sealed:

- a [JsonParseException](api:be.imgn.json.JsonParseException) when the document is not valid in the format of the
  parser, or exceeds one of its limits;
- a [JsonMappingException](api:be.imgn.json.JsonMappingException) when a valid document does not bind to the type it is
  read as: a value of the wrong type, a number out of range, a missing or unknown member, or a value a record rejects.

The message starts with the position of the error and, below the root, its path, then states what was expected and
what was found:

```
line 1, column 17, at /items/1: expected ',' or ']' after an element, found '3'
line 1, column 10: duplicate member name "a", first at line 1, column 2
line 2, column 43, at /lines/0/quantity: expected an int, found a string
```

`position()` returns the position, a [JsonPosition](api:be.imgn.json.JsonPosition), and `path()` the path, as a
[JSON Pointer](https://www.rfc-editor.org/rfc/rfc6901): `/lines/0/quantity` is the member `quantity` of the first
element of the member `lines` of the root, which is the empty string.

```java
try {
    order = parser.parseAs(Order.class, body);
} catch (JsonException e) {
    log(e.position().line(), e.path(), e.getMessage());
}
```

Other exceptions are not about the document:

- `IOException`, when a `ByteSource`, a `CharSource`, an `OutputStream` or a `ByteSink` fails;
- `IllegalArgumentException`, when writing a string with an unpaired surrogate or a number with no JSON form, or when
  no binder is registered for a type;
- `IllegalStateException`, for a [streaming](streaming.md) call out of order.

## Limits

A parser bounds what a document can make it do. Exceeding a limit is a `JsonParseException`, at the value that exceeds
it: `line 1, column 2, at /0: the string is longer than the maximum of 10000 bytes`.

```java
JsonParser parser = JsonParser.builder()
        .maxDepth(64)                   // default 1 000 nested objects and arrays
        .maxDocumentLength(1 << 20)     // default: none, besides that of an array
        .maxStringLength(10_000)        // default 20 000 000 bytes
        .maxNumberLength(100)           // default 1 000 bytes
        .build();
```

- `maxDepth` bounds the nesting of objects and arrays. Parsing never recurses, but binding does, so this limit also
  bounds the stack binding uses.
- `maxDocumentLength` bounds the length of a document, in bytes.
- `maxStringLength` bounds the length of a string or a member name, in bytes, as written between its quotes.
- `maxNumberLength` bounds the length of a number, in bytes, as written: converting a long number to a `BigDecimal` or a
  `BigInteger` takes more than linear time.

For JSON Lines, the limits apply to each line.
