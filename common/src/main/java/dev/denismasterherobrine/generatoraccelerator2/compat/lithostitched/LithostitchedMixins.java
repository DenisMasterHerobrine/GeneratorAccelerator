package dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched;

import dev.denismasterherobrine.generatoraccelerator2.compat.HookTargets;
import dev.denismasterherobrine.generatoraccelerator2.compat.KnownMixins;
import dev.denismasterherobrine.generatoraccelerator2.platform.Names;
import java.util.List;
import java.util.Set;

/** Lithostitched mixins that GA2 handles ({@link KnownMixins}). Contains no Minecraft types, because the mixin plugin reads it. */
public final class LithostitchedMixins {
    private LithostitchedMixins() {}

    public static final String MOD_ID = "lithostitched";
    /**
     * Wraps the {@code Optional.of(stub)} at the end of {@code NetherFossilStructure.findGenerationPoint}. Once vanilla has
     * found a spot, Lithostitched's template list may replace the fossil pieces. The spot search and the biome check before
     * it are vanilla's. GA2's early answer ({@code FossilProbe}) only ever says that no spot has a valid biome. So the
     * wrapped call still runs whenever vanilla would reach it.
     */
    public static final String NETHER_FOSSIL = "dev.worldgen.lithostitched.mixin.common.template.NetherFossilStructureMixin";

    /** GA2's mixin handing the partial replacement walk its per-biome subset ({@code LithostitchedInjectors}). */
    public static final String INJECTOR_LIST_MIXIN = "dev.denismasterherobrine.generatoraccelerator2.compat.lithostitched.mixin.InjectorBiomeSourceMixin";
    private static final String INJECTOR_SOURCE = "dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource";
    private static final String NOISE_BIOME = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;";

    private static final String BIOME_RESOLVER = "net/minecraft/world/level/biome/BiomeResolver";
    private static final String PARAMETER_LIST = "net/minecraft/world/level/biome/Climate$ParameterList";

