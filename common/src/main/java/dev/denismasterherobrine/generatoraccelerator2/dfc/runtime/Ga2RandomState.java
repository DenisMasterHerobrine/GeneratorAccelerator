package dev.denismasterherobrine.generatoraccelerator2.dfc.runtime;

/** Duck interface added to {@code RandomState} by mixin. */
public interface Ga2RandomState {
    ProgramSet ga2$programs();

    /** {@code structure.HeightProbes}' table for this random state, or null. */
    Object ga2$heightProbes();

    void ga2$setHeightProbes(Object table);
}
