package dev.denismasterherobrine.generatoraccelerator2.compat;

import dev.denismasterherobrine.generatoraccelerator2.Ga2Log;
import dev.denismasterherobrine.generatoraccelerator2.structure.TemplatePlacement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.objectweb.asm.tree.ClassNode;

/**
 * Other mods' structure processors the template clip takes ({@link TemplatePlacement}), each checked by its code. A class is
 * taken when its fingerprinted methods are as known ({@link CodeShape}) and neither it nor a superclass below
 * {@code StructureProcessor} declares any other block method. A processor that only handles entities has no block method at
 * all. Classes are matched by exact name, so a subclass stays unknown.
 */
public final class ProcessorCode {
    private ProcessorCode() {}

    private static final String STRUCTURE_PROCESSOR = "net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor";
    /** What processBlockInfos calls on a processor: the block call, NeoForge's block call with the template, and the finalize pass. */
    private static final List<String> BLOCK_METHODS = List.of("processBlock", "process", "finalizeProcessing");

    /** A processor class, its kind, and the fingerprints of its methods its block call runs (by method name). */
    public record Entry(String className, TemplatePlacement.Kind kind, Map<String, String> fingerprints) {}

    /** Registers a classifier for the entries whose code is as known. The others keep their placements whole. */
    public static void register(List<Entry> entries) {
        Map<String, TemplatePlacement.Kind> kinds = new HashMap<>();
        for (Entry e : entries) {
            String why = mismatch(e);
            if (why == null) {
                kinds.put(e.className(), e.kind());
            } else {
                Ga2Log.get("Compat").info("{}: {} -> its placements process the whole template", e.className(), why);
            }
        }
        if (!kinds.isEmpty()) {
            TemplatePlacement.addClassifier((processor, registries) -> kinds.get(processor.getClass().getName()));
        }
    }

    @Nullable
    private static String mismatch(Entry e) {
        ClassNode own = CodeShape.classNode(e.className());
        for (Map.Entry<String, String> f : e.fingerprints().entrySet()) {
            String got = CodeShape.fingerprint(CodeShape.method(own, f.getKey(), null));
            if (!got.equals(f.getValue())) {
                return f.getKey() + ": unknown code (" + got + ")";
            }
        }
        String name = e.className();
        while (!STRUCTURE_PROCESSOR.equals(name)) {
            ClassNode node = CodeShape.classNode(name);
            if (node == null || node.superName == null) {
                return "not a structure processor (" + name + ")";
            }
            for (String method : BLOCK_METHODS) {
                if (CodeShape.declares(node, method) && !(name.equals(e.className()) && e.fingerprints().containsKey(method))) {
                    return name + " declares " + method;
                }
            }
            name = node.superName.replace('/', '.');
        }
        return null;
    }
}
