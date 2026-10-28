package dev.denismasterherobrine.generatoraccelerator2.gpu;

import dev.denismasterherobrine.generatoraccelerator2.chunkio.SectionWriter;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ChunkBinding;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.Ga2NoiseChunk;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.Ga2RandomState;
import dev.denismasterherobrine.generatoraccelerator2.dfc.runtime.ProgramSet;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.CacheAllInCellAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.NoiseChunkAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.NoiseInterpolatorAccessor;
import dev.denismasterherobrine.generatoraccelerator2.mixin.dfc.PalettedContainerInvoker;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;

/**
 * GPU go/no-go probe: GA2's IR for the overworld router as OpenCL, run on the local GPU. Checks bit for bit against GA2's own
 * NoiseChunk (corners, per-block final density), GA2's noise fill (every block and fluid update mark), GA2's biome step (every
 * quart) and a chunk rebuilt from the GPU's output on the CPU (sections, palettes, heightmaps, post-processing). Then times every
 * kernel per batch size and the CPU work that is left. {@code gradlew test --tests "*GpuProbe*" -Dga2.gpu=true --no-configuration-cache}
 */
@EnabledIfSystemProperty(named = "ga2.gpu", matches = "true")
public class GpuProbe {
    private static final long SEED = Long.getLong("ga2.gpu.seed", 1234567L);
    static final String OPTS = "-cl-std=CL1.2 -cl-fp32-correctly-rounded-divide-sqrt";

    static void log(String fmt, Object... args) {
        System.out.println("[GpuProbe] " + String.format(Locale.ROOT, fmt, args));
    }

    /** Everything the kernels need that does not depend on the variant. */
    static final class Setup {
        final TerrainHarness.World world;
        final ProgramSet set;
        final List<Node.Marker> rootMarkers;
        final Node cellRoot;
        final int cellWidth, cellHeight, minY, height, seaLevel;
        final long aqLo, aqHi, oreLo, oreHi;
        final long[] treeBounds, treeOffset;
        final int[] treeFirst, treeCount, treeLeaf;
        final List<Holder<Biome>> biomeList = new ArrayList<>();
        final BlockState[] states;
        final float[] beardKernel;
        final Map<BlockState, Integer> stateIds = new IdentityHashMap<>();

        Setup(long seed) throws Exception {
            this(TerrainHarness.world(TerrainHarness.Preset.OVERWORLD, seed));
        }

        Setup(TerrainHarness.World world) throws Exception {
            this.world = world;

            set = ((Ga2RandomState) (Object) world.randomState).ga2$programs();
            NoiseSettings ns = world.settings.noiseSettings();
            cellWidth = ns.getCellWidth();
            cellHeight = ns.getCellHeight();
            minY = ns.minY();
            height = ns.height();
            seaLevel = world.settings.seaLevel();
            rootMarkers = set.column().rootMarkers;
            Node finalDensity = set.routerRoots().get("final_density");
            Node beard = new Node.Beardifier(DensityFunctions.BeardifierMarker.INSTANCE, 0.0, 0.0);
            cellRoot = new Node.Ap2(Node.Ap2Type.ADD, finalDensity, beard, finalDensity.min(), finalDensity.max());
            long[] aq = seeds(world.randomState.aquiferRandom());
            long[] ore = seeds(world.randomState.oreRandom());
            aqLo = aq[0];
            aqHi = aq[1];
            oreLo = ore[0];
            oreHi = ore[1];
            states = new BlockState[] { Blocks.AIR.defaultBlockState(), world.settings.defaultBlock(), Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState(),
                Blocks.COPPER_ORE.defaultBlockState(), Blocks.RAW_COPPER_BLOCK.defaultBlockState(), Blocks.GRANITE.defaultBlockState(),
                Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(), Blocks.RAW_IRON_BLOCK.defaultBlockState(), Blocks.TUFF.defaultBlockState() };
            if (world.settings.defaultFluid() != Blocks.WATER.defaultBlockState()) {
                throw new IllegalStateException("default fluid is not water");
            }
            Field beardField = Beardifier.class.getDeclaredField("BEARD_KERNEL");
            beardField.setAccessible(true);
            beardKernel = ((float[]) beardField.get(null)).clone();
            for (int i = 0; i < states.length; i++) {
                stateIds.put(states[i], i);
            }
            // the climate tree, flat (GA2's FlatClimateTree layout: breadth-first ids, children consecutive)
            Method parameters = MultiNoiseBiomeSource.class.getDeclaredMethod("parameters");
            parameters.setAccessible(true);
            @SuppressWarnings("unchecked")
            Climate.ParameterList<Holder<Biome>> list = (Climate.ParameterList<Holder<Biome>>) parameters.invoke(world.biomeSource);
            Climate.RTree<Holder<Biome>> tree = list.index;
            List<Climate.RTree.Node<Holder<Biome>>> order = new ArrayList<>();
            List<int[]> children = new ArrayList<>();
            order.add(tree.root);
            int head = 0;
            while (head < order.size()) {
                Climate.RTree.Node<Holder<Biome>> node = order.get(head++);
                if (node instanceof Climate.RTree.SubTree<Holder<Biome>> sub) {
                    int start = order.size();
                    order.addAll(Arrays.asList(sub.children));
                    children.add(new int[] { start, sub.children.length });
                } else {
                    children.add(new int[] { 0, 0 });
                }
            }
            int n = order.size();
            treeBounds = new long[n * 12];
            treeOffset = new long[n];
            treeFirst = new int[n];
            treeCount = new int[n];
            treeLeaf = new int[n];
            Map<Holder<Biome>, Integer> index = new IdentityHashMap<>();
            for (int id = 0; id < n; id++) {
                Climate.RTree.Node<Holder<Biome>> node = order.get(id);
                Climate.Parameter[] space = node.parameterSpace;
                for (int d = 0; d < 6; d++) {
                    treeBounds[id * 12 + 2 * d] = space[d].min();
                    treeBounds[id * 12 + 2 * d + 1] = space[d].max();
                }
                long above = -space[6].max();
                long below = space[6].min();
                long t = above > 0 ? above : Math.max(below, 0L);
                treeOffset[id] = t * t;
                treeFirst[id] = children.get(id)[0];
                treeCount[id] = children.get(id)[1];
                if (node instanceof Climate.RTree.Leaf<Holder<Biome>> leaf) {
                    Integer b = index.get(leaf.value);
                    if (b == null) {
                        b = biomeList.size();
                        biomeList.add(leaf.value);
                        index.put(leaf.value, b);
                    }
                    treeLeaf[id] = b;
                }
            }
        }

        private static long[] seeds(PositionalRandomFactory f) throws Exception {
            Field lo = f.getClass().getDeclaredField("seedLo");
            Field hi = f.getClass().getDeclaredField("seedHi");
            lo.setAccessible(true);
            hi.setAccessible(true);
            return new long[] { lo.getLong(f), hi.getLong(f) };
        }

        ClGen gen(ClGen.Prec prec, boolean hoist) {
            ClGen g = new ClGen(prec, hoist, set.specs(), set.blendedSpecs(), rootMarkers, cellRoot, set.routerRoots(), cellWidth, cellHeight, minY, height, seaLevel);
            g.tables = Boolean.parseBoolean(System.getProperty("ga2.gpu.tables", "true"));
            g.noinline = Boolean.parseBoolean(System.getProperty("ga2.gpu.noinline", "true"));
            return g;
        }

