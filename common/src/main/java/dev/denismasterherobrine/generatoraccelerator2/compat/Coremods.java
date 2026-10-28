package dev.denismasterherobrine.generatoraccelerator2.compat;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import dev.denismasterherobrine.generatoraccelerator2.platform.Platform;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;

/**
 * Changes other mods make to vanilla methods without Mixin. A coremod (a Forge {@code ITransformer} or JS coremod) rewrites a class
 * before Mixin applies anything and leaves no merged method behind, so the mixin inspection cannot see the change. For
 * example, Twilight Forest adds the density of its structures at the end of {@code Beardifier.compute}. It also adds its
 * canopy and glacier blankets right after the generator call of the SURFACE step. GA2's hooks take over both places.
 *
 * <p>The mixin plugins hand each class that a GA2 mixin targets to {@link #inspect}, before any mixin is applied to the
 * class. The class is as the transformers left it. Every method is compared with the game jar's own bytes, ignoring
 * labels, line numbers and frames. Forge's own coremods are applied to the jar's copy first, so the platform's changes
 * are not counted. Those coremods replace field reads with getters and redirect {@code finalizeSpawn}. Calls to a class's
 * own methods compare equal through either invoke opcode, because access transformers that open a private method switch
 * the opcode. Any other difference counts as a change by a foreign "mixin", for every hook that inspects the method. The
 * "mixin" is named after the class that the inserted code calls ({@code "coremod <owner>"}). Its merged names are
 * {@code "coremod$<method>"} for the methods the inserted code calls. A {@link KnownMixins} entry can handle
 * such a change like any mixin. A change that calls no other mod's code is named {@code "coremod (unknown)"} and is never
 * handled.
 *
 * <p>Loaded by the mixin plugin, so it must not reference Minecraft types.
 */
public final class Coremods {
    private Coremods() {}

    private static final Logger LOGGER = Ga2Log.get("Compat");
    public static final String UNKNOWN = "coremod (unknown)";

    /** A method some coremod changed: the "mixin" name it counts as and the merged names it has. */
    public record Change(String mixin, List<String> merged) {}

    /** Internal class name to method (name + descriptor) to the method's change. Classes looked at without changes map to an empty map. */
    private static final Map<String, Map<String, Change>> CHANGES = new ConcurrentHashMap<>();

    /** Forge's own coremods are only in Forge's game (unit tests read the vanilla jar). */
    private static final boolean FORGE = Platform.isForge();

    /** Forge's {@code field_to_method} coremod, for the worldgen classes: class to field to getter, by Mojang names. */
    private static final Map<String, Map<String, String>> FORGE_GETTERS = Map.of(
        "net/minecraft/world/level/biome/Biome", Map.of("climateSettings", "getModifiedClimateSettings", "specialEffects", "getModifiedSpecialEffects"),
        "net/minecraft/world/level/levelgen/structure/Structure", Map.of("settings", "getModifiedStructureSettings"),
        "net/minecraft/world/level/block/FlowerPotBlock", Map.of("potted", "getPotted"));

    /** Forge's {@code method_redirector} coremod: the classes whose {@code finalizeSpawn} calls go to {@code ForgeEventFactory.onFinalizeSpawn}. */
    private static final Set<String> FORGE_FINALIZE_SPAWN = Set.of(
        "net/minecraft/server/commands/RaidCommand", "net/minecraft/server/commands/SummonCommand", "net/minecraft/world/entity/EntityType",
        "net/minecraft/world/entity/ai/village/VillageSiege", "net/minecraft/world/entity/animal/frog/Tadpole",
        "net/minecraft/world/entity/animal/horse/SkeletonTrapGoal", "net/minecraft/world/entity/monster/Evoker$EvokerSummonSpellGoal",
        "net/minecraft/world/entity/monster/Spider", "net/minecraft/world/entity/monster/Strider", "net/minecraft/world/entity/monster/Zombie",
        "net/minecraft/world/entity/monster/ZombieVillager", "net/minecraft/world/entity/npc/CatSpawner", "net/minecraft/world/entity/npc/Villager",
        "net/minecraft/world/entity/raid/Raid", "net/minecraft/world/level/NaturalSpawner", "net/minecraft/world/level/levelgen/PatrolSpawner",
        "net/minecraft/world/level/levelgen/PhantomSpawner",
        "net/minecraft/world/level/levelgen/structure/structures/OceanMonumentPieces$OceanMonumentPiece",
        "net/minecraft/world/level/levelgen/structure/structures/OceanRuinPieces$OceanRuinPiece",
        "net/minecraft/world/level/levelgen/structure/structures/SwampHutPiece",
        "net/minecraft/world/level/levelgen/structure/structures/WoodlandMansionPieces$WoodlandMansionPiece",
        "net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate");
    private static final String FINALIZE_SPAWN_DESC = "(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/DifficultyInstance;"
        + "Lnet/minecraft/world/entity/MobSpawnType;Lnet/minecraft/world/entity/SpawnGroupData;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/entity/SpawnGroupData;";
    /** {@code Mob.finalizeSpawn} as the running game names it. */
    private static final String FINALIZE_SPAWN = Names.runtimeMethod("net/minecraft/world/entity/Mob", "finalizeSpawn", FINALIZE_SPAWN_DESC);

