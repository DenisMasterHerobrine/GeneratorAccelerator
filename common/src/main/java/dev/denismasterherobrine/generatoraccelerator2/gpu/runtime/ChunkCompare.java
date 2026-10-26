package dev.denismasterherobrine.generatoraccelerator2.gpu.runtime;

import java.util.Arrays;
import java.util.function.IntPredicate;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;

/** Two chunks after BIOMES and NOISE compared the way the GPU path must match the CPU path: sections as stored, heightmaps, marks, biomes. */
public final class ChunkCompare {
    private ChunkCompare() {}

    /** The first difference in blocks, counters, heightmaps and post-processing marks, or null. */
    public static String blocks(ChunkAccess a, ChunkAccess b) {
        LevelChunkSection[] sa = a.getSections();
        LevelChunkSection[] sb = b.getSections();
        if (sa.length != sb.length) {
            return "section count " + sa.length + " vs " + sb.length;
        }
        for (int s = 0; s < sa.length; s++) {
            if (sa[s].nonEmptyBlockCount != sb[s].nonEmptyBlockCount || sa[s].tickingBlockCount != sb[s].tickingBlockCount
                || sa[s].tickingFluidCount != sb[s].tickingFluidCount) {
                String why = "counters of section " + s + " (blocks " + sa[s].nonEmptyBlockCount + " vs " + sb[s].nonEmptyBlockCount + ", ticking " + sa[s].tickingBlockCount
                    + " vs " + sb[s].tickingBlockCount + ", fluids " + sa[s].tickingFluidCount + " vs " + sb[s].tickingFluidCount + ")";
                for (int i = 0; i < 4096; i++) {
                    var x = sa[s].getBlockState(i & 15, i >> 8, (i >> 4) & 15);
                    var y = sb[s].getBlockState(i & 15, i >> 8, (i >> 4) & 15);
                    if (x != y) {
                        return why + ", first at block " + (i & 15) + "," + (a.getMinBuildHeight() + s * 16 + (i >> 8)) + "," + ((i >> 4) & 15) + ": " + x + " vs " + y;
                    }
                }
                return why + ", the same blocks";
            }
            var da = sa[s].getStates().data;
            var db = sb[s].getStates().data;
            if (da.palette().getClass() != db.palette().getClass() || da.palette().getSize() != db.palette().getSize()) {
                return "palette of section " + s + ": " + da.palette().getSize() + " vs " + db.palette().getSize() + " entries";
            }
            for (int p = 0; p < da.palette().getSize(); p++) {
                if (da.palette().valueFor(p) != db.palette().valueFor(p)) {
                    return "palette entry " + p + " of section " + s + ": " + da.palette().valueFor(p) + " vs " + db.palette().valueFor(p);
                }
            }
            if (!Arrays.equals(da.storage().getRaw(), db.storage().getRaw())) {
                for (int i = 0; i < 4096; i++) {
                    var x = sa[s].getBlockState(i & 15, i >> 8, (i >> 4) & 15);
                    var y = sb[s].getBlockState(i & 15, i >> 8, (i >> 4) & 15);
                    if (x != y) {
                        return "block " + (i & 15) + "," + (a.getMinBuildHeight() + s * 16 + (i >> 8)) + "," + ((i >> 4) & 15) + ": " + x + " vs " + y;
                    }
                }
                return "storage of section " + s;
            }
        }
        for (Heightmap.Types type : new Heightmap.Types[] { Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG }) {
            if (a.hasPrimedHeightmap(type) != b.hasPrimedHeightmap(type)) {
                return "heightmap presence " + type;
            }
            if (a.hasPrimedHeightmap(type)
                && !Arrays.equals(a.getOrCreateHeightmapUnprimed(type).getRawData(), b.getOrCreateHeightmapUnprimed(type).getRawData())) {
                return "heightmap " + type;
            }
        }
        if (!Arrays.deepToString(a.getPostProcessing()).equals(Arrays.deepToString(b.getPostProcessing()))) {
            return "post-processing marks";
        }
        return null;
    }

    /** The first quart whose biome differs, or null. Ties are skipped ({@code tie} takes quart indices in y, x, z order). */
    public static String biomes(ChunkAccess a, ChunkAccess b, IntPredicate tie) {
        int qx0 = a.getPos().x * 4, qz0 = a.getPos().z * 4, qy0 = a.getMinBuildHeight() >> 2;
        int quartsY = a.getHeight() / 4;
        boolean anyTie = false;
        for (int i = 0; i < quartsY * 16 && !anyTie; i++) {
            anyTie = tie.test(i);
        }
        for (int qy = 0; qy < quartsY; qy++) {
            for (int qx = 0; qx < 4; qx++) {
                for (int qz = 0; qz < 4; qz++) {
                    if (tie.test(qy * 16 + qx * 4 + qz)) {
                        continue;
                    }
                    // Read the sections' own containers. The chunk's lookup refuses chunks before their BIOMES status is set.
                    Holder<Biome> x = a.getSections()[qy >> 2].getNoiseBiome(qx, qy & 3, qz);
                    Holder<Biome> y = b.getSections()[qy >> 2].getNoiseBiome(qx, qy & 3, qz);
                    if (x != y) {
                        return "biome at quart " + (qx0 + qx) + "," + (qy0 + qy) + "," + (qz0 + qz) + ": " + key(x) + " vs " + key(y);
                    }
                }
            }
        }
        if (!anyTie) {
            LevelChunkSection[] sa = a.getSections();
            LevelChunkSection[] sb = b.getSections();
            for (int s = 0; s < sa.length; s++) {
                @SuppressWarnings("unchecked")
                var ba = ((PalettedContainer<Holder<Biome>>) sa[s].getBiomes()).data;
                @SuppressWarnings("unchecked")
                var bb = ((PalettedContainer<Holder<Biome>>) sb[s].getBiomes()).data;
                if (ba.palette().getSize() != bb.palette().getSize() || !Arrays.equals(ba.storage().getRaw(), bb.storage().getRaw())) {
                    return "biome palette or storage of section " + s;
                }
                for (int p = 0; p < ba.palette().getSize(); p++) {
                    if (ba.palette().valueFor(p) != bb.palette().valueFor(p)) {
                        return "biome palette entry " + p + " of section " + s;
                    }
                }
            }
        }
        return null;
    }

    private static String key(Holder<Biome> h) {
        return h.unwrapKey().map(k -> k.location().toString()).orElse("?");
    }
}
