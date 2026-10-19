package dev.denismasterherobrine.generatoraccelerator2.mixin.state;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.platform.PlatformHooks;
import dev.denismasterherobrine.generatoraccelerator2.state.StateFlags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * {@code LevelChunkSection.setBlockState} that reads the counted properties of both states from their flags ({@link StateFlags}).
 * Vanilla asks each state, its block and its fluid state on every write. The counts change exactly as in vanilla, including
 * NeoForge's {@code isEmpty}. Every block write in worldgen and in game ends here. The body is put in place once the class is
 * complete, only when no other mod changed the method ({@code compat.LateOverwrites}). Otherwise vanilla's method runs with the
 * other mods' changes, and {@code state.sectionWrite} reads as changed.
 */
@Mixin(value = LevelChunkSection.class, priority = 5000)
public abstract class SectionWriteMixin {
    @Shadow
    public short nonEmptyBlockCount;
    @Shadow
    public short tickingBlockCount;
    @Shadow
    public short tickingFluidCount;
    @Shadow
    @Final
    private PalettedContainer<BlockState> states;

    @Unique
    private static final HookTargets.Hook GA2$HOOK = HookTargets.hook(HookTargets.SECTION_WRITE);

    /** The body of {@code setBlockState(x, y, z, state, useLocks)}. Reads counted properties from both states' flags. Same counts. */
    @Unique
    private BlockState ga2$setBlockState(int x, int y, int z, BlockState state, boolean useLocks) {
        BlockState old = useLocks ? this.states.getAndSet(x, y, z, state) : this.states.getAndSetUnchecked(x, y, z, state);
        if ((dev.denismasterherobrine.generatoraccelerator2.state.StateFast.ENABLED && GA2$HOOK.allowed())) {
            int fo = StateFlags.of(old);
            int fn = StateFlags.of(state);
            if ((fo & StateFlags.NON_EMPTY) != 0) {
                this.nonEmptyBlockCount--;
                if ((fo & StateFlags.TICKING) != 0) {
                    this.tickingBlockCount--;
                }
            }
            if ((fo & StateFlags.FLUID) != 0) {
                this.tickingFluidCount--;
            }
            if ((fn & StateFlags.NON_EMPTY) != 0) {
                this.nonEmptyBlockCount++;
                if ((fn & StateFlags.TICKING) != 0) {
                    this.tickingBlockCount++;
                }
            }
            if ((fn & StateFlags.FLUID) != 0) {
                this.tickingFluidCount++;
            }
            return old;
        }
        FluidState fluidOld = old.getFluidState();
        FluidState fluidNew = state.getFluidState();
        if (!PlatformHooks.isEmpty(old)) {
            this.nonEmptyBlockCount--;
            if (old.isRandomlyTicking()) {
                this.tickingBlockCount--;
            }
        }
        if (!fluidOld.isEmpty()) {
            this.tickingFluidCount--;
        }
        if (!PlatformHooks.isEmpty(state)) {
            this.nonEmptyBlockCount++;
            if (state.isRandomlyTicking()) {
                this.tickingBlockCount++;
            }
        }
        if (!fluidNew.isEmpty()) {
            this.tickingFluidCount++;
        }
        return old;
    }
}
