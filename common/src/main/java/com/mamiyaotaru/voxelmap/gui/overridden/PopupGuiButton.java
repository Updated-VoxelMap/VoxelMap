package com.mamiyaotaru.voxelmap.gui.overridden;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public class PopupGuiButton extends Button.Plain {
    final IPopupGuiScreen parentScreen;

    public PopupGuiButton(int x, int y, int width, int height, Component message, OnPress onPress, IPopupGuiScreen parentScreen) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        this.parentScreen = parentScreen;
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics) {
        if (this.parentScreen.overPopup(graphics.mouseX(), graphics.mouseY())) {
            super.extractContents(graphics);
        } else {
            graphics.extractWithoutMouse(() -> super.extractContents(graphics));
        }
    }
}
