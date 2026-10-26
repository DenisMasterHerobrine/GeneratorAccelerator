package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import dev.denismasterherobrine.generatoraccelerator2.features.FeatureHooks;
import dev.denismasterherobrine.generatoraccelerator2.features.FeaturesCompat;
import dev.denismasterherobrine.generatoraccelerator2.features.placement.PlacementPipeline;
import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Optional;

/** Routes placement through the explicit pipeline. Applied late so the plugin can see other mods' changes to the method first. */
@Mixin(value = PlacedFeature.class, priority = 5000)
public abstract class PlacedFeatureMixin implements PlacementPipeline.Kinds {
    @Unique
    private volatile PlacementPipeline.KindsOf ga2$kinds;

    @Override
    public PlacementPipeline.KindsOf ga2$kinds() {
        return this.ga2$kinds;
    }

    @Override
    public void ga2$setKinds(PlacementPipeline.KindsOf kinds) {
        this.ga2$kinds = kinds;
    }

    @Inject(method = "placeWithContext", at = @At("HEAD"), cancellable = true)
    private void ga2$place(PlacementContext ctx, RandomSource random, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (FeatureHooks.enabled() && FeaturesCompat.placementPipelineAllowed()) {
            cir.setReturnValue(PlacementPipeline.place((PlacedFeature) (Object) this, ctx, random, pos));
        }
    }

    /** A nested placement takes the region's context ({@code Ga2Region.ga2$placementContext}) instead of a new one for each call. */
    @WrapOperation(method = "place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;"
        + "Lnet/minecraft/core/BlockPos;)Z", at = @At(value = "NEW", target = "net/minecraft/world/level/levelgen/placement/PlacementContext"))
    private PlacementContext ga2$regionContext(WorldGenLevel level, ChunkGenerator generator,
                                               Optional<PlacedFeature> top, Operation<PlacementContext> original) {
        if (level instanceof Ga2Region region && top.isEmpty() && FeatureHooks.enabled()) {
            PlacementContext c = region.ga2$placementContext(generator);
            if (c != null) {
                return c;
            }
        }
        return original.call(level, generator, top);
    }
}
