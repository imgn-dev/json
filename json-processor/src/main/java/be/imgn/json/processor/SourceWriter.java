package be.imgn.json.processor;

import be.imgn.gaia.processing.SourceScope;
import be.imgn.json.processor.Binder.Body;
import be.imgn.json.processor.Binder.Call;
import be.imgn.json.processor.Binder.Codec;
import be.imgn.json.processor.Binder.Component;
import be.imgn.json.processor.Binder.Container;
import be.imgn.json.processor.Binder.ContainerBody;
import be.imgn.json.processor.Binder.Direct;
import be.imgn.json.processor.Binder.EnumBody;
import be.imgn.json.processor.Binder.Parsed;
import be.imgn.json.processor.Binder.Primitive;
import be.imgn.json.processor.Binder.RecordBody;
import be.imgn.json.processor.Binder.SealedBody;
import be.imgn.json.processor.Binder.Value;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/// Writes the source of a binder, after its package declaration and imports.
final class SourceWriter {

    private static final String INDENT = "    ";

    private final SourceScope scope;
    private final Binder binder;
    private final String names;
    private final String bindings;
    private final String reader;
    private final String writer;

    SourceWriter(SourceScope scope, Binder binder) {
        this.scope = scope;
        this.binder = binder;
        this.names = scope.name("be.imgn.json.JsonNames");
        this.bindings = scope.name("be.imgn.json.JsonBindings");
        this.reader = scope.name("be.imgn.json.JsonStreamReader");
        this.writer = scope.name("be.imgn.json.JsonStreamWriter");
    }

    String write() {
        var root = scope.name(binder.root());
        var constants = new StringBuilder();
        var methods = new StringBuilder();
        for (var body : binder.bodies()) {
            constants.append(constants(body));
            methods.append(methods(body));
        }
        return """
                /// Reads and writes `%1$s` as JSON.
                %2$s
                public final class %3$s implements %4$s<%1$s> {
                %5$s
                    /// Creates the binder; the service loader does.
                    public %3$s() {}

                    @Override
                    public Class<%1$s> type() {
                        return %1$s.class;
                    }

                    @Override
                    public %1$s read(%6$s reader) {
                        return %7$s;
                    }

                    @Override
                    public void write(%8$s writer, %1$s value) {
                        %9$s
                    }
                %10$s}
                """.formatted(
                        root,
                        scope.generated(),
                        scope.simpleName(),
                        scope.name("be.imgn.json.JsonBinder"),
                        constants,
                        reader,
                        readExpression(binder.codec()),
                        writer,
                        writeStatement(binder.codec(), "value"),
                        methods);
    }

    private String constants(Body body) {
        return switch (body) {
            case RecordBody record -> {
                var list = new ArrayList<String>();
                record.components().forEach(component -> list.add(component.name()));
                if (record.tag() != null) {
                    list.add(record.tag().member());
                }
                yield constant(record.names(), list);
            }
            case SealedBody sealed -> {
                var tags = sealed.leaves().stream().map(Binder.Leaf::tag).toList();
                yield (sealed.wrapper() ? "" : constant(sealed.memberConstant(), List.of(sealed.member())))
                        + constant(sealed.tags(), tags);
            }
            case EnumBody ignored -> "";
            case ContainerBody ignored -> "";
        };
    }

    private String constant(String name, List<String> values) {
        return INDENT + "private static final " + names + " " + name + " = " + names + ".of("
                + values.stream().map(SourceWriter::literal).collect(Collectors.joining(", ")) + ");\n";
    }

    private String methods(Body body) {
        return switch (body) {
            case RecordBody record -> readRecord(record) + writeRecord(record);
            case EnumBody enumBody -> readEnum(enumBody) + writeEnum(enumBody);
            case SealedBody sealed ->
                sealed.wrapper()
                        ? readWrapper(sealed) + writeWrapper(sealed)
                        : readTagged(sealed) + writeTagged(sealed);
            case ContainerBody container -> readContainer(container) + writeContainer(container);
        };
    }

    // Records.

