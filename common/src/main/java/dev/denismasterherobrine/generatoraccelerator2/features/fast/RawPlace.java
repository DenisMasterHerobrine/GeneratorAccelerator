package dev.denismasterherobrine.generatoraccelerator2.features.fast;

import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.features.FeatureHooks;
import dev.denismasterherobrine.generatoraccelerator2.features.FeaturesCompat;
import dev.denismasterherobrine.generatoraccelerator2.features.modded.ModdedFeatures;
import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.MultifaceGrowthFeature;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.SimpleBlockFeature;
import net.minecraft.world.level.levelgen.feature.SpringFeature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.MultifaceGrowthConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SpringConfiguration;

/**
 * The commonest features placed without the {@code FeaturePlaceContext} that {@code Feature.place(config, level, generator, random,
 * origin)} makes for each call (one object per placement: ores alone make a few hundred per chunk, most of them turned away by the
 * ore gate at once). The same tests as each feature's own hook ({@code OreFeatureMixin}, {@code SimpleBlockFeatureMixin},
 * {@code MultifaceGrowthFeatureMixin}, {@code SpringFeatureMixin}), then vanilla's write test, then the fast path. Only while no other
 * mod changes that {@code Feature.place} (hook {@code featureEntry}).
 */
public final class RawPlace {
    private RawPlace() {}

    private static final FeatureTargets.Hook ENTRY = FeatureTargets.hook("featureEntry");
    private static final int NONE = 0, ORE = 1, SIMPLE_BLOCK = 2, MULTIFACE = 3, SPRING = 4;

    /** Which fast path a feature class takes, as its own hook decides by class ({@code ModdedFeatures.fastPath}). */
    private static final ClassValue<Integer> KIND = new ClassValue<>() {
        @Override
        protected Integer computeValue(Class<?> type) {
            if (OreFeature.class.isAssignableFrom(type) && ModdedFeatures.fastPath(type, OreFeature.class)) {
                return ORE;
            } else if (SimpleBlockFeature.class.isAssignableFrom(type) && ModdedFeatures.fastPath(type, SimpleBlockFeature.class)) {
                return SIMPLE_BLOCK;
            } else if (MultifaceGrowthFeature.class.isAssignableFrom(type) && ModdedFeatures.fastPath(type, MultifaceGrowthFeature.class)) {
                return MULTIFACE;
            } else if (SpringFeature.class.isAssignableFrom(type) && ModdedFeatures.fastPath(type, SpringFeature.class)) {
                return SPRING;
            }
            return NONE;
        }
    };

    /** {@code feature.place(config, level, generator, random, origin)} through the fast path, or null when it must run vanilla's way. */
    public static Boolean place(Feature<?> feature, FeatureConfiguration config, WorldGenLevel level, RandomSource random, BlockPos origin) {
        int kind = KIND.get(feature.getClass());
        if (kind == NONE || !(level instanceof Ga2Region region) || !region.ga2$plain() || !FeatureHooks.enabled() || !ENTRY.allowed()) {
            return null;
        }
        switch (kind) {
            case ORE -> {
                if (!FeaturesCompat.ORE.allowed() || FeatureWraps.oreVetoed(config)) {
                    return null;
                }
                return level.ensureCanWrite(origin) && OreFast.place(random, origin, (OreConfiguration) config, region);
            }
            case SIMPLE_BLOCK -> {
                // another mod's callback takes the context
                if (!FeaturesCompat.SIMPLE_BLOCK.allowed() || FeatureWraps.simpleBlock != null) {
                    return null;
                }
                return level.ensureCanWrite(origin) && SmallFeatures.simpleBlock(feature, null, (SimpleBlockConfiguration) config, level, random, origin, region);
            }
            case MULTIFACE -> {
                if (!FeaturesCompat.MULTIFACE.allowed()) {
                    return null;
                }
                return level.ensureCanWrite(origin) && SmallFeatures.multiface((MultifaceGrowthConfiguration) config, level, random, origin, region);
            }
            default -> {
                if (!FeaturesCompat.SPRING.allowed()) {
                    return null;
                }
                return level.ensureCanWrite(origin) && SmallFeatures.spring((SpringConfiguration) config, level, origin, region);
            }
        }
    }
}
