package dev.denismasterherobrine.generatoraccelerator2.platform.neoforge;

import dev.denismasterherobrine.generatoraccelerator2.platform.PlatformHooks;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.neoforge.common.MonsterRoomHooks;
import net.neoforged.neoforge.common.extensions.IBlockExtension;

/** {@link PlatformHooks} on NeoForge: light emission by position ({@link IBlockExtension}) and the monster room mob hook. */
public final class NeoForgePlatformHooks implements PlatformHooks.Service {
    /** NeoForge's coarse filter in {@code findBlockLightSources}. */
    @Override
    public boolean mayEmitLight(BlockState state) {
        return state.hasDynamicLightEmission() || state.getLightEmission(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) != 0;
    }

    // Looking a method up resolves every method signature of the class. A mod's block may declare methods with client-only
    // types, which a dedicated server does not have, so the lookup fails with a LinkageError. Such a class is asked as NeoForge
    // asks it.
    @Override
    public boolean positionalLightEmission(Class<?> blockClass) {
        try {
            Method m = blockClass.getMethod("getLightEmission", BlockState.class, BlockGetter.class, BlockPos.class);
            return m.getDeclaringClass() != IBlockExtension.class;
        } catch (NoSuchMethodException | LinkageError e) {
            return true;
        }
    }

    @Override
    public int lightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        return state.getLightEmission(level, pos);
    }

    /** NeoForge's {@code findBlocks(Predicate, BiPredicate, BiConsumer)}, which its {@code findBlockLightSources} runs. */
    @Override
    public boolean plainBlockSearch(Class<? extends ChunkAccess> chunkClass) {
        try {
            Method m = chunkClass.getMethod("findBlocks", Predicate.class, BiPredicate.class, BiConsumer.class);
            return m.getDeclaringClass() == ChunkAccess.class;
        } catch (NoSuchMethodException | LinkageError e) {
            return false;
        }
    }

    @Override
    public EntityType<?> randomMonsterRoomMob(RandomSource random) {
        return MonsterRoomHooks.getRandomMonsterRoomMob(random);
    }

    @Override
    public boolean isEmpty(BlockState state) {
        return state.isEmpty();
    }

    @Override
    public Biome.ClimateSettings climateSettings(Biome biome) {
        return biome.modifiableBiomeInfo().get().climateSettings();
    }

    /** NeoForge's {@code processBlockInfos} calls {@code process}, which hands the template on and by default calls {@code processBlock}. */
    @Override
    public net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo processTemplateBlock(
            net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor processor, net.minecraft.world.level.LevelReader level,
            BlockPos offset, BlockPos pivot, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo raw,
            net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo placed,
            net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings settings,
            net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate template) {
        return processor.process(level, offset, pivot, raw, placed, settings, template);
    }
}
