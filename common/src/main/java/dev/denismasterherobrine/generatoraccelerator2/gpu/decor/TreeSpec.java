package dev.denismasterherobrine.generatoraccelerator2.gpu.decor;

import dev.denismasterherobrine.generatoraccelerator2.features.FeaturesCompat;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.AlterGroundDecoratorAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.BendingTrunkPlacerAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.BlobFoliagePlacerAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.CherryFoliagePlacerAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.CherryTrunkPlacerAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.CocoaDecoratorAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.FoliagePlacerAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.LeaveVineDecoratorAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.MegaJungleFoliagePlacerAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.MegaPineFoliagePlacerAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.PineFoliagePlacerAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.RandomSpreadFoliagePlacerAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.SpruceFoliagePlacerAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.ThreeLayersFeatureSizeAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.TrunkPlacerAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.TwoLayersFeatureSizeAccessor;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.featuresize.FeatureSize;
import net.minecraft.world.level.levelgen.feature.featuresize.ThreeLayersFeatureSize;
import net.minecraft.world.level.levelgen.feature.featuresize.TwoLayersFeatureSize;
import net.minecraft.world.level.levelgen.feature.foliageplacers.AcaciaFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.foliageplacers.BlobFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.foliageplacers.BushFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.foliageplacers.CherryFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.foliageplacers.DarkOakFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.foliageplacers.FancyFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer;
import net.minecraft.world.level.levelgen.feature.foliageplacers.MegaJungleFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.foliageplacers.MegaPineFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.foliageplacers.PineFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.foliageplacers.RandomSpreadFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.foliageplacers.SpruceFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.treedecorators.AlterGroundDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.CocoaDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.LeaveVineDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.TrunkVineDecorator;
import net.minecraft.world.level.levelgen.feature.trunkplacers.BendingTrunkPlacer;
import net.minecraft.world.level.levelgen.feature.trunkplacers.CherryTrunkPlacer;
import net.minecraft.world.level.levelgen.feature.trunkplacers.DarkOakTrunkPlacer;
import net.minecraft.world.level.levelgen.feature.trunkplacers.ForkingTrunkPlacer;
import net.minecraft.world.level.levelgen.feature.trunkplacers.GiantTrunkPlacer;
import net.minecraft.world.level.levelgen.feature.trunkplacers.MegaJungleTrunkPlacer;
import net.minecraft.world.level.levelgen.feature.trunkplacers.StraightTrunkPlacer;
import net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer;

/**
 * Vanilla's {@code TreeFeature} as GPU data ({@link DecorSpec#K_TREE}): the trunk placer, the foliage placer, the feature size, the three
 * block state providers and the decorators, for the vanilla placer classes {@link TreeKernel} transcribes. Root placers, the
 * attached-to-leaves decorator and any other class keep the tree on the CPU. The beehive decorator is supported, but a tree that
 * rolls a nest goes to the CPU, because the nest spot comes from Java's shared static Random.
 *
 * <p>Parameters: 0 trunk type, 1-3 base height and the two random heights, 4-8 the trunk's own values, 9 foliage type, 10 radius, 11
 * offset (int providers in {@code aux}), 12-16 the foliage's own values, 17 feature size kind (2 or 3 layers), 18 limit, 19 upper limit,
 * 20 lower, 21 middle, 22 upper size, 23 the minimum clipped height (-1: none), 24 trunk, 25 foliage, 26 dirt providers, 27 force dirt,
 * 28 ignore vines, 29 the vine and cocoa states in {@code aux}, 30 decorator count, then (type, argument) per decorator.
 */
public final class TreeSpec {
    private TreeSpec() {}

    static final int T_STRAIGHT = 1, T_FORKING = 2, T_GIANT = 3, T_MEGA_JUNGLE = 4, T_DARK_OAK = 5, T_BENDING = 6, T_CHERRY = 7, T_FANCY = 8;
    static final int F_BLOB = 1, F_SPRUCE = 2, F_PINE = 3, F_ACACIA = 4, F_BUSH = 5, F_FANCY = 6, F_MEGA_JUNGLE = 7, F_MEGA_PINE = 8, F_DARK_OAK = 9,
        F_RANDOM_SPREAD = 10, F_CHERRY = 11;
    static final int D_TRUNK_VINE = 1, D_LEAVE_VINE = 2, D_COCOA = 3, D_ALTER_GROUND = 4, D_BEEHIVE = 5;

