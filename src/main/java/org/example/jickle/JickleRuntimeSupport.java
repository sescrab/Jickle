package org.example.jickle;

import org.example.jickle.annotation.JickleIgnore;
import org.example.jickle.annotation.JicklableClass;
import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

final class JickleRuntimeSupport {

    private static final Set<String> EXPLICITLY_SUPPORTED_CLASS_NAMES = Set.of(
            ArrayList.class.getName(),
            LinkedList.class.getName(),
            LinkedHashMap.class.getName(),
            LinkedHashSet.class.getName(),
            ArrayDeque.class.getName()
    );

    private static final List<String> EXPLICITLY_SUPPORTED_PREFIXES = List.of(
            ArrayList.class.getName() + "$",
            LinkedList.class.getName() + "$",
            LinkedHashMap.class.getName() + "$",
            "java.util.HashMap$",
            LinkedHashSet.class.getName() + "$",
            ArrayDeque.class.getName() + "$"
    );

    private static final Unsafe UNSAFE = initUnsafe();

    private JickleRuntimeSupport() {
    }

    static boolean isSerializableClass(Class<?> clazz, boolean allowUnsafe) {
        return allowUnsafe || clazz.isArray() || clazz.isAnnotationPresent(JicklableClass.class) || isExplicitlySupportedClass(clazz);
    }

    static boolean isExplicitlySupportedClass(Class<?> clazz) {
        String className = clazz.getName();
        if (EXPLICITLY_SUPPORTED_CLASS_NAMES.contains(className)) {
            return true;
        }
        for (String prefix : EXPLICITLY_SUPPORTED_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static List<Field> getSerializableFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || field.isSynthetic()) {
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

    static Object readFieldValue(Field field, Object target) {
        try {
            if (field.canAccess(target) || field.trySetAccessible()) {
                return field.get(target);
            }
        } catch (RuntimeException | IllegalAccessException ignored) {
        }
        return readViaUnsafe(field, target);
    }

    static void writeFieldValue(Field field, Object target, Object value) throws IllegalAccessException {
        try {
            if (field.canAccess(target) || field.trySetAccessible()) {
                field.set(target, value);
                return;
            }
        } catch (RuntimeException ignored) {
        }
        writeViaUnsafe(field, target, value);
    }

    static Object createInstance(Class<?> clazz, boolean allowUnsafe) {
        Object constructed = tryCreateInstance(clazz);
        if (constructed != null) {
            return constructed;
        }

        if (isExplicitlySupportedClass(clazz) || allowUnsafe) {
            try {
                return UNSAFE.allocateInstance(clazz);
            } catch (InstantiationException e) {
                throw new IllegalArgumentException("Failed to allocate instance of " + clazz.getName(), e);
            }
        }

        throw new IllegalArgumentException("Failed to create instance of " + clazz.getName());
    }

    private static Object tryCreateInstance(Class<?> clazz) {
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

    private static Object readViaUnsafe(Field field, Object target) {
        long offset = UNSAFE.objectFieldOffset(field);
        Class<?> type = field.getType();

        if (type == int.class) return UNSAFE.getInt(target, offset);
        if (type == long.class) return UNSAFE.getLong(target, offset);
        if (type == boolean.class) return UNSAFE.getBoolean(target, offset);
        if (type == byte.class) return UNSAFE.getByte(target, offset);
        if (type == short.class) return UNSAFE.getShort(target, offset);
        if (type == char.class) return UNSAFE.getChar(target, offset);
        if (type == float.class) return UNSAFE.getFloat(target, offset);
        if (type == double.class) return UNSAFE.getDouble(target, offset);
        return UNSAFE.getObject(target, offset);
    }

    private static void writeViaUnsafe(Field field, Object target, Object value) {
        long offset = UNSAFE.objectFieldOffset(field);
        Class<?> type = field.getType();

        if (type == int.class) {
            UNSAFE.putInt(target, offset, value == null ? 0 : ((Number) value).intValue());
            return;
        }
        if (type == long.class) {
            UNSAFE.putLong(target, offset, value == null ? 0L : ((Number) value).longValue());
            return;
        }
        if (type == boolean.class) {
            UNSAFE.putBoolean(target, offset, value != null && (Boolean) value);
            return;
        }
        if (type == byte.class) {
            UNSAFE.putByte(target, offset, value == null ? (byte) 0 : ((Number) value).byteValue());
            return;
        }
        if (type == short.class) {
            UNSAFE.putShort(target, offset, value == null ? (short) 0 : ((Number) value).shortValue());
            return;
        }
        if (type == char.class) {
            UNSAFE.putChar(target, offset, value == null ? '\0' : (Character) value);
            return;
        }
        if (type == float.class) {
            UNSAFE.putFloat(target, offset, value == null ? 0f : ((Number) value).floatValue());
            return;
        }
        if (type == double.class) {
            UNSAFE.putDouble(target, offset, value == null ? 0d : ((Number) value).doubleValue());
            return;
        }
        UNSAFE.putObject(target, offset, value);
    }

    private static Unsafe initUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to initialize Unsafe access for serializer", e);
        }
    }
}
