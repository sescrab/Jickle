package org.example.jickle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Objects;

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

    public static JickleFilter eq(String path, Object value) {
        return new JickleFilter((node, map) -> Objects.equals(resolvePath(node, path, map), value));
    }

    public static JickleFilter ne(String path, Object value) {
        return new JickleFilter((node, map) -> !Objects.equals(resolvePath(node, path, map), value));
    }

    public static JickleFilter gt(String path, Number value) {
        return new JickleFilter((node, map) -> compare(resolvePath(node, path, map), value) > 0);
    }

    public static JickleFilter ge(String path, Number value) {
        return new JickleFilter((node, map) -> compare(resolvePath(node, path, map), value) >= 0);
    }

    public static JickleFilter lt(String path, Number value) {
        return new JickleFilter((node, map) -> compare(resolvePath(node, path, map), value) < 0);
    }

    public static JickleFilter le(String path, Number value) {
        return new JickleFilter((node, map) -> compare(resolvePath(node, path, map), value) <= 0);
    }

    public static JickleFilter contains(String path, String substring) {
        return new JickleFilter((node, map) -> {
            Object value = resolvePath(node, path, map);
            return value instanceof String stringValue && stringValue.contains(substring);
        });
    }

    public static JickleFilter startsWith(String path, String prefix) {
        return new JickleFilter((node, map) -> {
            Object value = resolvePath(node, path, map);
            return value instanceof String stringValue && stringValue.startsWith(prefix);
        });
    }

    public static JickleFilter endsWith(String path, String suffix) {
        return new JickleFilter((node, map) -> {
            Object value = resolvePath(node, path, map);
            return value instanceof String stringValue && stringValue.endsWith(suffix);
        });
    }

    public static JickleFilter isNull(String path) {
        return new JickleFilter((node, map) -> resolvePath(node, path, map) == null);
    }

    public static JickleFilter notNull(String path) {
        return new JickleFilter((node, map) -> resolvePath(node, path, map) != null);
    }

    public static JickleFilter and(JickleFilter... filters) {
        return new JickleFilter((node, map) -> {
            for (JickleFilter filter : filters) {
                if (!filter.matches(node, map)) {
                    return false;
                }
            }
            return true;
        });
    }

    public static JickleFilter or(JickleFilter... filters) {
        return new JickleFilter((node, map) -> {
            for (JickleFilter filter : filters) {
                if (filter.matches(node, map)) {
                    return true;
                }
            }
            return false;
        });
    }

    public static JickleFilter not(JickleFilter filter) {
        return new JickleFilter((node, map) -> !filter.matches(node, map));
    }

    private static Object resolvePath(ObjectNode root, String path, Map<String, ObjectNode> idToNode) {
        JsonNode current = root.get("data");
        String[] parts = path.split("\\.");

        for (int i = 0; i < parts.length; i++) {
            Step step = resolveStep(current, parts[i]);
            if (step == null || step.node == null || step.node.isNull()) {
                return null;
            }

            boolean last = i == parts.length - 1;
            if (step.reference) {
                ObjectNode target = idToNode.get(extractRefId(step.node));
                if (target == null) {
                    return null;
                }
                if (last) {
                    return target.get("data");
                }
                current = target.get("data");
                continue;
            }

            if (last) {
                return toJavaValue(step.node);
            }
            current = step.node;
        }

        return null;
    }

    private static Step resolveStep(JsonNode current, String pathPart) {
        if (current instanceof ObjectNode objectNode) {
            if (objectNode.has(pathPart)) {
                return new Step(objectNode.get(pathPart), false);
            }

            String referenceKey = "object_" + pathPart;
            if (objectNode.has(referenceKey)) {
                return new Step(objectNode.get(referenceKey), true);
            }

            if (objectNode.path("is_container").asBoolean(false) && objectNode.has("elements")) {
                if ("size".equals(pathPart)) {
                    return new Step(IntNode.valueOf(objectNode.withArray("elements").size()), false);
                }
                Integer index = parseIndex(pathPart);
                if (index != null && index >= 0 && index < objectNode.withArray("elements").size()) {
                    JsonNode element = objectNode.withArray("elements").get(index);
                    return new Step(element, isReference(element));
                }
            }

            return null;
        }

        if (current.isArray()) {
            Integer index = parseIndex(pathPart);
            if (index == null || index < 0 || index >= current.size()) {
                return null;
            }
            JsonNode element = current.get(index);
            return new Step(element, isReference(element));
        }

        return null;
    }

    private static Integer parseIndex(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
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

    private record Step(JsonNode node, boolean reference) {
    }
}
