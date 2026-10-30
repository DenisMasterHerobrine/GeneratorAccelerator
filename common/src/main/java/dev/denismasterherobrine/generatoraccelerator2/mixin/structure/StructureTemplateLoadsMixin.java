package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.structure.TemplateLoads;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A loaded template without the repository's lock, and a load that holds up only the same template ({@link TemplateLoads}). The
 * repository is ModernFix's soft cache when GA2 took that mixin over ({@link TemplateLoads#repository}).
 */
@Mixin(StructureTemplateManager.class)
public abstract class StructureTemplateLoadsMixin {
    @Shadow
    @Final
    @Mutable
    private Map<ResourceLocation, Optional<StructureTemplate>> structureRepository;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ga2$softRepository(CallbackInfo ci) {
        Map<ResourceLocation, Optional<StructureTemplate>> soft = TemplateLoads.repository();
        if (soft != null) {
            this.structureRepository = soft;
        }
    }

    @WrapOperation(method = "get(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;", at = @At(value = "INVOKE",
        target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"))
    private Object ga2$loadAlone(Map<Object, Object> map, Object id, Function<Object, Object> load, Operation<Object> original) {
        return TemplateLoads.get(map, id, load, original);
    }
}
