package be.imgn.json;

import java.util.ServiceLoader;

/// Finds the [JsonBinder] of a type, once per type.
final class Binders {

    private static final ClassValue<JsonBinder<?>> BINDERS = new ClassValue<>() {
        @Override
        protected JsonBinder<?> computeValue(Class<?> type) {
            // The class loader of the type also loads its binder, which the processor generates next to it. The
            // service is loaded through a Class<?>: the literal JsonBinder.class has a raw type.
            Class<?> service = JsonBinder.class;
            for (var provider : ServiceLoader.load(service, type.getClassLoader())) {
                if (provider instanceof JsonBinder<?> binder && binder.type() == type) {
                    return binder;
                }
            }
            throw new IllegalArgumentException("no JsonBinder is registered for " + type.getName()
                    + ": annotate it @JsonRecord, compile it with the annotation processor of"
                    + " be.imgn.json:json-processor, and on the module path, declare in its module:"
                    + " provides be.imgn.json.JsonBinder with its binder");
        }
    };

    private Binders() {}

    static <T> T read(Class<T> type, JsonStreamReader reader) {
        return type.cast(BINDERS.get(type).read(reader));
    }

    static void write(Class<?> type, JsonStreamWriter writer, Object value) {
        write(BINDERS.get(type), writer, value);
    }

    private static <T> void write(JsonBinder<T> binder, JsonStreamWriter writer, Object value) {
        binder.write(writer, binder.type().cast(value));
    }
}
