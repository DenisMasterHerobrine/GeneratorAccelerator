package dev.denismasterherobrine.generatoraccelerator2.compat.alexscaves;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.CodeShape;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.Ir;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.SurfaceEval;
import dev.denismasterherobrine.generatoraccelerator2.surface.compile.SurfaceExtern;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import org.slf4j.Logger;

/**
 * Alex's Caves' surface condition {@code ACBiomeConditionSource(rarityOffset)}: true at a block of the overworld at y 50 or below
 * whose column lies in the cave biome of that rarity offset. The condition runs per block as an opaque rule object, and each test
 * looks the column up in the mod's Voronoi cells through a per-thread cache. In a 575-mod pack that was 9% of the workers' time.
 * Below y 50 the answer depends only on the column. So it is compiled as {@code y <= 50} and an extern condition cached per column.
 * The extern calls the mod's own lookups in the order the mod's {@code test} does.
 *
 * <p>Written against Alex's Caves 2.0.10. The condition's {@code test} and the source's {@code apply} must have the fingerprints
 * ({@link CodeShape}) of that version, else the condition stays an opaque object.
 */
public final class AlexsCavesRules {
    private AlexsCavesRules() {}

    private static final Logger LOGGER = Ga2Log.get("Surface");
    static final String SOURCE = "com.github.alexmodguy.alexscaves.server.level.surface.ACSurfaceRuleConditionRegistry$ACBiomeConditionSource";
    static final String CONDITION = SOURCE + "$1ACBiomeCondition";
    private static final String TEST_FINGERPRINT = "e32a18f8c8e7e57f";
    private static final String APPLY_FINGERPRINT = "9fbe2adce3cfb3f4";
    /** The highest y the condition holds at (the {@code bipush 50} in the mod's test). */
    private static final int MAX_Y = 50;

    private static MethodHandle dimension, seed, rareInfo, offsetId, biomeAt, snapshot, rarityOf;

    public static void register() {
        if (!codeKnown() || !setUp()) {
            return;
        }
        SurfaceExtern.register((SurfaceExtern.CondLowering) AlexsCavesRules::lower);
    }

