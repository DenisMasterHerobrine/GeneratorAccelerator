package dev.denismasterherobrine.generatoraccelerator2.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.denismasterherobrine.generatoraccelerator2.compat.terrablender.TerraBlenderMixins;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** GA2 runs TerraBlender's region searches through its flat search only when every one is made with vanilla's distance. */
class TerraBlenderCheckTest {
    private static final String RTREE = "net/minecraft/world/level/biome/Climate$RTree";
    private static final Handle METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
        false);

    @Test
    void acceptsOnlyNodeDistance() {
        TerraBlenderMixins.check(parameterList(distance("net/minecraft/world/level/biome/Climate$RTree$Node", "distance"),
            distance("net/minecraft/world/level/biome/Climate$RTree$Node", "distance")));
        assertTrue(TerraBlenderMixins.distanceSearch());
        // one search with another metric: TerraBlender keeps its own walk
        TerraBlenderMixins.check(parameterList(distance("net/minecraft/world/level/biome/Climate$RTree$Node", "distance"),
            distance("com/example/Metric", "other")));
        assertFalse(TerraBlenderMixins.distanceSearch());
        // no region search at all (another TerraBlender version): nothing to route
        TerraBlenderMixins.check(parameterList());
        assertFalse(TerraBlenderMixins.distanceSearch());
    }

    private static InvokeDynamicInsnNode distance(String owner, String name) {
        return new InvokeDynamicInsnNode("distance", "()Lnet/minecraft/world/level/biome/Climate$DistanceMetric;", METAFACTORY,
            Type.getType("(Lnet/minecraft/world/level/biome/Climate$RTree$Node;[J)J"), new Handle(Opcodes.H_INVOKEVIRTUAL, owner, name, "([J)J", false),
            Type.getType("(Lnet/minecraft/world/level/biome/Climate$RTree$Node;[J)J"));
    }

    private static ClassNode parameterList(InvokeDynamicInsnNode... metrics) {
        ClassNode c = new ClassNode();
        c.name = "net/minecraft/world/level/biome/Climate$ParameterList";
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "findValuePositional", "(Lnet/minecraft/world/level/biome/Climate$TargetPoint;III)Ljava/lang/Object;", null, null);
        for (InvokeDynamicInsnNode metric : metrics) {
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            m.instructions.add(metric);
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, RTREE, "search",
                "(Lnet/minecraft/world/level/biome/Climate$TargetPoint;Lnet/minecraft/world/level/biome/Climate$DistanceMetric;)Ljava/lang/Object;", false));
            m.instructions.add(new InsnNode(Opcodes.POP));
        }
        m.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        m.instructions.add(new InsnNode(Opcodes.ARETURN));
        c.methods = new ArrayList<>(List.of(m));
        return c;
    }
}
