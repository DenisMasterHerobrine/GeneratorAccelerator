package dev.denismasterherobrine.generatoraccelerator2.mixin.dfc;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.aquifer.AquiferFastPath;
import dev.denismasterherobrine.generatoraccelerator2.aquifer.SurfaceLevelCache;
import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.StateDependence;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.pipeline.Compiler;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.BoundSampler;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.Ga2RandomState;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ProgramSet;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Compiles the router and climate sampler as soon as a {@link RandomState} is built, and binds its own sampler to the program. */
@Mixin(RandomState.class)
public abstract class RandomStateMixin implements Ga2RandomState {
    @Shadow @Final private NoiseRouter router;
    @Shadow @Final private Climate.Sampler sampler;
    @Shadow @Final private PositionalRandomFactory aquiferRandom;

    @Unique
    private ProgramSet ga2$programs;
    /** The binding of this state's own sampler. The binding map holds it weakly, so it lives as long as this state does. */
    @Unique
    private BoundSampler ga2$sharedSampler;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ga2$compile(NoiseGeneratorSettings settings, HolderGetter<NormalNoise.NoiseParameters> noises, long seed, CallbackInfo ci) {
        this.ga2$programs = Compiler.compile(this.router, this.sampler);
        if (this.ga2$programs != null && this.ga2$programs.globalSampler() != null) {
            this.ga2$sharedSampler = BoundSampler.bindShared(this.sampler, this.ga2$programs.globalSampler());
        }
        java.util.Map<String, Node> roots = this.ga2$programs != null ? this.ga2$programs.routerRoots() : null;
        boolean classifySafe = roots != null && StateDependence.orderIndependent(roots, AquiferFastPath.STATUS_FIELDS);
        // a cache_2d over y below the floodedness alone (Regions Unexplored's inferno weight) is ruled out aquifer by aquifer,
        // over the columns of the aquifer's cells. The other fields decide the mode as if the cache were not there.
        java.util.Map<Node, DensityFunction> cellCaches = classifySafe || roots == null ? null : StateDependence.cellCentreCaches(roots);
        if (cellCaches != null && cellCaches.isEmpty()) {
            cellCaches = null;
        }
        java.util.Set<Node> checked = cellCaches != null ? cellCaches.keySet() : java.util.Set.of();
        if (!classifySafe && cellCaches != null) {
            classifySafe = StateDependence.orderIndependent(roots, checked, AquiferFastPath.STATUS_FIELDS);
        }
        // the surface levels a status reads go through the chunk's memo. Once the memo holds every column a status needs,
        // computing the status reads only the point fields. A router whose order-sensitive state is in the surface level
        // function alone can therefore still have its neighbourhoods classified ahead, status by status.
        boolean columnsSafe = !classifySafe && roots != null && StateDependence.orderIndependent(roots, checked, AquiferFastPath.POINT_FIELDS);
        int mode = classifySafe ? AquiferFastPath.CLASSIFY : columnsSafe ? AquiferFastPath.COLUMNS : AquiferFastPath.SEARCH;
        boolean perChunk = mode != AquiferFastPath.SEARCH && cellCaches != null;
        AquiferFastPath.registerMode(this.aquiferRandom, mode, perChunk ? cellCaches.values().toArray(DensityFunction[]::new) : null);
        if (roots != null && (mode != AquiferFastPath.CLASSIFY || perChunk)) {
            Ga2Log.get("Aquifer").info(
                mode == AquiferFastPath.SEARCH ? "neighbourhoods searched per block (order dep: {})"
                    : perChunk ? "neighbourhoods classified "
                        + (mode == AquiferFastPath.COLUMNS ? "after surface levels" : "ahead")
                        + ", per-chunk column-sharing check (order dep: {})"
                    : "neighbourhoods classified after surface levels (order dep: {})",
                StateDependence.orderDependence(roots, AquiferFastPath.STATUS_FIELDS));
        }
        SurfaceLevelCache.register((RandomState) (Object) this,
            this.ga2$programs != null && StateDependence.chunkIndependent(this.ga2$programs.routerRoots(), "initial_density_without_jaggedness"));
    }

    @Override
    public ProgramSet ga2$programs() {
        return this.ga2$programs;
    }
}
