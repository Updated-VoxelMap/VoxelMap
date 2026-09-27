package com.mamiyaotaru.voxelmap.rendering;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;

public class OrderedGuiSubmitter implements AutoCloseable {
    private static final int INITIAL_CAPACITY = 2;

    private final String passName;
    private final GuiGraphicsExtractor graphics;

    private int order = 0;
    private DrawGroup[] drawGroups = new DrawGroup[INITIAL_CAPACITY];

    public OrderedGuiSubmitter(String passName, GuiGraphicsExtractor graphics) {
        this.passName = passName;
        this.graphics = graphics;
    }

    public void blit(RenderPipeline pipeline, Identifier texture, float x, float y, float w, float h, int color) {
        blit(pipeline, Minecraft.getInstance().getTextureManager().getTexture(texture), x, y, w, h, color);
    }

    public void blit(RenderPipeline pipeline, AbstractTexture texture, float x, float y, float w, float h, int color) {
        blit(pipeline, texture, x, y, w, h, 0.0F, 1.0F, 0.0F, 1.0F, color);
    }

    public void blit(RenderPipeline pipeline, Identifier identifier, float x, float y, float w, float h, float u0, float u1, float v0, float v1, int color) {
        blit(pipeline, Minecraft.getInstance().getTextureManager().getTexture(identifier), x, y, w, h, u0, u1, v0, v1, color);
    }

    public void blit(RenderPipeline pipeline, AbstractTexture texture, float x, float y, float w, float h, float u0, float u1, float v0, float v1, int color) {
        addDraw(new Fill(new Matrix3x2f(graphics.pose()), pipeline, TextureSetup.singleTexture(texture.getTextureView(), texture.getSampler()), x, y, x + w, y + h, u0, u1, v0, v1, color, color));
    }

    public void text(Font font, String text, float x, float y, int color) {
        text(font, text, x, y, color, true);
    }

    public void text(Font font, String text, float x, float y, int color, boolean shadow) {
        addDraw(new Text(new Matrix3x2f(graphics.pose()), font, text, x, y, color, false, shadow));
    }

    public void centeredText(Font font, String text, float x, float y, int color) {
        centeredText(font, text, x, y, color, true);
    }

    public void centeredText(Font font, String text, float x, float y, int color, boolean shadow) {
        addDraw(new Text(new Matrix3x2f(graphics.pose()), font, text, x, y, color, true, shadow));
    }

    public void nextOrder() {
        setOrder(order + 1);
    }

    public void setOrder(int i) {
        if (i < 0) {
            throw new IllegalArgumentException("Order must be non-negative!");
        }

        int len = i + 1;
        if (len > drawGroups.length) {
            int newCapacity = Math.max(drawGroups.length * 2, len);
            drawGroups = Arrays.copyOf(drawGroups, newCapacity);
        }

        order = i;
    }

    public void addDraw(Draw draw) {
        DrawGroup group = drawGroups[order];
        if (group == null) {
            group = new DrawGroup();
            drawGroups[order] = group;
        }

        group.addDraw(draw);
    }

    public void submitAll() {
        for (DrawGroup group : drawGroups) {
            if (group != null) {
                group.submit(graphics);
            }
        }
    }

    @Override
    public void close() {
        submitAll();
    }

    private static class DrawGroup {
        private Draw[] draws = new Draw[INITIAL_CAPACITY];
        private int len = 0;

        public void addDraw(Draw draw) {
            if (len == draws.length) {
                draws = Arrays.copyOf(draws, draws.length * 2);
            }
            draws[len++] = draw;
        }

        public void submit(GuiGraphicsExtractor graphics) {
            for (int i = 0; i < len; i++) {
                draws[i].submit(graphics);
            }
        }
    }

    public interface Draw {
        void submit(GuiGraphicsExtractor graphics);
    }

    public record Fill(Matrix3x2f matrix,
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
                       int color1) implements Draw {
        @Override
        public void submit(GuiGraphicsExtractor graphics) {
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

    public record Text(Matrix3x2f matrix,
                       Font font,
                       String text,
                       float x,
                       float y,
                       int color,
                       boolean center,
                       boolean shadow) implements Draw {

        @Override
        public void submit(GuiGraphicsExtractor graphics) {
            graphics.pose().pushMatrix();
            matrix.translate(x, y);
            graphics.pose().set(matrix);
            graphics.text(
                    font,
                    text,
                    center ? font.width(text) / 2 : 0,
                    0,
                    color,
                    shadow);
            graphics.pose().popMatrix();
        }
    }
}
