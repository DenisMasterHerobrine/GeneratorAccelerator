package dev.denismasterherobrine.generatoraccelerator2.mixin.beardifier;

import dev.denismasterherobrine.generatoraccelerator2.beardifier.BeardBounds;
import dev.denismasterherobrine.generatoraccelerator2.beardifier.BeardifierFastPath;
import dev.denismasterherobrine.generatoraccelerator2.beardifier.BeardExtras;
import dev.denismasterherobrine.generatoraccelerator2.beardifier.EnhancedBeard;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.function.Function;

/**
 * Exact piece index for {@code Beardifier.compute}.
 *
 * <p>Vanilla adds up every rigid piece and jigsaw junction near the chunk for every block. Pieces
 * and junctions are copied once into flat arrays in vanilla order. Each 4 x 8 x 4 block bucket
 * (one default noise cell) lists the pieces and junctions whose {@link BeardBounds} range reaches
 * the bucket. A block sums only that list, in the same order and with the same formulas. Everything
 * left out contributes exactly {@code +0.0}, so the sum is bit-identical.
 */
@Mixin(value = Beardifier.class, priority = 5000)
public abstract class BeardifierMixin {
    @Shadow @Final protected ObjectListIterator<Beardifier.Rigid> pieceIterator;
    @Shadow @Final protected ObjectListIterator<JigsawJunction> junctionIterator;

    @Shadow
    protected static double getBuryContribution(double x, double y, double z) {
        throw new AssertionError();
    }

    @Shadow
    protected static double getBeardContribution(int x, int y, int z, int yToGround) {
        throw new AssertionError();
    }

    @Unique private static final int GA2_OFF = 0;
    @Unique private static final int GA2_UNBUILT = 1;
    @Unique private static final int GA2_ACTIVE = 2;
    /** Other mods add steps after vanilla's sum (BeardExtras). Which steps this beardifier needs is decided on first use. */
    @Unique private static final int GA2_EXTRAS_PENDING = 3;
    /** Per piece: minX, maxX, minY, maxY, minZ, maxZ, minY + groundLevelDelta, adjustment code. */
    @Unique private static final int GA2_PIECE_STRIDE = 8;

    @Unique private int ga2$state;
    /** The steps other mods add after vanilla's sum, when this beardifier needs them. Null means the sum is the method's value. */
    @Unique private BeardExtras.Plan ga2$plan;
    @Unique private int ga2$pieceCount;
    @Unique private int[] ga2$pieces;
    @Unique private int[] ga2$pieceRanges;
    @Unique private int ga2$junctionCount;
    @Unique private int[] ga2$junctions;
    @Unique private int[] ga2$junctionRanges;
    @Unique private boolean ga2$bucketValid;
    @Unique private boolean ga2$bucketVanilla;
    @Unique private int ga2$bucketX;
    @Unique private int ga2$bucketY;
    @Unique private int ga2$bucketZ;
    @Unique private int[] ga2$activePieces;
    @Unique private int ga2$activePieceCount;
    @Unique private int[] ga2$activeJunctions;
    @Unique private int ga2$activeJunctionCount;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ga2$init(ObjectListIterator<Beardifier.Rigid> pieces, ObjectListIterator<JigsawJunction> junctions, CallbackInfo ci) {
        this.ga2$state = BeardifierFastPath.enabledFor(this) ? GA2_UNBUILT : BeardifierFastPath.enabledWithExtras(this) ? GA2_EXTRAS_PENDING : GA2_OFF;
    }

    @Inject(method = "compute", at = @At("HEAD"), cancellable = true)
    private void ga2$compute(DensityFunction.FunctionContext context, CallbackInfoReturnable<Double> cir) {
        if (this.ga2$state != GA2_OFF && !BeardifierFastPath.runningVanilla()) {
            double value = this.ga2$fast(context);
            // NaN never comes out of vanilla's sum, so it marks "run the original method".
            if (value == value) {
                if (BeardifierFastPath.sampleVerify()) {
                    double expected = BeardifierFastPath.vanilla(() -> BeardExtras.body((Beardifier) (Object) this, context));
                    BeardifierFastPath.verified(expected, value, context.blockX() + "," + context.blockY() + "," + context.blockZ());
                }
                cir.setReturnValue(value);
            }
        }
    }

