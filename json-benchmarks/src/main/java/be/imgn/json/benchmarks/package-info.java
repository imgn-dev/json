/// JMH benchmarks of the parser and the writer, against other JSON libraries.
///
/// Not annotated `@NullMarked`: with the JMH processor on the processor path, javac warns that no processor claims
/// that annotation, and NullAway checks every package under `be.imgn` anyway.
package be.imgn.json.benchmarks;
