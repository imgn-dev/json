package be.imgn.json.processor;

import be.imgn.gaia.processing.ProcessingContext;
import be.imgn.gaia.processing.Step;
import be.imgn.gaia.processing.StepProcessor;
import java.util.List;

/// Generates a `be.imgn.json.spi.JsonBinder` for each type annotated `be.imgn.json.bind.JsonRecord`, and registers it
/// as a
/// service.
public final class JsonProcessor extends StepProcessor {

    /// Creates the processor; javac does, through the service loader.
    public JsonProcessor() {}

    @Override
    protected List<Step> steps(ProcessingContext context) {
        return List.of(new BinderStep(context));
    }
}
