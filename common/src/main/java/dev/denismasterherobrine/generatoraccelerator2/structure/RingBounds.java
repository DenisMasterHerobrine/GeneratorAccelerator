package dev.denismasterherobrine.generatoraccelerator2.structure;

import com.google.common.collect.MapMaker;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.mixin.scheduling.ChunkGeneratorStructureStateAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.structure.StructurePlacementAccessor;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.Holder;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

/**
 * The chunks a ring placement (strongholds) can never place in, so their structure starts do not wait for the ring
 * positions.
 *
 * <p>The level computes the positions on the worker pool from its construction on. For vanilla's strongholds that is 128
 * biome searches of 3249 samples each. Every chunk's structure start asks for the positions. A new world's first chunks,
 * including the spawn search's chunks, therefore wait for all of them.
 * {@code ChunkGeneratorStructureState.generateRingPositions} puts the positions of ring i at
 * {@code 4d + 6di + (u - 0.5) 2.5d} chunks from the origin, where d is the placement's distance and u is in [0, 1). A
 * position is never closer than 2.75d. Rounding to a chunk moves a position by at most 0.71 chunks. The biome search
 * around the position ({@code findBiomeHorizontal}, 112 blocks around the chunk's middle) moves it by at most 7 chunks
 * along each axis (9.9 chunks). A chunk closer to the origin than {@code 2.75d - 11} chunks is therefore never a ring
 * position. {@code isPlacementChunk} answers false there without waiting, which is vanilla's answer once the positions
 * are known. For vanilla's strongholds (d = 32), this covers every chunk of a spawn area within about 60 chunks of the
 * origin. A failed ring computation still fails the structure starts that need its positions.
 *
 * <p>Stands down when another mod changes the ring computation or the placement check ({@code HookTargets} key
 * {@code structures.ringBound}). {@code -Dgeneratoraccelerator2.ringBound=false} turns it off.
 */
public final class RingBounds {
    private RingBounds() {}

    /** Not final so a test can compare both ways. */
    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.ringBound"));
    /** Placement checks answered from the bound while the positions were still being computed. */
    public static final LongAdder ANSWERED_EARLY = new LongAdder();

    /** Per structure state: how far from a chunk its structure start can ask a ring placement (exclusion zones). See {@link #reach}. */
    private static final ConcurrentMap<ChunkGeneratorStructureState, Map<ConcentricRingsStructurePlacement, Integer>> REACH =
        new MapMaker().weakKeys().concurrencyLevel(4).makeMap();

    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.STRUCTURE_RING_BOUND);

    public static boolean enabled() {
        return ENABLED && HOOK.allowed();
    }

    /** The radius in chunks inside which {@code placement} never has a ring position. A non-positive value means no such radius. */
    public static double innerRadius(ConcentricRingsStructurePlacement placement) {
        return 2.75 * placement.distance() - 11.0;
    }

    /** True when every chunk within {@code reach} chunks of x, z along each axis lies inside {@code radius} of the origin. */
    public static boolean inside(double radius, int x, int z, int reach) {
        if (radius <= 0.0 || reach == Integer.MAX_VALUE) {
            return false;
        }
        double fx = Math.abs((double) x) + reach;
        double fz = Math.abs((double) z) + reach;
        return fx * fx + fz * fz < radius * radius;
    }

    /**
     * {@code ConcentricRingsStructurePlacement.isPlacementChunk}: true when the answer is false without the positions. Applies
     * only to a placement whose positions the state has started computing, because vanilla's call would otherwise start them.
     * Does not apply once that computation has failed, because vanilla's call rethrows the failure.
     */
    public static boolean never(ChunkGeneratorStructureState state, ConcentricRingsStructurePlacement placement, int x, int z) {
        if (!enabled()) {
            return false;
        }
        CompletableFuture<?> positions = ((ChunkGeneratorStructureStateAccessor) state).ga2$ringPositions().get(placement);
        if (positions == null || positions.isCompletedExceptionally() || !inside(innerRadius(placement), x, z, 0)) {
            return false;
        }
        if (!positions.isDone()) {
            ANSWERED_EARLY.increment();
        }
        return true;
    }

    /**
     * True when the structure start of the chunk at x, z never needs the positions of {@code placement}. That holds when
     * every chunk the start can ask about lies inside the inner radius. Those are the chunk itself and the chunks checked by
     * the exclusion zones of its placements.
     */
    public static boolean startNeverWaits(ChunkGeneratorStructureState state, ConcentricRingsStructurePlacement placement, int x, int z) {
        return enabled() && inside(innerRadius(placement), x, z, reach(state, placement));
    }

    /**
     * How far from a chunk, in chunks along each axis, the chunk's structure start can ask {@code placement} about a chunk.
     * The base is 0, for the chunk itself. A placement's exclusion zone may name a set whose placement is this one, directly
     * or through further exclusion zones. In that case the chunk count of every exclusion zone on the way is added.
     * {@link Integer#MAX_VALUE} for a cycle.
     */
    static int reach(ChunkGeneratorStructureState state, ConcentricRingsStructurePlacement placement) {
        return REACH.computeIfAbsent(state, s -> new ConcurrentHashMap<>()).computeIfAbsent(placement, p -> {
            int best = -1;
            for (Holder<StructureSet> set : state.possibleStructureSets()) {
                int r = reach(p, set.value().placement(), new HashSet<>());
                if (r == Integer.MAX_VALUE) {
                    return Integer.MAX_VALUE;
                }
                best = Math.max(best, r);
            }
            return Math.max(best, 0);
        });
    }

    /** Farthest offset at which checking {@code from} asks {@code target}. -1 when it never does, MAX_VALUE for a cycle. */
    private static int reach(StructurePlacement target, StructurePlacement from, Set<StructurePlacement> visiting) {
        int best = from == target ? 0 : -1;
        StructurePlacement.ExclusionZone zone = ((StructurePlacementAccessor) from).ga2$exclusionZone().orElse(null);
        if (zone == null) {
            return best;
        }
        StructurePlacement other = zone.otherSet().value().placement();
        if (!visiting.add(other)) {
            return Integer.MAX_VALUE;
        }
        int r = reach(target, other, visiting);
        visiting.remove(other);
        if (r == Integer.MAX_VALUE) {
            return r;
        }
        return r < 0 ? best : Math.max(best, zone.chunkCount() + r);
    }
}
