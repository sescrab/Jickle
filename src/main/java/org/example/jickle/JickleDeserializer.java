package org.example.jickle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.jickle.annotation.JicklableClass;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public class JickleDeserializer {

    private final ObjectMapper mapper;
    private final boolean allowUnsafe;

    public JickleDeserializer(boolean allowUnsafe) {
        this.allowUnsafe = allowUnsafe;
        this.mapper = new ObjectMapper();
    }

    public List<Object> load(String filePath) throws IOException {
        File file = new File(filePath);
        String jsonContent = Files.readString(file.toPath(), StandardCharsets.UTF_8);

        JsonNode rootNode = mapper.readTree(jsonContent);

        if (!rootNode.isArray() || rootNode.size() != 2) {
            throw new IllegalArgumentException("Invalid format: root must be an array containing exactly two arrays [main, additional]");
        }

        ArrayNode mainArray = (ArrayNode) rootNode.get(0);
        ArrayNode additionalArray = (ArrayNode) rootNode.get(1);

        List<ObjectNode> allObjNodes = new ArrayList<>();
        addAndValidateObjects(mainArray, allObjNodes);
        addAndValidateObjects(additionalArray, allObjNodes);

        // Шаг 1: создаём все экземпляры (чтобы обработать циклы и shared-объекты)
        Map<String, Object> idToInstance = new HashMap<>();
        for (ObjectNode objNode : allObjNodes) {
            String id = objNode.get("id").asText();
            String className = objNode.get("class_name").asText();

            if (idToInstance.containsKey(id)) {
                throw new IllegalArgumentException("Duplicate id: " + id);
            }

            Class<?> clazz;
            try {
                clazz = Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("Class not found: " + className, e);
            }

            if (!allowUnsafe && !clazz.isAnnotationPresent(JicklableClass.class)) {
                throw new IllegalArgumentException(
                        "Class " + className + " is not annotated with @JicklableClass " +
                                "(pass allowUnsafe = true if needed)"
                );
            }

            Object instance = createInstance(clazz);
            idToInstance.put(id, instance);
        }

        // Шаг 2: заполняем поля всех объектов
        for (ObjectNode objNode : allObjNodes) {
            String id = objNode.get("id").asText();
            Object instance = idToInstance.get(id);
            Class<?> clazz = instance.getClass();

            ObjectNode dataNode = (ObjectNode) objNode.get("data");

            for (Iterator<Map.Entry<String, JsonNode>> it = dataNode.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                String key = entry.getKey();
                JsonNode valNode = entry.getValue();

                String fieldName;
                boolean isObjectRef = key.startsWith("object_");
                if (isObjectRef) {
                    fieldName = key.substring("object_".length());
                } else {
                    fieldName = key;
                }

                Field field = findField(clazz, fieldName);
                if (field == null) {
                    throw new IllegalArgumentException(
                            "Class " + clazz.getName() + " does not have field '" + fieldName + "' " +
                                    "(different set of fields)"
                    );
                }

                field.setAccessible(true);

                Object fieldValue;
                if (isObjectRef) {
                    if (valNode.isNull()) {
                        fieldValue = null;
                    } else {
                        String refId = valNode.asText();
                        fieldValue = idToInstance.get(refId);
                        if (fieldValue == null) {
                            throw new IllegalArgumentException(
                                    "Referenced object with id " + refId + " not found for field " + fieldName
                            );
                        }
                    }
                } else {
                    // Примитивы, String, Enum и другие простые типы
                    fieldValue = mapper.convertValue(valNode, field.getType());
                }

                try {
                    field.set(instance, fieldValue);
                } catch (IllegalAccessException e) {
                    throw new IllegalArgumentException("Failed to set field " + fieldName + " in " + clazz.getName(), e);
                }
            }
        }

        // Возвращаем только основные объекты
        List<Object> mainObjects = new ArrayList<>();
        for (JsonNode node : mainArray) {
            String id = node.get("id").asText();
            mainObjects.add(idToInstance.get(id));
        }

        return mainObjects;
    }

    private void addAndValidateObjects(ArrayNode arrayNode, List<ObjectNode> targetList) {
        for (JsonNode node : arrayNode) {
            if (!node.isObject()) {
                throw new IllegalArgumentException("Expected object node in array");
            }
            ObjectNode objNode = (ObjectNode) node;

            if (!objNode.hasNonNull("id") ||
                    !objNode.hasNonNull("class_name") ||
                    !objNode.hasNonNull("data") ||
                    !objNode.get("data").isObject()) {
                throw new IllegalArgumentException("Invalid object: missing or invalid 'id', 'class_name' or 'data'");
            }

            targetList.add(objNode);
        }
    }

    private Object createInstance(Class<?> clazz) {
        try {
            Constructor<?> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Class " + clazz.getName() + " has no no-argument constructor", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Failed to instantiate " + clazz.getName(), e);
        }
    }

    private Field findField(Class<?> type, String fieldName) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                // продолжаем поиск в суперклассе
            }
        }
        return null;
    }
}