        ClGen gen(ClGen.Prec prec, boolean tables, boolean noinline, String nonce) {
            ClGen g = gen(prec, true);
            g.tables = tables;
            g.noinline = noinline;
            g.nonce = nonce;
            return g;
        }
    }

    /** One compiled variant: program, kernels, constant buffers. */
    static final class Variant implements AutoCloseable {
        final String name;
        final ClGen gen;
        final Setup s;
        final Cl cl;
        final long kFlat, kHoist, kCorner, kSurface, kStatus, kBlock, kBiome, kPack, kHeights, kSurfaceIn, kBHoist;
        final long perm, tb, toff, tfirst, tcount, tleaf, od, oi, of, bk;
        final int rsize;
        final double buildMs;
        final int sourceChars;

        Variant(Cl cl, Setup s, String name, ClGen gen, String defines) {
            this.cl = cl;
            this.s = s;
            this.name = name;
            this.gen = gen;
            String src = gen.source();
            this.sourceChars = src.length();
            try {
                Path dir = Path.of("build", "gpu-probe");
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(name + ".cl"), src);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            long t0 = System.nanoTime();
            long program = cl.build(src, OPTS + " " + defines, null);
            this.buildMs = (System.nanoTime() - t0) / 1e6;
            kFlat = cl.kernel(program, "k_flat");
            kHoist = cl.kernel(program, "k_hoist");
            kCorner = cl.kernel(program, "k_corner");
            kSurface = cl.kernel(program, "k_surface");
            kSurfaceIn = cl.kernel(program, "k_surface_in");
            kBHoist = cl.kernel(program, "k_bhoist");
            kStatus = cl.kernel(program, "k_status");
            kBlock = cl.kernel(program, "k_block");
            kBiome = cl.kernel(program, "k_biome");
            kPack = cl.kernel(program, "k_pack");
            kHeights = cl.kernel(program, "k_heights");
            perm = cl.buffer(bytes(gen.permTable()));
            od = cl.buffer(doubles(gen.od));
            oi = cl.buffer(ints(gen.oi));
            of = cl.buffer(floats(gen.of));
            tb = cl.buffer(longs(s.treeBounds));
            toff = cl.buffer(longs(s.treeOffset));
            tfirst = cl.buffer(ints(s.treeFirst));
            tcount = cl.buffer(ints(s.treeCount));
            tleaf = cl.buffer(ints(s.treeLeaf));
            bk = cl.buffer(floats(s.beardKernel));
            rsize = gen.prec == ClGen.Prec.F64 ? 8 : 4;
        }

        private static ByteBuffer bytes(byte[] a) {
            ByteBuffer b = BufferUtils.createByteBuffer(Math.max(16, a.length));
            b.put(a).flip();
            return b;
        }

        private static ByteBuffer longs(long[] a) {
            ByteBuffer b = BufferUtils.createByteBuffer(Math.max(16, a.length * 8));
            b.asLongBuffer().put(a);
            return b;
        }

        private static ByteBuffer doubles(double[] a) {
            ByteBuffer b = BufferUtils.createByteBuffer(Math.max(16, a.length * 8));
            b.asDoubleBuffer().put(a);
            return b;
        }

        private static ByteBuffer floats(float[] a) {
            ByteBuffer b = BufferUtils.createByteBuffer(Math.max(16, a.length * 4));
            b.asFloatBuffer().put(a);
            return b;
        }

        static ByteBuffer ints(int[] a) {
            ByteBuffer b = BufferUtils.createByteBuffer(Math.max(16, a.length * 4));
            b.asIntBuffer().put(a);
            return b;
        }

        @Override
        public void close() {
            for (long m : new long[] { perm, tb, toff, tfirst, tcount, tleaf, od, oi, of, bk }) {
                cl.release(m);
            }
        }
    }

    /** One region of W x H chunks through every kernel. */
    static final class Run implements AutoCloseable {
        final Cl cl;
        final Variant v;
        final int cx0, cz0, w, h, nchunks;
        final int nqx, nqz, ncx, ncz, ncy, nbx, nbz, height, blocks;
        final long cornerCount;
        final int sqx0, sqz0, nsx, nsz;
        long flat, hz, corners, surf, cells, out, dens, biomes, storage, meta, marks, tops, bpBuf, bjBuf, bidxBuf, surfIn, bhBuf;
        final long[] ns = new long[9];
        static final String[] KERNELS = { "flat", "hoist", "corner", "surface", "status", "block", "biome", "pack", "heights" };
        final Structures structures;

        Run(Cl cl, Variant v, int cx0, int cz0, int w, int h, boolean density) {
            this(cl, v, cx0, cz0, w, h, density, Structures.none(cx0, cz0, w, h));
        }

