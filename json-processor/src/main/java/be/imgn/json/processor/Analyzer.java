package be.imgn.json.processor;

import static java.util.Map.entry;

import be.imgn.gaia.processing.MoreElements;
import be.imgn.gaia.processing.ProcessingContext;
import be.imgn.gaia.processing.ProcessingException;
import be.imgn.gaia.processing.source.NameAllocator;
import be.imgn.json.processor.Binder.Body;
import be.imgn.json.processor.Binder.Call;
import be.imgn.json.processor.Binder.Codec;
import be.imgn.json.processor.Binder.Component;
import be.imgn.json.processor.Binder.Constant;
import be.imgn.json.processor.Binder.Container;
import be.imgn.json.processor.Binder.ContainerBody;
import be.imgn.json.processor.Binder.Direct;
import be.imgn.json.processor.Binder.EnumBody;
import be.imgn.json.processor.Binder.Leaf;
import be.imgn.json.processor.Binder.Parsed;
import be.imgn.json.processor.Binder.Primitive;
import be.imgn.json.processor.Binder.RecordBody;
import be.imgn.json.processor.Binder.SealedBody;
import be.imgn.json.processor.Binder.Tag;
import be.imgn.json.processor.Binder.Value;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.jspecify.annotations.Nullable;

/// Works out the binder of a type: how each value converts, and the methods to generate for the records, enums, sealed
/// interfaces and containers it refers to. Each invalid element is reported as an error.
final class Analyzer {

    private static final Set<TypeKind> PRIMITIVES = Set.of(
            TypeKind.INT,
            TypeKind.LONG,
            TypeKind.SHORT,
            TypeKind.BYTE,
            TypeKind.DOUBLE,
            TypeKind.FLOAT,
            TypeKind.BOOLEAN);
    private static final Map<String, TypeKind> BOXES = Map.of(
            "java.lang.Integer", TypeKind.INT,
            "java.lang.Long", TypeKind.LONG,
            "java.lang.Short", TypeKind.SHORT,
            "java.lang.Byte", TypeKind.BYTE,
            "java.lang.Double", TypeKind.DOUBLE,
            "java.lang.Float", TypeKind.FLOAT,
            "java.lang.Boolean", TypeKind.BOOLEAN);
    private static final Map<String, String> DIRECT = Map.of(
            "java.lang.String", "readString",
            "java.math.BigInteger", "readBigInteger",
            "java.math.BigDecimal", "readBigDecimal");
    private static final Map<String, String> PARSED = Map.ofEntries(
            entry("java.time.Instant", "readInstant"),
            entry("java.time.OffsetDateTime", "readOffsetDateTime"),
            entry("java.time.LocalDate", "readLocalDate"),
            entry("java.time.LocalTime", "readLocalTime"),
            entry("java.time.LocalDateTime", "readLocalDateTime"),
            entry("java.time.Duration", "readDuration"),
            entry("java.util.UUID", "readUuid"),
            entry("java.net.URI", "readUri"));
    private static final Set<String> VALUES = Stream.of(
                    "JsonValue", "JsonObject", "JsonArray", "JsonString", "JsonNumber", "JsonBoolean", "JsonNull")
            .map(name -> "be.imgn.json." + name)
            .collect(Collectors.toUnmodifiableSet());
    private static final Map<String, Container> CONTAINERS =
            Map.of("java.util.List", Container.LIST, "java.util.Set", Container.SET, "java.util.Map", Container.MAP);
    // The names the generated methods use besides those of the components.
    private static final List<String> RESERVED_VARIABLES = List.of("reader", "writer", "value", "mark", "e");

    private final ProcessingContext context;
    private final String packageName;
    // The binder's own methods and constants are reserved, so that no generated method takes their names.
    private final NameAllocator methods = new NameAllocator();
    private final NameAllocator constants = new NameAllocator();
    private final Map<String, Call> codecs = new HashMap<>();
    private final Map<String, List<Component>> components = new HashMap<>();
    private final Queue<Runnable> pending = new ArrayDeque<>();
    private final List<Body> bodies = new ArrayList<>();

    Analyzer(ProcessingContext context, String packageName) {
        this.context = context;
        this.packageName = packageName;
        Stream.of("type", "read", "write").forEach(methods::newName);
    }

    /// Works out the binder of a record or a sealed interface, or returns empty if an error was reported.
    Optional<Binder> analyze(TypeElement root) {
        var errors = context.diagnostics().errorCount();
        var codec = named(root, root);
        while (!pending.isEmpty()) {
            pending.remove().run();
        }
        return context.diagnostics().errorCount() == errors
                ? Optional.of(new Binder(root, codec, bodies))
                : Optional.empty();
    }

