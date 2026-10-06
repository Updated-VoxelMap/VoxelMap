package com.mamiyaotaru.voxelmap.textures;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.util.function.Supplier;

public class VoxelMapDynamicTexture extends VoxelMapTexture {
    private NativeImage pixels;

    public VoxelMapDynamicTexture(Supplier<String> label, NativeImage image) {
        this.pixels = image;
        this.createTexture(label);
        this.upload();
    }

    public VoxelMapDynamicTexture(Supplier<String> label, int width, int height, boolean zero) {
        this.pixels = new NativeImage(width, height, zero);
        this.createTexture(label);
    }

    private void createTexture(Supplier<String> label) {
        GpuDevice device = RenderSystem.getDevice();
        this.texture = device.createTexture(label, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, this.pixels.getWidth(), this.pixels.getHeight(), 1, 1);
        this.sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST);
        this.textureView = device.createTextureView(this.texture);
    }

    public void upload() {
        if (this.texture != null) {
            this.pixels.writeToGpuTexture(RenderSystem.getDevice().createCommandEncoder(), this.texture);
        }
    }

    public NativeImage getPixels() {
        return this.pixels;
    }

    public void setPixels(NativeImage pixels) {
        this.pixels.close();
        this.pixels = pixels;
    }

    @Override
    public void close() {
        this.pixels.close();
        super.close();
    }
}
