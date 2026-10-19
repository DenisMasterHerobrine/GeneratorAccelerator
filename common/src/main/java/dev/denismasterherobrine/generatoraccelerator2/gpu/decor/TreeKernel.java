package dev.denismasterherobrine.generatoraccelerator2.gpu.decor;

/**
 * {@code TreeFeature.place} for the GPU ({@link TreeSpec}), on one thread of a decoration job, as {@code TreeFast} runs it on the CPU.
 * It covers the free height, the trunk placer, the foliage placer, the decorators over the y-sorted position lists,
 * {@code updateLeaves}, and the shape whose edge {@code StructureTemplate.updateShapeAtEdge} visits.
 *
 * <p>In vanilla, the four position sets and the seven distance sets of updateLeaves are {@code java.util.HashSet<BlockPos>}s. Their
 * iteration order decides the decorators' order (a stable sort by y of that order). It also decides the order of updateLeaves, which
 * takes the first element again and again. The sets are kept here as the JDK keeps them, as {@code LeafSets} does on the CPU. The
 * table has buckets by {@code hash ^ hash >>> 16}, with chains in the order of their adds. It starts with 16 buckets and doubles past
 * 3/4 full. A resize keeps each chain's order. A nine-key chain doubles a table under 64. A chain that would become a tree bin sends
 * the tree to the CPU. Membership is a cell grid around the tree (32 x 64 x 32). The grid is stamped per tree, so it is never cleared.
 * A position outside the grid sends the tree to the CPU.
 *
 * <p>The edge pass is the CPU's. The GPU only foresees what the pass does to the GPU's copy, so that the features after the tree read
 * the blocks as they will be. Every face's two blocks are read and recorded. A block whose {@code updateShape} the GPU does not know
 * sends the tree to the CPU.
 */
final class TreeKernel {
    private TreeKernel() {}

    /** Ints of one job's tree scratch (cells + stamp, three node arrays, bucket heads and tails, two lists). */
    static final int CELLS = 32 * 32 * 64, NODES = 8192, CAP = 2048, SETS = 11, LIST = 8192;
    static final int SCRATCH = CELLS + 16 + 3 * NODES + 2 * SETS * CAP + 3 * LIST;

    static String source(float twoPi) {
        return "#define TR_TWO_PI as_float(0x" + Integer.toHexString(Float.floatToRawIntBits(twoPi)) + ")\n"
            + "#define TR_CELLS " + CELLS + "\n#define TR_NODES " + NODES + "\n#define TR_CAP " + CAP + "\n#define TR_SETS " + SETS + "\n#define TR_LIST " + LIST + "\n"
            + "#define TR_SCRATCH " + SCRATCH + "\n"
            + "#define T_STRAIGHT " + TreeSpec.T_STRAIGHT + "\n#define T_FORKING " + TreeSpec.T_FORKING + "\n#define T_GIANT " + TreeSpec.T_GIANT
            + "\n#define T_MEGA_JUNGLE " + TreeSpec.T_MEGA_JUNGLE + "\n#define T_DARK_OAK " + TreeSpec.T_DARK_OAK + "\n#define T_BENDING " + TreeSpec.T_BENDING
            + "\n#define T_CHERRY " + TreeSpec.T_CHERRY + "\n#define T_FANCY " + TreeSpec.T_FANCY + "\n"
            + "#define F_BLOB " + TreeSpec.F_BLOB + "\n#define F_SPRUCE " + TreeSpec.F_SPRUCE + "\n#define F_PINE " + TreeSpec.F_PINE + "\n#define F_ACACIA " + TreeSpec.F_ACACIA
            + "\n#define F_BUSH " + TreeSpec.F_BUSH + "\n#define F_FANCY " + TreeSpec.F_FANCY + "\n#define F_MEGA_JUNGLE " + TreeSpec.F_MEGA_JUNGLE
            + "\n#define F_MEGA_PINE " + TreeSpec.F_MEGA_PINE + "\n#define F_DARK_OAK " + TreeSpec.F_DARK_OAK + "\n#define F_RANDOM_SPREAD " + TreeSpec.F_RANDOM_SPREAD
            + "\n#define F_CHERRY " + TreeSpec.F_CHERRY + "\n"
            + "#define D_TRUNK_VINE " + TreeSpec.D_TRUNK_VINE + "\n#define D_LEAVE_VINE " + TreeSpec.D_LEAVE_VINE + "\n#define D_COCOA " + TreeSpec.D_COCOA
            + "\n#define D_ALTER_GROUND " + TreeSpec.D_ALTER_GROUND + "\n#define D_BEEHIVE " + TreeSpec.D_BEEHIVE + "\n"
            + "#define P_VALID_TREE " + Preds.VALID_TREE + "\n#define P_FREE " + Preds.FREE + "\n#define P_VINE " + Preds.VINE + "\n#define P_AIR_OR_LEAVES " + Preds.AIR_OR_LEAVES
            + "\n#define P_TRUNK_DIRT " + Preds.TRUNK_DIRT + "\n#define P_TREE_GROW " + Preds.TREE_GROW + "\n#define P_WATER_SOURCE " + Preds.WATER_SOURCE
            + "\n#define P_DIRT " + Preds.DIRT + "\n#define P_SNOW " + Preds.SNOW + "\n"
            + "#define V_LEAF_DIST " + Preds.V_LEAF_DIST + "\n#define V_DIST " + Preds.V_DIST + "\n#define V_UPS " + Preds.V_UPS + "\n#define V_SNOWY " + Preds.V_SNOWY
            + "\n#define V_NOT_SNOWY " + Preds.V_NOT_SNOWY + "\n#define UPS_SAME " + Preds.UPS_SAME + "\n#define UPS_SNOWY " + Preds.UPS_SNOWY + "\n"
            + "#define V_UNKNOWN ((int) 0x80000000)\n"
            + SOURCE;
    }

