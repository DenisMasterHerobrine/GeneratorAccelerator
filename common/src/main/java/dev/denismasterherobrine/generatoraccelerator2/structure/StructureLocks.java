package dev.denismasterherobrine.generatoraccelerator2.structure;

import com.mojang.math.OctahedralGroup;
import net.minecraft.core.Direction;

/**
 * Monitors for the vanilla structure code that is not safe to run on several threads at once.
 * Parallel structure starts and parallel decoration would otherwise run it that way.
 *
 * <ul>
 * <li>Strongholds, nether fortresses and end cities lay out their pieces through static state (piece
 * weights and counts, the pending piece, the "ship placed" flag). Each layout resets that state first.
 * Running whole layouts one at a time therefore gives exactly the layout vanilla's single worldgen thread
 * gives.</li>
 * <li>A structure start is shared by every chunk it overlaps. Placing the start mutates its pieces (height
 * fixes, chest and spawner flags, template bounding boxes). Each placement holds the start's monitor.</li>
 * <li>Static block selectors (jungle temple stone, stronghold bricks) keep the block chosen for the current
 * position in a field. A box fill holds the selector's monitor.</li>
 * </ul>
 * No monitor here is taken while another is held, except a selector inside a start, so they cannot deadlock.
 */
public final class StructureLocks {
    private StructureLocks() {}

    public static final Object STRONGHOLD = new Object();
    public static final Object NETHER_FORTRESS = new Object();
    public static final Object END_CITY = new Object();

    /**
     * Fills the lazily built direction maps of {@code OctahedralGroup}. Vanilla publishes those maps before
     * filling them, so a racing reader gets a null direction. Called once at mod construction, before any
     * worldgen thread runs.
     */
    public static void prewarm() {
        for (OctahedralGroup group : OctahedralGroup.values()) {
            for (Direction direction : Direction.values()) {
                group.rotate(direction);
            }
        }
    }
}
