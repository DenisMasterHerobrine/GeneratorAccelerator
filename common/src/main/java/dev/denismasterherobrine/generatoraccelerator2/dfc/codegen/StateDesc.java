package dev.denismasterherobrine.generatoraccelerator2.dfc.codegen;

import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.BlendedSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.NoiseSpec;

/**
 * Describes one per-instance column kernel state the generated code uses: which spec it belongs
 * to and which hoisted scratch slot records whether it has been prepared for the current column.
 */
public record StateDesc(boolean blended, int spec, int slot) {
    public static Object[] instantiate(StateDesc[] descs, NoiseSpec[] specs, BlendedSpec[] blended) {
        Object[] states = new Object[descs.length];
        for (int i = 0; i < descs.length; i++) {
            states[i] = descs[i].blended ? blended[descs[i].spec].newColumn() : specs[descs[i].spec].newColumn();
        }
        return states;
    }
}
