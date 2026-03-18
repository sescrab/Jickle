package org.example.jickle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.example.jickle.annotation.JickleIgnore;
import org.example.jickle.annotation.JicklableClass;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class JickleSerializer {

    private final ObjectMapper mapper;
    private final boolean allowUnsafe;

    public JickleSerializer(boolean allowUnsafe) {
        this.allowUnsafe = allowUnsafe;
        this.mapper = new ObjectMapper();

        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    public void dump(Object object, String filePath) throws IOException, IllegalAccessException {
        Map<Object, Integer> idMap = new IdentityHashMap<>();
        Set<Object> rootObjects = new HashSet<>();

        if (object instanceof Collection<?> collection) {
            for (Object item : collection) {
                collectObjects(item, idMap);
                if (item != null) rootObjects.add(item);
            }
        } else if (object.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(object);
            for (int i = 0; i < len; i++) {
                Object item = java.lang.reflect.Array.get(object, i);
                collectObjects(item, idMap);
            }
            collectObjects(object, idMap);
            rootObjects.add(object);
        } else {
            collectObjects(object, idMap);
            if (object != null) rootObjects.add(object);
        }

        ArrayNode mainArray = mapper.createArrayNode();
        ArrayNode extraArray = mapper.createArrayNode();

        for (Object obj : rootObjects) {
            mainArray.add(buildObjectNode(obj, idMap));
        }

        List<Object> extras = new ArrayList<>(idMap.keySet());
        extras.removeAll(rootObjects);
        extras.sort(Comparator.comparingInt(idMap::get));

        for (Object obj : extras) {
            extraArray.add(buildObjectNode(obj, idMap));
        }

        ArrayNode root = mapper.createArrayNode();
        root.add(mainArray);
        root.add(extraArray);

        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        json = json.replaceAll("\\s*:\\s*", ":");

        Files.writeString(Path.of(filePath), json, StandardCharsets.UTF_8);
    }

    private void collectObjects(Object obj, Map<Object, Integer> idMap) throws IllegalAccessException {
        if (obj == null || idMap.containsKey(obj)) {
            return;
        }

        Class<?> cls = obj.getClass();
        boolean isArray = cls.isArray();
        boolean isCollection = !isArray && (obj instanceof Collection<?>);

        if (!isArray && !isCollection) {
            if (!allowUnsafe && !cls.isAnnotationPresent(JicklableClass.class)) {
                throw new IllegalArgumentException(
                        "Class " + cls.getName() + " is not annotated with @JicklableClass " +
                                "(pass allowUnsafe = true if needed)"
                );
            }
        }

        int id = idMap.size() + 1;
        idMap.put(obj, id);

        if (isArray) {
            int length = java.lang.reflect.Array.getLength(obj);
            for (int i = 0; i < length; i++) {
                Object item = java.lang.reflect.Array.get(obj, i);
                collectObjects(item, idMap);
            }
        } else if (isCollection) {
            for (Object item : (Collection<?>) obj) {
                collectObjects(item, idMap);
            }
        } else {
            for (Field field : getAllFields(cls)) {
                if (field.isAnnotationPresent(JickleIgnore.class)) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(obj);
                if (value == null) {
                    continue;
                }

                if (!isSimpleType(value.getClass()) &&
                        !(value instanceof Collection<?> || value.getClass().isArray())) {
                    collectObjects(value, idMap);
                }
            }
        }
    }

    private ObjectNode buildObjectNode(Object obj, Map<Object, Integer> idMap) throws IllegalAccessException {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", idMap.get(obj));
        node.put("class_name", obj.getClass().getName());

        ObjectNode data = mapper.createObjectNode();

        boolean isArray = obj.getClass().isArray();
        boolean isCollection = obj instanceof Collection<?> && !isArray;

        if (isArray || isCollection) {
            data.put("is_container", true);
            if (isArray) {
                data.put("component_type", obj.getClass().getComponentType().getName());
            }

            ArrayNode elements = mapper.createArrayNode();
            Iterable<?> iterable = isArray
                    ? () -> java.util.Arrays.stream((Object[]) obj).iterator()
                    : (Collection<?>) obj;

            for (Object item : iterable) {
                if (item == null) {
                    elements.addNull();
                } else if (isSimpleType(item.getClass())) {
                    putSimpleValueToArray(elements, item);
                } else {
                    Integer refId = idMap.get(item);
                    if (refId != null) {
                        elements.add(refId);
                    }
                }
            }
            data.set("elements", elements);
        } else {
            for (Field field : getAllFields(obj.getClass())) {
                if (field.isAnnotationPresent(JickleIgnore.class)) continue;
                field.setAccessible(true);
                Object value = field.get(obj);
                if (value == null) continue;

                String fieldName = field.getName();

                if (isSimpleType(value.getClass())) {
                    putSimpleValue(data, fieldName, value);
                } else if (value instanceof Collection<?> || value.getClass().isArray()) {
                    Integer refId = idMap.get(value);
                    if (refId != null) {
                        data.put("object_" + fieldName, refId);
                    }
                } else {
                    Integer refId = idMap.get(value);
                    if (refId != null) {
                        data.put("object_" + fieldName, refId);
                    }
                }
            }
        }

        node.set("data", data);
        return node;
    }

    private void putSimpleValueToArray(ArrayNode arr, Object value) {
        if (value instanceof String s) arr.add(s);
        else if (value instanceof Integer i) arr.add(i);
        else if (value instanceof Long l) arr.add(l);
        else if (value instanceof Double d) arr.add(d);
        else if (value instanceof Float f) arr.add(f);
        else if (value instanceof Boolean b) arr.add(b);
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

    private List<Field> getAllFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            fields.addAll(Arrays.asList(c.getDeclaredFields()));
        }
        return fields;
    }
}