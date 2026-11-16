package be.imgn.json.processor;

import java.util.List;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.jspecify.annotations.Nullable;

/// The binder to generate for a type: how its root value converts, and the methods and constants it needs.
///
/// @param root the annotated type
/// @param codec how the root value converts
/// @param bodies the methods to generate, each with the constants it uses
record Binder(TypeElement root, Codec codec, List<Body> bodies) {

    Binder {
        bodies = List.copyOf(bodies);
    }

    /// Returns the elements the binder is generated from: the types it reads and writes.
    Element[] elements() {
        // A container is generated from no type of its own: the root stands for it.
        return Stream.concat(Stream.of(root), bodies.stream().map(body -> switch (body) {
                    case RecordBody record -> record.type();
                    case EnumBody enumBody -> enumBody.type();
                    case SealedBody sealed -> sealed.type();
                    case ContainerBody ignored -> root;
                }))
                .distinct()
                .toArray(Element[]::new);
    }

    /// How a value converts, in the expression that reads it and the statement that writes it.
    sealed interface Codec {}

    /// A primitive type or its box, read and written by the method of the stream for its primitive type.
    ///
    /// @param kind the primitive type
    record Primitive(TypeKind kind) implements Codec {}

    /// A type read by a method of the stream, and written as it is: `String`, `BigInteger`, `BigDecimal`.
    ///
    /// @param method the method of the reader, such as `readString`
    record Direct(String method) implements Codec {}

    /// A type read from a string by a method of `JsonBindings`, and written with its `toString()`.
    ///
    /// @param method the method of `JsonBindings`, such as `readInstant`
    record Parsed(String method) implements Codec {}

    /// A `JsonValue` type.
    ///
    /// @param type the qualified name of the type
    record Value(String type) implements Codec {}

    /// A type converted by methods of the binder.
    ///
    /// @param read the name of the method that reads it
    /// @param write the name of the method that writes it
    record Call(String read, String write) implements Codec {}

    /// A method to generate, with the constants it uses.
    sealed interface Body {}

    /// The methods that read and write a record.
    ///
    /// @param type the record
    /// @param read the name of the method that reads it
    /// @param write the name of the method that writes it
    /// @param names the name of the constant of its member names
    /// @param components its components, in order
    /// @param tag the tag member it is read and written with, as a record of a sealed interface; null otherwise
    record RecordBody(
            TypeElement type,
            String read,
            String write,
            String names,
            List<Component> components,
            @Nullable Tag tag) implements Body {

        RecordBody {
            components = List.copyOf(components);
        }
    }

    /// The tag member of a record of a sealed interface: skipped when read, written first.
    ///
    /// @param member the name of the tag member
    /// @param value the tag of the record
    record Tag(String member, String value) {}

    /// A component of a record.
    ///
    /// @param name the name of its member
    /// @param accessor the name of its accessor
    /// @param variable the name of the local variable that holds it
    /// @param type its type
    /// @param nullable whether it may be null
    /// @param codec how its value converts
    record Component(String name, String accessor, String variable, TypeMirror type, boolean nullable, Codec codec) {}

    /// The methods that read and write an enum.
    ///
    /// @param type the enum
    /// @param read the name of the method that reads it
    /// @param write the name of the method that writes it
    /// @param constants its constants, in order
    record EnumBody(TypeElement type, String read, String write, List<Constant> constants) implements Body {

        EnumBody {
            constants = List.copyOf(constants);
        }
    }

    /// A constant of an enum.
    ///
    /// @param constant its Java name
    /// @param name the string it reads from and writes as
    record Constant(String constant, String name) {}

    /// The methods that read and write a sealed interface of records.
    ///
    /// @param type the sealed interface
    /// @param read the name of the method that reads it
    /// @param write the name of the method that writes it
    /// @param wrapper whether it is laid out as a wrapper object, rather than with a tag member
    /// @param member the name of the tag member, or empty for a wrapper
    /// @param memberConstant the name of the constant of the tag member's name, or empty for a wrapper
    /// @param tags the name of the constant of the tags
    /// @param leaves its records, in order
    record SealedBody(
            TypeElement type,
            String read,
            String write,
            boolean wrapper,
            String member,
            String memberConstant,
            String tags,
            List<Leaf> leaves)
            implements Body {

        SealedBody {
            leaves = List.copyOf(leaves);
        }
    }

    /// A record of a sealed interface.
    ///
    /// @param record the record
    /// @param tag its tag
    /// @param codec how it converts, as a record of the sealed interface
    record Leaf(TypeElement record, String tag, Call codec) {}

    /// The methods that read and write a list, a set or a map.
    ///
    /// @param kind which of them
    /// @param type its type
    /// @param element the type of its elements, or of the values of the map
    /// @param codec how its elements convert
    /// @param read the name of the method that reads it
    /// @param write the name of the method that writes it
    record ContainerBody(Container kind, TypeMirror type, TypeMirror element, Codec codec, String read, String write)
            implements Body {}

    /// The containers bound.
    enum Container {
        /// `java.util.List`, from an array.
        LIST,
        /// `java.util.Set`, from an array without duplicates.
        SET,
        /// `java.util.Map` with `String` keys, from an object.
        MAP
    }
}
