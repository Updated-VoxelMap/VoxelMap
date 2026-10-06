package com.mamiyaotaru.voxelmap.textures;

import com.mamiyaotaru.voxelmap.mixins.AccessorTextureManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureHandle;
import net.minecraft.client.renderer.texture.TextureResources;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

public abstract class VoxelMapTexture implements TextureHandle, AutoCloseable {
    protected @Nullable GpuTexture texture;
    protected @Nullable GpuTextureView textureView;
    protected GpuSampler sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.REPEAT, AddressMode.REPEAT, FilterMode.NEAREST, FilterMode.LINEAR, false);
    private @Nullable Identifier location;

    public GpuTexture getTexture() {
        if (this.texture == null) {
            throw new IllegalStateException("Texture does not exist, can't get it before something initializes it");
        }
        return this.texture;
    }

    public GpuTextureView getTextureView() {
        if (this.textureView == null) {
            throw new IllegalStateException("Texture view does not exist, can't get it before something initializes it");
        }
        return this.textureView;
    }

    public GpuSampler getSampler() {
        return this.sampler;
    }

    @Override
    public GpuTextureView textureView() {
        return this.getTextureView();
    }

    @Override
    public GpuSampler sampler() {
        return this.sampler;
    }

    public void setSampler(GpuSampler sampler) {
        this.sampler = sampler;
        this.publish();
    }

    public void register(Identifier location) {
        this.location = location;
        this.publish();
    }

    public void unregister() {
        if (this.location != null) {
            Minecraft.getInstance().getTextureManager().release(this.location);
            this.location = null;
        }
    }

    protected void publish() {
        if (this.location == null || this.texture == null || this.textureView == null) {
            return;
        }
        TextureResources resources = new TextureResources(this.texture, this.textureView, this.sampler);
        TextureResources previous = ((AccessorTextureManager) Minecraft.getInstance().getTextureManager()).voxelmap$getByPath().put(this.location, resources);
        if (previous != null && previous.texture() != this.texture) {
            previous.close();
        }
    }

    protected void releaseTextures() {
        if (this.texture != null) {
            this.texture.close();
            this.texture = null;
        }
        if (this.textureView != null) {
            this.textureView.close();
            this.textureView = null;
        }
    }

    @Override
    public void close() {
        this.releaseTextures();
    }
}
