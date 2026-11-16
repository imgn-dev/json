package be.imgn.json.benchmarks;

import be.imgn.json.JsonDocument;
import be.imgn.json.JsonParser;
import com.alibaba.fastjson2.JSON;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/// Parses a document into a tree. `parse` is the lazy tree alone: it validates the document and creates the root;
/// the others parse and read every value, as [Visits] does.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class TreeBenchmark {

    private static final JsonParser PARSER = new JsonParser();
    private static final ObjectMapper JACKSON = JsonMapper.builder().build();

    /// The document.
    @Param({"canada.json", "citm_catalog.json", "twitter.json", "status.json"})
    public String file = "";

    private byte[] input = new byte[0];

    /// Reads the document.
    @Setup
    public void setUp() {
        input = Data.read(file);
    }

    /// Parses into the lazy tree, without reading it.
    @Benchmark
    public JsonDocument parse() {
        return PARSER.parse(input);
    }

    /// Parses into the lazy tree, and reads every value.
    @Benchmark
    public double parseAndVisit() {
        return Visits.visit(PARSER.parse(input).root());
    }

    /// Parses into a Jackson tree, and reads every value.
    @Benchmark
    public double jackson() {
        return Visits.visit(JACKSON.readTree(input));
    }

    /// Parses into a Gson tree, and reads every value.
    @Benchmark
    public double gson() {
        var reader = new InputStreamReader(new ByteArrayInputStream(input), StandardCharsets.UTF_8);
        return Visits.visit(com.google.gson.JsonParser.parseReader(reader));
    }

    /// Parses into fastjson2's maps and lists, and reads every value.
    @Benchmark
    public double fastjson2() {
        return Visits.visit(Objects.requireNonNull(JSON.parse(input)));
    }
}
