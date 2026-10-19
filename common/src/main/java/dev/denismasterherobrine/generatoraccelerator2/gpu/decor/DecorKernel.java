package dev.denismasterherobrine.generatoraccelerator2.gpu.decor;

import static dev.denismasterherobrine.generatoraccelerator2.gpu.decor.DecorSpec.*;

/**
 * The decoration kernel {@code k_decor} and the gather {@code k_dgather}. {@code k_decor} runs one work group of {@link DecorSpec#WG}
 * per job. The group places the job's GPU features in vanilla's order, each from the random seed vanilla gives it. The source is
 * appended after the surface's source and reuses the surface's zoom helpers, the biome info noise {@code sx_info} and the Xoroshiro
 * helpers.
 *
 * <p>Ores place a blob with the whole work group. Every other kind runs on the group's first thread, as vanilla runs it, against the
 * job's copy of the 3x3 chunks. Every block read is recorded with the predicate asked and the answer ({@code dk_ask}). Every heightmap
 * read is recorded with its value. Every write and scheduled tick is recorded as an effect, in vanilla's order.
 */
final class DecorKernel {
    private DecorKernel() {}

    static String source(int height, int minY, int windowShorts, long slotShorts, int voidAir) {
        StringBuilder b = new StringBuilder();
        b.append(SinCos.SOURCE);
        b.append("// ---- decoration (gpu.decor.DecorKernel) ----\n");
        b.append("#define OD_H ").append(height).append("\n#define OD_MINY ").append(minY).append("\n#define OD_SLOT ").append(slotShorts).append("L\n");
        b.append("#define OD_WIN ").append(windowShorts).append("\n#define OD_MAXF ").append(MAXF).append("\n#define OD_MAXW ").append(MAXW).append("\n");
        b.append("#define OD_MAXR ").append(MAXR).append("\n#define OD_L2 ").append(L2).append("\n#define OD_CAND ").append(CAND).append("\n");
        b.append("#define OD_WG ").append(WG).append("\n#define OD_VOID ").append(voidAir).append("\n#define DK_HEAD ").append(HEAD).append("\n");
        b.append("#define OD_SW ").append(Preds.STATE_WORDS).append("\n#define OD_MAXOPS ").append(MAX_OPS).append("\n");
        b.append("#define OD_FMUL as_float(0x").append(Integer.toHexString(Float.floatToRawIntBits(5.9604645E-8F))).append(")\n");
        b.append("#define OD_DMUL as_double(0x").append(Long.toHexString(Double.doubleToRawLongBits((double) 1.110223E-16F))).append("L)\n");
        define(b, "K_ORE", K_ORE, "K_SIMPLE", K_SIMPLE, "K_PATCH", K_PATCH, "K_SELECT", K_SELECT, "K_SSELECT", K_SSELECT, "K_BSELECT", K_BSELECT,
            "K_SPRING", K_SPRING, "K_TREE", K_TREE, "K_SEAGRASS", K_SEAGRASS, "K_KELP", K_KELP, "K_PICKLE", K_PICKLE, "K_COLUMN", K_COLUMN, "K_DISK", K_DISK, "K_MULTIFACE", K_MULTIFACE, "K_VINES", K_VINES, "K_BLOB", K_BLOB, "K_SNOW", K_SNOW, "K_MUSHROOM", K_MUSHROOM);
        define(b, "OP_COUNT", OP_COUNT, "OP_SQUARE", OP_SQUARE, "OP_HEIGHT", OP_HEIGHT, "OP_BIOME", OP_BIOME, "OP_RARITY", OP_RARITY,
            "OP_HEIGHTMAP", OP_HEIGHTMAP, "OP_FILTER", OP_FILTER, "OP_OFFSET", OP_OFFSET, "OP_WATER_DEPTH", OP_WATER_DEPTH,
            "OP_SURFACE_REL", OP_SURFACE_REL, "OP_NOISE_THRESHOLD", OP_NOISE_THRESHOLD, "OP_NOISE_COUNT", OP_NOISE_COUNT, "OP_ENV_SCAN", OP_ENV_SCAN);
        define(b, "IP_CONST", IP_CONST, "IP_UNIFORM", IP_UNIFORM, "IP_BIASED", IP_BIASED, "IP_CLAMPED", IP_CLAMPED, "IP_WEIGHTED", IP_WEIGHTED);
        define(b, "HP_CONST", HP_CONST, "HP_UNIFORM", HP_UNIFORM, "HP_TRAPEZOID", HP_TRAPEZOID, "HP_BIASED", HP_BIASED, "HP_VERY_BIASED", HP_VERY_BIASED);
        define(b, "PR_TRUE", PR_TRUE, "PR_FALSE", PR_FALSE, "PR_STATE", PR_STATE, "PR_BOUNDS", PR_BOUNDS, "PR_NOT", PR_NOT, "PR_ALL", PR_ALL, "PR_ANY", PR_ANY, "PR_SURVIVE", PR_SURVIVE);
        define(b, "SP_SIMPLE", SP_SIMPLE, "SP_WEIGHTED", SP_WEIGHTED, "SP_ROTATED", SP_ROTATED, "SP_RANDINT", SP_RANDINT,"SV_ALWAYS", SV_ALWAYS, "SV_BELOW", SV_BELOW, "SV_NEVER", SV_NEVER, "SV_MULTI", SV_MULTI);
        define(b, "EF_SECTION", EF_SECTION, "EF_SET", EF_SET, "EF_TICK_FLUID", EF_TICK_FLUID, "EF_TICK_BLOCK", EF_TICK_BLOCK, "EF_TREE", EF_TREE, "EF_SHAPE", EF_SHAPE,
            "EF_EDGE", EF_EDGE, "EF_EDGE_SET", EF_EDGE_SET, "EF_POST", EF_POST, "RT_PRED", RT_PRED, "RT_HEIGHT", RT_HEIGHT, "RT_STATE", RT_STATE, "RT_VALUE", RT_VALUE, "RT_LIGHT", RT_LIGHT);
        define(b, "P_AIR", Preds.AIR, "P_HM0", Preds.HM_WORLD_SURFACE, "HM_WS", 2, "HM_OF", 3);
        b.append(HELPERS);
        b.append(TreeKernel.source((float) (Math.PI * 2)));
        b.append(LEAF);
        b.append(COMPOSITE.replace("NAME", "dk_cfg1").replace("NEXT", "dk_nested2"));
        b.append("int dk_nested1(Dk* k, int e, int x, int y, int z) {\n  __global const int* fs = k->fspec + k->fspec[e];\n"
            + "  if (!dk_filters(k, fs, x, &y, z)) return 0;\n  return dk_cfg1(k, fs, x, y, z);\n}\n");
        b.append(COMPOSITE.replace("NAME", "dk_cfg0").replace("NEXT", "dk_nested1"));
        b.append(KERNEL);
        return b.toString();
    }

    private static void define(StringBuilder b, Object... kv) {
        for (int i = 0; i < kv.length; i += 2) {
            b.append("#define ").append(kv[i]).append(' ').append(kv[i + 1]).append('\n');
        }
    }

