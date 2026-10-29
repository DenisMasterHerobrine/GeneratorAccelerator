package dev.denismasterherobrine.generatoraccelerator2.compat.magnumtorch;

import dev.denismasterherobrine.generatoraccelerator2.compat.CompatAdapters;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.Reflect;
import dev.denismasterherobrine.generatoraccelerator2.state.PoiStates;

/** Keeps the POI call for every change from or to a magnum torch ({@link MagnumTorchMixins#SERVER_LEVEL}). */
public final class MagnumTorchPoi {
    private MagnumTorchPoi() {}

    public static void register() {
        Class<?> torch = Reflect.type(MagnumTorchMixins.TORCH_BLOCK);
        if (torch == null || !MagnumTorchMixins.codeKnown()) {
            return;
        }
        PoiStates.watch(state -> torch.isInstance(state.getBlock()));
        CompatAdapters.register(HookTargets.STATE_POI_SKIP, MagnumTorchMixins.SERVER_LEVEL, () -> true);
    }
}
