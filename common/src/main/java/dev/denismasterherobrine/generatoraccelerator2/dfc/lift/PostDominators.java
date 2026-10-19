package dev.denismasterherobrine.generatoraccelerator2.dfc.lift;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;

/**
 * Immediate post-dominators over an instruction list, used to find the join point of a
 * conditional. A virtual exit node (index == size) post-dominates every return.
 */
final class PostDominators {
    private PostDominators() {}

    static int[] compute(InsnList insns) {
        int n = insns.size();
        int exit = n;
        List<List<Integer>> succ = new ArrayList<>(n + 1);
        for (int i = 0; i <= n; i++) {
            succ.add(new ArrayList<>(2));
        }
        for (int i = 0; i < n; i++) {
            AbstractInsnNode in = insns.get(i);
            int op = in.getOpcode();
            List<Integer> s = succ.get(i);
            if (op < 0) {
                s.add(i + 1);
                continue;
            }
            switch (op) {
                case Opcodes.GOTO -> s.add(insns.indexOf(((JumpInsnNode) in).label));
                case Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN, Opcodes.RETURN, Opcodes.ATHROW -> s.add(exit);
                case Opcodes.TABLESWITCH -> {
                    TableSwitchInsnNode t = (TableSwitchInsnNode) in;
                    s.add(insns.indexOf(t.dflt));
                    t.labels.forEach(l -> s.add(insns.indexOf(l)));
                }
                case Opcodes.LOOKUPSWITCH -> {
                    LookupSwitchInsnNode t = (LookupSwitchInsnNode) in;
                    s.add(insns.indexOf(t.dflt));
                    t.labels.forEach(l -> s.add(insns.indexOf(l)));
                }
                default -> {
                    if (in instanceof JumpInsnNode j) {
                        s.add(i + 1);
                        s.add(insns.indexOf(j.label));
                    } else {
                        s.add(i + 1);
                    }
                }
            }
        }
        // Iterative data-flow on the reverse graph: pdom(v) = {v} ∪ ∩ pdom(succ). Represented as bitsets of ints.
        int words = (n + 1 + 63) >>> 6;
        long[][] pdom = new long[n + 1][words];
        for (int i = 0; i < n; i++) {
            Arrays.fill(pdom[i], -1L);
        }
        set(pdom[exit], exit);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = n - 1; i >= 0; i--) {
                long[] acc = new long[words];
                Arrays.fill(acc, -1L);
                for (int sidx : succ.get(i)) {
                    long[] sp = pdom[sidx];
                    for (int w = 0; w < words; w++) {
                        acc[w] &= sp[w];
                    }
                }
                set(acc, i);
                if (!Arrays.equals(acc, pdom[i])) {
                    pdom[i] = acc;
                    changed = true;
                }
            }
        }
        // Immediate post-dominator: the strict post-dominator closest to v. The strict post-dominators of v form a chain
        // ipdom(v) -> ... -> exit, and each one's pdom set contains every later one. The closest is therefore the
        // candidate with the largest pdom set. The exit's set holds only the exit, so picking the smallest set would
        // always answer the exit. Only forward candidates are scanned, because the lifter rejects backward jumps.
        int[] ipdom = new int[n];
        for (int v = 0; v < n; v++) {
            int best = -1;
            int bestSize = 0;
            for (int c = v + 1; c <= n; c++) {
                if (!get(pdom[v], c)) {
                    continue;
                }
                int size = cardinality(pdom[c]);
                if (size > bestSize) {
                    bestSize = size;
                    best = c;
                }
            }
            ipdom[v] = best;
        }
        return ipdom;
    }

    private static void set(long[] bits, int i) {
        bits[i >>> 6] |= 1L << (i & 63);
    }

    private static boolean get(long[] bits, int i) {
        return (bits[i >>> 6] & (1L << (i & 63))) != 0;
    }

    private static int cardinality(long[] bits) {
        int c = 0;
        for (long b : bits) {
            c += Long.bitCount(b);
        }
        return c;
    }
}