    private Codec codec(TypeMirror type, Element where) {
        if (PRIMITIVES.contains(type.getKind())) {
            return new Primitive(type.getKind());
        }
        if (type.getKind() != TypeKind.DECLARED) {
            throw unsupported(type, where);
        }
        var declared = (DeclaredType) type;
        var element = MoreElements.asType(declared.asElement());
        var scalar = scalar(Types.qualifiedName(element));
        if (scalar != null) {
            return scalar;
        }
        var container = CONTAINERS.get(Types.qualifiedName(element));
        if (container != null) {
            return container(container, declared, where);
        }
        var kind = element.getKind();
        if (kind == ElementKind.RECORD || kind == ElementKind.ENUM || Types.isSealedInterface(element)) {
            return named(element, where);
        }
        throw unsupported(type, where);
    }

    /// Returns how a type of the JDK or a JSON value converts, or null if it is none of them.
    private static @Nullable Codec scalar(String name) {
        Codec codec = null;
        if (BOXES.containsKey(name)) {
            codec = new Primitive(BOXES.get(name));
        } else if (DIRECT.containsKey(name)) {
            codec = new Direct(DIRECT.get(name));
        } else if (PARSED.containsKey(name)) {
            codec = new Parsed(PARSED.get(name));
        } else if (VALUES.contains(name)) {
            codec = new Value(name);
        }
        return codec;
    }

    private Codec container(Container kind, DeclaredType type, Element where) {
        var arguments = type.getTypeArguments();
        if (arguments.isEmpty()) {
            throw new ProcessingException("Cannot bind the raw type " + type, where);
        }
        if (kind == Container.MAP && !isString(arguments.getFirst())) {
            throw new ProcessingException("Cannot bind the map " + type + ": its keys must be strings", where);
        }
        var element = arguments.getLast();
        var codec = codec(element, where);
        var key = type.toString();
        var existing = codecs.get(key);
        if (existing != null) {
            return existing;
        }
        var suffix = suffix(type);
        var call = new Call(methods.newName("read" + suffix), methods.newName("write" + suffix));
        codecs.put(key, call);
        bodies.add(new ContainerBody(kind, type, element, codec, call.read(), call.write()));
        return call;
    }

    /// Returns the methods that read and write a record, an enum or a sealed interface, whose body is analyzed once.
    private Call named(TypeElement type, Element where) {
        if (!type.getTypeParameters().isEmpty()) {
            throw new ProcessingException("Cannot bind the generic type " + type.getQualifiedName(), where);
        }
        checkAccessible(type, where);
        var existing = codecs.get(Types.qualifiedName(type));
        if (existing != null) {
            return existing;
        }
        var simpleName = type.getSimpleName().toString();
        var call = new Call(methods.newName("read" + simpleName), methods.newName("write" + simpleName));
        codecs.put(Types.qualifiedName(type), call);
        pending.add(() -> context.forEach(List.of(type), element -> {
            if (element.getKind() == ElementKind.RECORD) {
                bodies.add(new RecordBody(
                        element, call.read(), call.write(), constant(simpleName), components(element), null));
            } else if (element.getKind() == ElementKind.ENUM) {
                bodies.add(new EnumBody(element, call.read(), call.write(), constants(element)));
            } else {
                bodies.add(sealed(element, call));
            }
        }));
        return call;
    }

    private List<Component> components(TypeElement record) {
        var existing = components.get(Types.qualifiedName(record));
        if (existing != null) {
            return existing;
        }
        var variables = new NameAllocator();
        RESERVED_VARIABLES.forEach(variables::newName);
        var names = new HashSet<String>();
        var list = new ArrayList<Component>();
        context.forEach(record.getRecordComponents(), component -> {
            var accessor = component.getSimpleName().toString();
            var name = jsonName(component).orElse(accessor);
            if (!names.add(name)) {
                throw new ProcessingException("Another component already has the name \"" + name + "\"", component);
            }
            var nullable = Types.isNullable(component);
            if (nullable && component.asType().getKind().isPrimitive()) {
                throw new ProcessingException(
                        "A component of a primitive type cannot be @Nullable: use its boxed type", component);
            }
            var codec = codec(component.asType(), component);
            list.add(new Component(name, accessor, variables.newName(accessor), component.asType(), nullable, codec));
        });
        components.put(Types.qualifiedName(record), list);
        return list;
    }