    /** Packages whose calls are the game's or the platform's own (never another mod's coremod). */
    private static final String[] OWN_OWNERS = { "net/minecraft/", "com/mojang/", "java/", "javax/", "jdk/", "sun/", "it/unimi/dsi/",
        "com/google/", "org/apache/", "net/minecraftforge/", "cpw/mods/", "net/fabricmc/", "org/slf4j/", "org/joml/" };

    /**
     * Compares {@code current} (the class as the transformers left it, before any mixin) with the jar's bytes, once per
     * class. Classes whose bytes cannot be read are skipped (logged once).
     */
    public static void inspect(String className, ClassNode current) {
        String internal = className.replace('.', '/');
        if (CHANGES.containsKey(internal)) {
            return;
        }
        Map<String, Change> changed = new HashMap<>();
        try {
            ClassNode original = original(internal);
            if (original == null) {
                LOGGER.debug("{}: no jar bytes -> coremod check skipped", className);
            } else {
                Map<String, MethodNode> byKey = new HashMap<>();
                for (MethodNode m : original.methods) {
                    byKey.put(m.name + m.desc, m);
                }
                for (MethodNode m : current.methods) {
                    MethodNode o = byKey.get(m.name + m.desc);
                    if (o == null) {
                        continue; // a method some transformer added. Nothing of vanilla's to compare, and no hook inspects it
                    }
                    Change c = compare(internal, o, m);
                    if (c != null) {
                        // keyed by Mojang name, as the hooks ask ({@link Names})
                        changed.put(Names.mojangMethodKey(m.name, m.desc), c);
                    }
                }
            }
        } catch (RuntimeException | IOException e) {
            LOGGER.warn("{}: jar bytes compare failed -> coremod check skipped", className, e);
        }
        if (CHANGES.putIfAbsent(internal, Map.copyOf(changed)) == null && !changed.isEmpty()) {
            for (Map.Entry<String, Change> e : changed.entrySet()) {
                LOGGER.info("{}.{} modified outside Mixin by {} (calls {})", className, e.getKey(), e.getValue().mixin(),
                    e.getValue().merged());
            }
        }
    }

