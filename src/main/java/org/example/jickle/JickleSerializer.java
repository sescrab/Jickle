package org.example.jickle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.example.jickle.annotation.JickleIgnore;
import org.example.jickle.annotation.JicklableClass;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        File file = new File(filePath);
        ArrayNode rootArray = mapper.createArrayNode();

        if (object instanceof Collection<?> collection) {
            for (Object item : collection) {
                rootArray.add(serializeSingle(item));
            }
        } else {
            rootArray.add(serializeSingle(object));
        }

        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootArray);
        json = json.replaceAll("\\s*:\\s*", ":");

        Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
    }

    private JsonNode serializeSingle(Object obj) throws IllegalAccessException {
        if (obj == null) {
            return mapper.nullNode();
        }

        Class<?> obj_class = obj.getClass();
        if (!allowUnsafe && !obj_class.isAnnotationPresent(JicklableClass.class)) {
            throw new IllegalArgumentException(
                    "Class " + obj_class.getName() + " is not annotated with @JicklableClass " +
                            "(pass allowUnsafe = true if needed)"
            );
        }

        ObjectNode node = mapper.createObjectNode();
        node.put("class_name", obj_class.getName());

        for (Field field : getAllFields(obj_class)) {
            if (field.isAnnotationPresent(JickleIgnore.class)) {
                continue;
            }

            field.setAccessible(true);
            Object value = field.get(obj);

            if (value == null) {
                continue;
            }

            String fieldName = field.getName();

            if (isSimpleType(value.getClass())) {
                if (value instanceof String) {
                    node.put(fieldName, (String) value);
                } else if (value instanceof Integer || value instanceof Long ||
                        value instanceof Double || value instanceof Float ||
                        value instanceof Boolean || value instanceof Byte ||
                        value instanceof Short || value instanceof Character) {
                    node.putPOJO(fieldName, value);
                } else if (value.getClass().isEnum()) {
                    node.put(fieldName, ((Enum<?>) value).name());
                } else {
                    node.putPOJO(fieldName, value);
                }
            } else if (value instanceof Collection<?> || value.getClass().isArray() ||
                    value instanceof Map<?, ?>) {
                node.set(fieldName, mapper.valueToTree(value));
            } else {
                node.set(fieldName, serializeSingle(value));
            }
        }
        return node;
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
