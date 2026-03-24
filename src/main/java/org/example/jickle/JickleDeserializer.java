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

    public List<Object> load(String filePath) throws IOException, ClassNotFoundException, IllegalAccessException {
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

        Map<String, Object> idToInstance = new HashMap<>();

        // Шаг 1: создание экземпляров
        for (ObjectNode objNode : allObjNodes) {
            String id = objNode.get("id").asText();
            String className = objNode.get("class_name").asText();
            ObjectNode dataNode = (ObjectNode) objNode.get("data");
            boolean isContainer = dataNode.has("is_container") && dataNode.get("is_container").asBoolean();

            Class<?> clazz = getClassFromHumanReadableName(className);

            if (!allowUnsafe && !isContainer && !clazz.isAnnotationPresent(JicklableClass.class)) {
                throw new IllegalArgumentException("Class " + className + " is not annotated with @JicklableClass (pass allowUnsafe = true if needed)");
            }

            Object instance;
            if (isContainer) {
                if (dataNode.has("component_type")) {
                    String compName = dataNode.get("component_type").asText();
                    Class<?> compClass = getClassByName(compName);
                    int length = dataNode.get("elements").size();
                    instance = Array.newInstance(compClass, length);
                } else {
                    String collectionClassName = dataNode.get("collection_class").asText();
                    if (collectionClassName.contains("List") || collectionClassName.contains("ImmutableCollections")) {
                        instance = new ArrayList<>();
                    } else if (collectionClassName.contains("HashMap")) {
                        instance = new HashMap<>();
                    } else {
                        throw new IllegalArgumentException("Collection type '" + collectionClassName + "' is not supported yet.");
                    }
                }
            } else {
                instance = createInstance(clazz);
            }

            idToInstance.put(id, instance);
        }

        // Шаг 2: заполнение
        for (ObjectNode objNode : allObjNodes) {
            String id = objNode.get("id").asText();
            Object instance = idToInstance.get(id);
            ObjectNode dataNode = (ObjectNode) objNode.get("data");
            boolean isContainer = dataNode.has("is_container") && dataNode.get("is_container").asBoolean();

            if (isContainer) {
                if (dataNode.has("component_type")) {
                    // массив
                    Class<?> compClass = getClassByName(dataNode.get("component_type").asText());
                    boolean isSimple = isSimpleType(compClass);
                    ArrayNode elements = (ArrayNode) dataNode.get("elements");
                    for (int i = 0; i < elements.size(); i++) {
                        JsonNode elem = elements.get(i);
                        Object value = resolveValue(elem, compClass, isSimple, idToInstance);
                        Array.set(instance, i, value);
                    }
                } else {
                    // коллекция
                    String collClass = dataNode.get("collection_class").asText();
                    if (collClass.contains("List") || collClass.contains("ImmutableCollections")) {
                        Collection<Object> coll = (Collection<Object>) instance;
                        ArrayNode els = (ArrayNode) dataNode.get("elements");
                        for (JsonNode e : els) {
                            coll.add(resolveValue(e, Object.class, false, idToInstance));
                        }
                    } else if (collClass.contains("HashMap")) {
                        Map<Object, Object> map = (Map<Object, Object>) instance;
                        ArrayNode entries = (ArrayNode) dataNode.get("entries");
                        for (JsonNode p : entries) {
                            ArrayNode pair = (ArrayNode) p;
                            Object k = resolveValue(pair.get(0), Object.class, false, idToInstance);
                            Object v = resolveValue(pair.get(1), Object.class, false, idToInstance);
                            map.put(k, v);
                        }
                    }
                }
            } else {
                // обычный объект
                Class<?> clazz = instance.getClass();
                for (Iterator<Map.Entry<String, JsonNode>> it = dataNode.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    String key = entry.getKey();
                    JsonNode valNode = entry.getValue();

                    String fieldName = key.startsWith("object_") ? key.substring(7) : key;
                    Field field = findField(clazz, fieldName);
                    if (field == null) continue;

                    field.setAccessible(true);
                    Object fieldValue;
                    if (key.startsWith("object_")) {
                        fieldValue = valNode.isNull() ? null : idToInstance.get(valNode.asText());
                    } else {
                        fieldValue = mapper.convertValue(valNode, field.getType());
                    }
                    field.set(instance, fieldValue);
                }
            }
        }

        List<Object> mainObjects = new ArrayList<>();
        for (JsonNode node : mainArray) {
            mainObjects.add(idToInstance.get(node.get("id").asText()));
        }
        return mainObjects;
    }

    private Object resolveValue(JsonNode node, Class<?> type, boolean isSimple, Map<String, Object> idToInstance) {
        if (node.isNull()) return null;
        if (isSimple || !node.isTextual() || !node.textValue().startsWith("#")) {
            return mapper.convertValue(node, type);
        }
        String ref = node.textValue().substring(1);
        return idToInstance.get(ref);
    }

    private void addAndValidateObjects(ArrayNode arrayNode, List<ObjectNode> targetList) {
        for (JsonNode node : arrayNode) {
            if (node.isObject()) targetList.add((ObjectNode) node);
        }
    }

    private Object createInstance(Class<?> clazz) {
        try {
            Constructor<?> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to create instance of " + clazz.getName(), e);
        }
    }

    private Field findField(Class<?> type, String fieldName) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(fieldName);
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
        return switch (name) {
            case "int" -> int.class;
            case "long" -> long.class;
            case "double" -> double.class;
            case "float" -> float.class;
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "short" -> short.class;
            case "char" -> char.class;
            default -> {
                if (name.endsWith("[]")) {
                    String componentName = name.substring(0, name.length() - 2);
                    Class<?> compClass = getClassFromHumanReadableName(componentName);
                    yield Array.newInstance(compClass, 0).getClass();
                }
                if (name.startsWith("[L") && name.endsWith(";")) {
                    String componentName = name.substring(2, name.length() - 1);
                    Class<?> compClass = Class.forName(componentName);
                    yield Array.newInstance(compClass, 0).getClass();
                }
                yield Class.forName(name);
            }
        };
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
}