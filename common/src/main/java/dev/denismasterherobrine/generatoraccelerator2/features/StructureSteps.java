package dev.denismasterherobrine.generatoraccelerator2.features;

/**
 * The structure registry and its decoration step map last built ({@code mixin.features.StructureStepMapMixin}). The map's
 * structures lead back to their registry. So the pair is dropped when the server stops, instead of kept until another world
 * decorates.
 */
public final class StructureSteps {
    private StructureSteps() {}

    /** The registry and its map, published together so a reader never pairs one registry with another's map. */
    public static volatile Object[] cached;

    public static void clear() {
        cached = null;
    }
}
