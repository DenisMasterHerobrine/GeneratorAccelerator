package dev.denismasterherobrine.generatoraccelerator2.mixin.chunkload;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@code ChunkMap.getChunkRangeFuture} labels each neighbour's save dependency with {@code "getChunkRangeFuture " + pos + " " + i}.
 * {@code ChunkHolder.addSaveDependency} keeps the label only in a debug history that the game never makes ({@code chunkToSaveHistory}
 * is always null). Building it took 0.5-2% of the server thread while pregenerating, most of it in the position's text. That text is
 * left out of the label. Nothing reads it.
 *
 * <p>Forge's {@code ChunkMap}, rebuilt with Forge's patches, turns the position into text with {@code String.valueOf} first. Vanilla's
 * bytecode, which Fabric runs, hands the position to the string concatenation itself. There is nothing to wrap there, so the injection
 * is optional.
 */
@Mixin(ChunkMap.class)
abstract class ChunkRangeLabelMixin {
    @WrapOperation(method = "getChunkRangeFuture", require = 0,
        at = @At(value = "INVOKE", target = "Ljava/lang/String;valueOf(Ljava/lang/Object;)Ljava/lang/String;"))
    private String ga2$noPositionText(Object pos, Operation<String> original) {
        return "";
    }
}
