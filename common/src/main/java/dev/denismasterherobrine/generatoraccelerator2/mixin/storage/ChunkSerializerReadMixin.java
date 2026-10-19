package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import dev.denismasterherobrine.generatoraccelerator2.storage.LoadPrep;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ProtoChunkTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code ChunkSerializer.read} uses what {@link LoadPrep} prepared on the worker pool instead of making these calls:
 * the two palette decodes per section, the section constructor and the four tick list loaders. Each wrapper falls back
 * to the original call when nothing was prepared for its input, or the prepared result is no longer valid. An empty
 * heightmap priming is skipped.
 */
@Mixin(value = ChunkSerializer.class, priority = 5000)
public abstract class ChunkSerializerReadMixin {
    @Inject(method = "read", at = @At("HEAD"))
    private static void ga2$begin(ServerLevel level, PoiManager poi, RegionStorageInfo info, ChunkPos pos, CompoundTag tag,
                                  CallbackInfoReturnable<ProtoChunk> cir) {
        LoadPrep.begin(tag);
    }

    @Inject(method = "read", at = @At("RETURN"))
    private static void ga2$end(ServerLevel level, PoiManager poi, RegionStorageInfo info, ChunkPos pos, CompoundTag tag,
                                CallbackInfoReturnable<ProtoChunk> cir) {
        LoadPrep.end();
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @WrapOperation(method = "read", at = @At(value = "INVOKE",
        target = "Lcom/mojang/serialization/Codec;parse(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"))
    private static DataResult ga2$preparedPalette(Codec codec, DynamicOps ops, Object input, Operation<DataResult> original) {
        LoadPrep.Bundle bundle = LoadPrep.current();
        if (bundle != null && ops == NbtOps.INSTANCE && input instanceof CompoundTag tag) {
            Object decoded = LoadPrep.takeDecoded(bundle, tag);
            if (decoded != null) {
                if (LoadPrep.VERIFY.sample()) {
                    DataResult vanilla = original.call(codec, ops, input);
                    boolean same = vanilla.result().isPresent() && LoadPrep.sameDecoded(codec, vanilla.result().get(), decoded);
                    LoadPrep.VERIFY.result(same, "palette", "vanilla decode", "prepared decode");
                    if (!same) {
                        return vanilla;
                    }
                }
                return DataResult.success(decoded);
            }
        }
        return original.call(codec, ops, input);
    }

    @WrapOperation(method = "read", at = @At(value = "NEW",
        target = "(Lnet/minecraft/world/level/chunk/PalettedContainer;Lnet/minecraft/world/level/chunk/PalettedContainerRO;)Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private static LevelChunkSection ga2$preparedSection(PalettedContainer<BlockState> states, PalettedContainerRO<Holder<Biome>> biomes,
                                                         Operation<LevelChunkSection> original) {
        LoadPrep.Bundle bundle = LoadPrep.current();
        if (bundle != null) {
            LevelChunkSection section = LoadPrep.takeSection(bundle, states, biomes);
            if (section != null) {
                if (LoadPrep.VERIFY.sample()) {
                    LevelChunkSection vanilla = original.call(states, biomes);
                    boolean same = LoadPrep.sameSection(vanilla, section);
                    LoadPrep.VERIFY.result(same, "section block counts", "vanilla section", "prepared section");
                    if (!same) {
                        return vanilla;
                    }
                }
                return section;
            }
        }
        return original.call(states, biomes);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @WrapOperation(method = "read", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/ticks/LevelChunkTicks;load(Lnet/minecraft/nbt/ListTag;Ljava/util/function/Function;Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/ticks/LevelChunkTicks;"))
    private static LevelChunkTicks ga2$preparedTicks(ListTag list, Function lookup, ChunkPos pos, Operation<LevelChunkTicks> original) {
        LoadPrep.Bundle bundle = LoadPrep.current();
        if (bundle != null) {
            Object ticks = LoadPrep.takeTicks(bundle, list, pos, LevelChunkTicks.class);
            if (ticks != null) {
                return (LevelChunkTicks) ticks;
            }
        }
        return original.call(list, lookup, pos);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @WrapOperation(method = "read", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/ticks/ProtoChunkTicks;load(Lnet/minecraft/nbt/ListTag;Ljava/util/function/Function;Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/ticks/ProtoChunkTicks;"))
    private static ProtoChunkTicks ga2$preparedProtoTicks(ListTag list, Function lookup, ChunkPos pos, Operation<ProtoChunkTicks> original) {
        LoadPrep.Bundle bundle = LoadPrep.current();
        if (bundle != null) {
            Object ticks = LoadPrep.takeTicks(bundle, list, pos, ProtoChunkTicks.class);
            if (ticks != null) {
                return (ProtoChunkTicks) ticks;
            }
        }
        return original.call(list, lookup, pos);
    }

    @WrapOperation(method = "read", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/levelgen/Heightmap;primeHeightmaps(Lnet/minecraft/world/level/chunk/ChunkAccess;Ljava/util/Set;)V"))
    private static void ga2$primeOnlyWhenNeeded(ChunkAccess chunk, Set<Heightmap.Types> types, Operation<Void> original) {
        if (!LoadPrep.skipPrime(types)) {
            original.call(chunk, types);
        }
    }
}