    private String readRecord(RecordBody record) {
        var type = scope.name(record.type());
        var components = record.components();
        var words = (components.size() + Long.SIZE - 1) / Long.SIZE;
        var code = new Code();
        code.line("var mark = reader.mark();");
        code.line("reader.beginObject();");
        for (var word = 0; word < words; word++) {
            code.line("var seen" + word + " = 0L;");
        }
        for (var component : components) {
            code.line(
                    typeName(component.type()) + " " + component.variable() + " = " + initial(component.type()) + ";");
        }
        code.line("while (reader.hasNextMember()) {");
        code.line("    switch (reader.name(" + record.names() + ")) {");
        for (var i = 0; i < components.size(); i++) {
            var component = components.get(i);
            code.line("        case " + i + " -> {");
            code.line("            " + component.variable() + " = " + readComponent(component) + ";");
            code.line("            seen" + (i / Long.SIZE) + " |= " + bit(i % Long.SIZE) + ";");
            code.line("        }");
        }
        if (record.tag() != null) {
            code.line("        case " + components.size() + " -> reader.skipValue();");
        }
        code.line("        default -> reader.skipUnknownMember(" + record.names() + ");");
        code.line("    }");
        code.line("}");
        for (var word = 0; word < words; word++) {
            var mask = 0L;
            for (var i = word * Long.SIZE; i < Math.min(components.size(), (word + 1) * Long.SIZE); i++) {
                if (!components.get(i).nullable()) {
                    mask |= 1L << (i % Long.SIZE);
                }
            }
            if (mask != 0) {
                var hex = "0x" + Long.toHexString(mask) + "L";
                code.line("if ((seen" + word + " & " + hex + ") != " + hex + ") {");
                code.line("    throw reader.mappingError(mark, \"missing member \\\"\" + " + record.names() + ".get("
                        + (word * Long.SIZE) + " + Long.numberOfTrailingZeros(~seen" + word + " & " + hex
                        + ")) + \"\\\" of " + record.type().getSimpleName() + "\");");
                code.line("}");
            }
        }
        var arguments = components.stream().map(Component::variable).collect(Collectors.joining(", "));
        code.line("try {");
        code.line("    return new " + type + "(" + arguments + ");");
        code.line("} catch (RuntimeException e) {");
        code.line("    throw reader.mappingError(mark, \"" + record.type().getSimpleName()
                + " rejects the members: \" + e.getMessage(), e);");
        code.line("}");
        return method(type, record.read(), reader + " reader", code);
    }

    private String readComponent(Component component) {
        var read = readExpression(component.codec());
        if (!component.nullable() || (component.codec() instanceof Value(var type) && type.equals(Types.JSON_VALUE))) {
            return read;
        }
        return "reader.readNull() ? null : " + read;
    }

    private String writeRecord(RecordBody record) {
        var type = scope.name(record.type());
        var code = new Code();
        code.line("writer.beginObject();");
        var components = record.components();
        if (record.tag() != null) {
            code.line("writer.name(" + record.names() + ", " + components.size() + ").value("
                    + literal(record.tag().value()) + ");");
        }
        for (var i = 0; i < components.size(); i++) {
            var component = components.get(i);
            var name = "writer.name(" + record.names() + ", " + i + ");";
            var accessor = "value." + component.accessor() + "()";
            if (component.type().getKind().isPrimitive()) {
                code.line(name);
                code.line(writeStatement(component.codec(), accessor));
            } else if (component.nullable()) {
                code.line("var " + component.variable() + " = " + accessor + ";");
                code.line("if (" + component.variable() + " != null) {");
                code.line("    " + name);
                code.line("    " + writeStatement(component.codec(), component.variable()));
                code.line("} else if (writer.writesNullMembers()) {");
                code.line("    " + name);
                code.line("    writer.nullValue();");
                code.line("}");
            } else {
                code.line("var " + component.variable() + " = " + scope.name("java.util.Objects") + ".requireNonNull("
                        + accessor + ", " + literal(record.type().getSimpleName() + "." + component.accessor()) + ");");
                code.line(name);
                code.line(writeStatement(component.codec(), component.variable()));
            }
        }
        code.line("writer.endObject();");
        return method("void", record.write(), writer + " writer, " + type + " value", code);
    }

    // Enums.

    private String readEnum(EnumBody body) {
        var type = scope.name(body.type());
        var code = new Code();
        code.line("var mark = reader.mark();");
        code.line("var name = reader.readString();");
        code.line("return switch (name) {");
        for (var constant : body.constants()) {
            code.line("    case " + literal(constant.name()) + " -> " + type + "." + constant.constant() + ";");
        }
        var expected = body.constants().stream()
                .map(constant -> "\\\"" + escape(constant.name()) + "\\\"")
                .collect(Collectors.joining(", "));
        code.line("    default -> throw reader.mappingError(mark, \"expected one of " + expected + " for "
                + body.type().getSimpleName() + ", found \\\"\" + name + \"\\\"\");");
        code.line("};");
        return method(type, body.read(), reader + " reader", code);
    }

