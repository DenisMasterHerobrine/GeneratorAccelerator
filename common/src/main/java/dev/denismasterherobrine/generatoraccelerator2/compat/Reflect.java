package dev.denismasterherobrine.generatoraccelerator2.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import javax.annotation.Nullable;

/**
 * Reads other mods' objects by class name and shape, so compat code needs no compile-time dependency on those mods. A mod
 * version with a different shape is simply not recognised, and that version's objects take GA2's generic path.
 */
public final class Reflect {
    private Reflect() {}

    /** The record components of {@code value} when it is the record {@code className} with exactly these components, in order. */
    @Nullable
    public static Object[] components(Object value, String className, String... names) {
        Class<?> c = value.getClass();
        if (!c.getName().equals(className) || !c.isRecord()) {
            return null;
        }
        RecordComponent[] rc = c.getRecordComponents();
        if (rc.length != names.length) {
            return null;
        }
        Object[] values = new Object[names.length];
        try {
            for (int i = 0; i < names.length; i++) {
                if (!rc[i].getName().equals(names[i])) {
                    return null;
                }
                Method accessor = rc[i].getAccessor();
                accessor.trySetAccessible();
                values[i] = accessor.invoke(value);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
        return values;
    }

    /**
     * {@code type}'s declared methods, or none when one of their signatures names a class this side does not have. An example
     * is another mod's method with a client class, on a dedicated server. Looking methods up resolves every signature of the class.
     */
    public static java.lang.reflect.Method[] declaredMethods(Class<?> type) {
        try {
            return type.getDeclaredMethods();
        } catch (LinkageError e) {
            return new java.lang.reflect.Method[0];
        }
    }

    /** The value of a field declared by {@code value}'s class when that class is {@code className}; null when absent. */
    @Nullable
    public static Object field(Object value, String className, String name) {
        Class<?> c = value.getClass();
        if (!c.getName().equals(className)) {
            return null;
        }
        try {
            Field f = c.getDeclaredField(name);
            f.trySetAccessible();
            return f.get(value);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null; // LinkageError: a field's type is a class this side does not have (a client class on a server)
        }
    }

    /** The value of the one field of {@code value}'s class assignable to {@code type}, when that class is {@code className}; null when absent or ambiguous. */
    @Nullable
    public static Object fieldOfType(Object value, String className, Class<?> type) {
        Class<?> c = value.getClass();
        if (!c.getName().equals(className)) {
            return null;
        }
        Field found = null;
        Field[] fields;
        try {
            fields = c.getDeclaredFields();
        } catch (LinkageError e) {
            return null; // a field's type is a class this side does not have
        }
        for (Field f : fields) {
            if (type.isAssignableFrom(f.getType())) {
                if (found != null) {
                    return null;
                }
                found = f;
            }
        }
        if (found == null) {
            return null;
        }
        try {
            found.trySetAccessible();
            return found.get(value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** The class {@code name} if it is loaded (or loadable) by the game's class loader, else null. */
    @Nullable
    public static Class<?> type(String name) {
        try {
            return Class.forName(name, false, Reflect.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
