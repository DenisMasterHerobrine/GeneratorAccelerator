package dev.worldgen.lithostitched.api.worldgen.densityfunction;

import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Stand-in for Lithostitched's position context (same class name and factories), for GA2's tests without the mod. */
public class SimpleContext implements DensityFunction.FunctionContext {
    private final int x;
    private final int y;
    private final int z;

    private SimpleContext(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static SimpleContext of(int x, int y, int z) {
        return new SimpleContext(x, y, z);
    }

    public static SimpleContext of(double x, double y, double z) {
        return new SimpleContext((int) x, (int) y, (int) z);
    }

    public static SimpleContext of(Vec3i pos) {
        return new SimpleContext(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public int blockX() {
        return x;
    }

    @Override
    public int blockY() {
        return y;
    }

    @Override
    public int blockZ() {
        return z;
    }
}