        Run(Cl cl, Variant v, int cx0, int cz0, int w, int h, boolean density, Structures structures) {
            this.structures = structures;
            this.cl = cl;
            this.v = v;
            this.cx0 = cx0;
            this.cz0 = cz0;
            this.w = w;
            this.h = h;
            this.nchunks = w * h;
            ClGen g = v.gen;
            int per = 16 / g.cellWidth;
            nqx = 4 * w + 1;
            nqz = 4 * h + 1;
            ncx = per * w + 1;
            ncz = per * h + 1;
            ncy = g.cellCountY + 1;
            nbx = 16 * w;
            nbz = 16 * h;
            height = g.height;
            blocks = nbx * nbz * height;
            sqx0 = 4 * cx0 - 16;
            sqz0 = 4 * cz0 - 8;
            nsx = 4 * w + 23;
            nsz = 4 * h + 15;
            flat = cl.buffer((long) Math.max(1, g.flats.size()) * nqx * nqz * v.rsize);
            hz = cl.buffer((long) Math.max(1, g.hoistCount()) * ncx * ncz * v.rsize);
            cornerCount = g.perChunkCorners ? (long) nchunks * g.roots.size() * (per + 1) * (per + 1) * ncy : (long) g.roots.size() * ncx * ncz * ncy;
            corners = cl.buffer(cornerCount * v.rsize);
            surf = cl.buffer((long) nsx * nsz * 4);
            surfIn = cl.buffer((long) nchunks * 25 * 4);
            bhBuf = cl.buffer((long) nbx * nbz * Math.max(1, g.blockHoisted.size()) * v.rsize);
            cells = cl.buffer((long) nchunks * g.cellsPerChunk * 5 * 4);
            out = cl.buffer(blocks);
            dens = cl.buffer(density ? (long) blocks * v.rsize : 16);
            biomes = cl.buffer((long) nchunks * 16 * (height / 4) * 4);
            bpBuf = cl.buffer(Variant.ints(structures.bp));
            bjBuf = cl.buffer(Variant.ints(structures.bj));
            bidxBuf = cl.buffer(Variant.ints(structures.bidx));
            int nsec = height / 16;
            storage = cl.buffer((long) nchunks * nsec * 256 * 8);
            meta = cl.buffer((long) nchunks * nsec * ClGen.META * 4);
            marks = cl.buffer((long) nchunks * nsec * 64 * 8);
            tops = cl.buffer((long) nchunks * 256 * 2 * 4);
            int bx0 = cx0 * 16;
            int bz0 = cz0 * 16;
            int qx0 = bx0 >> 2;
            int qz0 = bz0 >> 2;
            long local = Long.getLong("ga2.gpu.local", 64);
            long[] ev = new long[9];

            int a = common(v.kFlat, qx0, qz0);
            ev[0] = cl.launch(v.kFlat, (long) nqx * nqz, local);

            a = common(v.kHoist, qx0, qz0);
            Cl.arg(v.kHoist, a++, hz);
            Cl.argInt(v.kHoist, a++, bx0);
            Cl.argInt(v.kHoist, a++, bz0);
            Cl.argInt(v.kHoist, a++, ncx);
            Cl.argInt(v.kHoist, a, ncz);
            ev[1] = cl.launch(v.kHoist, (long) ncx * ncz, local);

            a = common(v.kCorner, qx0, qz0);
            Cl.arg(v.kCorner, a++, hz);
            Cl.arg(v.kCorner, a++, corners);
            Cl.argInt(v.kCorner, a++, bx0);
            Cl.argInt(v.kCorner, a++, bz0);
            Cl.argInt(v.kCorner, a++, ncx);
            Cl.argInt(v.kCorner, a++, ncz);
            Cl.argInt(v.kCorner, a++, ncy);
            Cl.argInt(v.kCorner, a++, h);
            Cl.argInt(v.kCorner, a++, nchunks);
            Cl.arg(v.kCorner, a++, bpBuf);
            Cl.arg(v.kCorner, a++, bjBuf);
            Cl.arg(v.kCorner, a++, bidxBuf);
            Cl.arg(v.kCorner, a, v.bk);
            ev[2] = cl.launch(v.kCorner, g.perChunkCorners ? (long) nchunks * (per + 1) * (per + 1) * ncy : (long) ncx * ncz * ncy, local);

            a = common(v.kSurface, qx0, qz0);
            Cl.arg(v.kSurface, a++, surf);
            Cl.argInt(v.kSurface, a++, sqx0);
            Cl.argInt(v.kSurface, a++, sqz0);
            Cl.argInt(v.kSurface, a++, nsx);
            Cl.argInt(v.kSurface, a, nsz);
            ev[3] = cl.launch(v.kSurface, (long) nsx * nsz, local);
            long evIn = 0;
            if (g.surfacePerChunk) {
                int ai = common(v.kSurfaceIn, qx0, qz0);
                Cl.arg(v.kSurfaceIn, ai++, surfIn);
                Cl.argInt(v.kSurfaceIn, ai++, cx0);
                Cl.argInt(v.kSurfaceIn, ai++, cz0);
                Cl.argInt(v.kSurfaceIn, ai++, h);
                Cl.argInt(v.kSurfaceIn, ai, nchunks);
                evIn = cl.launch(v.kSurfaceIn, (long) nchunks * 25, local);
            }

            a = common(v.kStatus, qx0, qz0);
            Cl.arg(v.kStatus, a++, surf);
            Cl.arg(v.kStatus, a++, surfIn);
            Cl.argInt(v.kStatus, a++, sqx0);
            Cl.argInt(v.kStatus, a++, sqz0);
            Cl.argInt(v.kStatus, a++, nsz);
            Cl.arg(v.kStatus, a++, cells);
            Cl.argInt(v.kStatus, a++, cx0);
            Cl.argInt(v.kStatus, a++, cz0);
            Cl.argInt(v.kStatus, a++, h);
            Cl.argInt(v.kStatus, a++, nchunks);
            Cl.argLong(v.kStatus, a++, v.s.aqLo);
            Cl.argLong(v.kStatus, a, v.s.aqHi);
            ev[4] = cl.launch(v.kStatus, (long) nchunks * g.cellsPerChunk, local);

            int ab = common(v.kBHoist, qx0, qz0);
            Cl.arg(v.kBHoist, ab++, bhBuf);
            Cl.argInt(v.kBHoist, ab++, bx0);
            Cl.argInt(v.kBHoist, ab++, bz0);
            Cl.argInt(v.kBHoist, ab++, nbx);
            Cl.argInt(v.kBHoist, ab, nbz);
            long evBh = cl.launch(v.kBHoist, (long) nbx * nbz, local);
            a = common(v.kBlock, qx0, qz0);
            Cl.arg(v.kBlock, a++, corners);
            Cl.arg(v.kBlock, a++, out);
            Cl.arg(v.kBlock, a++, dens);
            Cl.argInt(v.kBlock, a++, bx0);
            Cl.argInt(v.kBlock, a++, bz0);
            Cl.argInt(v.kBlock, a++, nbz);
            Cl.argInt(v.kBlock, a++, ncx);
            Cl.argInt(v.kBlock, a++, ncz);
            Cl.argInt(v.kBlock, a++, ncy);
            Cl.argInt(v.kBlock, a++, blocks);
            Cl.arg(v.kBlock, a++, cells);
            Cl.argInt(v.kBlock, a++, cx0);
            Cl.argInt(v.kBlock, a++, cz0);
            Cl.argInt(v.kBlock, a++, h);
            Cl.argLong(v.kBlock, a++, v.s.oreLo);
            Cl.argLong(v.kBlock, a++, v.s.oreHi);
            Cl.arg(v.kBlock, a++, bpBuf);
            Cl.arg(v.kBlock, a++, bjBuf);
            Cl.arg(v.kBlock, a++, bidxBuf);
            Cl.arg(v.kBlock, a++, v.bk);
            Cl.arg(v.kBlock, a, bhBuf);
            ev[5] = cl.launch(v.kBlock, blocks, Long.getLong("ga2.gpu.blockLocal", 128));

            a = common(v.kBiome, qx0, qz0);
            Cl.arg(v.kBiome, a++, v.tb);
            Cl.arg(v.kBiome, a++, v.toff);
            Cl.arg(v.kBiome, a++, v.tfirst);
            Cl.arg(v.kBiome, a++, v.tcount);
            Cl.arg(v.kBiome, a++, v.tleaf);
            Cl.arg(v.kBiome, a++, biomes);
            Cl.argInt(v.kBiome, a++, cx0);
            Cl.argInt(v.kBiome, a++, cz0);
            Cl.argInt(v.kBiome, a++, h);
            Cl.argInt(v.kBiome, a, nchunks);
            ev[6] = cl.launch(v.kBiome, (long) nchunks * 16 * (height / 4), local);
            Cl.arg(v.kPack, 0, out);
            Cl.argInt(v.kPack, 1, nbz);
            Cl.argInt(v.kPack, 2, h);
            Cl.arg(v.kPack, 3, storage);
            Cl.arg(v.kPack, 4, meta);
            Cl.arg(v.kPack, 5, marks);
            ev[7] = cl.launch(v.kPack, (long) nchunks * nsec * 256, 256);
            Cl.arg(v.kHeights, 0, out);
            Cl.argInt(v.kHeights, 1, nbz);
            Cl.argInt(v.kHeights, 2, h);
            Cl.argInt(v.kHeights, 3, nchunks);
            Cl.arg(v.kHeights, 4, tops);
            ev[8] = cl.launch(v.kHeights, (long) nchunks * 256, local);
            cl.finish();
            for (int i = 0; i < 9; i++) {
                ns[i] = Cl.nanos(ev[i]);
            }
            if (evIn != 0) {
                ns[3] += Cl.nanos(evIn);
            }
            ns[1] += Cl.nanos(evBh);
        }

        private int common(long k, int qx0, int qz0) {
            int a = 0;
            Cl.arg(k, a++, v.perm);
            Cl.arg(k, a++, v.od);
            Cl.arg(k, a++, v.oi);
            Cl.arg(k, a++, v.of);
            Cl.arg(k, a++, flat);
            Cl.argInt(k, a++, qx0);
            Cl.argInt(k, a++, qz0);
            Cl.argInt(k, a++, nqx);
            Cl.argInt(k, a++, nqz);
            return a;
        }

