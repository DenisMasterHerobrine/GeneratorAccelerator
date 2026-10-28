package dev.denismasterherobrine.generatoraccelerator2.mixin.features;

import dev.denismasterherobrine.generatoraccelerator2.logging.HolderNames;
import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.denismasterherobrine.generatoraccelerator2.features.FeatureStats;
import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import dev.denismasterherobrine.generatoraccelerator2.surface.BiomeZoom;
import dev.denismasterherobrine.generatoraccelerator2.surface.NoiseBiomeMemo;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Biome lookups on a generation region go through the region's {@link BiomeZoom}. Such a lookup is
 * {@code WorldGenRegion.getBiome}, i.e. {@code BiomeManager.getBiome} over the region. The zoom does the same arithmetic,
 * with the per-quart-point work cached across the region's features. A {@link NoiseBiomeMemo} scope wraps other mods'
 * code known to query a biome source through its own manager. Inside that scope, the source's answer per point is
 * remembered. Every other lookup keeps the vanilla path.
 */
@Mixin(value = BiomeManager.class, priority = 5000)
public abstract class BiomeManagerMixin {
    @Shadow @Final private BiomeManager.NoiseBiomeSource noiseBiomeSource;

    /** {@code -Dgeneratoraccelerator2.verify.regionBiome=N}: every N-th region lookup is checked against vanilla's. */
    @Unique private static final int GA2_VERIFY_EVERY = Integer.getInteger("generatoraccelerator2.verify.regionBiome", 0);
    @Unique private static final AtomicLong GA2_SEEN = new AtomicLong();
    @Unique private static final AtomicLong GA2_DIFFERING = new AtomicLong();

    @WrapMethod(method = "getBiome")
    private Holder<Biome> ga2$regionZoom(BlockPos pos, Operation<Holder<Biome>> original) {
        // Kept small so it is compiled into the wrapper. The operation object then never exists.
        Holder<Biome> biome = this.ga2$fastBiome(pos);
        if (biome == null) {
            return original.call(pos);
        }
        if (GA2_VERIFY_EVERY > 0) {
            ga2$verify(pos, biome, original);
        }
        return biome;
    }

    /** Returns the answer from the region zoom or the memo, or null to fall back to vanilla. */
    @Unique
    private Holder<Biome> ga2$fastBiome(BlockPos pos) {
        if (this.noiseBiomeSource instanceof Ga2Region region) {
            BiomeZoom zoom = region.ga2$zoom();
            if (zoom != null) {
                Holder<Biome> biome = zoom.biome(pos.getX(), pos.getY(), pos.getZ());
                if (FeatureStats.DETAILED) {
                    FeatureStats.REGION_BIOME_LOOKUPS.increment();
                }
                return biome;
            }
            return null;
        }
        NoiseBiomeMemo memo = NoiseBiomeMemo.current();
        return memo != null ? memo.biome((BiomeManager) (Object) this, pos.getX(), pos.getY(), pos.getZ()) : null;
    }

    @Unique
    private void ga2$verify(BlockPos pos, Holder<Biome> biome, Operation<Holder<Biome>> original) {
        // only region lookups are checked, as before
        if (this.noiseBiomeSource instanceof Ga2Region && GA2_SEEN.incrementAndGet() % GA2_VERIFY_EVERY == 0) {
            Holder<Biome> vanilla = original.call(pos);
            if (vanilla != biome && GA2_DIFFERING.incrementAndGet() <= 10) {
                Ga2Log.get("Verify").warn("region biome mismatch at {}: expected {}, got {}",
                    pos, HolderNames.name(vanilla), HolderNames.name(biome));
            }
        }
    }
}
