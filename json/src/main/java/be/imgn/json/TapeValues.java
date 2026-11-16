package be.imgn.json;

import be.imgn.json.internal.Strings;
import be.imgn.json.internal.Tape;

/// Creates the values of a tape, for the lazy tree and the streaming reader.
final class TapeValues {

    private TapeValues() {}

    /// Returns the value at an index of a tape: a container backed by the tape, or a scalar independent of it.
    static JsonValue at(Tape tape, int index) {
        var entry = tape.entries()[index];
        return switch (Tape.tag(entry)) {
            case Tape.OBJECT_START -> new JsonObject(tape, index);
            case Tape.ARRAY_START -> new JsonArray(tape, index);
            case Tape.STRING -> new JsonString(Strings.decode(tape, index));
            case Tape.NUMBER ->
                JsonNumber.written(tape.input(), Tape.offset(entry), tape.length(index), Tape.flags(entry));
            case Tape.TRUE -> JsonBoolean.TRUE;
            case Tape.FALSE -> JsonBoolean.FALSE;
            case Tape.NULL -> JsonNull.INSTANCE;
            default -> throw new IllegalStateException("no value at index " + index + " of the tape");
        };
    }

    /// Returns the type of the value of a tape entry.
    static JsonType type(long entry) {
        return switch (Tape.tag(entry)) {
            case Tape.OBJECT_START -> JsonType.OBJECT;
            case Tape.ARRAY_START -> JsonType.ARRAY;
            case Tape.STRING -> JsonType.STRING;
            case Tape.NUMBER -> JsonType.NUMBER;
            case Tape.TRUE, Tape.FALSE -> JsonType.BOOLEAN;
            case Tape.NULL -> JsonType.NULL;
            default -> throw new IllegalStateException("not a value: tag " + Tape.tag(entry));
        };
    }
}
