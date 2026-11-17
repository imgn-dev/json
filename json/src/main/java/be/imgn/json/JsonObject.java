package be.imgn.json;

import static be.imgn.gaia.core.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import be.imgn.json.internal.Strings;
import be.imgn.json.internal.Tape;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/// A JSON object: members with distinct names, in the order of the document or of their insertion.
///
/// An object parsed from a document is lazy: it creates each member's value on its first access, and keeps the
/// document in memory while it is referenced. [#copyOf(JsonObject)] makes a copy that does not.
///
/// Two objects are equal when they have the same members, whatever their order, as two [Map]s are.
///
/// @since 0.1.0
public final class JsonObject implements JsonValue {

    private static final VarHandle VALUES = MethodHandles.arrayElementVarHandle(JsonValue[].class);
    // Up to this many members, a name is looked up by a linear scan; above, through a hash index.
    private static final int SMALL = 8;
    private static final String[] NO_NAMES = new String[0];
    private static final JsonObject EMPTY = new JsonObject(NO_NAMES, new JsonValue[0]);

    // The tape of a parsed object, with the index of its start and of each member's name; null when built.
    private final @Nullable Tape tape;
    private final int start;
    private final int[] nameIndices;
    // The names and values of a built object; for a parsed one, those created so far, set with release semantics.
    private final String[] names;
    private final JsonValue[] values;
    // The index of each name, for objects of more than SMALL members, created on the first lookup.
    private volatile @Nullable Map<String, Integer> index;

    private JsonObject(String[] names, JsonValue[] values) {
        this.tape = null;
        this.start = -1;
        this.nameIndices = new int[0];
        this.names = names;
        this.values = values;
    }

    JsonObject(Tape tape, int start) {
        this.tape = tape;
        this.start = start;
        var size = tape.count(start);
        this.nameIndices = new int[size];
        for (int i = 0, name = start + 1; i < size; i++, name = tape.next(name + 1)) {
            nameIndices[i] = name;
        }
        this.names = NO_NAMES;
        this.values = new JsonValue[size];
    }

    /// Returns the empty object.
    ///
    /// @return the empty object
    public static JsonObject of() {
        return EMPTY;
    }

    /// Returns an object of the entries of a map, in its iteration order.
    ///
    /// @param members the members
    /// @return the object
    /// @throws NullPointerException if a name or a value is null
    public static JsonObject of(Map<String, ? extends JsonValue> members) {
        var builder = builder();
        members.forEach(builder::put);
        return builder.build();
    }

    /// Returns a copy of an object that does not keep the document it was parsed from: the copies of its objects and
    /// arrays do not either.
    ///
    /// @param object the object
    /// @return the copy, or `object` if it was built rather than parsed, as were all the objects and arrays
    ///     it holds
    public static JsonObject copyOf(JsonObject object) {
        if (object.isDetached()) {
            return object;
        }
        var builder = builder();
        for (var i = 0; i < object.size(); i++) {
            builder.put(object.name(i), Detached.copyOf(object.value(i)));
        }
        return builder.build();
    }

    /// Returns a builder of an object.
    ///
    /// @return a new builder
    public static Builder builder() {
        return new Builder();
    }

    /// Returns the number of members.
    ///
    /// @return the number of members
    public int size() {
        return values.length;
    }

    /// Returns whether this object has no member.
    ///
    /// @return whether this object has no member
    public boolean isEmpty() {
        return values.length == 0;
    }

    /// Returns the value of a member.
    ///
    /// @param name the name of the member
    /// @return the value, or empty if this object has no such member
    public Optional<JsonValue> get(String name) {
        requireNonNull(name, "name");
        var i = indexOf(name);
        return i < 0 ? Optional.empty() : Optional.of(value(i));
    }

    /// Returns the members, as an unmodifiable map that iterates in their order.
    ///
    /// @return the members
    public Map<String, JsonValue> members() {
        return new Members();
    }

