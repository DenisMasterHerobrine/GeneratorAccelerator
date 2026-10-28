package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SectionStorage.class)
public interface SectionStorageAccessor {
    @Accessor("worker")
    IOWorker ga2$worker();
}
