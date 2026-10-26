package dev.denismasterherobrine.generatoraccelerator2.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.compat.biolith.BiolithMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.LithostitchedMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.naturespirit.NaturesSpiritMixins;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** The inspection finds other mods' handlers (also behind MixinExtras bridges) and {@link KnownMixins} matches them exactly. */
class KnownMixinsTest {
    private static final String OWN = "dev.denismasterherobrine.generatoraccelerator2.";
    private static final String TARGET = "test/Target";
    private static final String OTHER = "com.example.other.mixin.OtherMixin";

    @Test
    void handlerNamesMatchExactly() {
        String hook = HookTargets.SURFACE_BUILD;
        assertNotNull(KnownMixins.find(hook, NaturesSpiritMixins.SURFACE, List.of("handler$zza000$natures_spirit$injectPillars")));
        // MixinExtras' bridge for a handler with sugar carries the handler's name
        assertNotNull(KnownMixins.find(hook, BiolithMixins.SURFACE, List.of("wrapOperation$zzi000$biolith$injectSurfaceBuilders$mixinextras$bridge$15",
            "wrapOperation$zzi000$biolith$injectLateSurfaceBuilders")));
        assertNotNull(KnownMixins.find(HookTargets.STRUCTURE_FOSSIL_PROBE,
            LithostitchedMixins.NETHER_FOSSIL, List.of("wrapOperation$zzj000$lithostitched$useTemplateList")));
        // another handler of the same mixin (a newer version of the mod) is not known: the hook must stand down
        assertNull(KnownMixins.find(hook, NaturesSpiritMixins.SURFACE, List.of("handler$zza000$natures_spirit$injectPillars", "handler$zzb000$natures_spirit$somethingNew")));
        // an overwrite is reported with the method's own name, which no entry names
        assertNull(KnownMixins.find(hook, NaturesSpiritMixins.SURFACE, List.of("buildSurface")));
        // the same mixin on another hook, or another mixin, is not known
        assertNull(KnownMixins.find(HookTargets.SURFACE_TOP_MATERIAL, NaturesSpiritMixins.SURFACE, List.of("handler$zza000$natures_spirit$injectPillars")));
        assertNull(KnownMixins.find(hook, OTHER, List.of("handler$zza000$other$injectPillars")));
    }

    @Test
    void inspectionSeesHandlersAndNestedWrappers() {
        ClassNode target = new ClassNode();
        target.name = TARGET;
        MethodNode ownHandler = merged("handler$zzc000$generatoraccelerator2$ga2$buildSurface", "()V", OWN + "mixin.surface.SurfaceSystemMixin");
        MethodNode pillars = merged("handler$zza000$natures_spirit$injectPillars", "()V", NaturesSpiritMixins.SURFACE);
        MethodNode inner = merged("wrapOperation$zzb000$other$inner", "()Z", OTHER);
        MethodNode bridge = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "mixinextras$bridge$is$3", "()Z", null, null);
        bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, inner.name, inner.desc, false));
        bridge.instructions.add(new InsnNode(Opcodes.IRETURN));
        MethodNode build = new MethodNode(Opcodes.ACC_PUBLIC, "buildSurface", "()V", null, null);
        build.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, TARGET, ownHandler.name, ownHandler.desc, false));
        build.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, TARGET, pillars.name, pillars.desc, false));
        Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
            false);
        build.instructions.add(new InvokeDynamicInsnNode("call", "()Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;", metafactory,
            Type.getType("([Ljava/lang/Object;)Ljava/lang/Object;"), new Handle(Opcodes.H_INVOKESTATIC, TARGET, bridge.name, bridge.desc, false),
            Type.getType("([Ljava/lang/Object;)Ljava/lang/Object;")));
        build.instructions.add(new InsnNode(Opcodes.RETURN));
        target.methods = new ArrayList<>(List.of(build, ownHandler, pillars, inner, bridge));

        List<MergedMethods.Change> changes = MergedMethods.foreignCalls(target, List.of("buildSurface"), List.of(), MergedMethods.Mode.REPLACE, List.of(), OWN);
        assertEquals(2, changes.size(), changes.toString());
        assertEquals(NaturesSpiritMixins.SURFACE, changes.get(0).mixin());
        assertEquals(1, changes.get(0).at());
        // the wrapper inside the operation's bridge counts at the operation
        assertEquals(OTHER, changes.get(1).mixin());
        assertEquals(2, changes.get(1).at());
        assertTrue(changes.get(1).method().startsWith(inner.name));
    }

    private static MethodNode merged(String name, String desc, String mixin) {
        MethodNode m = new MethodNode(Opcodes.ACC_PRIVATE, name, desc, null, null);
        AnnotationNode a = new AnnotationNode("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");
        a.values = new ArrayList<>(List.of("mixin", mixin));
        m.visibleAnnotations = new ArrayList<>(List.of(a));
        m.instructions.add(new InsnNode(desc.endsWith("V") ? Opcodes.RETURN : Opcodes.ICONST_0));
        return m;
    }
}