    private static final String HELPERS = """
        #define DK_NOINLINE __attribute__((noinline))
        #ifdef OD_PROF
        ulong od_clk() { ulong v; asm volatile("mov.u64 %0, %%globaltimer;" : "=l"(v)); return v; }
        #define OD_T(i) if (t == 0) { ulong nw = od_clk(); prof[i] += nw - tp; tp = nw; }
        #define OD_N(i, v) if (t == 0) prof[i] += (ulong) (v);
        #else
        #define OD_T(i)
        #define OD_N(i, v)
        #endif
        #define OD_DRAW (1 << 30)
        #define OD_NOOP (1 << 16)
        #define OD_IMAX 0x7fffffff
        #define DK_UNK ((int) 0x80000000)
        typedef struct { Xo x; int n; } Br;
        int br_next(Br* r, int bits) { r->n++; return (int) (uint) (xo_next(&r->x) >> (64 - bits)); }
        int br_nextInt(Br* r, int bound) {
          if ((bound & (bound - 1)) == 0) return (int) (((long) bound * (long) br_next(r, 31)) >> 31);
          int i, j;
          do { i = br_next(r, 31); j = i % bound; } while ((int) ((uint) i - (uint) j + (uint) (bound - 1)) < 0);
          return j;
        }
        float br_nextFloat(Br* r) { return (float) br_next(r, 24) * OD_FMUL; }
        double br_nextDouble(Br* r) { int i = br_next(r, 26); int j = br_next(r, 27); long k = ((long) i << 27) + (long) j; return (double) k * OD_DMUL; }
        int br_nextBool(Br* r) { return br_next(r, 1) != 0; }
        int br_between(Br* r, int lo, int hi) { return br_nextInt(r, hi - lo + 1) + lo; }
        int dk_mnext(Br* r, int a, int b) { return a >= b ? a : br_nextInt(r, b - a + 1) + a; }
        int od_ceilf(float f) { int i = (int) f; return f > (float) i ? i + 1 : i; }
        /* Java's (int) of a double */
        int dk_d2i(double v) { if (isnan(v)) return 0; if (v >= 2147483647.0) return 2147483647; if (v <= -2147483648.0) return (int) 0x80000000; return (int) v; }
        /* BiomeManager.getBiome over the chunk's stored quart biomes (the surface's window) */
        int od_zoom(__global const ushort* win, int wqx0, int wqz0, __global const int* sp, int x, int y, int z) {
          int i = x - 2, j = y - 2, k = z - 2;
          int l = i >> 2, i1 = j >> 2, j1 = k >> 2;
          int lo = SF_MINY >> 2, hi = lo + SF_QY - 1;
          {
            // every candidate the same biome: the nearest is that biome whichever it is
            int y0 = (i1 < lo ? lo : i1 > hi ? hi : i1) - lo, y1 = (i1 + 1 < lo ? lo : i1 + 1 > hi ? hi : i1 + 1) - lo;
            int x0 = l - wqx0, z0 = j1 - wqz0;
            int b0 = win[(y0 * 6 + z0) * 6 + x0];
            if (b0 == win[(y0 * 6 + z0) * 6 + x0 + 1] && b0 == win[(y0 * 6 + z0 + 1) * 6 + x0] && b0 == win[(y0 * 6 + z0 + 1) * 6 + x0 + 1]
                && b0 == win[(y1 * 6 + z0) * 6 + x0] && b0 == win[(y1 * 6 + z0) * 6 + x0 + 1] && b0 == win[(y1 * 6 + z0 + 1) * 6 + x0]
                && b0 == win[(y1 * 6 + z0 + 1) * 6 + x0 + 1]) return b0;
          }
          double d0 = (double) (i & 3) / 4.0, d1 = (double) (j & 3) / 4.0, d2 = (double) (k & 3) / 4.0;
          long zs = sf_seed64(sp, SF_TSEED);
          int k1 = 0; double d3 = INFINITY;
          for (int l1 = 0; l1 < 8; l1++) {
            int fx = (l1 & 4) == 0, fy = (l1 & 2) == 0, fz = (l1 & 1) == 0;
            int i2 = fx ? l : l + 1, j2 = fy ? i1 : i1 + 1, k2 = fz ? j1 : j1 + 1;
            double d4 = fx ? d0 : d0 - 1.0, d5 = fy ? d1 : d1 - 1.0, d6 = fz ? d2 : d2 - 1.0;
            long h = sf_lcg(zs, (long) i2); h = sf_lcg(h, (long) j2); h = sf_lcg(h, (long) k2);
            h = sf_lcg(h, (long) i2); h = sf_lcg(h, (long) j2); h = sf_lcg(h, (long) k2);
            double f0 = sf_fiddle(h); h = sf_lcg(h, zs); double f1 = sf_fiddle(h); h = sf_lcg(h, zs); double f2 = sf_fiddle(h);
            double a = d6 + f2, bb = d5 + f1, cc = d4 + f0;
            double d7 = a * a + bb * bb + cc * cc;
            if (d3 > d7) { k1 = l1; d3 = d7; }
          }
          int qx = (k1 & 4) == 0 ? l : l + 1, qy = (k1 & 2) == 0 ? i1 : i1 + 1, qz = (k1 & 1) == 0 ? j1 : j1 + 1;
          int yy = qy < lo ? lo : qy > hi ? hi : qy;
          return win[((yy - lo) * 6 + (qz - wqz0)) * 6 + (qx - wqx0)];
        }
        /* the 3x3 chunk index (dz + 1) * 3 + dx + 1 of a block, or -1 */
        int od_ci(int x, int z, int bxo, int bzo) {
          int dx = (x - bxo) >> 4, dz = (z - bzo) >> 4;
          return dx < 0 || dx > 2 || dz < 0 || dz > 2 ? -1 : dz * 3 + dx;
        }
        int od_at(__global const ushort* sc, int x, int y, int z, int bxo, int bzo) {
          int ci = od_ci(x, z, bxo, bzo);
          if (ci < 0) return -1;
          return sc[ci * OD_SLOT + (((x - bxo) & 15) * 16 + ((z - bzo) & 15)) * OD_H + (y - OD_MINY)];
        }
        /* predicate p's answer bit for state s (the unknown plane is checked by dk_test) */
        int od_bit(__global const uint* pb, int p, int s) { return (pb[(long) p * 2 * OD_SW + (s >> 5)] >> (s & 31)) & 1u; }
        /* OreFeature.isAdjacentToAir through BulkSectionAccess (air outside the build height): 1, 0, or -1 for a block the GPU lacks */
        int od_air(__global const ushort* sc, __global const uint* pb, int x, int y, int z, int bxo, int bzo, int nstates) {
          int dx[6] = { 0, 0, 0, 0, -1, 1 }, dy[6] = { -1, 1, 0, 0, 0, 0 }, dz[6] = { 0, 0, -1, 1, 0, 0 };
          for (int q = 0; q < 6; q++) {
            int yy = y + dy[q];
            if (yy < OD_MINY || yy >= OD_MINY + OD_H) return 1;
            int s = od_at(sc, x + dx[q], yy, z + dz[q], bxo, bzo);
            if (s < 0 || s >= nstates) return -1;
            if (od_bit(pb, P_AIR, s)) return 1;
          }
          return 0;
        }
        int od_dec(int state, int old) { return state | (state == old ? OD_NOOP : 0); }
        /* exclusive prefix sum over the group; a[OD_WG - 1] holds the total afterwards */
        int od_scan(__local int* a, int t, int v) {
          a[t] = v;
          barrier(CLK_LOCAL_MEM_FENCE);
          for (int o = 1; o < OD_WG; o <<= 1) {
            int x = t >= o ? a[t - o] : 0;
            barrier(CLK_LOCAL_MEM_FENCE);
            a[t] += x;
            barrier(CLK_LOCAL_MEM_FENCE);
          }
          return a[t] - v;
        }
        /* the sphere and the block of box cell idx */
        int od_cell(int idx, __local const int* sst, __local const int* sb, int size, int* bx, int* by, int* bz) {
          int lo = 0, hi = size;
          while (lo < hi) { int m = (lo + hi + 1) >> 1; if (sst[m] <= idx) lo = m; else hi = m - 1; }
          int q = lo, rel = idx - sst[q], ny = sb[q * 6 + 4], nz = sb[q * 6 + 5];
          *bz = sb[q * 6 + 2] + rel % nz;
          *by = sb[q * 6 + 1] + (rel / nz) % ny;
          *bx = sb[q * 6] + rel / (nz * ny);
          return q;
        }

        /* ---- the generic features: one thread's view of the job */
        typedef struct {
          Br r;
          __global ushort* sc; __global const int* hw; __global int* hf; __global int* wr; __global int* rd;
          int nw, nr, flag, bxo, bzo, nstates, slot, cx, cz;
          __global const uint* pb; __global const int* aux; __global const int* fspec;
          __global const uint* bbits; int bw; __global const ushort* win; int wqx0, wqz0; __global const int* sp;
          __global int* tr; __global const float* msin; __global const int* vt; __global const uint* fsinFix; __global const uint* fcosFix;
          // the logs' room
          int maxw, maxr;
        } Dk;
        /* the state in the job's copy of a block inside the 3x3 chunks and the build height */
        int dk_view(Dk* k, int ci, int lx, int lz, int yi) {
          return k->sc[ci * OD_SLOT + (lx * 16 + lz) * OD_H + yi];
        }
        /* the state at a block (void air outside the build height); a block outside the 3x3 chunks sends the feature to the CPU */
        int dk_at(Dk* k, int x, int y, int z) {
          if (y < OD_MINY || y >= OD_MINY + OD_H) return OD_VOID;
          int ci = od_ci(x, z, k->bxo, k->bzo);
          if (ci < 0) { k->flag |= 2; return OD_VOID; }
          return dk_view(k, ci, (x - k->bxo) & 15, (z - k->bzo) & 15, y - OD_MINY);
        }
        /* predicate p of state s: 1, 0, or -1 when the tables lack the state or the predicate cannot tell */
        int pb_test(__global const uint* pb, int nstates, int s, int p) {
          if (s >= nstates) return -1;
          __global const uint* w = pb + (long) p * 2 * OD_SW + (s >> 5);
          if ((w[OD_SW] >> (s & 31)) & 1u) return -1;
          return (w[0] >> (s & 31)) & 1u;
        }
        /* predicate p of state s; a state the tables lack, or a predicate that cannot tell, sends the feature to the CPU */
        int dk_test(Dk* k, int s, int p) {
          int a = pb_test(k->pb, k->nstates, s, p);
          if (a < 0) { k->flag |= 2; return 0; }
          return a;
        }
        void dk_read(Dk* k, int where, int aux) {
          if (k->nr < k->maxr) { k->rd[k->nr * 2] = where; k->rd[k->nr * 2 + 1] = aux; }
          else k->flag |= 2 | 4;
          k->nr++;
        }
        /* reads a block and asks predicate p of it, recording the read with its answer for the CPU's check */
        int dk_ask(Dk* k, int x, int y, int z, int p) {
          int s = dk_at(k, x, y, z);
          int a = dk_test(k, s, p);
          if (y >= OD_MINY && y < OD_MINY + OD_H && !(k->flag & 2)) {
            int ci = od_ci(x, z, k->bxo, k->bzo);
            dk_read(k, (RT_PRED << 24) | (ci << 20) | ((y - OD_MINY) << 8) | (((z - k->bzo) & 15) << 4) | ((x - k->bxo) & 15), (p << 1) | a);
          }
          return a;
        }
        /* the first free y above the highest block at or below y0 heightmap type i (0..3, Preds.FINAL) counts (Heightmap.update's scan);
           DK_UNK for a state the tables lack. Out of line, without the Dk (a Dk passed to a call would leave registers for memory). */
        DK_NOINLINE int col_scan(__global const ushort* sc, __global const uint* pb, int nstates, int ci, int lx, int lz, int y0, int i) {
          __global const ushort* col = sc + ci * OD_SLOT + (lx * 16 + lz) * OD_H;
          for (int y = y0; y >= OD_MINY; y--) {
            int a = pb_test(pb, nstates, col[y - OD_MINY], P_HM0 + i);
            if (a < 0) return DK_UNK;
            if (a) return y + 1;
          }
          return OD_MINY;
        }
        int dk_scan(Dk* k, int ci, int lx, int lz, int y0, int i) {
          int v = col_scan(k->sc, k->pb, k->nstates, ci, lx, lz, y0, i);
          if (v == DK_UNK) { k->flag |= 2; return OD_MINY; }
          return v;
        }
        /* a final heightmap's value: the job's, scanned from the copy when first asked */
        int dk_hfinal(Dk* k, int ci, int lx, int lz, int i) {
          __global int* h = k->hf + (ci * 256 + lz * 16 + lx) * 4 + i;
          int v = *h;
          if (v == DK_UNK) { v = dk_scan(k, ci, lx, lz, OD_MINY + OD_H - 1, i); if (!(k->flag & 2)) *h = v; }
          return v;
        }
        /* WorldGenRegion.getHeight of heightmap t (0, 1: the chunks' worldgen maps; 2 + i: the final ones, from the job's copy when first asked) */
        int dk_height(Dk* k, int t, int x, int z) {
          int ci = od_ci(x, z, k->bxo, k->bzo);
          if (ci < 0) { k->flag |= 2; return OD_MINY; }
          int lx = (x - k->bxo) & 15, lz = (z - k->bzo) & 15, col = lz * 16 + lx;
          if (t < 2) return k->hw[(ci * 256 + col) * 2 + t];
          int v = dk_hfinal(k, ci, lx, lz, t - 2);
          // the CPU compares it with the chunk's own heightmap when it uses the result
          dk_read(k, (RT_HEIGHT << 24) | (ci << 20) | (t << 8) | (lz << 4) | lx, v);
          return v;
        }
        /* WorldGenRegion.setBlock: the job's copy, the heightmaps the write updates (ProtoChunk.setBlockState), the effect */
        int dk_set(Dk* k, int x, int y, int z, int s, int flags) {
          if (y < OD_MINY || y >= OD_MINY + OD_H) return 0;
          int ci = od_ci(x, z, k->bxo, k->bzo);
          if (ci < 0) { k->flag |= 2; return 0; }
          int lx = (x - k->bxo) & 15, lz = (z - k->bzo) & 15, yi = y - OD_MINY;
          k->sc[ci * OD_SLOT + (lx * 16 + lz) * OD_H + yi] = (ushort) s;
          for (int i = 0; i < 4; i++) {
            __global int* h = k->hf + (ci * 256 + lz * 16 + lx) * 4 + i;
            int v = *h;
            if (v == DK_UNK || y <= v - 2) continue;
            if (dk_test(k, s, P_HM0 + i)) { if (y >= v) *h = y + 1; }
            else if (v - 1 == y) *h = dk_scan(k, ci, lx, lz, y - 1, i);
          }
          if (k->nw < k->maxw) { k->wr[k->nw * 2] = (ci << 20) | (yi << 8) | (lz << 4) | lx; k->wr[k->nw * 2 + 1] = (EF_SET << 28) | ((flags & 0xfff) << 16) | s; }
          else k->flag |= 2 | 4;
          k->nw++;
          return 1;
        }
        /* a scheduled tick (the fluid or block by its index in the spec's lists) */
        void dk_tick(Dk* k, int x, int y, int z, int op, int idx, int delay) {
          int ci = od_ci(x, z, k->bxo, k->bzo);
          if (ci < 0 || y < OD_MINY || y >= OD_MINY + OD_H) { k->flag |= 2; return; }
          if (k->nw < k->maxw) {
            k->wr[k->nw * 2] = (ci << 20) | ((y - OD_MINY) << 8) | (((z - k->bzo) & 15) << 4) | ((x - k->bxo) & 15);
            k->wr[k->nw * 2 + 1] = (op << 28) | ((delay & 0xfff) << 16) | idx;
          } else k->flag |= 2 | 4;
          k->nw++;
        }
        /* Feature.markAboveForPostProcessing: the two blocks above, up to the first air */
        void dk_post_above(Dk* k, int x, int y, int z) {
          for (int i = 1; i <= 2; i++) {
            if (dk_ask(k, x, y + i, z, P_AIR)) return;
            dk_tick(k, x, y + i, z, EF_POST, 0, 0);
          }
        }
        int dk_int2(Br* r, __global const int* a) {
          if (a[0] == IP_CONST) return a[1];
          if (a[0] == IP_UNIFORM) return br_between(r, a[1], a[2]);
          return a[1] + br_nextInt(r, br_nextInt(r, a[2] - a[1] + 1) + 1);
        }
        /* IntProvider.sample */
        int dk_int(Br* r, __global const int* aux, int off) {
          __global const int* a = aux + off;
          if (a[0] == IP_CLAMPED) { int v = dk_int2(r, aux + a[1]); return v < a[2] ? a[2] : min(v, a[3]); }
          if (a[0] == IP_WEIGHTED) {
            int i = br_nextInt(r, a[1]);
            for (int q = 0; q < a[2]; q++) { i -= a[3 + q * 2]; if (i < 0) return dk_int2(r, aux + a[4 + q * 2]); }
            return 0;
          }
          return dk_int2(r, a);
        }
        /* HeightProvider.sample (anchors resolved) */
        int dk_heightp(Br* r, __global const int* a) {
          int lo = a[1], hi = a[2];
          if (a[0] == HP_CONST) return lo;
          if (a[0] == HP_UNIFORM) return br_between(r, lo, hi);
          if (a[0] == HP_TRAPEZOID) {
            int k = hi - lo, pl = a[3];
            if (pl >= k) return br_between(r, lo, hi);
            int l = (k - pl) / 2, i1 = k - l;
            int a1 = br_between(r, 0, i1);
            return lo + a1 + br_between(r, 0, l);
          }
          if (a[0] == HP_BIASED) { int k = br_nextInt(r, hi - lo - a[3] + 1); return br_nextInt(r, k + a[3]) + lo; }
          int k = dk_mnext(r, lo + a[3], hi);
          int l = dk_mnext(r, lo, k - 1);
          return dk_mnext(r, lo, l - 1 + a[3]);
        }
        /* BlockStateProvider.getState: the offset of the plant record drawn */
        int dk_state1(Br* r, __global const int* aux, int off) {
          __global const int* a = aux + off;
          if (a[0] == SP_SIMPLE) return off + 1;
          if (a[0] == SP_WEIGHTED) {
            int i = br_nextInt(r, a[1]);
            for (int q = 0; q < a[2]; q++) { i -= a[3 + q * 5]; if (i < 0) return off + 4 + q * 5; }
            return off + 4;
          }
          return off + 1 + br_nextInt(r, 3) * 4;
        }
        int dk_state(Br* r, __global const int* aux, int off) {
          __global const int* a = aux + off;
          if (a[0] != SP_RANDINT) return dk_state1(r, aux, off);
          // RandomizedIntStateProvider: the source's state, then the value when the state has the property
          int in = a[1], rec = dk_state1(r, aux, in), ik = aux[in];
          int q = ik == SP_SIMPLE ? 0 : ik == SP_WEIGHTED ? (rec - in - 4) / 5 : (rec - in - 1) / 4;
          __global const int* row = a + 5 + q * (1 + a[4]);
          if (!row[0]) return rec;
          int v = dk_int(r, aux, a[2]) - a[3];
          return row[1 + clamp(v, 0, a[4] - 1)];
        }
        int dk_value(Dk* k, int x, int y, int z, int table);
        /* a plant's survival by its rule over several blocks: each block's class (recorded), then the table */
        int dk_multi(Dk* k, int off, int x, int y, int z) {
          __global const int* a = k->aux + off;
          int n = a[0], idx = 0;
          for (int i = 0; i < n; i++) {
            __global const int* p = a + 1 + i * 5;
            int c = dk_value(k, x + p[0], y + p[1], z + p[2], p[3]);
            if (k->flag & 2) return 0;
            idx += c * p[4];
          }
          __global const int* bits = a + 1 + n * 5;
          return (bits[idx >> 5] >> (idx & 31)) & 1;
        }
        /* a block predicate program (postfix; every leaf evaluated and recorded) */
        int dk_pred(Dk* k, int off, int x, int y, int z) {
          __global const int* a = k->aux + off;
          int len = a[0], st[16], sp = 0;
          for (int i = 1; i <= len; ) {
            int op = a[i];
            if (op == PR_TRUE) { st[sp++] = 1; i++; }
            else if (op == PR_FALSE) { st[sp++] = 0; i++; }
            else if (op == PR_STATE) { st[sp++] = dk_ask(k, x + a[i + 1], y + a[i + 2], z + a[i + 3], a[i + 4]); i += 5; }
            else if (op == PR_SURVIVE) { st[sp++] = dk_multi(k, a[i + 4], x + a[i + 1], y + a[i + 2], z + a[i + 3]); i += 5; }
            else if (op == PR_BOUNDS) { int yy = y + a[i + 2]; st[sp++] = yy >= OD_MINY && yy < OD_MINY + OD_H; i += 4; }
            else if (op == PR_NOT) { st[sp - 1] = !st[sp - 1]; i++; }
            else {
              int n = a[i + 1], v = op == PR_ALL;
              for (int q = 0; q < n; q++) { int w = st[--sp]; v = op == PR_ALL ? (v && w) : (v || w); }
              st[sp++] = v;
              i += 2;
            }
          }
          return sp > 0 ? st[sp - 1] : 1;
        }
        /* EnvironmentScanPlacement.getPositions: the position found in *y, or 0 */
        int dk_envscan(Dk* k, __global const int* op, int x, int* y, int z) {
          int steps = abs(op[3]), dir = op[3] < 0 ? -1 : 1, yy = *y;
          if (!dk_pred(k, op[2], x, yy, z)) return 0;
          for (int i = 0; i < steps && !(k->flag & 2); i++) {
            if (dk_pred(k, op[1], x, yy, z)) { *y = yy; return 1; }
            yy += dir;
            if (yy < OD_MINY || yy >= OD_MINY + OD_H) return 0;
            if (!dk_pred(k, op[2], x, yy, z)) break;
          }
          if (dk_pred(k, op[1], x, yy, z)) { *y = yy; return 1; }
          return 0;
        }
        /* PlacedFeature.placeWithContext's modifiers depth first: 1 with the next position at depth *d, 0 when done */
        int dk_walk(Dk* k, int* d, int* back, int* rem, int* px, int* py, int* pz, __global const int* ops, int nops) {
          int mode = *back, dd = *d;
          while (1) {
            if (k->flag & 2) { *d = 0; *back = 0; return 0; }
            if (mode == 0) {
              if (dd == nops) { *d = dd; *back = 1; return 1; }
              __global const int* op = ops + dd * 4;
              int ty = op[0], x = px[dd], y = py[dd], z = pz[dd];
              if (ty == OP_COUNT || ty == OP_NOISE_THRESHOLD || ty == OP_NOISE_COUNT) {
                int n;
                if (ty == OP_COUNT) n = dk_int(&k->r, k->aux, op[1]);
                else {
                  __global const int* a = k->aux + op[1];
                  if (ty == OP_NOISE_THRESHOLD) {
                    double lv = as_double((ulong) (uint) a[0] | ((ulong) (uint) a[1] << 32));
                    n = sx_info(k->sp, (double) x / 200.0, (double) z / 200.0) < lv ? a[2] : a[3];
                  } else {
                    double f = as_double((ulong) (uint) a[1] | ((ulong) (uint) a[2] << 32));
                    double o = as_double((ulong) (uint) a[3] | ((ulong) (uint) a[4] << 32));
                    n = dk_d2i(ceil((sx_info(k->sp, (double) x / f, (double) z / f) + o) * (double) a[0]));
                  }
                }
                rem[dd] = n;
                mode = 2;
                continue;
              }
              int ok = 1, nx = x, ny = y, nz = z;
              if (ty == OP_SQUARE) { nx = br_nextInt(&k->r, 16) + x; nz = br_nextInt(&k->r, 16) + z; }
              else if (ty == OP_HEIGHT) ny = dk_heightp(&k->r, k->aux + op[1]);
              else if (ty == OP_BIOME) {
                // the zoom's window covers the chunk's own blocks
                if ((x >> 4) != k->cx || (z >> 4) != k->cz) { k->flag |= 2; continue; }
                int bi = od_zoom(k->win, k->wqx0, k->wqz0, k->sp, x, y, z);
                ok = (k->bbits[k->slot * k->bw + (bi >> 5)] >> (bi & 31)) & 1u;
              }
              else if (ty == OP_RARITY) ok = br_nextFloat(&k->r) < as_float(op[1]);
              else if (ty == OP_HEIGHTMAP) { ny = dk_height(k, op[1], x, z); ok = ny > OD_MINY; }
              else if (ty == OP_FILTER) ok = dk_pred(k, op[1], x, y, z);
              else if (ty == OP_ENV_SCAN) ok = dk_envscan(k, op, x, &ny, z);
              else if (ty == OP_OFFSET) {
                int ox = dk_int(&k->r, k->aux, op[1]); int oy = dk_int(&k->r, k->aux, op[2]); int oz = dk_int(&k->r, k->aux, op[1]);
                nx = x + ox; ny = y + oy; nz = z + oz;
              }
              else if (ty == OP_WATER_DEPTH) { int fl = dk_height(k, HM_OF, x, z); int su = dk_height(k, HM_WS, x, z); ok = su - fl <= op[1]; }
              else if (ty == OP_SURFACE_REL) { long h = (long) dk_height(k, op[1], x, z); ok = h + (long) op[2] <= (long) y && (long) y <= h + (long) op[3]; }
              else { k->flag |= 2; continue; }
              if (!ok) { mode = 1; continue; }
              rem[dd] = 0;
              px[dd + 1] = nx; py[dd + 1] = ny; pz[dd + 1] = nz;
              dd++;
              continue;
            }
            if (mode == 2) {
              if (rem[dd] > 0) { rem[dd]--; px[dd + 1] = px[dd]; py[dd + 1] = py[dd]; pz[dd + 1] = pz[dd]; dd++; mode = 0; continue; }
              mode = 1;
              continue;
            }
            dd--;
            if (dd < 0) { *d = 0; *back = 0; return 0; }
            int ty = ops[dd * 4];
            if (ty == OP_COUNT || ty == OP_NOISE_THRESHOLD || ty == OP_NOISE_COUNT) mode = 2;
          }
        }
        /* a nested placed feature's filters (rarity, block predicate) and scans, in order; a scan moves *y */
        int dk_filters(Dk* k, __global const int* fs, int x, int* y, int z) {
          for (int q = 0; q < fs[1]; q++) {
            __global const int* op = fs + DK_HEAD + q * 4;
            if (op[0] == OP_RARITY) { if (!(br_nextFloat(&k->r) < as_float(op[1]))) return 0; }
            else if (op[0] == OP_FILTER) { if (!dk_pred(k, op[1], x, *y, z)) return 0; }
            else if (op[0] == OP_ENV_SCAN) { if (!dk_envscan(k, op, x, y, z)) return 0; }
            else { k->flag |= 2; return 0; }
            if (k->flag & 2) return 0;
          }
          return 1;
        }
        """;