    /** Appends the tree's parameters. Returns null when the GPU places the tree as vanilla does, else the reason it cannot. */
    static String build(DecorSpec s, TreeConfiguration c, IntArrayList p) {
        if (!FeaturesCompat.treesAllowed()) {
            return "changed by another mod";
        }
        if (c.rootPlacer.isPresent()) {
            return "root placer " + c.rootPlacer.get().getClass().getSimpleName();
        }
        TrunkPlacer tp = c.trunkPlacer;
        Class<?> tc = tp.getClass();
        int trunk = tc == StraightTrunkPlacer.class ? T_STRAIGHT : tc == ForkingTrunkPlacer.class ? T_FORKING : tc == GiantTrunkPlacer.class ? T_GIANT
            : tc == MegaJungleTrunkPlacer.class ? T_MEGA_JUNGLE : tc == DarkOakTrunkPlacer.class ? T_DARK_OAK : tc == BendingTrunkPlacer.class ? T_BENDING
            : tc == CherryTrunkPlacer.class ? T_CHERRY : tc == net.minecraft.world.level.levelgen.feature.trunkplacers.FancyTrunkPlacer.class ? T_FANCY : -1;
        if (trunk < 0) {
            return "trunk placer " + tc.getSimpleName();
        }
        TrunkPlacerAccessor ta = (TrunkPlacerAccessor) tp;
        int[] te = new int[5];
        if (trunk == T_BENDING) {
            BendingTrunkPlacerAccessor b = (BendingTrunkPlacerAccessor) tp;
            te[0] = b.ga2$minLeaves();
            te[1] = s.intProvider(b.ga2$bendLength());
            if (te[1] < 0) {
                return "bend length";
            }
        } else if (trunk == T_CHERRY) {
            CherryTrunkPlacerAccessor ch = (CherryTrunkPlacerAccessor) tp;
            te[0] = s.intProvider(ch.ga2$branchCount());
            te[1] = s.intProvider(ch.ga2$branchLength());
            te[2] = s.intProvider(ch.ga2$branchStart());
            te[3] = s.intProvider(ch.ga2$secondBranchStart());
            te[4] = s.intProvider(ch.ga2$branchEnd());
            for (int v : te) {
                if (v < 0) {
                    return "cherry branch values";
                }
            }
        }
        p.add(trunk);
        p.add(ta.ga2$base());
        p.add(ta.ga2$randA());
        p.add(ta.ga2$randB());
        p.addElements(p.size(), te);

        FoliagePlacer fp = c.foliagePlacer;
        Class<?> fc = fp.getClass();
        int fol = fc == BlobFoliagePlacer.class ? F_BLOB : fc == SpruceFoliagePlacer.class ? F_SPRUCE : fc == PineFoliagePlacer.class ? F_PINE
            : fc == AcaciaFoliagePlacer.class ? F_ACACIA : fc == BushFoliagePlacer.class ? F_BUSH : fc == FancyFoliagePlacer.class ? F_FANCY
            : fc == MegaJungleFoliagePlacer.class ? F_MEGA_JUNGLE : fc == MegaPineFoliagePlacer.class ? F_MEGA_PINE : fc == DarkOakFoliagePlacer.class ? F_DARK_OAK
            : fc == RandomSpreadFoliagePlacer.class ? F_RANDOM_SPREAD : fc == CherryFoliagePlacer.class ? F_CHERRY : -1;
        if (fol < 0) {
            return "foliage placer " + fc.getSimpleName();
        }
        FoliagePlacerAccessor fa = (FoliagePlacerAccessor) fp;
        int radius = s.intProvider(fa.ga2$radius()), offset = s.intProvider(fa.ga2$offset());
        if (radius < 0 || offset < 0) {
            return "foliage radius or offset";
        }
        int[] fe = new int[5];
        switch (fol) {
            case F_BLOB, F_BUSH, F_FANCY -> fe[0] = ((BlobFoliagePlacerAccessor) fp).ga2$height();
            case F_MEGA_JUNGLE -> fe[0] = ((MegaJungleFoliagePlacerAccessor) fp).ga2$height();
            case F_SPRUCE -> fe[0] = s.intProvider(((SpruceFoliagePlacerAccessor) fp).ga2$trunkHeight());
            case F_PINE -> fe[0] = s.intProvider(((PineFoliagePlacerAccessor) fp).ga2$height());
            case F_MEGA_PINE -> fe[0] = s.intProvider(((MegaPineFoliagePlacerAccessor) fp).ga2$crownHeight());
            case F_RANDOM_SPREAD -> {
                fe[0] = s.intProvider(((RandomSpreadFoliagePlacerAccessor) fp).ga2$height());
                fe[1] = ((RandomSpreadFoliagePlacerAccessor) fp).ga2$attempts();
            }
            case F_CHERRY -> {
                CherryFoliagePlacerAccessor ch = (CherryFoliagePlacerAccessor) fp;
                fe[0] = s.intProvider(ch.ga2$height());
                fe[1] = Float.floatToRawIntBits(ch.ga2$wideHole());
                fe[2] = Float.floatToRawIntBits(ch.ga2$cornerHole());
                fe[3] = Float.floatToRawIntBits(ch.ga2$hanging());
                fe[4] = Float.floatToRawIntBits(ch.ga2$hangingExtension());
            }
            default -> {
            }
        }
        if ((fol == F_SPRUCE || fol == F_PINE || fol == F_MEGA_PINE || fol == F_RANDOM_SPREAD || fol == F_CHERRY) && fe[0] < 0) {
            return "foliage height";
        }
        p.add(fol);
        p.add(radius);
        p.add(offset);
        p.addElements(p.size(), fe);

        FeatureSize fs = c.minimumSize;
        if (fs.getClass() == TwoLayersFeatureSize.class) {
            TwoLayersFeatureSizeAccessor a = (TwoLayersFeatureSizeAccessor) fs;
            p.addElements(p.size(), new int[] { 2, a.ga2$limit(), 0, a.ga2$lower(), 0, a.ga2$upper() });
        } else if (fs.getClass() == ThreeLayersFeatureSize.class) {
            ThreeLayersFeatureSizeAccessor a = (ThreeLayersFeatureSizeAccessor) fs;
            p.addElements(p.size(), new int[] { 3, a.ga2$limit(), a.ga2$upperLimit(), a.ga2$lower(), a.ga2$middle(), a.ga2$upper() });
        } else {
            return "feature size " + fs.getClass().getSimpleName();
        }
        p.add(fs.minClippedHeight().isPresent() ? fs.minClippedHeight().getAsInt() : -1);

        int trunkProvider = s.stateProvider(c.trunkProvider, DecorSpec.REC_AXIS);
        int foliageProvider = s.stateProvider(c.foliageProvider, DecorSpec.REC_LEAF);
        int dirtProvider = s.stateProvider(c.dirtProvider, DecorSpec.REC_PLAIN);
        if (trunkProvider < 0 || foliageProvider < 0 || dirtProvider < 0) {
            return "block state provider";
        }
        p.add(trunkProvider);
        p.add(foliageProvider);
        p.add(dirtProvider);
        p.add(c.forceDirt ? 1 : 0);
        p.add(c.ignoreVines ? 1 : 0);
        p.add(constants(s));
        p.add(c.decorators.size());
        for (TreeDecorator d : c.decorators) {
            Class<?> dc = d.getClass();
            if (dc == TrunkVineDecorator.class) {
                p.add(D_TRUNK_VINE);
                p.add(0);
            } else if (dc == LeaveVineDecorator.class) {
                p.add(D_LEAVE_VINE);
                p.add(Float.floatToRawIntBits(((LeaveVineDecoratorAccessor) d).ga2$probability()));
            } else if (dc == CocoaDecorator.class) {
                p.add(D_COCOA);
                p.add(Float.floatToRawIntBits(((CocoaDecoratorAccessor) d).ga2$probability()));
            } else if (dc == AlterGroundDecorator.class) {
                int ground = s.stateProvider(((AlterGroundDecoratorAccessor) d).ga2$provider(), DecorSpec.REC_PLAIN);
                if (ground < 0) {
                    return "alter ground provider";
                }
                p.add(D_ALTER_GROUND);
                p.add(ground);
            } else if (dc == net.minecraft.world.level.levelgen.feature.treedecorators.BeehiveDecorator.class) {
                // the nest spot comes from Java's shared static Random (Collections.shuffle). A tree that rolls a nest goes to the CPU.
                p.add(D_BEEHIVE);
                p.add(Float.floatToRawIntBits(((dev.denismasterherobrine.generatoraccelerator2.mixin.gpu.BeehiveDecoratorAccessor) d).ga2$probability()));
            } else {
                return "decorator " + dc.getSimpleName();
            }
        }
        leaves(s);
        return null;
    }

