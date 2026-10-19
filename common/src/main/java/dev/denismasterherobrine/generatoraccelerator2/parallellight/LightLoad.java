package dev.denismasterherobrine.generatoraccelerator2.parallellight;

/**
 * How busy a dimension's light thread is: the share of wall time it spent running light updates over the last window
 * (half a second or more). Implemented on {@code ThreadedLevelLightEngine} by {@code mixin.ThreadedLevelLightEngineMixin}.
 */
public interface LightLoad {
    /** Busy share of the last completed window, 0 to 1. */
    double ga2pl$busy();
}
