package dev.denismasterherobrine.generatoraccelerator2.compat.biolith;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.denismasterherobrine.generatoraccelerator2.surface.ColumnHooks;
import java.lang.invoke.MethodHandle;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * Biolith's surface builders, run by GA2's surface builder. Biolith wraps two of vanilla's biome tests in the column loop
 * ({@code MixinSurfaceBuilder}). Before the eroded badlands test, Biolith runs every registered builder's {@code generate}.
 * Before the frozen ocean test, which follows the rule walk, Biolith runs every builder's {@code generateLate}. The test
 * itself runs after that. GA2 calls the two wrapper handlers at those places with the values that vanilla's loop holds.
 * The test is passed as the wrapped operation. The registered builders are other mods' code and may touch the chunk
 * directly, so GA2's surface builder runs with the chunk's sections unlocked.
 */
public final class BiolithSurface {
    private BiolithSurface() {}

    public static void register() {
        ColumnHooks.register(BiolithMixins.SURFACE, BiolithSurface::hooks);
    }

    private static List<ColumnHooks.Hook> hooks() throws ReflectiveOperationException {
        // boolean (Holder instance, ResourceKey key, Operation original, @Local(argsOnly) BiomeManager, @Local(argsOnly) ChunkAccess,
        //          @Local BlockColumn, [@Local SurfaceRules.Context,] @Local(ordinal = 4..6) int i1, j1, k1)
        MethodHandle early = ColumnHooks.handler("injectSurfaceBuilders", boolean.class, Holder.class, ResourceKey.class, Operation.class,
            BiomeManager.class, ChunkAccess.class, BlockColumn.class, int.class, int.class, int.class);
        MethodHandle late = ColumnHooks.handler("injectLateSurfaceBuilders", boolean.class, Holder.class, ResourceKey.class, Operation.class,
            BiomeManager.class, ChunkAccess.class, BlockColumn.class, SurfaceRules.Context.class, int.class, int.class, int.class);
        return List.of(new Wrapper("injectSurfaceBuilders", ColumnHooks.Place.TOP, false) {
            @Override
            boolean call(ColumnHooks.Frame f, ResourceKey<Biome> key, Operation<Boolean> op) throws Throwable {
                return (boolean) early.invoke(f.system, f.biome, key, op, f.biomeManager, f.chunk, f.column, f.x, f.z, f.top);
            }
        }, new Wrapper("injectLateSurfaceBuilders", ColumnHooks.Place.FROZEN, true) {
            @Override
            boolean call(ColumnHooks.Frame f, ResourceKey<Biome> key, Operation<Boolean> op) throws Throwable {
                return (boolean) late.invoke(f.system, f.biome, key, op, f.biomeManager, f.chunk, f.column, f.ruleContext, f.x, f.z, f.top);
            }
        });
    }

    private abstract static class Wrapper implements ColumnHooks.Hook {
        private final String handler;
        private final ColumnHooks.Place place;
        private final boolean context;

        Wrapper(String handler, ColumnHooks.Place place, boolean context) {
            this.handler = handler;
            this.place = place;
            this.context = context;
        }

        abstract boolean call(ColumnHooks.Frame f, ResourceKey<Biome> key, Operation<Boolean> op) throws Throwable;

        @Override
        public String handler() {
            return handler;
        }

        @Override
        public ColumnHooks.Place place() {
            return place;
        }

        @Override
        public boolean wrapper() {
            return true;
        }

        @Override
        public boolean needsRuleContext() {
            return context;
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean wrap(ColumnHooks.Frame f, ResourceKey<Biome> key, ColumnHooks.Test original) throws Throwable {
            Operation<Boolean> op = args -> original.is((Holder<Biome>) args[0], (ResourceKey<Biome>) args[1]);
            return call(f, key, op);
        }
    }
}
