package dev.denismasterherobrine.generatoraccelerator2.mixin.surface;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.chunkio.ChunkCopy;
import dev.denismasterherobrine.generatoraccelerator2.surface.LoopRules;
import dev.denismasterherobrine.generatoraccelerator2.surface.SurfaceBuilder;
import dev.denismasterherobrine.generatoraccelerator2.surface.SurfaceHooks;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands chunk surfaces to {@link SurfaceBuilder}. When the builder declines, vanilla builds them, with the compiled rules where
 * vanilla applies its rule ({@link LoopRules}).
 */
@Mixin(value = SurfaceSystem.class, priority = 5000)
public abstract class SurfaceSystemMixin {
    @Inject(method = "buildSurface", at = @At("HEAD"), cancellable = true)
    private void ga2$buildSurface(RandomState randomState, BiomeManager biomeManager, Registry<Biome> biomes, boolean legacy, WorldGenerationContext context,
                                  ChunkAccess chunk, NoiseChunk noiseChunk, SurfaceRules.RuleSource rule, CallbackInfo ci) {
        ProtoChunk expected = null;
        ProtoChunk before = null;
        if (SurfaceHooks.enabled() && SurfaceHooks.VERIFY.sample(chunk)) {
            // With -Dgeneratoraccelerator2.verify.surface=N, vanilla builds a copy first, including every other mod's changes
            before = ChunkCopy.copy((ProtoChunk) chunk, biomes);
            ProtoChunk copy = ChunkCopy.copy((ProtoChunk) chunk, biomes);
            SurfaceHooks.runVanilla(() -> ((SurfaceSystem) (Object) this).buildSurface(randomState, biomeManager, biomes, legacy, context, copy, noiseChunk, rule));
            expected = copy;
        }
        if (SurfaceBuilder.build((SurfaceSystem) (Object) this, randomState, biomeManager, biomes, legacy, context, chunk, noiseChunk, rule)) {
            if (expected != null) {
                SurfaceHooks.VERIFY.compare(before, expected, chunk);
            }
            ci.cancel();
        }
    }

    // another mod's replacement of the build may have no such call
    @WrapOperation(method = "buildSurface", require = 0,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;apply(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object ga2$loopRules(SurfaceRules.RuleSource source, Object context, Operation<Object> original) {
        SurfaceRules.SurfaceRule compiled = LoopRules.rule(source, (SurfaceRules.Context) context);
        return compiled != null ? compiled : original.call(source, context);
    }
}
