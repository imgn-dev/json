package be.imgn.json.internal;

import static java.util.Objects.requireNonNull;

import be.imgn.json.JsonMappingException;
import be.imgn.json.JsonParser;
import be.imgn.json.JsonPosition;
import be.imgn.json.JsonType;
import be.imgn.json.JsonValue;
import be.imgn.json.bind.UnknownMembers;
import be.imgn.json.stream.JsonStreamReader;
import be.imgn.json.stream.JsonStreamWriter;
import java.lang.invoke.MethodHandles;
import org.jspecify.annotations.Nullable;

/// Lets the exported packages of the module reach what each keeps package-private from the others, which Java cannot
/// grant from one package to another, as the shared secrets of the JDK do: each package registers its side when one of
/// its classes is initialized, and a side asked for before it is registered has that class initialized first.
public final class Access {

    private static volatile @Nullable Trees trees;
    private static volatile @Nullable Streams streams;

    private Access() {}

    /// Registers what `be.imgn.json` gives the other packages; [JsonParser] does, when it is initialized.
    public static void register(Trees access) {
        trees = access;
    }

    /// Registers what `be.imgn.json.stream` gives the other packages; [JsonStreamReader] does, when it is
    /// initialized.
    public static void register(Streams access) {
        streams = access;
    }

    /// Returns what `be.imgn.json` gives the other packages.
    public static Trees trees() {
        var access = trees;
        if (access == null) {
            initialize(JsonParser.class);
            access = requireNonNull(trees, "be.imgn.json is not registered");
        }
        return access;
    }

    /// Returns what `be.imgn.json.stream` gives the other packages.
    public static Streams streams() {
        var access = streams;
        if (access == null) {
            initialize(JsonStreamReader.class);
            access = requireNonNull(streams, "be.imgn.json.stream is not registered");
        }
        return access;
    }

    private static void initialize(Class<?> type) {
        try {
            MethodHandles.lookup().ensureInitialized(type);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /// What `be.imgn.json` gives the other packages: the values of a tape, their types, and its exceptions.
    public interface Trees {

        /// Returns the value at an index of a tape.
        JsonValue value(Tape tape, int index);

        /// Returns the type of the value of a tape entry.
        JsonType type(long entry);

        /// Returns a type with its article, as error messages name it: `an object`.
        String description(JsonType type);

        /// Returns an error of binding.
        JsonMappingException mappingError(String message, JsonPosition position, String path);

        /// Writes a value.
        void write(Output output, JsonValue value);
    }

    /// What `be.imgn.json.stream` gives the other packages: its reader and writer, over the module's internals.
    public interface Streams {

        /// Returns a reader of a tape.
        JsonStreamReader reader(Tape tape, UnknownMembers unknownMembers);

        /// Returns a writer to an output.
        JsonStreamWriter writer(Output output, boolean writeNullMembers);

        /// Returns a writer to an output of its own.
        JsonStreamWriter writer(int indent, boolean writeNullMembers);
    }
}
