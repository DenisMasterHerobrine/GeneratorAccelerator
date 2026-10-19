package dev.denismasterherobrine.generatoraccelerator2.compat.owo;

import com.google.common.collect.MapMaker;
import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.FeatureTargets;
import dev.denismasterherobrine.generatoraccelerator2.features.fast.FeatureWraps;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;

/**
 * owo-lib's change to the ore feature ({@link OwoMixins#COPENHAGEN}) combined with GA2's ore path. An ore whose targets name a
 * block on owo's list ({@code Maldenhagen.isOnCopium}) is placed by vanilla's code, with owo's handlers. Every other ore takes
 * GA2's path, because owo's handlers do nothing for it. Decided once per ore configuration, since mods fill the list while they
 * register.
 */
public final class OwoOres {
    private OwoOres() {}

    private static final ConcurrentMap<OreConfiguration, Boolean> VETOED = new MapMaker().weakKeys().makeMap();

    public static void register() {
        CompatAdapters.register(FeatureTargets.compatKey("ore"), OwoMixins.COPENHAGEN, OwoOres::setUp);
    }

    private static boolean setUp() {
        try {
            Class<?> maldenhagen = Class.forName("io.wispforest.owo.util.Maldenhagen", true, OwoOres.class.getClassLoader());
            MethodHandle copium = MethodHandles.publicLookup().findStatic(maldenhagen, "isOnCopium", MethodType.methodType(boolean.class, Block.class));
            FeatureWraps.oreVeto = config -> {
                Boolean known = VETOED.get(config);
                if (known == null) {
                    boolean any = false;
                    try {
                        for (OreConfiguration.TargetBlockState target : config.targetStates) {
                            if ((boolean) copium.invoke(target.state.getBlock())) {
                                any = true;
                                break;
                            }
                        }
                    } catch (Throwable t) {
                        any = true;
                    }
                    known = any;
                    VETOED.put(config, known);
                }
                return known;
            };
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }
}
