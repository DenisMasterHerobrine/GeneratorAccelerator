package dev.denismasterherobrine.generatoraccelerator2.structure;

import java.util.Optional;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/** What {@code ChunkGenerator.createReferences} throws when a start fails ({@code mixin.structure.StructureReferencesMixin}). */
public final class StructureReferences {
    private StructureReferences() {}

    /** Vanilla's crash report for a structure reference that failed. */
    public static ReportedException crash(WorldGenLevel level, StructureStart start, Exception exception) {
        CrashReport report = CrashReport.forThrowable(exception, "Generating structure reference");
        CrashReportCategory category = report.addCategory("Structure");
        Optional<? extends Registry<Structure>> registry = level.registryAccess().registry(Registries.STRUCTURE);
        category.setDetail("Id", () -> registry.<String>map(r -> r.getKey(start.getStructure()).toString()).orElse("UNKNOWN"));
        category.setDetail("Name", () -> BuiltInRegistries.STRUCTURE_TYPE.getKey(start.getStructure().type()).toString());
        category.setDetail("Class", () -> start.getStructure().getClass().getCanonicalName());
        return new ReportedException(report);
    }
}