    private String writeEnum(EnumBody body) {
        var code = new Code();
        code.line("writer.value(switch (value) {");
        for (var constant : body.constants()) {
            code.line("    case " + constant.constant() + " -> " + literal(constant.name()) + ";");
        }
        code.line("});");
        return method("void", body.write(), writer + " writer, " + scope.name(body.type()) + " value", code);
    }

    // Sealed interfaces.

    private String readTagged(SealedBody sealed) {
        var code = new Code();
        code.line("return switch (reader.peekTag(" + sealed.memberConstant() + ", " + sealed.tags() + ")) {");
        var leaves = sealed.leaves();
        for (var i = 0; i < leaves.size(); i++) {
            code.line("    case " + i + " -> " + leaves.get(i).codec().read() + "(reader);");
        }
        code.line("    default -> throw new IllegalStateException(\"no tag at that index\");");
        code.line("};");
        return method(scope.name(sealed.type()), sealed.read(), reader + " reader", code);
    }

    private String writeTagged(SealedBody sealed) {
        var code = new Code();
        code.line("switch (value) {");
        for (var leaf : sealed.leaves()) {
            code.line("    case " + scope.name(leaf.record()) + " leaf -> "
                    + leaf.codec().write() + "(writer, leaf);");
        }
        code.line("}");
        return method("void", sealed.write(), writer + " writer, " + scope.name(sealed.type()) + " value", code);
    }

    private String readWrapper(SealedBody sealed) {
        var type = scope.name(sealed.type());
        var simpleName = sealed.type().getSimpleName();
        var expected = sealed.leaves().stream()
                .map(leaf -> "\\\"" + escape(leaf.tag()) + "\\\"")
                .collect(Collectors.joining(", "));
        var code = new Code();
        code.line("var mark = reader.mark();");
        code.line("reader.beginObject();");
        code.line("if (!reader.hasNextMember()) {");
        code.line("    throw reader.mappingError(mark, \"expected a member naming the type of " + simpleName
                + ", as one of " + expected + ", found none\");");
        code.line("}");
        code.line("var tag = reader.readName();");
        code.line(type + " value = switch (tag) {");
        for (var leaf : sealed.leaves()) {
            code.line("    case " + literal(leaf.tag()) + " -> " + leaf.codec().read() + "(reader);");
        }
        code.line("    default -> throw reader.mappingError(mark, \"unknown type \\\"\" + tag + \"\\\" of " + simpleName
                + ", expected one of " + expected + "\");");
        code.line("};");
        code.line("if (reader.hasNextMember()) {");
        code.line("    throw reader.mappingError(mark, \"expected only the member naming the type of " + simpleName
                + ", found others\");");
        code.line("}");
        code.line("return value;");
        return method(type, sealed.read(), reader + " reader", code);
    }

    private String writeWrapper(SealedBody sealed) {
        var code = new Code();
        code.line("writer.beginObject();");
        code.line("switch (value) {");
        var leaves = sealed.leaves();
        for (var i = 0; i < leaves.size(); i++) {
            var leaf = leaves.get(i);
            code.line("    case " + scope.name(leaf.record()) + " leaf -> {");
            code.line("        writer.name(" + sealed.tags() + ", " + i + ");");
            code.line("        " + leaf.codec().write() + "(writer, leaf);");
            code.line("    }");
        }
        code.line("}");
        code.line("writer.endObject();");
        return method("void", sealed.write(), writer + " writer, " + scope.name(sealed.type()) + " value", code);
    }

    // Containers.

    private String readContainer(ContainerBody body) {
        var element = typeName(body.element());
        var code = new Code();
        switch (body.kind()) {
            case LIST -> {
                code.line("reader.beginArray();");
                code.line("var list = new " + scope.name("java.util.ArrayList") + "<" + element + ">();");
                code.line("while (reader.hasNextElement()) {");
                code.line("    list.add(" + readExpression(body.codec()) + ");");
                code.line("}");
                code.line("return " + scope.name("java.util.Collections") + ".unmodifiableList(list);");
            }
            case SET -> {
                code.line("reader.beginArray();");
                code.line("var set = new " + scope.name("java.util.LinkedHashSet") + "<" + element + ">();");
                code.line("while (reader.hasNextElement()) {");
                code.line("    var mark = reader.mark();");
                code.line("    if (!set.add(" + readExpression(body.codec()) + ")) {");
                code.line("        throw reader.mappingError(mark, \"duplicate element in a set\");");
                code.line("    }");
                code.line("}");
                code.line("return " + scope.name("java.util.Collections") + ".unmodifiableSet(set);");
            }
            default -> {
                // A map.
                code.line("reader.beginObject();");
                code.line("var map = new " + scope.name("java.util.LinkedHashMap") + "<String, " + element + ">();");
                code.line("while (reader.hasNextMember()) {");
                code.line("    var name = reader.readName();");
                code.line("    map.put(name, " + readExpression(body.codec()) + ");");
                code.line("}");
                code.line("return " + scope.name("java.util.Collections") + ".unmodifiableMap(map);");
            }
        }
        return method(typeName(body.type()), body.read(), reader + " reader", code);
    }