        long total() {
            long t = 0;
            for (long n : ns) {
                t += n;
            }
            return t;
        }

        double[] readReal(long mem, long count) {
            ByteBuffer b = cl.readAll(mem, count * v.rsize, null);
            double[] o = new double[(int) count];
            if (v.rsize == 8) {
                b.asDoubleBuffer().get(o);
            } else {
                FloatBuffer f = b.asFloatBuffer();
                for (int i = 0; i < o.length; i++) {
                    o[i] = f.get(i);
                }
            }
            return o;
        }

        byte[] readBlocks(long[] nanos) {
            ByteBuffer b = cl.readAll(out, blocks, nanos);
            byte[] o = new byte[blocks];
            b.get(o);
            return o;
        }

        int[] readBiomes(long[] nanos) {
            int n = nchunks * 16 * (height / 4);
            ByteBuffer b = cl.readAll(biomes, (long) n * 4, nanos);
            int[] o = new int[n];
            b.asIntBuffer().get(o);
            return o;
        }

        /** The packed result (k_pack, k_heights) and the biomes; {@code nanos[0]} receives the read time. */
        Packed readPacked(long[] nanos) {
            int nsec = height / 16;
            long t0 = System.nanoTime();
            long[] st = new long[nchunks * nsec * 256];
            cl.readAll(storage, (long) st.length * 8, null).asLongBuffer().get(st);
            int[] me = new int[nchunks * nsec * ClGen.META];
            cl.readAll(meta, (long) me.length * 4, null).asIntBuffer().get(me);
            long[] mk = new long[nchunks * nsec * 64];
            cl.readAll(marks, (long) mk.length * 8, null).asLongBuffer().get(mk);
            int[] tp = new int[nchunks * 256 * 2];
            cl.readAll(tops, (long) tp.length * 4, null).asIntBuffer().get(tp);
            int[] bi = readBiomes(null);
            if (nanos != null) {
                nanos[0] = System.nanoTime() - t0;
            }
            return new Packed(st, me, mk, tp, bi);
        }

        @Override
        public void close() {
            for (long m : new long[] { flat, hz, corners, surf, cells, out, dens, biomes, storage, meta, marks, tops, bpBuf, bjBuf, bidxBuf, surfIn, bhBuf }) {
                cl.release(m);
            }
        }
    }

    @Test
    void probe() throws Exception {
        Setup s = GpuDatapack.setup(SEED);
        try (Cl cl = Cl.open()) {
            log("device: %s", cl.describe());
            String bad = GpuArithmeticCheck.run(cl, OPTS);
            log("arithmetic check (%s): %s", OPTS, bad.isEmpty() ? "every pattern bit-identical to Java" : bad);
            boolean fp64 = cl.hasFp64();
            boolean timing = Boolean.parseBoolean(System.getProperty("ga2.gpu.timing", "true"));
            // the four programs compile in parallel (NVIDIA builds each in minutes cold)
            ExecutorService builds = Executors.newFixedThreadPool(4);
            long b0 = System.nanoTime();
            var f64vF = fp64 ? builds.submit(() -> new Variant(cl, s, "f64-verify", s.gen(ClGen.Prec.F64, true), "-D FULL -D WRITE_DENSITY")) : null;
            var f32vF = builds.submit(() -> new Variant(cl, s, "f32-verify", s.gen(ClGen.Prec.F32, true), "-D FULL -D WRITE_DENSITY"));
            var f64tF = fp64 && timing ? builds.submit(() -> new Variant(cl, s, "f64-full", s.gen(ClGen.Prec.F64, true), "-D FULL")) : null;
            var f32tF = timing ? builds.submit(() -> new Variant(cl, s, "f32-full", s.gen(ClGen.Prec.F32, true), "-D FULL")) : null;
            Variant f64v = f64vF == null ? null : f64vF.get();
            Variant f32v = f32vF.get();
            builds.shutdown();
            log("builds started together: verify variants done after %.0f ms", (System.nanoTime() - b0) / 1e6);
            ClGen g0 = (fp64 ? f64v : f32v).gen;
            log("router: %d interpolated roots, %d flat caches, %d hoisted x/z values; %d aquifer cells per chunk; climate tree of %d nodes, %d biomes; builds %.0f + %.0f ms",
                g0.roots.size(), g0.flats.size(), g0.hoistCount(), g0.cellsPerChunk, s.treeFirst.length, s.biomeList.size(),
                fp64 ? f64v.buildMs : 0.0, f32v.buildMs);

            // ---- exactness ----
            int[][] regions = { { 0, 0 }, { -1953, 771 }, { 117187, -117187 } };
            if (fp64) {
                long[] tot = new long[10];
                for (int[] r : regions) {
                    try (Run run = new Run(cl, f64v, r[0], r[1], 2, 2, true)) {
                        long[] n = compareDensity(s, run, run.readReal(run.corners, run.cornerCount), run.readReal(run.dens, run.blocks));
                        tot[0] += n[0];
                        tot[1] += n[1];
                        byte[] blocks = run.readBlocks(null);
                        int[] biomes = run.readBiomes(null);
                        long[] b = compareChunks(s, run, blocks, biomes);
                        for (int i = 0; i < b.length; i++) {
                            tot[2 + i] += b[i];
                        }
                    }
                }
                log("EXACT density: %,d corner values, %,d block densities bit-identical to GA2's NoiseChunk", tot[0], tot[1]);
                log("blocks vs GA2's noise fill: %,d compared, %,d differ; fluid update marks: %,d fluid blocks, %,d differ",
                    tot[2], tot[3], tot[4], tot[5]);
                log("biomes vs GA2's biome step: %,d quarts compared, %,d differ (%,d of them ties, which depend on the searching thread's last result), %,d ties in all",
                    tot[6], tot[7], tot[8], tot[9]);
                f32Stats(cl, f64v, f32v, regions);
            }
            if (!Boolean.parseBoolean(System.getProperty("ga2.gpu.timing", "true"))) {
                f32v.close();
                if (f64v != null) {
                    f64v.close();
                }
                return;
            }

            // ---- timing ----
            List<Variant> timed = new ArrayList<>();
            if (fp64) {
                timed.add(f64tF.get());
            }
            timed.add(f32tF.get());
            log("all four builds done after %.0f ms", (System.nanoTime() - b0) / 1e6);
            for (Variant v : timed) {
                log("%s: %,d chars of OpenCL C, built in %.0f ms", v.name, v.sourceChars, v.buildMs);
            }
            int[] sides = { 1, 2, 4, 8, 16 };
            int reps = Integer.getInteger("ga2.gpu.reps", 5);
            int origin = 4000;
            for (Variant v : timed) {
                for (int side : sides) {
                    long[][] samples = new long[reps][];
                    try (Run warm = new Run(cl, v, origin, origin, side, side, false)) {
                        origin += side + 1;
                    }
                    for (int i = 0; i < reps; i++) {
                        long w0 = System.nanoTime();
                        long[] rd = new long[2];
                        try (Run run = new Run(cl, v, origin, -origin, side, side, false)) {
                            long[] t1 = new long[1];
                            run.readPacked(t1);
                            rd[0] = t1[0];
                            long wall = System.nanoTime() - w0;
                            long[] row = new long[12];
                            System.arraycopy(run.ns, 0, row, 0, 9);
                            row[9] = run.total();
                            row[10] = rd[0];
                            row[11] = wall;
                            samples[i] = row;
                        }
                        origin += side + 1;
                    }
                    long[] med = new long[12];
                    for (int k = 0; k < 12; k++) {
                        final int kk = k;
                        long[] col = Arrays.stream(samples).mapToLong(x -> x[kk]).sorted().toArray();
                        med[k] = col[col.length / 2];
                    }
                    int chunks = side * side;
                    StringBuilder parts = new StringBuilder();
                    for (int k = 0; k < 9; k++) {
                        parts.append(String.format(Locale.ROOT, " %s %.1f", Run.KERNELS[k], med[k] / 1e3 / chunks));
                    }
                    log("%-8s %2dx%-2d: %7.1f us/chunk on the device (%s), readback %.1f us/chunk, wall %.1f us/chunk incl. allocation", v.name, side, side,
                        med[9] / 1e3 / chunks, parts.toString().trim(), med[10] / 1e3 / chunks, med[11] / 1e3 / chunks);
                }
            }
            cpuBaseline(s, cl, timed.get(0));
            for (Variant v : timed) {
                v.close();
            }
            f32v.close();
            if (f64v != null) {
                f64v.close();
            }
        }
    }