    /** The configured features that place blocks themselves, after the tree source. */
    private static final String LEAF = """
        /* BlockState.canSurvive by the plant's rule (mode, predicate of the block below) */
        int dk_survive(Dk* k, int mode, int pred, int x, int y, int z) {
          return mode == SV_ALWAYS ? 1 : mode == SV_NEVER ? 0 : mode == SV_MULTI ? dk_multi(k, pred, x, y, z) : dk_ask(k, x, y - 1, z, pred);
        }
        /* ---- multiface growth (glow lichen, MultifaceSpreader's default config); directions by ordinal: down, up, north, south, west, east */
        __constant int MF_DX[6] = { 0, 0, 0, 0, -1, 1 };
        __constant int MF_DY[6] = { -1, 1, 0, 0, 0, 0 };
        __constant int MF_DZ[6] = { 0, 0, -1, 1, 0, 0 };
        /* Util.shuffle */
        void mf_shuffle(Dk* k, int* l, int n) {
          for (int j = n; j > 1; j--) { int q = br_nextInt(&k->r, j); int t = l[j - 1]; l[j - 1] = l[q]; l[q] = t; }
        }
        /* MultifaceBlock.getStateForPlacement of the state at a block: the new state's code, or -1 */
        int mf_placement(Dk* k, __global const int* pr, int x, int y, int z, int d) {
          int cur = dk_value(k, x, y, z, pr[0]);
          // isValidStateForPlacement (every face supported)
          if (cur >= 0 && ((cur >> d) & 1)) return -1;
          if (!dk_ask(k, x + MF_DX[d], y + MF_DY[d], z + MF_DZ[d], pr[4 + d])) return -1;
          int code = cur >= 0 ? cur : (pr[19] && dk_ask(k, x, y, z, P_WATER_SOURCE)) ? pr[21] | 64 : pr[21];
          return code | (1 << d);
        }
        /* MultifaceSpreader.spreadFromFaceTowardRandomDirection from the state just placed (code) */
        void mf_spread(Dk* k, __global const int* pr, int x, int y, int z, int code, int face) {
          int dirs[6] = { 0, 1, 2, 3, 4, 5 };
          mf_shuffle(k, dirs, 6);
          for (int i = 0; i < 6 && !(k->flag & 2); i++) {
            int d = dirs[i];
            if ((d >> 1) == (face >> 1) || !(((code >> face) & 1) && !((code >> d) & 1))) continue;
            for (int t = 0; t < 3; t++) {
              // SAME_POSITION, SAME_PLANE, WRAP_AROUND
              int sx = x, sy = y, sz = z, sf = d;
              if (t >= 1) { sx += MF_DX[d]; sy += MF_DY[d]; sz += MF_DZ[d]; sf = face; }
              if (t == 2) { sx += MF_DX[face]; sy += MF_DY[face]; sz += MF_DZ[face]; sf = d ^ 1; }
              // DefaultSpreaderConfig.canSpreadInto: replaceable, and isValidStateForPlacement
              if (!dk_ask(k, sx, sy, sz, pr[20])) continue;
              int cur = dk_value(k, sx, sy, sz, pr[0]);
              if ((cur >= 0 && ((cur >> sf) & 1)) || !dk_ask(k, sx + MF_DX[sf], sy + MF_DY[sf], sz + MF_DZ[sf], pr[4 + sf])) continue;
              // spreadToFace: placeBlock (WorldGenRegion.setBlock answers true outside the build height too)
              int nc = mf_placement(k, pr, sx, sy, sz, sf);
              if (nc < 0) break;
              if (sy >= OD_MINY && sy < OD_MINY + OD_H) {
                dk_tick(k, sx, sy, sz, EF_POST, 0, 0);
                dk_set(k, sx, sy, sz, k->aux[pr[1] + nc], 2);
              }
              return;
            }
          }
        }
        /* MultifaceGrowthFeature.placeGrowthIfPossible */
        int mf_grow(Dk* k, __global const int* pr, int x, int y, int z, int* l, int n) {
          for (int i = 0; i < n; i++) {
            int d = l[i];
            if (!dk_ask(k, x + MF_DX[d], y + MF_DY[d], z + MF_DZ[d], pr[3])) continue;
            int nc = mf_placement(k, pr, x, y, z, d);
            if (nc < 0) return 0;
            dk_set(k, x, y, z, k->aux[pr[1] + nc], 3);
            dk_tick(k, x, y, z, EF_POST, 0, 0);
            if (br_nextFloat(&k->r) < as_float(pr[11])) mf_spread(k, pr, x, y, z, nc, d);
            return 1;
          }
          return 0;
        }
        /* Biome.coldEnoughToSnow at a block of biome bi (the surface's climate table and temperature noises) */
        int dk_cold(Dk* k, int bi, int x, int y, int z) {
          __global const int* info = k->sp + SF_TINFO + bi * 2;
          float f = as_float(info[1]);
          if (info[0] & 4) {
            double d0 = sx_frozen(k->sp, (double) x * 0.05, (double) z * 0.05) * 7.0;
            double d1 = sx_info(k->sp, (double) x * 0.2, (double) z * 0.2);
            double d2 = d0 + d1;
            if (d2 < 0.3) { double d3 = sx_info(k->sp, (double) x * 0.09, (double) z * 0.09); if (d3 < 0.8) f = 0.2f; }
          }
          float t = f;
          if (y > 80) {
            float f1 = (float) (sx_temperature(k->sp, (double) ((float) x / 8.0f), (double) ((float) z / 8.0f)) * 8.0);
            t = f - (f1 + (float) y - 80.0f) * 0.05f / 40.0f;
          }
          return !(t >= 0.15f);
        }
        /* block light below 10 there: taken as so (nothing is lit before the LIGHT step), recorded for the CPU to ask the level */
        int dk_light(Dk* k, int x, int y, int z) {
          int ci = od_ci(x, z, k->bxo, k->bzo);
          if (ci < 0) { k->flag |= 2; return 0; }
          dk_read(k, (RT_LIGHT << 24) | (ci << 20) | ((y - OD_MINY) << 8) | (((z - k->bzo) & 15) << 4) | ((x - k->bxo) & 15), 0);
          return 1;
        }
        /* one column of SnowAndFreezeFeature.place; the biome is the top block's */
        void dk_snow_column(Dk* k, __global const int* pr, int px, int pz) {
          int top = dk_height(k, 4, px, pz), lo = top - 1;
          int bi = od_zoom(k->win, k->wqx0, k->wqz0, k->sp, px, top, pz);
          // Biome.shouldFreeze (not at the edge only)
          if (dk_cold(k, bi, px, lo, pz) && lo >= OD_MINY && lo < OD_MINY + OD_H && dk_light(k, px, lo, pz) && dk_ask(k, px, lo, pz, pr[0]))
            dk_set(k, px, lo, pz, pr[1], 2);
          // Biome.shouldSnow
          if (dk_cold(k, bi, px, top, pz) && top >= OD_MINY && top < OD_MINY + OD_H && dk_light(k, px, top, pz) && dk_ask(k, px, top, pz, pr[3])
              && dk_survive(k, pr[4], pr[5], px, top, pz)) {
            dk_set(k, px, top, pz, pr[2], 2);
            int sn = dk_value(k, px, lo, pz, V_SNOWY);
            if (sn >= 0 && !(k->flag & 2)) dk_set(k, px, lo, pz, sn, 2);
          }
        }
        /* the configured features that place blocks themselves */
        int dk_leaf(Dk* k, __global const int* fs, int x, int y, int z) {
          int kind = fs[0];
          __global const int* pr = fs + DK_HEAD + fs[1] * 4;
          if (kind == K_SIMPLE) {
            // SimpleBlockFeature.place
            __global const int* p = k->aux + dk_state(&k->r, k->aux, pr[0]);
            int s = p[0], mode = p[1], up = p[3];
            int ok = dk_survive(k, mode, p[2], x, y, z);
            if (!ok || (k->flag & 2)) return 0;
            if (up >= 0) {
              // DoublePlantBlock.placeAt, after the free block above
              if (!dk_ask(k, x, y + 1, z, P_AIR)) return 0;
              dk_set(k, x, y, z, s, 2);
              dk_set(k, x, y + 1, z, up, 2);
            } else {
              dk_set(k, x, y, z, s, 2);
            }
            return 1;
          }
          if (kind == K_SPRING) {
            // SpringFeature.place
            int id = pr[0], tick = pr[1], v = pr[5];
            if (!dk_ask(k, x, y + 1, z, v)) return 0;
            if (pr[2] && !dk_ask(k, x, y - 1, z, v)) return 0;
            int air = dk_ask(k, x, y, z, P_AIR);
            if (!air && !dk_ask(k, x, y, z, v)) return 0;
            int rock = dk_ask(k, x - 1, y, z, v);
            rock += dk_ask(k, x + 1, y, z, v);
            rock += dk_ask(k, x, y, z - 1, v);
            rock += dk_ask(k, x, y, z + 1, v);
            rock += dk_ask(k, x, y - 1, z, v);
            int hole = dk_ask(k, x - 1, y, z, P_AIR);
            hole += dk_ask(k, x + 1, y, z, P_AIR);
            hole += dk_ask(k, x, y, z - 1, P_AIR);
            hole += dk_ask(k, x, y, z + 1, P_AIR);
            hole += dk_ask(k, x, y - 1, z, P_AIR);
            if (rock == pr[3] && hole == pr[4] && !(k->flag & 2)) {
              dk_set(k, x, y, z, id, 2);
              dk_tick(k, x, y, z, EF_TICK_FLUID, tick, 0);
              return 1;
            }
            return 0;
          }
          if (kind == K_TREE) return tr_place(k, pr, x, y, z);
          if (kind == K_SEAGRASS) {
            // SeagrassFeature.place
            int a = br_nextInt(&k->r, 8); int dx = a - br_nextInt(&k->r, 8);
            a = br_nextInt(&k->r, 8); int dz = a - br_nextInt(&k->r, 8);
            int px = x + dx, pz = z + dz, py = dk_height(k, HM_OF, px, pz);
            if (!dk_ask(k, px, py, pz, pr[8])) return 0;
            int tall = br_nextDouble(&k->r) < (double) as_float(pr[0]);
            int b = tall ? 4 : 1;
            if (!dk_survive(k, pr[b + 1], pr[b + 2], px, py, pz) || (k->flag & 2)) return 0;
            if (tall) {
              if (dk_ask(k, px, py + 1, pz, pr[8])) { dk_set(k, px, py, pz, pr[4], 2); dk_set(k, px, py + 1, pz, pr[7], 2); }
            } else dk_set(k, px, py, pz, pr[1], 2);
            return 1;
          }
          if (kind == K_KELP) {
            // KelpFeature.place
            int w = pr[0], yy = dk_height(k, HM_OF, x, z), c = 0;
            if (!dk_ask(k, x, yy, z, w)) return 0;
            int n = 1 + br_nextInt(&k->r, 10);
            for (int l = 0; l <= n && !(k->flag & 2); l++) {
              if (dk_ask(k, x, yy, z, w) && dk_ask(k, x, yy + 1, z, w) && dk_survive(k, pr[2], pr[3], x, yy, z)) {
                if (l == n) { int age = br_nextInt(&k->r, 4); dk_set(k, x, yy, z, pr[7 + age], 2); c++; }
                else dk_set(k, x, yy, z, pr[1], 2);
              } else if (l > 0) {
                if (dk_survive(k, pr[4], pr[5], x, yy - 1, z) && !dk_ask(k, x, yy - 2, z, pr[6])) {
                  int age = br_nextInt(&k->r, 4); dk_set(k, x, yy - 1, z, pr[7 + age], 2); c++;
                }
                break;
              }
              yy++;
            }
            return c > 0;
          }
          if (kind == K_PICKLE) {
            // SeaPickleFeature.place
            int n = dk_int(&k->r, k->aux, pr[0]), c = 0;
            for (int q = 0; q < n && !(k->flag & 2); q++) {
              int a = br_nextInt(&k->r, 8); int dx = a - br_nextInt(&k->r, 8);
              a = br_nextInt(&k->r, 8); int dz = a - br_nextInt(&k->r, 8);
              int px = x + dx, pz = z + dz, py = dk_height(k, HM_OF, px, pz);
              __global const int* p = pr + 2 + br_nextInt(&k->r, 4) * 3;
              if (dk_ask(k, px, py, pz, pr[1]) && dk_survive(k, p[1], p[2], px, py, pz)) { dk_set(k, px, py, pz, p[0], 2); c++; }
            }
            return c > 0;
          }
          if (kind == K_MULTIFACE) {
            // MultifaceGrowthFeature.place
            if (!dk_ask(k, x, y, z, pr[2])) return 0;
            int n = pr[12], l[6];
            for (int i = 0; i < n; i++) l[i] = pr[13 + i];
            mf_shuffle(k, l, n);
            if (mf_grow(k, pr, x, y, z, l, n)) return 1;
            for (int i = 0; i < n && !(k->flag & 2); i++) {
              int d = l[i], l1[6], n1 = 0;
              for (int q = 0; q < n; q++) if (pr[13 + q] != (d ^ 1)) l1[n1++] = pr[13 + q];
              mf_shuffle(k, l1, n1);
              // vanilla sets the position to origin + direction each step
              int px = x + MF_DX[d], py = y + MF_DY[d], pz = z + MF_DZ[d];
              for (int s = 0; s < pr[10] && !(k->flag & 2); s++) {
                if (!dk_ask(k, px, py, pz, pr[2]) && dk_value(k, px, py, pz, pr[0]) < 0) break;
                if (mf_grow(k, pr, px, py, pz, l1, n1)) return 1;
              }
            }
            return 0;
          }
          if (kind == K_SNOW) {
            // SnowAndFreezeFeature.place over the chunk's columns (the origin is its corner)
            if ((x >> 4) != k->cx || (z >> 4) != k->cz) { k->flag |= 2; return 0; }
            for (int i = 0; i < 16; i++) for (int jj = 0; jj < 16 && !(k->flag & 2); jj++) dk_snow_column(k, pr, x + i, z + jj);
            return 1;
          }
          if (kind == K_MUSHROOM) {
            // AbstractHugeMushroomFeature.place (red: HugeRedMushroomFeature, else brown)
            int red = pr[0], fr = pr[1];
            int h = br_nextInt(&k->r, 3) + 4;
            if (br_nextInt(&k->r, 12) == 0) h *= 2;
            if (!(y >= OD_MINY + 1 && y + h + 1 < OD_MINY + OD_H)) return 0;
            if (!dk_ask(k, x, y - 1, z, pr[4])) return 0;
            // isValidPosition: getTreeRadiusForHeight(-1, -1, radius, j) is 0 for red, the radius above 3 for brown
            for (int j = 0; j <= h; j++) {
              int rr = red || j <= 3 ? 0 : fr;
              for (int l = -rr; l <= rr; l++) for (int i1 = -rr; i1 <= rr; i1++) {
                if (!dk_ask(k, x + l, y + j, z + i1, P_AIR_OR_LEAVES) || (k->flag & 2)) return 0;
              }
            }
            __global const int* cap = k->aux + pr[3];
            if (red) {
              for (int i = h - 3; i <= h; i++) {
                int jr = i < h ? fr : fr - 1, kk = fr - 2;
                for (int l = -jr; l <= jr; l++) for (int i1 = -jr; i1 <= jr; i1++) {
                  int f4 = l == -jr || l == jr, f5 = i1 == -jr || i1 == jr;
                  if ((i >= h || f4 != f5) && !dk_ask(k, x + l, y + i, z + i1, pr[5])) {
                    int idx = ((i >= h - 1) << 4) | ((l < -kk) << 3) | ((l > kk) << 2) | ((i1 < -kk) << 1) | (i1 > kk);
                    dk_set(k, x + l, y + i, z + i1, cap[idx], 3);
                  }
                }
              }
            } else {
              int i = fr;
              for (int j = -i; j <= i; j++) for (int kk = -i; kk <= i; kk++) {
                int f = j == -i, f1 = j == i, f2 = kk == -i, f3 = kk == i, f4 = f || f1, f5 = f2 || f3;
                if ((!f4 || !f5) && !dk_ask(k, x + j, y + h, z + kk, pr[5])) {
                  int f6 = f || (f5 && j == 1 - i), f7 = f1 || (f5 && j == i - 1), f8 = f2 || (f4 && kk == 1 - i), f9 = f3 || (f4 && kk == i - 1);
                  dk_set(k, x + j, y + h, z + kk, cap[(f6 << 3) | (f7 << 2) | (f8 << 1) | f9], 3);
                }
              }
            }
            for (int i = 0; i < h && !(k->flag & 2); i++) if (!dk_ask(k, x, y + i, z, pr[5])) dk_set(k, x, y + i, z, pr[2], 3);
            return 1;
          }
          if (kind == K_VINES) {
            // VinesFeature.place
            if (!dk_ask(k, x, y, z, P_AIR)) return 0;
            for (int d = 1; d < 6; d++) {
              if (dk_ask(k, x + MF_DX[d], y + MF_DY[d], z + MF_DZ[d], pr[d - 1])) { dk_set(k, x, y, z, pr[4 + d], 2); return 1; }
            }
            return 0;
          }
          if (kind == K_BLOB) {
            // BlockBlobFeature.place
            int px = x, py = y, pz = z;
            for (; py > OD_MINY + 3 && !(k->flag & 2); py--) if (dk_ask(k, px, py - 1, pz, pr[1])) break;
            if (py <= OD_MINY + 3 || (k->flag & 2)) return 0;
            for (int l = 0; l < 3; l++) {
              int i = br_nextInt(&k->r, 2); int jj = br_nextInt(&k->r, 2); int kk = br_nextInt(&k->r, 2);
              float f = (float) (i + jj + kk) * 0.333f + 0.5f;
              double lim = (double) (f * f);
              for (int bz = pz - kk; bz <= pz + kk; bz++)
                for (int by = py - jj; by <= py + jj; by++)
                  for (int bx = px - i; bx <= px + i; bx++) {
                    double ddx = (double) (bx - px), ddy = (double) (by - py), ddz = (double) (bz - pz);
                    if (ddx * ddx + ddy * ddy + ddz * ddz <= lim) dk_set(k, bx, by, bz, pr[0], 3);
                  }
              int ox = -1 + br_nextInt(&k->r, 2); int oy = -br_nextInt(&k->r, 2); int oz = -1 + br_nextInt(&k->r, 2);
              px += ox; py += oy; pz += oz;
            }
            return 1;
          }
          if (kind == K_DISK) {
            // DiskFeature.place: the columns in BlockPos.betweenClosed's order (x fastest), each from the top
            int top = y + pr[1], bot = y - pr[1] - 1, l = dk_int(&k->r, k->aux, pr[0]), any = 0;
            for (int dz = -l; dz <= l; dz++) {
              for (int dx = -l; dx <= l; dx++) {
                if (dx * dx + dz * dz > l * l) continue;
                int px = x + dx, pz = z + dz, last = 0;
                for (int yy = top; yy > bot; yy--) {
                  if (k->flag & 2) return 0;
                  if (dk_pred(k, pr[2], px, yy, pz)) {
                    int prov = pr[3];
                    for (int q = 0; q < pr[4]; q++) if (dk_pred(k, pr[5 + q * 2], px, yy, pz)) { prov = pr[6 + q * 2]; break; }
                    int rec = dk_state(&k->r, k->aux, prov);
                    dk_set(k, px, yy, pz, k->aux[rec], 2);
                    if (!last) dk_post_above(k, px, yy, pz);
                    any = 1; last = 1;
                  } else last = 0;
                }
              }
            }
            return any;
          }
          if (kind == K_COLUMN) {
            // BlockColumnFeature.place
            int dx = pr[0], dy = pr[1], dz = pr[2], n = pr[5], h[8], tot = 0;
            for (int q = 0; q < n; q++) { h[q] = dk_int(&k->r, k->aux, pr[6 + q * 2]); tot += h[q]; }
            if (tot == 0) return 0;
            int mx = x + dx, my = y + dy, mz = z + dz;
            for (int l = 0; l < tot; l++) {
              if (!dk_pred(k, pr[3], mx, my, mz)) {
                // truncate
                int i = tot - l, st = pr[4] ? 1 : -1, q1 = pr[4] ? n : -1;
                for (int q = pr[4] ? 0 : n - 1; q != q1 && i > 0; q += st) { int m = min(h[q], i); i -= m; h[q] -= m; }
                break;
              }
              if (k->flag & 2) return 0;
              mx += dx; my += dy; mz += dz;
            }
            int px = x, py = y, pz = z;
            for (int q = 0; q < n; q++) {
              for (int j = 0; j < h[q] && !(k->flag & 2); j++) {
                int rec = dk_state(&k->r, k->aux, pr[7 + q * 2]);
                dk_set(k, px, py, pz, k->aux[rec], 2);
                px += dx; py += dy; pz += dz;
              }
            }
            return 1;
          }
          k->flag |= 2;
          return 0;
        }
        int dk_nested2(Dk* k, int e, int x, int y, int z) {
          __global const int* fs = k->fspec + k->fspec[e];
          if (!dk_filters(k, fs, x, &y, z)) return 0;
          return dk_leaf(k, fs, x, y, z);
        }
        """;

