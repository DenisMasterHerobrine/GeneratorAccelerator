package dev.denismasterherobrine.generatoraccelerator2.compat.lootr.mixin;

import dev.denismasterherobrine.generatoraccelerator2.compat.lootr.LootrEntries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drops an unloaded chunk's Lootr entries and its place in Lootr's loaded chunks ({@link LootrEntries#unloaded}). */
@Mixin(ServerLevel.class)
public abstract class ServerLevelLootrUnloadMixin {
    @Inject(method = "unload", at = @At("HEAD"))
    private void ga2$lootrUnload(LevelChunk chunk, CallbackInfo ci) {
        LootrEntries.unloaded(((ServerLevel) (Object) this).dimension(), chunk.getPos());
    }
}
