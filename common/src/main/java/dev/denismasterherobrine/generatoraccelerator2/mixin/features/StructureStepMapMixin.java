package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.denismasterherobrine.generatoraccelerator2.features.StructureSteps;
import java.util.stream.Collector;
import java.util.stream.Stream;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@code applyBiomeDecoration} groups the whole structure registry by decoration step for every chunk it decorates
 * ({@code registry.stream().collect(groupingBy(step))}). The registry does not change while the server runs, so the map is a function
 * of the registry instance. It is built once per registry and handed out again. The decoration only reads the map, with
 * {@code getOrDefault}. A different registry instance, from another server or a reload that replaces it, builds its own map.
 */
@Mixin(ChunkGenerator.class)
public abstract class StructureStepMapMixin {
    @WrapOperation(method = "applyBiomeDecoration", at = @At(value = "INVOKE",
        target = "Ljava/util/stream/Stream;collect(Ljava/util/stream/Collector;)Ljava/lang/Object;"), require = 0)
    private Object ga2$structureSteps(Stream<?> stream, Collector<?, ?, ?> collector, Operation<Object> original, @Local(argsOnly = true) WorldGenLevel level) {
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        // kept outside the mixin (StructureSteps) so a stopped server's registry can be dropped
        Object[] cached = StructureSteps.cached;
        if (cached != null && cached[0] == registry) {
            return cached[1];
        }
        Object map = original.call(stream, collector);
        StructureSteps.cached = new Object[] { registry, map };
        return map;
    }
}
