package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import dev.denismasterherobrine.generatoraccelerator2.storage.LoadPrep;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A section's three block counts, compared by {@code storage.LoadPrep}'s verify switch. */
@Mixin(LevelChunkSection.class)
public interface LevelChunkSectionAccessor extends LoadPrep.LevelChunkSectionCounts {
    @Override
    @Accessor("nonEmptyBlockCount")
    short ga2$nonEmptyBlockCount();

    @Override
    @Accessor("tickingBlockCount")
    short ga2$tickingBlockCount();

    @Override
    @Accessor("tickingFluidCount")
    short ga2$tickingFluidCount();
}
