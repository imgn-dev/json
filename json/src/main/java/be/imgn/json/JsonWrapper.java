package be.imgn.json;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.CLASS;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/// Lays out a sealed interface as an object with one member, named after the record, whose value is the record:
/// `{"circle": {"radius": 1}}`.
///
/// ```java
/// @JsonWrapper
/// sealed interface Event permits Created, Deleted {}
/// ```
///
/// The tag of a record is its [JsonName], or its simple name. An object without a member, with several members, or
/// with an unknown tag, is a [JsonMappingException].
///
/// @see JsonTagMember
/// @since 0.1.0
@Documented
@Retention(CLASS)
@Target(TYPE)
public @interface JsonWrapper {}
