package be.imgn.json.benchmarks;

import be.imgn.json.internal.Json5;
import be.imgn.json.internal.Jsonc;
import be.imgn.json.internal.Limits;
import be.imgn.json.internal.Tape;
import be.imgn.json.internal.TapeBuilder;
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
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.json.JsonFactory;

/// Stage 1 alone, which validates a whole document and builds its tape, against Jackson's streaming parser reading
/// every token, without and with the values.
///
/// Jackson does not validate what it skips as thoroughly: it decodes strings and numbers only when they are read.
/// `jacksonValues` reads them all, as the tape builder validates them all.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ValidateBenchmark {

    private static final JsonFactory JACKSON = new JsonFactory();

    /// The document.
    @Param({"twitter.json", "citm_catalog.json", "canada.json"})
    public String file = "";

    private byte[] input = new byte[0];

    /// Reads the document.
    @Setup
    public void setUp() {
        input = Data.read(file);
    }

    /// Builds the tape.
    @Benchmark
    public Tape tapeBuilder() {
        return TapeBuilder.build(input, Limits.DEFAULTS);
    }

    /// Builds the tape of the document read as JSONC: translated first, comments becoming spaces.
    @Benchmark
    public Tape jsonc() {
        return Jsonc.build(input, Limits.DEFAULTS);
    }

    /// Builds the tape of the document read as JSON5: translated first.
    @Benchmark
    public Tape json5() {
        return Json5.build(input, Limits.DEFAULTS);
    }

    /// Reads every token with Jackson.
    @Benchmark
    public int jacksonTokens() {
        try (var parser = JACKSON.createParser(ObjectReadContext.empty(), input)) {
            var count = 0;
            while (parser.nextToken() != null) {
                count++;
            }
            return count;
        }
    }

    /// Reads every token with Jackson, and decodes each name, string and number.
    @Benchmark
    public int jacksonValues() {
        try (var parser = JACKSON.createParser(ObjectReadContext.empty(), input)) {
            var count = 0;
            for (var token = parser.nextToken(); token != null; token = parser.nextToken()) {
                if (token == JsonToken.PROPERTY_NAME || token == JsonToken.VALUE_STRING) {
                    count += parser.getString().length();
                } else if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
                    count += parser.getNumberValue().intValue();
                }
            }
            return count;
        }
    }
}
