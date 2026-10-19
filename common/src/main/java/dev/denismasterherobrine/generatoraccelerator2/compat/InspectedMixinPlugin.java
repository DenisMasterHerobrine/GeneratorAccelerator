package dev.denismasterherobrine.generatoraccelerator2.compat;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Plugin for a mixin config whose mixins always apply. The config's hooked methods are inspected for other mods' changes
 * the same way as the main config's hooked methods (the late inspection, {@code HookTargets}). Used by packages that are
 * kept apart from the rest of the mod and have their own config, such as {@code parallellight}.
 */
public final class InspectedMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
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

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // the main config's plugin loads first and registered the late inspection then. It inspects what is queued here
        LateInspection.queue(targetClassName, mixinClassName);
    }
}
