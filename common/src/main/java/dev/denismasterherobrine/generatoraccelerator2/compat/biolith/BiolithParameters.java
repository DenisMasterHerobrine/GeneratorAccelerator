package dev.denismasterherobrine.generatoraccelerator2.compat.biolith;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import javax.annotation.Nullable;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import java.util.function.BiFunction;

/**
 * Biolith's parameter list, read without Biolith's lock. On the first {@code MultiNoiseBiomeSource.parameters()} call,
 * Biolith builds the overworld and nether parameter lists with its biomes injected. Biolith stores them in a field that it
 * adds to the biome source ({@code biolith$biomeEntries}). Every call, including all later ones, enters the biome source's
 * monitor to check that field and then returns it. Every biome lookup asks for the parameters, from all workers at once. In
 * a 76-mod pack, the workers waited 15 s on that monitor in a three-minute run. Once set, the field never changes. GA2
 * therefore returns it at the head of the method ({@code mixin/MultiNoiseParametersMixin}). Until the field is set, the
 * method runs unchanged and builds it. A list read without the lock is complete. The list's fields are final, so a thread
 * that sees the reference also sees them.
 */
public final class BiolithParameters {
    private BiolithParameters() {}

    @Nullable
    private static final VarHandle ENTRIES = find();
    /** Asked for every biome lookup. */
    private static final HookTargets.Hook HOOK = HookTargets.hook(HookTargets.BIOME_PARAMETERS);

    @Nullable
    private static VarHandle find() {
        try {
            Field f = MultiNoiseBiomeSource.class.getDeclaredField("biolith$biomeEntries");
            f.setAccessible(true);
            return MethodHandles.privateLookupIn(MultiNoiseBiomeSource.class, MethodHandles.lookup()).unreflectVarHandle(f);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    public static void register() {
        CompatAdapters.register(HookTargets.BIOME_PARAMETERS, BiolithMixins.PARAMETERS, () -> ENTRIES != null);
    }

    /**
     * Another mod's modifier of the {@code parameters()} return value, such as Spawn's restored biomes. GA2 applies it to
     * Biolith's built list the same way the method's own return applies it. Null when no modifier is installed.
     */
    public static volatile BiFunction<MultiNoiseBiomeSource, Object, Object> finisher;

    /** {@code entries} as the method returns them, including other mods' modifier of the return value. */
    public static Object finish(MultiNoiseBiomeSource source, Object entries) {
        BiFunction<MultiNoiseBiomeSource, Object, Object> f = finisher;
        return f != null ? f.apply(source, entries) : entries;
    }

    /** Biolith's built list for {@code source}. Null when the list is not built yet or the hook may not run. */
    @Nullable
    public static Object entries(MultiNoiseBiomeSource source) {
        if (ENTRIES == null || !HOOK.allowed()) {
            return null;
        }
        return ENTRIES.get(source);
    }
}
