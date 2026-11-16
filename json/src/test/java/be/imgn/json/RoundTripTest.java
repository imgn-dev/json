package be.imgn.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/// Parses each valid document of JSONTestSuite, writes it compact and pretty, from the tape and from a copy, and
/// parses it again: every way gives an equal value.
class RoundTripTest {

    static Stream<Path> documents() throws IOException, URISyntaxException {
        var tests = Path.of(Objects.requireNonNull(RoundTripTest.class.getResource("/JSONTestSuite/test_parsing"))
                .toURI());
        try (var files = Files.list(tests)) {
            return files
                    .filter(file -> file.getFileName().toString().startsWith("y_")
                            && !file.getFileName().toString().startsWith("y_object_duplicated"))
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    @ParameterizedTest
    @MethodSource("documents")
    void writesAndParsesBack(Path document) {
        var parser = new JsonParser();
        JsonValue value;
        try {
            value = parser.parse(Files.readAllBytes(document)).root();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var copy = Detached.copyOf(value);

        for (var writer : new JsonWriter[] {new JsonWriter(), JsonWriter.pretty()}) {
            assertThat(parser.parse(writer.toBytes(value)).root()).isEqualTo(value);
            assertThat(writer.format(copy)).isEqualTo(writer.format(value));
        }
    }
}
