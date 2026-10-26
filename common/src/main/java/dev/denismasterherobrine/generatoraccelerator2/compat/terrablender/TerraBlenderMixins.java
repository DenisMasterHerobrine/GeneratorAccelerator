package dev.denismasterherobrine.generatoraccelerator2.compat.terrablender;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * TerraBlender picks biomes per region. TerraBlender's mixin adds {@code findValuePositional} to vanilla's climate
 * parameter list. That method searches the region's own climate tree with vanilla's
 * {@code RTree.search(target, Node::distance)} instead of the list's {@code findValue}. GA2's exact flat search replaces
 * {@code findValue}'s search, so it never ran there. GA2's mixin
 * ({@code compat.terrablender.mixin.ParameterListRegionSearchMixin}) runs those searches through the flat search. Before
 * that mixin is applied, the plugin checks here that every tree search in the method uses vanilla's distance
 * ({@code Node::distance}). That is the search the flat tree reproduces. Otherwise TerraBlender's searches stay as they are.
 *
 * <p>Read by the mixin plugin, so it must not reference Minecraft types.
 */
public final class TerraBlenderMixins {
    private TerraBlenderMixins() {}

    public static final String MOD_ID = "terrablender";
    public static final String REGION_SEARCH_MIXIN = "dev.denismasterherobrine.generatoraccelerator2.compat.terrablender.mixin.ParameterListRegionSearchMixin";
    /** TerraBlender's {@code getNoiseBiome}: the region's tree instead of the list's ({@code TerraBlenderBiomes}). */
    public static final String BIOME_SOURCE = "terrablender.mixin.MixinMultiNoiseBiomeSource";
    /** TerraBlender's biome step: a copy of the biome source with a fresh region noise cache for every chunk. */
    public static final String BIOME_STEP = "terrablender.mixin.MixinNoiseBasedChunkGenerator";

    /**
     * The GPU searches each quart's region tree as TerraBlender does ({@code TerraBlenderBiomes}). The regions come from
     * TerraBlender's own noise. The copy per chunk only renews the noise's cache, so the GPU's answers stay the same.
     */
    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.GPU_BIOMES, BIOME_SOURCE, Set.of("getNoiseBiome"),
            "TerraBlender's biome regions still work. The GPU places each region's biomes the same way."),
        new KnownMixins.Entry(MOD_ID, HookTargets.GPU_BIOMES, BIOME_STEP, Set.of("modifyBiomeSource"),
            "TerraBlender's biome regions still work. The GPU uses TerraBlender's own region map."),
        // its lookup searches the region's tree. The regions come from its noise of the world's seed
        new KnownMixins.Entry(MOD_ID, HookTargets.BIOME_ANSWERS, BIOME_SOURCE, Set.of("getNoiseBiome"),
            "TerraBlender's biome regions still work. A biome that other mods ask for outside the chunks being decorated is looked "
                + "up once with TerraBlender's code and kept."));
    private static final String RTREE = "net/minecraft/world/level/biome/Climate$RTree";
    private static final String NODE = "net/minecraft/world/level/biome/Climate$RTree$Node";

    private static volatile boolean distanceSearch;

    /** {@code -Dgeneratoraccelerator2.verify.biomes=N}: every N-th region search is also walked the vanilla way and compared. */
    private static final int VERIFY_EVERY = Integer.getInteger("generatoraccelerator2.verify.biomes", 0);
    private static final AtomicLong SEEN = new AtomicLong();
    public static final AtomicLong VERIFIED = new AtomicLong();
    public static final AtomicLong DIFFERING = new AtomicLong();

    public static boolean sampleVerify() {
        return VERIFY_EVERY > 0 && SEEN.incrementAndGet() % VERIFY_EVERY == 0;
    }

    public static void verified(boolean same, Object target) {
        VERIFIED.incrementAndGet();
        if (!same && DIFFERING.incrementAndGet() <= 10) {
            Ga2Log.get("Verify").warn("TerraBlender region search mismatch vs vanilla at {}", target);
        }
    }

    /** True when TerraBlender's region searches were checked to use vanilla's distance. */
    public static boolean distanceSearch() {
        return distanceSearch;
    }

    /** Called with the parameter list class just before GA2's mixin applies. TerraBlender's method is merged by then. */
    public static void check(ClassNode parameterList) {
        int searches = 0;
        boolean all = true;
        for (MethodNode m : parameterList.methods) {
            if (!m.name.equals("findValuePositional")) {
                continue;
            }
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call && Names.mojangClass(call.owner).equals(RTREE) && Names.mojangMethod(call.name).equals("search")) {
                    searches++;
                    all &= isNodeDistance(previous(insn));
                }
            }
        }
        distanceSearch = searches > 0 && all;
        if (!distanceSearch) {
            Ga2Log.get("Compat").info(
                "TerraBlender region climate search: unknown shape ({} searches) -> own tree walk", searches);
        }
    }

    /** The instruction before {@code insn}, skipping labels, line numbers and frames. */
    private static AbstractInsnNode previous(AbstractInsnNode insn) {
        AbstractInsnNode p = insn.getPrevious();
        while (p != null && p.getOpcode() < 0) {
            p = p.getPrevious();
        }
        return p;
    }

    /** The metric argument: a method reference to {@code Climate.RTree.Node.distance(long[])}. */
    private static boolean isNodeDistance(AbstractInsnNode insn) {
        if (!(insn instanceof InvokeDynamicInsnNode indy) || indy.bsmArgs.length < 2 || !(indy.bsmArgs[1] instanceof Handle impl)) {
            return false;
        }
        return impl.getTag() == Opcodes.H_INVOKEVIRTUAL && Names.mojangClass(impl.getOwner()).equals(NODE) && Names.mojangMethod(impl.getName()).equals("distance")
            && impl.getDesc().equals("([J)J");
    }
}