    /** A composite configured feature (patch, selectors). Its children are placed through NEXT. Leaf children are placed directly. */
    private static final String COMPOSITE = """
        int NAME(Dk* k, __global const int* fs, int x, int y, int z) {
          int kind = fs[0];
          __global const int* pr = fs + DK_HEAD + fs[1] * 4;
          if (kind == K_PATCH) {
            // RandomPatchFeature.place
            int c = 0, j = pr[1], kk = pr[2];
            for (int l = 0; l < pr[0]; l++) {
              int a = br_nextInt(&k->r, j); int dx = a - br_nextInt(&k->r, j);
              a = br_nextInt(&k->r, kk); int dy = a - br_nextInt(&k->r, kk);
              a = br_nextInt(&k->r, j); int dz = a - br_nextInt(&k->r, j);
              if (NEXT(k, pr[3], x + dx, y + dy, z + dz)) c++;
              if (k->flag & 2) return 0;
            }
            return c > 0;
          }
          if (kind == K_SELECT) {
            for (int q = 0; q < pr[0]; q++) {
              if (br_nextFloat(&k->r) < as_float(pr[2 + q * 2])) return NEXT(k, pr[3 + q * 2], x, y, z);
            }
            return NEXT(k, pr[1], x, y, z);
          }
          if (kind == K_SSELECT) return NEXT(k, pr[1 + br_nextInt(&k->r, pr[0])], x, y, z);
          if (kind == K_BSELECT) { int b = br_nextBool(&k->r); return NEXT(k, b ? pr[0] : pr[1], x, y, z); }
          return dk_leaf(k, fs, x, y, z);
        }
        """;

