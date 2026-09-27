package com.mamiyaotaru.voxelmap.rendering;

import com.mamiyaotaru.voxelmap.textures.AllocatedTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.minecraft.client.renderer.texture.AbstractTexture;

public class VoxelMapRenderTarget extends RenderTarget {
    private static final GpuSampler DEFAULT_SAMPLER = RenderSystem.getSamplerCache().getSampler(AddressMode.REPEAT, AddressMode.REPEAT, FilterMode.LINEAR, FilterMode.LINEAR, false);
    private AllocatedTexture texture;

    public VoxelMapRenderTarget(String name, GpuFormat colorFormat, GpuFormat depthFormat) {
        super(name, colorFormat, depthFormat);
    }

    public AbstractTexture getTexture() {
        return texture;
    }

    @Override
    public void createBuffers(int width, int height) {
        super.createBuffers(width, height);
        texture = new AllocatedTexture(colorTexture, colorTextureView);
        texture.setSampler(DEFAULT_SAMPLER);
    }

    @Override
    public void destroyBuffers() {
        super.destroyBuffers();
        texture = null;
    }
}