    private static final String SOURCE = """
        // ---- trees (gpu.decor.TreeKernel) ----
        #define TR_ROOTS 0
        #define TR_LOGS 1
        #define TR_LEAVES 2
        #define TR_DECO 3
        #define TR_LV0 4
        #define TR_SHAPE 0x800u
        #define TR_NONE ((int) 0x80000000)
        /* Direction.Plane.HORIZONTAL: north, east, south, west */
        __constant int TR_HX[4] = { 0, 1, 0, -1 };
        __constant int TR_HZ[4] = { -1, 0, 1, 0 };
        typedef struct { int x, y, z, r, dbl; } TrAtt;
        typedef struct {
          __global uint* mem; __global int* nn; __global int* nh; __global int* nc; __global int* bh; __global int* bt; __global int* l1; __global int* l2; __global int* l3;
          int nodes; uint stamp; int ox, oy, oz;
          int cap[TR_SETS], size[TR_SETS], low[TR_SETS];
          int bx0, by0, bz0, bx1, by1, bz1, any;
          __global const int* cst;
        } Tr;
        int tr_cell(Tr* t, int x, int y, int z) {
          int dx = x - t->ox, dy = y - t->oy, dz = z - t->oz;
          if (dx < 0 || dx >= 32 || dz < 0 || dz >= 32 || dy < 0 || dy >= 64) return -1;
          return (dy * 32 + dz) * 32 + dx;
        }
        int tr_cx(Tr* t, int c) { return t->ox + (c & 31); }
        int tr_cz(Tr* t, int c) { return t->oz + ((c >> 5) & 31); }
        int tr_cy(Tr* t, int c) { return t->oy + (c >> 10); }
        uint tr_bits(Tr* t, int c) { uint v = t->mem[c]; return (v >> 12) == t->stamp ? (v & 0xfffu) : 0u; }
        void tr_mark(Tr* t, int c, uint bit) { t->mem[c] = (t->stamp << 12) | tr_bits(t, c) | bit; }
        void tr_unmark(Tr* t, int c, uint bit) { t->mem[c] = (t->stamp << 12) | (tr_bits(t, c) & ~bit); }
        /* BlockPos.hashCode, spread as HashMap.hash does */
        int tr_hash(int x, int y, int z) { int h = (y + z * 31) * 31 + x; return h ^ (int) ((uint) h >> 16); }
        void tr_open(Tr* t, int s) {
          t->cap[s] = 16; t->size[s] = 0; t->low[s] = 0;
          for (int b = 0; b < 16; b++) t->bh[s * TR_CAP + b] = -1;
        }
        /* HashMap.resize: double, every chain split in order between bucket j and j + old */
        void tr_resize(Dk* k, Tr* t, int s) {
          int old = t->cap[s];
          if (old * 2 > TR_CAP) { k->flag |= 2; return; }
          __global int* hd = t->bh + s * TR_CAP; __global int* tl = t->bt + s * TR_CAP;
          for (int j = 0; j < old; j++) {
            int e = hd[j], loH = -1, loT = -1, hiH = -1, hiT = -1;
            while (e >= 0) {
              int after = t->nn[e];
              if ((t->nh[e] & old) == 0) { if (loT < 0) loH = e; else t->nn[loT] = e; loT = e; }
              else { if (hiT < 0) hiH = e; else t->nn[hiT] = e; hiT = e; }
              e = after;
            }
            if (loT >= 0) t->nn[loT] = -1;
            if (hiT >= 0) t->nn[hiT] = -1;
            hd[j] = loH; tl[j] = loT; hd[j + old] = hiH; tl[j + old] = hiT;
          }
          t->cap[s] = old * 2; t->low[s] = 0;
        }
        /* HashSet<BlockPos>.add */
        void tr_add(Dk* k, Tr* t, int s, int x, int y, int z) {
          int c = tr_cell(t, x, y, z);
          if (c < 0) { k->flag |= 2; return; }
          uint bit = 1u << s;
          if (tr_bits(t, c) & bit) return;
          int h = tr_hash(x, y, z);
          if (t->cap[s] == 0) tr_open(t, s);
          __global int* hd = t->bh + s * TR_CAP;
          int b = h & (t->cap[s] - 1), first = hd[b], treeBin = 0;
          if (first >= 0) {
            int len = 1;
            for (int e = t->nn[first]; e >= 0; e = t->nn[e]) len++;
            if (len >= 8) { if (t->cap[s] >= 64) { k->flag |= 2; return; } treeBin = 1; }
          }
          if (t->nodes >= TR_NODES) { k->flag |= 2; return; }
          int n = t->nodes++;
          t->nn[n] = -1; t->nh[n] = h; t->nc[n] = c;
          if (first < 0) { hd[b] = n; if (b < t->low[s]) t->low[s] = b; }
          else t->nn[t->bt[s * TR_CAP + b]] = n;
          t->bt[s * TR_CAP + b] = n;
          tr_mark(t, c, bit);
          if (s <= TR_DECO) {
            if (!t->any) { t->bx0 = t->bx1 = x; t->by0 = t->by1 = y; t->bz0 = t->bz1 = z; t->any = 1; }
            t->bx0 = min(t->bx0, x); t->by0 = min(t->by0, y); t->bz0 = min(t->bz0, z);
            t->bx1 = max(t->bx1, x); t->by1 = max(t->by1, y); t->bz1 = max(t->bz1, z);
          }
          if (treeBin) tr_resize(k, t, s);
          if (++t->size[s] > t->cap[s] / 4 * 3) tr_resize(k, t, s);
        }
        /* iterator().next() then remove(): the cell of the first element */
        int tr_poll(Tr* t, int s) {
          __global int* hd = t->bh + s * TR_CAP;
          int b = t->low[s];
          while (hd[b] < 0) b++;
          t->low[s] = b;
          int n = hd[b];
          hd[b] = t->nn[n];
          t->size[s]--;
          int c = t->nc[n];
          tr_unmark(t, c, 1u << s);
          return c;
        }
        /* the set's cells in iteration order into l1, then stably sorted by y into l2 (TreeDecorator.Context); returns the count */
        int tr_sorted(Dk* k, Tr* t, int s) {
          int n = 0;
          if (t->cap[s] == 0) return 0;
          __global int* hd = t->bh + s * TR_CAP;
          for (int b = 0; b < t->cap[s]; b++)
            for (int e = hd[b]; e >= 0; e = t->nn[e]) { if (n >= TR_LIST) { k->flag |= 2; return 0; } t->l1[n++] = t->nc[e]; }
          int cnt[65];
          for (int y = 0; y <= 64; y++) cnt[y] = 0;
          for (int q = 0; q < n; q++) cnt[(t->l1[q] >> 10) + 1]++;
          for (int y = 1; y <= 64; y++) cnt[y] += cnt[y - 1];
          for (int q = 0; q < n; q++) { int c = t->l1[q]; t->l2[cnt[c >> 10]++] = c; }
          return n;
        }
        /* a tree's setter: the set's add, then setBlock(pos, state, 19) */
        void tr_put(Dk* k, Tr* t, int s, int x, int y, int z, int st) {
          tr_add(k, t, s, x, y, z);
          dk_set(k, x, y, z, st, 19);
        }
        /* reads the exact state at a block, recorded (the CPU compares the state itself) */
        int dk_state_at(Dk* k, int x, int y, int z) {
          int s = dk_at(k, x, y, z);
          if (y >= OD_MINY && y < OD_MINY + OD_H && !(k->flag & 2)) {
            int ci = od_ci(x, z, k->bxo, k->bzo);
            dk_read(k, (RT_STATE << 24) | (ci << 20) | ((y - OD_MINY) << 8) | (((z - k->bzo) & 15) << 4) | ((x - k->bxo) & 15), s);
          }
          return s;
        }
        /* a value table's value for a state (not recorded: the caller recorded the state) */
        int dk_vt(Dk* k, int table, int s) {
          if (s >= k->nstates) { k->flag |= 2; return V_UNKNOWN; }
          int v = k->vt[table * 65536 + s];
          if (v == V_UNKNOWN) k->flag |= 2;
          return v;
        }
        /* reads a block's value of a table, recorded with the value */
        int dk_value(Dk* k, int x, int y, int z, int table) {
          int s = dk_at(k, x, y, z);
          int v = dk_vt(k, table, s);
          if (y >= OD_MINY && y < OD_MINY + OD_H && !(k->flag & 2)) {
            int ci = od_ci(x, z, k->bxo, k->bzo);
            dk_read(k, (RT_VALUE << 24) | (ci << 20) | ((y - OD_MINY) << 8) | (((z - k->bzo) & 15) << 4) | ((x - k->bxo) & 15), (table << 20) | (v & 0xfffff));
          }
          return v;
        }
        /* a write the CPU's edge pass makes: the job's copy and heightmaps, and the effect the CPU's checks compare (not replayed) */
        void dk_set_edge(Dk* k, int x, int y, int z, int s) {
          if (y < OD_MINY || y >= OD_MINY + OD_H) return;
          int nw = k->nw;
          dk_set(k, x, y, z, s, 2);
          if (nw < k->maxw && k->nw > nw) k->wr[nw * 2 + 1] = (EF_EDGE_SET << 28) | (2 << 16) | s;
        }
        void dk_mark(Dk* k, int op, int where, int data) {
          if (k->nw < k->maxw) { k->wr[k->nw * 2] = where; k->wr[k->nw * 2 + 1] = (op << 28) | (data & 0xfffffff); }
          else k->flag |= 2 | 4;
          k->nw++;
        }
        /* FeatureSize.getSizeAtHeight */
        int tr_size(__global const int* pr, int height, int y) {
          if (pr[17] == 2) return y < pr[18] ? pr[20] : pr[22];
          if (y < pr[18]) return pr[20];
          return y >= height - pr[19] ? pr[22] : pr[21];
        }
        /* TreeFeature.getMaxFreeTreeHeight (vanilla's trunk placers keep the base free test) */
        int tr_free(Dk* k, __global const int* pr, int height, int x, int y, int z) {
          for (int i = 0; i <= height + 1; i++) {
            int j = tr_size(pr, height, i);
            for (int dx = -j; dx <= j; dx++) {
              for (int dz = -j; dz <= j; dz++) {
                if (!dk_ask(k, x + dx, y + i, z + dz, P_FREE) || (!pr[28] && dk_ask(k, x + dx, y + i, z + dz, P_VINE))) return i - 2;
                if (k->flag & 2) return -1000;
              }
            }
          }
          return height;
        }
        /* TrunkPlacer.setDirtAt (NeoForge's onTreeGrow keeps the default on the soils the GPU knows) */
        void tr_dirt(Dk* k, Tr* t, __global const int* pr, int x, int y, int z) {
          if (!dk_ask(k, x, y, z, P_TREE_GROW)) return;
          if (pr[27] || !dk_ask(k, x, y, z, P_TRUNK_DIRT)) {
            int rec = dk_state(&k->r, k->aux, pr[26]);
            tr_put(k, t, TR_LOGS, x, y, z, k->aux[rec]);
          }
        }
        /* TrunkPlacer.placeLog, the state as the trunk provider gives it (axis < 0) or with that axis set */
        int tr_log(Dk* k, Tr* t, __global const int* pr, int x, int y, int z, int axis) {
          if (!dk_ask(k, x, y, z, P_VALID_TREE)) return 0;
          int rec = dk_state(&k->r, k->aux, pr[24]);
          tr_put(k, t, TR_LOGS, x, y, z, k->aux[rec + (axis < 0 ? 0 : 1 + axis)]);
          return 1;
        }
        void tr_log_free(Dk* k, Tr* t, __global const int* pr, int x, int y, int z) {
          if (dk_ask(k, x, y, z, P_FREE)) tr_log(k, t, pr, x, y, z, -1);
        }
        /* FoliagePlacer.tryPlaceLeaf */
        int tr_leaf(Dk* k, Tr* t, __global const int* pr, int x, int y, int z) {
          if (!dk_ask(k, x, y, z, P_VALID_TREE)) return 0;
          __global const int* rec = k->aux + dk_state(&k->r, k->aux, pr[25]);
          int s = rec[0];
          if (rec[2] && dk_ask(k, x, y, z, P_WATER_SOURCE)) s = rec[1];
          tr_put(k, t, TR_LEAVES, x, y, z, s);
          return 1;
        }
        int tr_floorf(float f) { int i = (int) f; return f < (float) i ? i - 1 : i; }
        /* the foliage placer's shouldSkipLocation */
        int tr_skip(Dk* k, __global const int* pr, int a, int y, int b, int r, int dbl) {
          int f = pr[9];
          if (f == F_BLOB) return a == r && b == r && (br_nextInt(&k->r, 2) == 0 || y == 0);
          if (f == F_SPRUCE || f == F_PINE) return a == r && b == r && r > 0;
          if (f == F_ACACIA) return y == 0 ? (a > 1 || b > 1) && a != 0 && b != 0 : a == r && b == r && r > 0;
          if (f == F_BUSH) return a == r && b == r && br_nextInt(&k->r, 2) == 0;
          if (f == F_FANCY) { float fa = (float) a + 0.5f, fb = (float) b + 0.5f; return fa * fa + fb * fb > (float) (r * r); }
          if (f == F_MEGA_JUNGLE || f == F_MEGA_PINE) return a + b >= 7 ? 1 : a * a + b * b > r * r;
          if (f == F_DARK_OAK) { if (y == -1 && !dbl) return a == r && b == r; return y == 1 ? a + b > r * 2 - 2 : 0; }
          if (f == F_RANDOM_SPREAD) return 0;
          if (y == -1 && (a == r || b == r) && br_nextFloat(&k->r) < as_float(pr[13])) return 1;
          int full = a == r && b == r;
          if (r > 2) return full || (a + b > r * 2 - 2 && br_nextFloat(&k->r) < as_float(pr[14]));
          return full && br_nextFloat(&k->r) < as_float(pr[14]);
        }
        int tr_skip_signed(Dk* k, __global const int* pr, int j, int y, int kk, int r, int dbl) {
          if (pr[9] == F_DARK_OAK && !(y != 0 || !dbl || (j != -r && j < r) || (kk != -r && kk < r))) return 1;
          int a, b;
          if (dbl) { a = min(abs(j), abs(j - 1)); b = min(abs(kk), abs(kk - 1)); }
          else { a = abs(j); b = abs(kk); }
          return tr_skip(k, pr, a, y, b, r, dbl);
        }
        /* FoliagePlacer.placeLeavesRow */
        void tr_row(Dk* k, Tr* t, __global const int* pr, int ax, int ay, int az, int r, int y, int dbl) {
          int i = dbl ? 1 : 0;
          for (int j = -r; j <= r + i; j++) {
            for (int kk = -r; kk <= r + i; kk++) {
              if (!tr_skip_signed(k, pr, j, y, kk, r, dbl)) tr_leaf(k, t, pr, ax + j, ay + y, az + kk);
              if (k->flag & 2) return;
            }
          }
        }
        int tr_ext(Dk* k, Tr* t, __global const int* pr, float chance, int bx, int by, int bz, int x, int y, int z) {
          if (abs(x - bx) + abs(y - by) + abs(z - bz) >= 7) return 0;
          return br_nextFloat(&k->r) > chance ? 0 : tr_leaf(k, t, pr, x, y, z);
        }
        /* FoliagePlacer.placeLeavesRowWithHangingLeavesBelow */
        void tr_hanging(Dk* k, Tr* t, __global const int* pr, int ax, int ay, int az, int r, int y, int dbl, float c1, float c2) {
          tr_row(k, t, pr, ax, ay, az, r, y, dbl);
          int i = dbl ? 1 : 0;
          int bx = ax, by = ay - 1, bz = az;
          for (int d = 0; d < 4; d++) {
            int c = (d + 1) & 3;
            int j = (c == 1 || c == 2) ? r + i : r;
            int mx = ax + TR_HX[c] * j - TR_HX[d] * r, my = ay + y - 1, mz = az + TR_HZ[c] * j - TR_HZ[d] * r;
            for (int kk = -r; kk < r + i; kk++) {
              int up = tr_cell(t, mx, my + 1, mz);
              int set = up >= 0 && (tr_bits(t, up) & (1u << TR_LEAVES));
              if (set && tr_ext(k, t, pr, c1, bx, by, bz, mx, my, mz)) tr_ext(k, t, pr, c2, bx, by, bz, mx, my - 1, mz);
              mx += TR_HX[d]; mz += TR_HZ[d];
              if (k->flag & 2) return;
            }
          }
        }
        /* FoliagePlacer.foliageHeight */
        int tr_fheight(Dk* k, __global const int* pr, int height) {
          int f = pr[9];
          if (f == F_BLOB || f == F_BUSH || f == F_FANCY || f == F_MEGA_JUNGLE) return pr[12];
          if (f == F_SPRUCE) return max(4, height - dk_int(&k->r, k->aux, pr[12]));
          if (f == F_ACACIA) return 0;
          if (f == F_DARK_OAK) return 4;
          return dk_int(&k->r, k->aux, pr[12]);
        }
        /* FoliagePlacer.foliageRadius */
        int tr_fradius(Dk* k, __global const int* pr, int trunkHeight) {
          int r = dk_int(&k->r, k->aux, pr[10]);
          if (pr[9] == F_PINE) r += br_nextInt(&k->r, max(trunkHeight + 1, 1));
          return r;
        }
        /* FoliagePlacer.createFoliage (the offset drawn first) */
        void tr_foliage(Dk* k, Tr* t, __global const int* pr, TrAtt a, int fh, int fr) {
          int off = dk_int(&k->r, k->aux, pr[11]);
          int f = pr[9], dbl = a.dbl;
          if (f == F_BLOB) {
            for (int i = off; i >= off - fh; i--) tr_row(k, t, pr, a.x, a.y, a.z, max(fr + a.r - 1 - i / 2, 0), i, dbl);
          } else if (f == F_SPRUCE) {
            int i = br_nextInt(&k->r, 2), j = 1, kk = 0;
            for (int l = off; l >= -fh; l--) {
              tr_row(k, t, pr, a.x, a.y, a.z, i, l, dbl);
              if (i >= j) { i = kk; kk = 1; j = min(j + 1, fr + a.r); } else i++;
            }
          } else if (f == F_PINE) {
            int i = 0;
            for (int j = off; j >= off - fh; j--) {
              tr_row(k, t, pr, a.x, a.y, a.z, i, j, dbl);
              if (i >= 1 && j == off - fh + 1) i--; else if (i < fr + a.r) i++;
            }
          } else if (f == F_ACACIA) {
            int by = a.y + off;
            tr_row(k, t, pr, a.x, by, a.z, fr + a.r, -1 - fh, dbl);
            tr_row(k, t, pr, a.x, by, a.z, fr - 1, -fh, dbl);
            tr_row(k, t, pr, a.x, by, a.z, fr + a.r - 1, 0, dbl);
          } else if (f == F_BUSH) {
            for (int i = off; i >= off - fh; i--) tr_row(k, t, pr, a.x, a.y, a.z, fr + a.r - 1 - i, i, dbl);
          } else if (f == F_FANCY) {
            for (int i = off; i >= off - fh; i--) tr_row(k, t, pr, a.x, a.y, a.z, fr + (i != off && i != off - fh ? 1 : 0), i, dbl);
          } else if (f == F_MEGA_JUNGLE) {
            int i = dbl ? fh : 1 + br_nextInt(&k->r, 2);
            for (int j = off; j >= off - i; j--) tr_row(k, t, pr, a.x, a.y, a.z, fr + a.r + 1 - j, j, dbl);
          } else if (f == F_MEGA_PINE) {
            if (fh == 0) { k->flag |= 2; return; }
            int i = 0;
            for (int j = a.y - fh + off; j <= a.y + off; j++) {
              int kk = a.y - j;
              int l = fr + a.r + tr_floorf((float) kk / (float) fh * 3.5f);
              int i1 = (kk > 0 && l == i && (j & 1) == 0) ? l + 1 : l;
              tr_row(k, t, pr, a.x, j, a.z, i1, 0, dbl);
              i = l;
            }
          } else if (f == F_DARK_OAK) {
            int by = a.y + off;
            if (dbl) {
              tr_row(k, t, pr, a.x, by, a.z, fr + 2, -1, 1);
              tr_row(k, t, pr, a.x, by, a.z, fr + 3, 0, 1);
              tr_row(k, t, pr, a.x, by, a.z, fr + 2, 1, 1);
              if (br_nextBool(&k->r)) tr_row(k, t, pr, a.x, by, a.z, fr, 2, 1);
            } else {
              tr_row(k, t, pr, a.x, by, a.z, fr + 2, -1, 0);
              tr_row(k, t, pr, a.x, by, a.z, fr + 1, 0, 0);
            }
          } else if (f == F_RANDOM_SPREAD) {
            if (fr <= 0 || fh <= 0) { k->flag |= 2; return; }
            for (int i = 0; i < pr[13]; i++) {
              int q = br_nextInt(&k->r, fr); int dx = q - br_nextInt(&k->r, fr);
              q = br_nextInt(&k->r, fh); int dy = q - br_nextInt(&k->r, fh);
              q = br_nextInt(&k->r, fr); int dz = q - br_nextInt(&k->r, fr);
              tr_leaf(k, t, pr, a.x + dx, a.y + dy, a.z + dz);
              if (k->flag & 2) return;
            }
          } else {
            int by = a.y + off, i = fr + a.r - 1;
            tr_row(k, t, pr, a.x, by, a.z, i - 2, fh - 3, dbl);
            tr_row(k, t, pr, a.x, by, a.z, i - 1, fh - 4, dbl);
            for (int j = fh - 5; j >= 0; j--) tr_row(k, t, pr, a.x, by, a.z, i, j, dbl);
            tr_hanging(k, t, pr, a.x, by, a.z, i, -1, dbl, as_float(pr[15]), as_float(pr[16]));
            tr_hanging(k, t, pr, a.x, by, a.z, i - 1, -2, dbl, as_float(pr[15]), as_float(pr[16]));
          }
        }
        void tr_att(TrAtt* at, int* n, int x, int y, int z, int r, int dbl, Dk* k) {
          if (*n >= 64) { k->flag |= 2; return; }
          at[*n].x = x; at[*n].y = y; at[*n].z = z; at[*n].r = r; at[*n].dbl = dbl; (*n)++;
        }
        float tr_msin(Dk* k, float f) { return k->msin[((int) (f * 10430.378f)) & 65535]; }
        float tr_mcos(Dk* k, float f) { return k->msin[((int) (f * 10430.378f + 16384.0f)) & 65535]; }
        /* CherryTrunkPlacer.generateBranch */
        void tr_branch(Dk* k, Tr* t, __global const int* pr, int h, int x, int y, int z, int axis, int dir, int start, int b, TrAtt* at, int* n) {
          int mx = x, my = y + start, mz = z;
          int i = h - 1 + dk_int(&k->r, k->aux, pr[8]);
          int flag = b || i < start;
          int j = dk_int(&k->r, k->aux, pr[5]) + (flag ? 1 : 0);
          int tx = x + TR_HX[dir] * j, ty = y + i, tz = z + TR_HZ[dir] * j;
          int kk = flag ? 2 : 1;
          for (int l = 0; l < kk; l++) { mx += TR_HX[dir]; mz += TR_HZ[dir]; tr_log(k, t, pr, mx, my, mz, axis); }
          int up = ty > my;
          while (!(k->flag & 2)) {
            int i1 = abs(tx - mx) + abs(ty - my) + abs(tz - mz);
            if (i1 == 0) { tr_att(at, n, tx, ty + 1, tz, 0, 0, k); return; }
            float f = (float) abs(ty - my) / (float) i1;
            int vert = br_nextFloat(&k->r) < f;
            if (vert) my += up ? 1 : -1; else { mx += TR_HX[dir]; mz += TR_HZ[dir]; }
            tr_log(k, t, pr, mx, my, mz, vert ? -1 : axis);
          }
        }
        int tr_floord(double d) { int i = (int) d; return d < (double) i ? i - 1 : i; }
        /* FancyTrunkPlacer.treeShape */
        float tr_fshape(int h, int y) {
          if ((float) y < (float) h * 0.3f) return -1.0f;
          float f = (float) h / 2.0f, f1 = f - (float) y;
          float f2 = (float) sqrt((double) (f * f - f1 * f1));
          if (f1 == 0.0f) f2 = f;
          else if (fabs(f1) >= f) return 0.0f;
          return f2 * 0.5f;
        }
        /* FancyTrunkPlacer.makeLimb: whether every block on the way is free (place 0), or places the limb's logs (place 1) */
        int tr_limb(Dk* k, Tr* t, __global const int* pr, int sx, int sy, int sz, int ex, int ey, int ez, int place) {
          if (!place && sx == ex && sy == ey && sz == ez) return 1;
          int dx = ex - sx, dy = ey - sy, dz = ez - sz;
          int i = max(abs(dx), max(abs(dy), abs(dz)));
          if (i == 0) { k->flag |= 2; return 0; }
          float f = (float) dx / (float) i, f1 = (float) dy / (float) i, f2 = (float) dz / (float) i;
          for (int j = 0; j <= i; j++) {
            int px = sx + tr_floorf(0.5f + (float) j * f), py = sy + tr_floorf(0.5f + (float) j * f1), pz = sz + tr_floorf(0.5f + (float) j * f2);
            if (place) {
              // getLogAxis: X or Z by the larger horizontal distance from the limb's start, Y when straight up
              int ax = abs(px - sx), az = abs(pz - sz), m = max(ax, az);
              tr_log(k, t, pr, px, py, pz, m > 0 ? (ax == m ? 0 : 2) : 1);
            } else if (!dk_ask(k, px, py, pz, P_FREE)) return 0;
            if (k->flag & 2) return 0;
          }
          return 1;
        }
        /* FancyTrunkPlacer.placeTrunk (one limb per level: min(1, ...) is always 1) */
        int tr_fancy(Dk* k, Tr* t, __global const int* pr, int h, int x, int y, int z, TrAtt* at) {
          int j = h + 2, n = 0;
          int kk = tr_floord((double) j * 0.618);
          tr_dirt(k, t, pr, x, y - 1, z);
          int i1 = y + kk, j1 = j - 5;
          // the foliage coords: attachment position and branch base
          int cx[32], cy[32], cz[32], cb[32], nc = 1;
          cx[0] = x; cy[0] = y + j1; cz[0] = z; cb[0] = i1;
          for (; j1 >= 0 && !(k->flag & 2); j1--) {
            float f = tr_fshape(j, j1);
            if (f < 0.0f) continue;
            double d2 = 1.0 * (double) f * ((double) br_nextFloat(&k->r) + 0.328);
            int bad = 0, k24 = br_next(&k->r, 24);
            double sn = os_fsin(k24, k->fsinFix, &bad), cs = os_fcos(k24, k->fcosFix, &bad);
            if (bad) { k->flag |= 2; return 0; }
            double d4 = d2 * sn + 0.5, d5 = d2 * cs + 0.5;
            int bx = x + tr_floord(d4), by = y + j1 - 1, bz = z + tr_floord(d5);
            if (!tr_limb(k, t, pr, bx, by, bz, bx, by + 5, bz, 0)) continue;
            int l1 = x - bx, i2 = z - bz;
            double d6 = (double) by - sqrt((double) (l1 * l1 + i2 * i2)) * 0.381;
            int j2 = d6 > (double) i1 ? i1 : (int) d6;
            if (tr_limb(k, t, pr, x, j2, z, bx, by, bz, 0)) {
              if (nc >= 32) { k->flag |= 2; return 0; }
              cx[nc] = bx; cy[nc] = by; cz[nc] = bz; cb[nc] = j2; nc++;
            }
          }
          tr_limb(k, t, pr, x, y, z, x, y + kk, z, 1);
          for (int q = 0; q < nc && !(k->flag & 2); q++) {
            int b = cb[q];
            if (!(x == cx[q] && b == cy[q] && z == cz[q]) && (double) (b - y) >= (double) j * 0.2) tr_limb(k, t, pr, x, b, z, cx[q], cy[q], cz[q], 1);
          }
          for (int q = 0; q < nc; q++) if ((double) (cb[q] - y) >= (double) j * 0.2) tr_att(at, &n, cx[q], cy[q], cz[q], 0, 0, k);
          return n;
        }
        /* TrunkPlacer.placeTrunk: the foliage attachments */
        int tr_trunk(Dk* k, Tr* t, __global const int* pr, int h, int x, int y, int z, TrAtt* at) {
          int ty = pr[0], n = 0;
          if (ty == T_FANCY) return tr_fancy(k, t, pr, h, x, y, z, at);
          if (ty == T_STRAIGHT) {
            tr_dirt(k, t, pr, x, y - 1, z);
            for (int i = 0; i < h; i++) tr_log(k, t, pr, x, y + i, z, -1);
            tr_att(at, &n, x, y + h, z, 0, 0, k);
          } else if (ty == T_FORKING) {
            tr_dirt(k, t, pr, x, y - 1, z);
            int dir = br_nextInt(&k->r, 4);
            int i = h - br_nextInt(&k->r, 4) - 1;
            int j = 3 - br_nextInt(&k->r, 3);
            int kx = x, kz = z, top = TR_NONE;
            for (int i1 = 0; i1 < h; i1++) {
              int j1 = y + i1;
              if (i1 >= i && j > 0) { kx += TR_HX[dir]; kz += TR_HZ[dir]; j--; }
              if (tr_log(k, t, pr, kx, j1, kz, -1)) top = j1 + 1;
            }
            if (top != TR_NONE) tr_att(at, &n, kx, top, kz, 1, 0, k);
            kx = x; kz = z;
            int dir1 = br_nextInt(&k->r, 4);
            if (dir1 != dir) {
              int j2 = i - br_nextInt(&k->r, 2) - 1;
              int k1 = 1 + br_nextInt(&k->r, 3);
              top = TR_NONE;
              for (int l1 = j2; l1 < h && k1 > 0; k1--) {
                if (l1 >= 1) {
                  int i2 = y + l1;
                  kx += TR_HX[dir1]; kz += TR_HZ[dir1];
                  if (tr_log(k, t, pr, kx, i2, kz, -1)) top = i2 + 1;
                }
                l1++;
              }
              if (top != TR_NONE) tr_att(at, &n, kx, top, kz, 0, 0, k);
            }
          } else if (ty == T_GIANT || ty == T_MEGA_JUNGLE) {
            tr_dirt(k, t, pr, x, y - 1, z); tr_dirt(k, t, pr, x + 1, y - 1, z); tr_dirt(k, t, pr, x, y - 1, z + 1); tr_dirt(k, t, pr, x + 1, y - 1, z + 1);
            for (int i = 0; i < h; i++) {
              tr_log_free(k, t, pr, x, y + i, z);
              if (i < h - 1) { tr_log_free(k, t, pr, x + 1, y + i, z); tr_log_free(k, t, pr, x + 1, y + i, z + 1); tr_log_free(k, t, pr, x, y + i, z + 1); }
            }
            tr_att(at, &n, x, y + h, z, 0, 1, k);
            if (ty == T_MEGA_JUNGLE) {
              for (int i = h - 2 - br_nextInt(&k->r, 4); i > h / 2 && !(k->flag & 2); i -= 2 + br_nextInt(&k->r, 4)) {
                float f = br_nextFloat(&k->r) * TR_TWO_PI;
                int j = 0, kk = 0;
                for (int l = 0; l < 5; l++) {
                  j = (int) (1.5f + tr_mcos(k, f) * (float) l);
                  kk = (int) (1.5f + tr_msin(k, f) * (float) l);
                  tr_log(k, t, pr, x + j, y + i - 3 + l / 2, z + kk, -1);
                }
                tr_att(at, &n, x + j, y + i, z + kk, -2, 0, k);
              }
            }
          } else if (ty == T_DARK_OAK) {
            tr_dirt(k, t, pr, x, y - 1, z); tr_dirt(k, t, pr, x + 1, y - 1, z); tr_dirt(k, t, pr, x, y - 1, z + 1); tr_dirt(k, t, pr, x + 1, y - 1, z + 1);
            int dir = br_nextInt(&k->r, 4);
            int i = h - br_nextInt(&k->r, 4);
            int j = 2 - br_nextInt(&k->r, 3);
            int j1 = x, k1 = z, l1 = y + h - 1;
            for (int i2 = 0; i2 < h; i2++) {
              if (i2 >= i && j > 0) { j1 += TR_HX[dir]; k1 += TR_HZ[dir]; j--; }
              int j2 = y + i2;
              if (dk_ask(k, j1, j2, k1, P_AIR_OR_LEAVES)) {
                tr_log(k, t, pr, j1, j2, k1, -1); tr_log(k, t, pr, j1 + 1, j2, k1, -1);
                tr_log(k, t, pr, j1, j2, k1 + 1, -1); tr_log(k, t, pr, j1 + 1, j2, k1 + 1, -1);
              }
            }
            tr_att(at, &n, j1, l1, k1, 0, 1, k);
            for (int l2 = -1; l2 <= 2; l2++) {
              for (int i3 = -1; i3 <= 2; i3++) {
                if ((l2 < 0 || l2 > 1 || i3 < 0 || i3 > 1) && br_nextInt(&k->r, 3) <= 0) {
                  int j3 = br_nextInt(&k->r, 3) + 2;
                  for (int k2 = 0; k2 < j3; k2++) tr_log(k, t, pr, x + l2, l1 - k2 - 1, z + i3, -1);
                  tr_att(at, &n, j1 + l2, l1, k1 + i3, 0, 0, k);
                }
              }
            }
          } else if (ty == T_BENDING) {
            int dir = br_nextInt(&k->r, 4);
            int i = h - 1;
            int mx = x, my = y, mz = z;
            tr_dirt(k, t, pr, x, y - 1, z);
            for (int j = 0; j <= i; j++) {
              if (j + 1 >= i + br_nextInt(&k->r, 2)) { mx += TR_HX[dir]; mz += TR_HZ[dir]; }
              if (dk_ask(k, mx, my, mz, P_VALID_TREE)) tr_log(k, t, pr, mx, my, mz, -1);
              if (j >= pr[4]) tr_att(at, &n, mx, my, mz, 0, 0, k);
              my++;
            }
            int l = dk_int(&k->r, k->aux, pr[5]);
            for (int kk = 0; kk <= l; kk++) {
              if (dk_ask(k, mx, my, mz, P_VALID_TREE)) tr_log(k, t, pr, mx, my, mz, -1);
              tr_att(at, &n, mx, my, mz, 0, 0, k);
              mx += TR_HX[dir]; mz += TR_HZ[dir];
            }
          } else {
            tr_dirt(k, t, pr, x, y - 1, z);
            int i = max(0, h - 1 + dk_int(&k->r, k->aux, pr[6]));
            int j = max(0, h - 1 + dk_int(&k->r, k->aux, pr[7]));
            if (j >= i) j++;
            int kc = dk_int(&k->r, k->aux, pr[4]);
            int three = kc == 3, two = kc >= 2;
            int l = three ? h : two ? max(i, j) + 1 : i + 1;
            for (int i1 = 0; i1 < l; i1++) tr_log(k, t, pr, x, y + i1, z, -1);
            if (three) tr_att(at, &n, x, y + l, z, 0, 0, k);
            int dir = br_nextInt(&k->r, 4);
            int axis = TR_HX[dir] != 0 ? 0 : 2;
            tr_branch(k, t, pr, h, x, y, z, axis, dir, i, i < l - 1, at, &n);
            if (two) tr_branch(k, t, pr, h, x, y, z, axis, (dir + 2) & 3, j, j < l - 1, at, &n);
          }
          return n;
        }
        /* AlterGroundDecorator.placeBlockAt */
        void tr_ground(Dk* k, Tr* t, int prov, int x, int y, int z) {
          for (int i = 2; i >= -3; i--) {
            if (dk_ask(k, x, y + i, z, P_DIRT)) { int rec = dk_state(&k->r, k->aux, prov); tr_put(k, t, TR_DECO, x, y + i, z, k->aux[rec]); return; }
            if (!dk_ask(k, x, y + i, z, P_AIR) && i < 0) return;
          }
        }
        void tr_circle(Dk* k, Tr* t, int prov, int x, int y, int z) {
          for (int i = -2; i <= 2; i++) for (int j = -2; j <= 2; j++) if (abs(i) != 2 || abs(j) != 2) tr_ground(k, t, prov, x + i, y, z + j);
        }
        /* the decorators, in the configuration's order, over the y-sorted lists */
        void tr_decorate(Dk* k, Tr* t, __global const int* pr) {
          __global const int* cst = t->cst;
          int nd = pr[30];
          for (int q = 0; q < nd && !(k->flag & 2); q++) {
            int ty = pr[31 + q * 2], arg = pr[32 + q * 2];
            if (ty == D_BEEHIVE) {
              // BeehiveDecorator.place: a nest's spot comes from Java's static Random (Collections.shuffle), so a tree that rolls one goes to the CPU
              if (!(br_nextFloat(&k->r) >= as_float(arg))) k->flag |= 2;
              continue;
            }
            if (ty == D_TRUNK_VINE || ty == D_LEAVE_VINE) {
              int n = tr_sorted(k, t, ty == D_TRUNK_VINE ? TR_LOGS : TR_LEAVES);
              for (int e = 0; e < n && !(k->flag & 2); e++) {
                int c = t->l2[e], x = tr_cx(t, c), y = tr_cy(t, c), z = tr_cz(t, c);
                // west (vine facing east), east, north, south
                int dx[4] = { -1, 1, 0, 0 }, dz[4] = { 0, 0, -1, 1 };
                for (int s = 0; s < 4; s++) {
                  int go = ty == D_TRUNK_VINE ? br_nextInt(&k->r, 3) > 0 : br_nextFloat(&k->r) < as_float(arg);
                  if (go && dk_ask(k, x + dx[s], y, z + dz[s], P_AIR)) {
                    tr_put(k, t, TR_DECO, x + dx[s], y, z + dz[s], cst[s]);
                    if (ty == D_LEAVE_VINE) {
                      int i = 4, by = y - 1;
                      while (dk_ask(k, x + dx[s], by, z + dz[s], P_AIR) && i > 0) { tr_put(k, t, TR_DECO, x + dx[s], by, z + dz[s], cst[s]); by--; i--; }
                    }
                  }
                }
              }
            } else if (ty == D_COCOA) {
              if (!(br_nextFloat(&k->r) >= as_float(arg))) {
                int n = tr_sorted(k, t, TR_LOGS);
                if (n == 0) { k->flag |= 2; return; }
                int base = tr_cy(t, t->l2[0]);
                for (int e = 0; e < n && !(k->flag & 2); e++) {
                  int c = t->l2[e], x = tr_cx(t, c), y = tr_cy(t, c), z = tr_cz(t, c);
                  if (y - base > 2) continue;
                  for (int d = 0; d < 4; d++) {
                    if (br_nextFloat(&k->r) <= 0.25f) {
                      int px = x - TR_HX[d], pz = z - TR_HZ[d];
                      if (dk_ask(k, px, y, pz, P_AIR)) { int age = br_nextInt(&k->r, 3); tr_put(k, t, TR_DECO, px, y, pz, cst[4 + age * 4 + d]); }
                    }
                  }
                }
              }
            } else {
              // AlterGroundDecorator: the roots, or the logs, or both when their lowest layers meet
              int nr = tr_sorted(k, t, TR_ROOTS);
              int nl = 0, rootY = nr > 0 ? tr_cy(t, t->l2[0]) : 0;
              __global int* list = t->l3;
              int len = 0;
              for (int e = 0; e < nr; e++) list[len++] = t->l2[e];
              nl = tr_sorted(k, t, TR_LOGS);
              if (nr == 0 || (nl > 0 && rootY == tr_cy(t, t->l2[0]))) {
                // logs first, then the roots
                for (int e = len - 1; e >= 0; e--) list[e + nl] = list[e];
                for (int e = 0; e < nl; e++) list[e] = t->l2[e];
                len += nl;
              }
              if (len > TR_LIST) { k->flag |= 2; return; }
              if (len > 0) {
                int y0 = tr_cy(t, list[0]);
                for (int e = 0; e < len && !(k->flag & 2); e++) {
                  int c = list[e], x = tr_cx(t, c), y = tr_cy(t, c), z = tr_cz(t, c);
                  if (y != y0) continue;
                  tr_circle(k, t, arg, x - 1, y, z - 1); tr_circle(k, t, arg, x + 2, y, z - 1);
                  tr_circle(k, t, arg, x - 1, y, z + 2); tr_circle(k, t, arg, x + 2, y, z + 2);
                  for (int j = 0; j < 5; j++) {
                    int kk = br_nextInt(&k->r, 64), l = kk % 8, i1 = kk / 8;
                    if (l == 0 || l == 7 || i1 == 0 || i1 == 7) tr_circle(k, t, arg, x - 3 + l, y, z - 3 + i1);
                  }
                }
              }
            }
          }
        }
        /* BlockBehaviour.updateShape of the kinds the GPU knows; -1 for another kind */
        int tr_ups(Dk* k, int s, int up, int nb) {
          if (s >= k->nstates) { k->flag |= 2; return -1; }
          int kind = dk_vt(k, V_UPS, s);
          if (kind == UPS_SAME) return s;
          if (kind == UPS_SNOWY) {
            if (!up) return s;
            int v = dk_vt(k, dk_test(k, nb, P_SNOW) ? V_SNOWY : V_NOT_SNOWY, s);
            return v < 0 ? s : v;
          }
          k->flag |= 2;
          return -1;
        }
        /* updateShapeAtEdge's face: the block inside updated against the one outside, then the outside one against it */
        void tr_face(Dk* k, int d, int x, int y, int z) {
          // Direction: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east
          int sx[6] = { 0, 0, 0, 0, -1, 1 }, sy[6] = { -1, 1, 0, 0, 0, 0 }, sz[6] = { 0, 0, -1, 1, 0, 0 };
          int ox = x + sx[d], oy = y + sy[d], oz = z + sz[d];
          int s = dk_state_at(k, x, y, z), n = dk_state_at(k, ox, oy, oz);
          if (k->flag & 2) return;
          int u = tr_ups(k, s, d == 1, n);
          if (u < 0) return;
          if (u != s) dk_set_edge(k, x, y, z, u);
          int n2 = tr_ups(k, n, d == 0, u);
          if (n2 < 0) return;
          if (n2 != n) dk_set_edge(k, ox, oy, oz, n2);
        }
        /* TreeFeature.place: 1 placed, 0 not */
        int tr_place(Dk* k, __global const int* pr, int x, int y, int z) {
          Tr t;
          t.mem = (__global uint*) k->tr; t.nn = k->tr + TR_CELLS + 16; t.nh = t.nn + TR_NODES; t.nc = t.nh + TR_NODES;
          t.bh = t.nc + TR_NODES; t.bt = t.bh + TR_SETS * TR_CAP; t.l1 = t.bt + TR_SETS * TR_CAP; t.l2 = t.l1 + TR_LIST; t.l3 = t.l2 + TR_LIST;
          t.nodes = 0; t.any = 0; t.ox = x - 16; t.oy = y - 8; t.oz = z - 16; t.cst = k->aux + pr[29];
          for (int s = 0; s < TR_SETS; s++) { t.cap[s] = 0; t.size[s] = 0; t.low[s] = 0; }
          uint stamp = t.mem[TR_CELLS] + 1;
          if (stamp >= 0xfffffu) { for (int c = 0; c < TR_CELLS; c++) t.mem[c] = 0; stamp = 1; }
          t.mem[TR_CELLS] = stamp;
          t.stamp = stamp;
          int height = pr[1] + br_nextInt(&k->r, pr[2] + 1);
          height += br_nextInt(&k->r, pr[3] + 1);
          int fh = tr_fheight(k, pr, height);
          int fr = tr_fradius(k, pr, height - fh);
          if (!(y >= OD_MINY + 1 && y + height + 1 <= OD_MINY + OD_H)) return 0;
          int k1 = tr_free(k, pr, height, x, y, z);
          if (k->flag & 2) return 0;
          if (!(k1 >= height || (pr[23] >= 0 && k1 >= pr[23]))) return 0;
          TrAtt at[64];
          int n = tr_trunk(k, &t, pr, k1, x, y, z, at);
          for (int q = 0; q < n && !(k->flag & 2); q++) tr_foliage(k, &t, pr, at[q], fh, fr);
          if (k->flag & 2) return 0;
          if (t.size[TR_LOGS] == 0 && t.size[TR_LEAVES] == 0) return 0;
          tr_decorate(k, &t, pr);
          if ((k->flag & 2) || !t.any) return 0;
          // updateLeaves over the box of every set
          int bx0 = t.bx0, by0 = t.by0, bz0 = t.bz0, bx1 = t.bx1, by1 = t.by1, bz1 = t.bz1;
          for (int s = TR_ROOTS; s <= TR_DECO; s += TR_DECO - TR_ROOTS) {
            if (t.cap[s] == 0) continue;
            for (int b = 0; b < t.cap[s]; b++)
              for (int e = t.bh[s * TR_CAP + b]; e >= 0; e = t.nn[e]) tr_mark(&t, t.nc[e], TR_SHAPE);
          }
          if (t.cap[TR_LOGS] != 0) {
            for (int b = 0; b < t.cap[TR_LOGS]; b++)
              for (int e = t.bh[TR_LOGS * TR_CAP + b]; e >= 0; e = t.nn[e]) {
                int c = t.nc[e];
                tr_add(k, &t, TR_LV0, tr_cx(&t, c), tr_cy(&t, c), tr_cz(&t, c));
              }
          }
          int k2 = 0, done = 0;
          int ddx[6] = { 0, 0, 0, 0, -1, 1 }, ddy[6] = { -1, 1, 0, 0, 0, 0 }, ddz[6] = { 0, 0, -1, 1, 0, 0 };
          while (!done && !(k->flag & 2)) {
            while (k2 >= 7 || t.size[TR_LV0 + k2] != 0) {
              if (k2 >= 7 || (k->flag & 2)) { done = 1; break; }
              int c = tr_poll(&t, TR_LV0 + k2);
              int px = tr_cx(&t, c), py = tr_cy(&t, c), pz = tr_cz(&t, c);
              if (px < bx0 || px > bx1 || py < by0 || py > by1 || pz < bz0 || pz > bz1) continue;
              if (k2 != 0) {
                int s = dk_state_at(k, px, py, pz);
                int ns = dk_vt(k, V_DIST + k2 - 1, s);
                if (ns < 0) { k->flag |= 2; break; }
                dk_set(k, px, py, pz, ns, 19);
              }
              tr_mark(&t, c, TR_SHAPE);
              for (int d = 0; d < 6; d++) {
                int nx = px + ddx[d], ny = py + ddy[d], nz = pz + ddz[d];
                if (nx < bx0 || nx > bx1 || ny < by0 || ny > by1 || nz < bz0 || nz > bz1) continue;
                int nc = tr_cell(&t, nx, ny, nz);
                if (nc < 0) { k->flag |= 2; break; }
                if (tr_bits(&t, nc) & TR_SHAPE) continue;
                int dist = dk_value(k, nx, ny, nz, V_LEAF_DIST);
                if (dist != 15) {
                  int j1 = min(dist, k2 + 1);
                  if (j1 < 7) { tr_add(k, &t, TR_LV0 + j1, nx, ny, nz); k2 = min(k2, j1); }
                }
              }
            }
            if (!done) k2++;
          }
          if (k->flag & 2) return 0;
          // the box and the shape for the CPU's edge pass, which runs after the tree's writes (EF_EDGE)
          int bxo = k->bxo, bzo = k->bzo;
          dk_mark(k, EF_TREE, (bx0 - bxo) | ((by0 - OD_MINY) << 8) | ((bz0 - bzo) << 20), (bx1 - bx0 + 1) | ((bz1 - bz0 + 1) << 8));
          dk_mark(k, EF_TREE, by1 - by0 + 1, 0xffffff);
          for (int yy = by0; yy <= by1; yy++) for (int zz = bz0; zz <= bz1; zz++) for (int xx = bx0; xx <= bx1; xx++) {
            int c = tr_cell(&t, xx, yy, zz);
            if (c >= 0 && (tr_bits(&t, c) & TR_SHAPE)) {
              int ci = od_ci(xx, zz, bxo, bzo);
              dk_mark(k, EF_SHAPE, (ci << 20) | ((yy - OD_MINY) << 8) | (((zz - bzo) & 15) << 4) | ((xx - bxo) & 15), 0);
            }
          }
          // DiscreteVoxelShape.forAllFaces' three passes, as the CPU will run them: z faces by x, y, z; y faces by z, x, y; x faces by y, z, x
          int sxn = bx1 - bx0 + 1, syn = by1 - by0 + 1, szn = bz1 - bz0 + 1;
          for (int a = 0; a < sxn && !(k->flag & 2); a++) for (int b = 0; b < syn; b++) {
            int prev = 0;
            for (int c = 0; c <= szn; c++) {
              int cell = c == szn ? -1 : tr_cell(&t, bx0 + a, by0 + b, bz0 + c);
              int full = cell >= 0 && (tr_bits(&t, cell) & TR_SHAPE);
              if (!prev && full) tr_face(k, 2, bx0 + a, by0 + b, bz0 + c);
              if (prev && !full) tr_face(k, 3, bx0 + a, by0 + b, bz0 + c - 1);
              prev = full;
            }
          }
          for (int c = 0; c < szn && !(k->flag & 2); c++) for (int a = 0; a < sxn; a++) {
            int prev = 0;
            for (int b = 0; b <= syn; b++) {
              int cell = b == syn ? -1 : tr_cell(&t, bx0 + a, by0 + b, bz0 + c);
              int full = cell >= 0 && (tr_bits(&t, cell) & TR_SHAPE);
              if (!prev && full) tr_face(k, 0, bx0 + a, by0 + b, bz0 + c);
              if (prev && !full) tr_face(k, 1, bx0 + a, by0 + b - 1, bz0 + c);
              prev = full;
            }
          }
          for (int b = 0; b < syn && !(k->flag & 2); b++) for (int c = 0; c < szn; c++) {
            int prev = 0;
            for (int a = 0; a <= sxn; a++) {
              int cell = a == sxn ? -1 : tr_cell(&t, bx0 + a, by0 + b, bz0 + c);
              int full = cell >= 0 && (tr_bits(&t, cell) & TR_SHAPE);
              if (!prev && full) tr_face(k, 4, bx0 + a, by0 + b, bz0 + c);
              if (prev && !full) tr_face(k, 5, bx0 + a - 1, by0 + b, bz0 + c);
              prev = full;
            }
          }
          dk_mark(k, EF_EDGE, 0, 0);
          return (k->flag & 2) ? 0 : 1;
        }
        """;
}
