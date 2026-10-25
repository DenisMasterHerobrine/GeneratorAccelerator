package dev.denismasterherobrine.generatoraccelerator2.gpu.light;

import dev.denismasterherobrine.generatoraccelerator2.gpu.cl.ClApi;
import dev.denismasterherobrine.generatoraccelerator2.gpu.runtime.GpuDevice;
import java.nio.ByteBuffer;

/**
 * The GPU light's program and its objects on one device: a queue of its own, the kernels and the buffers, which grow with the
 * batches, and pinned host buffers for the transfers. One light thread uses it at a time ({@link GpuLight} holds the lock). A
 * batch queues its writes, kernels and reads without waiting, and waits once, for the queue to finish.
 *
 * <p>The kernels compute a batch's increase propagation as levels, from 15 down. Light only falls along a step (each step costs
 * at least 1), so once every level above {@code L} is done, the positions at {@code L} are final. Level {@code L} then spreads
 * them: the queued entries of level {@code L} in their own directions, and every position raised to {@code L} in all six. A step
 * is vanilla's {@code propagateIncrease} for one direction: the target's section must store light, the level must rise past the
 * stored one by the target's opacity, and the two blocks' faces must not occlude. Writes are an atomic maximum, so steps of one
 * level may meet in any order.
 *
 * <p>A batch is 16 launches: {@code k_prepare}, one {@code k_level} per level, {@code k_finish}. The parts that share a launch
 * write different buffers, or write only levels below {@code L} by atomic maxima, which no part of level {@code L} reads as a
 * source. Their order within the launch does not change the result. Each launch can wait behind other GPU work, so fewer
 * launches wait less (33 before).
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

        /* the stored slots' block types (a palette index per block, or the type itself with palette offset -1), and every slot's
           levels before the batch */
        __kernel void k_prepare(__global const ulong* words, __global const int* sinfo, __global const ushort* pal, __global ushort* types,
                                int nk, __global int* fail, __global const uchar* sn, __global int* cur, int n) {
          int g = get_global_id(0);
          if (g < nk * 4096) {
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
          if (g < n * 4096) cur[g] = stored(sn, g);
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

        /* Level L. The first ns work items are the queued entries: on the first level (emit) light from emission raises its block, as
           propagateIncreases does first, and an entry of level L propagates, one from emission unless its block already held more,
           any other only where its block holds that level (propagateIncreases' check). The rest are the positions of the stored
           slots: each raised to L spreads it in all six directions (its entry skips only the way back, which cannot rise). An
           emission raise races with this level's positions at most for the entry's own block, whose entry spreads it anyway. */
        __kernel void k_level(int L, int sky, int emit, __global const int* seeds, int ns, __global const int* kslots, int nk,
                              __global const int* nbr, __global const uchar* storing, __global const uchar* sn, __global int* cur,
                              __global const ushort* types, __global const uchar* opac, __global const ushort* face, __global const uchar* cover,
                              __global const short* part, __global const uint* pairs, int np, __global const int* hazard, __global int* fail) {
          int g = get_global_id(0);
          if (g < ns) {
            int p = seeds[g * 2], e = seeds[g * 2 + 1];
            int s = stored(sn, p);
            if (emit && (e & 2048) && s < (e & 15)) atomic_max(&cur[p], e & 15);
            if ((e & 15) != L) return;
            if ((e & 2048) ? s > L : s != L) return;
            int from = (e & 1024) ? 0 : types[p];
            for (int d = 0; d < 6; d++) {
              if ((e >> (d + 4)) & 1) step(p, L, d, from, sky, nbr, storing, sn, cur, types, opac, face, cover, part, pairs, np, hazard, fail);
            }
            return;
          }
          g -= ns;
          if (g >= nk * 4096) return;
          int p = kslots[g >> 12] * 4096 + (g & 4095);
          if (cur[p] != L || L <= stored(sn, p)) return;
          int from = types[p];
          for (int d = 0; d < 6; d++) step(p, L, d, from, sky, nbr, storing, sn, cur, types, opac, face, cover, part, pairs, np, hazard, fail);
        }

        /* the levels packed as DataLayer bytes, and per slot the sections setStoredLevel adds to the affected set for the positions
           that changed (3x3x3 bits) */
        __kernel void k_finish(__global const int* kslots, int nk, __global const uchar* sn, __global const int* cur, __global int* changed,
                               __global uchar* out) {
          int g = get_global_id(0);
          if (g < nk * 2048) {
            int slot = kslots[g >> 11], b = g & 2047, p = slot * 4096 + 2 * b;
            out[(long) slot * 2048 + b] = (uchar) ((cur[p] & 15) | (cur[p + 1] & 15) << 4);
          }
          if (g >= nk * 4096) return;
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
        """;

    final GpuDevice device;
    final ClApi cl;
    final long program, queue;
    final long kPrepare, kLevel, kFinish;
    /** Buffers by name, each with its capacity in bytes. */
    private final long[] mem = new long[Buf.values().length];
    private final long[] cap = new long[Buf.values().length];
    /** Pinned host buffers by name, and their buffer objects. */
    private final ByteBuffer[] hostBuf = new ByteBuffer[Buf.values().length];
    private final long[] hostMem = new long[Buf.values().length];
    boolean closed;

    enum Buf { WORDS, SINFO, PAL, TYPES, SN, CUR, SEEDS, NBR, STORING, HAZARD, OPAC, FACE, COVER, PART, PAIRS, KSLOTS, CHANGED, OUT, FAIL }

    LightProgram(GpuDevice device, String options) {
        this.device = device;
        this.cl = device.cl;
        StringBuilder log = new StringBuilder();
        this.program = cl.buildProgram(device.context, device.info.device(), SOURCE, options, log);
        this.queue = cl.createQueue(device.context, device.info.device(), false);
        this.kPrepare = cl.createKernel(program, "k_prepare");
        this.kLevel = cl.createKernel(program, "k_level");
        this.kFinish = cl.createKernel(program, "k_finish");
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

    /**
     * The pinned host buffer for {@code b} with room for {@code bytes}, cleared. A batch's transfers run from and into these without
     * blocking. NVIDIA serializes transfers from ordinary memory and waits for each one. Grown buffers are pinned anew. The caller
     * holds the light lock, and the batch finishes the queue before the buffers are filled again.
     */
    ByteBuffer host(Buf b, int bytes) {
        int i = b.ordinal();
        ByteBuffer h = hostBuf[i];
        if (h == null || h.capacity() < bytes) {
            if (h != null) {
                cl.unpin(queue, hostMem[i], h);
            }
            long[] m = new long[1];
            h = cl.pinned(device.context, queue, Math.max(bytes, h == null ? 0 : h.capacity() + (h.capacity() >> 1)), m);
            hostBuf[i] = h;
            hostMem[i] = m[0];
        }
        h.clear();
        return h;
    }

    /** Queues the write of {@code data}, a pinned host buffer of {@link #host}, into buffer {@code b}, grown as needed. */
    long upload(Buf b, ByteBuffer data) {
        long m = buffer(b, data.remaining());
        if (data.remaining() > 0) {
            long ev = cl.write(queue, m, false, 0, data);
            if (ev != 0) {
                cl.releaseEvent(ev);
            }
        }
        return m;
    }

    /** Queues the read of buffer {@code mem} into {@code into}, a pinned host buffer of {@link #host}. Done once the queue is finished. */
    void download(long mem, ByteBuffer into) {
        long ev = cl.read(queue, mem, false, 0, into);
        if (ev != 0) {
            cl.releaseEvent(ev);
        }
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
        for (int i = 0; i < hostBuf.length; i++) {
            if (hostBuf[i] != null) {
                try {
                    cl.unpin(queue, hostMem[i], hostBuf[i]);
                } catch (RuntimeException ignored) {
                    // a lost device: everything goes with the context
                }
                hostBuf[i] = null;
            }
        }
        for (long k : new long[] { kPrepare, kLevel, kFinish }) {
            cl.releaseKernel(k);
        }
        cl.releaseProgram(program);
        cl.releaseQueue(queue);
    }
}