    @Unique
    private double ga2$fast(DensityFunction.FunctionContext context) {
        if (this.ga2$state == GA2_EXTRAS_PENDING) {
            // The steps after vanilla's sum, as the mods have filled their lists for this chunk. The index applies these steps to
            // its own sum, as the method does after its loops. The cancelled method no longer reaches that code.
            BeardExtras.Plan plan = BeardExtras.plan((Beardifier) (Object) this);
            if (plan == null) {
                this.ga2$state = GA2_OFF;
                return Double.NaN;
            }
            this.ga2$plan = plan.vanilla ? null : plan;
            this.ga2$state = GA2_UNBUILT;
        }
        if (this.ga2$state == GA2_UNBUILT) {
            this.ga2$build();
            if (this.ga2$state != GA2_ACTIVE) {
                return Double.NaN;
            }
        }
        int x = context.blockX();
        int y = context.blockY();
        int z = context.blockZ();
        int bx = x >> 2;
        int by = y >> 3;
        int bz = z >> 2;
        if (!this.ga2$bucketValid || bx != this.ga2$bucketX || by != this.ga2$bucketY || bz != this.ga2$bucketZ) {
            this.ga2$selectBucket(bx, by, bz);
        }
        if (this.ga2$bucketVanilla) {
            return Double.NaN;
        }
        double sum = 0.0;
        int[] p = this.ga2$pieces;
        int[] active = this.ga2$activePieces;
        for (int n = 0, count = this.ga2$activePieceCount; n < count; n++) {
            int b = active[n] * GA2_PIECE_STRIDE;
            int minX = p[b];
            int maxX = p[b + 1];
            int minY = p[b + 2];
            int maxY = p[b + 3];
            int minZ = p[b + 4];
            int maxZ = p[b + 5];
            int k1 = p[b + 6];
            int i1 = Math.max(0, Math.max(minX - x, x - maxX));
            int j1 = Math.max(0, Math.max(minZ - z, z - maxZ));
            int l1 = y - k1;
            switch (p[b + 7]) {
                case BeardBounds.BURY -> sum += getBuryContribution((double) i1, (double) l1 / 2.0, (double) j1);
                case BeardBounds.BEARD_THIN -> sum += getBeardContribution(i1, l1, j1, l1) * 0.8;
                case BeardBounds.BEARD_BOX -> {
                    int i2 = Math.max(0, Math.max(k1 - y, y - maxY));
                    sum += getBeardContribution(i1, i2, j1, l1) * 0.8;
                }
                case BeardBounds.ENCAPSULATE -> {
                    int i2 = Math.max(0, Math.max(minY - y, y - maxY));
                    sum += getBuryContribution((double) i1 / 2.0, (double) i2 / 2.0, (double) j1 / 2.0) * 0.8;
                }
                default -> throw new IllegalStateException("adjustment code " + p[b + 7]);
            }
        }
        int[] j = this.ga2$junctions;
        int[] activeJ = this.ga2$activeJunctions;
        for (int n = 0, count = this.ga2$activeJunctionCount; n < count; n++) {
            int b = activeJ[n] * 3;
            int dy = y - j[b + 1];
            sum += getBeardContribution(x - j[b], dy, z - j[b + 2], dy) * 0.4;
        }
        Function<ChunkPos, EnhancedBeard.Chunk> test =
            EnhancedBeard.testChunks;
        if (test != null) {
            sum += EnhancedBeard.density(test.apply(new ChunkPos(x >> 4, z >> 4)), x, y, z, null);
        }
        BeardExtras.Plan plan = this.ga2$plan;
        return plan != null ? plan.apply((Beardifier) (Object) this, context, sum) : sum;
    }