    /**
     * Structures: synthetic pieces of every terrain adjustment and jigsaw junctions over each region, the GPU's beardifier against GA2's
     * (density, blocks, biomes, rebuilt chunks), then the device time with and without them.
     * {@code gradlew test --tests "*GpuProbe.structures*" -Dga2.gpu.structures=true --no-configuration-cache}
     */
    @Test
    @EnabledIfSystemProperty(named = "ga2.gpu.structures", matches = "true")
    void structures() throws Exception {
        Setup s = GpuDatapack.setup(SEED);
        try (Cl cl = Cl.open()) {
            Variant v = new Variant(cl, s, "structures-f64", s.gen(ClGen.Prec.F64, true, true, null), "-D FULL -D WRITE_DENSITY");
            log("structures: device %s, built in %.1f s", cl.describe(), v.buildMs / 1e3);
            int[][] regions = { { 0, 0 }, { -1953, 771 }, { 117187, -117187 }, { 40, -60 } };
            long[] tot = new long[10];
            long pieces = 0, junctions = 0;
            for (int[] r : regions) {
                Structures st = Structures.synthetic(SEED, r[0], r[1], 2, 2, 512);
                pieces += st.pieceRefs;
                junctions += st.junctionRefs;
                try (Run run = new Run(cl, v, r[0], r[1], 2, 2, true, st)) {
                    long[] n = compareDensity(s, run, run.readReal(run.corners, run.cornerCount), run.readReal(run.dens, run.blocks));
                    tot[0] += n[0];
                    tot[1] += n[1];
                    long[] b = compareChunks(s, run, run.readBlocks(null), run.readBiomes(null));
                    for (int i = 0; i < b.length; i++) {
                        tot[2 + i] += b[i];
                    }
                }
            }
            log("structures: %,d piece and %,d junction references over %d regions of 2x2 chunks; density bit-identical (%,d corners, %,d blocks);"
                + " blocks differing %d of %,d; fluid marks differing %d; biomes differing %d; rebuilt chunks identical", pieces, junctions, regions.length,
                tot[0], tot[1], tot[3], tot[2], tot[5], tot[7]);
            v.close();
            Variant t = new Variant(cl, s, "structures-time", s.gen(ClGen.Prec.F64, true, true, null), "-D FULL");
            for (double density : new double[] { 0, 8, 32 }) {
                long[] total = new long[3];
                long block = 0;
                for (int i = 0; i < 4; i++) {
                    int ox = 5000 + i * 20;
                    Structures st = density == 0 ? Structures.none(ox, 5000, 16, 16) : Structures.synthetic(SEED + i, ox, 5000, 16, 16, density);
                    try (Run run = new Run(cl, t, ox, 5000, 16, 16, false, st)) {
                        if (i > 0) {
                            total[0] += run.total();
                            block += run.ns[5];
                            total[1] += st.pieceRefs;
                            total[2] += st.junctionRefs;
                        }
                    }
                }
                log("structures: %4.0f clusters per 16x16 chunks (%.0f piece and %.0f junction references per chunk): %.1f us/chunk on the device, block kernel %.1f us/chunk",
                    density, total[1] / 3.0 / 256, total[2] / 3.0 / 256, total[0] / 3.0 / 1e3 / 256, block / 3.0 / 1e3 / 256);
            }
            t.close();
        }
    }

    // ---- exactness ----------------------------------------------------------------------------------------------

