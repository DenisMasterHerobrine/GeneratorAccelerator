package dev.denismasterherobrine.generatoraccelerator2.testutil;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;

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
        try (MultiPackResourceManager manager = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(ServerPacksSource.createVanillaPackSource()))) {
            bind(manager, BuiltInRegistries.BLOCK, Registries.BLOCK);
            bind(manager, BuiltInRegistries.FLUID, Registries.FLUID);
            bind(manager, BuiltInRegistries.ITEM, Registries.ITEM);
            bind(manager, BuiltInRegistries.ENTITY_TYPE, Registries.ENTITY_TYPE);
        }
        bound = true;
    }

    @SuppressWarnings("unchecked")
    private static <T> void bind(MultiPackResourceManager manager, Registry<T> registry, ResourceKey<? extends Registry<T>> key) {
        TagLoader.ElementLookup<Holder<T>> lookup = (TagLoader.ElementLookup<Holder<T>>) TagLoader.ElementLookup.fromFrozenRegistry(registry);
        Map<TagKey<T>, List<Holder<T>>> tags = TagLoader.loadTagsForRegistry(manager, key, lookup);
        if (!(registry instanceof WritableRegistry<T> open) || !isOpen(registry)) {
            registry.prepareTagReload(new TagLoader.LoadResult<>(key, tags)).apply();
            return;
        }
        // Fabric API keeps the built-in registries open in the test runtime. An open registry gives its holders their tags when it
        // is frozen, so they are given here.
        open.bindTags(tags);
        Map<Holder.Reference<T>, List<TagKey<T>>> byHolder = new IdentityHashMap<>();
        registry.listElements().forEach(h -> byHolder.put(h, new ArrayList<>()));
        tags.forEach((tag, holders) -> holders.forEach(h -> byHolder.get((Holder.Reference<T>) h).add(tag)));
        try {
            Method bindTags = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class);
            bindTags.setAccessible(true);
            for (Map.Entry<Holder.Reference<T>, List<TagKey<T>>> e : byHolder.entrySet()) {
                bindTags.invoke(e.getKey(), e.getValue());
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("binding tags to the holders of " + key, e);
        }
    }

    /** Whether the registry still takes writes (not frozen). */
    private static boolean isOpen(Registry<?> registry) {
        try {
            Field frozen = MappedRegistry.class.getDeclaredField("frozen");
            frozen.setAccessible(true);
            return registry instanceof MappedRegistry<?> && !frozen.getBoolean(registry);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
