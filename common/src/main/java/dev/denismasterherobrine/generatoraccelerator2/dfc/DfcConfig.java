package dev.denismasterherobrine.generatoraccelerator2.dfc;

import dev.denismasterherobrine.generatoraccelerator2.config.ConfigSpec;

/**
 * Common config for the density function compiler. Values are read through safe getters because
 * {@code RandomState}s can be built before the config file is loaded in some contexts (unit tests).
 * The {@code generatoraccelerator2.dfc.strict} system property forces both fail-fast flags on.
 */
public final class DfcConfig {
    private DfcConfig() {}

    public static final ConfigSpec SPEC;
    public static final ConfigSpec.BooleanValue COLUMN_PROGRAMS;
    public static final ConfigSpec.BooleanValue CELL_PROGRAMS;
    public static final ConfigSpec.BooleanValue WHOLE_CELLS;
    public static final ConfigSpec.BooleanValue GRID2D_PROGRAMS;
    public static final ConfigSpec.BooleanValue POINT_PROGRAMS;
    public static final ConfigSpec.BooleanValue BORDER_COLUMN_CACHE;
    public static final ConfigSpec.BooleanValue BYTECODE_LIFTING;
    public static final ConfigSpec.BooleanValue FAIL_ON_UNSUPPORTED;
    public static final ConfigSpec.BooleanValue FAIL_ON_CODEGEN_ERROR;
    public static final ConfigSpec.BooleanValue SHADOW_VERIFY;
    public static final ConfigSpec.BooleanValue AQUIFER_FAST_PATH;
    public static final ConfigSpec.BooleanValue SHARED_SURFACE_CACHE;
    public static final ConfigSpec.BooleanValue BEARDIFIER_INDEX;

    static {
        ConfigSpec.Builder b = new ConfigSpec.Builder();
        b.push("densityFunctionCompiler");
        COLUMN_PROGRAMS = b.comment("Compile interpolated density functions into whole-column programs.").define("columnPrograms", true);
        CELL_PROGRAMS = b.comment("Compile the per-block final density fill into whole-cell programs.").define("cellPrograms", true);
        WHOLE_CELLS = b.comment("Let the noise fill handle a cell whose density is bounded on one side of zero as a whole (all air, all solid) instead of per block.",
            "Output is identical to vanilla.").define("wholeCells", true);
        GRID2D_PROGRAMS = b.comment("Compile flat_cache functions into 2D grid programs.").define("grid2dPrograms", true);
        POINT_PROGRAMS = b.comment("Compile the climate sampler and single-point router fields.").define("pointPrograms", true);
        BORDER_COLUMN_CACHE = b.comment("Share cell-corner columns between neighbouring chunks (only when no old-chunk blending is active).").define("borderColumnCache", true);
        BYTECODE_LIFTING = b.comment("Try to compile modded density functions by lifting their bytecode (always validated bit for bit).").define("bytecodeLifting", true);
        FAIL_ON_UNSUPPORTED = b.comment("Crash at world load if any density function could not be compiled (development aid).").define("failOnUnsupported", false);
        FAIL_ON_CODEGEN_ERROR = b.comment("Crash at world load if code generation fails instead of using the reference interpreter (development aid).").define("failOnCodegenError", false);
        SHADOW_VERIFY = b.comment("Also run the vanilla path for every compiled program and compare results bit for bit (very slow).").define("shadowVerify", false);
        b.pop();
        b.push("aquifer");
        AQUIFER_FAST_PATH = b.comment("Skip the nearest-aquifer search where its outcome is already decided (open sky, open water) and use a branch-free",
            "search elsewhere. Output is identical to vanilla; turns itself off when another mod patches the aquifer.").define("fastPath", true);
        SHARED_SURFACE_CACHE = b.comment("Share preliminary surface levels (sampled by aquifers around every chunk) between neighbouring chunks.",
            "Only used without old-chunk blending and when the density function cannot differ between chunks; identical output.").define("sharedSurfaceCache", true);
        b.pop();
        b.push("beardifier");
        BEARDIFIER_INDEX = b.comment("Evaluate only the structure pieces and jigsaw junctions that can reach the current block when terrain is shaped",
            "around structures. Output is identical to vanilla; turns itself off when another mod patches the beardifier.").define("pieceIndex", true);
        b.pop();
        SPEC = b.build();
    }

    private static boolean strictProperty() {
        return Boolean.getBoolean("generatoraccelerator2.dfc.strict");
    }

    private static boolean get(ConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return SPEC.isLoaded() ? value.get() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static boolean columnPrograms() { return get(COLUMN_PROGRAMS, true); }
    public static boolean cellPrograms() { return get(CELL_PROGRAMS, true); }
    public static boolean wholeCells() { return get(WHOLE_CELLS, true); }
    public static boolean grid2dPrograms() { return get(GRID2D_PROGRAMS, true); }
    public static boolean pointPrograms() { return get(POINT_PROGRAMS, true); }
    public static boolean borderColumnCache() { return get(BORDER_COLUMN_CACHE, true); }
    public static boolean bytecodeLifting() { return get(BYTECODE_LIFTING, true); }
    public static boolean failOnUnsupported() { return strictProperty() || get(FAIL_ON_UNSUPPORTED, false); }
    public static boolean failOnCodegenError() { return strictProperty() || get(FAIL_ON_CODEGEN_ERROR, false); }
    public static boolean aquiferFastPath() { return get(AQUIFER_FAST_PATH, true); }
    public static boolean beardifierIndex() { return get(BEARDIFIER_INDEX, true); }
    public static boolean sharedSurfaceCache() { return get(SHARED_SURFACE_CACHE, true); }
    public static boolean shadowVerify() { return Boolean.getBoolean("generatoraccelerator2.dfc.verify") || get(SHADOW_VERIFY, false); }
}
