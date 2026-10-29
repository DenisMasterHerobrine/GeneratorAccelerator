package dev.denismasterherobrine.generatoraccelerator2.features.verify;

import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.ProtoChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import java.lang.reflect.Field;

/**
 * Compares two proto chunks that should be identical after the same decoration: every block state,
 * section counters, heightmap presence and data, post-processing lists (order and duplicates),
 * block entity NBT and scheduled ticks in order. Returns a human-readable list of differences,
 * stopping after a handful per chunk.
 */
public final class FeatureCompare {
    private FeatureCompare() {}

    public static List<String> compare(ProtoChunk expected, ProtoChunk actual, int limit) {
        List<String> out = new ArrayList<>();
        String where = "chunk " + expected.getPos();
        int minY = expected.getMinY();
        for (int s = 0; s < expected.getSections().length && out.size() < limit; s++) {
            LevelChunkSection a = expected.getSection(s);
            LevelChunkSection b = actual.getSection(s);
            if (a.hasOnlyAir() && b.hasOnlyAir()) {
                continue;
            }
            PalettedContainer<BlockState> sa = a.getStates();
            PalettedContainer<BlockState> sb = b.getStates();
            for (int y = 0; y < 16 && out.size() < limit; y++) {
                for (int z = 0; z < 16 && out.size() < limit; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState ea = sa.get(x, y, z);
                        BlockState eb = sb.get(x, y, z);
                        if (ea != eb) {
                            out.add(where + " block " + (expected.getPos().getMinBlockX() + x) + "," + (minY + s * 16 + y) + ","
                                + (expected.getPos().getMinBlockZ() + z) + ": expected " + ea + " got " + eb);
                            if (out.size() >= limit) {
                                break;
                            }
                        }
                    }
                }
            }
            if (a.hasOnlyAir() != b.hasOnlyAir() || a.isRandomlyTickingBlocks() != b.isRandomlyTickingBlocks()
                || a.isRandomlyTickingFluids() != b.isRandomlyTickingFluids()) {
                out.add(where + " section " + s + " counters differ");
            } else {
                String ca = counters(a);
                String cb = counters(b);
                if (!ca.equals(cb)) {
                    out.add(where + " section " + s + " counters differ: expected " + ca + " got " + cb);
                }
            }
        }
        CarvingMask ma = expected.getCarvingMask();
        CarvingMask mb = actual.getCarvingMask();
        if ((ma == null) != (mb == null)) {
            out.add(where + " carving mask present: expected " + (ma != null) + " got " + (mb != null));
        } else if (ma != null && !Arrays.equals(ma.toArray(), mb.toArray())) {
            out.add(where + " carving mask differs");
        }
        for (Heightmap.Types type : Heightmap.Types.values()) {
            boolean ha = expected.hasPrimedHeightmap(type);
            boolean hb = actual.hasPrimedHeightmap(type);
            if (ha != hb) {
                out.add(where + " heightmap " + type + " present: expected " + ha + " got " + hb);
            } else if (ha) {
                long[] da = expected.getOrCreateHeightmapUnprimed(type).getRawData();
                long[] db = actual.getOrCreateHeightmapUnprimed(type).getRawData();
                if (!Arrays.equals(da, db)) {
                    out.add(where + " heightmap " + type + " data differs");
                }
            }
        }
        ShortList[] pa = expected.getPostProcessing();
        ShortList[] pb = actual.getPostProcessing();
        for (int i = 0; i < pa.length; i++) {
            ShortList la = pa[i];
            ShortList lb = pb[i];
            boolean ea = la == null || la.isEmpty();
            boolean eb = lb == null || lb.isEmpty();
            if (ea != eb || (!ea && !la.equals(lb))) {
                out.add(where + " post-processing list " + i + " differs: expected " + la + " got " + lb);
            }
        }
        Map<BlockPos, CompoundTag> na = expected.getBlockEntityNbts();
        Map<BlockPos, CompoundTag> nb = actual.getBlockEntityNbts();
        if (!na.equals(nb)) {
            out.add(where + " block entity NBT differs: expected " + na + " got " + nb);
        }
        if (!expected.getBlockEntities().keySet().equals(actual.getBlockEntities().keySet())) {
            out.add(where + " block entities differ: expected " + expected.getBlockEntities().keySet() + " got " + actual.getBlockEntities().keySet());
        }
        compareTicks(where + " block ticks", (ProtoChunkTicks<?>) expected.getBlockTicks(), (ProtoChunkTicks<?>) actual.getBlockTicks(), out);
        compareTicks(where + " fluid ticks", (ProtoChunkTicks<?>) expected.getFluidTicks(), (ProtoChunkTicks<?>) actual.getFluidTicks(), out);
        return out;
    }

    private static final String[] COUNTER_FIELDS = { "nonEmptyBlockCount", "tickingBlockCount", "fluidCount", "tickingFluidCount" };

    /** The section's exact block counters (private fields, read reflectively). Empty when unavailable. */
    private static String counters(LevelChunkSection section) {
        StringBuilder sb = new StringBuilder();
        for (String name : COUNTER_FIELDS) {
            try {
                Field f = LevelChunkSection.class.getDeclaredField(Names.runtimeField("net/minecraft/world/level/chunk/LevelChunkSection", name));
                f.setAccessible(true);
                sb.append(name).append('=').append(f.getShort(section)).append(' ');
            } catch (ReflectiveOperationException | RuntimeException e) {
                return "";
            }
        }
        return sb.toString().trim();
    }

    private static void compareTicks(String where, ProtoChunkTicks<?> a, ProtoChunkTicks<?> b, List<String> out) {
        List<? extends SavedTick<?>> la = a.scheduledTicks();
        List<? extends SavedTick<?>> lb = b.scheduledTicks();
        if (!la.equals(lb)) {
            out.add(where + " differ: expected " + describe(la) + " got " + describe(lb));
        }
    }

    private static String describe(List<? extends SavedTick<?>> ticks) {
        StringBuilder sb = new StringBuilder("[");
        int n = 0;
        for (SavedTick<?> t : ticks) {
            if (n++ > 0) {
                sb.append(", ");
            }
            if (n > 12) {
                sb.append("... ").append(ticks.size()).append(" total");
                break;
            }
            Object type = t.type();
            String name = type instanceof Block block ? BuiltInRegistries.BLOCK.getKey(block).toString()
                : type instanceof Fluid fluid ? BuiltInRegistries.FLUID.getKey(fluid).toString() : String.valueOf(type);
            sb.append(name).append('@').append(t.pos().toShortString()).append('/').append(t.delay()).append('/').append(t.priority());
        }
        return sb.append(']').toString();
    }
}
