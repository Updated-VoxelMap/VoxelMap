package com.mamiyaotaru.voxelmap.entityrender.compat;

import com.mamiyaotaru.voxelmap.VoxelConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;

public final class AlexsMobsCompat {
    private static final String BASIC_ENTITY_MODEL_CLASS = "com.github.alexthe666.alexsmobs.citadel.client.model.basic.BasicEntityModel";

    private static final boolean AVAILABLE = isClassPresent(BASIC_ENTITY_MODEL_CLASS);

    private AlexsMobsCompat() {
    }

    public static boolean isMultipartSegment(Entity entity) {
        if (!AVAILABLE) {
            return false;
        }
        try {
            return AlexsMobsMeshSupport.isMultipartSegment(entity);
        } catch (LinkageError e) {
            return false;
        }
    }

    @SuppressWarnings("rawtypes")
    public static boolean buildEntityMeshes(PoseStack matrix, VertexConsumer buffer, EntityRenderer renderer) {
        if (!AVAILABLE) {
            return false;
        }
        try {
            return AlexsMobsMeshSupport.buildEntityMeshes(matrix, buffer, renderer);
        } catch (RuntimeException | LinkageError e) {
            VoxelConstants.getLogger().warn("Failed to build Alex's Mobs mesh for radar icon", e);
            return false;
        }
    }

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, AlexsMobsCompat.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
