package dev.denismasterherobrine.generatoraccelerator2.carver;

import dev.denismasterherobrine.generatoraccelerator2.mixin.aquifer.FluidStatusAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.AquiferStatusInvoker;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * {@code -Dgeneratoraccelerator2.verify.carvers.diag=true}. For a chunk whose carving differs from vanilla's, reports what
 * differs between two NoiseChunks: the one the fill left, and one made fresh. GA2's carvers use the aquifer of the fill's
 * NoiseChunk. Compared: aquifer cell statuses, the barrier noise, and the preliminary surface levels. A debugging aid.
 */
public final class AquiferDiag {
    private AquiferDiag() {}

    public static final boolean ENABLED = Boolean.getBoolean("generatoraccelerator2.verify.carvers.diag");

    public static String compare(NoiseChunk fill, NoiseChunk fresh, RandomState randomState, ChunkPos pos, int minY, int height) {
        StringBuilder out = new StringBuilder();
        for (int qx = 0; qx < 5; qx++) {
            for (int qz = 0; qz < 5; qz++) {
                int x = (pos.x * 4 + qx) << 2, z = (pos.z * 4 + qz) << 2;
                int a = fill.preliminarySurfaceLevel(x, z), b = fresh.preliminarySurfaceLevel(x, z);
                if (a != b) {
                    out.append(" surface level at ").append(x).append(",").append(z).append(": fill ").append(a).append(" fresh ").append(b).append(';');
                }
            }
        }
        Aquifer fa = fill.aquifer(), fr = fresh.aquifer();
        if (fa instanceof AquiferStatusInvoker si && fr instanceof AquiferStatusInvoker ri) {
            int minGridY = Math.floorDiv(minY, 12) - 1;
            int cellsY = Math.floorDiv(minY + height, 12) + 1 - minGridY + 1;
            int shown = 0;
            for (int jj = 0; jj < cellsY; jj++) {
                for (int kk = 0; kk < 3; kk++) {
                    for (int ii = 0; ii < 3; ii++) {
                        int gx = pos.x - 1 + ii, gy = minGridY + jj, gz = pos.z - 1 + kk;
                        RandomSource r = randomState.aquiferRandom().at(gx, gy, gz);
                        int x = gx * 16 + r.nextInt(10), y = gy * 12 + r.nextInt(9), z = gz * 16 + r.nextInt(10);
                        long loc = BlockPos.asLong(x, y, z);
                        var a = (FluidStatusAccessor) (Object) si.ga2$getAquiferStatus(loc);
                        var b = (FluidStatusAccessor) (Object) ri.ga2$getAquiferStatus(loc);
                        if (a.ga2$fluidLevel() != b.ga2$fluidLevel() || a.ga2$fluidType() != b.ga2$fluidType()) {
                            if (shown++ < 4) {
                                out.append(" cell ").append(gx).append(",").append(gy).append(",").append(gz).append(": fill ").append(a.ga2$fluidLevel()).append(' ')
                                    .append(a.ga2$fluidType().getBlock()).append(" fresh ").append(b.ga2$fluidLevel()).append(' ').append(b.ga2$fluidType().getBlock()).append(';');
                            }
                        }
                    }
                }
            }
            if (shown > 0) {
                out.append(" (").append(shown).append(" cells differ)");
            }
            DensityFunction ba = si.ga2$barrierNoise(), bb = ri.ga2$barrierNoise();
            int bad = 0;
            for (int y = minY; y < minY + height && bad < 3; y += 3) {
                for (int lx = 0; lx < 16 && bad < 3; lx += 3) {
                    for (int lz = 0; lz < 16 && bad < 3; lz += 3) {
                        DensityFunction.SinglePointContext c = new DensityFunction.SinglePointContext(pos.getMinBlockX() + lx, y, pos.getMinBlockZ() + lz);
                        double a = ba.compute(c), b = bb.compute(c);
                        if (Double.doubleToLongBits(a) != Double.doubleToLongBits(b)) {
                            bad++;
                            out.append(" barrier at ").append(c.blockX()).append(",").append(y).append(",").append(c.blockZ()).append(": fill ").append(a).append(" fresh ").append(b)
                                .append(';');
                        }
                    }
                }
            }
            // what computeSubstance answers at every block, fill's aquifer against the fresh one
            int diff = 0;
            for (int y = minY + 1; y < minY + height && diff < 3; y++) {
                for (int lx = 0; lx < 16 && diff < 3; lx++) {
                    for (int lz = 0; lz < 16 && diff < 3; lz++) {
                        DensityFunction.SinglePointContext c = new DensityFunction.SinglePointContext(pos.getMinBlockX() + lx, y, pos.getMinBlockZ() + lz);
                        var a = fa.computeSubstance(c, 0.0);
                        boolean af = fa.shouldScheduleFluidUpdate();
                        var b = fr.computeSubstance(c, 0.0);
                        boolean bf = fr.shouldScheduleFluidUpdate();
                        if (a != b || af != bf) {
                            diff++;
                            out.append(" substance at ").append(c.blockX()).append(",").append(y).append(",").append(c.blockZ()).append(": fill ").append(a).append('/').append(af)
                                .append(" fresh ").append(b).append('/').append(bf).append(';');
                        }
                    }
                }
            }
        }
        return out.length() == 0 ? "no difference in surface levels, statuses, barrier or substances" : out.toString();
    }
}
