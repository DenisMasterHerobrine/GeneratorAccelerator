package dev.denismasterherobrine.generatoraccelerator2.surface.compile;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.dfc.codegen.HiddenClassLoader;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.block.state.BlockState;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;

/**
 * Compiles a {@link SurfaceProgram} to a hidden class implementing {@link SurfaceProgram.Evaluator}.
 *
 * <p>The generated {@code apply} walks the rule tree in vanilla order with every branch, slot and
 * threshold as a constant: a sequence tries its rules in turn, a test jumps past its follow-up when
 * the condition is false, a state rule returns its state. Conditions call the {@link SurfaceEval}
 * methods that transcribe vanilla's {@code compute()} bodies. The expensive pure ones (biome,
 * temperature, gradient randomness) go through the per-block cache. Column values are cached by
 * {@code SurfaceEval} itself. Large subtrees become their own methods to stay below the JVM's method
 * size limits. If generation fails the program keeps its interpreter.
 */
public final class SurfaceCompiler {
    private SurfaceCompiler() {}

    private static final Logger LOGGER = Ga2Log.get("Surface");
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final String PREFIX = "dev/denismasterherobrine/generatoraccelerator2/surface/compile/GeneratedSurface";
    private static final String EVAL = "dev/denismasterherobrine/generatoraccelerator2/surface/compile/SurfaceEval";
    private static final String EVALUATOR = "dev/denismasterherobrine/generatoraccelerator2/surface/compile/SurfaceProgram$Evaluator";
    // vanilla's BlockState by the running game's name ({@link Names}): it is in the generated classes' descriptors
    private static final String STATE = Names.runtimeClass("net/minecraft/world/level/block/state/BlockState");
    private static final String APPLY_DESC = "(L" + EVAL + ";)L" + STATE + ";";
    private static final String CTOR_DESC = "([L" + STATE + ";)V";
    /** Subtrees with more nodes than this become their own method. */
    private static final int SPLIT_NODES = 300;
    /**
     * Nodes one method may inline (about 6 bytes each): a sequence of many mid-sized children (Terralith's surface rules)
     * would otherwise grow past HotSpot's 8000-byte limit for compiled methods and stay interpreted.
     */
    private static final int METHOD_NODES = 800;

    /** The generated evaluator, or the program's interpreter when generation fails. */
    public static SurfaceProgram.Evaluator compile(SurfaceProgram program) {
        try {
            return new Generator(program).generate();
        } catch (Throwable t) {
            LOGGER.warn("rule compilation failed -> interpreted", t);
            return program.interpreter;
        }
    }

    static final class SafeClassWriter extends ClassWriter {
        SafeClassWriter() {
            super(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            return type1.equals(type2) ? type1 : "java/lang/Object";
        }
    }

    private static final class Generator {
        private final SurfaceProgram program;
        private final String name = PREFIX + COUNTER.incrementAndGet();
        private final List<BlockState> constants = new ArrayList<>();
        private final Map<BlockState, Integer> constantIndex = new IdentityHashMap<>();
        private final Map<Ir.Rule, String> methods = new IdentityHashMap<>();
        private final List<Ir.Rule> pending = new ArrayList<>();
        private ClassWriter cw;
        /** Nodes the method being emitted may still inline. */
        private int budget;

        Generator(SurfaceProgram program) {
            this.program = program;
        }

        SurfaceProgram.Evaluator generate() throws Throwable {
            cw = new SafeClassWriter();
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, name, null, "java/lang/Object", new String[] { EVALUATOR });
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "c", "[L" + STATE + ";", null, null).visitEnd();
            MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", CTOR_DESC, null, null);
            ctor.visitCode();
            ctor.visitVarInsn(Opcodes.ALOAD, 0);
            ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            ctor.visitVarInsn(Opcodes.ALOAD, 0);
            ctor.visitVarInsn(Opcodes.ALOAD, 1);
            ctor.visitFieldInsn(Opcodes.PUTFIELD, name, "c", "[L" + STATE + ";");
            ctor.visitInsn(Opcodes.RETURN);
            ctor.visitMaxs(0, 0);
            ctor.visitEnd();

