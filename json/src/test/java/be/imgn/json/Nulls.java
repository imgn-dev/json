package be.imgn.json;

import java.util.Collections;

/// Nulls for tests of the null checks of the public API, which NullAway rejects as literals.
final class Nulls {

    private Nulls() {}

    /// Returns null, as unchecked code could pass it: NullAway trusts what the JDK's unannotated methods return.
    static <T> T nullOf(Class<T> type) {
        return type.cast(Collections.singletonList(null).getFirst());
    }
}
