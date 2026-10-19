package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Beardifier.class)
public interface BeardifierAccessor {
    @Accessor("pieceIterator") ObjectListIterator<Beardifier.Rigid> ga2$pieceIterator();
    @Accessor("junctionIterator") ObjectListIterator<JigsawJunction> ga2$junctionIterator();
}
