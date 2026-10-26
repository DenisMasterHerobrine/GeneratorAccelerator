package dev.denismasterherobrine.generatoraccelerator2.surface;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceRules.RuleSource;
import org.junit.jupiter.api.Test;

/**
 * {@link SurfaceBuilder#program}: a rule asked as a new sequence object each time (Citadel's merged rules) shares the program of
 * the first sequence of the same rule objects, so it is not lowered and compiled again for every chunk.
 */
class SurfaceShapeTest {
    private static final RuleSource FLOOR = SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, SurfaceRules.state(Blocks.GRASS_BLOCK.defaultBlockState()));
    private static final RuleSource STONE = SurfaceRules.state(Blocks.STONE.defaultBlockState());

    @Test
    void aNewSequenceOfTheSameRulesSharesOneProgram() {
        RuleSource first = SurfaceRules.sequence(FLOOR, STONE);
        RuleSource again = SurfaceRules.sequence(FLOOR, STONE);
        assertNotSame(first, again);
        assertSame(SurfaceBuilder.program(first), SurfaceBuilder.program(again));
    }

    @Test
    void nestedSequencesShareTooAndAnotherOrderDoesNot() {
        // a merge of an already merged rule, made anew both times
        assertSame(SurfaceBuilder.program(SurfaceRules.sequence(SurfaceRules.sequence(FLOOR, STONE), STONE)),
            SurfaceBuilder.program(SurfaceRules.sequence(SurfaceRules.sequence(FLOOR, STONE), STONE)));
        assertNotSame(SurfaceBuilder.program(SurfaceRules.sequence(FLOOR, STONE)), SurfaceBuilder.program(SurfaceRules.sequence(STONE, FLOOR)));
    }

    @Test
    void otherRuleObjectsGetAProgramOfTheirOwn() {
        // equal rules but other objects: identity decides below the sequences
        RuleSource floor2 = SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, SurfaceRules.state(Blocks.GRASS_BLOCK.defaultBlockState()));
        assertNotSame(SurfaceBuilder.program(SurfaceRules.sequence(FLOOR, STONE)), SurfaceBuilder.program(SurfaceRules.sequence(floor2, STONE)));
    }
}
