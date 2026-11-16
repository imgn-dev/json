package be.imgn.json;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.CLASS;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/// Gives the name of a record component's member, the string of an enum constant, or the tag that names a record in a
/// sealed interface, in place of its Java name.
///
/// ```java
/// record Customer(@JsonName("e-mail") String email) {}
///
/// enum Status {
///     @JsonName("in-progress") IN_PROGRESS,
///     DONE
/// }
///
/// @JsonName("circle")
/// record Circle(double radius) implements Shape {}
/// ```
///
/// @see JsonRecord
/// @since 0.1.0
@Documented
@Retention(CLASS)
@Target({RECORD_COMPONENT, FIELD, TYPE})
public @interface JsonName {

    /// Returns the name, taken as it is.
    ///
    /// @return the name
    String value();
}
