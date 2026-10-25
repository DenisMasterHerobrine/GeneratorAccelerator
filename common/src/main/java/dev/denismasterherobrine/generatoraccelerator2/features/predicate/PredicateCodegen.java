package dev.denismasterherobrine.generatoraccelerator2.features.predicate;

import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.HiddenClassLoader;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Turns a predicate program's tree into one generated class (a hidden class, like the density function compiler's). The
 * class's {@code test} is straight-line code. The combinators become jumps that stop where vanilla's loops stop. A fixed
 * block list becomes identity comparisons against final fields (constants to the JIT in a hidden class). A block read at
 * an offset that is read more than once is kept in a local until code the program does not run itself runs. Every other
 * predicate is called through a final field. The tree's meaning is unchanged. Only the dispatch between its parts goes away.
 */
final class PredicateCodegen {
    private PredicateCodegen() {}

    /** A tree with more nodes than this keeps the tree form (never the case for vanilla's and common modded predicates). */
    static final int MAX_NODES = 400;

    private static final AtomicInteger COUNTER = new AtomicInteger();
    static final java.util.concurrent.atomic.LongAdder GENERATED = new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder FAILED = new java.util.concurrent.atomic.LongAdder();
    static volatile Throwable lastFailure;
    private static final MethodType CTOR = MethodType.methodType(void.class, Object[].class);

    private static final String COMPILED = Type.getInternalName(PredicatePrograms.Compiled.class);
    private static final String PROGRAMS = Type.getInternalName(PredicatePrograms.class);
    // vanilla's classes and members the generated code uses, by the running game's names ({@link Names}: intermediary on Fabric)
    private static final String LEVEL = Names.runtimeClass("net/minecraft/world/level/WorldGenLevel");
    private static final String POS = Names.runtimeClass("net/minecraft/core/BlockPos");
    private static final String READS = "dev/denismasterherobrine/generatoraccelerator2/features/region/RegionReads";
    private static final String MOJ_STATE = "net/minecraft/world/level/block/state/BlockState";
    private static final String MOJ_FLUID_STATE = "net/minecraft/world/level/material/FluidState";
    private static final String STATE = Names.runtimeClass(MOJ_STATE);
    private static final String BLOCK = Names.runtimeClass("net/minecraft/world/level/block/Block");
    private static final String FLUID_STATE = Names.runtimeClass(MOJ_FLUID_STATE);
    private static final String HOLDER_SET = Names.runtimeClass("net/minecraft/core/HolderSet");
    private static final String TAG_KEY = Names.runtimeClass("net/minecraft/tags/TagKey");
    private static final String PREDICATE = Names.runtimeClass("net/minecraft/world/level/levelgen/blockpredicates/BlockPredicate");
    private static final String GET_BLOCK = Names.runtimeMethod(MOJ_STATE, "getBlock", "()Lnet/minecraft/world/level/block/Block;");
    private static final String STATE_IS_SET = Names.runtimeMethod(MOJ_STATE, "is", "(Lnet/minecraft/core/HolderSet;)Z");
    private static final String STATE_IS_TAG = Names.runtimeMethod(MOJ_STATE, "is", "(Lnet/minecraft/tags/TagKey;)Z");
    private static final String GET_FLUID_STATE = Names.runtimeMethod(MOJ_STATE, "getFluidState", "()Lnet/minecraft/world/level/material/FluidState;");
    private static final String FLUID_IS_SET = Names.runtimeMethod(MOJ_FLUID_STATE, "is", "(Lnet/minecraft/core/HolderSet;)Z");
    private static final String IS_SOLID = Names.runtimeMethod(MOJ_STATE, "isSolid", "()Z");
    private static final String CAN_BE_REPLACED = Names.runtimeMethod(MOJ_STATE, "canBeReplaced", "()Z");
    private static final String REF_SET = "it/unimi/dsi/fastutil/objects/ReferenceOpenHashSet";
    private static final String DIRECTION = Names.runtimeClass("net/minecraft/core/Direction");
    private static final String STURDY_DESC = "(L" + LEVEL + ";L" + POS + ";IIIL" + DIRECTION + ";)Z";
    private static final String TEST_DESC = "(L" + LEVEL + ";L" + POS + ";L" + READS + ";)Z";
    private static final String READ_DESC = "(L" + LEVEL + ";L" + POS + ";L" + READS + ";III)L" + STATE + ";";

