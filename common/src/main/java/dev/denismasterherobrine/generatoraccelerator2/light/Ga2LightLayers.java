package dev.denismasterherobrine.generatoraccelerator2.light;

/** A light engine whose two layers can update at once ({@link LightLayers}). Implemented by {@code LevelLightEngineMixin}. */
public interface Ga2LightLayers {
    /**
     * Starts recording the block light engine's part of every call this thread makes, until the update's propagation. Called by
     * the light thread at the start of an update.
     */
    void ga2$beginLayers();
}
