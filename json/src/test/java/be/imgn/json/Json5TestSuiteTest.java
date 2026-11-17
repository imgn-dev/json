package be.imgn.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/// Runs the parsing tests of [json5-tests](https://github.com/json5/json5-tests): every `.json` and `.json5`
/// document is accepted, except the one with duplicate member names, and every `.js` and `.txt` document is
/// rejected. The `todo` directory, of names written with Unicode letters, is accepted too.
class Json5TestSuiteTest {

    private static final Path TESTS = tests();
    private static final JsonParser JSON5 =
            JsonParser.builder().format(JsonFormat.JSON5).build();

    /// The valid documents that are rejected: JSON5, like RFC 8259, only says that the names of an object should be
    /// unique, but this parser requires it.
    private static final Set<String> REJECTED_VALID = Set.of("objects/duplicate-keys.json");

    static Stream<String> accepted() {
        return files().filter(
                        name -> (name.endsWith(".json") || name.endsWith(".json5")) && !REJECTED_VALID.contains(name));
    }

    static Stream<String> rejected() {
        return files().filter(name -> name.endsWith(".js") || name.endsWith(".txt") || REJECTED_VALID.contains(name));
    }

    /// The `.json` documents, but one: despite its name, it ends with a comment.
    static Stream<String> json() {
        return accepted()
                .filter(name -> name.endsWith(".json") && !name.equals("comments/irregular-block-comment.json"));
    }

    @ParameterizedTest
    @MethodSource("accepted")
    void accepts(String name) {
        var input = read(name);

        assertThatCode(() -> JSON5.parse(input)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("rejected")
    void rejects(String name) {
        var input = read(name);

        assertThatExceptionOfType(JsonParseException.class).isThrownBy(() -> JSON5.parse(input));
    }

    /// The JSON documents of the suite read the same as JSON and as JSON5.
    @ParameterizedTest
    @MethodSource("json")
    void readsJsonAlike(String name) {
        var input = read(name);

        assertThat(JSON5.parse(input).root())
                .isEqualTo(new JsonParser().parse(input).root());
    }

    @Test
    void findsEveryKindOfDocument() {
        assertThat(accepted()).hasSizeGreaterThan(70);
        assertThat(rejected()).hasSizeGreaterThan(30);
        assertThat(files()).containsAll(REJECTED_VALID);
    }

    private static Stream<String> files() {
        try (var files = Files.walk(TESTS)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(file -> TESTS.relativize(file).toString().replace('\\', '/'))
                    .filter(name -> name.matches(".*\\.(json|json5|js|txt)"))
                    .sorted()
                    .toList()
                    .stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] read(String name) {
        try {
            return Files.readAllBytes(TESTS.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path tests() {
        try {
            return Path.of(Objects.requireNonNull(Json5TestSuiteTest.class.getResource("/json5-tests/arrays"))
                            .toURI())
                    .resolveSibling("");
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
