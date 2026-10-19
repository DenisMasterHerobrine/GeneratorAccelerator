package dev.denismasterherobrine.generatoraccelerator2.dfc.lift;

import dev.denismasterherobrine.generatoraccelerator2.api.DensityFunctionLowering;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.INode;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Lifts the {@code compute(FunctionContext)} bytecode of a non-vanilla density function into IR
 * by symbolic execution. Supported: double/int arithmetic and conversions, final-field reads on
 * the instance (child density functions, noise holders, numeric constants), coordinate reads,
 * child {@code compute} calls, noise {@code getValue} calls, whitelisted pure static methods,
 * private helper calls on the same instance (inlined), and structured conditionals (if/else,
 * ternaries, short-circuit chains) recovered through post-dominators. Loops, allocations,
 * writes, exceptions, switches and anything else abort with a reason.
 *
 * <p>The result is never trusted on its own: {@link LiftValidator} compares it bit for bit with
 * the real object before the compiler accepts it.
 */
public final class BytecodeLifter {
    private static final String CTX = "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";
    private static final String DF = "net/minecraft/world/level/levelgen/DensityFunction";
    private static final String NOISE_HOLDER = "net/minecraft/world/level/levelgen/DensityFunction$NoiseHolder";
    private static final String NORMAL_NOISE = "net/minecraft/world/level/levelgen/synth/NormalNoise";
    private static final String HOLDER = "net/minecraft/core/Holder";
    /** {@code DensityFunction.compute}, as the running game names it ({@link Names}). Looked up in the classes' bytes. */
    private static final String COMPUTE_DESC = "(L" + CTX + ";)D";
    private static final String RUNTIME_COMPUTE = Names.runtimeMethod(DF, "compute", COMPUTE_DESC);
    private static final String RUNTIME_COMPUTE_DESC = Names.runtimeDesc(COMPUTE_DESC);
    private static final int MAX_INLINE_DEPTH = 8;
    private static final Map<String, ClassNode> CLASS_CACHE = new ConcurrentHashMap<>();

    /** Symbolic value: a double node, an int node, or a reference to a concrete object (this, ctx, field values). */
    private sealed interface Val permits DVal, IVal, Ref {}

    private record DVal(Node node) implements Val {}

    private record IVal(INode node) implements Val {}

    /** {@code obj} is the actual runtime object (resolved reflectively). {@code node} is set when it is a lowered density function. */
    private record Ref(Object obj, Node node) implements Val {}

    private static final Object CTX_MARK = new Object();

    private final DensityFunctionLowering.Context lowering;
    private final DensityFunction root;
    private int depth;

    private BytecodeLifter(DensityFunctionLowering.Context lowering, DensityFunction root) {
        this.lowering = lowering;
        this.root = root;
    }

    /** @throws LiftException with the reason when the method uses something outside the supported subset */
    public static Node lift(DensityFunction fn, DensityFunctionLowering.Context lowering) {
        return new BytecodeLifter(lowering, fn).liftCompute(fn);
    }

    private Node liftCompute(Object instance) {
        MethodNode method = findMethod(instance.getClass(), RUNTIME_COMPUTE, RUNTIME_COMPUTE_DESC);
        if (method == null) {
            throw new LiftException("no compute(FunctionContext) method found in " + Names.mojangName(instance.getClass()) + " or its superclasses");
        }
        Val[] locals = new Val[method.maxLocals + 2];
        locals[0] = new Ref(instance, null);
        locals[1] = new Ref(CTX_MARK, null);
        return liftBody(method, locals);
    }

    // ---------------------------------------------------------------- class loading

