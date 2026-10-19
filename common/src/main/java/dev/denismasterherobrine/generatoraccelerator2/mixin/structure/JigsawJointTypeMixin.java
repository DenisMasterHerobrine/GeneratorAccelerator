package dev.denismasterherobrine.generatoraccelerator2.mixin.structure;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code JointType.byName} streams over the enum values. Jigsaw assembly calls it for every pair of jigsaw
 * blocks it tries to attach. The answer for a name never changes, so it is looked up in a table.
 */
@Mixin(JigsawBlockEntity.JointType.class)
public abstract class JigsawJointTypeMixin {
    @Unique
    private static volatile Map<String, JigsawBlockEntity.JointType> ga2$byName;

    @Inject(method = "byName", at = @At("HEAD"), cancellable = true)
    private static void ga2$table(String name, CallbackInfoReturnable<Optional<JigsawBlockEntity.JointType>> cir) {
        Map<String, JigsawBlockEntity.JointType> table = ga2$byName;
        if (table == null) {
            table = new HashMap<>();
            for (JigsawBlockEntity.JointType type : JigsawBlockEntity.JointType.values()) {
                // first match wins, as with vanilla's findFirst over values()
                table.putIfAbsent(type.getSerializedName(), type);
            }
            ga2$byName = table;
        }
        cir.setReturnValue(Optional.ofNullable(table.get(name)));
    }
}
