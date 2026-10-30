package dev.denismasterherobrine.generatoraccelerator2.compat.productivebees;

/**
 * Productive Bees' nest features ({@code SolitaryNestFeature} and its kin, {@code NestFeature} in newer versions). Each placement
 * asks {@code SolitaryNest.getSpawningRecipes}, which memoizes the matching bee spawning recipes per held item, nest block and
 * biome in a static {@code HashMap}. Nothing else touches the map, and nothing removes from it. A key gets the same list whichever
 * placement fills it. GA2 swaps in a concurrent map ({@code mixin.SolitaryNestRecipesMixin}), so the features no longer place one
 * at a time.
 */
public final class ProductiveBeesNests {
    private ProductiveBeesNests() {}

    public static final String MOD_ID = "productivebees";
    /** The memo, as the scan names fields. */
    public static final String RECIPES = "cy/jdkdigital/productivebees/common/block/SolitaryNest.recipes";
}
