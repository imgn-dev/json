package be.imgn.json.stream;

import be.imgn.json.bind.UnknownMembers;
import be.imgn.json.internal.Access;
import be.imgn.json.internal.Output;
import be.imgn.json.internal.Tape;

/// What this package gives the other packages of the module: see [Access].
final class StreamAccess implements Access.Streams {

    @Override
    public JsonStreamReader reader(Tape tape, UnknownMembers unknownMembers) {
        return new JsonStreamReader(tape, unknownMembers);
    }

    @Override
    public JsonStreamWriter writer(Output output, boolean writeNullMembers) {
        return new JsonStreamWriter(output, writeNullMembers);
    }

    @Override
    public JsonStreamWriter writer(int indent, boolean writeNullMembers) {
        return new JsonStreamWriter(indent, writeNullMembers);
    }
}