            emitMethod("apply", Opcodes.ACC_PUBLIC, program.root);
            while (!pending.isEmpty()) {
                Ir.Rule rule = pending.remove(pending.size() - 1);
                emitMethod(methods.get(rule), Opcodes.ACC_PRIVATE, rule);
            }
            cw.visitEnd();
            byte[] bytes = cw.toByteArray();
            String huge = HiddenClassLoader.largestMethodOver(bytes, HiddenClassLoader.JIT_METHOD_LIMIT);
            if (huge != null) {
                throw new IllegalStateException("generated surface method " + huge + " is over the JIT's method size limit");
            }
            MethodHandles.Lookup lookup = MethodHandles.lookup().defineHiddenClass(bytes, true, MethodHandles.Lookup.ClassOption.NESTMATE);
            HiddenClassLoader.DEFINED.increment();
            MethodHandle ctorHandle = lookup.findConstructor(lookup.lookupClass(), MethodType.methodType(void.class, BlockState[].class));
            return (SurfaceProgram.Evaluator) ctorHandle.invoke(constants.toArray(new BlockState[0]));
        }

        private void emitMethod(String methodName, int access, Ir.Rule rule) {
            MethodVisitor mv = cw.visitMethod(access, methodName, APPLY_DESC, null, null);
            mv.visitCode();
            budget = METHOD_NODES;
            Label isNull = new Label();
            emitRule(mv, rule, isNull, true);
            mv.visitLabel(isNull);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        /** Emits code that returns the rule's state, or jumps to {@code fail} when the rule gives null. */
        private void emitRule(MethodVisitor mv, Ir.Rule rule, Label fail, boolean root) {
            if (!root && !(rule instanceof Ir.State) && (size(rule) > SPLIT_NODES || size(rule) > budget)) {
                String m = methods.get(rule);
                if (m == null) {
                    m = "r" + methods.size();
                    methods.put(rule, m);
                    pending.add(rule);
                }
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, name, m, APPLY_DESC, false);
                returnIfNonNull(mv, fail);
                budget -= 2;
                return;
            }
            budget -= rule instanceof Ir.Test t ? 1 + condSize(t.cond()) : 1;
            if (rule instanceof Ir.State s) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, name, "c", "[L" + STATE + ";");
                push(mv, constant(s.state()));
                mv.visitInsn(Opcodes.AALOAD);
                if (s.state() == null) {
                    mv.visitInsn(Opcodes.POP);
                    mv.visitJumpInsn(Opcodes.GOTO, fail);
                } else {
                    mv.visitInsn(Opcodes.ARETURN);
                }
            } else if (rule instanceof Ir.Bands b) {
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVAL, "bands", "()L" + STATE + ";", false);
                returnIfNonNull(mv, fail);
            } else if (rule instanceof Ir.OpaqueRule o) {
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                push(mv, o.index());
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVAL, "opaqueRule", "(I)L" + STATE + ";", false);
                returnIfNonNull(mv, fail);
            } else if (rule instanceof Ir.ExternRule e) {
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                push(mv, e.index());
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVAL, "externRule", "(I)L" + STATE + ";", false);
                returnIfNonNull(mv, fail);
            } else if (rule instanceof Ir.Test t) {
                emitCondJump(mv, t.cond(), fail, false);
                emitRule(mv, t.then(), fail, false);
            } else if (rule instanceof Ir.Seq seq) {
                List<Ir.Rule> rules = seq.rules();
                for (int i = 0; i < rules.size(); i++) {
                    if (i == rules.size() - 1) {
                        emitRule(mv, rules.get(i), fail, false);
                    } else {
                        Label next = new Label();
                        emitRule(mv, rules.get(i), next, false);
                        mv.visitLabel(next);
                    }
                }
            }
        }

        private static void returnIfNonNull(MethodVisitor mv, Label fail) {
            Label nonNull = new Label();
            mv.visitInsn(Opcodes.DUP);
            mv.visitJumpInsn(Opcodes.IFNONNULL, nonNull);
            mv.visitInsn(Opcodes.POP);
            mv.visitJumpInsn(Opcodes.GOTO, fail);
            mv.visitLabel(nonNull);
            mv.visitInsn(Opcodes.ARETURN);
        }

        /** Jumps to {@code target} when the condition's value equals {@code jumpIf}. Falls through otherwise. */
        private void emitCondJump(MethodVisitor mv, Ir.Cond cond, Label target, boolean jumpIf) {
            if (cond instanceof Ir.Const c) {
                if (c.value() == jumpIf) {
                    mv.visitJumpInsn(Opcodes.GOTO, target);
                }
                return;
            }
            if (cond instanceof Ir.Not not) {
                emitCondJump(mv, not.target(), target, !jumpIf);
                return;
            }
            if (cond instanceof Ir.All || cond instanceof Ir.Any) {
                // parts in order, stopping at the first that decides (false for all-of, true for any-of)
                boolean all = cond instanceof Ir.All;
                List<Ir.Cond> parts = all ? ((Ir.All) cond).conds() : ((Ir.Any) cond).conds();
                boolean decides = !all;
                if (jumpIf == decides) {
                    for (Ir.Cond part : parts) {
                        emitCondJump(mv, part, target, decides);
                    }
                } else {
                    Label done = new Label();
                    for (Ir.Cond part : parts) {
                        emitCondJump(mv, part, done, decides);
                    }
                    mv.visitJumpInsn(Opcodes.GOTO, target);
                    mv.visitLabel(done);
                }
                return;
            }
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            if (cond instanceof Ir.Biome b) {
                push(mv, b.id());
                push(mv, b.slot());
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVAL, "cachedBiome", "(II)Z", false);
            } else if (cond instanceof Ir.Temperature t) {
                push(mv, t.id());
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVAL, "cachedTemperature", "(I)Z", false);
            } else if (cond instanceof Ir.Gradient g) {
                push(mv, g.id());
                push(mv, g.slot());
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVAL, "cachedGradient", "(II)Z", false);
            } else if (cond instanceof Ir.Noise n) {
                push(mv, n.slot());
                mv.visitLdcInsn(n.min());
                mv.visitLdcInsn(n.max());
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVAL, "noise", "(IDD)Z", false);
            } else if (cond instanceof Ir.YCheck y) {
                push(mv, y.anchor());
                push(mv, y.mult());
                push(mv, y.addStone() ? 1 : 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVAL, "yCheck", "(IIZ)Z", false);
            } else if (cond instanceof Ir.Water w) {
                push(mv, w.offset());
                push(mv, w.mult());
                push(mv, w.addStone() ? 1 : 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVAL, "water", "(IIZ)Z", false);
            } else if (cond instanceof Ir.StoneDepth d) {
                push(mv, d.offset());
                push(mv, d.addSurface() ? 1 : 0);
                push(mv, d.secondaryRange());
                push(mv, d.ceiling() ? 1 : 0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVAL, "stoneDepth", "(IZIZ)Z", false);
            } else if (cond instanceof Ir.Steep s) {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVAL, "steep", "()Z", false);
            } else if (cond instanceof Ir.Slope sl) {
                push(mv, sl.id());
                push(mv, sl.min());
                push(mv, sl.max());
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVAL, "cachedSlope", "(III)Z", false);
            } else if (cond instanceof Ir.Hole h) {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVAL, "hole", "()Z", false);
            } else if (cond instanceof Ir.AboveSurface a) {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVAL, "aboveSurface", "()Z", false);
            } else if (cond instanceof Ir.OpaqueCond o) {
                push(mv, o.index());
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVAL, "opaqueCond", "(I)Z", false);
            } else if (cond instanceof Ir.ExternCond e) {
                push(mv, e.id());
                push(mv, e.index());
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVAL, "cachedExtern", "(II)Z", false);
            } else if (cond instanceof Ir.Const c) {
                throw new IllegalStateException();
            } else if (cond instanceof Ir.Not n) {
                throw new IllegalStateException();
            } else if (cond instanceof Ir.All a) {
                throw new IllegalStateException();
            } else if (cond instanceof Ir.Any a) {
                throw new IllegalStateException();
            }
            mv.visitJumpInsn(jumpIf ? Opcodes.IFNE : Opcodes.IFEQ, target);
        }

        private int constant(BlockState state) {
            Integer i = constantIndex.get(state);
            if (i == null) {
                i = constants.size();
                constants.add(state);
                if (state != null) {
                    constantIndex.put(state, i);
                }
            }
            return i;
        }

        private final Map<Ir.Rule, Integer> sizes = new IdentityHashMap<>();

        private int size(Ir.Rule rule) {
            Integer cached = sizes.get(rule);
            if (cached != null) {
                return cached;
            }
            int n;
            if (rule instanceof Ir.Seq seq) {
                int sum = 1;
                for (Ir.Rule r : seq.rules()) {
                    sum += size(r);
                }
                n = sum;
            } else if (rule instanceof Ir.Test t) {
                n = 1 + condSize(t.cond()) + size(t.then());
            } else {
                n = 1;
            }
            sizes.put(rule, n);
            return n;
        }

        private static int condSize(Ir.Cond c) {
            if (c instanceof Ir.Not n) {
                return 1 + condSize(n.target());
            } else if (c instanceof Ir.All a) {
                return 1 + condsSize(a.conds());
            } else if (c instanceof Ir.Any a) {
                return 1 + condsSize(a.conds());
            } else {
                return 1;
            }
        }

        private static int condsSize(List<Ir.Cond> conds) {
            int sum = 0;
            for (Ir.Cond c : conds) {
                sum += condSize(c);
            }
            return sum;
        }

        private static void push(MethodVisitor mv, int v) {
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
}
