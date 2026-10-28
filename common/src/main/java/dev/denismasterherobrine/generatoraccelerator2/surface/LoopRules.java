package dev.denismasterherobrine.generatoraccelerator2.surface;

import dev.denismasterherobrine.generatoraccelerator2.mixin.surface.SurfaceContextAccessor;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.Ir;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.SurfaceEval;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.SurfaceProgram;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;

/**
 * The compiled rules inside vanilla's {@code SurfaceSystem.buildSurface}, for the chunks GA2's builder leaves to vanilla: another
 * mod changes the build, or the chunk is one the builder does not take. Vanilla's loop runs as it is, with every other mod's code in
 * it. Only the rule it applies to each block is the compiled program, and the program reads what vanilla's context holds at that
 * block. Other mods' rule types run as their own objects on vanilla's context, as in the builder.
 */
public final class LoopRules {
    private LoopRules() {}

    /** Chunks vanilla built with the compiled rules. */
    public static final LongAdder CHUNKS = new LongAdder();

    /** The compiled rule for vanilla's loop, or null when vanilla's own rule must run. */
    public static SurfaceRules.SurfaceRule rule(SurfaceRules.RuleSource source, SurfaceRules.Context context) {
        if (!SurfaceHooks.loopRulesEnabled()) {
            return null;
        }
        SurfaceContextAccessor c = (SurfaceContextAccessor) (Object) context;
        SurfaceSystem system = c.ga2$system();
        ChunkAccess chunk = c.ga2$chunk();
        // the steepness and slope conditions read the heightmap as a proto chunk's getHeight reads it
        if (system.getClass() != SurfaceSystem.class || chunk.getClass() != ProtoChunk.class || !chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE_WG)) {
            return null;
        }
        SurfaceProgram program = SurfaceBuilder.program(source);
        if (program.root instanceof Ir.OpaqueRule || !program.guardsHold()) {
            return null;
        }
        SurfaceEval eval = new SurfaceEval(program, system, c.ga2$randomState(), c.ga2$context(),
            chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG), null);
        eval.follow(c);
        if (program.hasOpaque) {
            SurfaceBuilder.instantiateOpaques(program, eval, context);
        }
        CHUNKS.increment();
        return new Rule(eval, SurfaceConfig.compile() ? program.compiled() : program.interpreter);
    }

    private record Rule(SurfaceEval eval, SurfaceProgram.Evaluator evaluator) implements SurfaceRules.SurfaceRule {
        @Override
        public BlockState tryApply(int x, int y, int z) {
            eval.sync();
            return evaluator.apply(eval);
        }
    }
}
