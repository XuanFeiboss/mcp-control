package com.xuanfei.mcpcontrol.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Defensive reflection helpers for the client layer.
 *
 * <p>Screen/widget internals and a few rendering entry points are looked up by name at runtime instead
 * of being hard-linked at compile time. That keeps the mod buildable across 1.21.x mapping refreshes,
 * and when a member really is missing the tool returns a readable message instead of crashing.</p>
 */
public final class Ref {

    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();

    private Ref() {
    }

    // ---------------------------------------------------------------- fields

    public static Object get(Object target, String... names) {
        if (target == null) {
            return null;
        }
        for (String name : names) {
            Field field = findField(target.getClass(), name);
            if (field != null) {
                try {
                    field.setAccessible(true);
                    return field.get(target);
                } catch (Throwable ignored) {
                    // try the next candidate name
                }
            }
        }
        return null;
    }

    public static Boolean getBoolean(Object target, String... names) {
        Object value = get(target, names);
        return value instanceof Boolean bool ? bool : null;
    }

    public static int getInt(Object target, String... names) {
        Object value = get(target, names);
        return value instanceof Number number ? number.intValue() : 0;
    }

    public static boolean set(Object target, String name, Object value) {
        if (target == null) {
            return false;
        }
        Field field = findField(target.getClass(), name);
        if (field == null) {
            return false;
        }
        try {
            field.setAccessible(true);
            field.set(target, convertOne(field.getType(), value));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- methods

    public static boolean has(Object target, String name, int parameterCount) {
        return target != null && findMethod(target.getClass(), name, parameterCount) != null;
    }

    public static Object call(Object target, String name, Object... args) {
        if (target == null) {
            throw new IllegalStateException("Cannot call " + name + "() on a null target");
        }
        Method method = findMethod(target.getClass(), name, args == null ? 0 : args.length);
        if (method == null) {
            throw new IllegalStateException("Missing method " + name + "/" + (args == null ? 0 : args.length)
                    + " on " + target.getClass().getName());
        }
        try {
            method.setAccessible(true);
            return method.invoke(target, convert(method.getParameterTypes(), args));
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause() == null ? wrapped : wrapped.getCause();
            throw new IllegalStateException(name + "() failed: " + cause, cause);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(name + "() failed: " + error, error);
        }
    }

    public static Object callOpt(Object target, String name, Object... args) {
        try {
            return call(target, name, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object callStatic(Class<?> type, String name, Object... args) {
        if (type == null) {
            return null;
        }
        for (Method method : allMethods(type)) {
            if (!method.getName().equals(name) || method.getParameterCount() != (args == null ? 0 : args.length)) {
                continue;
            }
            if (!Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            try {
                method.setAccessible(true);
                return method.invoke(null, convert(method.getParameterTypes(), args));
            } catch (Throwable error) {
                throw new IllegalStateException(name + "() failed: " + error, error);
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) {
        String key = type.getName() + "#" + name + "#" + parameterCount;
        Method cached = METHODS.get(key);
        if (cached != null) {
            return cached;
        }
        Method best = null;
        for (Method method : allMethods(type)) {
            if (!method.getName().equals(name) || method.getParameterCount() != parameterCount) {
                continue;
            }
            if (best == null || isMoreSpecific(method, best)) {
                best = method;
            }
        }
        if (best != null) {
            METHODS.put(key, best);
        }
        return best;
    }

    private static boolean isMoreSpecific(Method candidate, Method current) {
        Class<?>[] a = candidate.getParameterTypes();
        Class<?>[] b = current.getParameterTypes();
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return b[i].isAssignableFrom(a[i]);
            }
        }
        return false;
    }

    private static List<Method> allMethods(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.isBridge() && !method.isSynthetic()) {
                    methods.add(method);
                }
            }
            current = current.getSuperclass();
        }
        return methods;
    }

    // ---------------------------------------------------------------- construction / classes

    public static Object newInstance(String className, Class<?>[] signature, Object... args) {
        Class<?> type = findClass(className);
        if (type == null) {
            throw new IllegalStateException("Class not found: " + className);
        }
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(signature);
            constructor.setAccessible(true);
            return constructor.newInstance(convert(signature, args));
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not instantiate " + className + ": " + error, error);
        }
    }

    public static Object newInstanceIfPossible(String className, Object... args) {
        Class<?> type = findClass(className);
        if (type == null) {
            return null;
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.getParameterCount() != (args == null ? 0 : args.length)) {
                continue;
            }
            try {
                constructor.setAccessible(true);
                return constructor.newInstance(convert(constructor.getParameterTypes(), args));
            } catch (Throwable ignored) {
                // try the next constructor
            }
        }
        return null;
    }

    public static Class<?> findClass(String className) {
        try {
            return Class.forName(className);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------------- text

    /** Turns a Minecraft {@code Component} (or a plain string) into display text. */
    public static String text(Object component) {
        if (component == null) {
            return "";
        }
        if (component instanceof CharSequence sequence) {
            return sequence.toString();
        }
        Object value = callOpt(component, "getString");
        return value == null ? "" : String.valueOf(value);
    }

    /** Extracts the translation key of a translatable component, or an empty string. */
    public static String translationKey(Object component) {
        if (component == null) {
            return "";
        }
        Object contents = callOpt(component, "getContents");
        Object holder = contents == null ? component : contents;
        Object key = callOpt(holder, "getKey");
        return key == null ? "" : String.valueOf(key);
    }

    // ---------------------------------------------------------------- conversion

    static Object[] convert(Class<?>[] types, Object[] args) {
        if (args == null) {
            return new Object[0];
        }
        Object[] converted = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            converted[i] = convertOne(i < types.length ? types[i] : Object.class, args[i]);
        }
        return converted;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object convertOne(Class<?> type, Object value) {
        if (value == null || type.isInstance(value)) {
            return value;
        }
        if (value instanceof Number number) {
            if (type == int.class || type == Integer.class) {
                return number.intValue();
            }
            if (type == long.class || type == Long.class) {
                return number.longValue();
            }
            if (type == float.class || type == Float.class) {
                return number.floatValue();
            }
            if (type == double.class || type == Double.class) {
                return number.doubleValue();
            }
            if (type == short.class || type == Short.class) {
                return number.shortValue();
            }
            if (type == byte.class || type == Byte.class) {
                return number.byteValue();
            }
        }
        if (type == boolean.class || type == Boolean.class) {
            return value instanceof Boolean ? value : Boolean.parseBoolean(String.valueOf(value));
        }
        if (type == char.class || type == Character.class) {
            if (value instanceof Character character) {
                return character;
            }
            String string = String.valueOf(value);
            return string.isEmpty() ? ' ' : string.charAt(0);
        }
        if (type == String.class) {
            return String.valueOf(value);
        }
        if (type.isEnum() && value instanceof String name) {
            return Enum.valueOf((Class) type, name);
        }
        return value;
    }
}
