package dev.denismasterherobrine.generatoraccelerator2.neoforge;

import dev.denismasterherobrine.generatoraccelerator2.GeneratorAccelerator;
import dev.denismasterherobrine.generatoraccelerator2.Notices;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(GeneratorAccelerator.MOD_ID)
public final class GeneratorAcceleratorNeoForge {
    public GeneratorAcceleratorNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        GeneratorAccelerator.init();
        for (String notice : Notices.take()) {
            ModLoader.addLoadingIssue(ModLoadingIssue.warning("{0}", notice));
        }
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> GeneratorAccelerator.registerCommands(e.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((LevelEvent.Load e) -> {
            if (e.getLevel() instanceof ServerLevel level) {
                GeneratorAccelerator.onLevelLoad(level);
            }
        });
        NeoForge.EVENT_BUS.addListener((TagsUpdatedEvent.ServerDataLoad e) -> GeneratorAccelerator.onTagsUpdated());
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent e) -> GeneratorAccelerator.onServerStarting(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> GeneratorAccelerator.onServerStopped(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> GeneratorAccelerator.onServerTickPost(e.getServer()));
    }
}
