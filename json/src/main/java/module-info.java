import be.imgn.json.spi.JsonBinder;

/// A fast, strict JSON parser and writer: a streaming API, an immutable tree, and binding to records through generated
/// code.
///
/// gaia-core is required transitively: its sources and sinks are part of the signatures this module exports.
module be.imgn.json {
    requires transitive be.imgn.gaia.core;
    requires static org.jspecify;

    exports be.imgn.json;
    exports be.imgn.json.bind;
    exports be.imgn.json.spi;
    exports be.imgn.json.stream;

    // JsonParser.parseAs and JsonWriter.formatAs load the binders that the annotation processor generates.
    uses JsonBinder;
}
