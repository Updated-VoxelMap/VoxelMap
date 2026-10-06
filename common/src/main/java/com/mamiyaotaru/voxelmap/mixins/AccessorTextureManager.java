package com.mamiyaotaru.voxelmap.mixins;

import java.util.Map;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TextureResources;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TextureManager.class)
public interface AccessorTextureManager {
    @Accessor("byPath")
    Map<Identifier, TextureResources> voxelmap$getByPath();
}
