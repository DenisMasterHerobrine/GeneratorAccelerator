package dev.denismasterherobrine.generatoraccelerator2.features.fast;

/** Added to {@code Xoroshiro128PlusPlus} ({@code Xoroshiro128ReseedMixin}): the state its constructor would start from, set in place. */
public interface Reseed {
    void ga2$reseed(long seedLo, long seedHi);
}
