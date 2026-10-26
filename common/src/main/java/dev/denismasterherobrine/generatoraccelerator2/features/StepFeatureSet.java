package dev.denismasterherobrine.generatoraccelerator2.features;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSpliterator;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/**
 * The set {@code applyBiomeDecoration} collects a step's feature indexes into, for every biome around the chunk: vanilla's
 * {@code IntArraySet} looks each index up in a linear scan, so a step costs the features of all those biomes times the distinct
 * ones. With the hundreds of features per biome of large packs that is most of the step's setup. This set holds them in a hash
 * set. Vanilla only adds, asks the size and takes the array, which it sorts, so the features run in the same order. Every other
 * method answers from the hash set too.
 */
public final class StepFeatureSet extends IntArraySet {
    private final IntOpenHashSet set = new IntOpenHashSet();

    @Override
    public boolean add(int k) {
        return set.add(k);
    }

    @Override
    public boolean contains(int k) {
        return set.contains(k);
    }

    @Override
    public boolean remove(int k) {
        return set.remove(k);
    }

    @Override
    public int size() {
        return set.size();
    }

    @Override
    public boolean isEmpty() {
        return set.isEmpty();
    }

    @Override
    public void clear() {
        set.clear();
    }

    @Override
    public IntIterator iterator() {
        return set.iterator();
    }

    @Override
    public IntSpliterator spliterator() {
        return set.spliterator();
    }

    @Override
    public void forEach(IntConsumer action) {
        set.forEach(action);
    }

    @Override
    public boolean removeIf(IntPredicate filter) {
        return set.removeIf(filter);
    }

    @Override
    public int[] toIntArray() {
        return set.toIntArray();
    }

    @Override
    public int[] toArray(int[] a) {
        return set.toArray(a);
    }

    @Override
    public IntArraySet clone() {
        IntArraySet copy = new IntArraySet(set.size());
        copy.addAll(set);
        return copy;
    }
}
