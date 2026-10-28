package dev.denismasterherobrine.generatoraccelerator2.beardifier;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;

/**
 * Block ranges outside of which a {@code Beardifier} piece or junction contributes exactly {@code +0.0}.
 *
 * <p>Derived from {@code Beardifier.compute}, with {@code dx}/{@code dz} the distance to the box on
 * that axis and {@code k1 = minY + groundLevelDelta}:
 * <ul>
 * <li>BURY: {@code clampedMap(length(dx, (y - k1) / 2, dz), 0, 6, 1, 0)} is 0 once the length reaches
 *     6, so it needs {@code dx, dz <= 5} and {@code |y - k1| <= 11}.</li>
 * <li>BEARD_THIN / BEARD_BOX and junctions: the beard kernel is 0 outside {@code [-12, 11]} on every
 *     axis. The vertical offset is {@code y - k1} (thin), the distance to {@code [k1, maxY]} (box) or
 *     {@code y - groundY} (junction).</li>
 * </ul>
 * The ranges below add one block of slack on each side. Skipping a {@code +0.0} term is exact
 * because the running sum starts at {@code +0.0} and every non-zero term is finite, so it never
 * holds {@code -0.0}.
 */
public final class BeardBounds {
    public static final int NONE = 0;
    public static final int BURY = 1;
    public static final int BEARD_THIN = 2;
    public static final int BEARD_BOX = 3;
    /** An adjustment this code does not know. The caller must not cull it. */
    public static final int UNKNOWN = -1;

    /** Coordinates beyond this make vanilla's int differences overflow-prone. Such inputs keep the vanilla path. */
    public static final int COORD_LIMIT = 1 << 29;

    private BeardBounds() {}

    public static int code(TerrainAdjustment adjustment) {
        if (adjustment == TerrainAdjustment.NONE) {
            return NONE;
        } else if (adjustment == TerrainAdjustment.BURY) {
            return BURY;
        } else if (adjustment == TerrainAdjustment.BEARD_THIN) {
            return BEARD_THIN;
        } else if (adjustment == TerrainAdjustment.BEARD_BOX) {
            return BEARD_BOX;
        }
        return UNKNOWN;
    }

    public static boolean sane(int v) {
        return v > -COORD_LIMIT && v < COORD_LIMIT;
    }

    /**
     * Writes {@code [x0, x1, y0, y1, z0, z1]} (inclusive) for a piece with a known, non-NONE
     * adjustment code into {@code out} at {@code at}.
     */
    public static void piece(BoundingBox box, int code, int groundLevelDelta, int[] out, int at) {
        long k1 = (long) box.minY() + groundLevelDelta;
        int xz = code == BURY ? 6 : 12;
        long y0;
        long y1;
        switch (code) {
            case BURY, BEARD_THIN -> {
                y0 = k1 - 12;
                y1 = k1 + 12;
            }
            case BEARD_BOX -> {
                y0 = k1 - 12;
                y1 = (long) box.maxY() + 12;
            }
            default -> throw new IllegalArgumentException("adjustment code " + code);
        }
        out[at] = clamp((long) box.minX() - xz);
        out[at + 1] = clamp((long) box.maxX() + xz);
        out[at + 2] = clamp(y0);
        out[at + 3] = clamp(y1);
        out[at + 4] = clamp((long) box.minZ() - xz);
        out[at + 5] = clamp((long) box.maxZ() + xz);
    }

    /** Same layout as {@link #piece} for a jigsaw junction. */
    public static void junction(JigsawJunction junction, int[] out, int at) {
        out[at] = clamp((long) junction.getSourceX() - 12);
        out[at + 1] = clamp((long) junction.getSourceX() + 12);
        out[at + 2] = clamp((long) junction.getSourceGroundY() - 12);
        out[at + 3] = clamp((long) junction.getSourceGroundY() + 12);
        out[at + 4] = clamp((long) junction.getSourceZ() - 12);
        out[at + 5] = clamp((long) junction.getSourceZ() + 12);
    }

    private static int clamp(long v) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
    }
}
