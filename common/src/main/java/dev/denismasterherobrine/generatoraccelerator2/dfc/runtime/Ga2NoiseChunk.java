package dev.denismasterherobrine.generatoraccelerator2.dfc.runtime;

/** Duck interface added to {@code NoiseChunk} by mixin. */
public interface Ga2NoiseChunk {
    /** Mirrors {@code sliceFillingContextProvider.forIndex}: sets the corner y, zeroes inCellY, sets arrayIndex and bumps the interpolation counter. */
    void ga2$setSliceY(int blockY, int arrayIndex);

    /** Mirrors {@code NoiseChunk.forIndex} / {@code fillAllDirectly}: sets the in-cell offsets and the array index. */
    void ga2$setCell(int inCellX, int inCellY, int inCellZ, int arrayIndex);

    /** Adds to {@code interpolationCounter} so counter-keyed caches see the same monotonic progression as vanilla. */
    void ga2$advanceInterpolationCounter(long by);

    ChunkBinding ga2$binding();

    /** The z fraction of the last {@code updateForZ} (see {@link LazyInterpolation}). */
    double ga2$dz();

    /** The class of the cell the last {@code selectCellYZ} selected: {@code CellBounds.MIXED}, {@code SKY} or {@code SOLID}. */
    int ga2$cellClass();

    void ga2$setCellClass(int cellClass);

    /** Fills the current cell's per-block values after all. Used when the cell was classified but one of its blocks needs a density. */
    void ga2$fillCurrentCell();

    /** The compiled surface level walk bound to this chunk, or null ({@link SurfaceLevels}). */
    SurfaceLevels ga2$levelProgram();
    /**
     * Drops the state only a fill uses (the bound programs, the router memo, the interpolators' slices), as
     * {@code stopInterpolation} does. Used for a NoiseChunk made again for a later step, such as carving a chunk loaded
     * before that step. Such a NoiseChunk never stops interpolating.
     */
    void ga2$releaseFill();
}
