package be.imgn.json.benchmarks;

import be.imgn.json.JsonObject;
import be.imgn.json.JsonParser;
import be.imgn.json.JsonValue;
import be.imgn.json.JsonWriter;
import com.alibaba.fastjson2.JSON;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/// Writes a tree as compact JSON, in UTF-8. `parsed` writes a lazy tree from its document, `built` a tree held in
/// memory, as each other library does.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class WriteBenchmark {

    private static final JsonWriter WRITER = new JsonWriter();
    private static final ObjectMapper JACKSON = JsonMapper.builder().build();
    private static final Gson GSON = new Gson();

    /// The document.
    @Param({"canada.json", "citm_catalog.json", "twitter.json", "status.json"})
    public String file = "";

    private JsonValue parsed = be.imgn.json.JsonNull.INSTANCE;
    private JsonValue built = be.imgn.json.JsonNull.INSTANCE;
    private JsonNode jackson = JACKSON.nullNode();
    private JsonElement gson = com.google.gson.JsonNull.INSTANCE;
    private Object fastjson2 = "";

    /// Parses the document into each tree.
    @Setup
    public void setUp() {
        var input = Data.read(file);
        parsed = new JsonParser().parse(input).root();
        built = JsonObject.copyOf((JsonObject) parsed);
        jackson = JACKSON.readTree(input);
        gson = com.google.gson.JsonParser.parseReader(
                new InputStreamReader(new ByteArrayInputStream(input), StandardCharsets.UTF_8));
        fastjson2 = Objects.requireNonNull(JSON.parse(input));
    }

    /// Writes the lazy tree, from its document.
    @Benchmark
    public byte[] parsed() {
        return WRITER.toBytes(parsed);
    }

    /// Writes a tree held in memory.
    @Benchmark
    public byte[] built() {
        return WRITER.toBytes(built);
    }

    /// Writes a Jackson tree.
    @Benchmark
    public byte[] jackson() {
        return JACKSON.writeValueAsBytes(jackson);
    }

    /// Writes a Gson tree.
    @Benchmark
    public byte[] gson() {
        return GSON.toJson(gson).getBytes(StandardCharsets.UTF_8);
    }

    /// Writes fastjson2's maps and lists.
    @Benchmark
    public byte[] fastjson2() {
        return JSON.toJSONBytes(fastjson2);
    }
}
