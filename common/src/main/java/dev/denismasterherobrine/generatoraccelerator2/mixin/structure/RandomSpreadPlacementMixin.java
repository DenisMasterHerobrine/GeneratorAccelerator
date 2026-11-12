package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import dev.denismasterherobrine.generatoraccelerator2.compat.structurify.StructurifyMixins;
import dev.denismasterherobrine.generatoraccelerator2.structure.PlacementRandoms;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * {@code getPotentialStructureChunk} on the thread's random source ({@link PlacementRandoms}) instead of a new one per call.
 * Vanilla made six objects for every structure set check at every chunk, and for every chunk an exclusion zone looks at.
 * Seeding and draws are unchanged. Copied over vanilla's body when no other mod changes it ({@code compat.LateOverwrites}).
 */
@Mixin(value = RandomSpreadStructurePlacement.class, priority = 5000)
public abstract class RandomSpreadPlacementMixin extends StructurePlacement {
    @Shadow @Final private int spacing;
    @Shadow @Final private int separation;
    @Shadow @Final private RandomSpreadType spreadType;

    @Shadow public abstract int spacing();

    @Shadow public abstract int separation();

    protected RandomSpreadPlacementMixin() {
        super(null, null, 0.0F, 0, null);
    }

    @Unique
    private ChunkPos ga2$getPotentialStructureChunk(long seed, int x, int z) {
        // Structurify's change gives each read its configured value, the accessors' value (compat.structurify)
        int spacing = StructurifyMixins.spreadAccessors ? this.spacing() : this.spacing;
        int separation = StructurifyMixins.spreadAccessors ? this.separation() : this.separation;
        int i = Math.floorDiv(x, spacing);
        int j = Math.floorDiv(z, spacing);
        WorldgenRandom worldgenrandom = PlacementRandoms.worldgen();
        worldgenrandom.setLargeFeatureWithSalt(seed, i, j, this.salt());
        int k = spacing - separation;
        int l = this.spreadType.evaluate(worldgenrandom, k);
        int i1 = this.spreadType.evaluate(worldgenrandom, k);
        return new ChunkPos(i * spacing + l, j * spacing + i1);
    }
}
