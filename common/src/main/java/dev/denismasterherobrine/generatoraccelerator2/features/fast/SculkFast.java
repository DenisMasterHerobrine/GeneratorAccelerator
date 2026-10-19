package dev.denismasterherobrine.generatoraccelerator2.features.fast;

import dev.denismasterherobrine.generatoraccelerator2.features.FeatureStats;
import dev.denismasterherobrine.generatoraccelerator2.features.region.Ga2Region;
import dev.denismasterherobrine.generatoraccelerator2.features.region.RegionWriter;
import dev.denismasterherobrine.generatoraccelerator2.features.region.SectionCursor;
import dev.denismasterherobrine.generatoraccelerator2.state.StateIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.MultifaceSpreader;
import net.minecraft.world.level.block.SculkBehaviour;
import net.minecraft.world.level.block.SculkBlock;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.SculkVeinBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.SculkPatchFeature;
import net.minecraft.world.level.levelgen.feature.configurations.SculkPatchConfiguration;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Transcription of {@code SculkPatchFeature.place} with the world generation {@code SculkSpreader} it runs. Covered: the charge
 * cursors, {@code SculkBlock} and {@code SculkVeinBlock} as sculk behaviours (and the default behaviour of every other block), the
 * vein spreaders ({@code MultifaceSpreader} with {@code SculkVeinBlock}'s spreader configs) and the vein's discharge. Same reads
 * in the same order where they decide anything, same random draws, same writes in the same order. Positions are ints, and reads
 * go through the section cursor. The streams, optionals and spread positions vanilla allocates per step are gone. The region's
 * level events, sounds and entity pushes are skipped. All of them are no-ops in a generation region.
 *
 * <p>Used only for vanilla's feature class, when the only blocks with a sculk behaviour are vanilla's sculk and sculk vein (of
 * their own classes) and no other mod changes the classes transcribed here ({@code SculkClassesInspectMixin}, hook {@code sculk}).
 */
public final class SculkFast {
    private static final Direction[] DIRS = Direction.values();
    private static final BooleanProperty[] FACE = new BooleanProperty[6];
    /** {@code ChargeCursor.NON_CORNER_NEIGHBOURS}, built as vanilla builds it. */
    private static final int[] NX, NY, NZ;
    private static final int NEIGHBOURS;
    private static volatile int applies;
    /** Per state id: its faces (bit per direction ordinal) + 1. 0 means not known yet. */
    private static final StateIndex.Table FACES = new StateIndex.Table(0);

    // spreader kinds
    private static final int VEINS = 0; // SculkVeinBlock.veinSpreader: same position, same plane, wrap around
    private static final int SAME_SPACE = 1; // SculkVeinBlock.sameSpaceSpreader: same position only

    // behaviours
    private static final int DEFAULT = 0, SCULK = 1, VEIN = 2;

    // world generation spreader (SculkSpreader.createWorldGenSpreader)
    private static final int GROWTH_SPAWN_COST = 50, NO_GROWTH_RADIUS = 1, CHARGE_DECAY_RATE = 5, ADDITIONAL_DECAY_RATE = 10;

    static {
        for (Direction d : DIRS) {
            FACE[d.ordinal()] = MultifaceBlock.getFaceProperty(d);
        }
        java.util.List<BlockPos> list = new java.util.ArrayList<>(18);
        BlockPos.betweenClosedStream(new BlockPos(-1, -1, -1), new BlockPos(1, 1, 1))
            .filter(p -> (p.getX() == 0 || p.getY() == 0 || p.getZ() == 0) && !p.equals(BlockPos.ZERO))
            .map(BlockPos::immutable)
            .forEach(list::add);
        NEIGHBOURS = list.size();
        NX = new int[NEIGHBOURS];
        NY = new int[NEIGHBOURS];
        NZ = new int[NEIGHBOURS];
        for (int i = 0; i < NEIGHBOURS; i++) {
            NX[i] = list.get(i).getX();
            NY[i] = list.get(i).getY();
            NZ[i] = list.get(i).getZ();
        }
    }

    /**
     * Whether the transcription stands for vanilla's code: sculk and sculk vein are vanilla's classes and the spread order is
     * vanilla's. Another mod's block with a sculk behaviour (Deeper and Darker's gloomy sculk) is asked about per patch
     * ({@link #othersOutOfReach}).
     */
    public static boolean applies() {
        int a = applies;
        if (a == 0) {
            boolean ok = Blocks.SCULK.getClass() == SculkBlock.class && Blocks.SCULK_VEIN.getClass() == SculkVeinBlock.class
                && java.util.Arrays.equals(MultifaceSpreader.DEFAULT_SPREAD_ORDER, new MultifaceSpreader.SpreadType[] {
                    MultifaceSpreader.SpreadType.SAME_POSITION, MultifaceSpreader.SpreadType.SAME_PLANE, MultifaceSpreader.SpreadType.WRAP_AROUND });
            boolean any = false;
            for (Block block : BuiltInRegistries.BLOCK) {
                if (isOther(block)) {
                    any = true;
                    break;
                }
            }
            othersRegistered = any;
            applies = a = ok ? 1 : 2;
        }
        return a == 1;
    }

    /** Set by {@link #applies}: some block other than vanilla's sculk and vein has a sculk behaviour. */
    private static volatile boolean othersRegistered;

    private static boolean isOther(Block block) {
        return block instanceof SculkBehaviour && block != Blocks.SCULK && block != Blocks.SCULK_VEIN;
    }

    /**
     * True when no block with another mod's sculk behaviour can be where this patch asks for a block's behaviour. Then the
     * transcription's three behaviours are the ones vanilla's code finds. Vanilla asks at the origin, at the patch's cursors and
     * at the cursors' 18 movement neighbours. A world generation cursor that ends up 15 blocks or more from the origin
     * (horizontally) stops. So those positions are within 16 blocks of the origin across. A cursor moves at most one block per
     * update and at most once per spread attempt, and it restarts at the origin each round. So those positions are within
     * {@code spreadAttempts + 1} blocks up or down. Every section of the region in that box is asked through its palette
     * ({@code maybeHas}). {@code maybeHas} may say yes for a state no longer stored, but never no for one that is. A section
     * that may hold such a block leaves the patch to vanilla's code.
     */
    public static boolean othersOutOfReach(FeaturePlaceContext<?> ctx, Ga2Region region) {
        if (!othersRegistered) {
            return true;
        }
        SculkPatchConfiguration config = (SculkPatchConfiguration) ctx.config();
        BlockPos origin = ctx.origin();
        WorldGenLevel level = region.ga2$level();
        int reachY = config.spreadAttempts() + 2;
        int minSy = SectionPos.blockToSectionCoord(Math.max(origin.getY() - reachY, region.ga2$minY()));
        int maxSy = SectionPos.blockToSectionCoord(Math.min(origin.getY() + reachY, region.ga2$maxY() - 1));
        for (int cx = (origin.getX() - 16) >> 4; cx <= (origin.getX() + 16) >> 4; cx++) {
            for (int cz = (origin.getZ() - 16) >> 4; cz <= (origin.getZ() + 16) >> 4; cz++) {
                // a chunk outside the region is one vanilla's reads would fail on too
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }
                ChunkAccess chunk = region.ga2$chunk(cx, cz);
                for (int sy = minSy; sy <= maxSy; sy++) {
                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
                    if (section.getStates().maybeHas(s -> isOther(s.getBlock()))) {
                        FeatureStats.SCULK_OTHERS_NEAR.increment();
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private final Ga2Region region;
    private final WorldGenLevel level;
    private final SectionCursor cursor;
    private final RandomSource random;
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos scratch2 = new BlockPos.MutableBlockPos();
    private final int originX, originY, originZ;
    private final int[] order = new int[18];

    // the charge cursors, at most 32
    private int cursors;
    private final int[] cx = new int[32], cy = new int[32], cz = new int[32];
    private final int[] charge = new int[32], updateDelay = new int[32], decayDelay = new int[32];
    /** The cursor's facings: a bit per direction ordinal, -1 for none (null). */
    private final int[] facings = new int[32];

    private SculkFast(Ga2Region region, RandomSource random, BlockPos origin) {
        this.region = region;
        this.level = region.ga2$level();
        this.cursor = new SectionCursor(region);
        this.random = random;
        this.originX = origin.getX();
        this.originY = origin.getY();
        this.originZ = origin.getZ();
    }

    /** Exact {@code SculkPatchFeature.place}. */
    public static boolean place(FeaturePlaceContext<?> ctx, Ga2Region region) {
        FeatureStats.fast(SculkPatchFeature.class);
        BlockPos origin = ctx.origin();
        SculkFast s = new SculkFast(region, ctx.random(), origin);
        if (!s.canSpreadFrom(origin.getX(), origin.getY(), origin.getZ())) {
            return false;
        }
        SculkPatchConfiguration config = (SculkPatchConfiguration) ctx.config();
        RandomSource random = ctx.random();
        int rounds = config.spreadRounds() + config.growthRounds();
        for (int j = 0; j < rounds; j++) {
            for (int k = 0; k < config.chargeCount(); k++) {
                s.addCursors(config.amountPerCharge());
            }
            boolean spread = j < config.spreadRounds();
            for (int l = 0; l < config.spreadAttempts(); l++) {
                s.updateCursors(spread);
            }
            s.cursors = 0;
        }
        WorldGenLevel level = s.level;
        BlockPos below = origin.below();
        if (random.nextFloat() <= config.catalystChance() && s.cursor.read(below).isCollisionShapeFullBlock(level, below)) {
            RegionWriter.setBlock(region, origin, Blocks.SCULK_CATALYST.defaultBlockState());
        }
        int growths = config.extraRareGrowths().sample(random);
        for (int i = 0; i < growths; i++) {
            BlockPos at = origin.offset(random.nextInt(5) - 2, 0, random.nextInt(5) - 2);
            if (s.cursor.read(at).isAir() && s.cursor.read(at.below()).isFaceSturdy(level, at.below(), Direction.UP)) {
                RegionWriter.setBlock(region, at, Blocks.SCULK_SHRIEKER.defaultBlockState().setValue(SculkShriekerBlock.CAN_SUMMON, Boolean.TRUE));
            }
        }
        return true;
    }

    /** Exact {@code SculkPatchFeature.canSpreadFrom}. */
    private boolean canSpreadFrom(int x, int y, int z) {
        BlockState state = read(x, y, z);
        if (behaviour(state) != DEFAULT) {
            return true;
        }
        if (!state.isAir() && (!state.is(Blocks.WATER) || !state.getFluidState().isSource())) {
            return false;
        }
        for (Direction d : DIRS) {
            int nx = x + d.getStepX(), ny = y + d.getStepY(), nz = z + d.getStepZ();
            if (read(nx, ny, nz).isCollisionShapeFullBlock(level, scratch.set(nx, ny, nz))) {
                return true;
            }
        }
        return false;
    }

    private BlockState read(int x, int y, int z) {
        return cursor.read(x, y, z);
    }

    /**
     * {@code -Dgeneratoraccelerator2.verify.sculk=N}: every N-th lookup of the default behaviour checks that the block has no other
     * mod's sculk behaviour. {@link #othersOutOfReach} keeps such blocks out of every patch this code runs.
     */
    private static final dev.denismasterherobrine.generatoraccelerator2.chunkio.VerifyCounter VERIFY =
        new dev.denismasterherobrine.generatoraccelerator2.chunkio.VerifyCounter("sculk");

    private static int behaviour(BlockState state) {
        Block b = state.getBlock();
        if (b == Blocks.SCULK) {
            return SCULK;
        }
        if (b == Blocks.SCULK_VEIN) {
            return VEIN;
        }
        if (VERIFY.sample()) {
            VERIFY.result(!isOther(b), "sculk behaviour lookup", "no other mod's sculk behaviour", b);
        }
        return DEFAULT;
    }

    /** {@code MultifaceBlock.hasFace} for every direction: a bit per direction ordinal. */
    private static int faces(BlockState state) {
        int id = StateIndex.id(state);
        if (id >= 0) {
            int v = FACES.get(id);
            if (v != 0) {
                return v - 1;
            }
        }
        int mask = 0;
        for (int i = 0; i < 6; i++) {
            if (state.hasProperty(FACE[i]) && state.getValue(FACE[i])) {
                mask |= 1 << i;
            }
        }
        if (id >= 0) {
            FACES.put(id, mask + 1);
        }
        return mask;
    }

    private boolean write(int x, int y, int z, BlockState state) {
        return RegionWriter.setBlock(region, new BlockPos(x, y, z), state);
    }

    // ---- SculkSpreader -------------------------------------------------------------------------------------------------

    private void addCursors(int amount) {
        while (amount > 0) {
            int c = Math.min(amount, 1000);
            if (cursors < 32) {
                int i = cursors++;
                cx[i] = originX;
                cy[i] = originY;
                cz[i] = originZ;
                charge[i] = c;
                decayDelay[i] = 1;
                updateDelay[i] = 0;
                facings[i] = -1;
            }
            amount -= c;
        }
    }

    /**
     * Exact {@code SculkSpreader.updateCursors} of a world generation spreader. Every cursor is updated in order. The cursors
     * left with charge stay, in order. World generation never merges cursors. The level events it sends are no-ops in a region.
     */
    private void updateCursors(boolean spread) {
        int n = cursors;
        int kept = 0;
        for (int i = 0; i < n; i++) {
            update(i, spread);
            if (charge[i] > 0) {
                if (kept != i) {
                    cx[kept] = cx[i];
                    cy[kept] = cy[i];
                    cz[kept] = cz[i];
                    charge[kept] = charge[i];
                    updateDelay[kept] = updateDelay[i];
                    decayDelay[kept] = decayDelay[i];
                    facings[kept] = facings[i];
                }
                kept++;
            }
        }
        cursors = kept;
    }

    /** Exact {@code ChargeCursor.update} (world generation: always updates while charged). */
    private void update(int i, boolean spread) {
        if (charge[i] <= 0) {
            return;
        }
        if (updateDelay[i] > 0) {
            updateDelay[i]--;
            return;
        }
        int x = cx[i], y = cy[i], z = cz[i];
        BlockState state = read(x, y, z);
        int behaviour = behaviour(state);
        if (spread && attemptSpreadVein(behaviour, x, y, z, state, facings[i])) {
            if (behaviour != SCULK) {
                // canChangeBlockStateOnSpread: every behaviour but sculk's
                state = read(x, y, z);
                behaviour = behaviour(state);
            }
        }
        charge[i] = attemptUseCharge(behaviour, i, spread);
        if (charge[i] <= 0) {
            onDischarged(behaviour, state, x, y, z);
            return;
        }
        if (validMovementPos(x, y, z)) {
            onDischarged(behaviour, state, x, y, z);
            x = moveX;
            y = moveY;
            z = moveZ;
            cx[i] = x;
            cy[i] = y;
            cz[i] = z;
            double dx = (double) x - (double) originX, dz = (double) z - (double) originZ;
            if (!(dx * dx + dz * dz < Mth.square(15.0))) {
                charge[i] = 0;
                return;
            }
            state = read(x, y, z);
        }
        if (behaviour(state) != DEFAULT) {
            // MultifaceBlock.availableFaces. Sculk is no multiface block (an empty set). A vein gives its faces.
            facings[i] = state.getBlock() instanceof MultifaceBlock ? faces(state) : 0;
        }
        decayDelay[i] = behaviour == DEFAULT ? Math.max(decayDelay[i] - 1, 0) : 1;
        updateDelay[i] = 1;
    }

    private int moveX, moveY, moveZ;

    /** Exact {@code ChargeCursor.getValidMovementPos}: true with the position in {@link #moveX} etc., false for none. */
    private boolean validMovementPos(int x, int y, int z) {
        int[] o = order;
        for (int k = 0; k < NEIGHBOURS; k++) {
            o[k] = k;
        }
        shuffle(o, NEIGHBOURS);
        int mx = x, my = y, mz = z;
        for (int k = 0; k < NEIGHBOURS; k++) {
            int n = o[k];
            int px = x + NX[n], py = y + NY[n], pz = z + NZ[n];
            BlockState state = read(px, py, pz);
            if (behaviour(state) != DEFAULT && movementUnobstructed(x, y, z, NX[n], NY[n], NZ[n])) {
                mx = px;
                my = py;
                mz = pz;
                if (hasSubstrateAccess(state, px, py, pz)) {
                    break;
                }
            }
        }
        if (mx == x && my == y && mz == z) {
            return false;
        }
        moveX = mx;
        moveY = my;
        moveZ = mz;
        return true;
    }

    /** Exact {@code ChargeCursor.isMovementUnobstructed} for an offset of one of the non-corner neighbours. */
    private boolean movementUnobstructed(int x, int y, int z, int dx, int dy, int dz) {
        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 1) {
            return true;
        }
        Direction ax = dx < 0 ? Direction.WEST : Direction.EAST;
        Direction ay = dy < 0 ? Direction.DOWN : Direction.UP;
        Direction az = dz < 0 ? Direction.NORTH : Direction.SOUTH;
        if (dx == 0) {
            return unobstructed(x, y, z, ay) || unobstructed(x, y, z, az);
        }
        return dy == 0 ? unobstructed(x, y, z, ax) || unobstructed(x, y, z, az) : unobstructed(x, y, z, ax) || unobstructed(x, y, z, ay);
    }

    private boolean unobstructed(int x, int y, int z, Direction d) {
        int bx = x + d.getStepX(), by = y + d.getStepY(), bz = z + d.getStepZ();
        return !read(bx, by, bz).isFaceSturdy(level, scratch.set(bx, by, bz), d.getOpposite());
    }

    /** Exact {@code SculkVeinBlock.hasSubstrateAccess}. */
    private boolean hasSubstrateAccess(BlockState state, int x, int y, int z) {
        if (!state.is(Blocks.SCULK_VEIN)) {
            return false;
        }
        int f = faces(state);
        for (int i = 0; i < 6; i++) {
            if ((f & 1 << i) != 0) {
                Direction d = DIRS[i];
                if (read(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ()).is(BlockTags.SCULK_REPLACEABLE)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@code Util.shuffle} on the first {@code n} entries. */
    private void shuffle(int[] a, int n) {
        for (int j = n; j > 1; j--) {
            int k = random.nextInt(j);
            int t = a[j - 1];
            a[j - 1] = a[k];
            a[k] = t;
        }
    }

    // ---- behaviours ------------------------------------------------------------------------------------------------------

    /** {@code SculkBehaviour.attemptSpreadVein} of the behaviour (world generation: post-processing marks on). */
    private boolean attemptSpreadVein(int behaviour, int x, int y, int z, BlockState state, int facing) {
        if (behaviour == DEFAULT) {
            if (facing == -1) {
                // reads the state again. Nothing changed it since.
                return spreadAll(SAME_SPACE, state, x, y, z) > 0L;
            }
            if (facing != 0) {
                return !state.isAir() && !state.getFluidState().is(Fluids.WATER) ? false : regrow(x, y, z, state, facing);
            }
        }
        return spreadAll(VEINS, state, x, y, z) > 0L;
    }

    /** {@code SculkBehaviour.attemptUseCharge} of the behaviour. */
    private int attemptUseCharge(int behaviour, int i, boolean spread) {
        int c = charge[i];
        if (behaviour == DEFAULT) {
            return decayDelay[i] > 0 ? c : 0;
        }
        int x = cx[i], y = cy[i], z = cz[i];
        if (behaviour == VEIN) {
            if (spread && attemptPlaceSculk(x, y, z)) {
                return c - 1;
            }
            return random.nextInt(CHARGE_DECAY_RATE) == 0 ? Mth.floor((float) c * 0.5F) : c;
        }
        // SculkBlock.attemptUseCharge
        if (c != 0 && random.nextInt(CHARGE_DECAY_RATE) == 0) {
            double ddx = (double) x - (double) originX, ddy = (double) y - (double) originY, ddz = (double) z - (double) originZ;
            double distSqr = ddx * ddx + ddy * ddy + ddz * ddz;
            boolean near = distSqr < Mth.square((double) NO_GROWTH_RADIUS);
            if (!near && canPlaceGrowth(x, y, z)) {
                if (random.nextInt(GROWTH_SPAWN_COST) < c) {
                    BlockState growth = random.nextInt(11) == 0
                        ? Blocks.SCULK_SHRIEKER.defaultBlockState().setValue(SculkShriekerBlock.CAN_SUMMON, Boolean.TRUE)
                        : Blocks.SCULK_SENSOR.defaultBlockState();
                    if (growth.hasProperty(BlockStateProperties.WATERLOGGED) && !cursor.readFluid(x, y + 1, z).isEmpty()) {
                        growth = growth.setValue(BlockStateProperties.WATERLOGGED, Boolean.TRUE);
                    }
                    write(x, y + 1, z, growth);
                }
                return Math.max(0, c - GROWTH_SPAWN_COST);
            }
            if (random.nextInt(ADDITIONAL_DECAY_RATE) != 0) {
                return c;
            }
            if (near) {
                return c - 1;
            }
            float f = Mth.square((float) Math.sqrt(distSqr) - (float) NO_GROWTH_RADIUS);
            int j = Mth.square(24 - NO_GROWTH_RADIUS);
            float f1 = Math.min(1.0F, f / (float) j);
            return c - Math.max(1, (int) ((float) c * f1 * 0.5F));
        }
        return c;
    }

    /** Exact {@code SculkBlock.canPlaceGrowth}. */
    private boolean canPlaceGrowth(int x, int y, int z) {
        BlockState above = read(x, y + 1, z);
        if (!(above.isAir() || above.is(Blocks.WATER) && above.getFluidState().is(Fluids.WATER))) {
            return false;
        }
        int found = 0;
        for (int bz = z - 4; bz <= z + 4; bz++) {
            for (int by = y; by <= y + 2; by++) {
                for (int bx = x - 4; bx <= x + 4; bx++) {
                    BlockState s = read(bx, by, bz);
                    if (s.is(Blocks.SCULK_SENSOR) || s.is(Blocks.SCULK_SHRIEKER)) {
                        found++;
                    }
                    if (found > 2) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** {@code SculkBehaviour.onDischarged} of the behaviour: only the vein's does anything. */
    private void onDischarged(int behaviour, BlockState state, int x, int y, int z) {
        if (behaviour == VEIN) {
            veinDischarged(state, x, y, z);
        }
    }

    /** Exact {@code SculkVeinBlock.onDischarged}. */
    private void veinDischarged(BlockState state, int x, int y, int z) {
        if (!state.is(Blocks.SCULK_VEIN)) {
            return;
        }
        for (int i = 0; i < 6; i++) {
            BooleanProperty p = FACE[i];
            Direction d = DIRS[i];
            if (state.getValue(p) && read(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ()).is(Blocks.SCULK)) {
                state = state.setValue(p, Boolean.FALSE);
            }
        }
        if (faces(state) == 0) {
            FluidState fluid = cursor.readFluid(x, y, z);
            state = (fluid.isEmpty() ? Blocks.AIR : Blocks.WATER).defaultBlockState();
        }
        write(x, y, z, state);
    }

    /** Exact {@code SculkVeinBlock.attemptPlaceSculk} (world generation spreader: replaceable by its tag, marks on). */
    private boolean attemptPlaceSculk(int x, int y, int z) {
        BlockState state = read(x, y, z);
        int[] o = order;
        for (int k = 0; k < 6; k++) {
            o[k] = k;
        }
        shuffle(o, 6);
        int f = faces(state);
        for (int k = 0; k < 6; k++) {
            int di = o[k];
            if ((f & 1 << di) != 0) {
                Direction d = DIRS[di];
                int bx = x + d.getStepX(), by = y + d.getStepY(), bz = z + d.getStepZ();
                BlockState target = read(bx, by, bz);
                if (target.is(BlockTags.SCULK_REPLACEABLE_WORLD_GEN)) {
                    BlockState sculk = Blocks.SCULK.defaultBlockState();
                    write(bx, by, bz, sculk);
                    // Block.pushEntitiesUp finds no entities in a generation region. The sound is a no-op there.
                    spreadAll(VEINS, sculk, bx, by, bz);
                    Direction back = d.getOpposite();
                    for (Direction d2 : DIRS) {
                        if (d2 != back) {
                            int nx = bx + d2.getStepX(), ny = by + d2.getStepY(), nz = bz + d2.getStepZ();
                            BlockState n = read(nx, ny, nz);
                            if (n.is(Blocks.SCULK_VEIN)) {
                                veinDischarged(n, nx, ny, nz);
                            }
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /** Exact {@code SculkVeinBlock.regrow}. */
    private boolean regrow(int x, int y, int z, BlockState state, int facing) {
        boolean any = false;
        BlockState vein = Blocks.SCULK_VEIN.defaultBlockState();
        for (int i = 0; i < 6; i++) {
            if ((facing & 1 << i) != 0) {
                Direction d = DIRS[i];
                int bx = x + d.getStepX(), by = y + d.getStepY(), bz = z + d.getStepZ();
                if (MultifaceBlock.canAttachTo(level, d, scratch.set(bx, by, bz), read(bx, by, bz))) {
                    vein = vein.setValue(FACE[i], Boolean.TRUE);
                    any = true;
                }
            }
        }
        if (!any) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            vein = vein.setValue(BlockStateProperties.WATERLOGGED, Boolean.TRUE);
        }
        write(x, y, z, vein);
        return true;
    }

    // ---- MultifaceSpreader with SculkVeinBlock's spreader configs ---------------------------------------------------------

    /** Exact {@code MultifaceSpreader.spreadAll} (marks for post-processing on, as world generation asks). */
    private long spreadAll(int kind, BlockState state, int x, int y, int z) {
        boolean other = !state.is(Blocks.SCULK_VEIN); // SculkVeinSpreaderConfig.isOtherBlockValidAsSource
        int f = faces(state);
        long total = 0L;
        for (int fi = 0; fi < 6; fi++) {
            if (other || (f & 1 << fi) != 0) {
                long count = 0L;
                for (int di = 0; di < 6; di++) {
                    if (spreadFromFaceTowardDirection(kind, other, f, x, y, z, fi, di)) {
                        count++;
                    }
                }
                total += count;
            }
        }
        return total;
    }

    /** Exact {@code MultifaceSpreader.spreadFromFaceTowardDirection}: whether a spread position was found and placed. */
    private boolean spreadFromFaceTowardDirection(int kind, boolean other, int f, int x, int y, int z, int fi, int di) {
        Direction face = DIRS[fi];
        Direction dir = DIRS[di];
        if (dir.getAxis() == face.getAxis()) {
            return false;
        }
        if (!(other || (f & 1 << fi) != 0 && (f & 1 << di) == 0)) {
            return false;
        }
        int types = kind == SAME_SPACE ? 1 : 3;
        for (int t = 0; t < types; t++) {
            int px, py, pz;
            Direction pface;
            if (t == 0) {
                // SAME_POSITION
                px = x;
                py = y;
                pz = z;
                pface = dir;
            } else if (t == 1) {
                // SAME_PLANE
                px = x + dir.getStepX();
                py = y + dir.getStepY();
                pz = z + dir.getStepZ();
                pface = face;
            } else {
                // WRAP_AROUND
                px = x + dir.getStepX() + face.getStepX();
                py = y + dir.getStepY() + face.getStepY();
                pz = z + dir.getStepZ() + face.getStepZ();
                pface = dir.getOpposite();
            }
            BlockState at = read(px, py, pz);
            if (canSpreadInto(x, y, z, px, py, pz, pface, at)) {
                // spreadToFace reads the position again (unchanged) and places. The first position found is the only one tried.
                return placeBlock(px, py, pz, pface, at);
            }
        }
        return false;
    }

    /** Exact {@code DefaultSpreaderConfig.canSpreadInto} with {@code SculkVeinSpreaderConfig.stateCanBeReplaced}. */
    private boolean canSpreadInto(int x, int y, int z, int px, int py, int pz, Direction face, BlockState at) {
        int ax = px + face.getStepX(), ay = py + face.getStepY(), az = pz + face.getStepZ();
        BlockState attached = read(ax, ay, az);
        if (attached.is(Blocks.SCULK) || attached.is(Blocks.SCULK_CATALYST) || attached.is(Blocks.MOVING_PISTON)) {
            return false;
        }
        if (Math.abs(px - x) + Math.abs(py - y) + Math.abs(pz - z) == 2) {
            Direction back = face.getOpposite();
            int bx = x + back.getStepX(), by = y + back.getStepY(), bz = z + back.getStepZ();
            if (read(bx, by, bz).isFaceSturdy(level, scratch.set(bx, by, bz), face)) {
                return false;
            }
        }
        FluidState fluid = at.getFluidState();
        if (!fluid.isEmpty() && !fluid.is(Fluids.WATER)) {
            return false;
        }
        if (at.is(BlockTags.FIRE)) {
            return false;
        }
        if (!(at.canBeReplaced() || at.isAir() || at.is(Blocks.SCULK_VEIN) || at.is(Blocks.WATER) && at.getFluidState().isSource())) {
            return false;
        }
        // MultifaceBlock.isValidStateForPlacement for the vein (every face supported), reading the attached block again
        return (!at.is(Blocks.SCULK_VEIN) || (faces(at) & 1 << face.ordinal()) == 0)
            && MultifaceBlock.canAttachTo(level, face, scratch2.set(ax, ay, az), attached);
    }

    /**
     * Exact {@code SpreadConfig.placeBlock} with {@code MultifaceBlock.getStateForPlacement}. The placement test of
     * {@code getStateForPlacement} is the one {@link #canSpreadInto} just passed on the same blocks, so the state follows directly.
     */
    private boolean placeBlock(int px, int py, int pz, Direction face, BlockState at) {
        BlockState base;
        if (at.is(Blocks.SCULK_VEIN)) {
            base = at;
        } else if (at.getFluidState().isSourceOfType(Fluids.WATER)) {
            base = Blocks.SCULK_VEIN.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, Boolean.TRUE);
        } else {
            base = Blocks.SCULK_VEIN.defaultBlockState();
        }
        BlockState placed = base.setValue(FACE[face.ordinal()], Boolean.TRUE);
        cursor.chunk(px >> 4, pz >> 4).markPosForPostprocessing(scratch.set(px, py, pz));
        return write(px, py, pz, placed);
    }
}
