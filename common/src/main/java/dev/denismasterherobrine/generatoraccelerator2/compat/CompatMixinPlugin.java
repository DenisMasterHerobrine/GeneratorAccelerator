package dev.denismasterherobrine.generatoraccelerator2.compat;

import dev.denismasterherobrine.generatoraccelerator2.compat.spelunkery.SpelunkeryMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.supplementaries.SupplementariesMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.terrablender.TerraBlenderMixins;
import dev.denismasterherobrine.generatoraccelerator2.compat.zeta.ZetaMixins;
import dev.denismasterherobrine.generatoraccelerator2.dfc.Compat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Plugin for the per-mod mixin configs ({@code generatoraccelerator2.compat.<modid>.mixins.json}, package
 * {@code compat.<modid>.mixin}). A config's mixins apply only while that mod is loaded. The package segment names the
 * mod. The few mod ids that are not Java identifiers are mapped below.
 */
public final class CompatMixinPlugin implements IMixinConfigPlugin {
    private static final String PREFIX = "dev.denismasterherobrine.generatoraccelerator2.compat.";
    /** Package segment to mod id, where they differ. */
    private static final Map<String, String> MOD_IDS = Map.of("naturespirit", "natures_spirit", "regionsunexplored", "regions_unexplored");

    private boolean active;

    @Override
    public void onLoad(String mixinPackage) {
        String segment = mixinPackage.startsWith(PREFIX) ? mixinPackage.substring(PREFIX.length()) : mixinPackage;
        int dot = segment.indexOf('.');
        if (dot >= 0) {
            segment = segment.substring(0, dot);
        }
        this.active = Compat.isLoaded(MOD_IDS.getOrDefault(segment, segment));
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (active && mixinClassName.equals(SpelunkeryMixins.STRIPES_MIXIN)) {
            // the fused noise relies on the FastNoiseLite code it was written against
            return SpelunkeryMixins.codeKnown();
        }
        if (active && ZetaMixins.MIXINS.contains(mixinClassName)) {
            // the cluster walk is for Quark's big stone clusters and relies on the code it was written against
            return Compat.isLoaded("quark") && ZetaMixins.codeKnown();
        }
        if (active && SupplementariesMixins.MIXINS.contains(mixinClassName)) {
            // the barnacle search relies on the code it was written against
            return SupplementariesMixins.codeKnown();
        }
        return active;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        Coremods.inspect(targetClassName, targetClass);
    }

    /**
     * Mixin calls every config's {@code preApply} for a class before applying any mixin to the class. Mixin calls
     * {@code postApply} after all regular mixins are merged. MixinExtras' late injectors, such as these configs' wrappers,
     * come after that. Checks of other mods' merged code therefore happen here.
     */
    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (mixinClassName.equals(TerraBlenderMixins.REGION_SEARCH_MIXIN)) {
            TerraBlenderMixins.check(targetClass);
        }
        // these configs' hooks are inspected like the main config's hooks. The main config's plugin registered the late inspection
        if (LateInspection.registered()) {
            LateInspection.queue(targetClassName, mixinClassName);
        }
    }
}
