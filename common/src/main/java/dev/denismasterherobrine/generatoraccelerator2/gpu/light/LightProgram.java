package dev.denismasterherobrine.generatoraccelerator2.gpu.light;

import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClApi;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuDevice;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The GPU light's program and its objects on one device: a queue of its own, the kernels and the buffers, which grow with the
 * batches. One light thread uses it at a time ({@link GpuLight} holds the lock).
 *
 * <p>The kernels compute a batch's increase propagation as levels, from 15 down. Light only falls along a step (each step costs
 * at least 1), so once every level above {@code L} is done, the positions at {@code L} are final. Round {@code L} then spreads
 * them: the queued entries of level {@code L} in their own directions ({@code k_seeds}), and every position raised to {@code L}
 * in all six ({@code k_round}). A step is vanilla's {@code propagateIncrease} for one direction: the target's section must store
 * light, the level must rise past the stored one by the target's opacity, and the two blocks' faces must not occlude. Writes are
 * an atomic maximum, so steps of one round may meet in any order.
 */
final class LightProgram {
    static final String SOURCE = """
        __constant int DX[6] = { 0, 0, 0, 0, -1, 1 };
        __constant int DY[6] = { -1, 1, 0, 0, 0, 0 };
        __constant int DZ[6] = { 0, 0, -1, 1, 0, 0 };

        /* the stored level before the batch, from the slots' nibble arrays (DataLayer's layout) */
        inline int stored(__global const uchar* sn, int p) { return (sn[p >> 1] >> ((p & 1) << 2)) & 15; }

        /* Shapes.faceShapeOccludes of two face shapes: either covers the face, or the pair does */
        inline int occludes(__global const uchar* cover, __global const short* part, __global const uint* pairs, int np, int a, int b) {
          if (cover[a] || cover[b]) return 1;
          int pa = part[a], pb = part[b];
          if (pa < 0 || pb < 0) return 0;
          int bit = pa * np + pb;
          return (pairs[bit >> 5] >> (bit & 31)) & 1;
        }

        /* the slot's block types: a palette index per block, or the type itself (palette offset -1) */
        __kernel void k_unpack(__global const ulong* words, __global const int* sinfo, __global const ushort* pal, __global ushort* types,
                               int nk, __global int* fail) {
          int g = get_global_id(0); if (g >= nk * 4096) return;
          __global const int* s = sinfo + (g >> 12) * 5;
          int i = g & 4095, slot = s[0], bits = s[1], wo = s[2], po = s[3], size = s[4];
          int v = 0;
          if (bits != 0) {
            int vpl = 64 / bits;
            ulong w = words[wo + i / vpl];
            v = (int) ((w >> ((i % vpl) * bits)) & (ulong) ((1 << bits) - 1));
          }
          if (po >= 0 && v >= size) { atomic_or(fail, 8); v = 0; }
          types[(long) slot * 4096 + i] = po < 0 ? (ushort) v : pal[po + v];
        }

        __kernel void k_init(__global const uchar* sn, __global int* cur, int n) {
          int g = get_global_id(0); if (g >= n * 4096) return;
          cur[g] = stored(sn, g);
        }

        /* light from emission raises its block first, as propagateIncreases does */
        __kernel void k_emit(__global const int* seeds, int ns, __global const uchar* sn, __global int* cur) {
          int g = get_global_id(0); if (g >= ns) return;
          int p = seeds[g * 2], e = seeds[g * 2 + 1];
          if ((e & 2048) && stored(sn, p) < (e & 15)) atomic_max(&cur[p], e & 15);
        }

        /* one step of propagateIncrease from p at level L in direction d. from: the source's type (0: an empty shape) */
        void step(int p, int L, int d, int from, int sky, __global const int* nbr, __global const uchar* storing, __global const uchar* sn,
                  __global int* cur, __global const ushort* types, __global const uchar* opac, __global const ushort* face,
                  __global const uchar* cover, __global const short* part, __global const uint* pairs, int np, __global const int* hazard,
                  __global int* fail) {
          int slot = p >> 12, i = p & 4095;
          int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
          int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
          int ns = slot, crossed = ((nx | ny | nz) & ~15) != 0;
          if (crossed) {
            ns = nbr[slot * 6 + d];
            if (ns < 0) { atomic_or(fail, 4); return; }
            nx &= 15; ny &= 15; nz &= 15;
          }
          if (!storing[ns]) return;
          int q = ns * 4096 + (ny << 8 | nz << 4 | nx);
          int k = cur[q];
          int t = types[q];
          int nv = L - max(1, (int) opac[t]);
          if (sky && crossed && d >= 2 && y == 0 && ((hazard[slot] >> d) & 1)) {
            /* propagateFromEmptySections would write the target's lower sections whatever they hold: the CPU runs the batch */
            if (L - 1 > stored(sn, q) && nv > stored(sn, q) && !occludes(cover, part, pairs, np, face[from * 6 + d], face[t * 6 + (d ^ 1)])) atomic_or(fail, 2);
            return;
          }
          if (L - 1 <= k || nv <= k) return;
          if (occludes(cover, part, pairs, np, face[from * 6 + d], face[t * 6 + (d ^ 1)])) return;
          atomic_max(&cur[q], nv);
        }

        /* the queued entries of level L that propagate: one from emission unless its block already held more, any other only
           where its block holds that level (propagateIncreases' check) */
        __kernel void k_seeds(int L, int sky, __global const int* seeds, int ns, __global const int* nbr, __global const uchar* storing,
                              __global const uchar* sn, __global int* cur, __global const ushort* types, __global const uchar* opac,
                              __global const ushort* face, __global const uchar* cover, __global const short* part, __global const uint* pairs,
                              int np, __global const int* hazard, __global int* fail) {
          int g = get_global_id(0); if (g >= ns) return;
          int p = seeds[g * 2], e = seeds[g * 2 + 1];
          if ((e & 15) != L) return;
          int s = stored(sn, p);
          if ((e & 2048) ? s > L : s != L) return;
          int from = (e & 1024) ? 0 : types[p];
          for (int d = 0; d < 6; d++) {
            if ((e >> (d + 4)) & 1) step(p, L, d, from, sky, nbr, storing, sn, cur, types, opac, face, cover, part, pairs, np, hazard, fail);
          }
        }

        /* every position raised to L spreads it in all six directions (its entry skips only the way back, which cannot rise) */
        __kernel void k_round(int L, int sky, __global const int* kslots, int nk, __global const int* nbr, __global const uchar* storing,
                              __global const uchar* sn, __global int* cur, __global const ushort* types, __global const uchar* opac,
                              __global const ushort* face, __global const uchar* cover, __global const short* part, __global const uint* pairs,
                              int np, __global const int* hazard, __global int* fail) {
          int g = get_global_id(0); if (g >= nk * 4096) return;
          int p = kslots[g >> 12] * 4096 + (g & 4095);
          if (cur[p] != L || L <= stored(sn, p)) return;
          int from = types[p];
          for (int d = 0; d < 6; d++) step(p, L, d, from, sky, nbr, storing, sn, cur, types, opac, face, cover, part, pairs, np, hazard, fail);
        }

        /* per slot, the sections setStoredLevel adds to the affected set for the positions that changed (3x3x3 bits) */
        __kernel void k_changed(__global const int* kslots, int nk, __global const uchar* sn, __global const int* cur, __global int* changed) {
          int g = get_global_id(0); if (g >= nk * 4096) return;
          int slot = kslots[g >> 12], i = g & 4095, p = slot * 4096 + i;
          if (cur[p] == stored(sn, p)) return;
          int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
          int mask = 0;
          for (int dx = x == 0 ? -1 : 0; dx <= (x == 15 ? 1 : 0); dx++)
            for (int dy = y == 0 ? -1 : 0; dy <= (y == 15 ? 1 : 0); dy++)
              for (int dz = z == 0 ? -1 : 0; dz <= (z == 15 ? 1 : 0); dz++)
                mask |= 1 << ((dx + 1) * 9 + (dy + 1) * 3 + (dz + 1));
          atomic_or(&changed[slot], mask);
        }

        __kernel void k_pack(__global const int* kslots, int nk, __global const int* cur, __global uchar* out) {
          int g = get_global_id(0); if (g >= nk * 2048) return;
          int slot = kslots[g >> 11], b = g & 2047, p = slot * 4096 + 2 * b;
          out[(long) slot * 2048 + b] = (uchar) ((cur[p] & 15) | (cur[p + 1] & 15) << 4);
        }
        """;

