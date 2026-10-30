package dev.denismasterherobrine.generatoraccelerator2.compat.lootr;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.CodeShape;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;

/** Lootr mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class LootrMixins {
    private static final Logger LOGGER = Ga2Log.get("Compat");

    private LootrMixins() {}

    public static final String MOD_ID = "lootr";
    /**
     * {@code PoiTypes.forState}: Lootr's barrel has the fisherman's POI type, like a vanilla barrel. GA2's per-state answer comes
     * from {@code forState} itself, Lootr's handler included, so the barrel keeps the call.
     */
    public static final String POI_TYPES = "noobanidus.mods.lootr.mixins.MixinPoiTypes";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.STATE_POI_SKIP, POI_TYPES, Set.of("LootrForState"),
            "Lootr barrels still work for fishermen. GA2 asks for each block's POI type with Lootr's code."));

    private static final String MIXINS = "dev.denismasterherobrine.generatoraccelerator2.compat.lootr.mixin.";
    /** The mixins that hand a converter class GA2's entry set ({@link LootrEntries}). Each applies only with {@link #tickerKnown}. */
    public static final Set<String> TICKER_MIXINS = Set.of(MIXINS + "TileTickerMixin", MIXINS + "FabricTileTickerMixin");
    /** The chunk events the entry set needs. Applied only when a converter class is known. */
    public static final Set<String> CHUNK_MIXINS = Set.of(MIXINS + "ChunkMapLootrStatusMixin", MIXINS + "ServerLevelLootrUnloadMixin");

    /**
     * Lootr's converters that keep one set of entries for the whole server: Lootr 0.7 on Forge and on Fabric. Each names the
     * converter class, its entry set, the methods that read the set and the class holding Lootr's loaded chunks.
     */
    private static final List<Ticker> TICKERS = List.of(
        new Ticker("noobanidus.mods.lootr.block.entities.TileTicker", "tileEntries", "addEntry", "serverTick",
            "noobanidus.mods.lootr.event.HandleChunk"),
        new Ticker("net.zestyblaze.lootr.block.entities.TileTicker", "tileEntries", "addEntry", "serverTick",
            "net.zestyblaze.lootr.event.HandleChunk"));

    private record Ticker(String owner, String field, String add, String tick, String loadedChunks) {}

    private static final Map<String, Boolean> KNOWN = new ConcurrentHashMap<>();

    /** True when {@code className} is a converter whose entry set GA2 can keep ({@link #check}). Logs what differs. */
    public static boolean tickerKnown(String className) {
        String name = className.replace('/', '.');
        for (Ticker t : TICKERS) {
            if (t.owner().equals(name)) {
                return KNOWN.computeIfAbsent(name, n -> check(t));
            }
        }
        return false;
    }

    /** True when the game has one of the converters and GA2 can keep its entry set. */
    public static boolean anyTickerKnown() {
        for (Ticker t : TICKERS) {
            if (tickerKnown(t.owner())) {
                return true;
            }
        }
        return false;
    }

    /** The classes that may hold Lootr's {@code LOADED_CHUNKS}, one per converter. A game has at most one of them. */
    public static List<String> loadedChunksHolders() {
        return TICKERS.stream().map(Ticker::loadedChunks).toList();
    }

    private static boolean check(Ticker t) {
        ClassNode node = CodeShape.classNode(t.owner());
        if (node == null) {
            return false; // the other loader's Lootr
        }
        String differs = differs(node, CodeShape.classNode(t.owner() + "$Entry"), t.field(), t.add(), t.tick());
        if (differs != null) {
            LOGGER.info("{}: unknown code ({}) -> Lootr's own entry list", t.owner(), differs);
            return false;
        }
        LOGGER.info("{}: entries kept by chunk", t.owner());
        return true;
    }

    /**
     * What keeps GA2's entry set off the converter {@code node}, or null when nothing does. The entry set must be a private static
     * {@code Set} that only the add and tick methods read. The tick must ask {@code hasChunk}, because GA2 hides entries whose
     * chunk fails that test. The entry class must give its dimension and its position, by which GA2 files each entry.
     */
    @Nullable
    static String differs(ClassNode node, @Nullable ClassNode entry, String field, String add, String tick) {
        FieldNode set = null;
        for (FieldNode f : node.fields) {
            if (f.name.equals(field)) {
                set = f;
            }
        }
        int privateStatic = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC;
        if (set == null || !set.desc.equals("Ljava/util/Set;") || (set.access & privateStatic) != privateStatic) {
            return "no private static Set " + field;
        }
        boolean added = false;
        boolean ticked = false;
        boolean asksChunk = false;
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof FieldInsnNode f && f.owner.equals(node.name) && f.name.equals(field)) {
                    if (m.name.equals("<clinit>") && f.getOpcode() == Opcodes.PUTSTATIC) {
                        continue;
                    }
                    if (f.getOpcode() != Opcodes.GETSTATIC || !(m.name.equals(add) || m.name.equals(tick))) {
                        return field + " used in " + m.name;
                    }
                    added |= m.name.equals(add);
                    ticked |= m.name.equals(tick);
                } else if (m.name.equals(tick) && insn instanceof MethodInsnNode call && call.desc.equals("(II)Z")
                    && Names.mojangMethod(call.name).equals("hasChunk")) {
                    asksChunk = true;
                }
            }
        }
        if (!added || !ticked || !asksChunk) {
            return "no " + (!added ? add : !ticked ? tick : "hasChunk test");
        }
        if (!returns(entry, "getDimension", "()Lnet/minecraft/resources/ResourceKey;")
            || !returns(entry, "getPosition", "()Lnet/minecraft/core/BlockPos;")) {
            return "entry without dimension and position";
        }
        return null;
    }

    private static boolean returns(@Nullable ClassNode node, String name, String mojangDesc) {
        MethodNode m = CodeShape.method(node, name, null);
        return m != null && (m.access & Opcodes.ACC_STATIC) == 0 && Names.mojangDesc(m.desc).equals(mojangDesc);
    }
}
