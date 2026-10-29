package dev.denismasterherobrine.generatoraccelerator2.compat.magnumtorch;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.compat.CodeShape;
import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;

/** Magnum Torch mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class MagnumTorchMixins {
    private MagnumTorchMixins() {}

    private static final Logger LOGGER = Ga2Log.get("Compat");

    public static final String MOD_ID = "magnumtorch";
    /**
     * {@code ServerLevel.updatePOIOnBlockStateChange}: a change from or to a magnum torch records or drops the torch's position
     * ({@code TorchPositions.onBlockStateChange}). Every other change does nothing there. GA2 keeps the call for every change that
     * involves a magnum torch ({@link MagnumTorchPoi}).
     */
    public static final String SERVER_LEVEL = "fuzs.magnumtorch.common.mixin.ServerLevelMixin";
    public static final String TORCH_POSITIONS = "fuzs.magnumtorch.common.attachment.TorchPositions";
    public static final String TORCH_BLOCK = "fuzs.magnumtorch.common.world.level.block.MagnumTorchBlock";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.STATE_POI_SKIP, SERVER_LEVEL, Set.of("updatePOIOnBlockStateChange"),
            "Magnum torches still keep mobs away. GA2 still reports every block change that involves a magnum torch."));

    /** The handler's code the compat was written against (Magnum Torch 26.1.0): it reacts to magnum torch blocks only. */
    private static final String ON_CHANGE = "acc46e83af2c2962";
    private static final String TORCH_TYPE = "2503695c5f4bfafb";

    /** True when the mod's handler has the code the compat was written against. Logs what differs. */
    static boolean codeKnown() {
        ClassNode node = CodeShape.classNode(TORCH_POSITIONS);
        String onChange = CodeShape.fingerprint(CodeShape.method(node, "onBlockStateChange", null));
        String torchType = CodeShape.fingerprint(CodeShape.method(node, "getMagnumTorchType", null));
        if (!onChange.equals(ON_CHANGE) || !torchType.equals(TORCH_TYPE)) {
            LOGGER.info("{}: unknown code ({}, {}) -> POI skip off", TORCH_POSITIONS, onChange, torchType);
            return false;
        }
        return true;
    }
}
