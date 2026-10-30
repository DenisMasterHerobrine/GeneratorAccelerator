package dev.denismasterherobrine.generatoraccelerator2.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * {@link Coremods}' comparison. A coremod's inserted call is found and named after the class it calls. Forge's own
 * rewrites are not changes, and neither are switched invoke opcodes on the class's own methods. A change that calls no
 * other mod's code is reported as unknown. Uses real game classes, read from the jar.
 */
public class CoremodsTest {
    private static ClassNode read(String internal) throws Exception {
        try (InputStream in = CoremodsTest.class.getClassLoader().getResourceAsStream(internal + ".class")) {
            assertNotNull(in, internal);
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    @Test
    void sameCodeIsNoChange() throws Exception {
        String owner = "net/minecraft/world/level/chunk/ChunkStatus";
        assertNull(Coremods.compare(owner, method(read(owner), "method_16569"), method(read(owner), "method_16569")));
    }

    @Test
    void twilightForestBlanketingIsFound() throws Exception {
        String owner = "net/minecraft/world/level/chunk/ChunkStatus";
        MethodNode original = method(read(owner), "method_16569");
        MethodNode changed = method(read(owner), "method_16569");
        // what a coremod like Twilight Forest's does to the SURFACE task (method_16569): after the generator call, chunkBlanketing(chunk, region)
        AbstractInsnNode call = null;
        for (AbstractInsnNode insn = changed.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode m && m.name.equals("buildSurface")) {
                call = insn;
            }
        }
        assertNotNull(call);
        InsnList inserted = new InsnList();
        inserted.add(new VarInsnNode(Opcodes.ALOAD, 4));
        inserted.add(new VarInsnNode(Opcodes.ALOAD, 5));
        inserted.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "twilightforest/asmhooks/WorldgenHooks", "chunkBlanketing",
            "(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/server/level/WorldGenRegion;)V", false));
        changed.instructions.insert(call, inserted);
        Coremods.Change c = Coremods.compare(owner, original, changed);
        assertNotNull(c);
        assertEquals("coremod twilightforest/asmhooks/WorldgenHooks", c.mixin());
        assertEquals(List.of("coremod$chunkBlanketing"), c.merged());
    }

    @Test
    void beardifierDensityIsFound() throws Exception {
        String owner = "net/minecraft/world/level/levelgen/Beardifier";
        MethodNode original = method(read(owner), "compute");
        MethodNode changed = method(read(owner), "compute");
        // what BeardifierComputeTransformer does before every DRETURN: getCustomDensity(value, ctx, this.field)
        for (AbstractInsnNode insn = changed.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.DRETURN) {
                InsnList inserted = new InsnList();
                inserted.add(new VarInsnNode(Opcodes.ALOAD, 1));
                inserted.add(new VarInsnNode(Opcodes.ALOAD, 0));
                inserted.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "twilightforest_customStructureDensities", "Lit/unimi/dsi/fastutil/objects/ObjectListIterator;"));
                inserted.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "twilightforest/asmhooks/WorldgenHooks", "getCustomDensity",
                    "(DLnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;Lit/unimi/dsi/fastutil/objects/ObjectListIterator;)D", false));
                changed.instructions.insertBefore(insn, inserted);
            }
        }
        Coremods.Change c = Coremods.compare(owner, original, changed);
        assertNotNull(c);
        assertEquals("coremod twilightforest/asmhooks/WorldgenHooks", c.mixin());
        assertEquals(List.of("coremod$getCustomDensity"), c.merged());
    }

    @Test
    void ownInvokeOpcodeIsNoChange() throws Exception {
        String owner = "net/minecraft/world/level/levelgen/Beardifier";
        MethodNode original = method(read(owner), "compute");
        MethodNode changed = method(read(owner), "compute");
        int switched = 0;
        for (AbstractInsnNode insn = changed.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode m && m.owner.equals(owner) && m.getOpcode() == Opcodes.INVOKESTATIC) {
                continue;
            }
            if (insn instanceof MethodInsnNode m && m.owner.equals(owner) && !m.name.equals("<init>")) {
                m.setOpcode(m.getOpcode() == Opcodes.INVOKEVIRTUAL ? Opcodes.INVOKESPECIAL : Opcodes.INVOKEVIRTUAL);
                switched++;
            }
        }
        assertNull(Coremods.compare(owner, original, changed), "switched " + switched + " own calls");
    }

    @Test
    void changedConstantIsUnknown() throws Exception {
        String owner = "net/minecraft/world/level/levelgen/Beardifier";
        MethodNode original = method(read(owner), "compute");
        MethodNode changed = method(read(owner), "compute");
        for (AbstractInsnNode insn = changed.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Double d && d == 0.8) {
                changed.instructions.set(insn, new LdcInsnNode(0.81));
                break;
            }
        }
        Coremods.Change c = Coremods.compare(owner, original, changed);
        assertNotNull(c);
        assertEquals(Coremods.UNKNOWN + " in compute", c.mixin(), "the method is named");
    }

    /** Fabric Loader's branding patch wraps the name in a call to its own hooks class. It is the platform's change, not a mod's. */
    @Test
    void fabricLoaderBrandingIsNoChange() throws Exception {
        String owner = "net/minecraft/world/level/levelgen/Beardifier";
        MethodNode original = method(read(owner), "compute");
        MethodNode changed = method(read(owner), "compute");
        for (AbstractInsnNode insn = changed.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Double d && d == 0.8) {
                changed.instructions.insert(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, "net/fabricmc/loader/impl/game/minecraft/Hooks", "insertBranding",
                    "(D)D", false));
                break;
            }
        }
        assertNull(Coremods.compare(owner, original, changed));
    }

    @Test
    void forgeGettersAreNoChange() throws Exception {
        // Forge's field_to_method coremod on Structure: its methods read settings through getModifiedStructureSettings()
        String owner = "net/minecraft/world/level/levelgen/structure/Structure";
        ClassNode jar = read(owner);
        ClassNode loaded = read(owner);
        int replaced = 0;
        for (MethodNode m : loaded.methods) {
            if (m.desc.equals("()Lnet/minecraft/world/level/levelgen/structure/Structure$StructureSettings;")) {
                continue;
            }
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.GETFIELD && ((FieldInsnNode) insn).name.equals("settings")) {
                    MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "getModifiedStructureSettings",
                        "()Lnet/minecraft/world/level/levelgen/structure/Structure$StructureSettings;", false);
                    m.instructions.set(insn, call);
                    insn = call;
                    replaced++;
                }
            }
        }
        Coremods.applyForge(jar);
        for (MethodNode m : loaded.methods) {
            MethodNode o = method(jar, m.name);
            if (o.desc.equals(m.desc)) {
                assertNull(Coremods.compare(owner, o, m), m.name + m.desc + " (" + replaced + " reads replaced)");
            }
        }
        // and a real change next to them still counts
        MethodNode biomes = loaded.methods.stream().filter(m -> m.name.equals("biomes")).findFirst().orElseThrow();
        biomes.instructions.insert(new InsnNode(Opcodes.NOP));
        assertNotNull(Coremods.compare(owner, method(jar, "biomes"), biomes));
    }
}
