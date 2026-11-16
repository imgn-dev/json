package be.imgn.json.processor;

import be.imgn.gaia.processing.testing.Compilation;
import be.imgn.gaia.processing.testing.TestCompiler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import javax.tools.JavaFileObject;

/// Compiles sources with the processor, and loads what they compile to.
final class Compiler {

    private static final String CLASS_OUTPUT = "/CLASS_OUTPUT/";

    private Compiler() {}

    /// Compiles sources with the processor, failing on any warning. The processing lint is off: the processor does
    /// not claim the annotations it processes, which leaves them to other processors.
    static Compilation compile(List<JavaFileObject> sources) {
        return TestCompiler.javac()
                .withProcessors(new JsonProcessor())
                .withOptions("-Xlint:all,-processing", "-Werror")
                .compile(sources);
    }

    /// Compiles sources, each given as its qualified name followed by its text.
    static Compilation compile(String... namesAndTexts) {
        var sources = new JavaFileObject[namesAndTexts.length / 2];
        for (var i = 0; i < sources.length; i++) {
            sources[i] = TestCompiler.source(namesAndTexts[2 * i], namesAndTexts[2 * i + 1]);
        }
        return compile(List.of(sources));
    }

    /// Returns the sources of the records of the test resources, in `records/`.
    static List<JavaFileObject> records() {
        var root = resource("/records");
        try (var files = Files.walk(root)) {
            return files.filter(file -> file.toString().endsWith(".java"))
                    .map(file -> TestCompiler.source(
                            root.relativize(file).toString().replace('/', '.').replaceFirst("\\.java$", ""),
                            read(file)))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path resource(String name) {
        try {
            return Path.of(
                    Objects.requireNonNull(Compiler.class.getResource(name)).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Writes the class files and resources of a compilation to a directory, and returns a class loader that loads
    /// them, as the class path of an application would.
    static ClassLoader load(Compilation compilation, Path directory) {
        compilation.generatedFiles().stream()
                .filter(file -> file.getName().startsWith(CLASS_OUTPUT))
                .forEach(file -> {
                    var target = directory.resolve(file.getName().substring(CLASS_OUTPUT.length()));
                    try (var in = file.openInputStream()) {
                        Files.createDirectories(target.getParent());
                        Files.copy(in, target);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        try {
            return new URLClassLoader(new URL[] {directory.toUri().toURL()}, Compiler.class.getClassLoader());
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }
}
