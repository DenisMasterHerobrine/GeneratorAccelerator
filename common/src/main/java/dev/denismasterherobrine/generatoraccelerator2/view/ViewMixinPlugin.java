package dev.denismasterherobrine.generatoraccelerator2.view;

import dev.denismasterherobrine.generatoraccelerator2.dfc.Compat;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Plugin for {@code generatoraccelerator2.view.mixins.json}. The render thread and section buffer mixins stay off when another
 * mod replaces vanilla's section renderer.
 */
public final class ViewMixinPlugin implements IMixinConfigPlugin {
    private static final String PACKAGE = "dev.denismasterherobrine.generatoraccelerator2.view.mixin.";
    /** Mods with a section renderer of their own. */
    private static final Set<String> RENDERERS = Set.of("sodium", "embeddium", "rubidium", "vulkanmod", "canvas");
    private static final Set<String> RENDER_MIXINS = Set.of("SectionOcclusionGraphMixin", "LevelRendererMeshMixin");
    private static final Set<String> BUFFER_MIXINS = Set.of("VertexBufferMixin", "RenderSectionBuffersMixin", "DebugVramMixin");

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String name = mixinClassName.startsWith(PACKAGE) ? mixinClassName.substring(PACKAGE.length()) : mixinClassName;
        if (RENDER_MIXINS.contains(name)) {
            return RenderThreads.ENABLED && RENDERERS.stream().noneMatch(Compat::isLoaded);
        }
        if (name.equals("LevelRendererEvictMixin")) {
            return RenderThreads.FREE_SECTION_BUFFERS && RenderThreads.EVICT_HIDDEN_SECONDS > 0 && RENDERERS.stream().noneMatch(Compat::isLoaded);
        }
        if (BUFFER_MIXINS.contains(name)) {
            return RenderThreads.FREE_SECTION_BUFFERS && RENDERERS.stream().noneMatch(Compat::isLoaded);
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
