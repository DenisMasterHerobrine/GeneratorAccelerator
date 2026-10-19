package dev.denismasterherobrine.generatoraccelerator2.fabric;

import dev.denismasterherobrine.generatoraccelerator2.Notices;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

public final class GeneratorAcceleratorFabricClient implements ClientModInitializer {
    private static boolean checked;

    @Override
    public void onInitializeClient() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (checked || !(screen instanceof TitleScreen)) {
                return;
            }
            checked = true;
            List<String> notices = Notices.take();
            if (!notices.isEmpty()) {
                // After the title screen's init is done, not inside it
                client.tell(() -> client.setScreen(chain(client, notices, 0, screen)));
            }
        });
    }

    private static Screen chain(Minecraft client, List<String> notices, int i, Screen last) {
        if (i == notices.size()) {
            return last;
        }
        return new AlertScreen(() -> client.setScreen(chain(client, notices, i + 1, last)), Component.literal("Generator Accelerator 2"),
            Component.literal(notices.get(i)));
    }
}
