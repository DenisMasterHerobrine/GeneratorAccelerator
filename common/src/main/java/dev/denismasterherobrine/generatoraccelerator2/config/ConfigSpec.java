package dev.denismasterherobrine.generatoraccelerator2.config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A config's layout and live values: a tree of tables holding typed, commented entries with defaults. Built once with a
 * {@link Builder}, then bound to a file by {@link ConfigManager#register}. The builder has the same shape as Forge's
 * {@code ForgeConfigSpec}, so the config classes read the same on both loaders. Until the file is loaded, every value reads
 * as its default and {@link #isLoaded()} is false.
 */
public final class ConfigSpec {
    private final List<ConfigValue<?>> values;
    private final Map<List<String>, List<String>> tableComments;
    private volatile boolean loaded;

    private ConfigSpec(List<ConfigValue<?>> values, Map<List<String>, List<String>> tableComments) {
        this.values = List.copyOf(values);
        this.tableComments = tableComments;
    }

    public boolean isLoaded() {
        return loaded;
    }

    void setLoaded() {
        loaded = true;
    }

    List<ConfigValue<?>> values() {
        return values;
    }

    /** The comment written above a table's header, if any. */
    List<String> tableComment(List<String> path) {
        return tableComments.getOrDefault(path, List.of());
    }

    public static final class Builder {
        private final Deque<String> path = new ArrayDeque<>();
        private final List<ConfigValue<?>> values = new ArrayList<>();
        private final Map<List<String>, List<String>> tableComments = new HashMap<>();
        private List<String> comment = List.of();

        /** The comment of the next entry or table. Each string is one line. */
        public Builder comment(String... lines) {
            comment = List.of(lines);
            return this;
        }

        /** Opens a table. Entries defined until the matching {@link #pop} go into it. */
        public Builder push(String name) {
            path.addLast(name);
            if (!comment.isEmpty()) {
                tableComments.put(List.copyOf(path), comment);
                comment = List.of();
            }
            return this;
        }

        public Builder pop() {
            if (path.isEmpty()) {
                throw new IllegalStateException("pop() without push()");
            }
            path.removeLast();
            return this;
        }

        public BooleanValue define(String key, boolean defaultValue) {
            return add(new BooleanValue(entryPath(key), takeComment(), defaultValue));
        }

        /** A string, or another plain value parsed as the type of its default. */
        public <T> ConfigValue<T> define(String key, T defaultValue) {
            if (defaultValue instanceof Boolean) {
                throw new IllegalArgumentException("use define(String, boolean) for " + key);
            }
            return add(new ConfigValue<>(entryPath(key), takeComment(), defaultValue));
        }

        public IntValue defineInRange(String key, int defaultValue, int min, int max) {
            return add(new IntValue(entryPath(key), withRange(min, max), defaultValue, min, max));
        }

        public LongValue defineInRange(String key, long defaultValue, long min, long max) {
            return add(new LongValue(entryPath(key), withRange(min, max), defaultValue, min, max));
        }

        public DoubleValue defineInRange(String key, double defaultValue, double min, double max) {
            return add(new DoubleValue(entryPath(key), withRange(min, max), defaultValue, min, max));
        }

        public ConfigSpec build() {
            if (!path.isEmpty()) {
                throw new IllegalStateException("unclosed tables: " + path);
            }
            return new ConfigSpec(values, Collections.unmodifiableMap(tableComments));
        }

        private <V extends ConfigValue<?>> V add(V value) {
            values.add(value);
            return value;
        }

        private List<String> entryPath(String key) {
            List<String> p = new ArrayList<>(path);
            p.add(key);
            return List.copyOf(p);
        }

        private List<String> takeComment() {
            List<String> c = comment;
            comment = List.of();
            return c;
        }

        private List<String> withRange(Object min, Object max) {
            List<String> c = new ArrayList<>(takeComment());
            c.add("Range: " + min + " ~ " + max);
            return List.copyOf(c);
        }
    }

    /** One entry: its path from the root table, comment, default and current value. */
    public static class ConfigValue<T> implements Supplier<T> {
        private final List<String> path;
        private final List<String> comment;
        private final T defaultValue;
        private volatile T value;

        ConfigValue(List<String> path, List<String> comment, T defaultValue) {
            this.path = path;
            this.comment = comment;
            this.defaultValue = defaultValue;
            this.value = defaultValue;
        }

        @Override
        public T get() {
            return value;
        }

        public T getDefault() {
            return defaultValue;
        }

        public List<String> getPath() {
            return path;
        }

        List<String> comment() {
            return comment;
        }

        void set(T value) {
            this.value = value;
        }

        /** The value a parsed TOML value stands for, or null when it is not a valid value of this entry. */
        @SuppressWarnings("unchecked")
        T accept(Object parsed) {
            return defaultValue.getClass().isInstance(parsed) ? (T) parsed : null;
        }
    }

    public static final class BooleanValue extends ConfigValue<Boolean> {
        BooleanValue(List<String> path, List<String> comment, boolean defaultValue) {
            super(path, comment, defaultValue);
        }

        public boolean getAsBoolean() {
            return get();
        }
    }

    public static final class IntValue extends ConfigValue<Integer> {
        private final int min;
        private final int max;

        IntValue(List<String> path, List<String> comment, int defaultValue, int min, int max) {
            super(path, comment, defaultValue);
            this.min = min;
            this.max = max;
        }

        public int getAsInt() {
            return get();
        }

        @Override
        Integer accept(Object parsed) {
            if (parsed instanceof Long l && l >= min && l <= max) {
                return l.intValue();
            }
            return null;
        }
    }

    public static final class LongValue extends ConfigValue<Long> {
        private final long min;
        private final long max;

        LongValue(List<String> path, List<String> comment, long defaultValue, long min, long max) {
            super(path, comment, defaultValue);
            this.min = min;
            this.max = max;
        }

        @Override
        Long accept(Object parsed) {
            return parsed instanceof Long l && l >= min && l <= max ? l : null;
        }
    }

    public static final class DoubleValue extends ConfigValue<Double> {
        private final double min;
        private final double max;

        DoubleValue(List<String> path, List<String> comment, double defaultValue, double min, double max) {
            super(path, comment, defaultValue);
            this.min = min;
            this.max = max;
        }

        public double getAsDouble() {
            return get();
        }

        @Override
        Double accept(Object parsed) {
            double d;
            if (parsed instanceof Double v) {
                d = v;
            } else if (parsed instanceof Long l) {
                d = l;
            } else {
                return null;
            }
            return d >= min && d <= max ? d : null;
        }
    }
}
