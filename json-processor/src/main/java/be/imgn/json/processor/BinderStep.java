package be.imgn.json.processor;

import be.imgn.gaia.processing.AnnotatedElements;
import be.imgn.gaia.processing.MoreElements;
import be.imgn.gaia.processing.ProcessingContext;
import be.imgn.gaia.processing.ProcessingException;
import be.imgn.gaia.processing.Step;
import be.imgn.gaia.processing.source.ClassName;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.NestingKind;

/// Generates the binder of each type annotated `JsonRecord`.
final class BinderStep implements Step {

    private static final ClassName JSON_BINDER = ClassName.of("be.imgn.json.spi", "JsonBinder");

    private final ProcessingContext context;

    BinderStep(ProcessingContext context) {
        this.context = context;
    }

    @Override
    public Set<String> annotations() {
        return Set.of(Types.JSON_RECORD);
    }

    @Override
    public Set<Element> process(AnnotatedElements elements) {
        context.forEach(elements.annotatedWith(Types.JSON_RECORD), this::generate);
        return Set.of();
    }

    private void generate(Element element) {
        var type = MoreElements.asTypeElement(element)
                .filter(candidate -> MoreElements.isRecord(candidate) || Types.isSealedInterface(candidate))
                .orElseThrow(() -> new ProcessingException(
                        "@JsonRecord must annotate a record or a sealed interface", element, Types.JSON_RECORD));
        if (!type.getTypeParameters().isEmpty()) {
            throw new ProcessingException("A @JsonRecord type cannot be generic", element, Types.JSON_RECORD);
        }
        var packageName = MoreElements.packageOf(type).getQualifiedName().toString();
        if (type.getNestingKind() == NestingKind.LOCAL || !MoreElements.isAccessible(type, packageName)) {
            throw new ProcessingException(
                    "A @JsonRecord type must be accessible from its package", element, Types.JSON_RECORD);
        }
        new Analyzer(context, packageName).analyze(type).ifPresent(binder -> {
            var name = ClassName.of(type).generatedName("JsonBinder");
            context.sources()
                    .write(name.canonicalName(), scope -> new SourceWriter(scope, binder).write(), binder.elements());
            context.services().add(JSON_BINDER, name, type);
        });
    }
}
