package dev.denismasterherobrine.generatoraccelerator2.gpu;

import dev.denismasterherobrine.generatoraccelerator2.features.HarnessWorld;
import dev.denismasterherobrine.generatoraccelerator2.testutil.TerrainHarness;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/**
 * Worlds on a datapack's worldgen (e.g. Terralith's data folder, extracted from its jar). The game's own registry loader loads
 * vanilla's data and the datapack's data together. The overworld's noise settings and biome parameter list are taken from the
 * result. {@code -Dga2.gpu.datapack=<folder holding data/>} switches the GPU tests to the datapack.
 */
final class GpuDatapack {
    private GpuDatapack() {}

    /** The vanilla overworld, or the datapack's when {@code ga2.gpu.datapack} names one. */
    static GpuProbe.Setup setup(long seed) throws Exception {
        String dir = System.getProperty("ga2.gpu.datapack");
        if (dir == null || dir.isBlank()) {
            return new GpuProbe.Setup(seed);
        }
        return new GpuProbe.Setup(world(Path.of(dir), seed));
    }

    static TerrainHarness.World world(Path dataRoot, long seed) throws Exception {
        URL u = NoiseGeneratorSettings.class.getResource("/data/minecraft/worldgen/noise_settings/overworld.json");
        if (u == null) {
            throw new IllegalStateException("vanilla worldgen data not on the classpath");
        }
        // .../data/minecraft/worldgen/noise_settings/overworld.json -> the pack root above data/
        java.net.URI uri = u.toURI();
        if (uri.getScheme().equals("jar")) {
            // the game jar's file system, opened once
            try {
                java.nio.file.FileSystems.getFileSystem(uri);
            } catch (java.nio.file.FileSystemNotFoundException e) {
                java.nio.file.FileSystems.newFileSystem(uri, java.util.Map.of());
            }
        }
        Path vanillaRoot = Path.of(uri).getParent().getParent().getParent().getParent().getParent();
        PackResources vanilla = new PathPackResources(new PackLocationInfo("vanilla", Component.literal("vanilla"), PackSource.BUILT_IN, Optional.empty()), vanillaRoot);
        PackResources pack = new PathPackResources(new PackLocationInfo("datapack", Component.literal("datapack"), PackSource.WORLD, Optional.empty()), dataRoot);
        RegistryAccess.Frozen loaded;
        net.minecraft.world.level.biome.BiomeSource dimensionSource = null;
        try (MultiPackResourceManager rm = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(vanilla, pack))) {
            RegistryAccess.Frozen builtIn = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            loaded = RegistryDataLoader.load(rm, builtIn, RegistryDataLoader.WORLDGEN_REGISTRIES);
            // the overworld's level stem, as the game loads it after the worldgen registries (Terralith places its biomes there
            // when Lithostitched is absent)
            RegistryAccess.Frozen dims = RegistryDataLoader.load(rm, new RegistryAccess.ImmutableRegistryAccess(
                java.util.stream.Stream.concat(builtIn.registries(), loaded.registries()).map(RegistryAccess.RegistryEntry::value).toList()).freeze(),
                RegistryDataLoader.DIMENSION_REGISTRIES);
            var stem = dims.registryOrThrow(Registries.LEVEL_STEM).get(net.minecraft.world.level.dimension.LevelStem.OVERWORLD);
            if (stem != null) {
                dimensionSource = stem.generator().getBiomeSource();
            }
        }
        Registry<Biome> biomes = loaded.registryOrThrow(Registries.BIOME);
        Holder<MultiNoiseBiomeSourceParameterList> params = loaded.registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
            .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        net.minecraft.world.level.biome.BiomeSource source = dimensionSource instanceof MultiNoiseBiomeSource ? dimensionSource
            : MultiNoiseBiomeSource.createFromPreset(params);
        Holder<NoiseGeneratorSettings> settings = loaded.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD);
        DimensionType dim = loaded.registryOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD);
        HarnessWorld.Registries2 registries = new HarnessWorld.Registries2(loaded, biomes, loaded);
        GpuProbe.log("datapack %s: %d biomes, %d density functions, %d noises, overworld noise settings %s; biome source %s (%d possible biomes)", dataRoot,
            biomes.size(), loaded.registryOrThrow(Registries.DENSITY_FUNCTION).size(), loaded.registryOrThrow(Registries.NOISE).size(),
            settings.unwrapKey().orElseThrow().location(), source == dimensionSource ? "from the dimension" : "from the parameter list preset", source.possibleBiomes().size());
        return new TerrainHarness.World(registries, settings, source, loaded.lookupOrThrow(Registries.NOISE), dim, seed);
    }
}
