package com.mamiyaotaru.voxelmap.rendering;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2f;

public record SubmitText(Matrix3x2f matrix,
                         String text,
                         float x,
                         float y,
                         int color,
                         boolean shadow) implements GuiSubmitter.Draw {

    @Override
    public void draw(GuiGraphicsExtractor graphics) {
        graphics.pose().pushMatrix();
        matrix.translate(x, y);
        graphics.pose().set(matrix);
        graphics.text(
                Minecraft.getInstance().font,
                text,
                0,
                0,
                color,
                shadow);
        graphics.pose().popMatrix();
    }
}
