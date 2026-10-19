package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.structure.JigsawBlocks;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Jigsaw blocks of a one-palette template from the palette's per-rotation cache ({@link JigsawBlocks}). */
@Mixin(value = StructureTemplate.class, priority = 5000)
public abstract class StructureTemplateMixin {
    @Unique
    private static final HookTargets.Hook GA2_HOOK = HookTargets.hook(HookTargets.STRUCTURE_JIGSAW_BLOCKS);

    /** {@code -Dgeneratoraccelerator2.verify.jigsaw=N}: every N-th cached answer is compared with vanilla's. */
    @Unique
    private static final dev.denismasterherobrine.generatoraccelerator2.chunkio.VerifyCounter GA2_VERIFY =
        new dev.denismasterherobrine.generatoraccelerator2.chunkio.VerifyCounter("jigsaw");
    @Unique
    private static final ThreadLocal<Boolean> GA2_VANILLA = new ThreadLocal<>();

    @Shadow @Final private List<StructureTemplate.Palette> palettes;

    @Inject(method = "filterBlocks(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;"
        + "Lnet/minecraft/world/level/block/Block;Z)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;", at = @At("HEAD"), cancellable = true)
    private void ga2$cachedJigsaws(BlockPos pos, StructurePlaceSettings settings, Block block, boolean relative,
                                   CallbackInfoReturnable<ObjectArrayList<StructureTemplate.StructureBlockInfo>> cir) {
        if (GA2_VERIFY.enabled() && GA2_VANILLA.get() != null) {
            return;
        }
        if (this.palettes.size() == 1 && JigsawBlocks.applies(block, relative, settings, ((StructurePlaceSettingsAccessor) settings).ga2$random())
            && GA2_HOOK.allowed()) {
            ObjectArrayList<StructureTemplate.StructureBlockInfo> cached = JigsawBlocks.filter(this.palettes.get(0), pos, settings.getRotation());
            if (GA2_VERIFY.sample()) {
                GA2_VANILLA.set(Boolean.TRUE);
                try {
                    ObjectArrayList<StructureTemplate.StructureBlockInfo> vanilla = ((StructureTemplate) (Object) this).filterBlocks(pos, settings, block, relative);
                    GA2_VERIFY.result(vanilla.equals(cached), pos + " " + settings.getRotation(), vanilla, cached);
                } finally {
                    GA2_VANILLA.remove();
                }
            }
            cir.setReturnValue(cached);
        }
    }
}
