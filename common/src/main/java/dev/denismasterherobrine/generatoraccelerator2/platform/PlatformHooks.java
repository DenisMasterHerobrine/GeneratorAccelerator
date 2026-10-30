package dev.denismasterherobrine.generatoraccelerator2.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import javax.annotation.Nullable;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.material.Fluid;

/**
 * Where the loaders' Minecraft differs. Forge patches light emission to depend on position, applies biome modifiers, lets
 * mods pick dungeon mobs and hands structure processors the template. Vanilla (Fabric) does none of that.
 * Each platform module provides a {@link Service} ({@code META-INF/services}).
 */
public final class PlatformHooks {
    private PlatformHooks() {}

    /** The loader's side of {@link PlatformHooks}. */
    public interface Service {
        boolean mayEmitLight(BlockState state, BlockGetter chunk);

        boolean positionalLightEmission(Class<?> blockClass);

        int lightEmission(BlockState state, BlockGetter level, BlockPos pos);

        boolean plainBlockSearch(Class<? extends ChunkAccess> chunkClass);

        EntityType<?> randomMonsterRoomMob(RandomSource random);

        Biome.ClimateSettings climateSettings(Biome biome);

        /**
         * One processor's step on a template block, as the loader's {@code StructureTemplate.processBlockInfos} calls it. Forge
         * calls {@code process} with the template, vanilla {@code processBlock}.
         */
        @Nullable
        StructureTemplate.StructureBlockInfo processTemplateBlock(
            StructureProcessor processor, LevelReader level,
            BlockPos offset, BlockPos pivot, StructureTemplate.StructureBlockInfo raw,
            StructureTemplate.StructureBlockInfo placed,
            StructurePlaceSettings settings,
            @Nullable StructureTemplate template);

        /** Fabric API lets a mod draw a fluid its own way. The other loaders draw every fluid with the game's code. */
        default boolean vanillaFluidRendering(Fluid fluid) {
            return true;
        }
    }

    private static final Service SERVICE = Platform.load(Service.class);

    /** The coarse light source filter a section's palette is tested with before the blocks of {@code chunk} are read. */
    public static boolean mayEmitLight(BlockState state, BlockGetter chunk) {
        return SERVICE.mayEmitLight(state, chunk);
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

    /** The biome's climate as the game sees it. On Forge, after biome modifiers. On vanilla, the biome's own. */
    public static Biome.ClimateSettings climateSettings(Biome biome) {
        return SERVICE.climateSettings(biome);
    }

    /** One processor's step on a template block, as the loader's {@code processBlockInfos} calls it ({@code structure.TemplatePlacement}). */
    @Nullable
    public static StructureTemplate.StructureBlockInfo processTemplateBlock(
            StructureProcessor processor, LevelReader level,
            BlockPos offset, BlockPos pivot, StructureTemplate.StructureBlockInfo raw,
            StructureTemplate.StructureBlockInfo placed,
            StructurePlaceSettings settings,
            @Nullable StructureTemplate template) {
        return SERVICE.processTemplateBlock(processor, level, offset, pivot, raw, placed, settings, template);
    }

    /**
     * True when {@code fluid} is drawn by the game's own {@code LiquidBlockRenderer} code. A water block with water on all six sides
     * then draws nothing ({@code fluid.StillWater#hidden}). Asked on every render, because a mod can change the renderer at any time.
     */
    public static boolean vanillaFluidRendering(Fluid fluid) {
        return SERVICE.vanillaFluidRendering(fluid);
    }
}
