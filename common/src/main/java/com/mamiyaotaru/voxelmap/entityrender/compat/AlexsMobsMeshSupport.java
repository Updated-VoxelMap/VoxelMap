package com.mamiyaotaru.voxelmap.entityrender.compat;

import com.github.alexthe666.alexsmobs.citadel.client.model.AdvancedEntityModel;
import com.github.alexthe666.alexsmobs.citadel.client.model.AdvancedModelBox;
import com.github.alexthe666.alexsmobs.citadel.client.model.basic.BasicEntityModel;
import com.github.alexthe666.alexsmobs.entity.IHurtableMultipart;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.function.BiConsumer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

final class AlexsMobsMeshSupport {
    private static final String[] HEAD_BOX_NAMES = {"head", "neck", "beak"};

    private static final float MIN_ICON_SIZE = 6.0F / 16.0F;
    private static final float MAX_ICON_SIZE = 12.0F / 16.0F;

    private static final int LIGHT = LightCoordsUtil.FULL_BRIGHT;
    private static final int OVERLAY = OverlayTexture.NO_OVERLAY;

    private AlexsMobsMeshSupport() {
    }

    static boolean isMultipartSegment(Entity entity) {
        return entity instanceof IHurtableMultipart;
    }

    @SuppressWarnings("rawtypes")
    static boolean buildEntityMeshes(PoseStack matrix, VertexConsumer buffer, EntityRenderer renderer) {
        if (!(renderer instanceof LivingEntityRenderer livingRenderer) || !(livingRenderer.getModel() instanceof BasicEntityModel<?> model)) {
            return false;
        }

        AdvancedModelBox head = null;
        if (model instanceof AdvancedEntityModel<?> advancedModel) {
            advancedModel.resetToDefaultPose();
            head = findHeadBox(advancedModel);
        }
        AdvancedModelBox headBox = head;
        BiConsumer<PoseStack, VertexConsumer> render = headBox != null
                ? (pose, consumer) -> headBox.render(pose, consumer, LIGHT, OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F)
                : (pose, consumer) -> model.renderToBuffer(pose, consumer, LIGHT, OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);

        BoundsConsumer bounds = new BoundsConsumer();
        render.accept(new PoseStack(), bounds);
        if (bounds.isEmpty()) {
            return false;
        }

        float size = Math.max(bounds.maxX - bounds.minX, bounds.maxY - bounds.minY);
        float scale = Math.clamp(size, MIN_ICON_SIZE, MAX_ICON_SIZE) / size;

        matrix.pushPose();
        matrix.scale(scale, scale, scale);
        matrix.translate(-(bounds.minX + bounds.maxX) / 2.0F, -(bounds.minY + bounds.maxY) / 2.0F, -(bounds.minZ + bounds.maxZ) / 2.0F);
        render.accept(matrix, buffer);
        matrix.popPose();
        return true;
    }

    @Nullable
    private static AdvancedModelBox findHeadBox(AdvancedEntityModel<?> model) {
        for (String name : HEAD_BOX_NAMES) {
            for (AdvancedModelBox part : model.getAllParts()) {
                if (name.equalsIgnoreCase(part.boxName)) {
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
}
