package be.imgn.json.processor;

import static be.imgn.gaia.processing.testing.CompilationAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import be.imgn.json.JsonMappingException;
import be.imgn.json.JsonParser;
import be.imgn.json.JsonWriter;
import be.imgn.json.UnknownMembers;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.assertj.core.api.ThrowableAssertAlternative;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Binds documents through the code the processor generates for the records of the test resources, compiled and
/// loaded as an application would.
class BindingTest {

    private static final String ORDER = """
            {
              "id": 42,
              "customer": {
                "name": "Ada",
                "e-mail": "ada@example.com",
                "id": "123e4567-e89b-12d3-a456-426614174000",
                "site": "https://example.com/ada"
              },
              "lines": [
                {
                  "product": "pen",
                  "quantity": 3,
                  "price": 1.5,
                  "discount": 10,
                  "at": "2026-11-16T10:15:30+01:00",
                  "day": "2026-11-16",
                  "time": "10:15:30",
                  "local": "2026-11-16T10:15:30",
                  "delay": "PT48H",
                  "serial": 123456789012345678901234567890,
                  "shelf": 7,
                  "bin": -3,
                  "weight": 0.25,
                  "gift": true
                }
              ],
              "status": "in-progress",
              "shipped": "2026-11-17T08:00:00Z",
              "notes": {
                "gate": "B",
                "floor": "2"
              },
              "tags": [
                "fragile",
                "urgent"
              ],
              "total-price": 4.50
            }""";

    private static ClassLoader loader;

    @BeforeAll
    static void compile(@TempDir Path directory) {
        var compilation = Compiler.compile(Compiler.records());
        assertThat(compilation).succeeded().hadNoWarnings();
        loader = Compiler.load(compilation, directory);
    }

    @Test
    void bindsAndWritesBack() {
        var order = parse("Order", ORDER);

        assertThat(order)
                .hasToString("Order[id=42, customer=Customer[name=Ada, email=ada@example.com,"
                        + " id=123e4567-e89b-12d3-a456-426614174000, site=https://example.com/ada],"
                        + " lines=[Line[product=pen, quantity=3, price=1.5, discount=10, at=2026-11-16T10:15:30+01:00,"
                        + " day=2026-11-16, time=10:15:30, local=2026-11-16T10:15:30, delay=PT48H,"
                        + " serial=123456789012345678901234567890, shelf=7, bin=-3, weight=0.25, gift=true]],"
                        + " status=IN_PROGRESS, shipped=2026-11-17T08:00:00Z, notes={gate=B, floor=2},"
                        + " tags=[fragile, urgent], total=4.50]");
        assertThat(format(JsonWriter.pretty(), "Order", order)).isEqualTo(ORDER);
    }

    @Test
    void omitsNullComponentsUnlessAsked() {
        var json = ORDER.replace("\"shipped\": \"2026-11-17T08:00:00Z\",", "\"shipped\": null,")
                .replace("\"discount\": 10,", "");
        var order = parse("Order", json);

        assertThat(order.toString()).contains("discount=null").contains("shipped=null");
        var written = format(new JsonWriter(), "Order", order);
        assertThat(written).doesNotContain("shipped").doesNotContain("discount");
        assertThat(format(JsonWriter.builder().writeNullMembers().build(), "Order", order))
                .contains("\"shipped\":null")
                .contains("\"discount\":null");
        assertThat(parse("Order", written)).isEqualTo(order);
    }

