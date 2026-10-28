package com.yungnickyoung.minecraft.yungsapi.world.structure.terrainadaptation.beardifier;

import com.yungnickyoung.minecraft.yungsapi.world.structure.terrainadaptation.EnhancedTerrainAdaptation;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;

/** Stand-in for YUNG's API 4.0 (1.20.1) junction record, for {@code EnhancedBeardYung4Test}. */
public record EnhancedJigsawJunction(JigsawJunction jigsawJunction, EnhancedTerrainAdaptation pieceTerrainAdaptation) {}
