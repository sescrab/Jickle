package org.example.jickle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.jickle.annotation.JicklableClass;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class JickleDeserializer {

    private static final Set<String> CONTAINER_METADATA_KEYS = Set.of(
            "is_container",
            "component_type",
            "collection_class",
            "elements",
            "entries"
    );

    private final ObjectMapper mapper;
    private final boolean allowUnsafe;

    public JickleDeserializer(boolean allowUnsafe) {
        this.allowUnsafe = allowUnsafe;
        this.mapper = new ObjectMapper();
    }

    public List<Object> load(String filePath) throws IOException, ClassNotFoundException, IllegalAccessException {
        return load(filePath, null);
    }

    public List<Object> load(String filePath, JickleFilter filter)
            throws IOException, ClassNotFoundException, IllegalAccessException {
        JsonNode rootNode = mapper.readTree(Files.readString(Path.of(filePath), StandardCharsets.UTF_8));
        if (!rootNode.isArray() || rootNode.size() != 2) {
            throw new IllegalArgumentException("Invalid format: root must be an array [main, additional]");
        }

        ArrayNode mainArray = asArray(rootNode.get(0), "main");
        ArrayNode additionalArray = asArray(rootNode.get(1), "additional");

        List<ObjectNode> mainNodes = readObjectNodes(mainArray);
        List<ObjectNode> additionalNodes = readObjectNodes(additionalArray);
        List<ObjectNode> allNodes = Stream.concat(mainNodes.stream(), additionalNodes.stream()).toList();

        Map<String, ObjectNode> idToNode = allNodes.stream()
                .collect(Collectors.toMap(node -> node.get("id").asText(), node -> node, (left, right) -> left, LinkedHashMap::new));

        List<ObjectNode> matchingRootNodes = mainNodes.stream()
                .filter(node -> filter == null || filter.matches(node, idToNode))
                .toList();

        if (matchingRootNodes.isEmpty()) {
            return List.of();
        }

        Set<String> reachableIds = collectReachableIds(matchingRootNodes, idToNode);
        List<ObjectNode> nodesToProcess = allNodes.stream()
                .filter(node -> reachableIds.contains(node.get("id").asText()))
                .toList();

        Map<String, Object> idToInstance = new LinkedHashMap<>();
        for (ObjectNode node : nodesToProcess) {
            String id = node.get("id").asText();
            ObjectNode dataNode = asObject(node.get("data"), "data");
            Class<?> clazz = getClassFromHumanReadableName(node.get("class_name").asText());
            boolean isContainer = isContainer(dataNode);

            validateClass(clazz);
            Object instance = isContainer ? createContainerInstance(clazz, dataNode) : createInstance(clazz);
            idToInstance.put(id, instance);
        }

        for (ObjectNode node : nodesToProcess) {
            String id = node.get("id").asText();
            Object instance = idToInstance.get(id);
            ObjectNode dataNode = asObject(node.get("data"), "data");

            if (isContainer(dataNode)) {
                fillContainer(instance, dataNode, idToInstance);
            }
            fillObjectFields(instance, dataNode, idToInstance);
        }

        return matchingRootNodes.stream()
                .map(node -> idToInstance.get(node.get("id").asText()))
                .toList();
    }

    private ArrayNode asArray(JsonNode node, String label) {
        if (node instanceof ArrayNode arrayNode) {
            return arrayNode;
        }
        throw new IllegalArgumentException("Invalid format: " + label + " must be an array");
    }

    private ObjectNode asObject(JsonNode node, String label) {
        if (node instanceof ObjectNode objectNode) {
            return objectNode;
        }
        throw new IllegalArgumentException("Invalid format: " + label + " must be an object");
    }

    private List<ObjectNode> readObjectNodes(ArrayNode arrayNode) {
        return stream(arrayNode)
                .filter(JsonNode::isObject)
                .map(node -> (ObjectNode) node)
                .toList();
    }

    private Stream<JsonNode> stream(ArrayNode arrayNode) {
        return StreamSupport.stream(arrayNode.spliterator(), false);
    }

    private Set<String> collectReachableIds(List<ObjectNode> roots, Map<String, ObjectNode> idToNode) {
        Set<String> reachable = new LinkedHashSet<>();
        roots.forEach(root -> collectReachable(root, idToNode, reachable));
        return reachable;
    }

    private void collectReachable(ObjectNode node, Map<String, ObjectNode> idToNode, Set<String> reachable) {
        String id = node.get("id").asText();
        if (!reachable.add(id)) {
            return;
        }

        Set<String> referencedIds = new LinkedHashSet<>();
        collectReferenceIds(asObject(node.get("data"), "data"), referencedIds);
        referencedIds.stream()
                .map(idToNode::get)
                .filter(java.util.Objects::nonNull)
                .forEach(target -> collectReachable(target, idToNode, reachable));
    }

    private void collectReferenceIds(JsonNode node, Set<String> referencedIds) {
        if (node == null || node.isNull()) {
            return;
        }
        if (JickleFilter.isReference(node)) {
            referencedIds.add(JickleFilter.extractRefId(node));
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectReferenceIds(child, referencedIds));
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (entry.getKey().startsWith("object_") && !entry.getValue().isNull()) {
                    referencedIds.add(JickleFilter.extractRefId(entry.getValue()));
                    return;
                }
                collectReferenceIds(entry.getValue(), referencedIds);
            });
        }
    }

    private boolean isContainer(ObjectNode dataNode) {
        return dataNode.path("is_container").asBoolean(false);
    }

    private void validateClass(Class<?> clazz) {
        if (allowUnsafe || clazz.isArray() || isJdkContainer(clazz) || clazz.isAnnotationPresent(JicklableClass.class)) {
            return;
        }

        throw new IllegalArgumentException("Class " + clazz.getName() + " is not annotated with @JicklableClass");
    }

    private boolean isJdkContainer(Class<?> clazz) {
        return (Collection.class.isAssignableFrom(clazz) || Map.class.isAssignableFrom(clazz)) && isJdkClass(clazz);
    }

    private boolean isJdkClass(Class<?> clazz) {
        Package pkg = clazz.getPackage();
        String packageName = pkg == null ? "" : pkg.getName();
        return packageName.startsWith("java.") ||
                packageName.startsWith("javax.") ||
                packageName.startsWith("jdk.");
    }

    private Object createContainerInstance(Class<?> clazz, ObjectNode dataNode) throws ClassNotFoundException {
        if (clazz.isArray()) {
            String componentTypeName = dataNode.get("component_type").asText();
            Class<?> componentType = getClassByName(componentTypeName);
            int length = dataNode.path("elements").size();
            return Array.newInstance(componentType, length);
        }

        Object directInstance = tryCreateInstance(clazz);
        if (directInstance != null) {
            return directInstance;
        }

        if (Map.class.isAssignableFrom(clazz)) {
            return new LinkedHashMap<>();
        }
        if (Set.class.isAssignableFrom(clazz)) {
            return new LinkedHashSet<>();
        }
        if (Deque.class.isAssignableFrom(clazz) || Queue.class.isAssignableFrom(clazz)) {
            return new ArrayDeque<>();
        }
        if (Collection.class.isAssignableFrom(clazz)) {
            return new ArrayList<>();
        }

        throw new IllegalArgumentException("Unsupported container: " + clazz.getName());
    }

    private Object tryCreateInstance(Class<?> clazz) {
        try {
            Constructor<?> constructor = clazz.getDeclaredConstructor();
            if (!constructor.canAccess(null) && !constructor.trySetAccessible()) {
                return null;
            }
            return constructor.newInstance();
        } catch (Exception ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void fillContainer(Object instance, ObjectNode dataNode, Map<String, Object> idToInstance) {
        if (instance.getClass().isArray()) {
            fillArray(instance, dataNode.path("elements"), idToInstance);
            return;
        }

        if (instance instanceof Map<?, ?> map) {
            fillMap((Map<Object, Object>) map, dataNode.path("entries"), idToInstance);
            return;
        }

        if (instance instanceof Collection<?> collection) {
            fillCollection((Collection<Object>) collection, dataNode.path("elements"), idToInstance);
        }
    }

    private void fillArray(Object array, JsonNode elementsNode, Map<String, Object> idToInstance) {
        Class<?> componentType = array.getClass().getComponentType();
        ArrayNode elements = asArray(elementsNode, "elements");
        for (int i = 0; i < elements.size(); i++) {
            Array.set(array, i, resolveContainerValue(elements.get(i), componentType, idToInstance));
        }
    }

    private void fillCollection(Collection<Object> collection, JsonNode elementsNode, Map<String, Object> idToInstance) {
        ArrayNode elements = asArray(elementsNode, "elements");
        elements.forEach(node -> collection.add(resolveContainerValue(node, Object.class, idToInstance)));
    }

    private void fillMap(Map<Object, Object> map, JsonNode entriesNode, Map<String, Object> idToInstance) {
        ArrayNode entries = asArray(entriesNode, "entries");
        entries.forEach(pairNode -> {
            ArrayNode pair = asArray(pairNode, "entry");
            Object key = resolveContainerValue(pair.get(0), Object.class, idToInstance);
            Object value = resolveContainerValue(pair.get(1), Object.class, idToInstance);
            map.put(key, value);
        });
    }

    private void fillObjectFields(Object instance, ObjectNode dataNode, Map<String, Object> idToInstance) throws IllegalAccessException {
        Class<?> clazz = instance.getClass();
        var fields = dataNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            if (CONTAINER_METADATA_KEYS.contains(key)) {
                continue;
            }

            String fieldName = key.startsWith("object_") ? key.substring(7) : key;
            Field field = findField(clazz, fieldName);
            if (field == null) {
                continue;
            }
            if (!field.canAccess(instance) && !field.trySetAccessible()) {
                continue;
            }

            Object value = key.startsWith("object_")
                    ? idToInstance.get(JickleFilter.extractRefId(entry.getValue()))
                    : mapper.convertValue(entry.getValue(), field.getType());

            field.set(instance, value);
        }
    }

    private Object resolveContainerValue(JsonNode node, Class<?> targetType, Map<String, Object> idToInstance) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (JickleFilter.isReference(node)) {
            return idToInstance.get(JickleFilter.extractRefId(node));
        }
        return mapper.convertValue(node, targetType);
    }

    private Object createInstance(Class<?> clazz) {
        Object instance = tryCreateInstance(clazz);
        if (instance != null) {
            return instance;
        }
        throw new IllegalArgumentException("Failed to create instance of " + clazz.getName());
    }

    private Field findField(Class<?> type, String fieldName) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || field.isSynthetic()) {
                    continue;
                }
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private Class<?> getClassByName(String className) throws ClassNotFoundException {
        return switch (className) {
            case "int" -> int.class;
            case "long" -> long.class;
            case "double" -> double.class;
            case "float" -> float.class;
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "short" -> short.class;
            case "char" -> char.class;
            default -> Class.forName(className);
        };
    }

    private Class<?> getClassFromHumanReadableName(String name) throws ClassNotFoundException {
        if (name.endsWith("[]")) {
            String componentName = name.substring(0, name.length() - 2);
            Class<?> componentType = getClassFromHumanReadableName(componentName);
            return Array.newInstance(componentType, 0).getClass();
        }

        return getClassByName(name);
    }
}
