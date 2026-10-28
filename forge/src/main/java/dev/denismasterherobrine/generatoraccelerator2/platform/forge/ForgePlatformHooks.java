package dev.denismasterherobrine.generatoraccelerator2.platform.forge;

import dev.denismasterherobrine.generatoraccelerator2.platform.PlatformHooks;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraftforge.common.DungeonHooks;
import net.minecraftforge.common.extensions.IForgeBlock;

/** {@link PlatformHooks} on Forge: light emission by position ({@link IForgeBlock}), biome modifiers and the dungeon mob hook. */
public final class ForgePlatformHooks implements PlatformHooks.Service {
    /** Forge's palette filter in {@code findBlockLightSources}: the state's emission in the chunk, at the origin. */
    @Override
    public boolean mayEmitLight(BlockState state, BlockGetter chunk) {
        return state.getLightEmission(chunk, BlockPos.ZERO) != 0;
    }

    // Looking a method up resolves every method signature of the class. A mod's block may declare methods with client-only
    // types, which a dedicated server does not have, so the lookup fails with a LinkageError. Such a class is asked as Forge
    // asks it.
    @Override
    public boolean positionalLightEmission(Class<?> blockClass) {
        try {
            Method m = blockClass.getMethod("getLightEmission", BlockState.class, BlockGetter.class, BlockPos.class);
            return m.getDeclaringClass() != IForgeBlock.class;
        } catch (NoSuchMethodException | LinkageError e) {
            return true;
        }
    }

    @Override
    public int lightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        return state.getLightEmission(level, pos);
    }

    /** Forge's {@code findBlocks(BiPredicate, BiConsumer)}, which its {@code findBlockLightSources} runs. */
    @Override
    public boolean plainBlockSearch(Class<? extends ChunkAccess> chunkClass) {
        try {
            Method m = chunkClass.getMethod("findBlocks", BiPredicate.class, BiConsumer.class);
            return m.getDeclaringClass() == ChunkAccess.class;
        } catch (NoSuchMethodException | LinkageError e) {
            return false;
        }
    }

    @Override
    public EntityType<?> randomMonsterRoomMob(RandomSource random) {
        return DungeonHooks.getRandomDungeonMob(random);
    }

    @Override
    public Biome.ClimateSettings climateSettings(Biome biome) {
        return biome.getModifiedClimateSettings();
    }

    /** Forge's {@code processBlockInfos} calls {@code process}, which hands the template on and by default calls {@code processBlock}. */
    @Override
    public StructureTemplate.StructureBlockInfo processTemplateBlock(
            StructureProcessor processor, LevelReader level,
            BlockPos offset, BlockPos pivot, StructureTemplate.StructureBlockInfo raw,
            StructureTemplate.StructureBlockInfo placed,
            StructurePlaceSettings settings,
            StructureTemplate template) {
        return processor.process(level, offset, pivot, raw, placed, settings, template);
    }
}
