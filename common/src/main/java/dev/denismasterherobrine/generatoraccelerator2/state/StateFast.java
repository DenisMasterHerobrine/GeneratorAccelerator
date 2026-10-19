package dev.denismasterherobrine.generatoraccelerator2.state;

/**
 * {@code -Dgeneratoraccelerator2.stateFast=false}: the state-level shortcuts ({@link TagBits}, {@link StateFlags}, the palettes'
 * remembered ids, remembered fluid emptiness and full-block shapes) answer with vanilla's code, for comparisons.
 */
public final class StateFast {
    private StateFast() {}

    public static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.stateFast"));
}
