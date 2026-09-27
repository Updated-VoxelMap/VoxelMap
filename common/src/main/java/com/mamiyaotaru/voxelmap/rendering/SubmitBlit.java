package com.mamiyaotaru.voxelmap.rendering;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import org.joml.Matrix3x2f;

public record SubmitBlit(Matrix3x2f matrix,
                         RenderPipeline pipeline,
                         TextureSetup texture,
                         float x0,
                         float y0,
                         float x1,
                         float y1,
                         float u0,
                         float u1,
                         float v0,
                         float v1,
                         int color0,
                         int color1) implements GuiSubmitter.Draw {

    @Override
    public void draw(GuiGraphicsExtractor graphics) {
        graphics.guiRenderState.addGuiElement(new FloatBlitRenderState(
                pipeline,
                texture,
                matrix,
                x0,
                y0,
                x1,
                y1,
                u0,
                u1,
                v0,
                v1,
                color0,
                color1,
                graphics.scissorStack.peek()));
    }
}
