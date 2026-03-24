package org.example.jickle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.jickle.annotation.JicklableClass;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
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

            ObjectNode dataNode = (ObjectNode) objNode.get("data");
            boolean isContainer = dataNode.has("is_container") && dataNode.get("is_container").asBoolean();

            Class<?> clazz;
            try {
                clazz = Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("Class not found: " + className, e);
            }

            if (!allowUnsafe && !isContainer && !clazz.isAnnotationPresent(JicklableClass.class)) {
                throw new IllegalArgumentException(
                        "Class " + className + " is not annotated with @JicklableClass " +
                                "(pass allowUnsafe = true if needed)"
                );
            }

            Object instance;
            if (isContainer) {
                if (clazz.isArray()) {
                    String compName = dataNode.get("component_type").asText();
                    Class<?> compClass;
                    try {
                        compClass = getClassByName(compName);
                    } catch (ClassNotFoundException e) {
                        throw new IllegalArgumentException("Component class not found: " + compName, e);
                    }
                    int length = dataNode.get("elements").size();
                    instance = Array.newInstance(compClass, length);
                } else {
                    // прочие коллекции распознаём как ArrayList (пока)
                    instance = new ArrayList<>();
                }
            } else {
                instance = createInstance(clazz);
            }

            if (idToInstance.containsKey(id)) {
                throw new IllegalArgumentException("Duplicate id: " + id);
            }

            idToInstance.put(id, instance);
        }

        // Шаг 2: заполняем поля всех объектов (или элементы контейнеров)
        for (ObjectNode objNode : allObjNodes) {
            String id = objNode.get("id").asText();
            Object instance = idToInstance.get(id);
            ObjectNode dataNode = (ObjectNode) objNode.get("data");

            boolean isContainer = dataNode.has("is_container") && dataNode.get("is_container").asBoolean();

            if (isContainer) {
                JsonNode elementsNode = dataNode.get("elements");
                if (elementsNode == null || !elementsNode.isArray()) {
                    throw new IllegalArgumentException("Container object missing valid 'elements' array");
                }
                ArrayNode elements = (ArrayNode) elementsNode;

                if (dataNode.has("component_type")) {
                    // массив
                    String compName = dataNode.get("component_type").asText();
                    Class<?> compClass;
                    try {
                        compClass = getClassByName(compName);
                    } catch (ClassNotFoundException e) {
                        throw new IllegalArgumentException("Component class not found: " + compName, e);
                    }
                    boolean isSimpleComponent = isSimpleType(compClass);

                    for (int i = 0; i < elements.size(); i++) {
                        JsonNode elemNode = elements.get(i);
                        Object elemValue;
                        if (elemNode.isNull()) {
                            elemValue = null;
                        } else if (isSimpleComponent) {
                            elemValue = mapper.convertValue(elemNode, compClass);
                        } else {
                            String refId = elemNode.asText();
                            elemValue = idToInstance.get(refId);
                            if (elemValue == null) {
                                throw new IllegalArgumentException(
                                        "Referenced object with id " + refId + " not found for array element"
                                );
                            }
                        }
                        Array.set(instance, i, elemValue);
                    }
                } else {
                    // коллекция (распознаём как ArrayList из шага 1)
                    Collection<Object> coll = (Collection<Object>) instance;
                    for (JsonNode elemNode : elements) {
                        Object elemValue;
                        if (elemNode.isNull()) {
                            elemValue = null;
                        } else if (elemNode.isNumber()) {
                            String refId = elemNode.asText();
                            if (idToInstance.containsKey(refId)) {
                                elemValue = idToInstance.get(refId);
                            } else {
                                elemValue = mapper.convertValue(elemNode, Object.class);
                            }
                        } else {
                            elemValue = mapper.convertValue(elemNode, Object.class);
                        }
                        coll.add(elemValue);
                    }
                }
            } else {
                // обычный объект (исходная логика)
                Class<?> clazz = instance.getClass();

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

    // Вспомогательный метод для разрешения примитивных типов в component_type (массивы)
    private Class<?> getClassByName(String className) throws ClassNotFoundException {
        if ("int".equals(className)) return int.class;
        if ("long".equals(className)) return long.class;
        if ("double".equals(className)) return double.class;
        if ("float".equals(className)) return float.class;
        if ("boolean".equals(className)) return boolean.class;
        if ("byte".equals(className)) return byte.class;
        if ("short".equals(className)) return short.class;
        if ("char".equals(className)) return char.class;
        return Class.forName(className);
    }

    // Копия isSimpleType из сериализатора (для определения, как интерпретировать элементы массива)
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
}