    final GpuDevice device;
    final ClApi cl;
    final long program, queue;
    final long kUnpack, kInit, kEmit, kSeeds, kRound, kChanged, kPack;
    /** Buffers by name, each with its capacity in bytes. */
    private final long[] mem = new long[Buf.values().length];
    private final long[] cap = new long[Buf.values().length];
    boolean closed;

    enum Buf { WORDS, SINFO, PAL, TYPES, SN, CUR, SEEDS, NBR, STORING, HAZARD, OPAC, FACE, COVER, PART, PAIRS, KSLOTS, CHANGED, OUT, FAIL }

    LightProgram(GpuDevice device, String options) {
        this.device = device;
        this.cl = device.cl;
        StringBuilder log = new StringBuilder();
        this.program = cl.buildProgram(device.context, device.info.device(), SOURCE, options, log);
        this.queue = cl.createQueue(device.context, device.info.device(), false, false);
        this.kUnpack = cl.createKernel(program, "k_unpack");
        this.kInit = cl.createKernel(program, "k_init");
        this.kEmit = cl.createKernel(program, "k_emit");
        this.kSeeds = cl.createKernel(program, "k_seeds");
        this.kRound = cl.createKernel(program, "k_round");
        this.kChanged = cl.createKernel(program, "k_changed");
        this.kPack = cl.createKernel(program, "k_pack");
    }

