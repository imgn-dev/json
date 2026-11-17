package be.imgn.json;

import be.imgn.json.internal.Numbers;
import be.imgn.json.internal.Output;
import be.imgn.json.internal.Strings;
import be.imgn.json.internal.Tape;
import java.nio.charset.StandardCharsets;

/// Writes values to an output. A parsed object or array is written from its tape, without creating its values: strings
/// without escape sequences and numbers are copied as they were written.
final class TreeWriter {

    private TreeWriter() {}

    /// Returns a value as compact JSON, except for JSON5's `NaN` and infinities, which JSON has no way to
    /// write: they are written `NaN`, `Infinity` and `-Infinity`, so that [Object#toString()] does not throw.
    ///
    /// @throws IllegalArgumentException if a string of `value` has an unpaired surrogate
    static String toString(JsonValue value) {
        var output = Output.pooled(0);
        try {
            write(output, value, true);
            return output.toString();
        } finally {
            output.release();
        }
    }

    /// Writes a value as JSON.
    ///
    /// @throws IllegalArgumentException if a string of `value` has an unpaired surrogate, or a number of it is
    ///     JSON5's `NaN` or an infinity
    static void write(Output out, JsonValue value) {
        write(out, value, false);
    }

    /// Writes a value, its non-finite numbers as written when `nonFinite` is true, rejected otherwise. The
    /// type is told by a switch on [JsonValue#type()], a table jump, rather than by a pattern switch, whose
    /// dynamic dispatch costs more for each value.
    private static void write(Output out, JsonValue value, boolean nonFinite) {
        switch (value.type()) {
            case STRING -> out.string(((JsonString) value).value());
            case NUMBER -> writeNumber(out, (JsonNumber) value, nonFinite);
            case OBJECT -> writeObject(out, (JsonObject) value, nonFinite);
            case ARRAY -> writeArray(out, (JsonArray) value, nonFinite);
            case BOOLEAN -> out.bool(((JsonBoolean) value).value());
            default -> out.nullValue();
        }
    }

    private static void writeNumber(Output out, JsonNumber number, boolean nonFinite) {
        var text = number.text();
        if (text == null) {
            out.number(number.value());
        } else {
            if (!nonFinite) {
                checkFinite(text, 0, text.length);
            }
            out.raw(text, 0, text.length);
        }
    }

    private static void writeObject(Output out, JsonObject object, boolean nonFinite) {
        var tape = object.tape();
        if (tape != null) {
            writeTape(out, tape, object.start(), nonFinite);
            return;
        }
        out.beginObject();
        for (var i = 0; i < object.size(); i++) {
            out.name(object.name(i));
            write(out, object.value(i), nonFinite);
        }
        out.endObject();
    }

    private static void writeArray(Output out, JsonArray array, boolean nonFinite) {
        var tape = array.tape();
        if (tape != null) {
            writeTape(out, tape, array.start(), nonFinite);
            return;
        }
        out.beginArray();
        for (var i = 0; i < array.size(); i++) {
            write(out, array.get(i), nonFinite);
        }
        out.endArray();
    }

    /// Rejects `NaN` and the infinities of JSON5, which JSON has no way to write.
    private static void checkFinite(byte[] text, int from, int length) {
        if (Numbers.isNonFinite(text, from, length)) {
            throw new IllegalArgumentException(
                    new String(text, from, length, StandardCharsets.ISO_8859_1) + " is not a JSON number");
        }
    }

    /// Writes the value at an index of a tape, and returns the index after it.
    private static int writeTape(Output out, Tape tape, int index, boolean nonFinite) {
        var entry = tape.entries()[index];
        var tag = Tape.tag(entry);
        var input = tape.input();
        switch (tag) {
            case Tape.OBJECT_START -> {
                out.beginObject();
                var i = index + 1;
                while (Tape.tag(tape.entries()[i]) != Tape.OBJECT_END) {
                    var name = tape.entries()[i];
                    if ((Tape.flags(name) & Tape.ESCAPED) == 0) {
                        out.name(input, Tape.offset(name), tape.length(i) + 2);
                    } else {
                        out.name(Strings.decode(tape, i));
                    }
                    i = writeTape(out, tape, i + 1, nonFinite);
                }
                out.endObject();
                return i + 1;
            }
            case Tape.ARRAY_START -> {
                out.beginArray();
                var i = index + 1;
                while (Tape.tag(tape.entries()[i]) != Tape.ARRAY_END) {
                    i = writeTape(out, tape, i, nonFinite);
                }
                out.endArray();
                return i + 1;
            }
            case Tape.STRING -> {
                if ((Tape.flags(entry) & Tape.ESCAPED) == 0) {
                    // Without escape sequences, a valid string has nothing to escape: its bytes are written as they
                    // are.
                    out.raw(input, Tape.offset(entry), tape.length(index) + 2);
                } else {
                    out.string(Strings.decode(tape, index));
                }
            }
            case Tape.NUMBER -> {
                if (!nonFinite && (Tape.flags(entry) & Tape.NON_FINITE) != 0) {
                    checkFinite(input, Tape.offset(entry), tape.length(index));
                }
                out.raw(input, Tape.offset(entry), tape.length(index));
            }
            case Tape.TRUE, Tape.FALSE -> out.bool(tag == Tape.TRUE);
            default -> out.nullValue();
        }
        return index + 1;
    }
}
