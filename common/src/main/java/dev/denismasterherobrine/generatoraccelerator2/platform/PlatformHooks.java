package dev.denismasterherobrine.generatoraccelerator2.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Where the loaders' Minecraft differs. NeoForge patches light emission to depend on position. It counts a section's blocks
 * by its own {@code isEmpty}, applies biome modifiers and lets mods pick dungeon mobs. Vanilla (Fabric) does none of that.
 * Each platform module provides a {@link Service} ({@code META-INF/services}).
 */
public final class PlatformHooks {
    private PlatformHooks() {}

    /** The loader's side of {@link PlatformHooks}. */
    public interface Service {
        boolean mayEmitLight(BlockState state);

        boolean positionalLightEmission(Class<?> blockClass);

        int lightEmission(BlockState state, BlockGetter level, BlockPos pos);

        boolean plainBlockSearch(Class<? extends ChunkAccess> chunkClass);

        EntityType<?> randomMonsterRoomMob(RandomSource random);

        boolean isEmpty(BlockState state);

        Biome.ClimateSettings climateSettings(Biome biome);

        /**
         * One processor's step on a template block, as the loader's {@code StructureTemplate.processBlockInfos} calls it. NeoForge
         * calls {@code process} with the template, vanilla {@code processBlock}.
         */
        @javax.annotation.Nullable
        net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo processTemplateBlock(
            net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor processor, net.minecraft.world.level.LevelReader level,
            BlockPos offset, BlockPos pivot, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo raw,
            net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo placed,
            net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings settings,
            @javax.annotation.Nullable net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate template);
    }

    private static final Service SERVICE = Platform.load(Service.class);

    /** The coarse light source filter a section's palette is tested with before its blocks are read. */
    public static boolean mayEmitLight(BlockState state) {
        return SERVICE.mayEmitLight(state);
    }

    /** True when the block's light emission may depend on its position. Each position is then queried. */
    public static boolean positionalLightEmission(Class<?> blockClass) {
        return SERVICE.positionalLightEmission(blockClass);
    }

    /** The state's light emission at a position. */
    public static int lightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        return SERVICE.lightEmission(state, level, pos);
    }

    /** True when the chunk's class keeps the loader's own block search, which {@code findBlockLightSources} runs. */
    public static boolean plainBlockSearch(Class<? extends ChunkAccess> chunkClass) {
        return SERVICE.plainBlockSearch(chunkClass);
    }

    /** The mob a monster room's spawner gets. */
    public static EntityType<?> randomMonsterRoomMob(RandomSource random) {
        return SERVICE.randomMonsterRoomMob(random);
    }

    /**
     * Whether a chunk section counts the state as empty for its non-empty block count. On NeoForge this is {@code isEmpty()},
     * which blocks may override. On vanilla it is {@code isAir()}.
     */
    public static boolean isEmpty(BlockState state) {
        return SERVICE.isEmpty(state);
    }

    /** The biome's climate as the game sees it. On NeoForge, after biome modifiers. On vanilla, the biome's own. */
    public static Biome.ClimateSettings climateSettings(Biome biome) {
        return SERVICE.climateSettings(biome);
    }

    /** One processor's step on a template block, as the loader's {@code processBlockInfos} calls it ({@code structure.TemplatePlacement}). */
    @javax.annotation.Nullable
    public static net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo processTemplateBlock(
            net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor processor, net.minecraft.world.level.LevelReader level,
            BlockPos offset, BlockPos pivot, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo raw,
            net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo placed,
            net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings settings,
            @javax.annotation.Nullable net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate template) {
        return SERVICE.processTemplateBlock(processor, level, offset, pivot, raw, placed, settings, template);
    }
}
