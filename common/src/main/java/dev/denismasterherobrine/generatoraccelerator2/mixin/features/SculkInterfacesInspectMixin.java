package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import net.minecraft.world.level.block.MultifaceSpreader;
import net.minecraft.world.level.block.SculkBehaviour;
import org.spongepowered.asm.mixin.Mixin;

/** Adds nothing: the interface half of {@link SculkClassesInspectMixin} (the default methods of the sculk behaviour and spread config). */
@Mixin(value = { SculkBehaviour.class, MultifaceSpreader.SpreadConfig.class }, priority = 5000)
public interface SculkInterfacesInspectMixin {
}