    private static MethodNode findMethod(Class<?> type, String name, String desc) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            ClassNode cn = classNode(c);
            if (cn == null) {
                continue;
            }
            for (MethodNode m : cn.methods) {
                if (m.name.equals(name) && m.desc.equals(desc) && (m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                    return m;
                }
            }
        }
        return null;
    }

    private static ClassNode classNode(Class<?> c) {
        return CLASS_CACHE.computeIfAbsent(c.getName(), k -> {
            String path = "/" + c.getName().replace('.', '/') + ".class";
            try (InputStream in = c.getResourceAsStream(path)) {
                if (in == null) {
                    return null;
                }
                ClassNode cn = new ClassNode();
                new ClassReader(in.readAllBytes()).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                return cn;
            } catch (Exception e) {
                return null;
            }
        });
    }

    // ---------------------------------------------------------------- structured execution

    private static final class Body {
        final InsnList insns;
        final int[] ipdom;      // immediate post-dominator index, -1 for exits
        final int exit;         // virtual exit index == insns.size()

        Body(InsnList insns, int[] ipdom) {
            this.insns = insns;
            this.ipdom = ipdom;
            this.exit = insns.size();
        }
    }

    private Node liftBody(MethodNode method, Val[] locals) {
        if (!method.tryCatchBlocks.isEmpty()) {
            throw new LiftException("try/catch in " + method.name);
        }
        Body body = new Body(method.instructions, PostDominators.compute(method.instructions));
        State st = new State(locals);
        Node result = run(body, 0, body.exit, st);
        if (result == null) {
            throw new LiftException("method " + method.name + " did not end in dreturn");
        }
        return result;
    }

    private static final class State {
        final Deque<Val> stack = new ArrayDeque<>();
        Val[] locals;

        State(Val[] locals) {
            this.locals = locals;
        }

        State copy() {
            State s = new State(locals.clone());
            s.stack.addAll(stack);
            return s;
        }
    }

    /**
     * Executes from {@code pc} until {@code stop} (exclusive) or a {@code dreturn}. Returns the returned
     * node, or {@code null} when execution reached {@code stop} with the state left in {@code st}.
     */
    private Node run(Body body, int pc, int stop, State st) {
        InsnList insns = body.insns;
        while (pc < stop) {
            AbstractInsnNode in = insns.get(pc);
            int op = in.getOpcode();
            if (op < 0) { // labels, line numbers, frames
                pc++;
                continue;
            }
            switch (op) {
                case Opcodes.DRETURN -> {
                    Val v = st.stack.pop();
                    return dbl(v, "dreturn");
                }
                case Opcodes.IRETURN, Opcodes.ARETURN, Opcodes.RETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.ATHROW ->
                    throw new LiftException("unsupported return/throw at " + pc);
                case Opcodes.GOTO -> {
                    int target = index(insns, ((JumpInsnNode) in).label);
                    if (target <= pc) {
                        throw new LiftException("loop (backward jump) at " + pc);
                    }
                    if (target == stop) {
                        return null;
                    }
                    pc = target;
                    continue;
                }
                case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE,
                     Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE -> {
                    Branch b = branch(body, pc, stop, st);
                    if (b.ret() != null) {
                        return b.ret();
                    }
                    pc = b.resume();
                    continue;
                }
                case Opcodes.IFNULL, Opcodes.IFNONNULL, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE, Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH, Opcodes.JSR, Opcodes.RET ->
                    throw new LiftException("unsupported control flow " + opName(op) + " at " + pc);
                default -> step(body, pc, in, st);
            }
            pc++;
        }
        return null;
    }

    /** A lifted conditional: the returned node when both arms return, otherwise null and the pc to resume at. */
    private record Branch(Node ret, int resume) {}

    /**
     * Handles a conditional at {@code pc}. Executes both arms to the join point and merges them with selects.
     *
     * <p>The join point is the conditional's immediate post-dominator, clamped to {@code stop}. When the arms cannot be
     * merged there (for example they leave different references in a slot), both arms run on to {@code stop} instead.
     * Each arm then lifts its own copy of the code after the join, and the arms meet in one select at the return.
     */
    private Branch branch(Body body, int pc, int stop, State st) {
        JumpInsnNode jump = (JumpInsnNode) body.insns.get(pc);
        int target = index(body.insns, jump.label);
        if (target <= pc) {
            throw new LiftException("loop (backward conditional) at " + pc);
        }
        int join = body.ipdom[pc];
        if (join < 0 || join > stop) {
            join = stop;
        }
        Node.Cond cond = condition(jump.getOpcode(), st);
        if (join < stop) {
            try {
                return branchTo(body, pc, target, join, cond, st);
            } catch (LiftException e) {
                // Not mergeable at the join. Duplicate the tail into both arms below.
            }
        }
        return branchTo(body, pc, target, stop, cond, st);
    }

    /** Runs both arms of the conditional at {@code pc} to {@code join}. Changes {@code st} only when the arms merge. */
    private Branch branchTo(Body body, int pc, int target, int join, Node.Cond cond, State st) {
        State fall = st.copy();
        State taken = st.copy();
        Node retFall = run(body, pc + 1, join, fall);
        Node retTaken = run(body, target, join, taken);
        if (retFall != null || retTaken != null) {
            if (retFall == null || retTaken == null) {
                throw new LiftException("one arm of the conditional at " + pc + " returns and the other falls through");
            }
            return new Branch(lowering.intern(new Node.Select(cond, retTaken, retFall)), join);
        }
        // Merge states. Identical slots stay. Differing doubles and ints become selects. Differing refs are unsupported.
        if (fall.stack.size() != taken.stack.size()) {
            throw new LiftException("stack height differs across the conditional at " + pc);
        }
        Val[] fs = fall.stack.toArray(new Val[0]);
        Val[] ts = taken.stack.toArray(new Val[0]);
        Val[] merged = new Val[fs.length];
        for (int i = 0; i < fs.length; i++) {
            merged[i] = merge(cond, ts[i], fs[i], pc);
        }
        Val[] locals = new Val[st.locals.length];
        for (int i = 0; i < locals.length; i++) {
            Val a = taken.locals[i];
            Val b = fall.locals[i];
            if (a == null || b == null) {
                locals[i] = a == null ? b : a; // Only one arm assigns it. The value is never read on the other path.
            } else {
                locals[i] = merge(cond, a, b, pc);
            }
        }
        // Everything merged. Commit to st. Deque.toArray gives head (top) first, so push from the bottom up.
        st.stack.clear();
        for (int i = merged.length - 1; i >= 0; i--) {
            st.stack.push(merged[i]);
        }
        st.locals = locals;
        return new Branch(null, join);
    }

    private Val merge(Node.Cond cond, Val taken, Val fall, int pc) {
        if (taken.equals(fall)) {
            return taken;
        }
        if (taken instanceof DVal a && fall instanceof DVal b) {
            return new DVal(lowering.intern(new Node.Select(cond, a.node(), b.node())));
        }
        if (taken instanceof IVal a && fall instanceof IVal b) {
            return new IVal(new INode.SelectInt(cond, a.node(), b.node()));
        }
        throw new LiftException("conditional at " + pc + " selects between references or mixed types");
    }

    private Node.Cond condition(int op, State st) {
        switch (op) {
            case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE -> {
                INode b = integer(st.stack.pop(), "if_icmp");
                INode a = integer(st.stack.pop(), "if_icmp");
                return new Node.Cond(Node.CmpKind.ICMP, jumpOp(op), null, null, a, b);
            }
            default -> {
                // if<op> consumes an int: either a dcmpl/dcmpg result (tracked as a CmpVal) or a plain int compared to zero.
                Val v = st.stack.pop();
                if (v instanceof IVal iv && iv.node() instanceof INode.IntStaticCall cmp && cmp.owner().equals("$cmp")) {
                    Node.CmpKind kind = cmp.name().equals("dcmpl") ? Node.CmpKind.DCMPL : Node.CmpKind.DCMPG;
                    return new Node.Cond(kind, jumpOp(op), (Node) cmp.args().get(0), (Node) cmp.args().get(1), null, null);
                }
                return new Node.Cond(Node.CmpKind.ICMP, jumpOp(op), null, null, integer(v, "if<op>"), new INode.IntConst(0));
            }
        }
    }

    private static Node.JumpOp jumpOp(int op) {
        return switch (op) {
            case Opcodes.IFEQ, Opcodes.IF_ICMPEQ -> Node.JumpOp.EQ;
            case Opcodes.IFNE, Opcodes.IF_ICMPNE -> Node.JumpOp.NE;
            case Opcodes.IFLT, Opcodes.IF_ICMPLT -> Node.JumpOp.LT;
            case Opcodes.IFGE, Opcodes.IF_ICMPGE -> Node.JumpOp.GE;
            case Opcodes.IFGT, Opcodes.IF_ICMPGT -> Node.JumpOp.GT;
            case Opcodes.IFLE, Opcodes.IF_ICMPLE -> Node.JumpOp.LE;
            default -> throw new LiftException("not a conditional jump: " + op);
        };
    }

    // ---------------------------------------------------------------- straight-line instructions

    private void step(Body body, int pc, AbstractInsnNode in, State st) {
        int op = in.getOpcode();
        Deque<Val> s = st.stack;
        switch (op) {
            case Opcodes.NOP -> { }
            case Opcodes.ACONST_NULL -> throw new LiftException("null constant at " + pc);
            case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2, Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5 ->
                s.push(new IVal(new INode.IntConst(op - Opcodes.ICONST_0)));
            case Opcodes.DCONST_0 -> s.push(new DVal(lowering.intern(new Node.Const(0.0))));
            case Opcodes.DCONST_1 -> s.push(new DVal(lowering.intern(new Node.Const(1.0))));
            case Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 -> s.push(new DVal(lowering.intern(new Node.Const((double) (op - Opcodes.FCONST_0)))));
            case Opcodes.BIPUSH, Opcodes.SIPUSH -> s.push(new IVal(new INode.IntConst(((IntInsnNode) in).operand)));
            case Opcodes.LDC -> {
                Object c = ((LdcInsnNode) in).cst;
                if (c instanceof Double d) {
                    s.push(new DVal(lowering.intern(new Node.Const(d))));
                } else if (c instanceof Float f) {
                    s.push(new DVal(lowering.intern(new Node.Const((double) f))));
                } else if (c instanceof Integer i) {
                    s.push(new IVal(new INode.IntConst(i)));
                } else {
                    throw new LiftException("unsupported constant " + c + " at " + pc);
                }
            }
            case Opcodes.ILOAD, Opcodes.DLOAD, Opcodes.FLOAD, Opcodes.ALOAD -> {
                Val v = st.locals[((VarInsnNode) in).var];
                if (v == null) {
                    throw new LiftException("read of unassigned local " + ((VarInsnNode) in).var + " at " + pc);
                }
                s.push(v);
            }
            case Opcodes.ISTORE, Opcodes.DSTORE, Opcodes.FSTORE, Opcodes.ASTORE -> st.locals[((VarInsnNode) in).var] = s.pop();
            case Opcodes.LLOAD, Opcodes.LSTORE -> throw new LiftException("long arithmetic at " + pc);
            case Opcodes.POP -> s.pop();
            case Opcodes.POP2 -> {
                Val v = s.pop();
                if (!(v instanceof DVal)) {
                    s.pop();
                }
            }
            case Opcodes.DUP -> s.push(s.peek());
            case Opcodes.DUP2 -> {
                Val v = s.peek();
                if (v instanceof DVal) {
                    s.push(v);
                } else {
                    Val a = s.pop();
                    Val b = s.pop();
                    s.push(b); s.push(a); s.push(b); s.push(a);
                }
            }
            case Opcodes.SWAP -> {
                Val a = s.pop();
                Val b = s.pop();
                s.push(a); s.push(b);
            }
            case Opcodes.DUP_X1, Opcodes.DUP_X2, Opcodes.DUP2_X1, Opcodes.DUP2_X2 -> throw new LiftException("stack shuffle " + opName(op) + " at " + pc);
            case Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV, Opcodes.DREM -> {
                Node b = dbl(s.pop(), opName(op));
                Node a = dbl(s.pop(), opName(op));
                s.push(new DVal(arith(op, a, b)));
            }
            case Opcodes.DNEG -> s.push(new DVal(arith(Opcodes.DSUB, lowering.intern(new Node.Const(-0.0)), dbl(s.pop(), "dneg"))));
            case Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR, Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR -> {
                INode b = integer(s.pop(), opName(op));
                INode a = integer(s.pop(), opName(op));
                s.push(new IVal(new INode.IntBin(intOp(op), a, b)));
            }
            case Opcodes.INEG -> s.push(new IVal(new INode.IntNeg(integer(s.pop(), "ineg"))));
            case Opcodes.IINC -> {
                IincInsnNode ii = (IincInsnNode) in;
                st.locals[ii.var] = new IVal(new INode.IntBin(INode.IntOp.ADD, integer(st.locals[ii.var], "iinc"), new INode.IntConst(ii.incr)));
            }
            case Opcodes.I2D -> s.push(new DVal(lowering.intern(new Node.IntToDouble(integer(s.pop(), "i2d")))));
            case Opcodes.D2I -> s.push(new IVal(new INode.DoubleToInt(dbl(s.pop(), "d2i"))));
            case Opcodes.D2F -> {
                // Only the round trip (double)(float) is representable. Require the next real instruction to be f2d.
                AbstractInsnNode next = nextReal(body.insns, pc);
                if (next == null || next.getOpcode() != Opcodes.F2D) {
                    throw new LiftException("float arithmetic (d2f not followed by f2d) at " + pc);
                }
                s.push(new DVal(lowering.intern(new Node.RoundFloat(dbl(s.pop(), "d2f")))));
            }
            case Opcodes.F2D -> {
                AbstractInsnNode prev = prevReal(body.insns, pc);
                if (prev == null || prev.getOpcode() != Opcodes.D2F) {
                    // float value from a float constant or lerpInt-style call: our DVal already holds the double of that float
                    Val v = s.pop();
                    s.push(new DVal(dbl(v, "f2d")));
                }
                // after a d2f the value is already a RoundFloat node
            }
            case Opcodes.I2F -> s.push(new DVal(lowering.intern(new Node.RoundFloat(lowering.intern(new Node.IntToDouble(integer(s.pop(), "i2f")))))));
            case Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM, Opcodes.FNEG, Opcodes.F2I, Opcodes.L2D, Opcodes.D2L, Opcodes.I2L, Opcodes.L2I, Opcodes.I2B, Opcodes.I2C, Opcodes.I2S ->
                throw new LiftException("unsupported conversion/arithmetic " + opName(op) + " at " + pc);
            case Opcodes.DCMPL, Opcodes.DCMPG -> {
                Node b = dbl(s.pop(), opName(op));
                Node a = dbl(s.pop(), opName(op));
                // Carried as a pseudo int call so the following if<op> can turn it into a condition.
                s.push(new IVal(new INode.IntStaticCall("$cmp", op == Opcodes.DCMPL ? "dcmpl" : "dcmpg", "(DD)I", List.of(a, b))));
            }
            case Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.LCMP -> throw new LiftException("unsupported compare " + opName(op) + " at " + pc);
            case Opcodes.GETFIELD -> {
                FieldInsnNode f = (FieldInsnNode) in;
                Val recv = s.pop();
                if (!(recv instanceof Ref r) || r.obj() == CTX_MARK) {
                    throw new LiftException("field read on a non-constant receiver at " + pc);
                }
                s.push(resolveField(r.obj(), f, pc));
            }
            case Opcodes.GETSTATIC -> {
                FieldInsnNode f = (FieldInsnNode) in;
                s.push(resolveField(null, f, pc));
            }
            case Opcodes.PUTFIELD, Opcodes.PUTSTATIC, Opcodes.NEW, Opcodes.NEWARRAY, Opcodes.ANEWARRAY, Opcodes.MULTIANEWARRAY,
                 Opcodes.MONITORENTER, Opcodes.MONITOREXIT, Opcodes.INSTANCEOF, Opcodes.ARRAYLENGTH ->
                throw new LiftException("unsupported instruction " + opName(op) + " at " + pc);
            case Opcodes.DALOAD, Opcodes.FALOAD, Opcodes.IALOAD -> {
                Val idx = s.pop();
                Val arr = s.pop();
                if (!(arr instanceof Ref r) || !(idx instanceof IVal iv) || !(iv.node() instanceof INode.IntConst ic)) {
                    throw new LiftException("array read with a non-constant index at " + pc);
                }
                Object a = r.obj();
                if (a instanceof double[] da) {
                    s.push(new DVal(lowering.intern(new Node.Const(da[ic.value()]))));
                } else if (a instanceof float[] fa) {
                    s.push(new DVal(lowering.intern(new Node.Const((double) fa[ic.value()]))));
                } else if (a instanceof int[] ia) {
                    s.push(new IVal(new INode.IntConst(ia[ic.value()])));
                } else {
                    throw new LiftException("array read on " + (a == null ? "null" : Names.mojangSimpleName(a.getClass())) + " at " + pc);
                }
            }
            case Opcodes.AALOAD -> {
                Val idx = s.pop();
                Val arr = s.pop();
                if (!(arr instanceof Ref r) || !(idx instanceof IVal iv) || !(iv.node() instanceof INode.IntConst ic) || !(r.obj() instanceof Object[] oa)) {
                    throw new LiftException("object array read with a non-constant index at " + pc);
                }
                s.push(ref(oa[ic.value()], pc));
            }
            case Opcodes.CHECKCAST -> {
                // Keep the value. The cast either holds at runtime, or the original would have thrown too.
                TypeInsnNode t = (TypeInsnNode) in;
                Val v = s.peek();
                if (!(v instanceof Ref)) {
                    throw new LiftException("checkcast to " + t.desc + " on a primitive at " + pc);
                }
            }
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE, Opcodes.INVOKESTATIC, Opcodes.INVOKESPECIAL -> invoke(body, pc, (MethodInsnNode) in, st);
            case Opcodes.INVOKEDYNAMIC -> throw new LiftException("invokedynamic at " + pc);
            default -> throw new LiftException("unsupported instruction " + opName(op) + " at " + pc);
        }
    }

    private Node arith(int op, Node a, Node b) {
        // Every op becomes a StaticCall on the pseudo owner "$op". The emitter writes it back as the plain instruction (dadd, dsub, ...).
        return switch (op) {
            case Opcodes.DADD -> lowering.intern(new Node.StaticCall("$op", "dadd", "(DD)D", List.of(a, b)));
            case Opcodes.DSUB -> lowering.intern(new Node.StaticCall("$op", "dsub", "(DD)D", List.of(a, b)));
            case Opcodes.DMUL -> lowering.intern(new Node.StaticCall("$op", "dmul", "(DD)D", List.of(a, b)));
            case Opcodes.DDIV -> lowering.intern(new Node.StaticCall("$op", "ddiv", "(DD)D", List.of(a, b)));
            case Opcodes.DREM -> lowering.intern(new Node.StaticCall("$op", "drem", "(DD)D", List.of(a, b)));
            default -> throw new LiftException("not an arithmetic op " + op);
        };
    }

    private static INode.IntOp intOp(int op) {
        return switch (op) {
            case Opcodes.IADD -> INode.IntOp.ADD;
            case Opcodes.ISUB -> INode.IntOp.SUB;
            case Opcodes.IMUL -> INode.IntOp.MUL;
            case Opcodes.IDIV -> INode.IntOp.DIV;
            case Opcodes.IREM -> INode.IntOp.REM;
            case Opcodes.ISHL -> INode.IntOp.SHL;
            case Opcodes.ISHR -> INode.IntOp.SHR;
            case Opcodes.IUSHR -> INode.IntOp.USHR;
            case Opcodes.IAND -> INode.IntOp.AND;
            case Opcodes.IOR -> INode.IntOp.OR;
            default -> INode.IntOp.XOR;
        };
    }

    // ---------------------------------------------------------------- calls

    private void invoke(Body body, int pc, MethodInsnNode m, State st) {
        Deque<Val> s = st.stack;
        Type[] argTypes = Type.getArgumentTypes(m.desc);
        if (m.getOpcode() == Opcodes.INVOKESTATIC) {
            if (!StaticCalls.allowed(m.owner, m.name, m.desc)) {
                throw new LiftException("call to non-whitelisted static method " + m.owner + "." + m.name + m.desc + " at " + pc);
            }
            List<Object> args = popArgs(s, argTypes, pc);
            char ret = StaticCalls.returnSort(m.desc);
            if (ret == 'D') {
                s.push(new DVal(lowering.intern(new Node.StaticCall(m.owner, m.name, m.desc, args))));
            } else if (ret == 'I') {
                s.push(new IVal(new INode.IntStaticCall(m.owner, m.name, m.desc, args)));
            } else {
                throw new LiftException("static call with unsupported return type at " + pc);
            }
            return;
        }
        // Instance call: pop args then receiver.
        List<Object> args = popArgs(s, argTypes, pc);
        Val recv = s.pop();
        if (!(recv instanceof Ref r)) {
            throw new LiftException("instance call on a primitive at " + pc);
        }
        // The calls recognised below are matched by their Mojang names. The bytes have the running game's names.
        String name = Names.mojangMethod(m.name);
        String desc = Names.mojangDesc(m.desc);
        if (r.obj() == CTX_MARK) {
            switch (name) {
                case "blockX" -> s.push(new IVal(new INode.IntCoord(0)));
                case "blockY" -> s.push(new IVal(new INode.IntCoord(1)));
                case "blockZ" -> s.push(new IVal(new INode.IntCoord(2)));
                default -> throw new LiftException("context call " + m.name + " at " + pc);
            }
            return;
        }
        Object obj = r.obj();
        if (name.equals("compute") && desc.equals(COMPUTE_DESC) && obj instanceof DensityFunction child) {
            Object ctxArg = args.get(0);
            if (!(ctxArg instanceof Ref cr) || cr.obj() != CTX_MARK) {
                throw new LiftException("child compute called with a different context at " + pc);
            }
            if (child == root) {
                throw new LiftException("recursive compute at " + pc);
            }
            s.push(new DVal(lowering.lower(child)));
            return;
        }
        if (name.equals("getValue") && desc.equals("(DDD)D") && (obj instanceof DensityFunction.NoiseHolder || obj instanceof NormalNoise)) {
            int spec;
            double max;
            if (obj instanceof DensityFunction.NoiseHolder h) {
                if (h.noise() == null) {
                    s.push(new DVal(lowering.intern(new Node.Const(0.0))));
                    return;
                }
                spec = lowering.noiseSpec(h);
                max = h.maxValue();
            } else {
                NormalNoise n = (NormalNoise) obj;
                spec = lowering.noiseSpec(new DensityFunction.NoiseHolder(null, n));
                max = n.maxValue();
            }
            s.push(new DVal(lowering.intern(new Node.NoiseAt(spec, (Node) args.get(0), (Node) args.get(1), (Node) args.get(2), max))));
            return;
        }
        if (name.equals("value") && obj instanceof Holder<?> h) {
            s.push(ref(h.value(), pc));
            return;
        }
        if (name.equals("maxValue") && desc.equals("()D") && obj instanceof DensityFunction.NoiseHolder h) {
            s.push(new DVal(lowering.intern(new Node.Const(h.maxValue()))));
            return;
        }
        if ((name.equals("minValue") || name.equals("maxValue")) && desc.equals("()D") && obj instanceof DensityFunction df && obj != root) {
            s.push(new DVal(lowering.intern(new Node.Const(name.equals("minValue") ? df.minValue() : df.maxValue()))));
            return;
        }
        // Helper method on the instance (or its superclass): inline it.
        if (obj == root || (st.locals[0] instanceof Ref self && self.obj() == obj)) {
            if (name.equals("compute")) {
                throw new LiftException("recursive compute at " + pc);
            }
            MethodNode helper = findMethod(obj.getClass(), m.name, m.desc);
            if (helper == null) {
                throw new LiftException("helper " + m.name + m.desc + " not found at " + pc);
            }
            if (++depth > MAX_INLINE_DEPTH) {
                throw new LiftException("helper inlining too deep at " + pc);
            }
            try {
                Val[] locals = new Val[helper.maxLocals + 2];
                locals[0] = new Ref(obj, null);
                int slot = 1;
                for (int i = 0; i < argTypes.length; i++) {
                    Object a = args.get(i);
                    locals[slot] = a instanceof Node n ? new DVal(n) : a instanceof INode in ? new IVal(in) : (Val) a;
                    slot += argTypes[i].getSize();
                }
                if (!helper.tryCatchBlocks.isEmpty()) {
                    throw new LiftException("try/catch in helper " + m.name);
                }
                FieldInsnNode getter = trivialGetter(helper);
                if (getter != null) {
                    // A record accessor or plain getter. Tectonic's functions read their argument through input().
                    // Push the field read itself.
                    s.push(resolveField(obj, getter, pc));
                    return;
                }
                Body hb = new Body(helper.instructions, PostDominators.compute(helper.instructions));
                State hs = new State(locals);
                char ret = StaticCalls.returnSort(m.desc);
                if (ret != 'D') {
                    throw new LiftException("helper " + m.name + " returns " + Type.getReturnType(m.desc) + " (only double helpers are inlined)");
                }
                Node result = run(hb, 0, hb.exit, hs);
                if (result == null) {
                    throw new LiftException("helper " + m.name + " did not return a value");
                }
                s.push(new DVal(result));
            } finally {
                depth--;
            }
            return;
        }
        throw new LiftException("call " + m.owner + "." + m.name + m.desc + " on " + Names.mojangSimpleName(obj.getClass()) + " at " + pc);
    }

    /** The field a method returns when its whole body is {@code return this.field;}, or null. */
    private static FieldInsnNode trivialGetter(MethodNode method) {
        AbstractInsnNode[] real = new AbstractInsnNode[3];
        int n = 0;
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() < 0) {
                continue; // labels, line numbers, frames
            }
            if (n == 3) {
                return null;
            }
            real[n++] = in;
        }
        if (n != 3 || real[0].getOpcode() != Opcodes.ALOAD || ((VarInsnNode) real[0]).var != 0 || real[1].getOpcode() != Opcodes.GETFIELD) {
            return null;
        }
        int ret = real[2].getOpcode();
        return ret >= Opcodes.IRETURN && ret <= Opcodes.ARETURN ? (FieldInsnNode) real[1] : null;
    }

    private List<Object> popArgs(Deque<Val> s, Type[] argTypes, int pc) {
        Object[] out = new Object[argTypes.length];
        for (int i = argTypes.length - 1; i >= 0; i--) {
            Val v = s.pop();
            switch (argTypes[i].getSort()) {
                case Type.DOUBLE -> out[i] = dbl(v, "argument");
                case Type.INT -> out[i] = integer(v, "argument");
                case Type.FLOAT -> out[i] = dbl(v, "float argument");
                case Type.OBJECT, Type.ARRAY -> {
                    if (!(v instanceof Ref)) {
                        throw new LiftException("primitive passed as object at " + pc);
                    }
                    out[i] = v;
                }
                default -> throw new LiftException("unsupported argument type at " + pc);
            }
        }
        return Arrays.asList(out);
    }

    // ---------------------------------------------------------------- fields

    private Val resolveField(Object receiver, FieldInsnNode f, int pc) {
        Class<?> owner;
        try {
            owner = Class.forName(f.owner.replace('/', '.'), false, (receiver != null ? receiver.getClass() : root.getClass()).getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            throw new LiftException("cannot load field owner " + f.owner + " at " + pc);
        }
        Field field = null;
        for (Class<?> c = owner; c != null && field == null; c = c.getSuperclass()) {
            try {
                field = c.getDeclaredField(f.name);
            } catch (NoSuchFieldException ignored) {
                // keep looking in superclasses
            } catch (LinkageError e) {
                // a field's type is a class this side does not have (a client class on a dedicated server)
                throw new LiftException("cannot resolve the fields of " + c.getName() + " at " + pc);
            }
        }
        if (field == null) {
            throw new LiftException("field " + f.owner + "." + f.name + " not found at " + pc);
        }
        if (!Modifier.isFinal(field.getModifiers())) {
            throw new LiftException("non-final field " + f.owner + "." + f.name + " (its value could change after compilation) at " + pc);
        }
        Object value;
        try {
            field.setAccessible(true);
            value = field.get(receiver);
        } catch (Exception e) {
            throw new LiftException("cannot read field " + f.owner + "." + f.name + ": " + e.getMessage());
        }
        return switch (f.desc) {
            case "D" -> new DVal(lowering.intern(new Node.Const((Double) value)));
            case "F" -> new DVal(lowering.intern(new Node.Const((double) (Float) value)));
            case "I", "S", "B", "C" -> new IVal(new INode.IntConst(((Number) value).intValue()));
            case "Z" -> new IVal(new INode.IntConst((Boolean) value ? 1 : 0));
            case "J" -> throw new LiftException("long field " + f.name + " at " + pc);
            default -> ref(value, pc);
        };
    }

    private Val ref(Object value, int pc) {
        if (value == null) {
            throw new LiftException("null reference at " + pc);
        }
        if (value instanceof DensityFunction df && df != root) {
            return new Ref(df, null); // lowered lazily when compute() is called on it
        }
        return new Ref(value, null);
    }

    // ---------------------------------------------------------------- helpers

    private static Node dbl(Val v, String what) {
        if (v instanceof DVal d) {
            return d.node();
        }
        throw new LiftException(what + " expected a double value");
    }

    private static INode integer(Val v, String what) {
        if (v instanceof IVal i) {
            return i.node();
        }
        throw new LiftException(what + " expected an int value");
    }

    static int index(InsnList insns, LabelNode label) {
        return insns.indexOf(label);
    }

    private static AbstractInsnNode nextReal(InsnList insns, int pc) {
        for (int i = pc + 1; i < insns.size(); i++) {
            if (insns.get(i).getOpcode() >= 0) {
                return insns.get(i);
            }
        }
        return null;
    }

    private static AbstractInsnNode prevReal(InsnList insns, int pc) {
        for (int i = pc - 1; i >= 0; i--) {
            if (insns.get(i).getOpcode() >= 0) {
                return insns.get(i);
            }
        }
        return null;
    }

    private static String opName(int op) {
        return org.objectweb.asm.util.Printer.OPCODES[op];
    }
}
