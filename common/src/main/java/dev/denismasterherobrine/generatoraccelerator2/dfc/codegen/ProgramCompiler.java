package dev.denismasterherobrine.generatoraccelerator2.dfc.codegen;

import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.CellBounds;
import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.CoordDeps;
import dev.denismasterherobrine.generatoraccelerator2.dfc.analysis.RefCount;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.BlendedSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.Node;
import dev.denismasterherobrine.generatoraccelerator2.dfc.ir.NoiseSpec;
import dev.denismasterherobrine.generatoraccelerator2.dfc.pipeline.Compiler;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Generates program classes from IR roots and instantiates them. */
public final class ProgramCompiler {
    private ProgramCompiler() {}

    private static final AtomicInteger COUNTER = new AtomicInteger();
    /** Cell programs keep each interpolated lerp's xy halves per cell row ({@code Ops.cellLerp}). Off with -Dgeneratoraccelerator2.cellRowLerp=false. */
    static final boolean CELL_ROW_LERP = !"false".equals(System.getProperty("generatoraccelerator2.cellRowLerp"));
    /** Interval hints for the splines of cell programs ({@link SplineRt}). Off with -Dgeneratoraccelerator2.dfc.splineHints=false. */
    public static boolean SPLINE_HINTS = !"false".equals(System.getProperty("generatoraccelerator2.dfc.splineHints"));
    /** Cell programs stamp their shared slots per block instead of filling them with NaN ({@link CellProgram#stamps}). Off with -Dgeneratoraccelerator2.dfc.slotStamps=false. */
    public static boolean SLOT_STAMPS = !"false".equals(System.getProperty("generatoraccelerator2.dfc.slotStamps"));
    private static final String GA2_NOISE_CHUNK = "dev/denismasterherobrine/generatoraccelerator2/dfc/runtime/Ga2NoiseChunk";
    private static final String CTOR_DESC = Names.runtimeDesc("([Ldev/denismasterherobrine/generatoraccelerator2/dfc/ir/NoiseSpec;"
        + "[Ldev/denismasterherobrine/generatoraccelerator2/dfc/ir/BlendedSpec;"
        + "[Lnet/minecraft/world/level/levelgen/DensityFunction;"
        + "[L" + SplineRt.INTERNAL + ";[Ljava/lang/Object;II)V");
    private static final MethodType CTOR_TYPE = MethodType.methodType(void.class,
        NoiseSpec[].class, BlendedSpec[].class, DensityFunction[].class, SplineRt[].class, Object[].class, int.class, int.class);

    /** ClassWriter that computes frames without loading classes: every merge of two different reference types is Object. */
    static final class SafeClassWriter extends ClassWriter {
        SafeClassWriter() {
            super(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            return type1.equals(type2) ? type1 : "java/lang/Object";
        }
    }

    /**
     * Generates at the default layout. When a method comes out too large to be JIT-compiled, generates again with smaller
     * methods. Large datapack splines and routers with many roots can trigger this. The code computes the same either way.
     */
    private static <T> T compacting(IntFunction<T> generate) {
        for (int compact = 0; ; compact++) {
            try {
                return generate.apply(compact);
            } catch (DfcCompileException e) {
                if (!e.sizeLimit || compact >= Emitter.MAX_COMPACT) {
                    throw e;
                }
            }
        }
    }

    /** RandomState-level point program (markers transparent), instantiated with its own externs. */
    public static PointProgram compilePoint(String debugName, List<Node> roots, List<NoiseSpec> specs, List<BlendedSpec> blended) {
        PointTemplate t = compilePointTemplate(debugName, roots, specs, blended, Emitter.Mode.POINT);
        return t.instantiate(t.externTemplates);
    }

    /**
     * Point program template. {@code Mode.POINT} evaluates markers transparently (RandomState-level
     * trees). {@code Mode.COLUMN} applies the chunk marker policy so the program can be bound to the
     * wrappers of a NoiseChunk. The chunk climate sampler uses this mode to sample single points.
     */
    public static PointTemplate compilePointTemplate(String debugName, List<Node> roots, List<NoiseSpec> specs, List<BlendedSpec> blended, Emitter.Mode mode) {
        return compacting(compact -> compilePointTemplate(debugName, roots, specs, blended, mode, compact));
    }

