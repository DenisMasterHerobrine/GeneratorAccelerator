package dev.denismasterherobrine.generatoraccelerator2.biome;

import com.mojang.datafixers.util.Pair;
import dev.denismasterherobrine.generatoraccelerator2.features.HarnessWorld;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Opt-in ({@code -Dga2.bench=true}): the shape of vanilla's climate trees (leaves, nodes, distinct depth ranges and offsets). */
public class ClimateTreeStatsProbe {
    @Test
    void stats() {
        Assumptions.assumeTrue(Boolean.getBoolean("ga2.bench"));
        for (var preset : List.of(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD, MultiNoiseBiomeSourceParameterList.Preset.NETHER)) {
            List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries = new MultiNoiseBiomeSourceParameterList(preset, HarnessWorld.registries().biomes.asLookup()).parameters().values();
            Map<String, Integer> depths = new HashMap<>();
            Map<Long, Integer> offsets = new HashMap<>();
            Map<String, Integer> depthOffsets = new HashMap<>();
            for (Pair<Climate.ParameterPoint, Holder<Biome>> e : entries) {
                Climate.ParameterPoint p = e.getFirst();
                String d = p.depth().min() + ".." + p.depth().max();
                depths.merge(d, 1, Integer::sum);
                offsets.merge(p.offset(), 1, Integer::sum);
                depthOffsets.merge(d + "@" + p.offset(), 1, Integer::sum);
            }
            Climate.ParameterList<Holder<Biome>> list = new Climate.ParameterList<>(entries);
            FlatClimateTree flat = ((Ga2ClimateTree) (Object) list.index).ga2$flat();
            System.out.printf("ClimateTreeStats %s: %d entries, %d nodes, %d distinct depth ranges %s, %d distinct offsets %s, %d depth+offset pairs%n", preset, entries.size(),
                flat.nodes(), depths.size(), depths, offsets.size(), offsets, depthOffsets.size());
        }
    }
}
