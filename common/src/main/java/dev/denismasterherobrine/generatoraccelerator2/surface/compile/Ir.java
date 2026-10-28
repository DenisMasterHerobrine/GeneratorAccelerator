package dev.denismasterherobrine.generatoraccelerator2.surface.compile;

import java.util.List;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Surface rule IR. Rules return a block state or {@code null} like {@code SurfaceRule.tryApply}.
 * Conditions are boolean. Every vanilla condition is a pure function of the evaluation state except
 * {@link Steep}. Steep reads the live {@code WORLD_SURFACE_WG} heightmap and is therefore evaluated
 * lazily, once per column, at its first test (as vanilla's shared {@code SteepMaterialCondition}).
 * Rule and condition types this compiler does not know become {@link OpaqueRule} / {@link OpaqueCond}
 * and run through vanilla objects built from the original source.
 *
 * <p>Conditions carry an id. Equal ids mean the same value at any evaluation point (equal sources of
 * a pure condition). This lets evaluation share one cached value per block or column.
 */
public final class Ir {
    private Ir() {}

    public sealed interface Rule permits Seq, Test, State, Bands, OpaqueRule, ExternRule {}

    public record Seq(List<Rule> rules) implements Rule {}

    public record Test(Cond cond, Rule then) implements Rule {}

    public record State(BlockState state) implements Rule {}

    /** {@code SurfaceSystem.getBand(x, y, z)} (badlands clay bands). */
    public record Bands() implements Rule {}

    /** A vanilla {@code SurfaceRule} built per chunk from an unknown rule source. {@code index} points into the program's opaque list. */
    public record OpaqueRule(int index) implements Rule {}

    /** Another mod's rule evaluated by compat code ({@link SurfaceExtern.Rule}). {@code index} points into the program's extern rules. */
    public record ExternRule(int index, boolean positional) implements Rule {}

    /** Scope of a condition's value: how long a computed value stays valid. */
    public enum Scope {
        /** Depends on the block (y, counters, biome). */
        BLOCK,
        /** Depends on the column only. */
        COLUMN
    }

    public sealed interface Cond permits Biome, Noise, Gradient, YCheck, Water, StoneDepth, Steep, Slope, Hole, AboveSurface, Temperature, Not, All, Any,
        OpaqueCond, ExternCond, Const {
        int id();
    }

    /** {@code test(context.biome.get())}. {@code slot} points into the program's biome predicates. */
    public record Biome(int id, int slot) implements Cond {}

    /** {@code min <= noise(x, 0, z) <= max}. {@code slot} points into the program's noise keys. */
    public record Noise(int id, int slot, double min, double max) implements Cond {}

    /** Vertical gradient. {@code slot} points into the program's gradients (random factory name and two anchors). */
    public record Gradient(int id, int slot) implements Cond {}

    /**
     * {@code blockY + (addStone ? stoneDepthAbove : 0) >= anchor + surfaceDepth * mult}. {@code anchor} points into the program's
     * anchors.
     */
    public record YCheck(int id, int anchor, int mult, boolean addStone) implements Cond {}

    /** {@code waterHeight == MIN || blockY + (addStone ? stoneDepthAbove : 0) >= waterHeight + offset + surfaceDepth * mult}. */
    public record Water(int id, int offset, int mult, boolean addStone) implements Cond {}

    /** {@code (ceiling ? stoneDepthBelow : stoneDepthAbove) <= 1 + offset + (addSurface ? surfaceDepth : 0) + secondary term}. */
    public record StoneDepth(int id, int offset, boolean addSurface, int secondaryRange, boolean ceiling) implements Cond {}

    public record Steep(int id) implements Cond {}

    /**
     * Lithostitched's slope: the spread of the neighbouring columns' surface heights within {@code [min, max]}. Like
     * {@link Steep}, it reads the live heightmap. Each use has an id of its own, since each use is its own lazy object there.
     */
    public record Slope(int id, int min, int max) implements Cond {}

    /** {@code surfaceDepth <= 0}. */
    public record Hole(int id) implements Cond {}

    /** {@code blockY >= minSurfaceLevel}. */
    public record AboveSurface(int id) implements Cond {}

    /** {@code biome.coldEnoughToSnow(pos)}. */
    public record Temperature(int id) implements Cond {}

    public record Not(int id, Cond target) implements Cond {}

    /** True when every condition is, asked in order up to the first false one (a library's all-of condition). */
    public record All(int id, List<Cond> conds) implements Cond {}

    /** True when some condition is, asked in order up to the first true one (a library's any-of condition). */
    public record Any(int id, List<Cond> conds) implements Cond {}

    /** A vanilla {@code Condition} built per chunk from an unknown condition source. {@code index} points into the program's opaque list. */
    public record OpaqueCond(int id, int index) implements Cond {}

    /** A condition already answered (a biome condition in a program made for one biome, {@link BiomeFold}). */
    public record Const(int id, boolean value) implements Cond {}

    /**
     * Another mod's condition evaluated by compat code ({@link SurfaceExtern.Cond}). {@code index} points into the program's
     * extern conditions.
     */
    public record ExternCond(int id, int index, Scope scope, boolean positional) implements Cond {}

    public static Scope scope(Cond c) {
        if (c instanceof Noise n) {
            return Scope.COLUMN;
        } else if (c instanceof Steep s) {
            return Scope.COLUMN;
        } else if (c instanceof Slope s) {
            return Scope.COLUMN;
        } else if (c instanceof Hole h) {
            return Scope.COLUMN;
        } else if (c instanceof ExternCond e) {
            return e.scope();
        } else if (c instanceof Not n) {
            return scope(n.target());
        } else {
            return Scope.BLOCK;
        }
    }
}