    private static final String KERNEL = """
        __kernel void k_dgather(__global const ulong* pool, __global const int* slots, int n, __global ulong* out) {
          long per = OD_SLOT / 4;
          long g = get_global_id(0);
          if (g >= (long) n * per) return;
          int e = (int) (g / per);
          out[g] = pool[(long) slots[e] * per + g % per];
        }
        /* the blocks a job speculates on (the GPU results of decorations before it), over its gathered copy: (job << 24 | where, state) */
        __kernel void k_doverlay(__global ushort* sc, __global const int* ov, int n) {
          int g = get_global_id(0);
          if (g >= n) return;
          int a = ov[g * 2], j = (int) ((uint) a >> 24), w = a & 0xffffff;
          int ci = w >> 20, yi = (w >> 8) & 0xfff, lz = (w >> 4) & 15, lx = w & 15;
          sc[((long) j * 9 + ci) * OD_SLOT + (lx * 16 + lz) * OD_H + yi] = (ushort) ov[g * 2 + 1];
        }
        __kernel __attribute__((reqd_work_group_size(OD_WG, 1, 1)))
        void k_decor(__global ushort* sc0, __global const int* hw0, __global int* hf0, __global const int* jhead, __global const int* jfeat,
                     __global const int* fspec, __global const float* fsin, __global const int* aux, __global const uint* pb,
                     __global const uint* bbits, int bw, __global const ushort* winPool, __global const int* spt,
                     __global const uint* sinFix, __global const uint* cosFix,
                     __global int* first0, __global int* cand0, __global int* dec0, __global int* dl0, __global int* fres0, __global int* wr0,
                     __global int* rd0, int nstates, __global ulong* prof0, __global int* tr0, __global const float* msin, __global const int* vt,
                     __global const uint* fsinFix, __global const uint* fcosFix) {
          int j = get_group_id(0), t = get_local_id(0);
          __global ushort* sc = sc0 + (long) j * 9 * OD_SLOT;
          __global const int* hw = hw0 + j * 9 * 256 * 2;
          __global int* hf = hf0 + j * 9 * 256 * 4;
          __global int* fi = first0 + (long) j * OD_L2;
          __global int* ca = cand0 + (long) j * OD_CAND;
          __global int* de = dec0 + (long) j * OD_CAND;
          __global int* dl = dl0 + (long) j * OD_CAND;
          __global int* fr = fres0 + j * OD_MAXF * 8;
          __global int* wr = wr0 + (long) j * OD_MAXW * 2;
          __global int* rd = rd0 + (long) j * OD_MAXR * 2;
          __global const int* jh = jhead + j * 4;
          int nfeat = jh[0], cx = jh[1], cz = jh[2];
          __global const ushort* win = winPool + (long) jh[3] * OD_WIN;
          int wqx0 = cx * 4 - 1, wqz0 = cz * 4 - 1, bxo = cx * 16 - 16, bzo = cz * 16 - 16;
          __local double sx[64], sy[64], sz[64], sr[64], dr[64];
          __local int sb[64 * 6], sst[65], svol[64], lsum[OD_WG], llose2[2];
          __local double sq[64 * 30];
          __local int lcmd, lX, lY, lZ, lflag, lgate, lnw, lnr, lrc, lany, lplaced, ltot, lk, ll, li1, lj1, lk1, ljob;
          __local double ld0, ld1, ld2, ld3, ld4, ld5;
          Dk k;
          int d = 0, back = 0;
          int rem[OD_MAXOPS + 1], px[OD_MAXOPS + 1], py[OD_MAXOPS + 1], pz[OD_MAXOPS + 1];
          #ifdef OD_PROF
          ulong prof[16];
          for (int i = 0; i < 16; i++) prof[i] = 0;
          ulong tp = od_clk(), t00 = tp;
          #endif
          for (int i = t; i < OD_L2; i += OD_WG) fi[i] = OD_IMAX;
          for (int i = t; i < 9 * 256 * 4; i += OD_WG) hf[i] = DK_UNK;
          if (t == 0) { lnw = 0; lnr = 0; lrc = 0; ljob = 0; }
          k.sc = sc; k.hw = hw; k.hf = hf; k.wr = wr; k.rd = rd; k.nw = 0; k.nr = 0; k.flag = 0; k.bxo = bxo; k.bzo = bzo;
          k.nstates = nstates; k.cx = cx; k.cz = cz; k.pb = pb; k.aux = aux; k.fspec = fspec; k.bbits = bbits; k.bw = bw;
          k.win = win; k.wqx0 = wqx0; k.wqz0 = wqz0; k.sp = spt;
          k.tr = tr0 + (long) j * TR_SCRATCH; k.msin = msin; k.vt = vt; k.fsinFix = fsinFix; k.fcosFix = fcosFix;
          k.maxw = OD_MAXW; k.maxr = OD_MAXR;
          barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
          for (int f = 0; f < nfeat; f++) {
            __global const int* jf = jfeat + (j * OD_MAXF + f) * 8;
            __global const int* fs = fspec + fspec[jf[0]];
            int kind = fs[0], nops = fs[1];
            __global const int* ops = fs + DK_HEAD;
            __global const int* pr = ops + nops * 4;
            if (t == 0) {
              k.r.x.lo = (ulong) (uint) jf[1] | ((ulong) (uint) jf[2] << 32);
              k.r.x.hi = (ulong) (uint) jf[3] | ((ulong) (uint) jf[4] << 32);
              k.r.n = 0; d = 0; back = 0; k.slot = jf[5]; k.flag = ljob;
              px[0] = cx * 16; py[0] = OD_MINY; pz[0] = cz * 16;
              lflag = ljob; lplaced = 0;
            }
            barrier(CLK_LOCAL_MEM_FENCE);
            if (kind == K_SNOW) {
              // snow and freeze: its columns are independent and it draws nothing, so a column per thread, each with its own logs
              // (the ores' scratch), laid out afterwards in vanilla's column order
              while (lflag == 0) {
                if (t == 0) {
                  k.nw = lnw; k.nr = lnr;
                  lcmd = dk_walk(&k, &d, &back, rem, px, py, pz, ops, nops);
                  if (k.flag & 2) { lflag |= k.flag; lcmd = 0; }
                  lnw = min(k.nw, OD_MAXW); lnr = min(k.nr, OD_MAXR);
                  lX = px[d]; lY = py[d]; lZ = pz[d];
                }
                barrier(CLK_LOCAL_MEM_FENCE);
                if (lcmd == 0) break;
                if ((lX >> 4) != cx || (lZ >> 4) != cz) { if (t == 0) lflag |= 2; barrier(CLK_LOCAL_MEM_FENCE); break; }
                Dk q = k;
                q.wr = de + t * 8; q.rd = ca + t * 32; q.nw = 0; q.nr = 0; q.maxw = 8; q.maxr = 32; q.flag = 0;
                dk_snow_column(&q, pr, lX + (t >> 4), lZ + (t & 15));
                if (q.flag & 2) atomic_or(&lflag, 2);
                int cw = min(q.nw, 8), cr = min(q.nr, 32);
                int pw = od_scan(lsum, t, cw), tw = lsum[OD_WG - 1];
                barrier(CLK_LOCAL_MEM_FENCE);
                int prd = od_scan(lsum, t, cr), trd = lsum[OD_WG - 1];
                if (lnw + tw > OD_MAXW || lnr + trd > OD_MAXR) { atomic_or(&lflag, 2 | 4); }
                else {
                  for (int e = 0; e < cw * 2; e++) wr[(lnw + pw) * 2 + e] = q.wr[e];
                  for (int e = 0; e < cr * 2; e++) rd[(lnr + prd) * 2 + e] = q.rd[e];
                }
                barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
                if (t == 0) {
                  if (lflag == 0) { lnw += tw; lnr += trd; lplaced = 1; }
                  k.nw = lnw; k.nr = lnr;
                }
                barrier(CLK_LOCAL_MEM_FENCE);
              }
              if (t == 0) k.flag = lflag;
              barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
            } else if (kind != K_ORE) {
              // the generic features: one thread, in order on the copy (each sees the ones before it)
              if (t == 0) {
                k.nw = lnw; k.nr = lnr;
                int any = 0;
                while (k.flag == 0 && dk_walk(&k, &d, &back, rem, px, py, pz, ops, nops)) {
                  if (dk_cfg0(&k, fs, px[d], py[d], pz[d])) any = 1;
                }
                lplaced = any;
                lflag = k.flag;
                lnw = min(k.nw, OD_MAXW);
                lnr = min(k.nr, OD_MAXR);
              }
              barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
            } else {
            int size = pr[0], nt = pr[2], msin = pr[3];
            float chance = as_float(pr[1]);
            __global const int* tg = pr + 4;
            while (lflag == 0) {
              if (t == 0) {
                k.nw = lnw; k.nr = lnr;
                lcmd = dk_walk(&k, &d, &back, rem, px, py, pz, ops, nops);
                if (k.flag & 2) { lflag |= k.flag; lcmd = 0; }
                lnw = min(k.nw, OD_MAXW); lnr = min(k.nr, OD_MAXR);
                lX = px[d]; lY = py[d]; lZ = pz[d];
              }
              barrier(CLK_LOCAL_MEM_FENCE);
              OD_T(0)
              if (lcmd == 0) break;
              OD_N(10, 1)
              // ---- OreFeature.place at (lX, lY, lZ) ----
              if (t == 0) {
                int X = lX, Y = lY, Z = lZ;
                int k24 = br_next(&k.r, 24);
                int bad = 0;
                double sn = os_sin(k24, sinFix, &bad), cs = os_cos(k24, cosFix, &bad);
                float f1 = (float) size / 8.0f;
                int ii = od_ceilf(((float) size / 16.0f * 2.0f + 1.0f) / 2.0f);
                ld0 = (double) X + sn * (double) f1;
                ld1 = (double) X - sn * (double) f1;
                ld2 = (double) Z + cs * (double) f1;
                ld3 = (double) Z - cs * (double) f1;
                int a4 = br_nextInt(&k.r, 3);
                ld4 = (double) (Y + a4 - 2);
                int a5 = br_nextInt(&k.r, 3);
                ld5 = (double) (Y + a5 - 2);
                int c1 = od_ceilf(f1);
                lk = X - c1 - ii; ll = Y - 2 - ii; li1 = Z - c1 - ii; lj1 = 2 * (c1 + ii); lk1 = 2 * (2 + ii);
                lgate = 0; lany = 0;
                if (bad) lflag |= 2;
              }
              barrier(CLK_LOCAL_MEM_FENCE);
              OD_T(1)
              // the ocean-floor gate over the blob's columns
              if (lflag == 0) {
                int w1 = lj1 + 1, ncol = w1 * w1;
                for (int q = t; q < ncol; q += OD_WG) {
                  int l1 = lk + q / w1, i2 = li1 + q % w1;
                  int ci = od_ci(l1, i2, bxo, bzo);
                  if (ci < 0) atomic_or(&lflag, 2);
                  else if (ll <= hw[(ci * 256 + ((i2 - bzo) & 15) * 16 + ((l1 - bxo) & 15)) * 2 + 1]) lgate = 1;
                }
              }
              barrier(CLK_LOCAL_MEM_FENCE);
              OD_T(2)
              if (lflag == 0 && lgate) {
                OD_N(11, 1)
                // ---- OreFeature.doPlace ----
                if (t == 0) {
                  for (int q = 0; q < size; q++) dr[q] = br_nextDouble(&k.r);
                }
                barrier(CLK_LOCAL_MEM_FENCE);
                for (int q = t; q < size; q += OD_WG) {
                  float ff = (float) q / (float) size;
                  sx[q] = ld0 + (double) ff * (ld1 - ld0);
                  sy[q] = ld4 + (double) ff * (ld5 - ld4);
                  sz[q] = ld2 + (double) ff * (ld3 - ld2);
                  double d3 = dr[q] * (double) size / 16.0;
                  sr[q] = ((double) fsin[msin + q] * d3 + 1.0) / 2.0;
                }
                barrier(CLK_LOCAL_MEM_FENCE);
                OD_T(3)
                // spheres inside larger ones: row by row as vanilla's double loop (in a row, the larger kills the smaller up to the
                // first that kills the row's own sphere; past it the row's sphere is gone and kills nothing)
                if (t == 0) llose2[0] = OD_IMAX;
                barrier(CLK_LOCAL_MEM_FENCE);
                int ar = 0;
                for (int l3 = 0; l3 < size - 1; l3++) {
                  double r3 = sr[l3];
                  if (!(r3 <= 0.0)) {
                    __local int* lp = llose2 + (ar & 1);
                    for (int i4 = l3 + 1 + t; i4 < size; i4 += OD_WG) {
                      double r4 = sr[i4];
                      if (!(r4 <= 0.0)) {
                        double d8 = sx[l3] - sx[i4], d10 = sy[l3] - sy[i4], d12 = sz[l3] - sz[i4], d14 = r3 - r4;
                        if (d14 * d14 > d8 * d8 + d10 * d10 + d12 * d12 && !(d14 > 0.0)) atomic_min(lp, i4);
                      }
                    }
                    barrier(CLK_LOCAL_MEM_FENCE);
                    int lose = *lp;
                    for (int i4 = l3 + 1 + t; i4 < size && i4 < lose; i4 += OD_WG) {
                      double r4 = sr[i4];
                      if (!(r4 <= 0.0)) {
                        double d8 = sx[l3] - sx[i4], d10 = sy[l3] - sy[i4], d12 = sz[l3] - sz[i4], d14 = r3 - r4;
                        if (d14 * d14 > d8 * d8 + d10 * d10 + d12 * d12 && d14 > 0.0) sr[i4] = -1.0;
                      }
                    }
                    if (t == 0) {
                      if (lose != OD_IMAX) sr[l3] = -1.0;
                      llose2[(ar + 1) & 1] = OD_IMAX;
                    }
                    barrier(CLK_LOCAL_MEM_FENCE);
                    ar++;
                  }
                }
                OD_T(4)
                // the boxes of the spheres left, and what the placement may read
                for (int q = t; q < size; q += OD_WG) {
                  double d9 = sr[q];
                  sb[q * 6 + 3] = 0; sb[q * 6 + 4] = 1; sb[q * 6 + 5] = 1;
                  svol[q] = 0;
                  if (!(d9 < 0.0)) {
                    int k4 = max(jfloor(sx[q] - d9), lk), l0 = max(jfloor(sy[q] - d9), ll), i10 = max(jfloor(sz[q] - d9), li1);
                    int j1x = max(jfloor(sx[q] + d9), k4), k1x = max(jfloor(sy[q] + d9), l0), l1x = max(jfloor(sz[q] + d9), i10);
                    int nx = j1x - k4 + 1, ny = k1x - l0 + 1, nz = l1x - i10 + 1;
                    if (nx > 10 || ny > 10 || nz > 10) atomic_or(&lflag, 2);
                    sb[q * 6] = k4; sb[q * 6 + 1] = l0; sb[q * 6 + 2] = i10; sb[q * 6 + 3] = nx; sb[q * 6 + 4] = ny; sb[q * 6 + 5] = nz;
                    svol[q] = nx * ny * nz;
                  }
                }
                barrier(CLK_LOCAL_MEM_FENCE);
                if (t == 0) {
                  int tot = 0;
                  for (int q = 0; q < size; q++) {
                    sst[q] = tot;
                    tot += svol[q];
                  }
                  sst[size] = tot;
                  ltot = tot;
                  if (tot > OD_CAND) lflag |= 2;
                }
                barrier(CLK_LOCAL_MEM_FENCE);
                int tot = ltot;
                // the squared terms of the inside test, which vanilla computes once per x, per y and per z of a sphere
                if (lflag == 0) {
                  for (int e = t; e < size * 30; e += OD_WG) {
                    int q = e / 30, a = (e % 30) / 10, i = e % 10;
                    double d9 = sr[q];
                    if (!(d9 < 0.0) && i < sb[q * 6 + 3 + a]) {
                      double c = a == 0 ? sx[q] : a == 1 ? sy[q] : sz[q];
                      double v = ((double) (sb[q * 6 + a] + i) + 0.5 - c) / d9;
                      sq[e] = v * v;
                    }
                  }
                }
                barrier(CLK_LOCAL_MEM_FENCE);
                OD_T(5)
                OD_N(12, tot)
                // A: the cells inside their sphere, and the first of each bitset index in vanilla's order
                if (lflag == 0) {
                  for (int idx = t; idx < tot; idx += OD_WG) {
                    int i2, j2, k2;
                    int q = od_cell(idx, sst, sb, size, &i2, &j2, &k2);
                    double s5 = sq[q * 30 + i2 - sb[q * 6]], s6 = sq[q * 30 + 10 + j2 - sb[q * 6 + 1]], s7 = sq[q * 30 + 20 + k2 - sb[q * 6 + 2]];
                    int in = s5 < 1.0 && s5 + s6 < 1.0 && s5 + s6 + s7 < 1.0 && !(j2 < OD_MINY || j2 >= OD_MINY + OD_H);
                    int l2 = -1;
                    if (in) {
                      l2 = i2 - lk + (j2 - ll) * lj1 + (k2 - li1) * lj1 * lk1;
                      if (l2 < 0 || l2 >= OD_L2) { atomic_or(&lflag, 2); l2 = -1; }
                      else atomic_min(fi + l2, idx);
                    }
                    ca[idx] = l2;
                  }
                }
                barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
                OD_T(6)
                // B: what each first visit does; the winner of an index frees it (a later reader then sees no match either)
                int ranB = lflag == 0;
                if (ranB) {
                  for (int idx = t; idx < tot; idx += OD_WG) {
                    int l2 = ca[idx], dv = -1, info = -1;
                    if (l2 >= 0 && fi[l2] == idx) {
                      fi[l2] = OD_IMAX;
                      dv = -2;
                      info = 0;
                      int i2, j2, k2;
                      od_cell(idx, sst, sb, size, &i2, &j2, &k2);
                      int s = od_at(sc, i2, j2, k2, bxo, bzo);
                      if (s < 0 || s >= nstates) atomic_or(&lflag, 2);
                      else {
                        int tm = 0;
                        for (int q2 = 0; q2 < nt; q2++) if (od_bit(pb, tg[q2 * 2], s)) tm |= 1 << q2;
                        info = tm & 31;
                        if (tm != 0) {
                          int q0 = 0;
                          while (((tm >> q0) & 1) == 0) q0++;
                          if (chance <= 0.0f) dv = od_dec(tg[q0 * 2 + 1], s);
                          else {
                            int adj = od_air(sc, pb, i2, j2, k2, bxo, bzo, nstates);
                            if (adj < 0) atomic_or(&lflag, 2);
                            else {
                              info |= adj << 5;
                              if (chance >= 1.0f) { if (!adj) dv = od_dec(tg[q0 * 2 + 1], s); }
                              else dv = OD_DRAW | (s << 9) | (adj << 8) | tm;
                            }
                          }
                        }
                      }
                    }
                    de[idx] = dv;
                    ca[idx] = info;
                  }
                } else {
                  for (int idx = t; idx < tot; idx += OD_WG) {
                    int l2 = ca[idx];
                    if (l2 >= 0) fi[l2] = OD_IMAX;
                  }
                }
                barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
                OD_T(7)
                // the air-exposure draws, in vanilla's order, for the cells that need one
                int per = (tot + OD_WG - 1) / OD_WG, a0 = min(tot, t * per), a1 = min(tot, a0 + per);
                if (lflag == 0 && chance > 0.0f && chance < 1.0f) {
                  int cnt = 0;
                  for (int idx = a0; idx < a1; idx++) {
                    int dv = de[idx];
                    if (dv >= 0 && (dv & OD_DRAW)) cnt++;
                  }
                  int p = od_scan(lsum, t, cnt);
                  int ndraw = lsum[OD_WG - 1];
                  for (int idx = a0; idx < a1; idx++) {
                    int dv = de[idx];
                    if (dv >= 0 && (dv & OD_DRAW)) dl[p++] = idx;
                  }
                  barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
                  if (t == 0) {
                    for (int q = 0; q < ndraw; q++) {
                      int idx = dl[q], dv = de[idx];
                      int tm = dv & 0xff, adj = (dv >> 8) & 1, s = (dv >> 9) & 0xffff, out = -2;
                      for (int q2 = 0; q2 < nt; q2++) {
                        if ((tm >> q2) & 1) {
                          int skip = br_nextFloat(&k.r) >= chance;
                          if (skip || !adj) { out = od_dec(tg[q2 * 2 + 1], s); break; }
                        }
                      }
                      de[idx] = out;
                    }
                  }
                  barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
                }
                OD_T(8)
                // C: the writes, in vanilla's order
                if (lflag == 0) {
                  int cnt = 0, any = 0;
                  for (int idx = a0; idx < a1; idx++) {
                    int dv = de[idx];
                    if (dv >= 0) { any = 1; if (!(dv & OD_NOOP)) cnt++; }
                  }
                  if (any) lany = 1;
                  int pos = lnw + od_scan(lsum, t, cnt);
                  int sum = lsum[OD_WG - 1];
                  for (int idx = a0; idx < a1; idx++) {
                    int dv = de[idx];
                    if (dv != -1) {
                      int i2, j2, k2;
                      od_cell(idx, sst, sb, size, &i2, &j2, &k2);
                      int ci = od_ci(i2, k2, bxo, bzo), lx = (i2 - bxo) & 15, lz = (k2 - bzo) & 15, yi = j2 - OD_MINY;
                      int where = (ci << 20) | (yi << 8) | (lz << 4) | lx;
                      // every first visit read the block (and, with air checks, its neighbours): with the target mask and air exposure the
                      // GPU found, whether it decided a write, and whether that write changed the block
                      int info = ca[idx];
                      int rec = where | ((info & 63) << 24) | (dv >= 0 ? 1 << 30 : 0) | (dv >= 0 && !(dv & OD_NOOP) ? (int) 0x80000000u : 0);
                      int rp = lnr + atomic_inc(&lrc);
                      if (rp < OD_MAXR) { rd[rp * 2] = rec; rd[rp * 2 + 1] = 0; }
                      else atomic_or(&lflag, 2 | 4);
                      if (dv >= 0 && !(dv & OD_NOOP)) {
                        int st = dv & 0xffff;
                        if (pos < OD_MAXW) {
                          wr[pos * 2] = where;
                          wr[pos * 2 + 1] = (EF_SECTION << 28) | st;
                          sc[ci * OD_SLOT + (lx * 16 + lz) * OD_H + yi] = (ushort) st;
                        } else atomic_or(&lflag, 2 | 4);
                        pos++;
                      }
                    }
                  }
                  barrier(CLK_LOCAL_MEM_FENCE);
                  if (t == 0) {
                    lnw += sum;
                    lnr = min(lnr + lrc, OD_MAXR);
                    lrc = 0;
                    if (lany) lplaced = 1;
                  }
                }
              }
              barrier(CLK_LOCAL_MEM_FENCE | CLK_GLOBAL_MEM_FENCE);
              OD_T(9)
            }
            }
            barrier(CLK_LOCAL_MEM_FENCE);
            if (t == 0) {
              fr[f * 8] = lplaced | (lflag ? 2 : 0) | (lflag & 4);
              fr[f * 8 + 1] = (int) (uint) k.r.x.lo; fr[f * 8 + 2] = (int) (uint) (k.r.x.lo >> 32);
              fr[f * 8 + 3] = (int) (uint) k.r.x.hi; fr[f * 8 + 4] = (int) (uint) (k.r.x.hi >> 32);
              fr[f * 8 + 5] = k.r.n; fr[f * 8 + 6] = min(lnw, OD_MAXW); fr[f * 8 + 7] = lnr;
              if (lflag & 4) ljob = 4;
            }
            barrier(CLK_LOCAL_MEM_FENCE);
          }
          #ifdef OD_PROF
          if (t == 0) {
            prof[13] = (ulong) nfeat;
            prof[15] = od_clk() - t00;
            for (int i = 0; i < 16; i++) prof0[j * 16 + i] = prof[i];
          }
          #endif
        }
        """;
}
