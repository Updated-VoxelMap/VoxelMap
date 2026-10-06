package com.mamiyaotaru.voxelmap.textures;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

public class AllocatedTexture extends VoxelMapTexture {
    public AllocatedTexture(GpuTexture texture) {
        this(texture, RenderSystem.getDevice().createTextureView(texture));
    }

    public AllocatedTexture(GpuTexture texture, GpuTextureView textureView) {
        this.texture = texture;
        this.textureView = textureView;
    }
}
