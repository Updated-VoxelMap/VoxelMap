package com.mamiyaotaru.voxelmap.gui.overridden;

import com.mamiyaotaru.voxelmap.rendering.VoxelMapGuiGraphics;
import com.mamiyaotaru.voxelmap.textures.Sprite;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class PopupGuiIconButton extends PopupGuiButton {
    private Identifier icon;
    private int iconWidth;
    private int iconHeight;
    private float u0 = 0.0F;
    private float u1 = 1.0F;
    private float v0 = 0.0F;
    private float v1 = 1.0F;
    private int color = 0xFFFFFFFF;

    public PopupGuiIconButton(int x, int y, int width, int height, OnPress onPress, IPopupGuiScreen parentScreen) {
        super(x, y, width, height, Component.empty(), onPress, parentScreen);
    }

    public void setIcon(Identifier icon, int iconWidth, int iconHeight) {
        setIcon(icon, iconWidth, iconHeight, 0.0F, 1.0F, 0.0F, 1.0F, 0xFFFFFFFF);
    }

    public void setIcon(Identifier icon, int iconWidth, int iconHeight, int color) {
        setIcon(icon, iconWidth, iconHeight, 0.0F, 1.0F, 0.0F, 1.0F, color);
    }

    public void setIcon(Identifier icon, int iconWidth, int iconHeight, float u0, float u1, float v0, float v1, int color) {
        this.icon = icon;
        this.iconWidth = iconWidth;
        this.iconHeight = iconHeight;
        this.u0 = u0;
        this.u1 = u1;
        this.v0 = v0;
        this.v1 = v1;
        this.color = color;
    }

    public void setIcon(Sprite icon, int iconWidth, int iconHeight, int color) {
        setIcon(icon.getIdentifier(), iconWidth, iconHeight, icon.getMinU(), icon.getMaxU(), icon.getMinV(), icon.getMaxV(), color);
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractContents(graphics, mouseX, mouseY, delta);
        if (this.icon != null) {
            float iconX = getX() + ((getWidth() - iconWidth) / 2.0F);
            float iconY = getY() + ((getHeight() - iconHeight) / 2.0F);
            int drawColor = this.active ? this.color : 0xFFA0A0A0;
            VoxelMapGuiGraphics.blitFloat(graphics, RenderPipelines.GUI_TEXTURED, this.icon, iconX, iconY, this.iconWidth, this.iconHeight, this.u0, this.u1, this.v0, this.v1, drawColor);
        }
    }
}
