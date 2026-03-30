package org.example.jickle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

public class JickleFilter {

    private final Predicate evaluator;

    private JickleFilter(Predicate evaluator) {
        this.evaluator = evaluator;
    }

    public boolean matches(ObjectNode node) {
        return evaluator.test(normalizeDataNode(node));
    }

    @FunctionalInterface
    private interface Predicate {
        boolean test(ObjectNode dataNode);
    }

    public static JickleFilter eq(String path, Object value) {
        return new JickleFilter(dataNode -> Objects.equals(resolveDirectField(dataNode, path), value));
    }

    public static JickleFilter ne(String path, Object value) {
        return new JickleFilter(dataNode -> !Objects.equals(resolveDirectField(dataNode, path), value));
    }

    public static JickleFilter gt(String path, Number value) {
        return new JickleFilter(dataNode -> compare(resolveDirectField(dataNode, path), value) > 0);
    }

    public static JickleFilter ge(String path, Number value) {
        return new JickleFilter(dataNode -> compare(resolveDirectField(dataNode, path), value) >= 0);
    }

    public static JickleFilter lt(String path, Number value) {
        return new JickleFilter(dataNode -> compare(resolveDirectField(dataNode, path), value) < 0);
    }

    public static JickleFilter le(String path, Number value) {
        return new JickleFilter(dataNode -> compare(resolveDirectField(dataNode, path), value) <= 0);
    }

    public static JickleFilter contains(String path, String substring) {
        return new JickleFilter(dataNode -> {
            Object value = resolveDirectField(dataNode, path);
            return value instanceof String stringValue && stringValue.contains(substring);
        });
    }

    public static JickleFilter startsWith(String path, String prefix) {
        return new JickleFilter(dataNode -> {
            Object value = resolveDirectField(dataNode, path);
            return value instanceof String stringValue && stringValue.startsWith(prefix);
        });
    }

    public static JickleFilter endsWith(String path, String suffix) {
        return new JickleFilter(dataNode -> {
            Object value = resolveDirectField(dataNode, path);
            return value instanceof String stringValue && stringValue.endsWith(suffix);
        });
    }

    public static JickleFilter isNull(String path) {
        return new JickleFilter(dataNode -> resolveDirectField(dataNode, path) == null);
    }

    public static JickleFilter notNull(String path) {
        return new JickleFilter(dataNode -> resolveDirectField(dataNode, path) != null);
    }

    public static JickleFilter and(JickleFilter... filters) {
        return new JickleFilter(dataNode -> {
            for (JickleFilter filter : filters) {
                if (!filter.matches(dataNode)) {
                    return false;
                }
            }
            return true;
        });
    }

    public static JickleFilter or(JickleFilter... filters) {
        return new JickleFilter(dataNode -> {
            for (JickleFilter filter : filters) {
                if (filter.matches(dataNode)) {
                    return true;
                }
            }
            return false;
        });
    }

    public static JickleFilter not(JickleFilter filter) {
        return new JickleFilter(dataNode -> !filter.matches(dataNode));
    }

    private static ObjectNode normalizeDataNode(ObjectNode node) {
        JsonNode data = node.get("data");
        if (data instanceof ObjectNode objectNode) {
            return objectNode;
        }
        return node;
    }

    private static Object resolveDirectField(ObjectNode dataNode, String path) {
        if (path == null || path.isBlank() || path.contains(".")) {
            return null;
        }

        JsonNode directValue = dataNode.get(path);
        if (directValue != null) {
            return toJavaValue(directValue);
        }

        JsonNode referenceValue = dataNode.get("object_" + path);
        if (referenceValue != null) {
            return toJavaValue(referenceValue);
        }

        return null;
    }

    private static Object toJavaValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.numberValue();
        }
        if (value.isTextual()) {
            return value.textValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        return value;
    }

    public static boolean isReference(JsonNode node) {
        return node != null && node.isTextual() && node.textValue().startsWith("#");
    }

    public static String extractRefId(JsonNode node) {
        if (node.isTextual()) {
            String text = node.textValue();
            return text.startsWith("#") ? text.substring(1) : text;
        }
        return node.asText();
    }

    private static int compare(Object left, Number right) {
        if (!(left instanceof Number number)) {
            return 0;
        }
        return Double.compare(number.doubleValue(), right.doubleValue());
    }
}
