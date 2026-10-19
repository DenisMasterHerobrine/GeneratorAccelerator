package dev.denismasterherobrine.generatoraccelerator2.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.denismasterherobrine.generatoraccelerator2.surface.compile.PositionalDraws;
import java.util.Random;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.Test;

/** The allocation-free first draws equal the vanilla random sources' first draws bit for bit. */
class PositionalDrawsTest {
    @Test
    void xoroshiroAndLegacyMatch() {
        Random r = new Random(7);
        PositionalRandomFactory[] factories = {
            new XoroshiroRandomSource(1234567L).forkPositional().fromHashOf("minecraft:deepslate").forkPositional(),
            new XoroshiroRandomSource(-42L).forkPositional(),
            new XoroshiroRandomSource(0L, 0L).forkPositional(),
            new LegacyRandomSource(1234567L).forkPositional(),
            new LegacyRandomSource(-99L).forkPositional() };
        for (PositionalRandomFactory f : factories) {
            PositionalDraws draws = new PositionalDraws(f);
            for (int i = 0; i < 20000; i++) {
                int x = r.nextInt(2_000_000) - 1_000_000;
                int y = r.nextInt(500) - 128;
                int z = r.nextInt(2_000_000) - 1_000_000;
                assertEquals(Float.floatToRawIntBits(f.at(x, y, z).nextFloat()), Float.floatToRawIntBits(draws.nextFloat(x, y, z)), f + " float at " + x + "," + y + "," + z);
                assertEquals(Double.doubleToRawLongBits(f.at(x, y, z).nextDouble()), Double.doubleToRawLongBits(draws.nextDouble(x, y, z)), f + " double at " + x + "," + y + "," + z);
            }
        }
    }
}
