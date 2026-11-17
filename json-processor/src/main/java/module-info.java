import be.imgn.json.processor.JsonProcessor;
import javax.annotation.processing.Processor;

/// The annotation processor that generates the binders of the types annotated `be.imgn.json.bind.JsonRecord`.
///
/// It exports no package: javac finds the processor through the service it provides.
module be.imgn.json.processor {
    requires be.imgn.gaia.processing;
    requires static org.jspecify;

    provides Processor with
            JsonProcessor;
}