    /** The changes coremods made to {@code method} (name + descriptor, or a bare name for every method of that name) of the class. Empty when none. */
    public static List<Change> changes(String internalName, String method) {
        Map<String, Change> m = CHANGES.get(internalName);
        if (m == null || m.isEmpty()) {
            return List.of();
        }
        List<Change> out = new ArrayList<>();
        for (Map.Entry<String, Change> e : m.entrySet()) {
            String key = e.getKey();
            if (key.equals(method) || key.startsWith(method + "(")) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    /**
     * {@code changes} plus what coremods did to {@code methods} of the class, added as separate changes, one per merged name.
     * {@code FeatureTargets}/{@code HookTargets} then match them against {@link KnownMixins} like a mixin's handlers.
     */
    public static List<MergedMethods.Change> with(String internalName, List<String> methods, List<MergedMethods.Change> changes) {
        List<MergedMethods.Change> out = null;
        for (String method : methods) {
            for (Change c : changes(internalName, method)) {
                if (out == null) {
                    out = new ArrayList<>(changes);
                }
                for (String name : c.merged()) {
                    out.add(new MergedMethods.Change(c.mixin(), method, name + "()V", -1));
                }
            }
        }
        return out == null ? changes : out;
    }

    /** {@code mixins} plus the coremods that changed {@code methods} of the class, by their "mixin" names. */
    public static List<String> withNames(String internalName, List<String> methods, List<String> mixins) {
        List<String> out = null;
        for (String method : methods) {
            for (Change c : changes(internalName, method)) {
                if (out == null) {
                    out = new ArrayList<>(mixins);
                }
                if (!out.contains(c.mixin())) {
                    out.add(c.mixin());
                }
            }
        }
        return out == null ? mixins : out;
    }

    /** Whether {@link #inspect} looked at the class. */
    public static boolean inspected(String internalName) {
        return CHANGES.containsKey(internalName);
    }

    private static ClassNode original(String internal) throws IOException {
        byte[] bytes = null;
        for (ClassLoader loader : new ClassLoader[] { Coremods.class.getClassLoader(), Thread.currentThread().getContextClassLoader() }) {
            if (loader == null) {
                continue;
            }
            try (InputStream in = loader.getResourceAsStream(internal + ".class")) {
                if (in != null) {
                    bytes = in.readAllBytes();
                    break;
                }
            }
        }
        if (bytes == null) {
            return null;
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
        if (FORGE) {
            applyForge(node);
        }
        return node;
    }

    /** Forge's own coremods, applied to the jar's copy the way Forge applies them to the loaded class. */
    static void applyForge(ClassNode node) {
        Map<String, String> getters = FORGE_GETTERS.get(node.name);
        if (getters != null) {
            for (Map.Entry<String, String> g : getters.entrySet()) {
                String field = Names.runtimeField(node.name, g.getKey());
                String fieldDesc = null;
                for (FieldNode f : node.fields) {
                    if (f.name.equals(field)) {
                        fieldDesc = f.desc;
                    }
                }
                if (fieldDesc == null) {
                    continue;
                }
                String getterDesc = "()" + fieldDesc;
                String getter = Names.runtimeMethod(node.name, g.getValue(), Names.mojangDesc(getterDesc));
                for (MethodNode m : node.methods) {
                    if (m.name.equals(getter) && m.desc.equals(getterDesc) || m.desc.equals(getterDesc)) {
                        continue; // Forge skips the getter and every method with its descriptor
                    }
                    for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (insn.getOpcode() == Opcodes.GETFIELD && ((FieldInsnNode) insn).name.equals(field)) {
                            MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, getter, getterDesc, false);
                            m.instructions.set(insn, call);
                            insn = call;
                        }
                    }
                }
            }
        }
        if (FORGE_FINALIZE_SPAWN.contains(node.name)) {
            String desc = Names.runtimeDesc(FINALIZE_SPAWN_DESC);
            for (MethodNode m : node.methods) {
                for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL && insn instanceof MethodInsnNode call && call.name.equals(FINALIZE_SPAWN)
                        && call.desc.equals(desc)) {
                        MethodInsnNode redirect = new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraftforge/event/ForgeEventFactory", "onFinalizeSpawn",
                            "(Lnet/minecraft/world/entity/Mob;" + desc.substring(1), false);
                        m.instructions.set(insn, redirect);
                        insn = redirect;
                    }
                }
            }
        }
    }

    /** The change from {@code original} to {@code current}, or null when they are the same code. */
    static Change compare(String owner, MethodNode original, MethodNode current) {
        List<String> a = normalize(owner, original);
        List<String> b = normalize(owner, current);
        if (a.equals(b)) {
            return null;
        }
        // the calls and field accesses the transformed method has more of than the jar's, by owner
        Map<String, Integer> count = new HashMap<>();
        for (String s : a) {
            if (s.startsWith("M ") || s.startsWith("F ")) {
                count.merge(s, 1, Integer::sum);
            }
        }
        String foreignOwner = null;
        Set<String> merged = new LinkedHashSet<>();
        Map<LabelNode, Integer> currentLabels = labelOrdinals(current);
        for (AbstractInsnNode insn = current.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            String s = describe(owner, insn, currentLabels);
            if (s == null || !(s.startsWith("M ") || s.startsWith("F "))) {
                continue;
            }
            Integer n = count.get(s);
            if (n != null && n > 0) {
                count.put(s, n - 1);
                continue;
            }
            String refOwner = insn instanceof MethodInsnNode mi ? mi.owner : ((FieldInsnNode) insn).owner;
            String refName = insn instanceof MethodInsnNode mi ? mi.name : ((FieldInsnNode) insn).name;
            if (refOwner.equals(owner) || isOwn(refOwner)) {
                continue; // a field of the class (added by the coremod) or a vanilla call. No other mod is named here
            }
            if (foreignOwner == null) {
                foreignOwner = refOwner;
            }
            if (insn instanceof MethodInsnNode) {
                merged.add("coremod$" + refName);
            }
        }
        if (foreignOwner == null) {
            return new Change(UNKNOWN, List.of("coremod$unknown"));
        }
        return new Change("coremod " + foreignOwner, List.copyOf(merged.isEmpty() ? Set.of("coremod$unknown") : merged));
    }

    private static boolean isOwn(String owner) {
        for (String p : OWN_OWNERS) {
            if (owner.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> normalize(String owner, MethodNode m) {
        List<String> out = new ArrayList<>();
        Map<LabelNode, Integer> labels = labelOrdinals(m);
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            String s = describe(owner, insn, labels);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    /** One instruction as text (null for labels, line numbers and frames). Jump targets appear as label ordinals. */
    private static String describe(String owner, AbstractInsnNode insn, Map<LabelNode, Integer> labels) {
        if (insn instanceof LabelNode || insn instanceof LineNumberNode || insn instanceof FrameNode) {
            return null;
        }
        int op = insn.getOpcode();
        if (insn instanceof MethodInsnNode mi) {
            // INVOKESPECIAL and INVOKEVIRTUAL of the class's own method compare equal (an access transformer switches them)
            String kind = mi.owner.equals(owner) && !mi.name.equals("<init>") && (op == Opcodes.INVOKESPECIAL || op == Opcodes.INVOKEVIRTUAL) ? "own" : Integer.toString(op);
            return "M " + kind + " " + mi.owner + "." + mi.name + mi.desc;
        }
        if (insn instanceof FieldInsnNode fi) {
            return "F " + op + " " + fi.owner + "." + fi.name + ":" + fi.desc;
        }
        if (insn instanceof VarInsnNode vi) {
            return op + " v" + vi.var;
        }
        if (insn instanceof IntInsnNode ii) {
            return op + " i" + ii.operand;
        }
        if (insn instanceof LdcInsnNode ldc) {
            Object c = ldc.cst;
            return op + " c" + (c instanceof Type t ? "T" + t.getDescriptor() : c.getClass().getSimpleName() + ":" + c);
        }
        if (insn instanceof TypeInsnNode ti) {
            return op + " t" + ti.desc;
        }
        if (insn instanceof JumpInsnNode ji) {
            return op + " L" + labels.getOrDefault(ji.label, -1);
        }
        if (insn instanceof IincInsnNode ii) {
            return op + " v" + ii.var + "+" + ii.incr;
        }
        if (insn instanceof TableSwitchInsnNode ts) {
            StringBuilder sb = new StringBuilder().append(op).append(" ").append(ts.min).append("..").append(ts.max).append(" d").append(labels.getOrDefault(ts.dflt, -1));
            for (LabelNode l : ts.labels) {
                sb.append(",").append(labels.getOrDefault(l, -1));
            }
            return sb.toString();
        }
        if (insn instanceof LookupSwitchInsnNode ls) {
            StringBuilder sb = new StringBuilder().append(op).append(" d").append(labels.getOrDefault(ls.dflt, -1));
            for (int i = 0; i < ls.keys.size(); i++) {
                sb.append(",").append(ls.keys.get(i)).append("->").append(labels.getOrDefault(ls.labels.get(i), -1));
            }
            return sb.toString();
        }
        if (insn instanceof MultiANewArrayInsnNode ma) {
            return op + " " + ma.desc + "x" + ma.dims;
        }
        if (insn instanceof InvokeDynamicInsnNode indy) {
            StringBuilder sb = new StringBuilder().append(op).append(" ").append(indy.name).append(indy.desc).append(" ").append(handle(indy.bsm));
            for (Object arg : indy.bsmArgs) {
                sb.append(",").append(arg instanceof Handle h ? handle(h) : arg instanceof Type t ? t.getDescriptor() : String.valueOf(arg));
            }
            return sb.toString();
        }
        return Integer.toString(op);
    }

    private static String handle(Handle h) {
        return h.getTag() + " " + h.getOwner() + "." + h.getName() + h.getDesc();
    }

    /** Ordinals of the method's labels that are jump, switch or try-catch targets, in order. Other labels do not shift the ordinals. */
    private static Map<LabelNode, Integer> labelOrdinals(MethodNode m) {
        Set<LabelNode> targets = new HashSet<>();
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof JumpInsnNode j) {
                targets.add(j.label);
            } else if (insn instanceof TableSwitchInsnNode t) {
                targets.add(t.dflt);
                targets.addAll(t.labels);
            } else if (insn instanceof LookupSwitchInsnNode s) {
                targets.add(s.dflt);
                targets.addAll(s.labels);
            }
        }
        for (TryCatchBlockNode tc : m.tryCatchBlocks) {
            targets.add(tc.start);
            targets.add(tc.end);
            targets.add(tc.handler);
        }
        Map<LabelNode, Integer> out = new HashMap<>();
        int i = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode l && targets.contains(l)) {
                out.put(l, i++);
            }
        }
        return out;
    }
}
