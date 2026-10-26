package dev.denismasterherobrine.generatoraccelerator2.platform.fabric;

import dev.denismasterherobrine.generatoraccelerator2.fabric.mixin.BiomeClimateAccessor;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import dev.denismasterherobrine.generatoraccelerator2.platform.PlatformHooks;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.feature.MonsterRoomFeature;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** {@link PlatformHooks} on Fabric: vanilla's game, where light emission does not depend on position. */
public final class FabricPlatformHooks implements PlatformHooks.Service {
    /** Vanilla's {@code findBlockLightSources} filter. */
    @Override
    public boolean mayEmitLight(BlockState state) {
        return state.getLightEmission() != 0;
    }

    @Override
    public boolean positionalLightEmission(Class<?> blockClass) {
        return false;
    }

    @Override
    public int lightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        return state.getLightEmission();
    }

    /** {@code findBlocks(Predicate, BiConsumer)}, which vanilla's {@code findBlockLightSources} runs. */
    private static final String FIND_BLOCKS = Names.runtimeMethod("net/minecraft/world/level/chunk/ChunkAccess", "findBlocks",
        "(Ljava/util/function/Predicate;Ljava/util/function/BiConsumer;)V");

    @Override
    public boolean plainBlockSearch(Class<? extends ChunkAccess> chunkClass) {
        try {
            Method m = chunkClass.getMethod(FIND_BLOCKS, Predicate.class, BiConsumer.class);
            return m.getDeclaringClass() == ChunkAccess.class;
        } catch (NoSuchMethodException | LinkageError e) {
            return false;
        }
    }

    /** Vanilla's {@code MonsterRoomFeature.randomEntityId}. */
    @Override
    public EntityType<?> randomMonsterRoomMob(RandomSource random) {
        return Util.getRandom(MonsterRoomFeature.MOBS, random);
    }

    /**
     * Vanilla counts a state as empty by {@code isAir()}. Fabric API, which every Fabric game has, replaces that test in
     * {@code setBlockState} with a check for the three air blocks. The replacement is Fabric API's {@code ChunkSectionMixin}.
     * Both tests select the same states for every vanilla block.
     */
    @Override
    public boolean isEmpty(BlockState state) {
        return state.is(Blocks.AIR) || state.is(Blocks.VOID_AIR) || state.is(Blocks.CAVE_AIR);
    }

    @Override
    public Biome.ClimateSettings climateSettings(Biome biome) {
        return ((BiomeClimateAccessor) (Object) biome).ga2$climateSettings();
    }

    /** Vanilla's {@code processBlockInfos} calls {@code processBlock}. */
    @Override
    public StructureTemplate.StructureBlockInfo processTemplateBlock(
            StructureProcessor processor, LevelReader level,
            BlockPos offset, BlockPos pivot, StructureTemplate.StructureBlockInfo raw,
            StructureTemplate.StructureBlockInfo placed,
            StructurePlaceSettings settings,
            StructureTemplate template) {
        return processor.processBlock(level, offset, pivot, raw, placed, settings);
    }
}
