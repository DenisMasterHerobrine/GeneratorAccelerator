package dev.denismasterherobrine.generatoraccelerator2.dfc.runtime;

/**
 * Whole-cell knowledge the noise fill has beyond the density: whether the aquifer returns air for every block of a cell
 * whose density is non-positive everywhere (see {@code CellBounds} and {@code aquifer.CellAir}).
 */
public interface CellSkipper {
    /** True when every block of the cell {@code [x0, x0 + w) x [y0, y0 + h) x [z0, z0 + w)} with non-positive density is air. */
    boolean cellAir(int x0, int y0, int z0, int w, int h);
}
