package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.structure.ModdedStructures;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Generates the starts of modded structures that keep layout state in static fields one at a time ({@link ModdedStructures}). */
@Mixin(ChunkGenerator.class)
public abstract class ModdedStructureStartsMixin {
    @WrapOperation(method = "tryGenerateStructure", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/levelgen/structure/Structure;generate(Lnet/minecraft/core/Holder;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/chunk/ChunkGenerator;"
            + "Lnet/minecraft/world/level/biome/BiomeSource;Lnet/minecraft/world/level/levelgen/RandomState;"
            + "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;JLnet/minecraft/world/level/ChunkPos;I"
            + "Lnet/minecraft/world/level/LevelHeightAccessor;Ljava/util/function/Predicate;)Lnet/minecraft/world/level/levelgen/structure/StructureStart;"),
        require = 0)
    private StructureStart ga2$oneAtATime(Structure structure, Holder<Structure> selected, ResourceKey<Level> dimension, RegistryAccess access,
                                          ChunkGenerator generator, BiomeSource biomes, RandomState random, StructureTemplateManager templates, long seed, ChunkPos pos, int references, LevelHeightAccessor heights,
                                          Predicate<Holder<Biome>> biomeTest, Operation<StructureStart> original) {
        Object lock = ModdedStructures.lock(structure);
        if (lock == null) {
            return original.call(structure, selected, dimension, access, generator, biomes, random, templates, seed, pos, references, heights, biomeTest);
        }
        synchronized (lock) {
            return original.call(structure, selected, dimension, access, generator, biomes, random, templates, seed, pos, references, heights, biomeTest);
        }
    }
}
