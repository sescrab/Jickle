package org.example.jickle;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class JickleDeserializer {

    private static final Set<String> ARRAY_METADATA_KEYS = Set.of(
            "is_container",
            "component_type",
            "elements"
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
        Path path = Path.of(filePath);
        RootSelection rootSelection = selectMatchingRoots(path, filter);
        if (rootSelection.rootIds().isEmpty()) {
            return List.of();
        }

        Map<String, RawRecord> records = new LinkedHashMap<>(rootSelection.records());
        Set<String> wantedIds = new LinkedHashSet<>(rootSelection.rootIds());
        rootSelection.rootIds().stream()
                .map(records::get)
                .filter(Objects::nonNull)
                .forEach(record -> wantedIds.addAll(record.references()));

        while (scanNeededRecords(path, wantedIds, records)) {
            // keep scanning until no newly discovered backwards dependencies remain
        }

        Map<String, Object> idToInstance = instantiateObjects(records);
        fillObjects(records, idToInstance);

        return rootSelection.rootIds().stream()
                .map(idToInstance::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private RootSelection selectMatchingRoots(Path path, JickleFilter filter) throws IOException {
        Map<String, RawRecord> selectedRoots = new LinkedHashMap<>();
        List<String> rootIds = new ArrayList<>();

        scanFile(path, (record, root) -> {
            if (!root) {
                return;
            }
            if (filter == null || filter.matches(record.data())) {
                selectedRoots.put(record.id(), record);
                rootIds.add(record.id());
            }
        });

        return new RootSelection(selectedRoots, rootIds);
    }

    private boolean scanNeededRecords(Path path, Set<String> wantedIds, Map<String, RawRecord> records) throws IOException {
        boolean[] changed = {false};

        scanFile(path, (record, root) -> {
            if (!wantedIds.contains(record.id()) || records.containsKey(record.id())) {
                return;
            }

            records.put(record.id(), record);
            if (wantedIds.addAll(record.references())) {
                changed[0] = true;
            }
            changed[0] = true;
        });

        return changed[0];
    }

    private void scanFile(Path path, RecordConsumer consumer) throws IOException {
        try (InputStream inputStream = Files.newInputStream(path);
             JsonParser parser = jsonFactory.createParser(inputStream)) {

            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IllegalArgumentException("Invalid format: root must be an array [main, additional]");
            }

            scanRecordArray(parser, true, consumer, "main");
            scanRecordArray(parser, false, consumer, "additional");

            if (parser.nextToken() != JsonToken.END_ARRAY) {
                throw new IllegalArgumentException("Invalid format: root must be an array [main, additional]");
            }
        }
    }

    private void scanRecordArray(JsonParser parser, boolean root, RecordConsumer consumer, String label) throws IOException {
        if (parser.nextToken() != JsonToken.START_ARRAY) {
            throw new IllegalArgumentException("Invalid format: " + label + " must be an array");
        }

        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                parser.skipChildren();
                continue;
            }

            ObjectNode objectNode = mapper.readTree(parser);
            consumer.accept(toRawRecord(objectNode), root);
        }
    }

    private RawRecord toRawRecord(ObjectNode node) {
        String id = node.path("id").asText();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Invalid format: object id is missing");
        }

        String className = node.path("class_name").asText();
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("Invalid format: class_name is missing");
        }

        ObjectNode data = asObject(node.get("data"), "data");
        Set<String> references = new LinkedHashSet<>();
        collectReferenceIds(data, references);
        return new RawRecord(id, className, data, references);
    }

    private Map<String, Object> instantiateObjects(Map<String, RawRecord> records) throws ClassNotFoundException {
        Map<String, Object> idToInstance = new LinkedHashMap<>();

        for (RawRecord record : records.values()) {
            Class<?> clazz = getClassFromHumanReadableName(record.className());
            validateClass(clazz);

            Object instance = clazz.isArray()
                    ? createArrayInstance(clazz, record.data())
                    : JickleRuntimeSupport.createInstance(clazz, allowUnsafe);

            idToInstance.put(record.id(), instance);
        }

        return idToInstance;
    }

    private void fillObjects(Map<String, RawRecord> records, Map<String, Object> idToInstance)
            throws IllegalAccessException {
        for (RawRecord record : records.values()) {
            Object instance = idToInstance.get(record.id());
            if (instance == null) {
                continue;
            }

            if (instance.getClass().isArray()) {
                fillArray(instance, record.data().path("elements"), idToInstance);
                continue;
            }

            fillObjectFields(instance, record.data(), idToInstance);
        }
    }

    private void validateClass(Class<?> clazz) {
        if (JickleRuntimeSupport.isSerializableClass(clazz, allowUnsafe)) {
            return;
        }

        throw new IllegalArgumentException(
                "Class " + clazz.getName() + " is not annotated with @JicklableClass or explicitly supported"
        );
    }

    private Object createArrayInstance(Class<?> clazz, ObjectNode dataNode) throws ClassNotFoundException {
        String componentTypeName = dataNode.path("component_type").asText();
        Class<?> componentType = getClassByName(componentTypeName);
        int length = asArray(dataNode.get("elements"), "elements").size();
        return Array.newInstance(componentType, length);
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

    private void fillArray(Object array, JsonNode elementsNode, Map<String, Object> idToInstance) {
        ArrayNode elements = asArray(elementsNode, "elements");
        Class<?> componentType = array.getClass().getComponentType();

        for (int index = 0; index < elements.size(); index++) {
            Array.set(array, index, resolveValue(elements.get(index), componentType, idToInstance));
        }
    }

    private void fillObjectFields(Object instance, ObjectNode dataNode, Map<String, Object> idToInstance)
            throws IllegalAccessException {
        var fields = dataNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            if (ARRAY_METADATA_KEYS.contains(key)) {
                continue;
            }

            String fieldName = key.startsWith("object_") ? key.substring(7) : key;
            Field field = findField(instance.getClass(), fieldName);
            if (field == null) {
                continue;
            }

            Object value = key.startsWith("object_")
                    ? idToInstance.get(JickleFilter.extractRefId(entry.getValue()))
                    : mapper.convertValue(entry.getValue(), field.getType());

            JickleRuntimeSupport.writeFieldValue(field, instance, value);
        }
    }

    private Object resolveValue(JsonNode node, Class<?> targetType, Map<String, Object> idToInstance) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (JickleFilter.isReference(node)) {
            return idToInstance.get(JickleFilter.extractRefId(node));
        }
        return mapper.convertValue(node, targetType);
    }

    private Field findField(Class<?> type, String fieldName) {
        for (Field field : JickleRuntimeSupport.getSerializableFields(type)) {
            if (field.getName().equals(fieldName)) {
                return field;
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

    @FunctionalInterface
    private interface RecordConsumer {
        void accept(RawRecord record, boolean root);
    }

    private record RawRecord(String id, String className, ObjectNode data, Set<String> references) {
    }

    private record RootSelection(Map<String, RawRecord> records, List<String> rootIds) {
    }
}
