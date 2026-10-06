package com.mamiyaotaru.voxelmap.textures;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.util.function.Supplier;

public final class ConfiguredDynamicTexture extends VoxelMapTexture {
    public ConfiguredDynamicTexture(Supplier<String> label, NativeImage image) {
        try (image) {
            GpuDevice device = RenderSystem.getDevice();
            this.texture = device.createTexture(label, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, image.getWidth(), image.getHeight(), 1, 1);
            this.textureView = device.createTextureView(this.texture);
            this.sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST);
            image.writeToGpuTexture(device.createCommandEncoder(), this.texture);
        }
    }
}
