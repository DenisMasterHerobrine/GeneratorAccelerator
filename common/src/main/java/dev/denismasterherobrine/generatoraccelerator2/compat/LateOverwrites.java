package dev.denismasterherobrine.generatoraccelerator2.compat;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.slf4j.Logger;

/**
 * GA2's replacements of whole vanilla methods, put in place once every mixin of a class is applied, and only where no other
 * mod changed the method.
 *
 * <p>An {@code @Overwrite} would not work. Mixin merges every overwrite before it prepares any injector. Another mod's injector
 * that points inside a method merged by a mixin of the same or higher priority fails the class with an
 * {@code InvalidInjectionException} ("cannot inject into ... merged by"), and the failed class takes the game down. So each
 * replacement is a separate method in GA2's mixin ({@code ga2$<name>}, the same descriptor), merged next to vanilla's method.
 * The class is complete after MixinExtras' late injectors ({@link LateInspection}). The plugin then inspects vanilla's method.
 * When neither another mod nor a coremod changed vanilla's method, the plugin copies the replacement's body into it. A method
 * that another mod changed keeps vanilla's body with those changes, and the hook reads as changed. GA2's own injections into
 * such a method also target the replacement, so the copied body carries them.
 *
 * <p>Loaded by the mixin plugin, so it must not reference Minecraft types.
 */
public final class LateOverwrites {
    private static final Logger LOGGER = Ga2Log.get("Compat");

    private LateOverwrites() {}

    private static final String BLOCK_STATE = "Lnet/minecraft/world/level/block/state/BlockState;";

    /** Per GA2 mixin (simple name): the vanilla methods it replaces (name and descriptor). The replacement is {@code ga2$<name>}. */
    private static final Map<String, List<String>> METHODS = Map.ofEntries(
        Map.entry("XoroshiroReseedMixin", List.of("setSeed(J)V")),
        Map.entry("SectionWriteMixin", List.of("setBlockState(III" + BLOCK_STATE + "Z)" + BLOCK_STATE)),
        Map.entry("ProtoChunkReadsInspectMixin", List.of("getBlockState(Lnet/minecraft/core/BlockPos;)" + BLOCK_STATE,
            "getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;")),
        Map.entry("FluidStateEmptyMixin", List.of("isEmpty()Z")),
        Map.entry("ChunkHeightsMixin", List.of("getMinBuildHeight()I", "getHeight()I")),
        Map.entry("FullBlockShapeMixin", List.of("isShapeFullBlock(Lnet/minecraft/world/phys/shapes/VoxelShape;)Z")),
        Map.entry("HolderTagsMixin", List.of("is(Lnet/minecraft/tags/TagKey;)Z")),
        Map.entry("LinearPaletteMixin", List.of("idFor(Ljava/lang/Object;)I")),
        Map.entry("HashMapPaletteMixin", List.of("idFor(Ljava/lang/Object;)I")),
        Map.entry("ImprovedNoiseMixin", List.of("noise(DDD)D", "noise(DDDDD)D")),
        Map.entry("PerlinNoiseMixin", List.of("getValue(DDD)D", "getValue(DDDDDZ)D", "wrap(D)D")),
        Map.entry("BlendedNoiseMixin", List.of("compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D")),
        Map.entry("NoiseChunkSurfaceMixin", List.of("preliminarySurfaceLevel(II)I")),
        Map.entry("SplineMultipointMapMixin", List.of("mapAll(Lnet/minecraft/util/CubicSpline$CoordinateVisitor;)Lnet/minecraft/util/CubicSpline;")),
        Map.entry("BiomeSourceLookupMixin", List.of("getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;")),
        Map.entry("RandomSpreadPlacementMixin", List.of("getPotentialStructureChunk(JII)Lnet/minecraft/world/level/ChunkPos;")),
        Map.entry("StructureReferencesMixin", List.of("createReferences(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/StructureManager;"
            + "Lnet/minecraft/world/level/chunk/ChunkAccess;)V")),
        Map.entry("StructurePlacementRandomsMixin", List.of("probabilityReducer(JIIIF)Z", "legacyProbabilityReducerWithDouble(JIIIF)Z",
            "legacyArbitrarySaltProbabilityReducer(JIIIF)Z", "legacyPillagerOutpostReducer(JIIIF)Z")),
        Map.entry("RuleProcessorRandomMixin", List.of("processBlock(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate$StructureBlockInfo;"
            + "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate$StructureBlockInfo;"
            + "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;)"
            + "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate$StructureBlockInfo;")));

    /** The vanilla methods the mixin replaces (empty when it replaces none). */
    public static List<String> methods(String mixinSimpleName) {
        return METHODS.getOrDefault(mixinSimpleName, List.of());
    }

    /**
     * Copies the replacement of each of {@code methods} into vanilla's method. Call only for methods no other mod changed.
     *
     * @return the methods whose replacement was missing (left as they are)
     */
    public static List<String> install(ClassNode target, List<String> methods) {
        List<String> missing = new ArrayList<>();
        String owner = Names.mojangClass(target.name);
        for (String m : methods) {
            int paren = m.indexOf('(');
            // the table names vanilla's methods by their Mojang names. The class has the running game's names. The replacement keeps
            // its own name, and the replacement's descriptor is renamed with the class
            String name = m.substring(0, paren), desc = Names.runtimeDesc(m.substring(paren));
            MethodNode vanilla = find(target, Names.runtimeMethod(owner, name, m.substring(paren)), desc), fast = find(target, "ga2$" + name, desc);
            if (vanilla == null || fast == null || (vanilla.access & 0x0008) != (fast.access & 0x0008)) {
                missing.add(m);
                continue;
            }
            copyBody(fast, vanilla);
        }
        if (!missing.isEmpty()) {
            LOGGER.warn("{}: no replacement for {} -> vanilla methods kept", owner, missing);
        }
        return missing;
    }

    private static MethodNode find(ClassNode c, String name, String desc) {
        for (MethodNode m : c.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) {
                return m;
            }
        }
        return null;
    }

    /** {@code to}'s code becomes a copy of {@code from}'s. Both have the same descriptor and class, so locals and frames carry over. */
    private static void copyBody(MethodNode from, MethodNode to) {
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode i = from.instructions.getFirst(); i != null; i = i.getNext()) {
            if (i instanceof LabelNode l) {
                labels.put(l, new LabelNode());
            }
        }
        InsnList code = new InsnList();
        for (AbstractInsnNode i = from.instructions.getFirst(); i != null; i = i.getNext()) {
            code.add(i.clone(labels));
        }
        to.instructions.clear();
        to.instructions.add(code);
        List<TryCatchBlockNode> handlers = new ArrayList<>();
        if (from.tryCatchBlocks != null) {
            for (TryCatchBlockNode t : from.tryCatchBlocks) {
                handlers.add(new TryCatchBlockNode(labels.get(t.start), labels.get(t.end), labels.get(t.handler), t.type));
            }
        }
        to.tryCatchBlocks = handlers;
        if (from.localVariables != null) {
            List<LocalVariableNode> locals = new ArrayList<>();
            for (LocalVariableNode v : from.localVariables) {
                locals.add(new LocalVariableNode(v.name, v.desc, v.signature, labels.get(v.start), labels.get(v.end), v.index));
            }
            to.localVariables = locals;
        } else {
            to.localVariables = null;
        }
        // annotations on the old body's locals pointed into it
        to.visibleLocalVariableAnnotations = null;
        to.invisibleLocalVariableAnnotations = null;
        to.maxStack = from.maxStack;
        to.maxLocals = from.maxLocals;
    }
}
