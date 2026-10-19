package dev.denismasterherobrine.generatoraccelerator2.state;

/**
 * Switch for {@code mixin.state.PaletteCopyMixin}. The mixin resizes a container with one palette lookup per distinct old
 * id. {@code -Dgeneratoraccelerator2.paletteCopy=false} turns it off. The field is not final so the exactness test can
 * compare both ways.
 */
public final class PaletteCopy {
    private PaletteCopy() {}

    public static boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.paletteCopy"));
}