    private List<Constant> constants(TypeElement type) {
        var names = new HashSet<String>();
        var list = new ArrayList<Constant>();
        var elements = type.getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.ENUM_CONSTANT)
                .toList();
        if (elements.isEmpty()) {
            throw new ProcessingException(
                    "Cannot bind the enum " + type.getQualifiedName() + ": it has no constant", type);
        }
        context.forEach(elements, constant -> {
            var name = jsonName(constant).orElse(constant.getSimpleName().toString());
            if (!names.add(name)) {
                throw new ProcessingException("Another constant already has the name \"" + name + "\"", constant);
            }
            list.add(new Constant(constant.getSimpleName().toString(), name));
        });
        return list;
    }

    private SealedBody sealed(TypeElement type, Call call) {
        var wrapper = context.annotation(type, Types.JSON_WRAPPER).isPresent();
        var tagMember = context.annotation(type, Types.JSON_TAG_MEMBER);
        if (wrapper && tagMember.isPresent()) {
            throw new ProcessingException(
                    "A sealed interface cannot be both @JsonWrapper and @JsonTagMember", type, Types.JSON_WRAPPER);
        }
        var member = wrapper
                ? ""
                : tagMember.map(annotation -> annotation.string("value")).orElse("type");
        var simpleName = type.getSimpleName().toString();
        var tags = new HashMap<String, TypeElement>();
        var leaves = new ArrayList<Leaf>();
        context.forEach(leaves(type, type, new LinkedHashMap<>()).values(), record -> {
            var tag = jsonName(record).orElse(record.getSimpleName().toString());
            var other = tags.putIfAbsent(tag, record);
            if (other != null) {
                throw new ProcessingException(
                        "The tag \"" + tag + "\" already names " + other.getQualifiedName() + " in " + simpleName,
                        record);
            }
            leaves.add(new Leaf(record, tag, wrapper ? named(record, record) : tagged(record, type, member, tag)));
        });
        return new SealedBody(
                type,
                call.read(),
                call.write(),
                wrapper,
                member,
                wrapper ? "" : constants.newName(upperSnake(simpleName) + "_TAG"),
                constants.newName(upperSnake(simpleName) + "_TAGS"),
                leaves);
    }

    /// Returns the methods that read and write a record of a sealed interface with a tag member.
    private Call tagged(TypeElement record, TypeElement sealed, String member, String tag) {
        var name = record.getSimpleName() + "In" + sealed.getSimpleName();
        var call = new Call(methods.newName("read" + name), methods.newName("write" + name));
        var list = components(record);
        list.stream()
                .filter(component -> component.name().equals(member))
                .findFirst()
                .ifPresent(component -> context.diagnostics()
                        .on(record)
                        .error("The component " + component.accessor() + " is named as the tag member \"" + member
                                + "\" of " + sealed.getSimpleName()
                                + ": rename it with @JsonName, or the tag member with @JsonTagMember"));
        bodies.add(new RecordBody(record, call.read(), call.write(), constant(name), list, new Tag(member, tag)));
        return call;
    }

    /// Collects the records of a sealed interface, through the sealed interfaces it permits.
    private Map<String, TypeElement> leaves(TypeElement type, TypeElement root, Map<String, TypeElement> leaves) {
        for (var permitted : type.getPermittedSubclasses()) {
            var element = MoreElements.asType(((DeclaredType) permitted).asElement());
            if (element.getKind() == ElementKind.RECORD) {
                if (!element.getTypeParameters().isEmpty()) {
                    throw new ProcessingException("Cannot bind the generic record " + element.getQualifiedName(), root);
                }
                checkAccessible(element, root);
                leaves.putIfAbsent(Types.qualifiedName(element), element);
            } else if (Types.isSealedInterface(element)) {
                leaves(element, root, leaves);
            } else {
                throw new ProcessingException(
                        type.getSimpleName() + " permits " + element.getQualifiedName()
                                + ", which is neither a record nor a sealed interface: it cannot be bound",
                        root);
            }
        }
        return leaves;
    }

    private Optional<String> jsonName(Element element) {
        return context.annotation(element, Types.JSON_NAME).map(annotation -> annotation.string("value"));
    }

    private String constant(String name) {
        return constants.newName(upperSnake(name));
    }

    private void checkAccessible(TypeElement type, Element where) {
        if (type.getNestingKind() == NestingKind.LOCAL || !MoreElements.isAccessible(type, packageName)) {
            throw new ProcessingException(
                    "Cannot bind " + type.getQualifiedName() + ": it is not accessible from the package "
                            + (packageName.isEmpty() ? "(unnamed)" : packageName),
                    where);
        }
    }

    private static boolean isString(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED
                && MoreElements.asType(((DeclaredType) type).asElement())
                        .getQualifiedName()
                        .contentEquals("java.lang.String");
    }

    /// Returns a name for the methods of a container type: `ListOfOrder`, `MapOfListOfString`.
    private static String suffix(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) {
            return type.getKind().name().charAt(0)
                    + type.getKind().name().substring(1).toLowerCase(Locale.ROOT);
        }
        var declared = (DeclaredType) type;
        var name = declared.asElement().getSimpleName().toString();
        var arguments = declared.getTypeArguments();
        return arguments.isEmpty() ? name : name + "Of" + suffix(arguments.getLast());
    }

    /// Returns a Java name in upper snake case: `OrderLine` is `ORDER_LINE`.
    private static String upperSnake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private static ProcessingException unsupported(TypeMirror type, Element where) {
        return new ProcessingException(
                "Cannot bind the type " + type + ": see @JsonRecord for the types it binds", where);
    }
}
