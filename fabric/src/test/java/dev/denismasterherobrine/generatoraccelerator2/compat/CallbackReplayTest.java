package dev.denismasterherobrine.generatoraccelerator2.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Which other mods' changes {@link CallbackReplay} calls itself: callbacks at the head or before a return, with the method's arguments only. */
class CallbackReplayTest {
    private static final String OWNER = "test/Level";
    private static final String CI = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    private static final String METHOD_DESC = "(Ljava/lang/String;)V";
    private static final CallbackReplay.Site SITE = new CallbackReplay.Site("test", "onChange", Set.of(CallbackReplay.Point.HEAD, CallbackReplay.Point.RETURN));

    private enum At { HEAD, MIDDLE, RETURN }

    /** A class with {@code onChange(String)} that calls one merged handler at {@code at}. */
    private static ClassNode target(At at, String handlerDesc) {
        ClassNode cn = new ClassNode();
        cn.name = OWNER;
        cn.superName = "java/lang/Object";
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "onChange", METHOD_DESC, null, null);
        InsnList body = new InsnList();
        // the method's own work: a call on its argument
        InsnList work = new InsnList();
        work.add(new VarInsnNode(Opcodes.ALOAD, 1));
        work.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I"));
        work.add(new InsnNode(Opcodes.POP));
        InsnList callback = new InsnList();
        callback.add(new TypeInsnNode(Opcodes.NEW, CI));
        callback.add(new InsnNode(Opcodes.DUP));
        callback.add(new LdcInsnNode("onChange"));
        callback.add(new InsnNode(Opcodes.ICONST_1));
        callback.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CI, "<init>", "(Ljava/lang/String;Z)V"));
        callback.add(new VarInsnNode(Opcodes.ASTORE, 2));
        callback.add(new VarInsnNode(Opcodes.ALOAD, 0));
        callback.add(new VarInsnNode(Opcodes.ALOAD, 1));
        callback.add(new VarInsnNode(Opcodes.ALOAD, 2));
        callback.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, "handler$zza000$mod$watch", handlerDesc));
        switch (at) {
            case HEAD -> {
                body.add(callback);
                body.add(work);
            }
            case MIDDLE -> {
                body.add(work);
                body.add(callback);
                InsnList more = new InsnList();
                more.add(new VarInsnNode(Opcodes.ALOAD, 1));
                more.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I"));
                more.add(new InsnNode(Opcodes.POP));
                body.add(more);
            }
            case RETURN -> {
                body.add(work);
                body.add(callback);
            }
        }
        body.add(new InsnNode(Opcodes.RETURN));
        m.instructions = body;
        cn.methods.add(m);
        MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "handler$zza000$mod$watch", handlerDesc, null, null);
        handler.instructions = new InsnList();
        handler.instructions.add(new InsnNode(Opcodes.RETURN));
        AnnotationNode merged = new AnnotationNode("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");
        merged.values = List.of("mixin", "other.mod.WatchMixin");
        handler.invisibleAnnotations = List.of(merged);
        cn.methods.add(handler);
        return cn;
    }

    private static CallbackReplay.Callback classify(ClassNode cn) {
        List<MergedMethods.Change> changes = MergedMethods.foreignCalls(cn, List.of("onChange"), List.of(), MergedMethods.Mode.BYPASS, List.of(), "own.");
        assertEquals(1, changes.size(), "the handler call is a change");
        return CallbackReplay.callback(cn, changes.get(0), SITE);
    }

    @Test
    void headCallbackIsReplayed() {
        CallbackReplay.Callback cb = classify(target(At.HEAD, "(Ljava/lang/String;L" + CI + ";)V"));
        assertNotNull(cb);
        assertEquals(CallbackReplay.Point.HEAD, cb.point());
        assertEquals("other.mod.WatchMixin", cb.mixin());
    }

    @Test
    void returnCallbackIsReplayed() {
        CallbackReplay.Callback cb = classify(target(At.RETURN, "(Ljava/lang/String;L" + CI + ";)V"));
        assertNotNull(cb);
        assertEquals(CallbackReplay.Point.RETURN, cb.point());
    }

    @Test
    void callbackInsideTheBodyIsNot() {
        assertNull(classify(target(At.MIDDLE, "(Ljava/lang/String;L" + CI + ";)V")));
    }

    @Test
    void callbackWithLocalsIsNot() {
        // a handler that also takes a local of the method: it reads something only vanilla's body has
        assertNull(classify(target(At.HEAD, "(Ljava/lang/String;L" + CI + ";I)V")));
    }
}
