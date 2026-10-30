package dev.denismasterherobrine.generatoraccelerator2.forge;

import dev.denismasterherobrine.generatoraccelerator2.GeneratorAccelerator;
import dev.denismasterherobrine.generatoraccelerator2.Notices;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.ModLoadingStage;
import net.minecraftforge.fml.ModLoadingWarning;
import net.minecraftforge.fml.common.Mod;

@Mod(GeneratorAccelerator.MOD_ID)
public final class GeneratorAcceleratorForge {
    // older Forge 47 builds construct mods without a context argument, so the static context stays
    @SuppressWarnings("removal")
    public GeneratorAcceleratorForge() {
        GeneratorAccelerator.init();
        for (String notice : Notices.take()) {
            ModLoader.get().addWarning(new ModLoadingWarning(ModLoadingContext.get().getActiveContainer().getModInfo(), ModLoadingStage.CONSTRUCT,
                "{0}", notice));
        }
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> GeneratorAccelerator.registerCommands(e.getDispatcher()));
        MinecraftForge.EVENT_BUS.addListener((LevelEvent.Load e) -> {
            if (e.getLevel() instanceof ServerLevel level) {
                GeneratorAccelerator.onLevelLoad(level);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((TagsUpdatedEvent e) -> {
            if (e.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
                GeneratorAccelerator.onTagsUpdated();
            }
        });
        MinecraftForge.EVENT_BUS.addListener((ServerAboutToStartEvent e) -> GeneratorAccelerator.onServerStarting(e.getServer()));
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> GeneratorAccelerator.onServerStopped(e.getServer()));
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent e) -> {
            if (e.phase == TickEvent.Phase.END) {
                GeneratorAccelerator.onServerTickPost(e.getServer());
            }
        });
    }
}
