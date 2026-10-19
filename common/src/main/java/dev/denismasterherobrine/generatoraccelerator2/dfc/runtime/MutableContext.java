package dev.denismasterherobrine.generatoraccelerator2.dfc.runtime;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * A position that is moved instead of made anew. {@code SinglePointContext} is a record, so vanilla allocates one object
 * per sample. Used for compiled programs with no extern function. Only GA2's code reads it, and only during the call, so
 * one instance per binding (and thread) serves every sample. Programs that call other functions keep getting a fresh
 * {@code SinglePointContext}, as vanilla gives them.
 */
public final class MutableContext implements DensityFunction.FunctionContext {
    private int x;
    private int y;
    private int z;

    /** This context, moved to {@code x, y, z}. */
    public MutableContext at(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    @Override
    public int blockX() {
        return this.x;
    }

    @Override
    public int blockY() {
        return this.y;
    }

    @Override
    public int blockZ() {
        return this.z;
    }
}
