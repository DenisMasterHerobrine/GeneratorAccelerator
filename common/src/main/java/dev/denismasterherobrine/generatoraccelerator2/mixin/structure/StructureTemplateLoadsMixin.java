package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.structure.TemplateLoads;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A loaded template without the repository's lock, and a load that holds up only the same template ({@link TemplateLoads}). */
@Mixin(StructureTemplateManager.class)
public abstract class StructureTemplateLoadsMixin {
    @WrapOperation(method = "get(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;", at = @At(value = "INVOKE",
        target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"))
    private Object ga2$loadAlone(Map<Object, Object> map, Object id, Function<Object, Object> load, Operation<Object> original) {
        return TemplateLoads.get(map, id, load, original);
    }
}
