package com.mamiyaotaru.voxelmap.rendering;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import org.joml.Matrix3x2f;

public record SubmitFill(Matrix3x2f matrix,
                         RenderPipeline pipeline,
                         TextureSetup texture,
                         float x0,
                         float y0,
                         float x1,
                         float y1,
                         int color00,
                         int color01,
                         int color10,
                         int color11) implements GuiSubmitter.Draw {

    @Override
    public void draw(GuiGraphicsExtractor graphics) {
        graphics.guiRenderState.addGuiElement(new FourColoredRectangleRenderState(
                pipeline,
                texture,
                matrix,
                x0,
                y0,
                x1,
                y1,
                color00,
                color01,
                color10,
                color11,
                graphics.scissorStack.peek()));
    }
}
