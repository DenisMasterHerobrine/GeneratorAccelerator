package dev.denismasterherobrine.generatoraccelerator2.gpu;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

/**
 * Structure pieces and jigsaw junctions over a region, collected per chunk the way {@code Beardifier.forStructuresInChunk} collects
 * them (pieces within 12 blocks of the chunk, junctions with their source within 12 blocks), for the CPU's Beardifier and the GPU's
 * per-chunk lists. Synthetic: villages of boxes on the surface (beard or bury), buried rooms (encapsulate), junctions at their edges.
 */
final class Structures {
    final int cx0, cz0, w, h;
    private final List<Beardifier.Rigid> pieces = new ArrayList<>();
    private final List<JigsawJunction> junctions = new ArrayList<>();
    /** GPU form: pieces (8 ints each) and junctions (3 ints each) per chunk, and per chunk {first piece, count, first junction, count}. */
    final int[] bp, bj, bidx;
    int pieceRefs, junctionRefs;

    private Structures(int cx0, int cz0, int w, int h) {
        this.cx0 = cx0;
        this.cz0 = cz0;
        this.w = w;
        this.h = h;
        bidx = new int[w * h * 4];
        bp = null;
        bj = null;
    }

    private Structures(Structures src, int[] bp, int[] bj) {
        this.cx0 = src.cx0;
        this.cz0 = src.cz0;
        this.w = src.w;
        this.h = src.h;
        this.pieces.addAll(src.pieces);
        this.junctions.addAll(src.junctions);
        this.bidx = src.bidx;
        this.bp = bp;
        this.bj = bj;
        this.pieceRefs = src.pieceRefs;
        this.junctionRefs = src.junctionRefs;
    }

    /** No structures: every chunk's lists empty. */
    static Structures none(int cx0, int cz0, int w, int h) {
        return new Structures(new Structures(cx0, cz0, w, h), new int[8], new int[3]);
    }

    /** About {@code density} structures per 16x16 chunks, each a cluster of pieces, deterministic for the seed and region. */
    static Structures synthetic(long seed, int cx0, int cz0, int w, int h, double density) {
        Structures s = new Structures(cx0, cz0, w, h);
        SplittableRandom r = new SplittableRandom(seed ^ ((long) cx0 * 341873128712L + (long) cz0 * 132897987541L));
        int clusters = Math.max(1, (int) Math.round(density * w * h / 256.0));
        TerrainAdjustment[] surface = { TerrainAdjustment.BEARD_THIN, TerrainAdjustment.BEARD_BOX, TerrainAdjustment.BURY };
        for (int k = 0; k < clusters; k++) {
            int x = cx0 * 16 + r.nextInt(w * 16);
            int z = cz0 * 16 + r.nextInt(h * 16);
            boolean buried = r.nextInt(4) == 0;
            TerrainAdjustment adj = buried ? TerrainAdjustment.ENCAPSULATE : surface[r.nextInt(surface.length)];
            int baseY = buried ? -40 + r.nextInt(60) : 58 + r.nextInt(20);
            int count = 4 + r.nextInt(12);
            for (int p = 0; p < count; p++) {
                int px = x + r.nextInt(48) - 24;
                int pz = z + r.nextInt(48) - 24;
                int sx = 3 + r.nextInt(12), sy = 3 + r.nextInt(10), sz = 3 + r.nextInt(12);
                int py = baseY + r.nextInt(6) - 3;
                s.pieces.add(new Beardifier.Rigid(new BoundingBox(px, py, pz, px + sx, py + sy, pz + sz), r.nextInt(10) == 0 ? TerrainAdjustment.NONE : adj,
                    r.nextInt(3)));
                if (r.nextBoolean()) {
                    s.junctions.add(new JigsawJunction(px + r.nextInt(sx + 1), py, pz + r.nextInt(sz + 1), r.nextInt(3) - 1,
                        r.nextBoolean() ? StructureTemplatePool.Projection.RIGID : StructureTemplatePool.Projection.TERRAIN_MATCHING));
                }
            }
        }
        // per chunk, as forStructuresInChunk filters
        List<Integer> bp = new ArrayList<>();
        List<Integer> bj = new ArrayList<>();
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                int b = i * h + j;
                List<Beardifier.Rigid> ps = s.piecesFor(cx0 + i, cz0 + j);
                List<JigsawJunction> js = s.junctionsFor(cx0 + i, cz0 + j);
                s.bidx[b * 4] = bp.size() / 8;
                s.bidx[b * 4 + 1] = ps.size();
                s.bidx[b * 4 + 2] = bj.size() / 3;
                s.bidx[b * 4 + 3] = js.size();
                for (Beardifier.Rigid p : ps) {
                    BoundingBox bb = p.box();
                    bp.addAll(List.of(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ(), p.terrainAdjustment().ordinal(), p.groundLevelDelta()));
                }
                for (JigsawJunction jj : js) {
                    bj.addAll(List.of(jj.getSourceX(), jj.getSourceGroundY(), jj.getSourceZ()));
                }
                s.pieceRefs += ps.size();
                s.junctionRefs += js.size();
            }
        }
        return new Structures(s, toArray(bp, 8), toArray(bj, 3));
    }

    private static int[] toArray(List<Integer> l, int min) {
        int[] a = new int[Math.max(min, l.size())];
        for (int i = 0; i < l.size(); i++) {
            a[i] = l.get(i);
        }
        return a;
    }

    List<Beardifier.Rigid> piecesFor(int cx, int cz) {
        int minX = cx * 16, minZ = cz * 16;
        List<Beardifier.Rigid> out = new ArrayList<>();
        for (Beardifier.Rigid p : pieces) {
            // StructurePiece.isCloseToChunk(chunk, 12)
            if (p.box().intersects(minX - 12, minZ - 12, minX + 15 + 12, minZ + 15 + 12)) {
                out.add(p);
            }
        }
        return out;
    }

    List<JigsawJunction> junctionsFor(int cx, int cz) {
        int i = cx * 16, j = cz * 16;
        List<JigsawJunction> out = new ArrayList<>();
        for (JigsawJunction jj : junctions) {
            int k = jj.getSourceX();
            int l = jj.getSourceZ();
            if (k > i - 12 && l > j - 12 && k < i + 15 + 12 && l < j + 15 + 12) {
                out.add(jj);
            }
        }
        return out;
    }

    /** The chunk's Beardifier as the NOISE step would build it from these structures. */
    Beardifier beardifierFor(int cx, int cz) {
        return new Beardifier(new ObjectArrayList<>(piecesFor(cx, cz)).iterator(), new ObjectArrayList<>(junctionsFor(cx, cz)).iterator());
    }
}
