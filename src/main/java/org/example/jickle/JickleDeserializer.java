package org.example.jickle;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.jickle.annotation.JicklableClass;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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

public class JickleDeserializer {

    private static final Set<String> CONTAINER_METADATA_KEYS = Set.of(
            "is_container",
            "component_type",
            "collection_class",
            "elements",
            "entries"
    );

    private final ObjectMapper mapper;
    private final JsonFactory jsonFactory;
    private final boolean allowUnsafe;

    public JickleDeserializer(boolean allowUnsafe) {
        this.allowUnsafe = allowUnsafe;
        this.mapper = new ObjectMapper();
        this.jsonFactory = mapper.getFactory();
    }

    public List<Object> load(String filePath) throws IOException, ClassNotFoundException, IllegalAccessException {
        return load(filePath, null);
    }

    public List<Object> load(String filePath, JickleFilter filter)
            throws IOException, ClassNotFoundException, IllegalAccessException {
        StreamedRecords streamedRecords = readRecordsStreaming(Path.of(filePath));
        Map<String, ObjectNode> idToNode = buildNodeIndex(streamedRecords.records());

        List<RawRecord> matchingRoots = streamedRecords.rootIds().stream()
                .map(streamedRecords.records()::get)
                .filter(record -> record != null && (filter == null || filter.matches(record.node(), idToNode)))
                .toList();

        if (matchingRoots.isEmpty()) {
            return List.of();
        }

        Set<String> reachableIds = collectReachableIds(matchingRoots, streamedRecords.records());
        Map<String, Object> idToInstance = instantiateReachableObjects(reachableIds, streamedRecords.records());
        fillReachableObjects(reachableIds, streamedRecords.records(), idToInstance);

        return matchingRoots.stream()
                .map(record -> idToInstance.get(record.id()))
                .toList();
    }

    private StreamedRecords readRecordsStreaming(Path path) throws IOException {
        Map<String, RawRecord> records = new LinkedHashMap<>();
        List<String> rootIds = new ArrayList<>();

        try (InputStream inputStream = Files.newInputStream(path);
             JsonParser parser = jsonFactory.createParser(inputStream)) {

            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IllegalArgumentException("Invalid format: root must be an array [main, additional]");
            }

            readRecordArray(parser, records, rootIds, true, "main");
            readRecordArray(parser, records, rootIds, false, "additional");

            if (parser.nextToken() != JsonToken.END_ARRAY) {
                throw new IllegalArgumentException("Invalid format: root must be an array [main, additional]");
            }
        }

        return new StreamedRecords(records, rootIds);
    }

    private void readRecordArray(JsonParser parser,
                                 Map<String, RawRecord> records,
                                 List<String> rootIds,
                                 boolean root,
                                 String label) throws IOException {
        if (parser.nextToken() != JsonToken.START_ARRAY) {
            throw new IllegalArgumentException("Invalid format: " + label + " must be an array");
        }

        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                parser.skipChildren();
                continue;
            }

            ObjectNode node = mapper.readTree(parser);
            RawRecord record = toRawRecord(node, root);
            records.put(record.id(), record);
            if (root) {
                rootIds.add(record.id());
            }
        }
    }

    private RawRecord toRawRecord(ObjectNode node, boolean root) {
        String id = node.path("id").asText();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Invalid format: object id is missing");
        }

        ObjectNode data = asObject(node.get("data"), "data");
        Set<String> references = new LinkedHashSet<>();
        collectReferenceIds(data, references);
        return new RawRecord(id, node, data, references, root);
    }

    private Map<String, ObjectNode> buildNodeIndex(Map<String, RawRecord> records) {
        Map<String, ObjectNode> idToNode = new LinkedHashMap<>();
        records.forEach((id, record) -> idToNode.put(id, record.node()));
        return idToNode;
    }

    private Set<String> collectReachableIds(List<RawRecord> roots, Map<String, RawRecord> records) {
        Set<String> reachable = new LinkedHashSet<>();
        roots.forEach(root -> collectReachable(root, records, reachable));
        return reachable;
    }

    private void collectReachable(RawRecord record, Map<String, RawRecord> records, Set<String> reachable) {
        if (!reachable.add(record.id())) {
            return;
        }

        record.references().stream()
                .map(records::get)
                .filter(java.util.Objects::nonNull)
                .forEach(target -> collectReachable(target, records, reachable));
    }

    private Map<String, Object> instantiateReachableObjects(Set<String> reachableIds, Map<String, RawRecord> records)
            throws ClassNotFoundException {
        Map<String, Object> idToInstance = new LinkedHashMap<>();

        for (String id : records.keySet()) {
            if (!reachableIds.contains(id)) {
                continue;
            }

            RawRecord record = records.get(id);
            Class<?> clazz = getClassFromHumanReadableName(record.node().get("class_name").asText());
            boolean isContainer = isContainer(record.data());

            validateClass(clazz);
            Object instance = isContainer ? createContainerInstance(clazz, record.data()) : createInstance(clazz);
            idToInstance.put(id, instance);
        }

        return idToInstance;
    }

    private void fillReachableObjects(Set<String> reachableIds,
                                      Map<String, RawRecord> records,
                                      Map<String, Object> idToInstance) throws IllegalAccessException {
        for (String id : records.keySet()) {
            if (!reachableIds.contains(id)) {
                continue;
            }

            RawRecord record = records.get(id);
            Object instance = idToInstance.get(id);

            if (isContainer(record.data())) {
                fillContainer(instance, record.data(), idToInstance);
            }
            fillObjectFields(instance, record.data(), idToInstance);
        }
    }

    private ObjectNode asObject(JsonNode node, String label) {
        if (node instanceof ObjectNode objectNode) {
            return objectNode;
        }
        throw new IllegalArgumentException("Invalid format: " + label + " must be an object");
    }

    private ArrayNode asArray(JsonNode node, String label) {
        if (node instanceof ArrayNode arrayNode) {
            return arrayNode;
        }
        throw new IllegalArgumentException("Invalid format: " + label + " must be an array");
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

    private record RawRecord(String id, ObjectNode node, ObjectNode data, Set<String> references, boolean root) {
    }

    private record StreamedRecords(Map<String, RawRecord> records, List<String> rootIds) {
    }
}
