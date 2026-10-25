package dev.denismasterherobrine.generatoraccelerator2.compat.eclipticseasons;

import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.denismasterherobrine.generatoraccelerator2.surface.ColumnHooks;
import java.lang.invoke.MethodHandle;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;

/**
 * Ecliptic Seasons' biome map, recorded by GA2's surface builder. The mod's mixin on {@code buildSurface} has three handlers that
 * share three values ({@code @Share}): the map (256 biome ids), how many columns it holds, and a signal. At the head of the build
 * the first handler sets them up. After each column's biome lookup the second records the column (its biome, or for a small biome
 * the river biome of the climate at the lookup's position). At the return the third keeps the map on the chunk when every column
 * is in it. GA2 calls the three handlers at the same places with references of its own for the shared values, and with the
 * lookup's biome and position as vanilla's loop holds them. The handlers read the biome registry, the biomes' tags and the
 * climate sampler. They touch no block and no NoiseChunk.
 */
public final class EclipticSeasonsSurface {
    private EclipticSeasonsSurface() {}

    /** The key of the build's shared values in {@link ColumnHooks.Frame#shared}. */
    private static final Object SHARED = new Object();

    public static void register() {
        ColumnHooks.register(EclipticSeasonsMixins.SURFACE, EclipticSeasonsSurface::hooks);
        // where the GPU builds the surface, the map is recorded on the CPU just before (ColumnHooks.records)
        dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters.register(dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets.GPU_SURFACE,
            EclipticSeasonsMixins.SURFACE, () -> ColumnHooks.ready(EclipticSeasonsMixins.SURFACE));
    }

    private record Shared(ColumnHooks.Ref<int[]> map, ColumnHooks.IntRef count, ColumnHooks.IntRef signal) {}

    private static Shared shared(ColumnHooks.Frame f) {
        Object s = f.shared.get(SHARED);
        if (s == null) {
            s = new Shared(new ColumnHooks.Ref<>(), new ColumnHooks.IntRef(), new ColumnHooks.IntRef());
            f.shared.put(SHARED, s);
        }
        return (Shared) s;
    }

    private static List<ColumnHooks.Hook> hooks() throws ReflectiveOperationException {
        // (buildSurface's arguments, CallbackInfo, @Share("biomeArrays") LocalRef<int[]>, @Share("intCounter") LocalIntRef, @Share("signal") LocalIntRef)
        MethodHandle init = ColumnHooks.handler(EclipticSeasonsMixins.INIT, void.class,
            ColumnHooks.injectionParams(LocalRef.class, LocalIntRef.class, LocalIntRef.class));
        // (..., CallbackInfo, @Local Holder<Biome> biome, @Local(ordinal = 1) MutableBlockPos lookup position, the three shared values)
        MethodHandle column = ColumnHooks.handler(EclipticSeasonsMixins.COLUMN, void.class,
            ColumnHooks.injectionParams(Holder.class, BlockPos.MutableBlockPos.class, LocalRef.class, LocalIntRef.class, LocalIntRef.class));
        MethodHandle end = ColumnHooks.handler(EclipticSeasonsMixins.END, void.class,
            ColumnHooks.injectionParams(LocalRef.class, LocalIntRef.class, LocalIntRef.class));
        return List.of(
            new Hook(EclipticSeasonsMixins.INIT, ColumnHooks.Place.START) {
                @Override
                public void run(ColumnHooks.Frame f) throws Throwable {
                    Shared s = shared(f);
                    init.invoke(f.system, f.randomState, f.biomeManager, f.biomes, f.legacy, f.context, f.chunk, f.noiseChunk, f.rule, f.ci, s.map, s.count, s.signal);
                }

                /** It only makes the map and resets the count and the signal. Where it sits before GA2's callback, vanilla ran it for its own references. */
                @Override
                public boolean setsUpShared() {
                    return true;
                }
            },
            new Hook(EclipticSeasonsMixins.COLUMN, ColumnHooks.Place.TOP) {
                @Override
                public void run(ColumnHooks.Frame f) throws Throwable {
                    Shared s = shared(f);
                    column.invoke(f.system, f.randomState, f.biomeManager, f.biomes, f.legacy, f.context, f.chunk, f.noiseChunk, f.rule, f.ci, f.biome, f.biomePos,
                        s.map, s.count, s.signal);
                }
            },
            new Hook(EclipticSeasonsMixins.END, ColumnHooks.Place.END) {
                @Override
                public void run(ColumnHooks.Frame f) throws Throwable {
                    Shared s = shared(f);
                    end.invoke(f.system, f.randomState, f.biomeManager, f.biomes, f.legacy, f.context, f.chunk, f.noiseChunk, f.rule, f.ci, s.map, s.count, s.signal);
                }
            });
    }

    private abstract static class Hook implements ColumnHooks.Hook {
        private final String handler;
        private final ColumnHooks.Place place;

        Hook(String handler, ColumnHooks.Place place) {
            this.handler = handler;
            this.place = place;
        }

        @Override
        public String handler() {
            return handler;
        }

        @Override
        public ColumnHooks.Place place() {
            return place;
        }

        /** The handlers write only their shared values and, at the end, the chunk's own record. No block. */
        @Override
        public boolean columnLocal() {
            return true;
        }

        @Override
        public boolean readsNoiseChunk() {
            return false;
        }

        @Override
        public boolean record() {
            return true;
        }
    }
}