    /**
     * Copies the pieces and junctions in vanilla order. Vanilla's own {@code compute} walks both
     * iterators to the end and rewinds them. This method does the same and leaves both iterators where it found them.
     */
    @Unique
    private void ga2$build() {
        this.ga2$state = GA2_OFF;
        ObjectListIterator<Beardifier.Rigid> pieces = this.pieceIterator;
        ObjectListIterator<JigsawJunction> junctions = this.junctionIterator;
        // A first compute() starting mid-list would see fewer elements than every later call. Keep vanilla for that case.
        if (pieces.nextIndex() != 0 || junctions.nextIndex() != 0) {
            return;
        }
        int pieceTotal = 0;
        while (pieces.hasNext()) {
            pieces.next();
            pieceTotal++;
        }
        pieces.back(Integer.MAX_VALUE);
        int junctionTotal = 0;
        while (junctions.hasNext()) {
            junctions.next();
            junctionTotal++;
        }
        junctions.back(Integer.MAX_VALUE);

        int[] data = new int[pieceTotal * GA2_PIECE_STRIDE];
        int[] ranges = new int[pieceTotal * 6];
        int count = 0;
        boolean ok = true;
        while (pieces.hasNext()) {
            Beardifier.Rigid rigid = pieces.next();
            int code = BeardBounds.code(rigid.terrainAdjustment());
            if (code == BeardBounds.NONE) {
                continue;
            }
            BoundingBox box = rigid.box();
            int k1 = box.minY() + rigid.groundLevelDelta();
            if (code == BeardBounds.UNKNOWN || !BeardBounds.sane(box.minX()) || !BeardBounds.sane(box.maxX()) || !BeardBounds.sane(box.minY())
                || !BeardBounds.sane(box.maxY()) || !BeardBounds.sane(box.minZ()) || !BeardBounds.sane(box.maxZ()) || !BeardBounds.sane(k1)) {
                ok = false;
                continue;
            }
            int b = count * GA2_PIECE_STRIDE;
            data[b] = box.minX();
            data[b + 1] = box.maxX();
            data[b + 2] = box.minY();
            data[b + 3] = box.maxY();
            data[b + 4] = box.minZ();
            data[b + 5] = box.maxZ();
            data[b + 6] = k1;
            data[b + 7] = code;
            BeardBounds.piece(box, code, rigid.groundLevelDelta(), ranges, count * 6);
            count++;
        }
        pieces.back(Integer.MAX_VALUE);

        int[] jData = new int[junctionTotal * 3];
        int[] jRanges = new int[junctionTotal * 6];
        int jCount = 0;
        while (junctions.hasNext()) {
            JigsawJunction junction = junctions.next();
            if (!BeardBounds.sane(junction.getSourceX()) || !BeardBounds.sane(junction.getSourceGroundY()) || !BeardBounds.sane(junction.getSourceZ())) {
                ok = false;
                continue;
            }
            jData[jCount * 3] = junction.getSourceX();
            jData[jCount * 3 + 1] = junction.getSourceGroundY();
            jData[jCount * 3 + 2] = junction.getSourceZ();
            BeardBounds.junction(junction, jRanges, jCount * 6);
            jCount++;
        }
        junctions.back(Integer.MAX_VALUE);
        if (!ok) {
            return;
        }
        this.ga2$pieces = data;
        this.ga2$pieceRanges = ranges;
        this.ga2$pieceCount = count;
        this.ga2$junctions = jData;
        this.ga2$junctionRanges = jRanges;
        this.ga2$junctionCount = jCount;
        this.ga2$activePieces = new int[count];
        this.ga2$activeJunctions = new int[jCount];
        this.ga2$bucketValid = false;
        this.ga2$state = GA2_ACTIVE;
    }

    @Unique
    private void ga2$selectBucket(int bx, int by, int bz) {
        this.ga2$bucketX = bx;
        this.ga2$bucketY = by;
        this.ga2$bucketZ = bz;
        this.ga2$bucketValid = true;
        int x0 = bx << 2;
        int y0 = by << 3;
        int z0 = bz << 2;
        int x1 = x0 + 3;
        int y1 = y0 + 7;
        int z1 = z0 + 3;
        // Far out of range, vanilla's int differences could overflow. Leave such blocks to vanilla.
        this.ga2$bucketVanilla = !BeardBounds.sane(x0) || !BeardBounds.sane(x1) || !BeardBounds.sane(y0) || !BeardBounds.sane(y1)
            || !BeardBounds.sane(z0) || !BeardBounds.sane(z1);
        int[] r = this.ga2$pieceRanges;
        int n = 0;
        for (int i = 0, count = this.ga2$pieceCount; i < count; i++) {
            int b = i * 6;
            if (x1 >= r[b] && x0 <= r[b + 1] && y1 >= r[b + 2] && y0 <= r[b + 3] && z1 >= r[b + 4] && z0 <= r[b + 5]) {
                this.ga2$activePieces[n++] = i;
            }
        }
        this.ga2$activePieceCount = n;
        r = this.ga2$junctionRanges;
        n = 0;
        for (int i = 0, count = this.ga2$junctionCount; i < count; i++) {
            int b = i * 6;
            if (x1 >= r[b] && x0 <= r[b + 1] && y1 >= r[b + 2] && y0 <= r[b + 3] && z1 >= r[b + 4] && z0 <= r[b + 5]) {
                this.ga2$activeJunctions[n++] = i;
            }
        }
        this.ga2$activeJunctionCount = n;
    }
}
