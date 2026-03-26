package org.example.jickle;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import org.example.jickle.annotation.JickleIgnore;
import org.example.jickle.annotation.JicklableClass;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class JickleSerializer {

    private final ObjectMapper mapper;
    private final boolean allowUnsafe;
    private final DefaultPrettyPrinter prettyPrinter;

    public JickleSerializer(boolean allowUnsafe) {
        this.allowUnsafe = allowUnsafe;
        this.mapper = new ObjectMapper();
        this.prettyPrinter = createPrettyPrinter();

        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    public void dump(Object object, String filePath) throws IOException, IllegalAccessException {
        if (object == null) {
            writeEmptyResult(filePath);
            return;
        }
        dumpRoots(List.of(object), filePath);
    }

    public void dumpList(List<?> objList, String filePath) throws IOException, IllegalAccessException {
        if (objList == null || objList.isEmpty()) {
            writeEmptyResult(filePath);
            return;
        }
        dumpRoots(objList, filePath);
    }

    private void dumpRoots(Collection<?> roots, String filePath) throws IOException, IllegalAccessException {
        IdentityHashMap<Object, Integer> idMap = new IdentityHashMap<>();
        List<Object> orderedObjects = new ArrayList<>();
        IdentityHashMap<Object, Boolean> rootSet = new IdentityHashMap<>();
        List<Object> orderedRoots = new ArrayList<>();

        for (Object root : roots) {
            if (root == null) {
                continue;
            }
            ensureSupportedRoot(root);
            collectObjects(root, idMap, orderedObjects);

            if (!rootSet.containsKey(root)) {
                rootSet.put(root, Boolean.TRUE);
                orderedRoots.add(root);
            }
        }

        ArrayNode mainArray = mapper.createArrayNode();
        orderedRoots.forEach(root -> mainArray.add(buildObjectNode(root, idMap)));

        ArrayNode additionalArray = mapper.createArrayNode();
        orderedObjects.stream()
                .filter(obj -> !rootSet.containsKey(obj))
                .forEach(obj -> additionalArray.add(buildObjectNode(obj, idMap)));

        ArrayNode root = mapper.createArrayNode();
        root.add(mainArray);
        root.add(additionalArray);

        String json = mapper.writer(prettyPrinter).writeValueAsString(root);
        json = json.replace(" : ", ": ");
        Files.writeString(Path.of(filePath), json, StandardCharsets.UTF_8);
    }

    private DefaultPrettyPrinter createPrettyPrinter() {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        DefaultIndenter indenter = new DefaultIndenter("  ", DefaultIndenter.SYS_LF);
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return printer;
    }

    private void writeEmptyResult(String filePath) throws IOException {
        Files.writeString(Path.of(filePath), "[[],[]]", StandardCharsets.UTF_8);
    }

    private void ensureSupportedRoot(Object root) {
        if (isSimpleType(root.getClass())) {
            throw new IllegalArgumentException("Simple values cannot be serialized as root objects");
        }
    }

    private void collectObjects(Object object,
                                IdentityHashMap<Object, Integer> idMap,
                                List<Object> orderedObjects) throws IllegalAccessException {
        if (object == null || isSimpleType(object.getClass()) || idMap.containsKey(object)) {
            return;
        }

        validateClass(object.getClass());

        idMap.put(object, orderedObjects.size() + 1);
        orderedObjects.add(object);

        if (object.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(object);
            for (int i = 0; i < length; i++) {
                collectObjects(java.lang.reflect.Array.get(object, i), idMap, orderedObjects);
            }
        } else if (object instanceof Collection<?> collection) {
            for (Object item : collection) {
                collectObjects(item, idMap, orderedObjects);
            }
        } else if (object instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                collectObjects(entry.getKey(), idMap, orderedObjects);
                collectObjects(entry.getValue(), idMap, orderedObjects);
            }
        }

        for (Field field : getSerializableFields(object.getClass())) {
            Object value = readFieldValue(field, object);
            if (value != null && !isSimpleType(value.getClass())) {
                collectObjects(value, idMap, orderedObjects);
            }
        }
    }

    private void validateClass(Class<?> clazz) {
        if (allowUnsafe || clazz.isArray() || isJdkContainer(clazz)) {
            return;
        }

        if (!clazz.isAnnotationPresent(JicklableClass.class)) {
            throw new IllegalArgumentException(
                    "Class " + clazz.getName() + " is not annotated with @JicklableClass " +
                            "(pass allowUnsafe = true if needed)"
            );
        }
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

    private ObjectNode buildObjectNode(Object obj, IdentityHashMap<Object, Integer> idMap) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", idMap.get(obj));
        node.put("class_name", getHumanReadableClassName(obj.getClass()));

        ObjectNode data = mapper.createObjectNode();

        if (obj.getClass().isArray()) {
            data.put("is_container", true);
            data.put("component_type", obj.getClass().getComponentType().getName());

            ArrayNode elements = mapper.createArrayNode();
            int length = java.lang.reflect.Array.getLength(obj);
            for (int i = 0; i < length; i++) {
                addContainerElement(elements, java.lang.reflect.Array.get(obj, i), idMap);
            }
            data.set("elements", elements);
        } else if (obj instanceof Collection<?> collection) {
            data.put("is_container", true);
            data.put("collection_class", obj.getClass().getName());

            ArrayNode elements = mapper.createArrayNode();
            collection.forEach(item -> addContainerElement(elements, item, idMap));
            data.set("elements", elements);
        } else if (obj instanceof Map<?, ?> map) {
            data.put("is_container", true);
            data.put("collection_class", obj.getClass().getName());

            ArrayNode entries = mapper.createArrayNode();
            map.forEach((key, value) -> {
                ArrayNode pair = mapper.createArrayNode();
                addContainerElement(pair, key, idMap);
                addContainerElement(pair, value, idMap);
                entries.add(pair);
            });
            data.set("entries", entries);
        }

        for (Field field : getSerializableFields(obj.getClass())) {
            Object value = readFieldValue(field, obj);
            if (value == null) {
                continue;
            }

            String fieldName = field.getName();
            if (isSimpleType(value.getClass())) {
                putSimpleValue(data, fieldName, value);
                continue;
            }

            Integer refId = idMap.get(value);
            if (refId != null) {
                data.put("object_" + fieldName, refId);
            }
        }

        node.set("data", data);
        return node;
    }

    private void addContainerElement(ArrayNode elements, Object item, IdentityHashMap<Object, Integer> idMap) {
        if (item == null) {
            elements.addNull();
        } else if (isSimpleType(item.getClass())) {
            putSimpleValueToArray(elements, item);
        } else {
            Integer refId = idMap.get(item);
            if (refId != null) {
                elements.add("#" + refId);
            }
        }
    }

    private Object readFieldValue(Field field, Object target) {
        try {
            if (!field.canAccess(target) && !field.trySetAccessible()) {
                return null;
            }
            return field.get(target);
        } catch (RuntimeException | IllegalAccessException ignored) {
            return null;
        }
    }

    private String getHumanReadableClassName(Class<?> clazz) {
        if (clazz.isArray()) {
            return getHumanReadableClassName(clazz.getComponentType()) + "[]";
        }
        return clazz.getName();
    }

    private void putSimpleValueToArray(ArrayNode arr, Object value) {
        if (value instanceof String s) arr.add(s);
        else if (value instanceof Integer i) arr.add(i);
        else if (value instanceof Long l) arr.add(l);
        else if (value instanceof Double d) arr.add(d);
        else if (value instanceof Float f) arr.add(f);
        else if (value instanceof Boolean b) arr.add(b);
        else if (value instanceof Short s) arr.add(s);
        else if (value instanceof Byte b) arr.add(b.intValue());
        else if (value instanceof Character c) arr.add(String.valueOf(c));
        else if (value.getClass().isEnum()) arr.add(((Enum<?>) value).name());
        else arr.addPOJO(value);
    }

    private void putSimpleValue(ObjectNode node, String key, Object value) {
        if (value instanceof String s) node.put(key, s);
        else if (value instanceof Integer i) node.put(key, i);
        else if (value instanceof Long l) node.put(key, l);
        else if (value instanceof Double d) node.put(key, d);
        else if (value instanceof Float f) node.put(key, f);
        else if (value instanceof Boolean b) node.put(key, b);
        else if (value instanceof Short s) node.put(key, s);
        else if (value instanceof Byte b) node.put(key, b.intValue());
        else if (value instanceof Character c) node.put(key, String.valueOf(c));
        else if (value.getClass().isEnum()) node.put(key, ((Enum<?>) value).name());
        else node.putPOJO(key, value);
    }

    private boolean isSimpleType(Class<?> type) {
        return type.isPrimitive() ||
                type == String.class ||
                type == Boolean.class ||
                type == Integer.class ||
                type == Long.class ||
                type == Double.class ||
                type == Float.class ||
                type == Byte.class ||
                type == Short.class ||
                type == Character.class ||
                Number.class.isAssignableFrom(type) ||
                type.isEnum();
    }

    private List<Field> getSerializableFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()) {
                    continue;
                }
                if (field.isAnnotationPresent(JickleIgnore.class)) {
                    continue;
                }
                fields.add(field);
            }
        }
        return fields;
    }
}