    private static PointTemplate compilePointTemplate(String debugName, List<Node> roots, List<NoiseSpec> specs, List<BlendedSpec> blended, Emitter.Mode mode,
                                                      int compact) {
        String name = HiddenClassLoader.GENERATED_PREFIX + "Point" + COUNTER.incrementAndGet();
        SafeClassWriter cw = new SafeClassWriter();
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, name, null, PointProgram.INTERNAL, null);

        Emitter emitter = new Emitter(cw, name, specs, blended, mode, new CoordDeps(mode != Emitter.Mode.POINT), compact);
        emitter.plan(roots);
        if (compact > 0) {
            emitter.forceHelpers(roots);
        }

        // computeAll(ctx, scratch, out): locals 0 this, 1 ctx, 2 scratch, 3 out, 4 x, 5 y, 6 z, 7-8 tmp, 9-10 tmp2
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "computeAll", PointProgram.COMPUTE_ALL_DESC, null, null);
        mv.visitCode();
        Emitter.Locals l = new Emitter.Locals(1, 2, 4, 5, 6, 7, 9, 11);
        Emitter.loadCoords(mv, l);
        Emitter.emitReset(mv, l, 0, emitter.slotCount());
        if (mode == Emitter.Mode.COLUMN) {
            // Single-point use of the column marker policy: eager hoisted (x,z) values are read back
            // without a NaN check, so their phase must run here too, before the per-point phase.
            emitter.emitPrologue(mv, l, true);
        }
        emitter.emitPrologue(mv, l, false);
        for (int r = 0; r < roots.size(); r++) {
            emitter.emit(mv, l, roots.get(r));
            mv.visitVarInsn(Opcodes.DSTORE, l.tmp());
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            Emitter.pushInt(mv, r);
            mv.visitVarInsn(Opcodes.DLOAD, l.tmp());
            mv.visitInsn(Opcodes.DASTORE);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // computeColumnPoint(ctx, scratch, out, hoistedValid): locals 0 this, 1 ctx, 2 scratch, 3 out, 4 hoistedValid, 5 x, 6 y, 7 z, 8-9 tmp, 10-11 tmp2
        MethodVisitor cp = cw.visitMethod(Opcodes.ACC_PUBLIC, "computeColumnPoint", PointProgram.COMPUTE_COLUMN_POINT_DESC, null, null);
        cp.visitCode();
        Emitter.Locals cl = new Emitter.Locals(1, 2, 5, 6, 7, 8, 10, 12);
        Emitter.loadCoords(cp, cl);
        Label hoistedReady = new Label();
        cp.visitVarInsn(Opcodes.ILOAD, 4);
        cp.visitJumpInsn(Opcodes.IFNE, hoistedReady);
        Emitter.emitReset(cp, cl, 0, emitter.hoistEnd());
        if (mode == Emitter.Mode.COLUMN) {
            emitter.emitPrologue(cp, cl, true);
        }
        cp.visitLabel(hoistedReady);
        emitter.emitResetBeforePrologue(cp, cl, emitter.hoistEnd(), emitter.slotCount(), false);
        emitter.emitPrologue(cp, cl, false);
        for (int r = 0; r < roots.size(); r++) {
            emitter.emit(cp, cl, roots.get(r));
            cp.visitVarInsn(Opcodes.DSTORE, cl.tmp());
            cp.visitVarInsn(Opcodes.ALOAD, 3);
            Emitter.pushInt(cp, r);
            cp.visitVarInsn(Opcodes.DLOAD, cl.tmp());
            cp.visitInsn(Opcodes.DASTORE);
        }
        cp.visitInsn(Opcodes.RETURN);
        cp.visitMaxs(0, 0);
        cp.visitEnd();

        emitter.emitHelpers();
        emitConstructor(cw, PointProgram.INTERNAL);
        cw.visitEnd();

        MethodHandles.Lookup lookup = HiddenClassLoader.define(cw, "point-" + debugName);
        try {
            MethodHandle mh = lookup.findConstructor(lookup.lookupClass(), CTOR_TYPE);
            return new PointTemplate(mh, specs.toArray(new NoiseSpec[0]), blended.toArray(new BlendedSpec[0]),
                emitter.splines().toArray(new SplineRt[0]), emitter.stateDescriptors(), emitter.slotCount(), roots.size(),
                emitter.externs().toArray(new DensityFunction[0]));
        } catch (Throwable t) {
            throw new DfcCompileException("could not resolve the constructor of generated point program " + debugName, t);
        }
    }

