package dev.denismasterherobrine.generatoraccelerator2.testutil;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.tags.TagManager;

/**
 * Binds the vanilla data pack's block, fluid and item tags to the built-in registries. The unit-test
 * bootstrap creates registries but never loads a data pack, so without this every
 * {@code BlockState.is(tag)} is false and most features (ores, grass, trees) silently do nothing.
 */
public final class VanillaTags {
    private VanillaTags() {}

    private static boolean bound;

    public static synchronized void bind() {
        if (bound) {
            return;
        }
        try (MultiPackResourceManager manager = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(new ServerPacksSource().getVanillaPack()))) {
            bind(manager, BuiltInRegistries.BLOCK, Registries.BLOCK);
            bind(manager, BuiltInRegistries.FLUID, Registries.FLUID);
            bind(manager, BuiltInRegistries.ITEM, Registries.ITEM);
            bind(manager, BuiltInRegistries.ENTITY_TYPE, Registries.ENTITY_TYPE);
        }
        bound = true;
    }

    private static <T> void bind(MultiPackResourceManager manager, Registry<T> registry, ResourceKey<? extends Registry<T>> key) {
        TagLoader<Holder<T>> loader = new TagLoader<>(id -> registry.getHolder(ResourceKey.create(key, id)).map(h -> (Holder<T>) h),
            TagManager.getTagDir(key));
        Map<ResourceLocation, Collection<Holder<T>>> loaded = loader.loadAndBuild(manager);
        Map<TagKey<T>, List<Holder<T>>> tags = new HashMap<>();
        for (Map.Entry<ResourceLocation, Collection<Holder<T>>> e : loaded.entrySet()) {
            tags.put(TagKey.create(key, e.getKey()), List.copyOf(e.getValue()));
        }
        registry.bindTags(tags);
    }

    /** Null when a holder is absent, so the loader reports a missing entry instead of failing. */
    @SuppressWarnings("unused")
    private static <T> Optional<Holder<T>> holder(Registry<T> registry, ResourceKey<? extends Registry<T>> key, ResourceLocation id) {
        return registry.getHolder(ResourceKey.create(key, id)).map(h -> h);
    }
}
