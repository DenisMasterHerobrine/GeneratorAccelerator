package dev.denismasterherobrine.generatoraccelerator2.mixin.carver;

import net.minecraft.world.level.levelgen.carver.CanyonWorldCarver;
import net.minecraft.world.level.levelgen.carver.CaveWorldCarver;
import net.minecraft.world.level.levelgen.carver.NetherWorldCarver;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds nothing. Applied late, so the mixin plugin can record other mods' changes to the vanilla carver classes. The
 * carver runner replays the tunnels, rooms and block carving of these classes itself. If another mod changed them, the
 * runner leaves those carvers to vanilla.
 */
@Mixin(value = { WorldCarver.class, CaveWorldCarver.class, CanyonWorldCarver.class, NetherWorldCarver.class }, priority = 5000)
public abstract class CarverClassesInspectMixin {
}