    /** The buffer {@code b} with room for {@code bytes}, made anew when it is too small. Its contents are then undefined. */
    long buffer(Buf b, long bytes) {
        int i = b.ordinal();
        long need = Math.max(16, bytes);
        if (cap[i] < need) {
            if (mem[i] != 0) {
                cl.releaseMem(mem[i]);
            }
            long size = Math.max(need, cap[i] + (cap[i] >> 1));
            mem[i] = cl.createBuffer(device.context, ClApi.MEM_READ_WRITE, size);
            cap[i] = size;
        }
        return mem[i];
    }

    /** Writes {@code data} into buffer {@code b}, grown as needed. Blocking, so the host buffer may be reused right after. */
    long upload(Buf b, ByteBuffer data) {
        long m = buffer(b, data.remaining());
        if (data.remaining() > 0) {
            long ev = cl.write(queue, m, true, 0, data);
            if (ev != 0) {
                cl.releaseEvent(ev);
            }
        }
        return m;
    }

    void release() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            cl.finish(queue);
        } catch (RuntimeException ignored) {
            // a lost device: everything goes with the context
        }
        for (long m : mem) {
            if (m != 0) {
                cl.releaseMem(m);
            }
        }
        for (long k : new long[] { kUnpack, kInit, kEmit, kSeeds, kRound, kChanged, kPack }) {
            cl.releaseKernel(k);
        }
        cl.releaseProgram(program);
        cl.releaseQueue(queue);
    }

    static ByteBuffer direct(int bytes) {
        return ByteBuffer.allocateDirect(Math.max(16, bytes)).order(ByteOrder.nativeOrder());
    }
}
