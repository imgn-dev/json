package be.imgn.json.processor;

import java.util.stream.Stream;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

/// The names of the types of the runtime module, and checks of elements.
final class Types {

    static final String JSON_RECORD = "be.imgn.json.bind.JsonRecord";
    static final String JSON_NAME = "be.imgn.json.bind.JsonName";
    static final String JSON_TAG_MEMBER = "be.imgn.json.bind.JsonTagMember";
    static final String JSON_WRAPPER = "be.imgn.json.bind.JsonWrapper";
    static final String JSON_VALUE = "be.imgn.json.JsonValue";

    private Types() {}

    static boolean isSealedInterface(Element element) {
        return element.getKind() == ElementKind.INTERFACE
                && element.getModifiers().contains(Modifier.SEALED);
    }

    /// Returns whether an element, or its type, has an annotation named `Nullable`, from whatever package.
    static boolean isNullable(Element element) {
        return Stream.concat(element.getAnnotationMirrors().stream(), element.asType().getAnnotationMirrors().stream())
                .map(AnnotationMirror::getAnnotationType)
                .anyMatch(annotation -> annotation.asElement().getSimpleName().contentEquals("Nullable"));
    }

    static String qualifiedName(TypeElement type) {
        return type.getQualifiedName().toString();
    }
}
