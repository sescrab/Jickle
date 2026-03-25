package org.example.jickle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class JickleFilter {

    private final Predicate evaluator;

    private JickleFilter(Predicate evaluator) {
        this.evaluator = evaluator;
    }

    public boolean matches(ObjectNode objectNode, Map<String, ObjectNode> idToNode) {
        return evaluator.test(objectNode, idToNode);
    }

    @FunctionalInterface
    private interface Predicate {
        boolean test(ObjectNode node, Map<String, ObjectNode> idToNode);
    }

    // Фабрики
    public static JickleFilter eq(String path, Object value) {
        return new JickleFilter((n, m) -> Objects.equals(resolvePath(n, path, m), value));
    }

    public static JickleFilter ne(String path, Object value) {
        return new JickleFilter((n, m) -> !Objects.equals(resolvePath(n, path, m), value));
    }

    public static JickleFilter gt(String path, Number value) {
        return new JickleFilter((n, m) -> compare(resolvePath(n, path, m), value) > 0);
    }

    public static JickleFilter ge(String path, Number value) {
        return new JickleFilter((n, m) -> compare(resolvePath(n, path, m), value) >= 0);
    }

    public static JickleFilter lt(String path, Number value) {
        return new JickleFilter((n, m) -> compare(resolvePath(n, path, m), value) < 0);
    }

    public static JickleFilter le(String path, Number value) {
        return new JickleFilter((n, m) -> compare(resolvePath(n, path, m), value) <= 0);
    }

    public static JickleFilter contains(String path, String substring) {
        return new JickleFilter((n, m) -> {
            Object v = resolvePath(n, path, m);
            return v instanceof String s && s.contains(substring);
        });
    }

    public static JickleFilter startsWith(String path, String prefix) {
        return new JickleFilter((n, m) -> {
            Object v = resolvePath(n, path, m);
            return v instanceof String s && s.startsWith(prefix);
        });
    }

    public static JickleFilter endsWith(String path, String suffix) {
        return new JickleFilter((n, m) -> {
            Object v = resolvePath(n, path, m);
            return v instanceof String s && s.endsWith(suffix);
        });
    }

    public static JickleFilter isNull(String path) {
        return new JickleFilter((n, m) -> resolvePath(n, path, m) == null);
    }

    public static JickleFilter notNull(String path) {
        return new JickleFilter((n, m) -> resolvePath(n, path, m) != null);
    }

    public static JickleFilter and(JickleFilter... filters) {
        return new JickleFilter((n, m) -> {
            for (JickleFilter f : filters) if (!f.matches(n, m)) return false;
            return true;
        });
    }

    public static JickleFilter or(JickleFilter... filters) {
        return new JickleFilter((n, m) -> {
            for (JickleFilter f : filters) if (f.matches(n, m)) return true;
            return false;
        });
    }

    public static JickleFilter not(JickleFilter filter) {
        return new JickleFilter((n, m) -> !filter.matches(n, m));
    }

    // Вспомогательные методы (некоторые используются в deserializer)
    private static Object resolvePath(ObjectNode root, String path, Map<String, ObjectNode> idToNode) {
        String[] parts = path.split("\\.");
        ObjectNode currentData = (ObjectNode) root.get("data");

        for (String field : parts) {
            JsonNode val = getDataField(currentData, field);
            if (val == null || val.isNull()) return null;

            if (isReference(val)) {
                String refId = extractRefId(val);
                ObjectNode target = idToNode.get(refId);
                if (target == null) return null;
                currentData = (ObjectNode) target.get("data");
                continue;
            }

            // простое значение
            if (val.isNumber()) return val.numberValue();
            if (val.isTextual()) return val.textValue();
            if (val.isBoolean()) return val.booleanValue();
            return null;
        }
        return null;
    }

    private static JsonNode getDataField(ObjectNode data, String fieldName) {
        if (data.has(fieldName)) return data.get(fieldName);
        String refKey = "object_" + fieldName;
        if (data.has(refKey)) return data.get(refKey);
        return null;
    }

    public static boolean isReference(JsonNode node) {
        if (node.isNull()) return false;
        if (node.isTextual() && node.textValue().startsWith("#")) return true;
        return node.isNumber(); // т.к. object_x хранит просто число
    }

    public static String extractRefId(JsonNode node) {
        if (node.isTextual()) {
            String s = node.textValue();
            return s.startsWith("#") ? s.substring(1) : s;
        }
        return node.asText();
    }

    private static int compare(Object left, Number right) {
        if (left == null || !(left instanceof Number n)) return 0;
        return Double.compare(n.doubleValue(), right.doubleValue());
    }
}