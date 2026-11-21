package com.mamiyaotaru.voxelmap;

import com.mamiyaotaru.voxelmap.gui.GuiAddWaypoint;
import com.mamiyaotaru.voxelmap.gui.GuiWaypoints;
import com.mamiyaotaru.voxelmap.gui.overridden.EnumOptionsMinimap;
import com.mamiyaotaru.voxelmap.interfaces.AbstractMapData;
import com.mamiyaotaru.voxelmap.persistent.GuiPersistentMap;
import com.mamiyaotaru.voxelmap.rendering.CachedProjectionMatrixBuffer;
import com.mamiyaotaru.voxelmap.rendering.RenderUtils;
import com.mamiyaotaru.voxelmap.rendering.SubmitPass;
import com.mamiyaotaru.voxelmap.rendering.VoxelMapRenderTarget;
import com.mamiyaotaru.voxelmap.rendering.VoxelMapRenderTypes;
import com.mamiyaotaru.voxelmap.textures.ConfiguredDynamicTexture;
import com.mamiyaotaru.voxelmap.textures.Sprite;
import com.mamiyaotaru.voxelmap.textures.TextureAtlas;
import com.mamiyaotaru.voxelmap.tutorial.ModTutorialSignals;
import com.mamiyaotaru.voxelmap.util.BiomeRepository;
import com.mamiyaotaru.voxelmap.util.Contact;
import com.mamiyaotaru.voxelmap.util.DimensionContainer;
import com.mamiyaotaru.voxelmap.util.GameVariableAccessShim;
import com.mamiyaotaru.voxelmap.util.MinimapContext;
import com.mamiyaotaru.voxelmap.util.Waypoint;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.math.Axis;
import com.mojang.renderpearl.api.GpuFormat;
import java.util.ArrayList;
import java.util.Random;
import java.util.TreeSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import org.joml.Matrix4fStack;
import org.joml.Vector4f;

public class MapRenderer  {
    private static final float SQRT_2 = (float) Math.sqrt(2.0);

    private static final float MAP_IMAGE_DEPTH = 0.0F;
    private static final float MAP_OVERLAY_DEPTH = 100.0F;
    private static final float MAP_TEXT_DEPTH = 200.0F;

    private final Minecraft minecraft = Minecraft.getInstance();
    private final MapSettingsManager options;
    private final WaypointManager waypointManager;
    private final MinimapContext context = new MinimapContext();
    private final Random random = new Random();
    private final Map map;

    private final Identifier resourceArrow = Identifier.fromNamespaceAndPath(VoxelConstants.MOD_ID, "images/minimap/minimap_arrow.png");
    private final Identifier resourceSquareMapFrame = Identifier.fromNamespaceAndPath(VoxelConstants.MOD_ID, "images/minimap/square_map_frame.png");
    private final Identifier resourceSquareMapStencil = Identifier.fromNamespaceAndPath(VoxelConstants.MOD_ID, "images/minimap/square_map_stencil.png");
    private final Identifier resourceRoundMapFrame = Identifier.fromNamespaceAndPath(VoxelConstants.MOD_ID, "images/minimap/round_map_frame.png");
    private final Identifier resourceRoundMapStencil = Identifier.fromNamespaceAndPath(VoxelConstants.MOD_ID, "images/minimap/round_map_stencil.png");

    private volatile boolean imageChanged = true;
    private int lastImageX;
    private int lastImageZ;
    private float direction;
    private float rotation;
    private int zoom;
    private double zoomScale = 1.0;
    private double zoomScaleAdjusted = 1.0;

    private int scWidth;
    private int scHeight;
    public boolean fullscreen;
    public boolean lastFullscreen;
    private Screen lastScreen;
    private String message = "";
    private long messageTime;
    private static double minTablistOffset;
    private static float statusIconOffset = 0.0F;

    private final CachedProjectionMatrixBuffer hudProjection;
    private final CachedProjectionMatrixBuffer mapProjection;
    private final VoxelMapRenderTarget baseMapRenderTarget; // for map, radar, etc.
    private final VoxelMapRenderTarget finalMapRenderTarget; // for masking

