package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import com.google.common.collect.ImmutableList;
import dev.denismasterherobrine.generatoraccelerator2.structure.PlacementRandoms;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.ProcessorRule;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * {@code RuleProcessor.processBlock} with the thread's random source ({@link PlacementRandoms}), seeded the same, instead of a new
 * source for every block of a template. The rules draw from the source only while they are queried. The shared source is used only
 * when every test and block entity modifier of the processor is a vanilla class. No vanilla class keeps the source. Otherwise a
 * new source is made per block, as before. Copied over vanilla's body when no other mod changes it ({@code compat.LateOverwrites}).
 */
@Mixin(value = RuleProcessor.class, priority = 5000)
public abstract class RuleProcessorRandomMixin {
    @Shadow @Final private ImmutableList<ProcessorRule> rules;

    /** 0 not known yet, 1 every rule's parts are vanilla's, 2 not. */
    @Unique
    private volatile byte ga2$vanillaRules;

    @Unique
    private boolean ga2$sharedRandom() {
        byte v = this.ga2$vanillaRules;
        if (v == 0) {
            boolean vanilla = true;
            for (ProcessorRule rule : this.rules) {
                ProcessorRuleAccessor a = (ProcessorRuleAccessor) rule;
                if (!ga2$vanillaClass(a.ga2$inputPredicate()) || !ga2$vanillaClass(a.ga2$locPredicate()) || !ga2$vanillaClass(a.ga2$posPredicate())
                    || !ga2$vanillaClass(a.ga2$blockEntityModifier())) {
                    vanilla = false;
                    break;
                }
            }
            v = vanilla ? (byte) 1 : (byte) 2;
            this.ga2$vanillaRules = v;
        }
        return v == 1;
    }

    @Unique
    private static boolean ga2$vanillaClass(@Nullable Object o) {
        return o != null && o.getClass().getName().startsWith("net.minecraft.");
    }

    @Unique
    @Nullable
    private StructureTemplate.StructureBlockInfo ga2$processBlock(LevelReader level, BlockPos offset, BlockPos pos, StructureTemplate.StructureBlockInfo blockInfo,
                                                                  StructureTemplate.StructureBlockInfo relativeBlockInfo, StructurePlaceSettings settings) {
        long seed = Mth.getSeed(relativeBlockInfo.pos());
        RandomSource randomsource = this.ga2$sharedRandom() ? PlacementRandoms.legacy(seed) : RandomSource.create(seed);
        BlockState blockstate = level.getBlockState(relativeBlockInfo.pos());
        // Iterates by index. The list iterator was an object per block of every template the processor runs on.
        ImmutableList<ProcessorRule> rules = this.rules;
        for (int i = 0, n = rules.size(); i < n; i++) {
            ProcessorRule processorrule = rules.get(i);
            if (processorrule.test(relativeBlockInfo.state(), blockstate, blockInfo.pos(), relativeBlockInfo.pos(), pos, randomsource)) {
                return new StructureTemplate.StructureBlockInfo(relativeBlockInfo.pos(), processorrule.getOutputState(),
                    processorrule.getOutputTag(randomsource, relativeBlockInfo.nbt()));
            }
        }
        return relativeBlockInfo;
    }
}
