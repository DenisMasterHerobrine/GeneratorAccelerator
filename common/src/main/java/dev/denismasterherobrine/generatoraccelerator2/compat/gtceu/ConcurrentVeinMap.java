package dev.denismasterherobrine.generatoraccelerator2.compat.gtceu;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A {@code HashMap} whose contents live in a {@link ConcurrentHashMap}, for a field another mod declares as {@code HashMap} and
 * fills from several threads. GTCEu's bedrock fluid veins ({@code BedrockFluidVeinSavedData.veinFluids}) get an entry the first time
 * a chunk asks, and TerraFirmaGreg asks from its worldgen features on the worker threads. The server thread saves the map by
 * iterating it, which a plain {@code HashMap} answers with a {@code ConcurrentModificationException} that stops the server. Here the
 * iteration is weakly consistent and the writes are safe. Every method that reads or writes contents goes to the concurrent map.
 * The inherited table stays empty. Null keys and values are not supported, which the veins never use.
 */
public final class ConcurrentVeinMap<K, V> extends HashMap<K, V> {
    private final ConcurrentHashMap<K, V> map;

    public ConcurrentVeinMap(Map<? extends K, ? extends V> contents) {
        super(0);
        this.map = new ConcurrentHashMap<>(contents);
    }

    @Override
    public int size() {
        return this.map.size();
    }

    @Override
    public boolean isEmpty() {
        return this.map.isEmpty();
    }

    @Override
    public V get(Object key) {
        return key == null ? null : this.map.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return key != null && this.map.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return value != null && this.map.containsValue(value);
    }

    @Override
    public V put(K key, V value) {
        return this.map.put(key, value);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        this.map.putAll(m);
    }

    @Override
    public V remove(Object key) {
        return key == null ? null : this.map.remove(key);
    }

    @Override
    public void clear() {
        this.map.clear();
    }

    @Override
    public Set<K> keySet() {
        return this.map.keySet();
    }

    @Override
    public Collection<V> values() {
        return this.map.values();
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        return this.map.entrySet();
    }

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        return key == null ? defaultValue : this.map.getOrDefault(key, defaultValue);
    }

    @Override
    public V putIfAbsent(K key, V value) {
        return this.map.putIfAbsent(key, value);
    }

    @Override
    public boolean remove(Object key, Object value) {
        return key != null && value != null && this.map.remove(key, value);
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        return this.map.replace(key, oldValue, newValue);
    }

    @Override
    public V replace(K key, V value) {
        return this.map.replace(key, value);
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        return this.map.computeIfAbsent(key, mappingFunction);
    }

    @Override
    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        return this.map.computeIfPresent(key, remappingFunction);
    }

    @Override
    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        return this.map.compute(key, remappingFunction);
    }

    @Override
    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        return this.map.merge(key, value, remappingFunction);
    }

    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        this.map.forEach(action);
    }

    @Override
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        this.map.replaceAll(function);
    }

    @Override
    public Object clone() {
        return new ConcurrentVeinMap<>(this.map);
    }
}
