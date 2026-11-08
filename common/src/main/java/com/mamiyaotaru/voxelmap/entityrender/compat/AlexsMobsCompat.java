package com.mamiyaotaru.voxelmap.entityrender.compat;

import com.mamiyaotaru.voxelmap.VoxelConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

public final class AlexsMobsCompat {
    private static final String MODEL_PACKAGE = "com.github.alexthe666.alexsmobs.citadel.client.model.";
    private static final String[] HEAD_BOX_NAMES = {"head", "neck", "beak"};

    private static final float MIN_ICON_SIZE = 6.0F / 16.0F;
    private static final float MAX_ICON_SIZE = 12.0F / 16.0F;

    private static final int LIGHT = LightCoordsUtil.FULL_BRIGHT;
    private static final int OVERLAY = OverlayTexture.NO_OVERLAY;

    @Nullable
    private static final Reflection REFLECTION = Reflection.load();

    private AlexsMobsCompat() {
    }

    public static boolean isMultipartSegment(Entity entity) {
        return REFLECTION != null && REFLECTION.multipartSegment.isInstance(entity);
    }

    @SuppressWarnings("rawtypes")
    public static boolean buildEntityMeshes(PoseStack matrix, VertexConsumer buffer, EntityRenderer renderer) {
        if (REFLECTION == null || !(renderer instanceof LivingEntityRenderer livingRenderer)) {
            return false;
        }
        Object model = livingRenderer.getModel();
        if (!REFLECTION.basicEntityModel.isInstance(model)) {
            return false;
        }
        try {
            Object head = null;
            if (REFLECTION.advancedEntityModel.isInstance(model)) {
                REFLECTION.resetToDefaultPose.invoke(model);
                head = findHeadBox(model);
            }
            Object target = head != null ? head : model;
            Method render = head != null ? REFLECTION.renderPart : REFLECTION.renderModel;

            BoundsConsumer bounds = new BoundsConsumer();
            render.invoke(target, new PoseStack(), bounds, LIGHT, OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
            if (bounds.isEmpty()) {
                return false;
            }

            float size = Math.max(bounds.maxX - bounds.minX, bounds.maxY - bounds.minY);
            float scale = Math.clamp(size, MIN_ICON_SIZE, MAX_ICON_SIZE) / size;

            matrix.pushPose();
            matrix.scale(scale, scale, scale);
            matrix.translate(-(bounds.minX + bounds.maxX) / 2.0F, -(bounds.minY + bounds.maxY) / 2.0F, -(bounds.minZ + bounds.maxZ) / 2.0F);
            render.invoke(target, matrix, buffer, LIGHT, OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
            matrix.popPose();
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            VoxelConstants.getLogger().warn("Failed to build Alex's Mobs mesh for radar icon", e);
            return false;
        }
    }

    @Nullable
    private static Object findHeadBox(Object model) throws ReflectiveOperationException {
        Iterable<?> parts = (Iterable<?>) REFLECTION.getAllParts.invoke(model);
        for (String name : HEAD_BOX_NAMES) {
            for (Object part : parts) {
                if (name.equalsIgnoreCase((String) REFLECTION.boxName.get(part))) {
                    return part;
                }
            }
        }
        return null;
    }

    private static final class BoundsConsumer implements VertexConsumer {
        private float minX = Float.POSITIVE_INFINITY;
        private float minY = Float.POSITIVE_INFINITY;
        private float minZ = Float.POSITIVE_INFINITY;
        private float maxX = Float.NEGATIVE_INFINITY;
        private float maxY = Float.NEGATIVE_INFINITY;
        private float maxZ = Float.NEGATIVE_INFINITY;

        boolean isEmpty() {
            return minX > maxX || minY > maxY;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }

    private record Reflection(Class<?> multipartSegment, Class<?> basicEntityModel, Class<?> advancedEntityModel, Method renderModel, Method resetToDefaultPose, Method getAllParts, Field boxName, Method renderPart) {
        @Nullable
        static Reflection load() {
            try {
                ClassLoader loader = AlexsMobsCompat.class.getClassLoader();
                Class<?> basicEntityModel = Class.forName(MODEL_PACKAGE + "basic.BasicEntityModel", false, loader);
                Class<?> basicModelPart = Class.forName(MODEL_PACKAGE + "basic.BasicModelPart", false, loader);
                Class<?> advancedEntityModel = Class.forName(MODEL_PACKAGE + "AdvancedEntityModel", false, loader);
                Class<?> advancedModelBox = Class.forName(MODEL_PACKAGE + "AdvancedModelBox", false, loader);
                Class<?>[] renderArgs = {PoseStack.class, VertexConsumer.class, int.class, int.class, float.class, float.class, float.class, float.class};
                return new Reflection(
                        Class.forName("com.github.alexthe666.alexsmobs.entity.IHurtableMultipart", false, loader),
                        basicEntityModel,
                        advancedEntityModel,
                        basicEntityModel.getMethod("renderToBuffer", renderArgs),
                        advancedEntityModel.getMethod("resetToDefaultPose"),
                        advancedEntityModel.getMethod("getAllParts"),
                        advancedModelBox.getField("boxName"),
                        basicModelPart.getMethod("render", renderArgs));
            } catch (ClassNotFoundException | LinkageError e) {
                return null;
            } catch (ReflectiveOperationException e) {
                VoxelConstants.getLogger().warn("Alex's Mobs found but its model API changed, radar icons will be missing", e);
                return null;
            }
        }
    }
}
