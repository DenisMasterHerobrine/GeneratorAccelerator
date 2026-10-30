package dev.denismasterherobrine.generatoraccelerator2.compat.productivebees.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.denismasterherobrine.generatoraccelerator2.compat.productivebees.ProductiveBeesNests;
import dev.denismasterherobrine.generatoraccelerator2.features.modded.ConcurrentStatics;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Stores a concurrent map as Productive Bees' nest recipe memo ({@link ProductiveBeesNests}). Without a match the memo stays a
 * {@code HashMap} and the nest features keep placing one at a time.
 */
@Mixin(targets = "cy.jdkdigital.productivebees.common.block.SolitaryNest", remap = false)
public abstract class SolitaryNestRecipesMixin {
    @WrapOperation(method = "<clinit>", at = @At(value = "FIELD", target = "Lcy/jdkdigital/productivebees/common/block/SolitaryNest;recipes:Ljava/util/Map;",
        opcode = Opcodes.PUTSTATIC), require = 0)
    private static void ga2$concurrentRecipes(Map<String, ?> recipes, Operation<Void> original) {
        original.call(new ConcurrentHashMap<>(recipes));
        ConcurrentStatics.made(ProductiveBeesNests.RECIPES);
    }
}
