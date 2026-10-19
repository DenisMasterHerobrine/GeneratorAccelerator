package dev.denismasterherobrine.generatoraccelerator2.aquifer;

/** Duck interface added to {@code Aquifer.NoiseBasedAquifer} by mixin. */
public interface Ga2Aquifer {
    /**
     * True when every block of the cell {@code [x0, x0 + w) x [y0, y0 + h) x [z0, z0 + w)} whose density is non-positive
     * is air. The answer must not require the aquifer to compute anything vanilla would not. No block may be lava of the
     * global picker.
     * Each neighbourhood the cell touches must be classified, with every fluid level at or below {@code y0}. For a mixed
     * neighbourhood the air line counts as its fluid level. False whenever that is not known.
     */
    boolean ga2$cellAir(int x0, int y0, int z0, int w, int h);

    /**
     * {@code computeSubstance(new SinglePointContext(x, y, z), density)}. The context is made only when the block's search
     * samples the barrier noise. GA2's carvers ask at every carved block.
     */
    net.minecraft.world.level.block.state.BlockState ga2$substanceAt(int x, int y, int z, double density);

    /**
     * {@code computeSubstance(context, density)} for the noise fill. Calls the fast path directly instead of through its hook,
     * which made a callback object per block. The fast path only runs when no other mod changes the aquifer's methods. The
     * hook is then the only code before vanilla's body.
     */
    net.minecraft.world.level.block.state.BlockState ga2$substance(net.minecraft.world.level.levelgen.DensityFunction.FunctionContext context, double density);

    /** {@code shouldScheduleFluidUpdate()} without the hook's callback object. Resolves the pending flag as the hook does. */
    boolean ga2$scheduleFlag();

    /** Whether GA2's fast path answers this aquifer, i.e. no other mod changes its methods. */
    boolean ga2$fast();
}