    /** Corners and per-block final density against GA2's NoiseChunk; throws on the first difference. */
    @SuppressWarnings("unchecked")
    static long[] compareDensity(Setup s, Run run, double[] gc, double[] gd) throws Exception {
        Field rootIndexField = ChunkBinding.class.getDeclaredField("rootIndex");
        rootIndexField.setAccessible(true);
        int cw = s.cellWidth, ch = s.cellHeight, per = 16 / cw, cellCountY = s.height / ch;
        long nCorners = 0, nBlocks = 0;
        for (int i = 0; i < run.w; i++) {
            for (int j = 0; j < run.h; j++) {
                int cx = run.cx0 + i, cz = run.cz0 + j;
                NoiseChunk nc = new NoiseChunk(per, s.world.randomState, cx * 16, cz * 16, s.world.settings.noiseSettings(), run.structures.beardifierFor(cx, cz),
                    s.world.settings, s.world.fluidPicker, Blender.empty());
                ChunkBinding binding = ((Ga2NoiseChunk) nc).ga2$binding();
                Map<NoiseChunk.NoiseInterpolator, Integer> rootIndex = (Map<NoiseChunk.NoiseInterpolator, Integer>) rootIndexField.get(binding);
                NoiseChunkAccessor acc = (NoiseChunkAccessor) nc;
                List<DensityFunction> caches = acc.ga2$cellCaches();
                for (int k = 0; k < per; k++) {
                    if (k == 0) {
                        nc.initializeForFirstCellX();
                        nc.advanceCellX(0);
                    } else {
                        nc.swapSlices();
                        nc.advanceCellX(k);
                    }
                    for (NoiseChunk.NoiseInterpolator it : acc.ga2$interpolators()) {
                        int r = rootIndex.get(it);
                        NoiseInterpolatorAccessor a = (NoiseInterpolatorAccessor) it;
                        if (k == 0) {
                            nCorners += compareSlice(run, gc, r, i * per, j * per, a.ga2$slice0(), cx, cz);
                        }
                        nCorners += compareSlice(run, gc, r, i * per + k + 1, j * per, a.ga2$slice1(), cx, cz);
                    }
                    for (int cellZ = 0; cellZ < per; cellZ++) {
                        for (int cellY = cellCountY - 1; cellY >= 0; cellY--) {
                            nc.selectCellYZ(cellY, cellZ);
                            double[] values = ((CacheAllInCellAccessor) caches.get(0)).ga2$values();
                            for (int iy = 0; iy < ch; iy++) {
                                for (int ix = 0; ix < cw; ix++) {
                                    for (int iz = 0; iz < cw; iz++) {
                                        double expected = values[((ch - 1 - iy) * cw + ix) * cw + iz];
                                        int X = i * 16 + k * cw + ix, Z = j * 16 + cellZ * cw + iz, Y = cellY * ch + iy;
                                        double actual = gd[(X * run.nbz + Z) * run.height + Y];
                                        if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(actual)) {
                                            throw new AssertionError("block density differs at chunk " + cx + "," + cz + " block " + X + "," + Y + "," + Z + ": cpu=" + expected + " gpu=" + actual);
                                        }
                                        nBlocks++;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return new long[] { nCorners, nBlocks };
    }

    private static long compareSlice(Run run, double[] gc, int root, int colX, int colZ0, double[][] slice, int cx, int cz) {
        long n = 0;
        for (int z = 0; z < slice.length; z++) {
            for (int y = 0; y < slice[z].length; y++) {
                int idx = run.v.gen.perChunkCorners
                    ? (((cx - run.cx0) * run.h + (cz - run.cz0)) * run.v.gen.roots.size() + root) * 25 * run.ncy + ((colX - (cx - run.cx0) * 4) * 5 + colZ0 + z - (cz - run.cz0) * 4) * run.ncy + y
                    : ((root * run.ncx + colX) * run.ncz + colZ0 + z) * run.ncy + y;
                if (Double.doubleToRawLongBits(slice[z][y]) != Double.doubleToRawLongBits(gc[idx])) {
                    throw new AssertionError("corner differs: chunk " + cx + "," + cz + " root " + root + " column " + colX + "," + (colZ0 + z) + " y index " + y
                        + ": cpu=" + slice[z][y] + " gpu=" + gc[idx]);
                }
                n++;
            }
        }
        return n;
    }

    /**
     * The GPU's blocks and biomes against GA2's BIOMES and NOISE steps, then a chunk rebuilt from them on the CPU against the
     * real one: sections (counts, palettes in id order, storage), heightmaps, post-processing marks, biome palettes.
     *
     * @return {blocks, block differences, fluid blocks, mark differences, quarts, biome differences, of them ties, ties}
     */
    /** GA2's BIOMES and NOISE steps for a chunk with these structures' beardifier (as the step would build it). */
    static ProtoChunk refChunk(Setup s, int cx, int cz, Structures structures) {
        TerrainHarness.World w = s.world;
        ProtoChunk c = w.emptyChunk(cx, cz);
        Beardifier beard = structures.beardifierFor(cx, cz);
        c.getOrCreateNoiseChunk(ch -> NoiseChunk.forChunk(ch, w.randomState, beard, w.settings, w.fluidPicker, Blender.empty()));
        w.generator.createBiomes(Runnable::run, w.randomState, Blender.empty(), null, c).join();
        c.setStatus(ChunkStatus.BIOMES);
        w.generator.fillFromNoise(Runnable::run, Blender.empty(), w.randomState, null, c).join();
        c.setStatus(ChunkStatus.NOISE);
        return c;
    }

    static long[] compareChunks(Setup s, Run run, byte[] blocks, int[] biomes) {
        long[] r = new long[8];
        int quartsY = s.height / 4;
        int shown = 0;
        Packed packed = run.readPacked(null);
        for (int i = 0; i < run.w; i++) {
            for (int j = 0; j < run.h; j++) {
                int cx = run.cx0 + i, cz = run.cz0 + j;
                ProtoChunk ref = refChunk(s, cx, cz, run.structures);
                ShortList[] post = ref.getPostProcessing();
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        for (int Y = 0; Y < s.height; Y++) {
                            int y = s.minY + Y;
                            BlockState expected = ref.getSection(ref.getSectionIndex(y)).getBlockState(lx, y & 15, lz);
                            int g = blocks[((i * 16 + lx) * run.nbz + j * 16 + lz) * run.height + Y] & 255;
                            BlockState actual = s.states[g & 0x7f];
                            r[0]++;
                            if (actual != expected) {
                                r[1]++;
                                if (shown++ < 8) {
                                    log("  block differs at %d,%d,%d: cpu %s gpu %s", cx * 16 + lx, y, cz * 16 + lz, expected, actual);
                                }
                            } else if (!expected.getFluidState().isEmpty()) {
                                r[2]++;
                                ShortList list = post[ref.getSectionIndex(y)];
                                boolean marked = list != null && list.contains(ProtoChunk.packOffsetCoordinates(new BlockPos(lx, y, lz)));
                                if (marked != ((g & 0x80) != 0)) {
                                    r[3]++;
                                }
                            }
                        }
                    }
                }
                int base = (i * run.h + j) * 16 * quartsY;
                for (int qy = 0; qy < quartsY; qy++) {
                    for (int qx = 0; qx < 4; qx++) {
                        for (int qz = 0; qz < 4; qz++) {
                            int v = biomes[base + qy * 16 + qx * 4 + qz];
                            boolean tie = v < 0;
                            Holder<Biome> actual = s.biomeList.get(v & 0x7fffffff);
                            Holder<Biome> expected = ref.getNoiseBiome(cx * 4 + qx, (s.minY >> 2) + qy, cz * 4 + qz);
                            r[4]++;
                            if (tie) {
                                r[7]++;
                            }
                            if (actual != expected) {
                                r[5]++;
                                if (tie) {
                                    r[6]++;
                                }
                                if (shown++ < 8) {
                                    log("  biome differs at quart %d,%d,%d: cpu %s gpu %s%s", cx * 4 + qx, (s.minY >> 2) + qy, cz * 4 + qz,
                                        expected.unwrapKey().map(k -> k.location().toString()).orElse("?"), actual.unwrapKey().map(k -> k.location().toString()).orElse("?"),
                                        tie ? " (tie)" : "");
                                }
                            }
                        }
                    }
                }
                ProtoChunk rebuilt = s.world.emptyChunk(cx, cz);
                consume(s, rebuilt, blocks, biomes, run, i, j);
                ProtoChunk packedChunk = s.world.emptyChunk(cx, cz);
                consumePacked(s, packedChunk, packed, run, i, j);
                try {
                    sameChunk(ref, rebuilt, "chunk " + cx + "," + cz);
                    sameChunk(ref, packedChunk, "packed chunk " + cx + "," + cz);
                } catch (AssertionError e) {
                    if (Boolean.getBoolean("ga2.gpu.lenientRebuild")) {
                        log("  %s", e.getMessage());
                    } else {
                        throw e;
                    }
                }
            }
        }
        return r;
    }

    /**
     * The CPU's part once the GPU has decided a chunk: its biomes written as {@code LevelChunkSection.fillBiomesFromNoise} writes
     * them, its blocks in {@code doFill}'s order (through GA2's section writer), the two worldgen heightmaps and the fluid update marks.
     */
    static void consume(Setup s, ProtoChunk chunk, byte[] blocks, int[] biomes, Run run, int i, int j) {
        int quartsY = s.height / 4;
        int cx = chunk.getPos().x, cz = chunk.getPos().z;
        int qx0 = cx * 4, qz0 = cz * 4, qy0 = s.minY >> 2;
        int base = (i * run.h + j) * 16 * quartsY;
        LevelChunkSection[] secs = chunk.getSections();
        for (int sec = 0; sec < secs.length; sec++) {
            int sy = chunk.getSectionYFromSectionIndex(sec);
            secs[sec].fillBiomesFromNoise((x, y, z, sampler) -> s.biomeList.get(biomes[base + (y - qy0) * 16 + (x - qx0) * 4 + (z - qz0)] & 0x7fffffff), null,
                qx0, sy * 4, qz0);
        }
        Heightmap ocean = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        var oceanTest = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();
        int[] topO = new int[256];
        int[] topS = new int[256];
        BlockState[] stO = new BlockState[256];
        BlockState[] stS = new BlockState[256];
        Arrays.fill(topO, Integer.MIN_VALUE);
        Arrays.fill(topS, Integer.MIN_VALUE);
        SectionWriter sw = SectionWriter.begin(chunk);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int cw = s.cellWidth, ch = s.cellHeight, per = 16 / cw, cellCountY = s.height / ch, cellMinY = Math.floorDiv(s.minY, ch);
        try {
            for (int cellX = 0; cellX < per; cellX++) {
                for (int cellZ = 0; cellZ < per; cellZ++) {
                    for (int cellY = cellCountY - 1; cellY >= 0; cellY--) {
                        for (int iy = ch - 1; iy >= 0; iy--) {
                            int y = (cellMinY + cellY) * ch + iy;
                            int sec = chunk.getSectionIndex(y);
                            for (int ix = 0; ix < cw; ix++) {
                                int lx = cellX * cw + ix;
                                for (int iz = 0; iz < cw; iz++) {
                                    int lz = cellZ * cw + iz;
                                    int g = blocks[((i * 16 + lx) * run.nbz + j * 16 + lz) * run.height + (y - s.minY)] & 255;
                                    int id = g & 0x7f;
                                    if (id == ClGen.ID_AIR) {
                                        continue;
                                    }
                                    BlockState state = s.states[id];
                                    if (sw != null) {
                                        sw.select(sec);
                                        sw.put((y & 15) << 8 | lz << 4 | lx, sw.id(state));
                                    } else {
                                        secs[sec].setBlockState(lx, y & 15, lz, state, false);
                                    }
                                    int col = lx + lz * 16;
                                    if (topS[col] == Integer.MIN_VALUE) {
                                        topS[col] = y;
                                        stS[col] = state;
                                    }
                                    if (topO[col] == Integer.MIN_VALUE && oceanTest.test(state)) {
                                        topO[col] = y;
                                        stO[col] = state;
                                    }
                                    if ((g & 0x80) != 0 && !state.getFluidState().isEmpty()) {
                                        chunk.markPosForPostprocessing(pos.set(cx * 16 + lx, y, cz * 16 + lz));
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (sw != null) {
                sw.finish();
            }
        } finally {
            if (sw != null) {
                sw.close();
            }
        }
        for (int col = 0; col < 256; col++) {
            if (topO[col] != Integer.MIN_VALUE) {
                ocean.update(col & 15, topO[col], col >> 4, stO[col]);
            }
            if (topS[col] != Integer.MIN_VALUE) {
                surface.update(col & 15, topS[col], col >> 4, stS[col]);
            }
        }
    }

    /** k_pack's and k_heights' output for a region, with its biomes. */
    record Packed(long[] storage, int[] meta, long[] marks, int[] tops, int[] biomes) {}

    /**
     * The CPU's part when the GPU has also packed the chunk: biomes as {@link #consume} writes them, then per section vanilla's linear
     * palette rebuilt from the GPU's order and the GPU's storage copied in, the counters from its histogram, the fluid update marks in
     * {@code doFill}'s order, and one heightmap update per column.
     */
    static void consumePacked(Setup s, ProtoChunk chunk, Packed p, Run run, int i, int j) {
        int quartsY = s.height / 4;
        int cx = chunk.getPos().x, cz = chunk.getPos().z;
        int qx0 = cx * 4, qz0 = cz * 4, qy0 = s.minY >> 2;
        int b = i * run.h + j;
        int base = b * 16 * quartsY;
        int nsec = s.height / 16;
        LevelChunkSection[] secs = chunk.getSections();
        int[] biomes = p.biomes();
        BlockState air = s.states[ClGen.ID_AIR];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int sec = 0; sec < secs.length; sec++) {
            int sy = chunk.getSectionYFromSectionIndex(sec);
            secs[sec].fillBiomesFromNoise((x, y, z, sampler) -> s.biomeList.get(biomes[base + (y - qy0) * 16 + (x - qx0) * 4 + (z - qz0)] & 0x7fffffff), null,
                qx0, sy * 4, qz0);
            int grp = b * nsec + sec;
            int m = grp * ClGen.META;
            int psize = p.meta()[m];
            if (psize == 0) {
                continue; // nothing but air: the section stays as it is
            }
            PalettedContainer<BlockState> container = secs[sec].getStates();
            @SuppressWarnings("unchecked")
            PalettedContainer.Data<BlockState> data = ((PalettedContainerInvoker) (Object) container)
                .ga2$createOrReuseData(null, Mth.ceillog2(psize));
            var palette = data.palette();
            palette.idFor(air);
            int nonEmpty = 0, ticking = 0, fluids = 0;
            for (int a = 1; a < psize; a++) {
                BlockState state = s.states[p.meta()[m + a]];
                palette.idFor(state);
            }
            for (int id = 1; id < s.states.length; id++) {
                int c = p.meta()[m + 16 + id];
                if (c == 0) {
                    continue;
                }
                BlockState state = s.states[id];
                if (!state.isAir()) {
                    nonEmpty += c;
                    if (state.isRandomlyTicking()) {
                        ticking += c;
                    }
                }
                if (!state.getFluidState().isEmpty()) {
                    fluids += c;
                }
            }
            long[] raw = data.storage().getRaw();
            if (palette.getSize() != psize || data.storage().getBits() != 4 || raw.length != 256) {
                throw new IllegalStateException("unexpected section layout: palette " + palette.getSize() + ", bits " + data.storage().getBits());
            }
            System.arraycopy(p.storage(), grp * 256, raw, 0, 256);
            container.data = data;
            secs[sec].nonEmptyBlockCount = (short) nonEmpty;
            secs[sec].tickingBlockCount = (short) ticking;
            secs[sec].tickingFluidCount = (short) fluids;
            int mk = grp * 64;
            boolean anyMark = false;
            for (int k = 0; k < 64 && !anyMark; k++) {
                anyMark = p.marks()[mk + k] != 0L;
            }
            if (anyMark) {
                int y0 = sy * 16;
                for (int cellX = 0; cellX < 4; cellX++) {
                    for (int cellZ = 0; cellZ < 4; cellZ++) {
                        for (int y = 15; y >= 0; y--) {
                            for (int ix = 0; ix < 4; ix++) {
                                for (int iz = 0; iz < 4; iz++) {
                                    int lx = cellX * 4 + ix, lz = cellZ * 4 + iz;
                                    int idx = y << 8 | lz << 4 | lx;
                                    if ((p.marks()[mk + (idx >> 6)] >>> (idx & 63) & 1L) != 0L) {
                                        chunk.markPosForPostprocessing(pos.set(cx * 16 + lx, y0 + y, cz * 16 + lz));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Heightmap ocean = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        BlockState stone = s.states[ClGen.ID_STONE];
        int t = b * 512;
        for (int col = 0; col < 256; col++) {
            int top = p.tops()[t + col * 2];
            int floor = p.tops()[t + col * 2 + 1];
            if (top != Integer.MIN_VALUE) {
                surface.update(col & 15, top, col >> 4, stone);
            }
            if (floor != Integer.MIN_VALUE) {
                ocean.update(col & 15, floor, col >> 4, stone);
            }
        }
    }

    /** GA2's whole-chunk comparison (MapAllMemoTest.sameChunk) plus the biome palettes. */
    static void sameChunk(ProtoChunk a, ProtoChunk b, String where) {
        LevelChunkSection[] sa = a.getSections();
        LevelChunkSection[] sb = b.getSections();
        for (int s = 0; s < sa.length; s++) {
            check(sa[s].nonEmptyBlockCount == sb[s].nonEmptyBlockCount, "non-empty count, section " + s + ", " + where);
            check(sa[s].tickingBlockCount == sb[s].tickingBlockCount, "ticking count, section " + s + ", " + where);
            check(sa[s].tickingFluidCount == sb[s].tickingFluidCount, "fluid count, section " + s + ", " + where);
            var da = sa[s].getStates().data;
            var db = sb[s].getStates().data;
            check(da.palette().getClass() == db.palette().getClass(), "palette type, section " + s + ", " + where);
            check(da.palette().getSize() == db.palette().getSize(), "palette size, section " + s + ", " + where);
            for (int p = 0; p < da.palette().getSize(); p++) {
                check(da.palette().valueFor(p) == db.palette().valueFor(p), "palette entry " + p + ", section " + s + ", " + where);
            }
            check(Arrays.equals(da.storage().getRaw(), db.storage().getRaw()), "storage, section " + s + ", " + where);
            @SuppressWarnings("unchecked")
            var ba = ((PalettedContainer<Holder<Biome>>) sa[s].getBiomes()).data;
            @SuppressWarnings("unchecked")
            var bb = ((PalettedContainer<Holder<Biome>>) sb[s].getBiomes()).data;
            check(ba.palette().getSize() == bb.palette().getSize(), "biome palette size, section " + s + ", " + where);
            for (int p = 0; p < ba.palette().getSize(); p++) {
                check(ba.palette().valueFor(p) == bb.palette().valueFor(p), "biome palette entry " + p + ", section " + s + ", " + where);
            }
            check(Arrays.equals(ba.storage().getRaw(), bb.storage().getRaw()), "biome storage, section " + s + ", " + where);
        }
        for (var type : Heightmap.Types.values()) {
            check(a.hasPrimedHeightmap(type) == b.hasPrimedHeightmap(type), "heightmap presence " + type + ", " + where);
            if (a.hasPrimedHeightmap(type)) {
                check(Arrays.equals(a.getOrCreateHeightmapUnprimed(type).getRawData(), b.getOrCreateHeightmapUnprimed(type).getRawData()), "heightmap " + type + ", " + where);
            }
        }
        check(Arrays.deepToString(a.getPostProcessing()).equals(Arrays.deepToString(b.getPostProcessing())), "post-processing, " + where);
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("rebuilt chunk differs: " + what);
        }
    }

    /** F32 against F64 (exact): density drift, and how many blocks and biomes the float pipeline would get wrong. */
    static void f32Stats(Cl cl, Variant f64, Variant f32, int[][] regions) {
        double maxDensity = 0;
        long total = 0, flips = 0, blockDiff = 0, biomeDiff = 0, quarts = 0;
        double[] bounds = { 1e-6, 1e-5, 1e-4, 1e-3 };
        long[] within = new long[bounds.length];
        for (int[] r : regions) {
            try (Run a = new Run(cl, f64, r[0], r[1], 2, 2, true); Run b = new Run(cl, f32, r[0], r[1], 2, 2, true)) {
                double[] da = a.readReal(a.dens, a.blocks);
                double[] db = b.readReal(b.dens, b.blocks);
                byte[] ba = a.readBlocks(null);
                byte[] bb = b.readBlocks(null);
                for (int i = 0; i < da.length; i++) {
                    maxDensity = Math.max(maxDensity, Math.abs(da[i] - db[i]));
                    flips += (da[i] > 0) != (db[i] > 0) ? 1 : 0;
                    blockDiff += ba[i] != bb[i] ? 1 : 0;
                    for (int k = 0; k < bounds.length; k++) {
                        within[k] += Math.abs(da[i]) <= bounds[k] ? 1 : 0;
                    }
                    total++;
                }
                int[] ia = a.readBiomes(null);
                int[] ib = b.readBiomes(null);
                for (int i = 0; i < ia.length; i++) {
                    biomeDiff += (ia[i] & 0x7fffffff) != (ib[i] & 0x7fffffff) ? 1 : 0;
                    quarts++;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < bounds.length; k++) {
            sb.append(String.format(Locale.ROOT, " |d|<=%.0e: %.4f%%", bounds[k], 100.0 * within[k] / total));
        }
        log("F32 vs F64: max density error %.3g, solid/air flips %d, block ids differing %d of %,d, biomes differing %d of %,d;%s",
            maxDensity, flips, blockDiff, total, biomeDiff, quarts, sb);
    }

    // ---- CPU ------------------------------------------------------------------------------------------------------

    /** GA2's single-threaded BIOMES and NOISE steps, and the CPU's part (the rebuild) when the GPU decides. */
    static void cpuBaseline(Setup s, Cl cl, Variant v) {
        TerrainHarness.World world = s.world;
        int origin = 9000;
        double biomesUs = 0, noiseUs = 0, consumeUs = 0, packedUs = 0;
        for (int round = 0; round < 4; round++) {
            int n = 64;
            ProtoChunk[] chunks = new ProtoChunk[n];
            for (int i = 0; i < n; i++) {
                chunks[i] = world.emptyChunk(origin + i, 7000);
            }
            long t0 = System.nanoTime();
            for (ProtoChunk c : chunks) {
                world.noiseChunk(c);
                world.generator.createBiomes(Runnable::run, world.randomState, Blender.empty(), null, c).join();
                c.setStatus(ChunkStatus.BIOMES);
            }
            long t1 = System.nanoTime();
            for (ProtoChunk c : chunks) {
                world.generator.fillFromNoise(Runnable::run, Blender.empty(), world.randomState, null, c).join();
                c.setStatus(ChunkStatus.NOISE);
            }
            long t2 = System.nanoTime();
            // the CPU's part of the same work when the GPU decides: rebuild 8x8 chunks from one region's output
            long consume, packedNs;
            try (Run run = new Run(cl, v, origin + 100, 7100, 8, 8, false)) {
                byte[] blocks = run.readBlocks(null);
                int[] biomes = run.readBiomes(null);
                ProtoChunk[] empty = new ProtoChunk[64];
                for (int i = 0; i < 64; i++) {
                    empty[i] = world.emptyChunk(run.cx0 + i / 8, run.cz0 + i % 8);
                }
                long c0 = System.nanoTime();
                for (int i = 0; i < 64; i++) {
                    consume(s, empty[i], blocks, biomes, run, i / 8, i % 8);
                }
                consume = System.nanoTime() - c0;
                Packed packed = run.readPacked(null);
                ProtoChunk[] empty2 = new ProtoChunk[64];
                for (int i = 0; i < 64; i++) {
                    empty2[i] = world.emptyChunk(run.cx0 + i / 8, run.cz0 + i % 8);
                }
                long p0 = System.nanoTime();
                for (int i = 0; i < 64; i++) {
                    consumePacked(s, empty2[i], packed, run, i / 8, i % 8);
                }
                packedNs = System.nanoTime() - p0;
            }
            origin += n + 200;
            if (round > 0) {
                biomesUs += (t1 - t0) / 1e3 / n / 3;
                noiseUs += (t2 - t1) / 1e3 / n / 3;
                consumeUs += consume / 1e3 / 64 / 3;
                packedUs += packedNs / 1e3 / 64 / 3;
            }
        }
        log("CPU (GA2, one thread, warm, mean of 3 rounds of 64 chunks): BIOMES %.1f us/chunk, NOISE %.1f us/chunk; with the GPU deciding, the CPU's part"
            + " (biome and block writes, heightmaps, marks) %.1f us/chunk block by block, %.1f us/chunk from the GPU-packed sections", biomesUs, noiseUs, consumeUs, packedUs);
    }
}