    /** Per spec (one per world and dimension). Weak, so a closed world's spec does not stay for the rest of the session. */
    private static final java.util.Map<DecorSpec, Integer> CONSTANTS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** The vine states (face east, west, south, north set) and cocoa states (age 0-2, facing north, east, south, west) in {@code aux}. */
    private static int constants(DecorSpec s) {
        return CONSTANTS.computeIfAbsent(s, k -> {
            int at = s.auxList.size();
            BlockState vine = Blocks.VINE.defaultBlockState();
            s.auxList.add(s.ids.id(vine.setValue(VineBlock.EAST, true)));
            s.auxList.add(s.ids.id(vine.setValue(VineBlock.WEST, true)));
            s.auxList.add(s.ids.id(vine.setValue(VineBlock.SOUTH, true)));
            s.auxList.add(s.ids.id(vine.setValue(VineBlock.NORTH, true)));
            Direction[] faces = { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };
            for (int age = 0; age < 3; age++) {
                for (Direction d : faces) {
                    s.auxList.add(s.ids.id(Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.AGE, age).setValue(CocoaBlock.FACING, d)));
                }
            }
            return at;
        });
    }

    /** Every state of every block with a leaf distance gets its id now (updateLeaves writes their other distances). */
    private static void leaves(DecorSpec s) {
        if (s.leavesRegistered) {
            return;
        }
        s.leavesRegistered = true;
        for (Block b : BuiltInRegistries.BLOCK) {
            if (b.getStateDefinition().getProperties().contains(BlockStateProperties.DISTANCE)) {
                for (BlockState st : b.getStateDefinition().getPossibleStates()) {
                    s.ids.id(st);
                }
            }
        }
    }

    private static final ConcurrentHashMap<Class<?>, Integer> SHAPE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Boolean> TREE_GROW = new ConcurrentHashMap<>();

    /**
     * What {@code updateShape} does to a block of this class ({@code StructureTemplate.updateShapeAtEdge} at a tree's edge). The class
     * that declares the method decides. Vanilla's default, leaves and fluids return the state (they only schedule ticks). Grass-like
     * blocks set their snowy property from the block above. Anything else is {@link Preds#UPS_OTHER} and keeps the tree on the CPU.
     */
    static int shapeKind(Class<?> cls) {
        return SHAPE.computeIfAbsent(cls, c -> {
            try {
                for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                    for (Method m : k.getDeclaredMethods()) {
                        if (Names.mojangMethod(m.getName()).equals("updateShape") && m.getParameterCount() == 6) {
                            if (k == BlockBehaviour.class || k == LeavesBlock.class || k == LiquidBlock.class) {
                                return Preds.UPS_SAME;
                            }
                            return k == SnowyDirtBlock.class ? Preds.UPS_SNOWY : Preds.UPS_OTHER;
                        }
                    }
                }
            } catch (LinkageError e) {
                return Preds.UPS_OTHER; // a signature names a class this side lacks (a client class on a server). The CPU handles it.
            }
            return Preds.UPS_SAME;
        });
    }

    /** Whether a block class overrides NeoForge's {@code onTreeGrow} (vanilla's soils keep the default, which places dirt). */
    static boolean overridesTreeGrow(Class<?> cls) {
        return TREE_GROW.computeIfAbsent(cls, c -> {
            try {
                for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                    for (Method m : k.getDeclaredMethods()) {
                        if (m.getName().equals("onTreeGrow")) {
                            return true;
                        }
                    }
                }
            } catch (LinkageError e) {
                return true; // unreadable (a client class in a signature on a server). Treated as an override.
            }
            return false;
        });
    }
}
