package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late so the mixin plugin can record other mods' changes to {@code PalettedContainer}'s codec builders and
 * {@code unpack}, which a loaded section's palettes skip in part ({@code storage.fastPalettes}).
 */
@Mixin(value = PalettedContainer.class, priority = 5000)
public abstract class PaletteCodecInspectMixin {
}
