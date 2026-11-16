/// The core of the parser: the tape builder, which validates a document and turns it into a [Tape], and the helpers
/// that decode it. Not exported: the public API in `be.imgn.json` rests on it.
@NullMarked
package be.imgn.json.internal;

import org.jspecify.annotations.NullMarked;
