package be.imgn.json.benchmarks;

import be.imgn.json.JsonArray;
import be.imgn.json.JsonBoolean;
import be.imgn.json.JsonNull;
import be.imgn.json.JsonNumber;
import be.imgn.json.JsonObject;
import be.imgn.json.JsonString;
import be.imgn.json.JsonValue;
import com.google.gson.JsonElement;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/// Visits every value of a tree, the same way for each library: the length of each string and name, and the value of
/// each number as a `double`, so that nothing is left unread.
final class Visits {

    private Visits() {}

    static double visit(JsonValue value) {
        return switch (value) {
            case JsonObject object -> {
                var sum = 0.0;
                for (var member : object.members().entrySet()) {
                    sum += member.getKey().length() + visit(member.getValue());
                }
                yield sum;
            }
            case JsonArray array -> {
                var sum = 0.0;
                for (var i = 0; i < array.size(); i++) {
                    sum += visit(array.get(i));
                }
                yield sum;
            }
            case JsonString string -> string.value().length();
            case JsonNumber number -> number.asDouble();
            case JsonBoolean bool -> bool.value() ? 1 : 0;
            case JsonNull ignored -> 0;
        };
    }

    static double visit(JsonNode node) {
        var sum = 0.0;
        if (node.isObject()) {
            for (var member : node.properties()) {
                sum += member.getKey().length() + visit(member.getValue());
            }
        } else if (node.isArray()) {
            for (var element : node.values()) {
                sum += visit(element);
            }
        } else if (node.isString()) {
            sum = node.stringValue().length();
        } else if (node.isNumber()) {
            sum = node.doubleValue();
        } else if (node.isBoolean() && node.booleanValue()) {
            sum = 1;
        }
        return sum;
    }

    static double visit(JsonElement element) {
        var sum = 0.0;
        if (element.isJsonObject()) {
            for (var member : element.getAsJsonObject().entrySet()) {
                sum += member.getKey().length() + visit(member.getValue());
            }
        } else if (element.isJsonArray()) {
            for (var item : element.getAsJsonArray()) {
                sum += visit(item);
            }
        } else if (element.isJsonPrimitive()) {
            var primitive = element.getAsJsonPrimitive();
            if (primitive.isString()) {
                sum = primitive.getAsString().length();
            } else if (primitive.isNumber()) {
                sum = primitive.getAsDouble();
            } else {
                sum = primitive.getAsBoolean() ? 1 : 0;
            }
        }
        return sum;
    }

    /// Visits the maps, lists, strings, numbers and booleans of fastjson2.
    static double visit(Object value) {
        var sum = 0.0;
        if (value instanceof Map<?, ?> map) {
            for (var member : map.entrySet()) {
                sum += ((String) member.getKey()).length() + visitNullable(member.getValue());
            }
        } else if (value instanceof List<?> list) {
            for (var item : list) {
                sum += visitNullable(item);
            }
        } else if (value instanceof String string) {
            sum = string.length();
        } else if (value instanceof Number number) {
            sum = number.doubleValue();
        } else if (value instanceof Boolean bool && bool) {
            sum = 1;
        }
        return sum;
    }

    private static double visitNullable(@Nullable Object value) {
        return value == null ? 0 : visit(value);
    }
}
