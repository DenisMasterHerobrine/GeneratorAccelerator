package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import net.minecraft.world.level.chunk.storage.ChunkStorage;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late so the mixin plugin can record other mods' changes to {@code ChunkStorage.upgradeChunkTag}. Clean
 * loads take an unchanged tag instance as a tag the data fixer left alone ({@code storage.CleanLoads}).
 */
@Mixin(value = ChunkStorage.class, priority = 5000)
public abstract class ChunkStorageUpgradeInspectMixin {
}
