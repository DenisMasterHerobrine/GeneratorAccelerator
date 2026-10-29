package com.gregtechceu.gtceu.config;

/** The fields of GregTech CEu's config that GA2 reads ({@code compat.gtceu.GtceuVeins}), for tests. */
public class ConfigHolder {
    public static ConfigHolder INSTANCE;

    public WorldGenConfigs worldgen = new WorldGenConfigs();

    public static class WorldGenConfigs {
        public OreVeinConfigs oreVeins = new OreVeinConfigs();

        public static class OreVeinConfigs {
            public boolean removeVanillaLargeOreVeins;
        }
    }
}