    /**
     * Compiles the children of the given {@code interpolated} markers into one column program.
     * Externs are RandomState-level objects. {@link ColumnTemplate#instantiate} binds chunk instances.
     */
    public static ColumnTemplate compileColumn(String debugName, List<Node.Marker> rootMarkers, List<NoiseSpec> specs, List<BlendedSpec> blended) {
        return compacting(compact -> compileColumn(debugName, rootMarkers, specs, blended, compact));
    }

    private static ColumnTemplate compileColumn(String debugName, List<Node.Marker> rootMarkers, List<NoiseSpec> specs, List<BlendedSpec> blended, int compact) {
        String name = HiddenClassLoader.GENERATED_PREFIX + "Column" + COUNTER.incrementAndGet();
        SafeClassWriter cw = new SafeClassWriter();
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, name, null, ColumnProgram.INTERNAL, null);

        List<Node> roots = new ArrayList<>(rootMarkers.size());
        for (Node.Marker m : rootMarkers) {
            roots.add(m.child());
        }
        Emitter emitter = new Emitter(cw, name, specs, blended, Emitter.Mode.COLUMN, new CoordDeps(true), compact);
        emitter.plan(roots);
        if (compact > 0) {
            emitter.forceHelpers(roots);
        }

        // fillColumn(ctx, scratch, out, blockX, blockZ, cellNoiseMinY, cellHeight, cellCountY)
        // locals: 0 this, 1 ctx, 2 scratch, 3 out, 4 blockX, 5 blockZ, 6 cellNoiseMinY, 7 cellHeight, 8 cellCountY,
        //         9 y, 10 yIndex, 11-12 tmp, 13-14 tmp2
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "fillColumn", ColumnProgram.FILL_DESC, null, null);
        mv.visitCode();
        Emitter.Locals l = new Emitter.Locals(1, 2, 4, 9, 5, 11, 13, 15);
        final int Y = 9, YI = 10, OUT = 3, MIN_Y = 6, CELL_H = 7, COUNT_Y = 8;
        Emitter.emitReset(mv, l, 0, emitter.slotCount());
        // (x,z)-only values that are always needed: computed once per column, before the corner loop
        mv.visitVarInsn(Opcodes.ILOAD, MIN_Y);
        mv.visitVarInsn(Opcodes.ILOAD, CELL_H);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitVarInsn(Opcodes.ISTORE, Y);
        emitter.emitPrologue(mv, l, true);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, YI);
        Label loop = new Label();
        Label end = new Label();
        Label skipCtx = new Label();
        mv.visitLabel(loop);
        mv.visitVarInsn(Opcodes.ILOAD, YI);
        mv.visitVarInsn(Opcodes.ILOAD, COUNT_Y);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, end);
        // y = (yIndex + cellNoiseMinY) * cellHeight
        mv.visitVarInsn(Opcodes.ILOAD, YI);
        mv.visitVarInsn(Opcodes.ILOAD, MIN_Y);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ILOAD, CELL_H);
        mv.visitInsn(Opcodes.IMUL);
        mv.visitVarInsn(Opcodes.ISTORE, Y);
        // if (ctx instanceof Ga2NoiseChunk) ctx.ga2$setSliceY(y, yIndex)
        mv.visitVarInsn(Opcodes.ALOAD, l.ctx());
        mv.visitTypeInsn(Opcodes.INSTANCEOF, GA2_NOISE_CHUNK);
        mv.visitJumpInsn(Opcodes.IFEQ, skipCtx);
        mv.visitVarInsn(Opcodes.ALOAD, l.ctx());
        mv.visitTypeInsn(Opcodes.CHECKCAST, GA2_NOISE_CHUNK);
        mv.visitVarInsn(Opcodes.ILOAD, Y);
        mv.visitVarInsn(Opcodes.ILOAD, YI);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, GA2_NOISE_CHUNK, "ga2$setSliceY", "(II)V", true);
        mv.visitLabel(skipCtx);
        // per-corner slots are recomputed for every y. Hoisted (x,z)-only slots persist across the column
        emitter.emitResetBeforePrologue(mv, l, emitter.hoistEnd(), emitter.slotCount(), false);
        emitter.emitPrologue(mv, l, false);
        for (int r = 0; r < roots.size(); r++) {
            emitter.emit(mv, l, roots.get(r));
            mv.visitVarInsn(Opcodes.DSTORE, l.tmp());
            mv.visitVarInsn(Opcodes.ALOAD, OUT);
            Emitter.pushInt(mv, r);
            mv.visitInsn(Opcodes.AALOAD);
            mv.visitVarInsn(Opcodes.ILOAD, YI);
            mv.visitVarInsn(Opcodes.DLOAD, l.tmp());
            mv.visitInsn(Opcodes.DASTORE);
        }
        mv.visitIincInsn(YI, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loop);
        mv.visitLabel(end);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        emitter.emitHelpers();
        emitConstructor(cw, ColumnProgram.INTERNAL);
        cw.visitEnd();

        MethodHandles.Lookup lookup = HiddenClassLoader.define(cw, "column-" + debugName);
        try {
            MethodHandle mh = lookup.findConstructor(lookup.lookupClass(), CTOR_TYPE);
            return new ColumnTemplate(mh, specs.toArray(new NoiseSpec[0]), blended.toArray(new BlendedSpec[0]),
                emitter.splines().toArray(new SplineRt[0]), emitter.stateDescriptors(), emitter.slotCount(),
                emitter.externs().toArray(new DensityFunction[0]), List.copyOf(rootMarkers), emitter.statefulMarkers());
        } catch (Throwable t) {
            throw new DfcCompileException("could not resolve the constructor of generated column program " + debugName, t);
        }
    }

    /**
     * Compiles one cell-cache root into a per-block fill program. {@code lerpIndex} maps each
     * {@code interpolated} marker to its column-program root so corner values can be read.
     */
    public static CellTemplate compileCell(String debugName, Node root, Node.Marker marker, Map<Node.Marker, Integer> lerpIndex,
                                           List<NoiseSpec> specs, List<BlendedSpec> blended) {
        return compacting(compact -> compileCell(debugName, root, marker, lerpIndex, specs, blended, compact));
    }

    private static CellTemplate compileCell(String debugName, Node root, Node.Marker marker, Map<Node.Marker, Integer> lerpIndex,
                                            List<NoiseSpec> specs, List<BlendedSpec> blended, int compact) {
        String name = HiddenClassLoader.GENERATED_PREFIX + "Cell" + COUNTER.incrementAndGet();
        SafeClassWriter cw = new SafeClassWriter();
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, name, null, CellProgram.INTERNAL, null);

        Emitter emitter = new Emitter(cw, name, specs, blended, Emitter.Mode.CELL, new CoordDeps(true), compact);
        emitter.setLerpIndex(lerpIndex);
        emitter.stampedSlots = SLOT_STAMPS;
        emitter.plan(List.of(root));
        // After the per-point slots: the two xy halves of every interpolated corner lerp, per cell row (one y, one x)
        final int rowBase = emitter.slotCount();
        int lerpCount = 0;
        for (int r : lerpIndex.values()) {
            lerpCount = Math.max(lerpCount, r + 1);
        }
        final int rowEnd = rowBase + 2 * lerpCount;
        SortedSet<Integer> rowLerps = new TreeSet<>();
        collectLerps(root, lerpIndex, rowLerps, new IdentityHashMap<>());
        if (CELL_ROW_LERP) {
            emitter.setCellRowBase(rowBase);
        }
        // Then the column tables, for x/z-only cache_2d markers and the other x/z-only subtrees. Two key slots hold the
        // cell column's start + 0.5, so a fresh, zeroed scratch never matches. One table per cache or value follows.
        final int columnBase = CELL_ROW_LERP ? rowEnd : rowBase;
        final int columnCount = emitter.columnTableCount();
        final int scratchEnd = columnBase + (columnCount == 0 ? 0 : 2 + columnCount * Emitter.COLUMN_CACHE_SIZE);
        if (columnCount > 0) {
            emitter.setColumnCacheBase(columnBase);
        }
        // then one interval hint per spline, never reset (a hint is checked before it is used)
        if (SPLINE_HINTS) {
            emitter.setSplineHintBase(scratchEnd);
        }

        // fillCell(ctx, scratch, values, corners, cellStartX, cellStartY, cellStartZ, cellWidth, cellHeight, beardActive)
        // locals: 0 this, 1 ctx, 2 scratch, 3 values, 4 corners, 5 csx, 6 csy, 7 csz, 8 cw, 9 ch, 10 beard,
        //         11 x, 12 y, 13 z, 14 ix, 15 iy, 16 iz, 17 idx, 18-19 dx, 20-21 dy, 22-23 dz, 24-25 tmp, 26-27 tmp2
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "fillCell", CellProgram.FILL_DESC, null, null);
        mv.visitCode();
        Emitter.Locals l = new Emitter.Locals(1, 2, 11, 12, 13, 24, 26, 28, 4, 18, 20, 22, 10);
        final int VALUES = 3, CSX = 5, CSY = 6, CSZ = 7, CW = 8, CH = 9, IX = 14, IY = 15, IZ = 16, IDX = 17;
        Label ly = new Label(), lx = new Label(), lz = new Label(), nx = new Label(), nz = new Label(), end = new Label(), skipCtx = new Label();
        if (columnCount > 0) {
            // another cell column: forget the column caches' values
            Label reset = new Label(), keep = new Label();
            emitColumnKeyCompare(mv, l.scratch(), columnBase, CSX);
            mv.visitJumpInsn(Opcodes.IFNE, reset);
            emitColumnKeyCompare(mv, l.scratch(), columnBase + 1, CSZ);
            mv.visitJumpInsn(Opcodes.IFEQ, keep);
            mv.visitLabel(reset);
            Emitter.emitReset(mv, l, columnBase + 2, scratchEnd);
            emitColumnKeyStore(mv, l.scratch(), columnBase, CSX);
            emitColumnKeyStore(mv, l.scratch(), columnBase + 1, CSZ);
            mv.visitLabel(keep);
        }
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, IDX);
        mv.visitVarInsn(Opcodes.ILOAD, CH);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ISTORE, IY);
        mv.visitLabel(ly);
        mv.visitVarInsn(Opcodes.ILOAD, IY);
        mv.visitJumpInsn(Opcodes.IFLT, end);
        mv.visitVarInsn(Opcodes.ILOAD, CSY);
        mv.visitVarInsn(Opcodes.ILOAD, IY);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ISTORE, l.y());
        divToDouble(mv, IY, CH);
        mv.visitVarInsn(Opcodes.DSTORE, l.dy());
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, IX);
        mv.visitLabel(lx);
        mv.visitVarInsn(Opcodes.ILOAD, IX);
        mv.visitVarInsn(Opcodes.ILOAD, CW);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, nx);
        mv.visitVarInsn(Opcodes.ILOAD, CSX);
        mv.visitVarInsn(Opcodes.ILOAD, IX);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ISTORE, l.x());
        divToDouble(mv, IX, CW);
        mv.visitVarInsn(Opcodes.DSTORE, l.dx());
        if (CELL_ROW_LERP) {
            // Ops.cellLerp only checks the first half of a pair for the marker, since the second half is written with
            // it. So only first-half slots are reset, and only for the interpolated markers this program can reach.
            for (int r : rowLerps) {
                mv.visitVarInsn(Opcodes.ALOAD, l.scratch());
                Emitter.pushInt(mv, rowBase + 2 * r);
                mv.visitLdcInsn(Double.NaN);
                mv.visitInsn(Opcodes.DASTORE);
            }
        }
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, IZ);
        mv.visitLabel(lz);
        mv.visitVarInsn(Opcodes.ILOAD, IZ);
        mv.visitVarInsn(Opcodes.ILOAD, CW);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, nz);
        mv.visitVarInsn(Opcodes.ILOAD, CSZ);
        mv.visitVarInsn(Opcodes.ILOAD, IZ);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ISTORE, l.z());
        divToDouble(mv, IZ, CW);
        mv.visitVarInsn(Opcodes.DSTORE, l.dz());
        // if (ctx instanceof Ga2NoiseChunk) ctx.ga2$setCell(ix, iy, iz, idx)
        mv.visitVarInsn(Opcodes.ALOAD, l.ctx());
        mv.visitTypeInsn(Opcodes.INSTANCEOF, GA2_NOISE_CHUNK);
        mv.visitJumpInsn(Opcodes.IFEQ, skipCtx);
        mv.visitVarInsn(Opcodes.ALOAD, l.ctx());
        mv.visitTypeInsn(Opcodes.CHECKCAST, GA2_NOISE_CHUNK);
        mv.visitVarInsn(Opcodes.ILOAD, IX);
        mv.visitVarInsn(Opcodes.ILOAD, IY);
        mv.visitVarInsn(Opcodes.ILOAD, IZ);
        mv.visitVarInsn(Opcodes.ILOAD, IDX);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, GA2_NOISE_CHUNK, "ga2$setCell", "(IIII)V", true);
        mv.visitLabel(skipCtx);
        if (SLOT_STAMPS) {
            // A new block. The shared slots' values from the previous block become stale.
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, CellProgram.INTERNAL, "advance", "(L" + CellProgram.INTERNAL + ";)V", false);
        } else {
            emitter.emitResetBeforePrologue(mv, l, 0, emitter.slotCount(), false);
        }
        emitter.emitPrologue(mv, l, false);
        emitter.emit(mv, l, root);
        mv.visitVarInsn(Opcodes.DSTORE, l.tmp());
        mv.visitVarInsn(Opcodes.ALOAD, VALUES);
        mv.visitVarInsn(Opcodes.ILOAD, IDX);
        mv.visitVarInsn(Opcodes.DLOAD, l.tmp());
        mv.visitInsn(Opcodes.DASTORE);
        mv.visitIincInsn(IDX, 1);
        mv.visitIincInsn(IZ, 1);
        mv.visitJumpInsn(Opcodes.GOTO, lz);
        mv.visitLabel(nz);
        mv.visitIincInsn(IX, 1);
        mv.visitJumpInsn(Opcodes.GOTO, lx);
        mv.visitLabel(nx);
        mv.visitIincInsn(IY, -1);
        mv.visitJumpInsn(Opcodes.GOTO, ly);
        mv.visitLabel(end);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        emitter.emitHelpers();
        int hints = emitter.splineHintCount();
        if ((CELL_ROW_LERP || columnCount > 0 || hints > 0) && emitter.slotCount() != rowBase) {
            throw new DfcCompileException("cell program " + debugName + " allocated slots after planning; the row lerp, column table and spline hint slots would overlap them");
        }
        final int scratchSize = (columnCount > 0 || CELL_ROW_LERP || hints > 0 ? scratchEnd : emitter.slotCount()) + hints;
        emitConstructor(cw, CellProgram.INTERNAL);
        cw.visitEnd();

        MethodHandles.Lookup lookup = HiddenClassLoader.define(cw, "cell-" + debugName);
        try {
            MethodHandle mh = lookup.findConstructor(lookup.lookupClass(), CTOR_TYPE);
            CellBounds bounds = CellBounds.of(root, lerpIndex,
                specs.toArray(new NoiseSpec[0]));
            if (bounds == null) {
                Compiler.LOGGER.info("cell program {}: no whole-cell bounds (first uncovered node: {})", debugName,
                    CellBounds.uncovered(root, lerpIndex));
            }
            if (emitter.columnValueCount() > 0) {
                Compiler.LOGGER.info(
                    "cell program {}: {} x/z-only parts hoisted per column", debugName,
                    emitter.columnValueCount());
            }
            return new CellTemplate(mh, specs.toArray(new NoiseSpec[0]), blended.toArray(new BlendedSpec[0]),
                emitter.splines().toArray(new SplineRt[0]), emitter.stateDescriptors(), scratchSize,
                emitter.externs().toArray(new DensityFunction[0]), marker, emitter.statefulMarkers(), emitter.columnCacheCount(), emitter.columnValueCount(), bounds);
        } catch (Throwable t) {
            throw new DfcCompileException("could not resolve the constructor of generated cell program " + debugName, t);
        }
    }

    /** Pushes the comparison ({@code DCMPL}) of {@code scratch[slot]} with {@code local + 0.5}. */
    private static void emitColumnKeyCompare(MethodVisitor mv, int scratch, int slot, int local) {
        mv.visitVarInsn(Opcodes.ALOAD, scratch);
        Emitter.pushInt(mv, slot);
        mv.visitInsn(Opcodes.DALOAD);
        mv.visitVarInsn(Opcodes.ILOAD, local);
        mv.visitInsn(Opcodes.I2D);
        mv.visitLdcInsn(0.5);
        mv.visitInsn(Opcodes.DADD);
        mv.visitInsn(Opcodes.DCMPL);
    }

    /** {@code scratch[slot] = local + 0.5}. */
    private static void emitColumnKeyStore(MethodVisitor mv, int scratch, int slot, int local) {
        mv.visitVarInsn(Opcodes.ALOAD, scratch);
        Emitter.pushInt(mv, slot);
        mv.visitVarInsn(Opcodes.ILOAD, local);
        mv.visitInsn(Opcodes.I2D);
        mv.visitLdcInsn(0.5);
        mv.visitInsn(Opcodes.DADD);
        mv.visitInsn(Opcodes.DASTORE);
    }

    /** Emits {@code (double) local_a / (double) local_b}. */
    private static void divToDouble(MethodVisitor mv, int a, int b) {
        mv.visitVarInsn(Opcodes.ILOAD, a);
        mv.visitInsn(Opcodes.I2D);
        mv.visitVarInsn(Opcodes.ILOAD, b);
        mv.visitInsn(Opcodes.I2D);
        mv.visitInsn(Opcodes.DDIV);
    }

    private static void emitConstructor(ClassWriter cw, String superInternal) {
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", CTOR_DESC, null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitVarInsn(Opcodes.ALOAD, 2);
        ctor.visitVarInsn(Opcodes.ALOAD, 3);
        ctor.visitVarInsn(Opcodes.ALOAD, 4);
        ctor.visitVarInsn(Opcodes.ALOAD, 5);
        ctor.visitVarInsn(Opcodes.ILOAD, 6);
        ctor.visitVarInsn(Opcodes.ILOAD, 7);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, superInternal, "<init>", CTOR_DESC, false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
    }

    /** Indexes of the interpolated markers reachable from a cell program's root (an over-approximation is harmless). */
    private static void collectLerps(Node n, Map<Node.Marker, Integer> lerpIndex, Set<Integer> out, Map<Node, Boolean> seen) {
        if (seen.put(n, Boolean.TRUE) != null) {
            return;
        }
        if (n instanceof Node.Marker m && m.type() == Node.MarkerType.INTERPOLATED) {
            Integer r = lerpIndex.get(m);
            if (r != null) {
                out.add(r);
            }
        }
        if (n instanceof Node.Spline sp) {
            collectSplineLerps(sp.spline(), lerpIndex, out, seen);
        }
        for (Node c : RefCount.children(n)) {
            collectLerps(c, lerpIndex, out, seen);
        }
    }

    private static void collectSplineLerps(Node.SplineSpec s, Map<Node.Marker, Integer> lerpIndex, Set<Integer> out, Map<Node, Boolean> seen) {
        if (s instanceof Node.SplineMulti m) {
            collectLerps(m.coordinate(), lerpIndex, out, seen);
            for (Node.SplineSpec v : m.values()) {
                collectSplineLerps(v, lerpIndex, out, seen);
            }
        }
    }
}