    @Test
    void reportsMappingErrors() {
        assertMappingError(
                "Order", ORDER.replace("\"id\": 42,", ""), "line 1, column 1: missing member \"id\" of Order");
        assertMappingError(
                "Order",
                ORDER.replace("\"quantity\": 3", "\"quantity\": \"3\""),
                "line 12, column 19, at /lines/0/quantity: expected an int, found a string");
        assertMappingError(
                "Order",
                ORDER.replace("\"in-progress\"", "\"IN_PROGRESS\""),
                "line 27, column 13, at /status: expected one of \"NEW\", \"in-progress\", \"DONE\" for Status, found"
                        + " \"IN_PROGRESS\"");
        assertMappingError(
                "Order",
                ORDER.replace("426614174000", "4266"),
                "line 6, column 11, at /customer/id: expected a UUID, found \"123e4567-e89b-12d3-a456-4266\"");
        assertMappingError(
                "Order",
                ORDER.replace("\"urgent\"", "\"fragile\""),
                "line 35, column 5, at /tags/1: duplicate element in a set");
        assertMappingError(
                "Order",
                ORDER.replace("\"name\": \"Ada\"", "\"name\": null"),
                "line 4, column 13, at /customer/name: expected a string, found null");
        assertMappingError(
                        "Positive",
                        "{\"value\": -1}",
                        "line 1, column 1: Positive rejects the members: not positive: -1")
                .withCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ignoresOrRejectsUnknownMembers() {
        var json = "{\"value\": 1, \"other\": {\"deep\": [1, 2]}}";

        assertThat(parse("Positive", json)).hasToString("Positive[value=1]");
        var strict = JsonParser.builder().unknownMembers(UnknownMembers.REJECT).build();
        assertThatExceptionOfType(JsonMappingException.class)
                .isThrownBy(() -> strict.parseAs(type("Positive"), json))
                .withMessage("line 1, column 14, at /other: unknown member \"other\", expected one of \"value\"");
    }

    @Test
    void bindsSealedInterfaceWithTagMember() {
        var json = """
                {"shapes": [{"radius": 1, "kind": "Circle"}, {"kind": "rectangle", "width": 2, "height": 3},
                 {"sides": [3, 4, 5], "kind": "Triangle"}], "meta": {}}""";
        var drawing = parse("Drawing", json);

        assertThat(drawing)
                .hasToString("Drawing[shapes=[Circle[radius=1.0], Rectangle[width=2.0, height=3.0],"
                        + " Triangle[sides=[3.0, 4.0, 5.0]]], last=null, extra=null, meta={}]");
        assertThat(format(new JsonWriter(), "Drawing", drawing))
                .isEqualTo("{\"shapes\":[{\"kind\":\"Circle\",\"radius\":1.0},{\"kind\":\"rectangle\",\"width\":2.0,"
                        + "\"height\":3.0},{\"kind\":\"Triangle\",\"sides\":[3.0,4.0,5.0]}],\"meta\":{}}");
        assertMappingError(
                "Shape",
                "{\"radius\": 1}",
                "line 1, column 1: missing member \"kind\" naming the type, as one of \"Circle\", \"rectangle\","
                        + " \"Triangle\"");
        assertMappingError(
                "Shape",
                "{\"kind\": \"Square\"}",
                "line 1, column 10, at /kind: unknown type \"Square\", expected one of \"Circle\", \"rectangle\","
                        + " \"Triangle\"");
    }

    @Test
    void bindsSealedInterfaceAsWrapper() {
        var drawing = parse("Drawing", "{\"shapes\": [], \"last\": {\"deleted\": {\"id\": 2}}, \"meta\": {}}");

        assertThat(drawing).hasToString("Drawing[shapes=[], last=Deleted[id=2, reason=null], extra=null, meta={}]");
        assertThat(format(new JsonWriter(), "Drawing", drawing))
                .isEqualTo("{\"shapes\":[],\"last\":{\"deleted\":{\"id\":2}},\"meta\":{}}");
        assertThat(parse("Event", "{\"Created\": {\"id\": 1}}")).hasToString("Created[id=1]");
        assertMappingError(
                "Event",
                "{}",
                "line 1, column 1: expected a member naming the type of Event, as one of \"Created\", \"deleted\","
                        + " found none");
        assertMappingError(
                "Event",
                "{\"Created\": {\"id\": 1}, \"deleted\": {\"id\": 2}}",
                "line 1, column 1, at /deleted: expected only the member naming the type of Event, found others");
        assertMappingError(
                "Event",
                "{\"Updated\": {}}",
                "line 1, column 1, at /Updated: unknown type \"Updated\" of Event, expected one of \"Created\","
                        + " \"deleted\"");
    }

    @Test
    void tellsMissingMemberFromNullOne() {
        assertThat(parse("Drawing", "{\"shapes\": [], \"meta\": {}}").toString())
                .contains("extra=null");
        assertThat(parse("Drawing", "{\"shapes\": [], \"extra\": null, \"meta\": {}}")
                        .toString())
                .contains("extra=null");
        var withNull = parse("Drawing", "{\"shapes\": [], \"extra\": null, \"meta\": {\"a\": [1]}}");
        assertThat(format(new JsonWriter(), "Drawing", withNull))
                .isEqualTo("{\"shapes\":[],\"extra\":null,\"meta\":{\"a\":[1]}}");
        assertMappingError(
                "Drawing",
                "{\"shapes\": [], \"meta\": []}",
                "line 1, column 24, at /meta: expected an object, found an array");
    }

    @Test
    void bindsRecursiveRecords() {
        var json = "{\"name\":\"a\",\"children\":[{\"name\":\"b\",\"children\":[]},{\"name\":\"c\",\"children\":[]}]}";

        assertThat(format(new JsonWriter(), "Node", parse("Node", json))).isEqualTo(json);
    }

    @Test
    void bindsRecordsOfMoreThan64Components() {
        var members = IntStream.range(0, 70)
                .filter(i -> i != 66)
                .mapToObj(i -> "\"c" + i + "\":" + i)
                .collect(Collectors.joining(","));
        var wide = parse("Wide", "{" + members + "}");

        assertThat(wide.toString()).contains("c65=65, c66=null, c67=67").endsWith("c69=69]");
        assertThat(format(new JsonWriter(), "Wide", wide)).isEqualTo("{" + members + "}");
        assertMappingError(
                "Wide",
                "{" + members.replace(",\"c69\":69", "") + "}",
                "line 1, column 1: missing member \"c69\" of Wide");
    }

    private static Class<?> type(String simpleName) {
        try {
            return Class.forName("be.imgn.json.tests." + simpleName, true, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object parse(String type, String json) {
        return new JsonParser().parseAs(type(type), json);
    }

    private static String format(JsonWriter writer, String type, Object value) {
        return format(writer, type(type), value);
    }

    private static <T> String format(JsonWriter writer, Class<T> type, Object value) {
        return writer.formatAs(type, type.cast(value));
    }

    private static ThrowableAssertAlternative<JsonMappingException> assertMappingError(
            String type, String json, String message) {
        return assertThatExceptionOfType(JsonMappingException.class)
                .isThrownBy(() -> parse(type, json))
                .withMessage(message);
    }
}