    /** The compiled form of {@code root}, or null when it is too large or cannot be defined (the tree form is used then). */
    static PredicatePrograms.Compiled generate(PredicatePrograms.Node root, int slots) {
        if (count(root) > MAX_NODES) {
            return null;
        }
        String name = HiddenClassLoader.GENERATED_PREFIX + "Predicate" + COUNTER.incrementAndGet();
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return type1.equals(type2) ? type1 : "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, name, null, "java/lang/Object", new String[] { COMPILED });
        Gen gen = new Gen(cw, name, slots);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "test", TEST_DESC, null, null);
        gen.mv = mv;
        mv.visitCode();
        for (int s = 0; s < slots; s++) {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitVarInsn(Opcodes.ASTORE, gen.memo(s));
        }
        Label no = new Label();
        gen.cond(root, no);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(no);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        gen.constructor();
        cw.visitEnd();
        try {
            MethodHandles.Lookup lookup = HiddenClassLoader.define(cw, "predicate");
            MethodHandle ctor = lookup.findConstructor(lookup.lookupClass(), CTOR);
            PredicatePrograms.Compiled compiled = (PredicatePrograms.Compiled) ctor.invoke(gen.constants.toArray());
            GENERATED.increment();
            return compiled;
        } catch (Throwable t) {
            FAILED.increment();
            lastFailure = t;
            return null;
        }
    }

    private static int count(PredicatePrograms.Node n) {
        int c = 1;
        if (n instanceof PredicatePrograms.All a) {
            for (PredicatePrograms.Node k : a.kids) {
                c += count(k);
            }
        } else if (n instanceof PredicatePrograms.Any a) {
            for (PredicatePrograms.Node k : a.kids) {
                c += count(k);
            }
        } else if (n instanceof PredicatePrograms.Not not) {
            c += count(not.kid);
        } else if (n instanceof PredicatePrograms.FewBlocks f) {
            c += f.blocks.length;
        }
        return c;
    }

    private static final class Gen {
        final ClassWriter cw;
        final String name;
        final int slots;
        final List<Object> constants = new ArrayList<>();
        final List<String> descriptors = new ArrayList<>();
        MethodVisitor mv;

        Gen(ClassWriter cw, String name, int slots) {
            this.cw = cw;
            this.name = name;
            this.slots = slots;
        }

        /** Locals: 0 this, 1 level, 2 origin, 3 reads, then one per memo slot, then a scratch block. */
        int memo(int slot) {
            return 4 + slot;
        }

        int scratch() {
            return 4 + this.slots;
        }

        /** A final field holding {@code value}. Pushes it. */
        void constant(Object value, String internalType) {
            int i = this.constants.size();
            this.constants.add(value);
            this.descriptors.add("L" + internalType + ";");
            this.cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "c" + i, "L" + internalType + ";", null, null).visitEnd();
            this.mv.visitVarInsn(Opcodes.ALOAD, 0);
            this.mv.visitFieldInsn(Opcodes.GETFIELD, this.name, "c" + i, "L" + internalType + ";");
        }

        void constructor() {
            MethodVisitor c = this.cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "([Ljava/lang/Object;)V", null, null);
            c.visitCode();
            c.visitVarInsn(Opcodes.ALOAD, 0);
            c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            for (int i = 0; i < this.descriptors.size(); i++) {
                String desc = this.descriptors.get(i);
                c.visitVarInsn(Opcodes.ALOAD, 0);
                c.visitVarInsn(Opcodes.ALOAD, 1);
                pushInt(c, i);
                c.visitInsn(Opcodes.AALOAD);
                c.visitTypeInsn(Opcodes.CHECKCAST, desc.substring(1, desc.length() - 1));
                c.visitFieldInsn(Opcodes.PUTFIELD, this.name, "c" + i, desc);
            }
            c.visitInsn(Opcodes.RETURN);
            c.visitMaxs(0, 0);
            c.visitEnd();
        }

        /** Falls through when {@code n} holds, jumps to {@code no} when it does not. */
        void cond(PredicatePrograms.Node n, Label no) {
            if (n instanceof PredicatePrograms.All a) {
                for (PredicatePrograms.Node k : a.kids) {
                    cond(k, no);
                }
            } else if (n instanceof PredicatePrograms.Any a) {
                if (a.kids.length == 0) {
                    this.mv.visitJumpInsn(Opcodes.GOTO, no);
                    return;
                }
                Label yes = new Label();
                for (int i = 0; i < a.kids.length - 1; i++) {
                    Label next = new Label();
                    cond(a.kids[i], next);
                    this.mv.visitJumpInsn(Opcodes.GOTO, yes);
                    this.mv.visitLabel(next);
                }
                cond(a.kids[a.kids.length - 1], no);
                this.mv.visitLabel(yes);
            } else if (n instanceof PredicatePrograms.Not not) {
                Label kidNo = new Label();
                cond(not.kid, kidNo);
                this.mv.visitJumpInsn(Opcodes.GOTO, no);
                this.mv.visitLabel(kidNo);
            } else if (n instanceof PredicatePrograms.True) {
                // holds
            } else if (n instanceof PredicatePrograms.Opaque o) {
                forget();
                constant(o.predicate, PREDICATE);
                this.mv.visitVarInsn(Opcodes.ALOAD, 1);
                this.mv.visitVarInsn(Opcodes.ALOAD, 2);
                this.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PREDICATE, "test", "(Ljava/lang/Object;Ljava/lang/Object;)Z", true);
                forget();
                this.mv.visitJumpInsn(Opcodes.IFEQ, no);
            } else if (n instanceof PredicatePrograms.Sturdy s) {
                // reads the world as a leaf does, and writes nothing: the remembered reads stay
                this.mv.visitVarInsn(Opcodes.ALOAD, 1);
                this.mv.visitVarInsn(Opcodes.ALOAD, 2);
                pushInt(this.mv, s.dx);
                pushInt(this.mv, s.dy);
                pushInt(this.mv, s.dz);
                constant(s.direction, DIRECTION);
                this.mv.visitMethodInsn(Opcodes.INVOKESTATIC, PROGRAMS, "sturdy", STURDY_DESC, false);
                this.mv.visitJumpInsn(Opcodes.IFEQ, no);
            } else if (n instanceof PredicatePrograms.Leaf leaf) {
                state(leaf);
                leafTest(leaf, no);
            } else {
                throw new IllegalStateException("unknown node " + n);
            }
        }

        /** The remembered reads are dropped (after code the program does not run itself). */
        void forget() {
            for (int s = 0; s < this.slots; s++) {
                this.mv.visitInsn(Opcodes.ACONST_NULL);
                this.mv.visitVarInsn(Opcodes.ASTORE, memo(s));
            }
        }

        /** Pushes the leaf's block state: {@code PredicatePrograms.read}, through its memo local when it has one. */
        void state(PredicatePrograms.Leaf leaf) {
            Label have = null;
            if (leaf.slot >= 0) {
                have = new Label();
                this.mv.visitVarInsn(Opcodes.ALOAD, memo(leaf.slot));
                this.mv.visitInsn(Opcodes.DUP);
                this.mv.visitJumpInsn(Opcodes.IFNONNULL, have);
                this.mv.visitInsn(Opcodes.POP);
            }
            this.mv.visitVarInsn(Opcodes.ALOAD, 1);
            this.mv.visitVarInsn(Opcodes.ALOAD, 2);
            this.mv.visitVarInsn(Opcodes.ALOAD, 3);
            pushInt(this.mv, leaf.dx);
            pushInt(this.mv, leaf.dy);
            pushInt(this.mv, leaf.dz);
            this.mv.visitMethodInsn(Opcodes.INVOKESTATIC, PROGRAMS, "read", READ_DESC, false);
            if (have != null) {
                this.mv.visitInsn(Opcodes.DUP);
                this.mv.visitVarInsn(Opcodes.ASTORE, memo(leaf.slot));
                this.mv.visitLabel(have);
            }
        }

        /** With the state on the stack: jumps to {@code no} when the leaf's test fails, else falls through (stack empty). */
        void leafTest(PredicatePrograms.Leaf leaf, Label no) {
            MethodVisitor m = this.mv;
            if (leaf instanceof PredicatePrograms.OneBlock one) {
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STATE, GET_BLOCK, "()L" + BLOCK + ";", false);
                constant(one.block, BLOCK);
                m.visitJumpInsn(Opcodes.IF_ACMPNE, no);
            } else if (leaf instanceof PredicatePrograms.FewBlocks few) {
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STATE, GET_BLOCK, "()L" + BLOCK + ";", false);
                m.visitVarInsn(Opcodes.ASTORE, scratch());
                Label yes = new Label();
                for (Object b : few.blocks) {
                    m.visitVarInsn(Opcodes.ALOAD, scratch());
                    constant(b, BLOCK);
                    m.visitJumpInsn(Opcodes.IF_ACMPEQ, yes);
                }
                m.visitJumpInsn(Opcodes.GOTO, no);
                m.visitLabel(yes);
            } else if (leaf instanceof PredicatePrograms.ManyBlocks many) {
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STATE, GET_BLOCK, "()L" + BLOCK + ";", false);
                m.visitVarInsn(Opcodes.ASTORE, scratch());
                constant(many.blocks, REF_SET);
                m.visitVarInsn(Opcodes.ALOAD, scratch());
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, REF_SET, "contains", "(Ljava/lang/Object;)Z", false);
                m.visitJumpInsn(Opcodes.IFEQ, no);
            } else if (leaf instanceof PredicatePrograms.InSet set) {
                constant(set.set, HOLDER_SET);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STATE, STATE_IS_SET, "(L" + HOLDER_SET + ";)Z", false);
                m.visitJumpInsn(Opcodes.IFEQ, no);
            } else if (leaf instanceof PredicatePrograms.InTag tag) {
                constant(tag.tag, TAG_KEY);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STATE, STATE_IS_TAG, "(L" + TAG_KEY + ";)Z", false);
                m.visitJumpInsn(Opcodes.IFEQ, no);
            } else if (leaf instanceof PredicatePrograms.InFluids fluids) {
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STATE, GET_FLUID_STATE, "()L" + FLUID_STATE + ";", false);
                constant(fluids.fluids, HOLDER_SET);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FLUID_STATE, FLUID_IS_SET, "(L" + HOLDER_SET + ";)Z", false);
                m.visitJumpInsn(Opcodes.IFEQ, no);
            } else if (leaf instanceof PredicatePrograms.Solid) {
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STATE, IS_SOLID, "()Z", false);
                m.visitJumpInsn(Opcodes.IFEQ, no);
            } else if (leaf instanceof PredicatePrograms.Replaceable) {
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STATE, CAN_BE_REPLACED, "()Z", false);
                m.visitJumpInsn(Opcodes.IFEQ, no);
            } else {
                throw new IllegalStateException("unknown leaf " + leaf);
            }
        }
    }

    private static void pushInt(MethodVisitor mv, int v) {
        if (v >= -1 && v <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + v);
        } else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, v);
        } else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.SIPUSH, v);
        } else {
            mv.visitLdcInsn(v);
        }
    }
}
