package dev.denismasterherobrine.generatoraccelerator2.noise;

/** Duck interface added to {@code ImprovedNoise} by mixin. */
public interface Ga2ImprovedNoise {
    /** 512-entry expanded permutation table, built once in the constructor. */
    int[] ga2$perm();

    /** Vanilla's raw permutation bytes, exposed for the self-check reference. */
    byte[] ga2$p();
}
