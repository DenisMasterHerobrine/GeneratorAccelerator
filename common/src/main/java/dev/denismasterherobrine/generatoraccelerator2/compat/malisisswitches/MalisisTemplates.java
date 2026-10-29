package dev.denismasterherobrine.generatoraccelerator2.compat.malisisswitches;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import dev.denismasterherobrine.generatoraccelerator2.structure.TemplatePlacement;
import java.lang.invoke.MethodHandle;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Runs Malisis Switches' {@code processBlockInfos} return handler on GA2's clipped result ({@link MalisisSwitchesMixins#TEMPLATE}).
 * The handler is found once, when the clip hook is first asked, after the template class has its mixins.
 */
public final class MalisisTemplates {
    private MalisisTemplates() {}

    private static volatile MethodHandle restore;

    public static void register() {
        TemplatePlacement.addReturnStep(MalisisTemplates::restore);
        CompatAdapters.register(HookTargets.STRUCTURE_TEMPLATE_CLIP, MalisisSwitchesMixins.TEMPLATE, MalisisTemplates::ready);
    }

    private static boolean ready() {
        if (!MalisisSwitchesMixins.codeKnown()) {
            return false;
        }
        restore = Reflect.mergedMethod(StructureTemplate.class, "$restoreStructureLinks", true, void.class, ServerLevelAccessor.class, BlockPos.class,
            BlockPos.class, StructurePlaceSettings.class, List.class, StructureTemplate.class, CallbackInfoReturnable.class);
        return restore != null;
    }

    private static List<StructureTemplate.StructureBlockInfo> restore(ServerLevelAccessor level, BlockPos offset, BlockPos pivot, StructurePlaceSettings settings,
                                                                     List<StructureTemplate.StructureBlockInfo> blocks, StructureTemplate template,
                                                                     List<StructureTemplate.StructureBlockInfo> result) {
        MethodHandle h = restore;
        if (h == null) {
            return result;
        }
        CallbackInfoReturnable<List<StructureTemplate.StructureBlockInfo>> cir = new CallbackInfoReturnable<>("processBlockInfos", true, result);
        try {
            h.invokeExact(level, offset, pivot, settings, blocks, template, cir);
        } catch (Throwable t) {
            throw Reflect.unchecked(t);
        }
        return cir.getReturnValue();
    }
}
