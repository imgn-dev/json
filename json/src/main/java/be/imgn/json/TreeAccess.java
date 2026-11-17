package be.imgn.json;

import be.imgn.json.internal.Access;
import be.imgn.json.internal.Output;
import be.imgn.json.internal.Tape;

/// What this package gives the other packages of the module: see [Access].
final class TreeAccess implements Access.Trees {

    @Override
    public JsonValue value(Tape tape, int index) {
        return TapeValues.at(tape, index);
    }

    @Override
    public JsonType type(long entry) {
        return TapeValues.type(entry);
    }

    @Override
    public String description(JsonType type) {
        return type.description();
    }

    @Override
    public JsonMappingException mappingError(String message, JsonPosition position, String path) {
        return new JsonMappingException(message, position, path);
    }

    @Override
    public void write(Output output, JsonValue value) {
        TreeWriter.write(output, value);
    }
}
