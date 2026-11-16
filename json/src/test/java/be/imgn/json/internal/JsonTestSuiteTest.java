package be.imgn.json.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/// Runs the parsing tests of [JSONTestSuite](https://github.com/nst/JSONTestSuite): every `y_` document is accepted,
/// except those with duplicate member names, every `n_` document is rejected, and every `i_` document, whose outcome
/// RFC 8259 leaves to the implementation, has the outcome decided here.
class JsonTestSuiteTest {

    private static final Path TESTS = tests();

    /// The `i_` documents that are accepted: numbers are kept exact whatever their size, and 500 nested arrays are
    /// within the default depth. All the others are rejected: unpaired surrogates, invalid UTF-8, UTF-16, and byte
    /// order marks.
    private static final Set<String> ACCEPTED_IMPLEMENTATION_DEFINED = Set.of(
            "i_number_double_huge_neg_exp.json",
            "i_number_huge_exp.json",
            "i_number_neg_int_huge_exp.json",
            "i_number_pos_double_huge_exp.json",
            "i_number_real_neg_overflow.json",
            "i_number_real_pos_overflow.json",
            "i_number_real_underflow.json",
            "i_number_too_big_neg_int.json",
            "i_number_too_big_pos_int.json",
            "i_number_very_big_negative_int.json",
            "i_structure_500_nested_arrays.json");

    /// The `y_` documents that are rejected: RFC 8259 only says that the names of an object should be unique, but this
    /// parser requires it, as parsers that keep different duplicates read the same document differently.
    private static final Set<String> REJECTED_VALID =
            Set.of("y_object_duplicated_key.json", "y_object_duplicated_key_and_value.json");

    static Stream<String> accepted() {
        return files().filter(name -> (name.startsWith("y_") && !REJECTED_VALID.contains(name))
                || ACCEPTED_IMPLEMENTATION_DEFINED.contains(name));
    }

    static Stream<String> rejected() {
        return files().filter(name -> name.startsWith("n_")
                || REJECTED_VALID.contains(name)
                || (name.startsWith("i_") && !ACCEPTED_IMPLEMENTATION_DEFINED.contains(name)));
    }

    @ParameterizedTest
    @MethodSource("accepted")
    void accepts(String name) {
        var input = read(name);

        assertThatCode(() -> TapeBuilder.build(input, Limits.DEFAULTS)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("rejected")
    void rejects(String name) {
        var input = read(name);

        assertThatExceptionOfType(TapeException.class).isThrownBy(() -> TapeBuilder.build(input, Limits.DEFAULTS));
    }

    @Test
    void decidesEveryImplementationDefinedDocument() {
        assertThat(files().filter(name -> name.startsWith("i_"))).containsAll(ACCEPTED_IMPLEMENTATION_DEFINED);
        assertThat(files()).containsAll(REJECTED_VALID);
        assertThat(files().filter(name -> !name.matches("[yni]_.*\\.json"))).isEmpty();
    }

    private static Stream<String> files() {
        try (var files = Files.list(TESTS)) {
            List<String> names =
                    files.map(file -> file.getFileName().toString()).sorted().toList();
            return names.stream();
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
            return Path.of(Objects.requireNonNull(JsonTestSuiteTest.class.getResource("/JSONTestSuite/test_parsing"))
                    .toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
