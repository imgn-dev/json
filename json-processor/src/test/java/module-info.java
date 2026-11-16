import be.imgn.json.processor.JsonProcessor;
import javax.annotation.processing.Processor;

// The processor module as its tests see it: the main descriptor, and the modules the tests use. Without it, the tests
// are patched into the main module, which does not read the runtime module the generated code is compiled against.
open module be.imgn.json.processor {
    requires be.imgn.gaia.processing;
    requires be.imgn.gaia.processing.testing;
    requires be.imgn.json;
    requires org.assertj.core;
    requires org.junit.jupiter.api;
    requires org.junit.jupiter.params;
    requires static org.jspecify;

    provides Processor with
            JsonProcessor;
}
