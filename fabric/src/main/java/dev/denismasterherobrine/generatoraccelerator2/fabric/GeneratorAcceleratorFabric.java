package dev.denismasterherobrine.generatoraccelerator2.fabric;

import dev.denismasterherobrine.generatoraccelerator2.GeneratorAccelerator;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;

public final class GeneratorAcceleratorFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        GeneratorAccelerator.init();
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> GeneratorAccelerator.registerCommands(dispatcher));
        ServerWorldEvents.LOAD.register((server, level) -> GeneratorAccelerator.onLevelLoad(level));
        CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> {
            if (!client) {
                GeneratorAccelerator.onTagsUpdated();
            }
        });
        ServerLifecycleEvents.SERVER_STARTING.register(GeneratorAccelerator::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPED.register(GeneratorAccelerator::onServerStopped);
        ServerTickEvents.END_SERVER_TICK.register(GeneratorAccelerator::onServerTickPost);
    }
}
