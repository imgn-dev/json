# The tree

## Parsing a document

[JsonParser](api:be.imgn.json.JsonParser) parses a document from bytes in UTF-8, a `String`, or one of gaia's
`ByteSource` and `CharSource`, which it reads whole. It returns a [JsonDocument](api:be.imgn.json.JsonDocument): the
root value, and where each value is written.

```java
JsonParser parser = new JsonParser();
JsonDocument document = parser.parse("""
        {
          "id": 42,
          "customer": {"name": "Ada", "e-mail": "ada@example.com"},
          "tags": ["new", "priority"],
          "total": 19.90
        }
        """);
JsonValue root = document.root();
```

The whole document is validated before `parse` returns: a document that is not valid JSON never yields a value. See
[Errors and limits](errors.md).

## Values

A value is a [JsonValue](api:be.imgn.json.JsonValue), a sealed interface, so a `switch` over its six types needs no
`default`:

```java
static String describe(JsonValue value) {
    return switch (value) {
        case JsonObject object -> "an object of " + object.size() + " members";
        case JsonArray array -> "an array of " + array.size() + " elements";
        case JsonString string -> "the string " + string.value();
        case JsonNumber number -> "the number " + number;
        case JsonBoolean bool -> "the boolean " + bool.value();
        case JsonNull ignored -> "null";
    };
}
```

| JSON    | Value                                        | Reads as                                            |
|---------|----------------------------------------------|-----------------------------------------------------|
| object  | [JsonObject](api:be.imgn.json.JsonObject)    | `get(name)`, an `Optional`; `members()`, a `Map`, in order |
| array   | [JsonArray](api:be.imgn.json.JsonArray)      | `get(index)`; `elements()`, a `List`                |
| string  | [JsonString](api:be.imgn.json.JsonString)    | `value()`, a `String`                               |
| number  | [JsonNumber](api:be.imgn.json.JsonNumber)    | `asInt()`, `asLong()`, `asDouble()`, `asBigDecimal()`… |
| boolean | [JsonBoolean](api:be.imgn.json.JsonBoolean)  | `value()`; the constants `TRUE` and `FALSE`         |
| `null`  | [JsonNull](api:be.imgn.json.JsonNull)        | the constant `INSTANCE`                             |

`type()` returns the type of a value as a [JsonType](api:be.imgn.json.JsonType), and `toString()` returns it as compact
JSON (but for the `NaN` and infinities of [JSON5](formats.md#json5): see [Writing](writing.md)).

```java
JsonObject order = (JsonObject) document.root();
long id = ((JsonNumber) order.get("id").orElseThrow()).asLong();             // 42
JsonArray tags = (JsonArray) order.get("tags").orElseThrow();
String first = ((JsonString) tags.get(0)).value();                            // "new"
for (Map.Entry<String, JsonValue> member : order.members().entrySet()) {     // id, customer, tags, total
    System.out.println(member.getKey() + " is " + describe(member.getValue()));
}
```

Values are immutable: the maps and lists they return cannot be modified.

## Numbers

A number is kept exactly as it is written, and converted only when it is read:

- `asInt()`, `asLong()`, `asShort()`, `asByte()` and `asBigInteger()` are exact. They accept any number whose value is
  an integer in their range, `1.0` and `1e2` included, and throw an `ArithmeticException` otherwise: `19.90 is not an
  int`.
- `asDouble()` and `asFloat()` return the nearest value.
- `asBigDecimal()` is exact: `19.90` keeps its scale.

Two numbers are equal when their values are: `1`, `1.0` and `1e0` are equal. `toString()` returns the number as it is
written.

## Positions

The document tells where each of its values starts, as a [JsonPosition](api:be.imgn.json.JsonPosition): a line and a
column, both from 1, the column counted in UTF-16 code units, as in a `String`.

```java
document.rootPosition();                 // line 1, column 1
document.position(order, "total");      // line 5, column 12
document.position(tags, 1);             // line 4, column 19
```

The object or array must be one of this document, as the document returned it.

## Laziness and memory

The objects and arrays of a document are lazy: each value is created on its first access, from the document's bytes
and its index, which a value keeps in memory while it is referenced. To keep a small part of a large document,
copy it: `JsonObject.copyOf` and `JsonArray.copyOf` make copies that do not keep the document, and neither do the
objects and arrays they hold.

```java
JsonObject customer = JsonObject.copyOf((JsonObject) order.get("customer").orElseThrow());
```

## Building values

Values are also built directly, to be [written](writing.md):

```java
JsonObject built = JsonObject.builder()
        .put("id", JsonNumber.of(42))
        .put("tags", JsonArray.of(new JsonString("new"), new JsonString("priority")))
        .put("paid", JsonBoolean.TRUE)
        .put("note", JsonNull.INSTANCE)
        .build();                         // {"id":42,"tags":["new","priority"],"paid":true,"note":null}
```

`JsonObject.of(map)` and `JsonArray.of(list)` copy a map or a list. A builder rejects a name it already has, as the
parser does. `JsonNumber.of` takes a `long`, a finite `double`, a `BigInteger` or a `BigDecimal`, and
`JsonNumber.parse` the text of a JSON number.

Values compare by content: objects with the same members, whatever their order, are equal, whether parsed or built.
