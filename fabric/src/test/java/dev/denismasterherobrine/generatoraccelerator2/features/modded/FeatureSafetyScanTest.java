package dev.denismasterherobrine.generatoraccelerator2.features.modded;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.junit.jupiter.api.Test;

/**
 * {@link FeatureSafetyScan} on a feature's own fields: a feature is one object for every placement, so its fields written or
 * filled during a placement are shared (Blueprint's trees keep their log positions that way). Fields set in the constructor and
 * state that lives in each placement's own objects are not.
 */
class FeatureSafetyScanTest {
    /** Resets and fills its own position set per placement, as Blueprint's tree feature does. */
    static class PositionsFeature extends Feature<NoneFeatureConfiguration> {
        public Set<BlockPos> logs = new HashSet<>();

        PositionsFeature() {
            super(NoneFeatureConfiguration.CODEC);
        }

        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
            this.logs = new HashSet<>();
            addLog(context.origin());
            return true;
        }

        void addLog(BlockPos pos) {
            logs.add(pos);
        }
    }

    /** Only fills the set it was made with: a change of the shared object's collection. */
    static final class FillsOwnSet extends Feature<NoneFeatureConfiguration> {
        private final Set<BlockPos> seen = new HashSet<>();

        FillsOwnSet() {
            super(NoneFeatureConfiguration.CODEC);
        }

        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
            return seen.add(context.origin());
        }
    }

    /** A subclass that writes nothing itself: its modded superclass's placement still shares the set. */
    static final class InheritsPositions extends PositionsFeature {}

    /** Config fixed in the constructor and a set made for each placement. */
    static final class PerPlacement extends Feature<NoneFeatureConfiguration> {
        private final int size;

        PerPlacement() {
            super(NoneFeatureConfiguration.CODEC);
            this.size = 3;
        }

        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
            Set<BlockPos> logs = new HashSet<>();
            for (int i = 0; i < size; i++) {
                logs.add(context.origin().above(i));
            }
            return !logs.isEmpty();
        }
    }

    @Test
    void ownFieldsWrittenInPlacementsAreShared() {
        assertNotNull(FeatureSafetyScan.sharedState(PositionsFeature.class));
        assertNotNull(FeatureSafetyScan.sharedState(FillsOwnSet.class));
        assertNotNull(FeatureSafetyScan.sharedState(InheritsPositions.class));
    }

    @Test
    void constructorFieldsAndPerPlacementObjectsAreNot() {
        assertNull(FeatureSafetyScan.sharedState(PerPlacement.class));
    }

    /** Reads its set before it assigns a new one: the last placement's positions leak into the next. */
    static final class ReadsFirst extends Feature<NoneFeatureConfiguration> {
        public Set<BlockPos> logs = new HashSet<>();

        ReadsFirst() {
            super(NoneFeatureConfiguration.CODEC);
        }

        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
            boolean had = !logs.isEmpty();
            this.logs = new HashSet<>();
            logs.add(context.origin());
            return had;
        }
    }

    /**
     * {@link FeatureSafetyScan#perPlacementState}: only fields assigned anew at the start of every placement make a feature safe to
     * place on per-thread copies ({@link FeatureCopies}).
     */
    @Test
    void perPlacementFieldsAllowCopies() {
        assertTrue(FeatureSafetyScan.perPlacementState(PositionsFeature.class), "fields assigned first");
        assertTrue(FeatureSafetyScan.perPlacementState(InheritsPositions.class), "a modded superclass's placement");
        assertFalse(FeatureSafetyScan.perPlacementState(FillsOwnSet.class), "a set kept across placements");
        assertFalse(FeatureSafetyScan.perPlacementState(ReadsFirst.class), "a set read before it is assigned");
    }

    @Test
    void copiesAreSeparateObjectsWithTheSameFields() {
        PositionsFeature feature = new PositionsFeature();
        Object copy = FeatureCopies.forThread(feature);
        assertNotNull(copy);
        assertNotSame(feature, copy);
        assertSame(PositionsFeature.class, copy.getClass());
        assertSame(feature.logs, ((PositionsFeature) copy).logs, "a copy starts with the object's fields");
        assertSame(copy, FeatureCopies.forThread(copy), "a copy is its own copy");
        assertSame(copy, FeatureCopies.forThread(feature), "one copy per thread");
    }
}