    /**
     * The biome answers kept for points outside a region ({@code biome.BiomeAnswers}) with Alex's Caves' lookup in the source. The
     * lookup reads the mod's seed holder and biome map. Both are set while the server starts. Nothing is kept before both are.
     */
    public static void registerBiomeAnswers() {
        Class<?> seeds = Reflect.type("com.github.alexmodguy.alexscaves.server.level.biome.ACWorldSeedHolder");
        Class<?> maps = Reflect.type("com.github.alexmodguy.alexscaves.server.level.biome.ACBiomeMapHolder");
        if (seeds == null || maps == null) {
            return;
        }
        try {
            MethodHandles.Lookup l = MethodHandles.publicLookup();
            MethodHandle seedsSet = l.findStatic(seeds, "isInitialized", MethodType.methodType(boolean.class));
            MethodHandle mapsSet = l.findStatic(maps, "isInitialized", MethodType.methodType(boolean.class));
            dev.denismasterherobrine.generatoraccelerator2.biome.BiomeAnswers.requireReady(() -> {
                try {
                    return (boolean) seedsSet.invokeExact() && (boolean) mapsSet.invokeExact();
                } catch (Throwable t) {
                    return false;
                }
            });
            dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters.register(
                dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets.BIOME_ANSWERS, AlexsCavesMixins.BIOME_SOURCE, () -> true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.info("Alex's Caves biome lookup: {} -> biomes outside a region looked up each time", e.toString());
        }
    }

    private static boolean codeKnown() {
        String test = CodeShape.fingerprint(CodeShape.method(CodeShape.classNode(CONDITION), "test", "()Z"));
        String apply = CodeShape.fingerprint(CodeShape.method(CodeShape.classNode(SOURCE), "apply",
            "(Lnet/minecraft/world/level/levelgen/SurfaceRules$Context;)Lnet/minecraft/world/level/levelgen/SurfaceRules$Condition;"));
        if (!test.equals(TEST_FINGERPRINT) || !apply.equals(APPLY_FINGERPRINT)) {
            LOGGER.info("Alex's Caves biome surface condition: unknown code (test {}, apply {}) -> runs as an object", test, apply);
            return false;
        }
        return true;
    }

    private static boolean setUp() {
        Class<?> holder = Reflect.type("com.github.alexmodguy.alexscaves.server.level.biome.ACWorldSeedHolder");
        Class<?> rarity = Reflect.type("com.github.alexmodguy.alexscaves.server.level.biome.ACBiomeRarity");
        Class<?> info = Reflect.type("com.github.alexmodguy.alexscaves.server.misc.VoronoiGenerator$VoronoiInfo");
        Class<?> config = Reflect.type("com.github.alexmodguy.alexscaves.server.config.BiomeGenerationConfig");
        Class<?> noiseCondition = Reflect.type("com.github.alexmodguy.alexscaves.server.config.BiomeGenerationNoiseCondition");
        if (holder == null || rarity == null || info == null || config == null || noiseCondition == null) {
            return false;
        }
        try {
            MethodHandles.Lookup l = MethodHandles.publicLookup();
            dimension = l.findStatic(holder, "getDimension", MethodType.methodType(ResourceKey.class));
            seed = l.findStatic(holder, "getSeed", MethodType.methodType(long.class));
            rareInfo = l.findStatic(rarity, "getRareBiomeInfoForQuad", MethodType.methodType(info, long.class, int.class, int.class))
                .asType(MethodType.methodType(Object.class, long.class, int.class, int.class));
            offsetId = l.findStatic(rarity, "getRareBiomeOffsetId", MethodType.methodType(int.class, info)).asType(MethodType.methodType(int.class, Object.class));
            biomeAt = l.findStatic(rarity, "getACBiomeForPosition", MethodType.methodType(ResourceKey.class, long.class, int.class, int.class));
            snapshot = l.findStatic(config, "getBiomesSnapshot", MethodType.methodType(Map.class));
            rarityOf = l.findVirtual(noiseCondition, "getRarityOffset", MethodType.methodType(int.class)).asType(MethodType.methodType(int.class, Object.class));
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.info("Alex's Caves biome surface condition: {} -> runs as an object", e.toString());
            return false;
        }
    }

    static Ir.Cond lower(SurfaceRules.ConditionSource source, SurfaceExtern.Context ctx) {
        if (!source.getClass().getName().equals(SOURCE) || !(Reflect.field(source, SOURCE, "rarityOffset") instanceof Integer offset)) {
            return null;
        }
        Ir.Cond below = ctx.cond(SurfaceRules.not(SurfaceRules.yBlockCheck(VerticalAnchor.absolute(MAX_Y + 1), 0)));
        return ctx.all(List.of(below, ctx.extern(new Column(offset))));
    }

    /** The column's part of the mod's test: the level, the seed, the rare biome's cell, the biome there and its config. */
    private static final class Column implements SurfaceExtern.Cond {
        private final int offset;

        Column(int offset) {
            this.offset = offset;
        }

        @Override
        public boolean test(SurfaceEval s) {
            try {
                // the mod's condition takes the dimension when it is made for a chunk; per column here, the same value
                if (!Level.OVERWORLD.equals((ResourceKey<?>) dimension.invokeExact())) {
                    return false;
                }
                long worldSeed = (long) seed.invokeExact();
                if (worldSeed == 0L) {
                    return false;
                }
                Object cell = rareInfo.invokeExact(worldSeed, s.blockX >> 2, s.blockZ >> 2);
                if (cell == null || (int) offsetId.invokeExact(cell) != offset) {
                    return false;
                }
                ResourceKey<?> biome = (ResourceKey<?>) biomeAt.invokeExact(worldSeed, s.blockX, s.blockZ);
                if (biome == null) {
                    return false;
                }
                Object noise = ((Map<?, ?>) snapshot.invokeExact()).get(biome);
                return noise != null && (int) rarityOf.invokeExact(noise) == offset;
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        @Override
        public Ir.Scope scope() {
            return Ir.Scope.COLUMN;
        }

        @Override
        public boolean positional() {
            return true;
        }
    }
}