    @Override
    public JsonType type() {
        return JsonType.OBJECT;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof JsonObject object) || object.size() != size()) {
            return false;
        }
        for (var i = 0; i < size(); i++) {
            var j = object.indexOf(name(i));
            if (j < 0 || !value(i).equals(object.value(j))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        var hash = 0;
        for (var i = 0; i < size(); i++) {
            hash += name(i).hashCode() ^ value(i).hashCode();
        }
        return hash;
    }

    /// Returns this object as compact JSON, except for JSON5's `NaN` and infinities, which JSON has no way
    /// to write: they are written `NaN`, `Infinity` and `-Infinity`, where [JsonWriter] rejects them.
    ///
    /// @throws IllegalArgumentException if a string of this object has an unpaired surrogate
    @Override
    public String toString() {
        return TreeWriter.toString(this);
    }

    @Nullable
    Tape tape() {
        return tape;
    }

    int start() {
        return start;
    }

    /// Returns the tape index of the value of a member of a parsed object.
    int valueIndex(int member) {
        return nameIndices[member] + 1;
    }

    /// Returns the index of the member with a name, or -1.
    int indexOf(String name) {
        if (size() <= SMALL) {
            for (var i = 0; i < size(); i++) {
                if (name(i).equals(name)) {
                    return i;
                }
            }
            return -1;
        }
        var lookup = index;
        if (lookup == null) {
            var created = HashMap.<String, Integer>newHashMap(size());
            for (var i = 0; i < size(); i++) {
                created.put(name(i), i);
            }
            // Two threads may create it: either result is correct, and the volatile write publishes it whole.
            index = created;
            lookup = created;
        }
        return lookup.getOrDefault(name, -1);
    }

    /// Returns the name of a member: for a parsed object, from the names of its document, decoded once for all its
    /// objects.
    String name(int member) {
        var parsed = tape;
        return parsed == null ? names[member] : Strings.decode(parsed, nameIndices[member]);
    }

    JsonValue value(int member) {
        if (tape == null) {
            return values[member];
        }
        var value = (JsonValue) VALUES.getAcquire(values, member);
        if (value == null) {
            value = TapeValues.at(requireNonNull(tape), nameIndices[member] + 1);
            VALUES.setRelease(values, member, value);
        }
        return value;
    }

    boolean isDetached() {
        if (tape != null) {
            return false;
        }
        for (var value : values) {
            if (!Detached.isDetached(value)) {
                return false;
            }
        }
        return true;
    }

    /// Builds an object, keeping the order of the members.
    ///
    /// @since 0.1.0
    public static final class Builder {

        private final LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();

        private Builder() {}

        /// Adds a member.
        ///
        /// @param name the name of the member
        /// @param value its value
        /// @return this builder
        /// @throws IllegalArgumentException if the builder already has a member of that name
        public Builder put(String name, JsonValue value) {
            requireNonNull(name, "name");
            requireNonNull(value, "value");
            checkArgument(members.putIfAbsent(name, value) == null, "duplicate member name \"%s\"", name);
            return this;
        }

        /// Builds the object.
        ///
        /// @return the object
        public JsonObject build() {
            return members.isEmpty()
                    ? EMPTY
                    : new JsonObject(
                            members.keySet().toArray(String[]::new),
                            members.values().toArray(JsonValue[]::new));
        }
    }

    /// The members of this object, as a map.
    private final class Members extends AbstractMap<String, JsonValue> {

        @Override
        public int size() {
            return JsonObject.this.size();
        }

        @Override
        public boolean containsKey(@Nullable Object key) {
            return key instanceof String name && indexOf(name) >= 0;
        }

        @Override
        public @Nullable JsonValue get(@Nullable Object key) {
            if (!(key instanceof String name)) {
                return null;
            }
            var i = indexOf(name);
            return i < 0 ? null : value(i);
        }

        @Override
        public Set<Entry<String, JsonValue>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public int size() {
                    return JsonObject.this.size();
                }

                @Override
                public Iterator<Entry<String, JsonValue>> iterator() {
                    return new Iterator<>() {
                        private int next;

                        @Override
                        public boolean hasNext() {
                            return next < JsonObject.this.size();
                        }

                        @Override
                        public Entry<String, JsonValue> next() {
                            if (!hasNext()) {
                                throw new NoSuchElementException();
                            }
                            var i = next++;
                            return new SimpleImmutableEntry<>(name(i), value(i));
                        }
                    };
                }
            };
        }
    }
}