    public MapRenderer(Map map) {
        options = VoxelConstants.getVoxelMapInstance().getMapOptions();
        waypointManager = VoxelConstants.getVoxelMapInstance().getWaypointManager();
        this.map = map;

        hudProjection = CachedProjectionMatrixBuffer.orthographic("VoxelMap HUD Projection", 1000.0F, 21000.0F, true);
        mapProjection = CachedProjectionMatrixBuffer.orthographic("VoxelMap Map Projection", 1000.0F, 21000.0F, true);

        final int fboTextureSize = 512;
        baseMapRenderTarget = new VoxelMapRenderTarget("VoxelMap Base Map Target", GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        baseMapRenderTarget.createBuffers(fboTextureSize, fboTextureSize);
        finalMapRenderTarget = new VoxelMapRenderTarget("VoxelMap Final Map Target", GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        finalMapRenderTarget.createBuffers(fboTextureSize, fboTextureSize);

        zoom = options.zoom;
        setZoomScale();
        map.zoomChanged(zoom);
    }

    public static double getMinTablistOffset() {
        return minTablistOffset;
    }

    public static float getStatusIconOffset() {
        return statusIconOffset;
    }

    public void reloadTextures(ResourceManager resourceManager) {
        TextureManager textureManager = minecraft.getTextureManager();

        try {
            boolean arrowFiltering = Boolean.parseBoolean(VoxelConstants.getVoxelMapInstance().getImageProperties().getProperty("minimapArrowFiltering", "true"));
            boolean frameFiltering = Boolean.parseBoolean(VoxelConstants.getVoxelMapInstance().getImageProperties().getProperty("minimapFrameFiltering", "true"));

            ConfiguredDynamicTexture arrowTexture = new ConfiguredDynamicTexture(() -> "Minimap Arrow", TextureContents.load(resourceManager, resourceArrow).image());
            arrowTexture.setSampler(RenderUtils.getSampler(arrowFiltering, false));
            textureManager.register(resourceArrow, arrowTexture);

            ConfiguredDynamicTexture roundMapTexture = new ConfiguredDynamicTexture(() -> "Minimap Round Map Frame", TextureContents.load(resourceManager, resourceRoundMapFrame).image());
            roundMapTexture.setSampler(RenderUtils.getSampler(frameFiltering, false));
            textureManager.register(resourceRoundMapFrame, roundMapTexture);

            ConfiguredDynamicTexture squareMapTexture = new ConfiguredDynamicTexture(() -> "Minimap Square Map Frame", TextureContents.load(resourceManager, resourceSquareMapFrame).image());
            squareMapTexture.setSampler(RenderUtils.getSampler(frameFiltering, false));
            textureManager.register(resourceSquareMapFrame, squareMapTexture);

        } catch (Exception exception) {
            VoxelConstants.getLogger().error("Failed getting map images {}", exception.getLocalizedMessage(), exception);
        }
    }

    public void imageChanged() {
        imageChanged = true;
    }

    public void showMessage(String str) {
        message = str;
        messageTime = System.currentTimeMillis();
    }

    public void onTickInGame(GuiGraphicsExtractor graphics) {
        rotation = options.oldNorth ? 90 : 0;

        direction = GameVariableAccessShim.rotationYaw() + 180.0F;
        while (direction >= 360.0F) {
            direction -= 360.0F;
        }
        while (direction < 0.0F) {
            direction += 360.0F;
        }

        if (!message.isEmpty() && (System.currentTimeMillis() - messageTime > 3000L)) {
            message = "";
        }

        if (options.deathWaypointAllowed && minecraft.gui.screen() instanceof DeathScreen && !(lastScreen instanceof DeathScreen)) {
            waypointManager.handleDeath();

        } else if (minecraft.gui.screen() == null) {
            if (options.keyBindMenu.consumeClick()) {
                minecraft.gui.setScreen(new GuiPersistentMap(null));
                ModTutorialSignals.menuOpened();
            }

            if (options.keyBindWaypointToggle.consumeClick()) {
                options.toggleIngameWaypoints();
            }

            if (options.keyBindMobToggle.consumeClick()) {
                VoxelConstants.getVoxelMapInstance().getRadarOptions().toggleBooleanValue(EnumOptionsMinimap.SHOW_RADAR);
                options.saveAll();
            }

            if (options.keyBindZoom.consumeClick()) {
                cycleZoom();
            }

            if (options.keyBindFullscreen.consumeClick()) {
                fullscreen = !fullscreen;
                showMessage(I18n.get("minimap.ui.zoomLevel", 2.0 / zoomScale));
            }

            if (options.keyBindMinimapToggle.consumeClick()) {
                options.toggleBooleanValue(EnumOptionsMinimap.HIDE_MINIMAP);
            }

            if (options.waypointsAllowed) {
                if (options.keyBindWaypointMenu.consumeClick()) {
                    minecraft.gui.setScreen(new GuiWaypoints(null));
                }

                if (options.keyBindWaypoint.consumeClick()) {
                    boolean isFirst = waypointManager.getWaypoints().isEmpty();

                    float r = isFirst ? 0.0F : random.nextFloat();
                    float g = isFirst ? 1.0F : random.nextFloat();
                    float b = isFirst ? 0.0F : random.nextFloat();

                    TreeSet<DimensionContainer> dimensions = new TreeSet<>();
                    dimensions.add(VoxelConstants.getVoxelMapInstance().getDimensionManager().getDimensionContainerByWorld(VoxelConstants.getPlayer().level()));
                    double dimensionScale = VoxelConstants.getPlayer().level().dimensionType().coordinateScale();
                    Waypoint newWaypoint = new Waypoint(
                            "",
                            (int) (GameVariableAccessShim.xCoord() * dimensionScale),
                            (int) (GameVariableAccessShim.zCoord() * dimensionScale),
                            GameVariableAccessShim.yCoord(),
                            true,
                            r,
                            g,
                            b,
                            "",
                            waypointManager.getCurrentSubworldDescriptor(false),
                            dimensions
                    );
                    minecraft.gui.setScreen(new GuiAddWaypoint(null, newWaypoint, false));
                }
            }
        }

        lastScreen = minecraft.gui.screen();

        boolean enabled = options.minimapAllowed
                && !minecraft.gui.hud.isHidden()
                && (options.showUnderMenus || minecraft.gui.screen() == null)
                && !minecraft.debugEntries.isOverlayVisible();

        if (enabled) {
            drawMinimap(graphics);
        }
    }

    private void cycleZoom() {
        if (--zoom < 0) {
            zoom = map.getMaxZoom();
        }
        setZoomScale();
        showMessage(I18n.get("minimap.ui.zoomLevel", 2.0 / zoomScale));

        options.zoom = zoom;
        map.zoomChanged(zoom);
    }

    private void setZoomScale() {
        zoomScale = Math.pow(2.0, zoom) / 2.0;
        if (options.squareMap && options.rotates) {
            zoomScaleAdjusted = zoomScale / SQRT_2;
        } else {
            zoomScaleAdjusted = zoomScale;
        }
    }

    public void drawMinimap(GuiGraphicsExtractor graphics) {
        int scScaleOrig = 1;

        while (minecraft.getWindow().getWidth() / (scScaleOrig + 1) >= 320 && minecraft.getWindow().getHeight() / (scScaleOrig + 1) >= 240) {
            ++scScaleOrig;
        }

        int scScale = Math.max(1, scScaleOrig + (fullscreen ? 0 : options.sizeModifier));
        double scaledWidthD = (double) minecraft.getWindow().getWidth() / scScale;
        double scaledHeightD = (double) minecraft.getWindow().getHeight() / scScale;
        scWidth = Mth.ceil(scaledWidthD);
        scHeight = Mth.ceil(scaledHeightD);
        float scaleProj = (float) (scScale) / minecraft.getWindow().getGuiScale();

        int mapX;
        if (options.mapCorner != 0 && options.mapCorner != 3) {
            mapX = scWidth - 37;
        } else {
            mapX = 37;
        }

        int mapY;
        if (options.mapCorner != 0 && options.mapCorner != 1) {
            mapY = scHeight - 37;
        } else {
            mapY = 37;
        }

        float statusIconOffset = 0.0F;
        if (options.moveMapBelowStatusEffectIcons) {
            if (options.mapCorner == 1 && !VoxelConstants.getPlayer().getActiveEffects().isEmpty()) {
                for (MobEffectInstance statusEffectInstance : VoxelConstants.getPlayer().getActiveEffects()) {
                    if (statusEffectInstance.showIcon()) {
                        if (statusEffectInstance.getEffect().value().isBeneficial()) {
                            statusIconOffset = Math.max(statusIconOffset, 24.0F);
                        } else {
                            statusIconOffset = 50.0F;
                        }
                    }
                }
                int scHeight = minecraft.getWindow().getGuiScaledHeight();
                float resFactor = (float) scHeight / scHeight;
                mapY += (int) (statusIconOffset * resFactor);
            }
        }
        MapRenderer.statusIconOffset = statusIconOffset;

        context.updateVars(
                GameVariableAccessShim.xCoordDouble(),
                GameVariableAccessShim.yCoordDouble(),
                GameVariableAccessShim.zCoordDouble(),
                (options.rotates && !fullscreen) ? direction : -rotation,
                zoomScale,
                zoomScaleAdjusted
        );

        VoxelMapRenderTarget fullscreenTarget = RenderUtils.getFullscreenTarget();
        boolean lastShaderRendering = RenderUtils.setShaderRendering(false);
        RenderUtils.setupProjectionMatrix(hudProjection.getBuffer(RenderUtils.getGuiWidth(), RenderUtils.getGuiHeight()), ProjectionType.ORTHOGRAPHIC, -2000.0F);
        Matrix4fStack matrixStack = RenderUtils.getMatrixStack();
        matrixStack.pushMatrix();
        matrixStack.identity();
        try (SubmitPass pass = RenderUtils.createSubmitPass("VoxelMap HUD", fullscreenTarget, new Vector4f(0.0F, 0.0F, 0.0F, 0.0F), 0.0)) {
            if (!options.hide) {
                synchronized (map.getRenderLock()) {
                    if (imageChanged) {
                        imageChanged = false;
                        map.updateMapImage();
                        lastImageX = map.getPosX();
                        lastImageZ = map.getPosZ();
                    }
                }

                if (fullscreen) {
                    renderMapFull(pass, matrixStack, scWidth, scHeight, scaleProj);
                    drawArrow(pass, matrixStack, scWidth / 2, scHeight / 2, scaleProj);
                } else {
                    renderMap(pass, matrixStack, mapX, mapY, scScale, scaleProj);
                    drawArrow(pass, matrixStack, mapX, mapY, scaleProj);
                    drawDirections(pass, matrixStack, mapX, mapY, scaleProj);
                }
            }
            showCoords(pass, matrixStack, mapX, mapY, scaleProj);
        } finally {
            matrixStack.popMatrix();
            RenderUtils.restoreProjectionMatrix();
            RenderUtils.setShaderRendering(lastShaderRendering);
        }

        RenderUtils.blitToScreen(graphics, fullscreenTarget.getColorTextureView(), 0.0F, 0.0F, RenderUtils.getGuiWidth(), RenderUtils.getGuiHeight(), 0xFFFFFFFF);
    }


    private void renderMap(SubmitPass pass, Matrix4fStack matrixStack, int x, int y, int scScale, float scaleProj) {
        matrixStack.pushMatrix();
        try {
            matrixStack.scale(scaleProj, scaleProj, 1.0F);

            RenderUtils.setupProjectionMatrix(mapProjection.getBuffer(512.0f, 512.0F), ProjectionType.ORTHOGRAPHIC, -2000.0F);
            matrixStack.pushMatrix();
            try {
                matrixStack.identity();
                matrixStack.translate(256.0F, 256.0F, 0.0F);

                // Draw map, radar, etc.
                try (SubmitPass basePass = RenderUtils.createSubmitPass("VoxelMap Base Map", baseMapRenderTarget, new Vector4f(0.0F, 0.0F, 0.0F, 0.0F), 0.0)) {
                    float matrixScale = 512.0F / 64.0F;
                    float scale = 1.0F;
                    if (options.squareMap && options.rotates) {
                        scale = 1.4142F;
                    }
                    float multi = (float) (1.0 / zoomScale);
                    float percentX = (float) (GameVariableAccessShim.xCoordDouble() - lastImageX) * multi;
                    float percentY = (float) (GameVariableAccessShim.zCoordDouble() - lastImageZ) * multi;

                    matrixStack.pushMatrix();
                    if (!options.rotates) {
                        matrixStack.rotate(Axis.ZP.rotationDegrees(rotation));
                    } else {
                        matrixStack.rotate(Axis.ZP.rotationDegrees(-direction));
                    }
                    matrixStack.scale(scale, scale, 1.0F);
                    matrixStack.translate(-percentX * matrixScale, -percentY * matrixScale, 0.0F);

                    basePass.setRenderType(VoxelMapRenderTypes.GUI_TEXTURED_GEQUAL_DEPTH.apply(map.getMapImage()));
                    basePass.submitQuad(matrixStack, -256.0F, -256.0F, 0.0F, 512.0F, 512.0F, 0xFFFFFFFF);
                    basePass.nextDraw();
                    matrixStack.popMatrix();

                    if (VoxelConstants.getVoxelMapInstance().getRadar() != null) {
                        VoxelConstants.getVoxelMapInstance().getRadar().onTickInGame(context);
                        VoxelConstants.getVoxelMapInstance().getRadar().renderMapMobs(basePass, matrixStack, Contact.DisplayState.BELOW_FRAME, 0, 0, scScale, matrixScale);
                    }
                }

                // Masking the drawn map
                try (SubmitPass finalPass = RenderUtils.createSubmitPass("VoxelMap Final Map", finalMapRenderTarget, new Vector4f(0.0F, 0.0F, 0.0F, 0.0F), 0.0)) {
                    finalPass.setRenderType(VoxelMapRenderTypes.GUI_TEXTURED_ANY_DEPTH.apply(options.squareMap ? resourceSquareMapStencil : resourceRoundMapStencil));
                    finalPass.submitQuad(matrixStack, -256.0F, -256.0F, 0.0F, 512.0F, 512.0F, 0xFFFFFFFF);
                    finalPass.nextDraw();

                    finalPass.setRenderType(VoxelMapRenderTypes.GUI_TEXTURED_ANY_DEPTH_MASKED.apply(baseMapRenderTarget.textureId));
                    finalPass.submitBlit(matrixStack, -256.0F, -256.0F, 0.0F, 512.0F, 512.0F, 0xFFFFFFFF);
                }
            } finally {
                matrixStack.popMatrix();
                RenderUtils.restoreProjectionMatrix();
            }

            double guiScale = (double) minecraft.getWindow().getWidth() / scWidth;
            minTablistOffset = guiScale * 63;


            pass.setRenderType(VoxelMapRenderTypes.GUI_TEXTURED_GEQUAL_DEPTH.apply(finalMapRenderTarget.textureId));
            pass.submitBlit(matrixStack, x - 32.0F, y - 32.0F, MAP_IMAGE_DEPTH, 64.0F, 64.0F, 0xFFFFFFFF);
            pass.nextDraw();

            pass.setRenderType(VoxelMapRenderTypes.GUI_TEXTURED_GEQUAL_DEPTH.apply(options.squareMap ? resourceSquareMapFrame : resourceRoundMapFrame));
            pass.submitQuad(matrixStack, x - 32.0F, y - 32.0F, MAP_OVERLAY_DEPTH, 64.0F, 64.0F, 0xFFFFFFFF);
            pass.nextDraw();

            if (options.waypointsAllowed) {
                TextureAtlas textureAtlas = VoxelConstants.getVoxelMapInstance().getWaypointManager().getTextureAtlas();
                pass.setRenderType(VoxelMapRenderTypes.GUI_TEXTURED_GEQUAL_DEPTH.apply(textureAtlas.getIdentifier()));
                double lastXDouble = GameVariableAccessShim.xCoordDouble();
                double lastZDouble = GameVariableAccessShim.zCoordDouble();

                for (Waypoint waypoint : waypointManager.getWaypoints()) {
                    boolean isHighlighted = waypointManager.isHighlightedWaypoint(waypoint);
                    if (waypoint.isActive() || isHighlighted) {
                        double distanceSq = waypoint.getDistanceSqToEntity(minecraft.getCameraEntity());
                        boolean isOutOfRange = options.maxWaypointDisplayDistance >= 0 && distanceSq >= (options.maxWaypointDisplayDistance * options.maxWaypointDisplayDistance);
                        if (!isOutOfRange || isHighlighted) {
                            drawWaypoint(pass, matrixStack, x, y, waypoint, textureAtlas, null, isHighlighted, -1, lastXDouble, lastZDouble);
                        }
                    }
                }

                Waypoint highlightedPoint = waypointManager.getHighlightedWaypoint();
                if (highlightedPoint != null) {
                    drawWaypoint(pass, matrixStack, x, y, highlightedPoint, textureAtlas, textureAtlas.getAtlasSprite("marker/target"), true, 0xFFFF0000, lastXDouble, lastZDouble);
                }
            }

            pass.nextDraw();
        } finally {
            matrixStack.popMatrix();
        }
    }

    private void renderMapFull(SubmitPass pass, Matrix4fStack matrixStack, int scWidth, int scHeight, float scaleProj) {
        matrixStack.pushMatrix();
        matrixStack.scale(scaleProj, scaleProj, 1.0F);
        matrixStack.translate(scWidth / 2.0F, scHeight / 2.0F, 0.0F);
        matrixStack.rotate(Axis.ZP.rotationDegrees(rotation));
        matrixStack.translate(-(scWidth / 2.0F), -(scHeight / 2.0F), 0.0F);
        int left = scWidth / 2 - 128;
        int top = scHeight / 2 - 128;
        pass.setRenderType(VoxelMapRenderTypes.GUI_TEXTURED_GEQUAL_DEPTH.apply(map.getMapImage()));
        pass.submitQuad(matrixStack, left, top, MAP_IMAGE_DEPTH, 256.0F, 256.0F, 0xFFFFFFFF);
        pass.nextDraw();
        matrixStack.popMatrix();

        if (options.biomeOverlay != 0) {
            double factor = Math.pow(2.0, 3 - zoom);
            int minimumSize = (int) Math.pow(2.0, zoom);
            minimumSize *= minimumSize;
            ArrayList<AbstractMapData.BiomeLabel> labels = map.getMapData().getBiomeLabels();
            matrixStack.pushMatrix();

            for (AbstractMapData.BiomeLabel o : labels) {
                if (o.segmentSize > minimumSize) {
                    String name = o.name;
                    float x = (float) (o.x * factor);
                    float z = (float) (o.z * factor);
                    if (options.oldNorth) {
                        pass.submitCenteredText(matrixStack, name, (left + 256) - z, top + x - 3.0F, MAP_TEXT_DEPTH, 0xFFFFFFFF, true);
                    } else {
                        pass.submitCenteredText(matrixStack, name, left + x, top + z - 3.0F, MAP_TEXT_DEPTH, 0xFFFFFFFF, true);
                    }
                }
            }

            pass.nextDraw();
            matrixStack.popMatrix();
        }
    }

    private void drawWaypoint(SubmitPass pass, Matrix4fStack matrixStack, int x, int y, Waypoint waypoint, TextureAtlas textureAtlas, Sprite icon, boolean isHighlighted, int color, double baseX, double baseZ) {
        boolean uprightIcon = icon != null;

        double wayX = baseX - waypoint.getX() - 0.5;
        double wayY = baseZ - waypoint.getZ() - 0.5;
        float locate = (float) Math.toDegrees(Math.atan2(wayX, wayY));
        float hypot = (float) (Math.sqrt(wayX * wayX + wayY * wayY) / zoomScaleAdjusted);
        boolean far;
        if (options.rotates) {
            locate += direction;
        } else {
            locate -= rotation;
        }

        if (options.squareMap) {
            double radLocate = Math.toRadians(locate);
            double dispX = hypot * Math.cos(radLocate);
            double dispY = hypot * Math.sin(radLocate);
            far = Math.abs(dispX) > 28.5 || Math.abs(dispY) > 28.5;
            if (far) {
                hypot = (float) (hypot / Math.max(Math.abs(dispX), Math.abs(dispY)) * 30.0);
            }
        } else {
            far = hypot >= 31.0f;
            if (far) {
                hypot = 34.0f;
            }
        }

        int iconColor = color == -1 ? waypoint.getUnifiedColor(!waypoint.enabled && isHighlighted ? 0.3F : 1.0F) : color;
        if (far) {
            if (icon == null) {
                icon = textureAtlas.getAtlasSprite("marker/" + waypoint.imageSuffix);
                if (icon == textureAtlas.getMissingImage()) {
                    icon = textureAtlas.getAtlasSprite("marker/arrow");
                }
            }

            try {
                matrixStack.pushMatrix();
                matrixStack.translate(x, y, 0.0F);
                matrixStack.rotate(Axis.ZP.rotationDegrees(-locate));
                if (uprightIcon) {
                    matrixStack.translate(0.0F, -hypot, 0.0F);
                    matrixStack.rotate(Axis.ZP.rotationDegrees(locate));
                    matrixStack.translate(-x, -y, 0.0F);
                } else {
                    matrixStack.translate(-x, -y, 0.0F);
                    matrixStack.translate(0.0F, -hypot, 0.0F);
                }

                pass.submitQuad(matrixStack, icon, x - 4.0F, y - 4.0F, MAP_OVERLAY_DEPTH, 8.0F, 8.0F, iconColor);
            } catch (Exception var40) {
                showMessage("Error: marker overlay not found!");
            } finally {
                matrixStack.popMatrix();
            }
        } else {
            if (icon == null) {
                icon = textureAtlas.getAtlasSprite("selectable/" + waypoint.imageSuffix);
                if (icon == textureAtlas.getMissingImage()) {
                    icon = textureAtlas.getAtlasSprite(WaypointManager.fallbackIconLocation);
                }
            }

            try {
                matrixStack.pushMatrix();
                matrixStack.rotate(Axis.ZP.rotationDegrees(-locate));
                matrixStack.translate(0.0F, -hypot, 0.0F);
                matrixStack.rotate(Axis.ZP.rotationDegrees(locate));

                pass.submitQuad(matrixStack, icon, x - 4.0F, y - 4.0F, MAP_OVERLAY_DEPTH, 8.0F, 8.0F, iconColor);
            } catch (Exception var42) {
                showMessage("Error: waypoint overlay not found!");
            } finally {
                matrixStack.popMatrix();
            }
        }
    }

    private void drawArrow(SubmitPass pass, Matrix4fStack matrixStack, int x, int y, float scaleProj) {
        matrixStack.pushMatrix();
        matrixStack.scale(scaleProj, scaleProj, 1.0F);

        matrixStack.translate(x, y, 0.0F);
        matrixStack.rotate(Axis.ZP.rotationDegrees(options.rotates && !fullscreen ? 0.0F : direction + rotation));
        matrixStack.translate(-x, -y, 0.0F);

        pass.setRenderType(VoxelMapRenderTypes.GUI_TEXTURED_GEQUAL_DEPTH.apply(resourceArrow));
        pass.submitQuad(matrixStack, x - 4.0F, y - 4.0F, MAP_OVERLAY_DEPTH, 8.0F, 8.0F, 0xFFFFFFFF);
        pass.nextDraw();

        matrixStack.popMatrix();
    }

    private void drawDirections(SubmitPass pass, Matrix4fStack matrixStack, int x, int y, float scaleProj) {
        float scale = 0.5F;
        float rotate = -90.0F;
        if (options.rotates) {
            rotate -= direction + rotation;
        }

        float distance;
        if (options.squareMap) {
            if (options.rotates) {
                float tempdir = direction % 90.0F;
                tempdir = 45.0F - Math.abs(45.0F - tempdir);
                distance = 33.5F / scale / Mth.cos(Math.toRadians(tempdir));
            } else {
                distance = 33.5F / scale;
            }
        } else {
            distance = 32.5F / scale;
        }

        matrixStack.pushMatrix();
        matrixStack.scale(scaleProj, scaleProj, 1.0F);
        matrixStack.scale(scale, scale, 1.0F);

        // N
        matrixStack.pushMatrix();
        matrixStack.translate(distance * Mth.sin(-(rotate - 90.0F) * Mth.DEG_TO_RAD), distance * Mth.cos(-(rotate - 90.0F) * Mth.DEG_TO_RAD), 0.0F);
        pass.submitCenteredText(matrixStack, "N", x / scale, y / scale - 4.0F, MAP_TEXT_DEPTH, 0xFFFFFFFF, true);
        matrixStack.popMatrix();

        // E
        matrixStack.pushMatrix();
        matrixStack.translate(distance * Mth.sin(-(rotate) * Mth.DEG_TO_RAD), distance * Mth.cos(-(rotate) * Mth.DEG_TO_RAD), 0.0F);
        pass.submitCenteredText(matrixStack, "E", x / scale, y / scale - 4.0F, MAP_TEXT_DEPTH, 0xFFFFFFFF, true);
        matrixStack.popMatrix();

        // S
        matrixStack.pushMatrix();
        matrixStack.translate(distance * Mth.sin(-(rotate + 90.0F) * Mth.DEG_TO_RAD), distance * Mth.cos(-(rotate + 90.0F) * Mth.DEG_TO_RAD), 0.0F);
        pass.submitCenteredText(matrixStack, "S", x / scale, y / scale - 4.0F, MAP_TEXT_DEPTH, 0xFFFFFFFF, true);
        matrixStack.popMatrix();

        // W
        matrixStack.pushMatrix();
        matrixStack.translate(distance * Mth.sin(-(rotate + 180.0F) * Mth.DEG_TO_RAD), distance * Mth.cos(-(rotate + 180.0F) * Mth.DEG_TO_RAD), 0.0F);
        pass.submitCenteredText(matrixStack, "W", x / scale, y / scale - 4.0F, MAP_TEXT_DEPTH, 0xFFFFFFFF, true);
        matrixStack.popMatrix();

        pass.nextDraw();
        matrixStack.popMatrix();
    }

    private void showCoords(SubmitPass pass, Matrix4fStack matrixStack, int x, int y, float scaleProj) {
        if (!options.hide && !fullscreen) {
            int textStart;
            if (y > scHeight - 37 - 32 - 4 - 15) {
                textStart = y - 32 - 4 - 9;
            } else {
                textStart = y + 32 + 4;
            }
            int lineCount = 0;
            int lineHeight = 10;
            float scale = 0.5F;
            matrixStack.pushMatrix();
            matrixStack.scale(scale * scaleProj, scale * scaleProj, 1.0F);

            String coords;

            if (options.coordsMode == 1) {
                coords = signCoord(GameVariableAccessShim.xCoord()) + ", " + signCoord(GameVariableAccessShim.zCoord());
                pass.submitCenteredText(matrixStack, coords, x / scale, textStart / scale + lineHeight * lineCount, MAP_TEXT_DEPTH, 0xFFFFFFFF, true); // X, Z
                lineCount++;

                coords = signCoord(GameVariableAccessShim.yCoord());
                pass.submitCenteredText(matrixStack, coords, x / scale, textStart / scale + lineHeight * lineCount, MAP_TEXT_DEPTH, 0xFFFFFFFF, true); // Y
                lineCount++;
            } else if (options.coordsMode == 2) {
                coords = GameVariableAccessShim.xCoord() + ", " + GameVariableAccessShim.yCoord() + ", " + GameVariableAccessShim.zCoord();
                pass.submitCenteredText(matrixStack, coords, x / scale, textStart / scale + lineHeight * lineCount, MAP_TEXT_DEPTH, 0xFFFFFFFF, true); // X, Z
                lineCount++;
            }

            if (options.showBiome) {
                coords = BiomeRepository.getName(map.lastBiome);
                pass.submitCenteredText(matrixStack, coords, x / scale, textStart / scale + lineHeight * lineCount, MAP_TEXT_DEPTH, 0xFFFFFFFF, true); // BIOME
                lineCount++;
            }

            if (!message.isEmpty()) {
                pass.submitCenteredText(matrixStack, message, x / scale, textStart / scale + lineHeight * lineCount, MAP_TEXT_DEPTH, 0xFFFFFFFF, true); // WORLD NAME
                lineCount++;
            }

            matrixStack.popMatrix();
        } else {
            int textStart = 5;
            int lineCount = 0;
            int lineHeight = 10;

            if (options.coordsMode != 0) {
                int heading = (int) (direction + rotation);
                if (heading > 360) {
                    heading -= 360;
                }
                String ns = "";
                String ew = "";
                if (heading > 360 - 67.5 || heading <= 67.5) {
                    ns = "north";
                } else if (heading > 180 - 67.5 && heading <= 180 + 67.5) {
                    ns = "south";
                }
                if (heading > 90 - 67.5 && heading <= 90 + 67.5) {
                    ew = "east";
                } else if (heading > 270 - 67.5 && heading <= 270 + 67.5) {
                    ew = "west";
                }

                String direction = I18n.get("minimap.ui." + ns + ew);
                String stats = "(" + signCoord(GameVariableAccessShim.xCoord()) + ", " + signCoord(GameVariableAccessShim.yCoord()) + ", " + signCoord(GameVariableAccessShim.zCoord()) + ") " + heading + "' " + direction;
                pass.submitCenteredText(matrixStack, stats, (scWidth * scaleProj / 2.0F), textStart + lineHeight * lineCount, MAP_TEXT_DEPTH, 0xFFFFFFFF, true);
                lineCount++;
            }
            if (!message.isEmpty()) {
                pass.submitCenteredText(matrixStack, message, (scWidth * scaleProj / 2.0F), textStart + lineHeight * lineCount, MAP_TEXT_DEPTH, 0xFFFFFFFF, true);
                lineCount++;
            }
        }
    }

    private String signCoord(int paramInt1) {
        if (paramInt1 < 0) {
            return "-" + Math.abs(paramInt1);
        } else {
            return paramInt1 > 0 ? "+" + paramInt1 : Integer.toString(paramInt1);
        }
    }
}
