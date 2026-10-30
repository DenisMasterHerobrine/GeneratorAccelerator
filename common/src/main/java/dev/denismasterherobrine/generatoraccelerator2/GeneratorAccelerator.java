package dev.denismasterherobrine.generatoraccelerator2;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import dev.denismasterherobrine.generatoraccelerator2.aquifer.AquiferCommand;
import dev.denismasterherobrine.generatoraccelerator2.biome.BiomeAnswers;
import dev.denismasterherobrine.generatoraccelerator2.biome.BiomeConfig;
import dev.denismasterherobrine.generatoraccelerator2.biome.FlatClimateTree;
import dev.denismasterherobrine.generatoraccelerator2.carver.CarverConfig;
import dev.denismasterherobrine.generatoraccelerator2.carver.CarverPaths;
import dev.denismasterherobrine.generatoraccelerator2.carver.CarverRunner;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadConfig;
import dev.denismasterherobrine.generatoraccelerator2.chunkload.ChunkLoadStats;
import dev.denismasterherobrine.generatoraccelerator2.compat.CompatConfig;
import dev.denismasterherobrine.generatoraccelerator2.compat.biolith.BiolithSearch;
import dev.denismasterherobrine.generatoraccelerator2.compat.chunky.ChunkyHolds;
import dev.denismasterherobrine.generatoraccelerator2.compat.chunky.ChunkyRequests;
import dev.denismasterherobrine.generatoraccelerator2.compat.lootr.LootrEntries;
import dev.denismasterherobrine.generatoraccelerator2.compat.lootr.LootrMixins;
import dev.denismasterherobrine.generatoraccelerator2.features.fast.GeodeFast;
import dev.denismasterherobrine.generatoraccelerator2.gpu.light.GpuLight;
import dev.denismasterherobrine.generatoraccelerator2.logging.RepeatedWarnings;
import dev.denismasterherobrine.generatoraccelerator2.compat.CompatModules;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.Takeover;
import dev.denismasterherobrine.generatoraccelerator2.config.ConfigManager;
import dev.denismasterherobrine.generatoraccelerator2.dfc.Compat;
import dev.denismasterherobrine.generatoraccelerator2.dfc.DfcConfig;
import dev.denismasterherobrine.generatoraccelerator2.dfc.verify.DfcCommand;
import dev.denismasterherobrine.generatoraccelerator2.features.FeaturesConfig;
import dev.denismasterherobrine.generatoraccelerator2.features.StructureSteps;
import dev.denismasterherobrine.generatoraccelerator2.features.region.TagEpoch;
import dev.denismasterherobrine.generatoraccelerator2.gpu.Gpu;
import dev.denismasterherobrine.generatoraccelerator2.gpu.GpuCommand;
import dev.denismasterherobrine.generatoraccelerator2.gpu.GpuConfig;
import dev.denismasterherobrine.generatoraccelerator2.join.JoinConfig;
import dev.denismasterherobrine.generatoraccelerator2.light.FastLight;
import dev.denismasterherobrine.generatoraccelerator2.light.LightConfig;
import dev.denismasterherobrine.generatoraccelerator2.memory.IdleTrims;
import dev.denismasterherobrine.generatoraccelerator2.memory.MemoryPressure;
import dev.denismasterherobrine.generatoraccelerator2.noise.SelfCheck;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.ParallelLight;
import dev.denismasterherobrine.generatoraccelerator2.parallellight.ParallelLightConfig;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.PregenBench;
import dev.denismasterherobrine.generatoraccelerator2.scheduling.SchedulingConfig;
import dev.denismasterherobrine.generatoraccelerator2.state.PoiStates;
import dev.denismasterherobrine.generatoraccelerator2.storage.SavePrep;
import dev.denismasterherobrine.generatoraccelerator2.storage.StorageConfig;
import dev.denismasterherobrine.generatoraccelerator2.structure.StructureLocks;
import dev.denismasterherobrine.generatoraccelerator2.surface.SurfaceBuilder;
import dev.denismasterherobrine.generatoraccelerator2.surface.SurfaceConfig;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The loader-neutral side of the mod. {@link #init} runs from each platform's entry point. The platform forwards its
 * server events to the {@code on*} methods here.
 */
public final class GeneratorAccelerator {
    public static final String MOD_ID = "generatoraccelerator2";
    public static final String MOD_NAME = "Generator Accelerator 2";
    public static final Logger LOGGER = LoggerFactory.getLogger("GeneratorAccelerator2");

    /** The command root and its alias. Every GA2 command lives under both. */
    public static final String COMMAND = "ga2";
    public static final String COMMAND_ALIAS = MOD_ID;

    private static final List<Consumer<MinecraftServer>> TICK_LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile MinecraftServer server;

    private GeneratorAccelerator() {}

    public static void init() {
        ConfigFiles.migrate(Ga2Log.get("Config"));
        MemoryPressure.install();
        // The light switches follow the light config. They are read again whenever a config file changes.
        ConfigManager.onLoad(FastLight::refresh);
        ConfigManager.onReload(FastLight::refresh);
        // Turning the GPU off in the config gives its memory back without a restart.
        ConfigManager.onReload(Gpu::configReloaded);
        ConfigManager.register(DfcConfig.SPEC, ConfigFiles.name("common"));
        ConfigManager.register(FeaturesConfig.SPEC, ConfigFiles.name("features"));
        ConfigManager.register(SchedulingConfig.SPEC, ConfigFiles.name("scheduling"));
        ConfigManager.register(SurfaceConfig.SPEC, ConfigFiles.name("surface"));
        ConfigManager.register(CarverConfig.SPEC, ConfigFiles.name("carvers"));
        ConfigManager.register(BiomeConfig.SPEC, ConfigFiles.name("biomes"));
        ConfigManager.register(CompatConfig.SPEC, ConfigFiles.name("compat"));
        ConfigManager.register(StorageConfig.SPEC, ConfigFiles.name("storage"));
        ConfigManager.register(LightConfig.SPEC, ConfigFiles.name("light"));
        ConfigManager.register(JoinConfig.SPEC, ConfigFiles.name("join"));
        ConfigManager.register(ParallelLightConfig.SPEC, ConfigFiles.name("parallel-light"));
        ConfigManager.register(GpuConfig.SPEC, ConfigFiles.name("gpu"));
        ConfigManager.register(ChunkLoadConfig.SPEC, ConfigFiles.name("chunk-loading"));
        // The parallel light package knows nothing of GA2's compat inspection. It asks through this gate.
        ParallelLight.setCompatGate(() -> HookTargets.allowed(HookTargets.LIGHT_PARALLEL));
        // large light batches propagate their increases on the GPU, under the same gate (the same methods, run elsewhere)
        GpuLight.setCompatGate(() -> HookTargets.allowed(HookTargets.LIGHT_PARALLEL));
        ParallelLight.setIncreaseOffload(GpuLight.INSTANCE);
        StructureLocks.prewarm();
        CompatModules.init();
        if (Boolean.getBoolean("generatoraccelerator2.selfcheck")) {
            SelfCheck.run(Ga2Log.get("SelfCheck"));
        }
    }

    /** The running server, or null between worlds. */
    public static MinecraftServer server() {
        return server;
    }

    /** Runs at the end of every server tick from now on. */
    public static void addServerTickListener(Consumer<MinecraftServer> listener) {
        TICK_LISTENERS.add(listener);
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        GpuCommand.register(dispatcher);
        DfcCommand.register(dispatcher);
        AquiferCommand.register(dispatcher);
        // The alias gets every subcommand the root has. Registering a literal twice merges the children of both.
        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild(COMMAND);
        if (root != null) {
            LiteralArgumentBuilder<CommandSourceStack> alias = Commands.literal(COMMAND_ALIAS).requires(root.getRequirement());
            if (root.getCommand() != null) {
                alias.executes(root.getCommand());
            }
            for (CommandNode<CommandSourceStack> child : root.getChildren()) {
                alias.then(child);
            }
            dispatcher.register(alias);
        }
    }

    /** A level's GPU program builds as soon as the level loads. The level's spawn area is generated after that. */
    public static void onLevelLoad(ServerLevel level) {
        Gpu.prewarm(level);
    }

    /**
     * The server's tags were (re)bound. The loaders call this only for the server's own data load. In singleplayer the client then
     * receives the same tags. Bumping the epoch for that copy would make every program built at level load stale for nothing.
     */
    public static void onTagsUpdated() {
        TagEpoch.bump();
        // a datapack reload: Biolith's requests from data may have changed, and the biomes kept with them
        BiomeAnswers.forget();
        BiolithSearch.forgetRequests();
    }

    public static void onServerStarting(MinecraftServer starting) {
        server = starting;
        PoiStates.serverStarting();
        Takeover.checkConsulted();
        if (Compat.isLoaded(ChunkyRequests.MOD_ID)) {
            ChunkyRequests.serverStarting(starting);
        }
    }

    /** Caches of a world's worldgen objects are dropped with it. A singleplayer client opens many worlds per session. */
    public static void onServerStopped(MinecraftServer stopped) {
        CarverPaths.clear();
        CarverRunner.clearKinds();
        // The device goes too. A singleplayer client back at the title screen then holds no VRAM for it.
        Gpu.release("the server stopped");
        // These would otherwise keep the stopped server's registries or levels until the next world replaces them.
        SavePrep.onServerStopped();
        PregenBench.onServerStopped();
        RepeatedWarnings.serverStopped();
        ChunkLoadStats.serverStopped();
        ChunkyHolds.serverStopped();
        GeodeFast.forgetSeeds();
        SurfaceBuilder.forgetShapes();
        StructureSteps.clear();
        FlatClimateTree.forgetWorlds();
        BiolithSearch.forgetWorlds();
        if (Compat.isLoaded(LootrMixins.MOD_ID)) {
            // Lootr's own list would carry the old world's entries into the next world
            LootrEntries.ENTRIES.clear();
        }
        if (server == stopped) {
            server = null;
        }
    }

    public static void onServerTickPost(MinecraftServer ticking) {
        RepeatedWarnings.tick();
        IdleTrims.tick();
        for (Consumer<MinecraftServer> listener : TICK_LISTENERS) {
            listener.accept(ticking);
        }
    }
}
