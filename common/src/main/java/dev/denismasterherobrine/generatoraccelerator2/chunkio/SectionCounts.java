package dev.denismasterherobrine.generatoraccelerator2.chunkio;

import dev.denismasterherobrine.generatoraccelerator2.state.StateFlags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * The four counters of a {@code LevelChunkSection}, as {@code setBlockState} changes them when it writes over air: non-empty
 * blocks, randomly ticking blocks, fluids and randomly ticking fluids. Fluids are counted in non-empty blocks only. For the
 * writers that fill a section's storage at once instead of block by block.
 */
public final class SectionCounts {
    public int nonEmpty;
    public int ticking;
    public int fluids;
    public int tickingFluids;

    /** Counts {@code count} blocks of {@code state}. */
    public void add(BlockState state, int count) {
        int f = StateFlags.of(state);
        if ((f & StateFlags.NON_EMPTY) == 0) {
            return;
        }
        nonEmpty += count;
        if ((f & StateFlags.TICKING) != 0) {
            ticking += count;
        }
        if ((f & StateFlags.FLUID) != 0) {
            fluids += count;
            if ((f & StateFlags.FLUID_TICKING) != 0) {
                tickingFluids += count;
            }
        }
    }

    public void clear() {
        nonEmpty = 0;
        ticking = 0;
        fluids = 0;
        tickingFluids = 0;
    }

    /** Adds these counts to the section's counters. */
    public void addTo(LevelChunkSection section) {
        section.nonEmptyBlockCount = (short) (section.nonEmptyBlockCount + nonEmpty);
        section.tickingBlockCount = (short) (section.tickingBlockCount + ticking);
        section.fluidCount = (short) (section.fluidCount + fluids);
        section.tickingFluidCount = (short) (section.tickingFluidCount + tickingFluids);
    }

    /** Sets the section's counters to these counts. */
    public void setOn(LevelChunkSection section) {
        section.nonEmptyBlockCount = (short) nonEmpty;
        section.tickingBlockCount = (short) ticking;
        section.fluidCount = (short) fluids;
        section.tickingFluidCount = (short) tickingFluids;
    }

    /** Whether the section's counters equal these counts. */
    public boolean matches(LevelChunkSection section) {
        return nonEmpty == section.nonEmptyBlockCount && ticking == section.tickingBlockCount && fluids == section.fluidCount
            && tickingFluids == section.tickingFluidCount;
    }

    /** A section's counters in the order {@link #toString} prints them. */
    public static String of(LevelChunkSection section) {
        return section.nonEmptyBlockCount + "/" + section.tickingBlockCount + "/" + section.fluidCount + "/" + section.tickingFluidCount;
    }

    /** {@code nonEmpty/ticking/fluids/tickingFluids}. */
    @Override
    public String toString() {
        return nonEmpty + "/" + ticking + "/" + fluids + "/" + tickingFluids;
    }
}
