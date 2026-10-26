package dev.denismasterherobrine.generatoraccelerator2.features.fast;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import dev.denismasterherobrine.generatoraccelerator2.features.region.RegionWriter;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.feature.Feature;
import java.util.function.Predicate;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;

/**
 * Other mods' wrappers of calls inside the vanilla features GA2 transcribes. The transcriptions run them at the same calls.
 * {@code compat/<modid>} sets them before worldgen. For a handled mixin whose wrapper is not set, the feature hook stays
 * off (see {@code compat.FeatureTargets}).
 */
public final class FeatureWraps {
    private FeatureWraps() {}

    /** {@code biome.shouldSnow(level, pos)} in {@code SnowAndFreezeFeature.place}, as another mod wraps it. */
    @FunctionalInterface
    public interface SnowTest {
        boolean shouldSnow(Feature<?> feature, Biome biome, LevelReader level, BlockPos pos);
    }

    public static volatile SnowTest snow;

    /**
     * Sets {@link #snow} unless another mod's change to the same call already did. Two such changes would be nested in an order
     * only the merged method knows. So the second change's adapter fails, and the fast path stands down.
     */
    public static synchronized boolean claimSnow(SnowTest test) {
        if (snow != null) {
            return false;
        }
        snow = test;
        return true;
    }

    /**
     * Ore configurations that another mod's change to the ore feature acts on (owo-lib's). Those configurations keep vanilla's code
     * with that change. Null when no such change is installed.
     */
    public static volatile Predicate<OreConfiguration> oreVeto;

    /** True when {@code config}'s placements must run vanilla's ore code ({@link #oreVeto}). */
    public static boolean oreVetoed(FeatureConfiguration config) {
        Predicate<OreConfiguration> veto = oreVeto;
        return veto != null && config instanceof OreConfiguration oc && veto.test(oc);
    }

    static boolean shouldSnow(Feature<?> feature, Biome biome, LevelReader level, BlockPos pos) {
        SnowTest wrap = snow;
        return wrap != null ? wrap.shouldSnow(feature, biome, level, pos) : biome.shouldSnow(level, pos);
    }

    /**
     * {@code level.setBlock(pos, Blocks.ICE.defaultBlockState(), 2)} in {@code SnowAndFreezeFeature.place}, as another mod
     * wraps it. {@code write} is the wrapped call, done by GA2's exact writer. Its arguments are {@code level, pos, state, flags}.
     */
    @FunctionalInterface
    public interface IceWrite {
        boolean setIce(Feature<?> feature, WorldGenLevel level, BlockPos pos, BlockState state, int flags,
                       Operation<Boolean> write);
    }

    public static volatile IceWrite ice;

    /**
     * Another mod's handler at the tail of {@code GeodeFeature.place} (Pastel's gemstone ores around a finished geode). The
     * transcription runs it where vanilla runs it: before the final {@code return true}, with the same context (and random).
     */
    @FunctionalInterface
    public interface GeodeTail {
        void after(Feature<?> feature, FeaturePlaceContext<?> ctx);
    }

    public static volatile GeodeTail geodeTail;

    static void geodeDone(Feature<?> feature, FeaturePlaceContext<?> ctx) {
        GeodeTail tail = geodeTail;
        if (tail != null) {
            tail.after(feature, ctx);
        }
    }

    /**
     * {@code randomEntityId(random)} in {@code MonsterRoomFeature.place}, as another mod modifies the returned value (Variants &
     * Ventures' biome variants). The handler also gets the locals it reads: the room's origin, the feature's random and the level.
     */
    @FunctionalInterface
    public interface SpawnerMob {
        EntityType<?> mob(Feature<?> feature, EntityType<?> original, BlockPos origin,
                                                     RandomSource random, WorldGenLevel level);
    }

    public static volatile SpawnerMob spawnerMob;

    static EntityType<?> spawnerMob(Feature<?> feature, EntityType<?> original, BlockPos origin,
                                                              RandomSource random, WorldGenLevel level) {
        SpawnerMob wrap = spawnerMob;
        return wrap != null ? wrap.mob(feature, original, origin, random, level) : original;
    }

    /**
     * Another mod's callback right after {@code state.canSurvive(level, pos)} in {@code SimpleBlockFeature.place} (Cobblemon's grains).
     * Returns the callback's answer when it cancels the placement, else null. On null, the placement goes on, whatever
     * {@code canSurvive} said.
     */
    @FunctionalInterface
    public interface SimpleBlockCheck {
        Boolean afterSurvive(Feature<?> feature, FeaturePlaceContext<?> ctx, BlockState state, BlockPos pos,
                             WorldGenLevel level);
    }

    public static volatile SimpleBlockCheck simpleBlock;

    static Boolean simpleBlockAfterSurvive(Feature<?> feature, FeaturePlaceContext<?> ctx, BlockState state,
                                           BlockPos pos, WorldGenLevel level) {
        SimpleBlockCheck check = simpleBlock;
        return check != null ? check.afterSurvive(feature, ctx, state, pos, level) : null;
    }

    /** A local handed to another mod's handler that only reads it ({@code @Local LocalRef<T>}). */
    public static final class Ref<T> implements LocalRef<T> {
        private T value;

        public Ref(T value) {
            this.value = value;
        }

        @Override
        public T get() {
            return value;
        }

        @Override
        public void set(T value) {
            this.value = value;
        }
    }

    static void setIce(Feature<?> feature, WorldGenLevel level, Ga2Region region, BlockPos pos) {
        IceWrite wrap = ice;
        BlockState state = Blocks.ICE.defaultBlockState();
        if (wrap == null) {
            RegionWriter.setBlock(region, pos, state);
            return;
        }
        wrap.setIce(feature, level, pos, state, 2, args -> RegionWriter.setBlock(region, (BlockPos) args[1], (BlockState) args[2]));
    }
}
