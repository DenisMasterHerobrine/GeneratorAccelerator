package dev.denismasterherobrine.generatoraccelerator2.mixin.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Region file internals for {@code FastRegionWrite}. Applied late so the mixin plugin sees other mods' changes. */
@Mixin(value = RegionFile.class, priority = 5000)
public interface RegionFileAccessor {
    @Accessor("version")
    RegionFileVersion ga2$version();

    @Invoker("write")
    void ga2$write(ChunkPos pos, ByteBuffer data) throws IOException;
}
