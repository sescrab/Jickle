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

    //Поддержка старого формата загрузки (без фильтрации)
    public List<Object> load(String filePath) throws IOException, ClassNotFoundException, IllegalAccessException {
        return load(filePath, null);
    }

    //Загрузка с фильтрацией
    //Подгружаются только те объекты из главного списка, которые удовлетворяют построенному фильтру
    //А также те, которые нужны для них (на которые ссылаются)
    //Хотя сами java-объекты создаются только для тех, которые прошли фильтр, но загрузить приходится все равно весь фаил...
    public List<Object> load(String filePath, JickleFilter filter)
            throws IOException, ClassNotFoundException, IllegalAccessException {

        // 1. Читаем JSON
        String jsonContent = Files.readString(new File(filePath).toPath(), StandardCharsets.UTF_8);
        JsonNode rootNode = mapper.readTree(jsonContent);

        if (!rootNode.isArray() || rootNode.size() != 2) {
            throw new IllegalArgumentException("Invalid format: root must be an array [main, additional]");
        }

        ArrayNode mainArray = (ArrayNode) rootNode.get(0);
        ArrayNode additionalArray = (ArrayNode) rootNode.get(1);

        List<ObjectNode> allObjNodes = new ArrayList<>();
        addAndValidateObjects(mainArray, allObjNodes);
        addAndValidateObjects(additionalArray, allObjNodes);

        Map<String, ObjectNode> idToNode = new HashMap<>();
        for (ObjectNode n : allObjNodes) {
            idToNode.put(n.get("id").asText(), n);
        }

        // 2. Применяем фильтр к объектам главного списка
        List<ObjectNode> matchingRootNodes = new ArrayList<>();
        for (JsonNode node : mainArray) {
            ObjectNode objNode = (ObjectNode) node;
            if (filter == null || filter.matches(objNode, idToNode)) {
                matchingRootNodes.add(objNode);
            }
        }

        if (matchingRootNodes.isEmpty()) {
            return Collections.emptyList();
        }

        // 3. Находим все необходимые объекты, чтобы не было висячих ссылок
        Set<String> reachableIds = collectReachableIds(matchingRootNodes, idToNode);

        List<ObjectNode> objectsToProcess = allObjNodes.stream()
                .filter(n -> reachableIds.contains(n.get("id").asText()))
                .toList();

        // 4. Создаём экземпляры объектов, прошедших фильтрацию (и на которые те ссылаются
        Map<String, Object> idToInstance = new HashMap<>();
        for (ObjectNode objNode : objectsToProcess) {
            String id = objNode.get("id").asText();
            ObjectNode dataNode = (ObjectNode) objNode.get("data");
            boolean isContainer = dataNode.has("is_container") && dataNode.get("is_container").asBoolean();

            Class<?> clazz = getClassFromHumanReadableName(objNode.get("class_name").asText());

            if (!allowUnsafe && !isContainer && !clazz.isAnnotationPresent(JicklableClass.class)) {
                throw new IllegalArgumentException("Class " + clazz.getName() + " is not annotated with @JicklableClass");
            }

            Object instance = isContainer ? createContainerInstance(dataNode) : createInstance(clazz);
            idToInstance.put(id, instance);
        }

        // 5. Заполняем поля
        for (ObjectNode objNode : objectsToProcess) {
            String id = objNode.get("id").asText();
            Object instance = idToInstance.get(id);
            ObjectNode dataNode = (ObjectNode) objNode.get("data");
            boolean isContainer = dataNode.has("is_container") && dataNode.get("is_container").asBoolean();

            if (isContainer) {
                fillContainer(instance, dataNode, idToInstance);
            } else {
                fillObject(instance, dataNode, idToInstance);
            }
        }

        // 6. Возвращаем только собственно объекты, прошедшие фильтрацию
        List<Object> result = new ArrayList<>();
        for (ObjectNode node : matchingRootNodes) {
            result.add(idToInstance.get(node.get("id").asText()));
        }
        return result;
    }

    private Set<String> collectReachableIds(List<ObjectNode> roots, Map<String, ObjectNode> idToNode) {
        Set<String> reachable = new HashSet<>();
        for (ObjectNode root : roots) {
            collectReachable(root, idToNode, reachable);
        }
        return reachable;
    }

    private void collectReachable(ObjectNode node, Map<String, ObjectNode> idToNode, Set<String> reachable) {
        String id = node.get("id").asText();
        if (reachable.contains(id)) return;
        reachable.add(id);

        ObjectNode data = (ObjectNode) node.get("data");
        boolean isContainer = data.has("is_container") && data.get("is_container").asBoolean();

        if (isContainer) {
            if (data.has("elements")) {
                for (JsonNode e : (ArrayNode) data.get("elements")) {
                    if (JickleFilter.isReference(e)) {  // используем статический метод из фильтра
                        String refId = JickleFilter.extractRefId(e);
                        ObjectNode target = idToNode.get(refId);
                        if (target != null) collectReachable(target, idToNode, reachable);
                    }
                }
            } else if (data.has("entries")) {
                for (JsonNode pairNode : (ArrayNode) data.get("entries")) {
                    ArrayNode pair = (ArrayNode) pairNode;
                    for (JsonNode item : pair) {
                        if (JickleFilter.isReference(item)) {
                            String refId = JickleFilter.extractRefId(item);
                            ObjectNode target = idToNode.get(refId);
                            if (target != null) collectReachable(target, idToNode, reachable);
                        }
                    }
                }
            }
        } else {
            for (Iterator<Map.Entry<String, JsonNode>> it = data.fields(); it.hasNext(); ) {
                JsonNode val = it.next().getValue();
                if (JickleFilter.isReference(val)) {
                    String refId = JickleFilter.extractRefId(val);
                    ObjectNode target = idToNode.get(refId);
                    if (target != null) collectReachable(target, idToNode, reachable);
                }
            }
        }
    }

    private Object createContainerInstance(ObjectNode dataNode) throws ClassNotFoundException {
        if (dataNode.has("component_type")) {
            String compName = dataNode.get("component_type").asText();
            Class<?> compClass = getClassByName(compName);
            int length = dataNode.get("elements").size();
            return Array.newInstance(compClass, length);
        } else {
            String collClass = dataNode.get("collection_class").asText();
            if (collClass.contains("List") || collClass.contains("ImmutableCollections")) {
                return new ArrayList<>();
            } else if (collClass.contains("HashMap")) {
                return new HashMap<>();
            }
            throw new IllegalArgumentException("Unsupported collection: " + collClass);
        }
    }

    private void fillContainer(Object instance, ObjectNode dataNode, Map<String, Object> idToInstance) {
        if (dataNode.has("component_type")) {
            // массив
            ArrayNode elements = (ArrayNode) dataNode.get("elements");
            for (int i = 0; i < elements.size(); i++) {
                Object value = resolveValue(elements.get(i), Object.class, false, idToInstance);
                Array.set(instance, i, value);
            }
        } else {
            // коллекция или словарь
            if (instance instanceof Collection) {
                Collection<Object> coll = (Collection<Object>) instance;
                ArrayNode els = (ArrayNode) dataNode.get("elements");
                for (JsonNode e : els) {
                    coll.add(resolveValue(e, Object.class, false, idToInstance));
                }
            } else if (instance instanceof Map) {
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
    }

    private void fillObject(Object instance, ObjectNode dataNode, Map<String, Object> idToInstance) throws IllegalAccessException {
        Class<?> clazz = instance.getClass();
        for (Iterator<Map.Entry<String, JsonNode>> it = dataNode.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            String key = entry.getKey();
            JsonNode valNode = entry.getValue();

            String fieldName = key.startsWith("object_") ? key.substring(7) : key;
            Field field = findField(clazz, fieldName);
            if (field == null) continue;

            field.setAccessible(true);
            Object fieldValue = key.startsWith("object_")
                    ? (valNode.isNull() ? null : idToInstance.get(valNode.asText()))
                    : mapper.convertValue(valNode, field.getType());

            field.set(instance, fieldValue);
        }
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
        if (name.endsWith("[]")) {
            String componentName = name.substring(0, name.length() - 2);
            Class<?> compClass = getClassFromHumanReadableName(componentName);
            return Array.newInstance(compClass, 0).getClass();
        }
        if (name.startsWith("[L") && name.endsWith(";")) {
            String componentName = name.substring(2, name.length() - 1);
            Class<?> compClass = Class.forName(componentName);
            return Array.newInstance(compClass, 0).getClass();
        }
        return switch (name) {
            case "int" -> int.class;
            case "long" -> long.class;
            case "double" -> double.class;
            case "float" -> float.class;
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "short" -> short.class;
            case "char" -> char.class;
            default -> Class.forName(name);
        };
    }
}