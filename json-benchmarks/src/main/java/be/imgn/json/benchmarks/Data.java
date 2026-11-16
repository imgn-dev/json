package be.imgn.json.benchmarks;

import be.imgn.json.JsonArray;
import be.imgn.json.JsonObject;
import be.imgn.json.JsonParser;
import be.imgn.json.JsonWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/// The documents the benchmarks parse: the resources in `data/`, and `status.json`, an API-sized payload of about
/// 2 KB, the first status of `twitter.json` written compact.
final class Data {

    /// The documents, from the largest to the smallest.
    static final String[] FILES = {"canada.json", "citm_catalog.json", "twitter.json", "status.json"};

    private Data() {}

    /// Returns the bytes of a document.
    static byte[] read(String name) {
        if ("status.json".equals(name)) {
            var twitter =
                    (JsonObject) new JsonParser().parse(read("twitter.json")).root();
            var statuses = (JsonArray) twitter.get("statuses").orElseThrow();
            return new JsonWriter().toBytes(statuses.get(0));
        }
        try (var in = Objects.requireNonNull(Data.class.getResourceAsStream("/data/" + name), name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
