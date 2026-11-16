package be.imgn.json;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.CLASS;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/// Lays out a sealed interface as an object of its record whose tag member names the record, wherever it is among the
/// members: `{"type": "circle", "radius": 1}`. It is the layout of a sealed interface without annotation, with the tag
/// member `type`.
///
/// ```java
/// @JsonTagMember("kind")
/// sealed interface Shape permits Circle, Rectangle {}
///
/// @JsonName("circle")
/// record Circle(double radius) implements Shape {}
/// ```
///
/// The tag of a record is its [JsonName], or its simple name. No record may have a component named as the tag member.
/// The tag member is written first. A missing or unknown tag is a [JsonMappingException].
///
/// @see JsonWrapper
/// @since 0.1.0
@Documented
@Retention(CLASS)
@Target(TYPE)
public @interface JsonTagMember {

    /// Returns the name of the tag member.
    ///
    /// @return the name of the tag member
    String value() default "type";
}
