package dev.denismasterherobrine.generatoraccelerator2.dfc.ir;

import java.util.List;

/**
 * Integer-typed IR lane, produced only by the bytecode lifter for modded density functions that
 * do integer math on block coordinates. Every operation maps to exactly one JVM instruction (or
 * one whitelisted static call), so evaluation is identical to the original bytecode.
 */
public sealed interface INode permits INode.IntConst, INode.IntCoord, INode.IntBin, INode.IntNeg, INode.DoubleToInt, INode.IntStaticCall, INode.SelectInt {
    enum IntOp { ADD, SUB, MUL, DIV, REM, SHL, SHR, USHR, AND, OR, XOR }

    record IntConst(int value) implements INode {}

    /** {@code ctx.blockX()} (0), {@code blockY()} (1) or {@code blockZ()} (2). */
    record IntCoord(int axis) implements INode {}

    record IntBin(IntOp op, INode a, INode b) implements INode {}

    record IntNeg(INode a) implements INode {}

    /** {@code (int) d}, JVM {@code d2i} semantics. */
    record DoubleToInt(Node value) implements INode {}

    /** Whitelisted pure static method returning int, e.g. {@code Mth.floor(D)I}. Args are doubles or ints per the descriptor. */
    record IntStaticCall(String owner, String name, String desc, List<Object> args) implements INode {}

    /** Integer-valued conditional, see {@link Node.Select}. */
    record SelectInt(Node.Cond cond, INode whenJump, INode whenFall) implements INode {}
}
