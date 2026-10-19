package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.MultifaceSpreader;
import net.minecraft.world.level.block.SculkBlock;
import net.minecraft.world.level.block.SculkSpreader;
import net.minecraft.world.level.block.SculkVeinBlock;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late, so the mixin plugin can record other mods' changes to the classes whose work the sculk patch
 * transcription ({@code SculkFast}) replicates. It also records changes to the generation region's no-op level events, sounds
 * and entity lookups that the transcription skips. Hook {@code sculk}. On a change, the fast path stands down.
 */
@Mixin(value = { SculkSpreader.class, SculkSpreader.ChargeCursor.class, SculkBlock.class, SculkVeinBlock.class, MultifaceSpreader.class,
    MultifaceSpreader.DefaultSpreaderConfig.class, MultifaceBlock.class, WorldGenRegion.class },
    targets = { "net.minecraft.world.level.block.SculkVeinBlock$SculkVeinSpreaderConfig", "net.minecraft.world.level.block.SculkBehaviour$1",
        "net.minecraft.world.level.block.MultifaceSpreader$SpreadType$1", "net.minecraft.world.level.block.MultifaceSpreader$SpreadType$2",
        "net.minecraft.world.level.block.MultifaceSpreader$SpreadType$3" },
    priority = 5000)
public abstract class SculkClassesInspectMixin {
}
