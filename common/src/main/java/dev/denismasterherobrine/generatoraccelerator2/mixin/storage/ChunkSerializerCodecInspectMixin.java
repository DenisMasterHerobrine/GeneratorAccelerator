package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late so the mixin plugin can record other mods' changes to the section codecs {@code ChunkSerializer} makes
 * (its static block state codec and {@code makeBiomeCodec}), which a loaded section's palettes skip in part ({@code storage.fastPalettes}).
 */
@Mixin(value = ChunkSerializer.class, priority = 5000)
public abstract class ChunkSerializerCodecInspectMixin {
}
