package dev.denismasterherobrine.generatoraccelerator2.state;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.features.FeatureStats;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Every block a generation region writes goes to {@code ServerLevel.updatePOIOnBlockStateChange}. That method looks both states up in
 * the POI types' state map ({@code PoiTypes.forState}, two hash lookups). It does nothing when neither state has a POI type,
 * which holds for almost every block worldgen writes. Whether a state has a POI type is fixed while a server runs. The types
 * are registered before the server starts, and NeoForge rebuilds the map only when registries are swapped, between servers.
 * So each state computes the answer once per server start and stores it in a field ({@code BlockStateFlagsMixin}). A change
 * between two states without a POI type skips the call. Off when another mod changes {@code updatePOIOnBlockStateChange},
 * {@code PoiTypes.forState} or the call in {@code WorldGenRegion.setBlock} (hook {@code state.poiSkip}). Compat code can keep
 * such a change: a mod's own POI types come through {@code forState}, and the states a mod's handler of the call reacts to are
 * watched ({@link #watch}), so changes involving them keep the call.
 */
public final class PoiStates {
    private PoiStates() {}

    /** Implemented by every block state. The stamp is the server start of the cached answer times 2, plus 1 for a POI type. */
    public interface Holder {
        int ga2$poiStamp();

        void ga2$setPoiStamp(int stamp);
    }

    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.STATE_POI_SKIP);
    private static final boolean ENABLED = !"false".equals(System.getProperty("generatoraccelerator2.poiSkip"));
    private static volatile int epoch = 1;
    /** Calls left out (both states without a POI type). Per-block counter, kept with {@code -Dgeneratoraccelerator2.features.stats=true}. */
    public static final LongAdder SKIPPED = new LongAdder();

    /** A server is about to start. The answers cached so far may belong to another registry. */
    public static void serverStarting() {
        epoch = (epoch + 1) & 0x3FFFFFFF;
    }

    /** Set when another mod's handler of the call acts on the server thread only (Pastel's). That thread always makes the call. */
    private static volatile boolean keepOnServerThread;

    public static void keepOnServerThread() {
        keepOnServerThread = true;
    }

    /** States another mod's handler of the call reacts to (compat). A change from or to one keeps the call. */
    private static final List<Predicate<BlockState>> WATCHED = new CopyOnWriteArrayList<>();

    /** Registered while mods load, before any state's answer is cached. */
    public static void watch(Predicate<BlockState> states) {
        WATCHED.add(states);
    }

    /** True when {@code level.updatePOIOnBlockStateChange(pos, oldState, newState)} can be left out (it would do nothing). */
    public static boolean skip(ServerLevel level, BlockState oldState, BlockState newState) {
        if (!ENABLED || !HOOK.allowed() || poi(oldState) || poi(newState) || keepOnServerThread && level.getServer().isSameThread()) {
            return false;
        }
        if (FeatureStats.DETAILED) {
            SKIPPED.increment();
        }
        return true;
    }

    private static boolean poi(BlockState state) {
        Holder h = (Holder) state;
        int e = epoch;
        int stamp = h.ga2$poiStamp();
        if (stamp >>> 1 == e) {
            return (stamp & 1) != 0;
        }
        boolean poi = PoiTypes.forState(state).isPresent() || watched(state);
        h.ga2$setPoiStamp(e << 1 | (poi ? 1 : 0));
        return poi;
    }

    private static boolean watched(BlockState state) {
        for (Predicate<BlockState> w : WATCHED) {
            if (w.test(state)) {
                return true;
            }
        }
        return false;
    }
}