    private String writeContainer(ContainerBody body) {
        var code = new Code();
        if (body.kind() == Container.MAP) {
            code.line("writer.beginObject();");
            code.line("for (var entry : value.entrySet()) {");
            code.line("    writer.name(entry.getKey());");
            code.line("    " + writeStatement(body.codec(), "entry.getValue()"));
            code.line("}");
            code.line("writer.endObject();");
        } else {
            code.line("writer.beginArray();");
            code.line("for (var element : value) {");
            code.line("    " + writeStatement(body.codec(), "element"));
            code.line("}");
            code.line("writer.endArray();");
        }
        return method("void", body.write(), writer + " writer, " + typeName(body.type()) + " value", code);
    }

    // Values.

    /// Returns the expression that reads a value from `reader`.
    private String readExpression(Codec codec) {
        return switch (codec) {
            case Primitive(var kind) -> "reader.read" + capitalized(kind) + "()";
            case Direct(var method) -> "reader." + method + "()";
            case Parsed(var method) -> bindings + "." + method + "(reader)";
            case Value(var type) ->
                type.equals(Types.JSON_VALUE)
                        ? "reader.readValue()"
                        : bindings + ".readValue(reader, " + scope.name(type) + ".class)";
            case Call call -> call.read() + "(reader)";
        };
    }

    /// Returns the statement that writes a value, which is not null, to `writer`.
    private String writeStatement(Codec codec, String value) {
        return switch (codec) {
            case Primitive ignored -> "writer.value(" + value + ");";
            case Direct ignored -> "writer.value(" + value + ");";
            case Value ignored -> "writer.value(" + value + ");";
            case Parsed ignored -> "writer.value(" + value + ".toString());";
            case Call call -> call.write() + "(writer, " + value + ");";
        };
    }

    private static String capitalized(TypeKind kind) {
        var name = kind.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String initial(TypeMirror type) {
        return switch (type.getKind()) {
            case BOOLEAN -> "false";
            case INT, LONG, SHORT, BYTE, DOUBLE, FLOAT -> "0";
            default -> "null";
        };
    }

    private static String bit(int index) {
        return "0x" + Long.toHexString(1L << index) + "L";
    }

    /// Returns the name of a type, as written in the source, without its type annotations.
    private String typeName(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return type.getKind().name().toLowerCase(Locale.ROOT);
        }
        var declared = (DeclaredType) type;
        var raw = scope.name((TypeElement) declared.asElement());
        var arguments = declared.getTypeArguments();
        return arguments.isEmpty()
                ? raw
                : raw + arguments.stream().map(this::typeName).collect(Collectors.joining(", ", "<", ">"));
    }

    private static String method(String returnType, String name, String parameters, Code code) {
        return "\n" + INDENT + "private static " + returnType + " " + name + "(" + parameters + ") {\n" + code + INDENT
                + "}\n";
    }

    /// Returns a string as a Java string literal. Control characters are written as octal escapes: a Unicode escape
    /// would be translated before the literal is parsed.
    static String literal(String value) {
        return "\"" + escape(value) + "\"";
    }

    private static String escape(String value) {
        var escaped = new StringBuilder();
        value.chars().forEach(c -> {
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < ' ' || c == '\u007F') {
                        escaped.append("\\%03o".formatted(c));
                    } else {
                        escaped.append((char) c);
                    }
                }
            }
        });
        return escaped.toString();
    }

    /// The statements of a method body, each on its line, indented twice.
    private static final class Code {

        private final StringBuilder lines = new StringBuilder();

        void line(String line) {
            lines.append(INDENT).append(INDENT).append(line).append('\n');
        }

        @Override
        public String toString() {
            return lines.toString();
        }
    }
}
