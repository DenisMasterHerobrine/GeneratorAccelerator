package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's biome check of a generation stub, for the fossil probe's early answer. Also lets the plugin record changes to the check. */
@Mixin(Structure.class)
public interface StructureInvoker {
    @Invoker("isValidBiome")
    static boolean ga2$isValidBiome(Structure.GenerationStub stub, Structure.GenerationContext context) {
        throw new AssertionError();
    }
}
