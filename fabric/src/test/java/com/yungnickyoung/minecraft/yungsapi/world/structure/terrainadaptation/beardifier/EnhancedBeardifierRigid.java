package com.yungnickyoung.minecraft.yungsapi.world.structure.terrainadaptation.beardifier;

import com.yungnickyoung.minecraft.yungsapi.world.structure.terrainadaptation.EnhancedTerrainAdaptation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Stand-in for YUNG's API 4.0 (1.20.1) piece record, for {@code EnhancedBeardYung4Test}. */
public record EnhancedBeardifierRigid(BoundingBox pieceBoundingBox, EnhancedTerrainAdaptation pieceTerrainAdaptation, int pieceGroundLevelDelta) {}
