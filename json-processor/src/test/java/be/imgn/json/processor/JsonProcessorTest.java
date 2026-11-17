package be.imgn.json.processor;

import static be.imgn.gaia.processing.testing.CompilationAssert.assertThat;
import static be.imgn.gaia.processing.testing.ProcessorAssert.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import be.imgn.gaia.processing.testing.Compilation;
import java.util.stream.Stream;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JsonProcessorTest {

    @Test
    void isRegistered() {
        assertThat(JsonProcessor.class).isRegistered();
    }

    @Test
    void generatesBindersWithoutWarnings() {
        var compilation = Compiler.compile(Compiler.records());

        assertThat(compilation).succeeded().hadNoWarnings();
        assertThat(compilation)
                .generatedSourceFile("be.imgn.json.tests.$Order$$JsonBinder")
                .contains("public final class $Order$$JsonBinder implements JsonBinder<Order>")
                .contains("private static final JsonNames ORDER = JsonNames.of(\"id\", \"customer\", \"lines\","
                        + " \"status\", \"shipped\", \"notes\", \"tags\", \"total-price\");");
        assertThat(compilation)
                .generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/services/be.imgn.json.spi.JsonBinder")
                .contains("be.imgn.json.tests.$Order$$JsonBinder")
                .contains("be.imgn.json.tests.$Shape$$JsonBinder");
    }

    static Stream<Arguments> invalidRecords() {
        return Stream.of(
                arguments("class Bad {}", "@JsonRecord must annotate a record or a sealed interface"),
                arguments("record Bad<T>(T value) {}", "A @JsonRecord type cannot be generic"),
                arguments(
                        "record Bad(Object value) {}",
                        "Cannot bind the type java.lang.Object: see @JsonRecord for the types it binds"),
                arguments("record Bad(int[] values) {}", "Cannot bind the type int[]"),
                arguments(
                        "record Bad(java.util.Map<Integer, String> map) {}",
                        "Cannot bind the map java.util.Map<java.lang.Integer,java.lang.String>: its keys must be"
                                + " strings"),
                arguments("record Bad(java.util.List<?> list) {}", "Cannot bind the type ?"),
                arguments(
                        "record Bad(int a, @JsonName(\"a\") int b) {}", "Another component already has the name \"a\""),
                arguments(
                        "record Bad(@org.jspecify.annotations.Nullable int a) {}",
                        "A component of a primitive type cannot be @Nullable: use its boxed type"),
                arguments("record Bad(E e) { enum E {} }", "it has no constant"),
                arguments(
                        "record Bad(E e) { enum E { A, @JsonName(\"A\") B } }",
                        "Another constant already has the name \"A\""),
                arguments(
                        "record Bad(Bad.Hidden h) { private record Hidden(int a) {} }",
                        "it is not accessible from the package"));
    }

    @ParameterizedTest
    @MethodSource("invalidRecords")
    void rejectsRecord(String declaration, String error) {
        assertThat(compile(declaration)).failed().hadErrorContaining(error);
    }

    static Stream<Arguments> invalidSealedInterfaces() {
        return Stream.of(
                arguments("""
                        @JsonWrapper @JsonTagMember sealed interface Bad permits A {}
                        record A() implements Bad {}""", "cannot be both @JsonWrapper and @JsonTagMember"),
                arguments("""
                        sealed interface Bad permits A, B {}
                        record A() implements Bad {}
                        @JsonName("A") record B() implements Bad {}""", "The tag \"A\" already names example.A in Bad"),
                arguments("""
                        sealed interface Bad permits A, C {}
                        record A() implements Bad {}
                        final class C implements Bad {}""", "example.C, which is neither a record nor a sealed"),
                arguments("""
                        sealed interface Bad permits A {}
                        record A(int type) implements Bad {}""", "is named as the tag member \"type\" of Bad"));
    }

    @ParameterizedTest
    @MethodSource("invalidSealedInterfaces")
    void rejectsSealedInterface(String declarations, String error) {
        assertThat(compile(declarations)).failed().hadErrorContaining(error);
    }

    private static Compilation compile(String declarations) {
        return Compiler.compile("example.Bad", """
                package example;

                import be.imgn.json.bind.JsonName;
                import be.imgn.json.bind.JsonRecord;
                import be.imgn.json.bind.JsonTagMember;
                import be.imgn.json.bind.JsonWrapper;

                @JsonRecord
                %s
                """.formatted(declarations));
    }
}
