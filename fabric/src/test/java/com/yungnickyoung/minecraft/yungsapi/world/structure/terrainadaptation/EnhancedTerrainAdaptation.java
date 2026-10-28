package com.yungnickyoung.minecraft.yungsapi.world.structure.terrainadaptation;

import net.minecraft.util.Mth;

/**
 * Stand-in for YUNG's API 4.0 (1.20.1) {@code EnhancedTerrainAdaptation}, for {@code EnhancedBeardYung4Test}. The members GA2 reads
 * have the mod's names. {@link #computeDensityFactor} is transcribed from the mod's bytecode.
 */
public class EnhancedTerrainAdaptation {
    public static final EnhancedTerrainAdaptation NONE = new EnhancedTerrainAdaptation(0, 0, false, false);

    private final boolean doCarving;
    private final boolean doBearding;
    private final int kernelSize;
    private final int kernelDistance;
    private final float[] kernel;

    public EnhancedTerrainAdaptation(int kernelSize, int kernelDistance, boolean doCarving, boolean doBearding) {
        this.kernelSize = kernelSize;
        this.kernelDistance = kernelDistance;
        this.doCarving = doCarving;
        this.doBearding = doBearding;
        this.kernel = new float[kernelSize * kernelSize * kernelSize];
        int r = getKernelRadius();
        for (int i = 0; i < kernelSize; i++) {
            for (int j = 0; j < kernelSize; j++) {
                for (int k = 0; k < kernelSize; k++) {
                    kernel[index(i, j, k)] = computeKernelValue(i - r, (double) (j - r) + 0.5, k - r);
                }
            }
        }
    }

    private float computeKernelValue(double x, double y, double z) {
        double d = x * x + y * y + z * z;
        return (float) Math.pow(Math.E, -d / (double) kernelDistance);
    }

    public boolean carves() {
        return doCarving;
    }

    public boolean beards() {
        return doBearding;
    }

    public int getKernelSize() {
        return kernelSize;
    }

    public int getKernelRadius() {
        return kernelSize / 2;
    }

    public float[] getKernel() {
        return kernel;
    }

    public double computeDensityFactor(int dx, int dy, int dz, int yRel) {
        int r = getKernelRadius();
        int i = dx + r;
        int j = dy + r;
        int k = dz + r;
        if (isInKernelRange(i) && isInKernelRange(j) && isInKernelRange(k)) {
            float kv = getKernel()[index(i, j, k)];
            double d0 = (double) yRel + 0.5;
            double d1 = (double) dx * (double) dx + d0 * d0 + (double) dz * (double) dz;
            double d2 = -d0 * Mth.invSqrt(d1 / 2.0) / 2.0;
            if (d2 > 0.0 && !beards()) {
                return 0.0;
            }
            if (d2 < 0.0 && !carves()) {
                return 0.0;
            }
            return d2 * (double) kv;
        }
        return 0.0;
    }

    private boolean isInKernelRange(int i) {
        return i >= 0 && i < kernelSize;
    }

    private int index(int x, int y, int z) {
        return z * kernelSize * kernelSize + x * kernelSize + y;
    }
}