    /**
     * True when the injector source's {@code getNoiseBiome} keeps the current biome in one {@code Holder} local. Only two
     * lookups may assign that local, and each result must go straight into it: the base biome lookup
     * ({@code BiomeResolver.getNoiseBiome}) and an alternate layout's lookup ({@code ParameterList.findValue}). GA2's mixin
     * notes those two results as the local's value. Any other version of the method keeps its walks as they are.
     */
    public static boolean injectorListShapeOk() {
        try {
            org.objectweb.asm.tree.ClassNode node = org.spongepowered.asm.service.MixinService.getService().getBytecodeProvider().getClassNode(INJECTOR_SOURCE);
            for (org.objectweb.asm.tree.MethodNode m : node.methods) {
                if (Names.mojangMethodKey(m.name, m.desc).equals(NOISE_BIOME)) {
                    return biomeLocalShapeOk(m);
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /**
     * Checks the local holding the current biome against the lookup of the partial replacements. That lookup is the
     * {@code getOrDefault} whose key is {@code ReplacePartially.CODEC}. Before that lookup, only the two biome lookups store
     * into the local, and each result goes straight there. Nothing after the lookup jumps back before it. The walks after
     * the lookup may store a replacement, but they run after the subset was taken. No exception handlers.
     */
    private static boolean biomeLocalShapeOk(org.objectweb.asm.tree.MethodNode m) {
        if (m.localVariables == null || (m.tryCatchBlocks != null && !m.tryCatchBlocks.isEmpty())) {
            return false;
        }
        int slot = -1;
        for (org.objectweb.asm.tree.LocalVariableNode v : m.localVariables) {
            if (Names.mojangDesc(v.desc).equals("Lnet/minecraft/core/Holder;")) {
                if (slot >= 0) {
                    return false;
                }
                slot = v.index;
            }
        }
        if (slot < 0) {
            return false;
        }
        org.objectweb.asm.tree.InsnList insns = m.instructions;
        int partial = -1;
        for (org.objectweb.asm.tree.AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof org.objectweb.asm.tree.FieldInsnNode f && f.getOpcode() == org.objectweb.asm.Opcodes.GETSTATIC
                && f.owner.equals("dev/worldgen/lithostitched/impl/worldgen/biomeinjector/ReplacePartially") && f.name.equals("CODEC")) {
                if (partial >= 0) {
                    return false;
                }
                partial = insns.indexOf(insn);
            }
        }
        if (partial < 0) {
            return false;
        }
        int lookups = 0;
        for (org.objectweb.asm.tree.AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            int at = insns.indexOf(insn);
            if (at >= partial) {
                if (insn instanceof org.objectweb.asm.tree.JumpInsnNode jump && insns.indexOf(jump.label) < partial
                    || insn instanceof org.objectweb.asm.tree.TableSwitchInsnNode || insn instanceof org.objectweb.asm.tree.LookupSwitchInsnNode) {
                    return false;
                }
                continue;
            }
            if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && isBiomeLookup(call)) {
                // The result goes straight into the local, and a cast in between is fine. Or the result is returned, when
                // there are no injectors at all.
                org.objectweb.asm.tree.AbstractInsnNode next = real(call.getNext());
                if (next != null && next.getOpcode() == org.objectweb.asm.Opcodes.CHECKCAST) {
                    next = real(next.getNext());
                }
                if (next != null && next.getOpcode() == org.objectweb.asm.Opcodes.ARETURN) {
                    continue;
                }
                if (!(next instanceof org.objectweb.asm.tree.VarInsnNode store) || store.getOpcode() != org.objectweb.asm.Opcodes.ASTORE || store.var != slot) {
                    return false;
                }
                lookups++;
            } else if (insn instanceof org.objectweb.asm.tree.VarInsnNode store && store.getOpcode() == org.objectweb.asm.Opcodes.ASTORE && store.var == slot) {
                // and nothing else is stored there before the partial replacements
                org.objectweb.asm.tree.AbstractInsnNode prev = realBack(store.getPrevious());
                if (prev != null && prev.getOpcode() == org.objectweb.asm.Opcodes.CHECKCAST) {
                    prev = realBack(prev.getPrevious());
                }
                if (!(prev instanceof org.objectweb.asm.tree.MethodInsnNode call) || !isBiomeLookup(call)) {
                    return false;
                }
            }
        }
        return lookups >= 1;
    }

    private static boolean isBiomeLookup(org.objectweb.asm.tree.MethodInsnNode call) {
        // The method is read as the running game has it. Names are compared by their Mojang names (Names).
        String owner = Names.mojangClass(call.owner);
        String name = Names.mojangMethod(call.name);
        return owner.equals(BIOME_RESOLVER) && name.equals("getNoiseBiome")
            || owner.equals(PARAMETER_LIST) && name.equals("findValue");
    }

    private static org.objectweb.asm.tree.AbstractInsnNode real(org.objectweb.asm.tree.AbstractInsnNode insn) {
        while (insn != null && insn.getOpcode() < 0) {
            insn = insn.getNext();
        }
        return insn;
    }

    private static org.objectweb.asm.tree.AbstractInsnNode realBack(org.objectweb.asm.tree.AbstractInsnNode insn) {
        while (insn != null && insn.getOpcode() < 0) {
            insn = insn.getPrevious();
        }
        return insn;
    }

    /**
     * Wraps {@code doCreateBiomes}' resolver. With TerraBlender loaded, an injector source is unwrapped and wrapped again around the
     * resolver that TerraBlender built. The GPU decides biomes only for a plain multi-noise source. That source's resolver is never an
     * injector, so the wrapped calls then run unchanged.
     */
    public static final String TERRABLENDER_RESOLVER = "dev.worldgen.lithostitched.mixin.common.compat.terrablender.NoiseBasedChunkGeneratorMixin";

    public static final List<KnownMixins.Entry> ENTRIES = List.of(
        new KnownMixins.Entry(MOD_ID, HookTargets.STRUCTURE_FOSSIL_PROBE, NETHER_FOSSIL, Set.of("useTemplateList"),
            "Lithostitched fossils still generate as normal. GA2 only speeds up places where no fossil can appear."),
        new KnownMixins.Entry(MOD_ID, HookTargets.GPU_BIOMES, TERRABLENDER_RESOLVER, Set.of("unwrapInjector", "rewrapInjector"),
            "Lithostitched's TerraBlender support still works. It changes nothing for the standard biome setup, the only one GA2 places on the GPU."));
}
