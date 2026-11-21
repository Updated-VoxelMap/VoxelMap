package com.mamiyaotaru.voxelmap;

import com.mamiyaotaru.voxelmap.interfaces.IChangeObserver;
import com.mamiyaotaru.voxelmap.interfaces.IReloadListener;
import com.mamiyaotaru.voxelmap.rendering.RenderUtils;
import com.mamiyaotaru.voxelmap.textures.DynamicMutableTexture;
import com.mamiyaotaru.voxelmap.textures.ScaledDynamicMutableTexture;
import com.mamiyaotaru.voxelmap.util.BiomeRepository;
import com.mamiyaotaru.voxelmap.util.BlockRepository;
import com.mamiyaotaru.voxelmap.util.CPULightmap;
import com.mamiyaotaru.voxelmap.util.ColorUtils;
import com.mamiyaotaru.voxelmap.util.FullMapData;
import com.mamiyaotaru.voxelmap.util.GameVariableAccessShim;
import com.mamiyaotaru.voxelmap.util.MapChunkCache;
import com.mamiyaotaru.voxelmap.util.MapUtils;
import com.mamiyaotaru.voxelmap.util.MutableBlockPos;
import com.mamiyaotaru.voxelmap.util.MutableBlockPosCache;
import java.util.ArrayList;
import java.util.Arrays;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.OutOfMemoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector4f;

public class Map implements Runnable, IChangeObserver, IReloadListener {
    private final Minecraft minecraft = Minecraft.getInstance();
    private final MapSettingsManager options;
    private final ColorManager colorManager;
    private final WaypointManager waypointManager;
    private final MapRenderer renderer;

    // Map Data
    private final int mapDataCount = 5;
    private final FullMapData[] mapData = new FullMapData[mapDataCount];
    private final MapChunkCache[] chunkCache = new MapChunkCache[mapDataCount];
    private DynamicMutableTexture[] mapImages;
    private Identifier[] mapResources;
    private final DynamicMutableTexture[] mapImagesFiltered = new DynamicMutableTexture[mapDataCount];
    private final DynamicMutableTexture[] mapImagesUnfiltered = new DynamicMutableTexture[mapDataCount];
    private final Identifier[] resourceMapImageFiltered = new Identifier[mapDataCount];
    private final Identifier[] resourceMapImageUnfiltered = new Identifier[mapDataCount];

    // Map Core Calculation
    private final Object renderLock = new Object();
    private Thread zCalc = createZCalcThread();
    private final Object zCalcLock = new Object();
    private int zCalcTicker;
    private volatile boolean zCalcRunning = true;

    private int zoom;
    private int lastX;
    private int lastZ;
    private int lastY;

    private final int availableProcessors = Runtime.getRuntime().availableProcessors();
    private final boolean multicore = availableProcessors > 1;
    private final boolean threading = multicore;


    private ClientLevel world;
    private int updateTimer;
    private volatile boolean doFullRender = true;

    // Map Terrain Calculation
    private final int heightMapResetHeight = multicore ? 2 : 5;
    private final int heightMapResetTime = multicore ? 300 : 3000;
    private int heightMapFudge;
    private boolean lastCaveRendering;

    // Map Player Calculation
    private volatile boolean zoomChanged;

    // Map Light Calculation
    private boolean needLightmapRefresh = true;
    private int tickWithLightChange;
    private final int[] lightmapColors = new int[256];
    private final int[] lastLightmapValues = new int[16];
    private boolean needSkyColor;
    private int lastSkyColor;
    private double lastGamma;
    private float lastSunBrightness;
    private float lastLightning;
    private float lastNightVision;
    private boolean lastPaused = true;
    private boolean lastAboveHorizon = true;
    public int lastBiome;
    private boolean worldIsEther;

    public Map() {
        options = VoxelConstants.getVoxelMapInstance().getMapOptions();
        colorManager = VoxelConstants.getVoxelMapInstance().getColorManager();
        waypointManager = VoxelConstants.getVoxelMapInstance().getWaypointManager();
        renderer = new MapRenderer(this);

        ArrayList<KeyMapping> tempBindings = new ArrayList<>();
        tempBindings.addAll(Arrays.asList(minecraft.options.keyMappings));
        tempBindings.addAll(Arrays.asList(options.keyBindings));
        minecraft.options.keyMappings = tempBindings.toArray(new KeyMapping[0]);

        zCalc.start();

        for (int i = 0; i < mapDataCount; i++) {
            resourceMapImageFiltered[i] = Identifier.fromNamespaceAndPath(VoxelConstants.MOD_ID, String.format("map/filtered/%s", i));
            resourceMapImageUnfiltered[i] = Identifier.fromNamespaceAndPath(VoxelConstants.MOD_ID, String.format("map/unfiltered/%s", i));

            int resolution = 1 << (i + 5); // 32, 64, ...
            int chunks = (1 << (i + 1)) + 1; //3, 5, ...

            mapData[i] = new FullMapData(resolution, resolution);
            chunkCache[i] = new MapChunkCache(chunks, chunks, this);

            mapImagesFiltered[i] = new DynamicMutableTexture(String.format("voxelmap-map-%s", resolution), resolution, resolution, true);
            mapImagesFiltered[i].setSampler(RenderUtils.getSampler(true, false));
            minecraft.getTextureManager().register(resourceMapImageFiltered[i], mapImagesFiltered[i]);

            mapImagesUnfiltered[i] = new ScaledDynamicMutableTexture(String.format("voxelmap-map-unfiltered-%s", resolution), resolution, resolution, true);
            mapImagesUnfiltered[i].setSampler(RenderUtils.getSampler(true, false));
            minecraft.getTextureManager().register(resourceMapImageUnfiltered[i], mapImagesUnfiltered[i]);

        }

        if (options.filtering) {
            mapImages = mapImagesFiltered;
            mapResources = resourceMapImageFiltered;
        } else {
            mapImages = mapImagesUnfiltered;
            mapResources = resourceMapImageUnfiltered;
        }
    }

    private Thread createZCalcThread() {
        Thread thread = new Thread(this, "Voxelmap LiveMap Calculation Thread");
        thread.setDaemon(true);
        return thread;
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        renderer.reloadTextures(resourceManager);
    }

    public void forceFullRender(boolean forceFullRender) {
        doFullRender = forceFullRender;
        VoxelConstants.getVoxelMapInstance().getSettingsAndLightingChangeNotifier().notifyOfChanges();
    }

    public void zoomChanged(int zoom) {
        this.zoom = Math.clamp(zoom, 0, getMaxZoom());
        zoomChanged = true;
        doFullRender = true;
    }

    public Object getRenderLock() {
        return renderLock;
    }

    public int getPosX() {
        return lastX;
    }

    public int getPosZ() {
        return lastZ;
    }

    public int getMaxZoom() {
        return mapDataCount - 1;
    }

    public FullMapData getMapData() {
        return mapData[zoom];
    }

    public void updateMapImage() {
        mapImages[zoom].upload();
    }

    public Identifier getMapImage() {
        return mapResources[zoom];
    }

    @Override
    public void run() {
        while (zCalcRunning) {
            if (world != null) {
                if (!options.hide && options.minimapAllowed) {
                    try {
                        mapCalc(doFullRender);
                        if (!doFullRender) {
                            MutableBlockPos blockPos = MutableBlockPosCache.get();
                            chunkCache[zoom].centerChunks(blockPos.withXYZ(lastX, 0, lastZ));
                            MutableBlockPosCache.release(blockPos);
                            chunkCache[zoom].checkIfChunksChanged();
                        }
                    } catch (Exception exception) {
                        VoxelConstants.getLogger().error("Voxelmap LiveMap Calculation Thread", exception);
                    }
                }

                doFullRender = zoomChanged;
                zoomChanged = false;
            }

            zCalcTicker = 0;
            synchronized (zCalcLock) {
                try {
                    zCalcLock.wait(0L);
                } catch (InterruptedException exception) {
                    if (zCalcRunning) {
                        VoxelConstants.getLogger().error("Voxelmap LiveMap Calculation Thread", exception);
                    }
                }
            }
        }

    }

    public void shutdown() {
        zCalcRunning = false;
        Thread thread = zCalc;
        synchronized (zCalcLock) {
            zCalcLock.notifyAll();
        }
        thread.interrupt();

        try {
            thread.join(5000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }

        if (thread.isAlive()) {
            VoxelConstants.getLogger().warn("Voxelmap LiveMap Calculation Thread did not stop within shutdown timeout");
        }
    }

    public void newWorld(ClientLevel world) {
        this.world = world;
        worldIsEther = world != null && world.dimension().identifier().toString().toLowerCase().contains("ether");
        mapData[zoom].blank();
        doFullRender = true;
        calculateCurrentLightAndSkyColor(true);
        VoxelConstants.getVoxelMapInstance().getSettingsAndLightingChangeNotifier().notifyOfChanges();
    }

    public void newWorldName() {
        String subworldName = waypointManager.getCurrentSubworldDescriptor(true);
        StringBuilder subworldNameBuilder = (new StringBuilder("§r")).append(I18n.get("worldmap.multiworld.newWorld")).append(":").append(" ");
        if (subworldName.isEmpty() && waypointManager.isMultiworld()) {
            subworldNameBuilder.append("???");
        } else if (!subworldName.isEmpty()) {
            subworldNameBuilder.append(subworldName);
        }

        renderer.showMessage(subworldNameBuilder.toString());
    }

    public void onTickInGame(GuiGraphicsExtractor graphics) {
        calculateCurrentLightAndSkyColor(false);
        if (threading) {
            if (zCalcRunning && !zCalc.isAlive()) {
                zCalc = createZCalcThread();
                zCalc.start();
                zCalcTicker = 0;
            }

            if (zCalcRunning && !(minecraft.gui.screen() instanceof DeathScreen) && !(minecraft.gui.screen() instanceof OutOfMemoryScreen)) {
                ++zCalcTicker;
                if (zCalcTicker > 2000) {
                    zCalcTicker = 0;
                    Exception ex = new Exception();
                    ex.setStackTrace(zCalc.getStackTrace());
                    DebugRenderState.print();
                    VoxelConstants.getLogger().error("Voxelmap LiveMap Calculation Thread is hanging?", ex);
                }
                synchronized (zCalcLock) {
                    zCalcLock.notifyAll();
                }
            }
        } else {
            if (!options.hide && options.minimapAllowed && world != null) {
                mapCalc(doFullRender);
                if (!doFullRender) {
                    MutableBlockPos blockPos = MutableBlockPosCache.get();
                    chunkCache[zoom].centerChunks(blockPos.withXYZ(lastX, 0, lastZ));
                    MutableBlockPosCache.release(blockPos);
                    chunkCache[zoom].checkIfChunksChanged();
                }
            }

            doFullRender = false;
        }
        checkForChanges();

        renderer.onTickInGame(graphics);

        updateTimer = updateTimer > 5000 ? 0 : updateTimer + 1;
    }

    public void calculateCurrentLightAndSkyColor(boolean force) {
        try {
            if (world != null) {
                if (force || (needLightmapRefresh && VoxelConstants.getElapsedTicks() != tickWithLightChange && !minecraft.isPaused() || options.realTimeTorches)) {
                    needLightmapRefresh = false;
                    CPULightmap lightmap = CPULightmap.getInstance();
                    lightmap.setup();
                    for (int blockLight = 0; blockLight < 16; blockLight++) {
                        for (int skyLight = 0; skyLight < 16; skyLight++) {
                            lightmapColors[blockLight + skyLight * 16] = lightmap.getLight(blockLight, skyLight);
                        }
                    }
                }

                boolean lightChanged = false;
                if (minecraft.options.gamma().get() != lastGamma) {
                    lightChanged = true;
                    lastGamma = minecraft.options.gamma().get();
                }

                float sunBrightness = 1 - (world.getSkyDarken() / 15f);
                if (Math.abs(lastSunBrightness - sunBrightness) > 0.01 || sunBrightness == 1.0 && sunBrightness != lastSunBrightness || sunBrightness == 0.0 && sunBrightness != lastSunBrightness) {
                    lightChanged = true;
                    needSkyColor = true;
                    lastSunBrightness = sunBrightness;
                }

                float nightVision = 0.0F;
                if (VoxelConstants.getPlayer().hasEffect(MobEffects.NIGHT_VISION)) {
                    int duration = VoxelConstants.getPlayer().getEffect(MobEffects.NIGHT_VISION).getDuration();
                    nightVision = duration > 200 ? 1.0F : 0.7F + Mth.sin((duration - 1.0F) * (float) Math.PI * 0.2F) * 0.3F;
                }

                if (lastNightVision != nightVision) {
                    lastNightVision = nightVision;
                    lightChanged = true;
                }

                int lastLightningBolt = world.getSkyFlashTime();
                if (lastLightning != lastLightningBolt) {
                    lastLightning = lastLightningBolt;
                    lightChanged = true;
                }

                if (lastPaused != minecraft.isPaused()) {
                    lastPaused = !lastPaused;
                    lightChanged = true;
                }

                boolean scheduledUpdate = (updateTimer - 50) % 50 == 0;
                if (lightChanged || scheduledUpdate) {
                    tickWithLightChange = VoxelConstants.getElapsedTicks();
                    needLightmapRefresh = true;
                }

                boolean aboveHorizon = VoxelConstants.getPlayer().getEyePosition(0.0F).y >= world.getLevelData().getHorizonHeight(world);
                if (worldIsEther) {
                    aboveHorizon = true;
                }

                if (aboveHorizon != lastAboveHorizon) {
                    needSkyColor = true;
                    lastAboveHorizon = aboveHorizon;
                }

                MutableBlockPos blockPos = MutableBlockPosCache.get();
                int biomeID = world.registryAccess().lookupOrThrow(Registries.BIOME).getId(world.getBiome(blockPos.withXYZ(GameVariableAccessShim.xCoord(), GameVariableAccessShim.yCoord(), GameVariableAccessShim.zCoord())).value());
                MutableBlockPosCache.release(blockPos);
                if (biomeID != lastBiome) {
                    needSkyColor = true;
                    lastBiome = biomeID;
                }

                if (needSkyColor || scheduledUpdate) {
                    colorManager.setSkyColor(getSkyColor());
                }
            }
        } catch (NullPointerException ignore) {

        }
    }

    private int getSkyColor() {
        needSkyColor = false;
        boolean aboveHorizon = lastAboveHorizon;
        Vector4f color = new Vector4f();
        minecraft.gameRenderer.fogRenderer.computeFogColor(minecraft.gameRenderer.mainCamera(), 0.0F, world, minecraft.options.renderDistance().get(), minecraft.gameRenderer.bossOverlayWorldDarkening(0.0F), color);
        int r = (int) (color.x * 255.0F);
        int g = (int) (color.y * 255.0F);
        int b = (int) (color.z * 255.0F);
        if (!aboveHorizon) {
            return 0x0A000000 | (r << 16) | (g << 8) | b;
        } else {
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    public int[] getLightmapArray() {
        return lightmapColors;
    }

    public int getLightmapColor(int skyLight, int blockLight) {
        return ARGB.toABGR(lightmapColors[blockLight + skyLight * 16]);
    }

    private void checkForChanges() {
        boolean changed = colorManager.checkForChanges();

        if (options.isChanged()) {
            if (options.filtering) {
                mapImages = mapImagesFiltered;
                mapResources = resourceMapImageFiltered;
            } else {
                mapImages = mapImagesUnfiltered;
                mapResources = resourceMapImageUnfiltered;
            }

            changed = true;
        }

        if (changed) {
            doFullRender = true;
            VoxelConstants.getVoxelMapInstance().getSettingsAndLightingChangeNotifier().notifyOfChanges();
        }
    }

    private void mapCalc(boolean full) {
        int currentX = GameVariableAccessShim.xCoord();
        int currentZ = GameVariableAccessShim.zCoord();
        int currentY = GameVariableAccessShim.yCoord();
        int offsetX = currentX - lastX;
        int offsetZ = currentZ - lastZ;
        int offsetY = currentY - lastY;
        int multi = (int) Math.pow(2.0, zoom);
        boolean needHeightAndID;
        boolean needHeightMap = false;
        boolean needLight = false;
        boolean skyColorChanged = false;
        int skyColor = colorManager.getAirColor();
        if (lastSkyColor != skyColor) {
            skyColorChanged = true;
            lastSkyColor = skyColor;
        }

        if (options.dynamicLighting) {
            int torchOffset = options.realTimeTorches ? 8 : 0;
            for (int t = 0; t < 16; ++t) {
                int newValue = getLightmapColor(t, torchOffset);
                if (lastLightmapValues[t] != newValue) {
                    needLight = true;
                    lastLightmapValues[t] = newValue;
                }
            }
        }

        if (offsetY != 0) {
            ++heightMapFudge;
        } else if (heightMapFudge != 0) {
            ++heightMapFudge;
        }

        if (full || Math.abs(offsetY) >= heightMapResetHeight || heightMapFudge > heightMapResetTime) {
            if (lastY != currentY) {
                needHeightMap = true;
            }

            lastY = currentY;
            heightMapFudge = 0;
        }

        if (Math.abs(offsetX) > 32 * multi || Math.abs(offsetZ) > 32 * multi) {
            full = true;
        }

        boolean caves = MapUtils.isUnderground(world, lastX, currentY, lastZ);

        if (lastCaveRendering != caves) {
            full = true;
        }
        lastCaveRendering = caves;

        needHeightAndID = needHeightMap && caves;
        int color24;
        synchronized (renderLock) {
            if (!full) {
                mapImages[zoom].moveY(offsetZ);
                mapImages[zoom].moveX(offsetX);
            }

            lastX = currentX;
            lastZ = currentZ;
        }
        int startX = currentX - 16 * multi;
        int startZ = currentZ - 16 * multi;
        if (!full) {
            mapData[zoom].moveZ(offsetZ);
            mapData[zoom].moveX(offsetX);

            for (int imageY = offsetZ > 0 ? 32 * multi - 1 : -offsetZ - 1; imageY >= (offsetZ > 0 ? 32 * multi - offsetZ : 0); --imageY) {
                for (int imageX = 0; imageX < 32 * multi; ++imageX) {
                    color24 = getPixelColor(true, true, true, true, caves, world, zoom, multi, startX, startZ, imageX, imageY);
                    mapImages[zoom].setRGB(imageX, imageY, color24);
                }
            }

            for (int imageY = 32 * multi - 1; imageY >= 0; --imageY) {
                for (int imageX = offsetX > 0 ? 32 * multi - offsetX : 0; imageX < (offsetX > 0 ? 32 * multi : -offsetX); ++imageX) {
                    color24 = getPixelColor(true, true, true, true, caves, world, zoom, multi, startX, startZ, imageX, imageY);
                    mapImages[zoom].setRGB(imageX, imageY, color24);
                }
            }
        }

        if (full || options.heightmap && needHeightMap || needHeightAndID || options.dynamicLighting && needLight || skyColorChanged) {
            for (int imageY = 32 * multi - 1; imageY >= 0; --imageY) {
                for (int imageX = 0; imageX < 32 * multi; ++imageX) {
                    color24 = getPixelColor(full, full || needHeightAndID, full, full || needLight || needHeightAndID, caves, world, zoom, multi, startX, startZ, imageX, imageY);
                    mapImages[zoom].setRGB(imageX, imageY, color24);
                }
            }
        }

        if ((full || offsetX != 0 || offsetZ != 0 || !renderer.lastFullscreen) && renderer.fullscreen && options.biomeOverlay != 0) {
            mapData[zoom].segmentBiomes();
            mapData[zoom].findCenterOfSegments(!options.oldNorth);
        }

        renderer.lastFullscreen = renderer.fullscreen;
        if (full || offsetX != 0 || offsetZ != 0 || needHeightMap || needLight || skyColorChanged) {
            renderer.imageChanged();
        }

        if (needLight || skyColorChanged) {
            VoxelConstants.getVoxelMapInstance().getSettingsAndLightingChangeNotifier().notifyOfChanges();
        }

    }

    @Override
    public void handleChangeInWorld(int chunkX, int chunkZ) {
        try {
            chunkCache[zoom].registerChangeAt(chunkX, chunkZ);
        } catch (Exception e) {
            VoxelConstants.getLogger().warn(e);
        }
    }

    @Override
    public void processChunk(LevelChunk chunk) {
        rectangleCalc(chunk.getPos().x() * 16, chunk.getPos().z() * 16, chunk.getPos().x() * 16 + 15, chunk.getPos().z() * 16 + 15);
    }

    private void rectangleCalc(int left, int top, int right, int bottom) {
        boolean caves = MapUtils.isUnderground(world, lastX, GameVariableAccessShim.yCoord(), lastZ);
        int startX = lastX;
        int startZ = lastZ;
        int multi = (int) Math.pow(2.0, zoom);
        startX -= 16 * multi;
        startZ -= 16 * multi;
        left = left - startX - 1;
        right = right - startX + 1;
        top = top - startZ - 1;
        bottom = bottom - startZ + 1;
        left = Math.max(0, left);
        right = Math.min(32 * multi - 1, right);
        top = Math.max(0, top);
        bottom = Math.min(32 * multi - 1, bottom);
        int color24;

        for (int imageY = bottom; imageY >= top; --imageY) {
            for (int imageX = left; imageX <= right; ++imageX) {
                color24 = getPixelColor(true, true, true, true, caves, world, zoom, multi, startX, startZ, imageX, imageY);
                mapImages[zoom].setRGB(imageX, imageY, color24);
            }
        }

        renderer.imageChanged();
    }

    private int getPixelColor(boolean needBiome, boolean needHeightAndID, boolean needTint, boolean needLight, boolean caves, ClientLevel world, int zoom, int multi, int startX, int startZ, int imageX, int imageY) {
        int surfaceHeight = Short.MIN_VALUE;
        int seafloorHeight = Short.MIN_VALUE;
        int transparentHeight = Short.MIN_VALUE;
        int foliageHeight = Short.MIN_VALUE;
        int surfaceColor;
        int seafloorColor = 0;
        int transparentColor = 0;
        int foliageColor = 0;
        BlockState transparentBlockState = BlockRepository.air.defaultBlockState();
        BlockState surfaceBlockState = null;
        BlockState foliageBlockState = BlockRepository.air.defaultBlockState();
        BlockState seafloorBlockState = BlockRepository.air.defaultBlockState();
        boolean surfaceBlockChangeForcedTint = false;
        boolean transparentBlockChangeForcedTint = false;
        boolean foliageBlockChangeForcedTint = false;
        boolean seafloorBlockChangeForcedTint = false;
        int surfaceBlockStateID;
        int transparentBlockStateID;
        int foliageBlockStateID;
        int seafloorBlockStateID;
        MutableBlockPos blockPos = MutableBlockPosCache.get();
        MutableBlockPos tempBlockPos = MutableBlockPosCache.get();
        blockPos.withXYZ(startX + imageX, 64, startZ + imageY);
        int color24;
        Biome biome;
        if (needBiome) {
            // int chunkX = SectionPos.blockToSectionCoord(blockPos.getX());
            // int chunkZ = SectionPos.blockToSectionCoord(blockPos.getZ());
            // if (world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null) { // TODO 1.21.5 testen
            biome = world.getBiome(blockPos).value();
            // } else {
            // biome = null;
            // }

            mapData[zoom].setBiome(imageX, imageY, biome);
        } else {
            biome = mapData[zoom].getBiome(imageX, imageY);
        }

        if (options.biomeOverlay == 1) {
            if (biome != null) {
                color24 = ARGB.toABGR(BiomeRepository.getBiomeColor(biome) | 0xFF000000);
            } else {
                color24 = 0;
            }

        } else {
            boolean solid = false;
            if (needHeightAndID) {
                if (!caves) {
                    LevelChunk chunk = world.getChunkAt(blockPos);
                    transparentHeight = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, blockPos.getX() & 15, blockPos.getZ() & 15) + 1;
                    transparentBlockState = world.getBlockState(blockPos.withXYZ(startX + imageX, transparentHeight - 1, startZ + imageY));
                    FluidState fluidState = transparentBlockState.getFluidState();
                    if (fluidState != Fluids.EMPTY.defaultFluidState()) {
                        transparentBlockState = fluidState.createLegacyBlock();
                    }

                    surfaceHeight = transparentHeight;
                    surfaceBlockState = transparentBlockState;
                    VoxelShape voxelShape;
                    boolean hasOpacity = surfaceBlockState.getLightDampening() > 0;
                    if (!hasOpacity && surfaceBlockState.canOcclude() && surfaceBlockState.useShapeForLightOcclusion()) {
                        voxelShape = surfaceBlockState.getFaceOcclusionShape(Direction.DOWN);
                        hasOpacity = Shapes.faceShapeOccludes(voxelShape, Shapes.empty());
                        voxelShape = surfaceBlockState.getFaceOcclusionShape(Direction.UP);
                        hasOpacity = hasOpacity || Shapes.faceShapeOccludes(Shapes.empty(), voxelShape);
                    }

                    while (!hasOpacity && surfaceHeight > world.getMinY()) {
                        foliageBlockState = surfaceBlockState;
                        --surfaceHeight;
                        surfaceBlockState = world.getBlockState(blockPos.withXYZ(startX + imageX, surfaceHeight - 1, startZ + imageY));
                        fluidState = surfaceBlockState.getFluidState();
                        if (fluidState != Fluids.EMPTY.defaultFluidState()) {
                            surfaceBlockState = fluidState.createLegacyBlock();
                        }

                        hasOpacity = surfaceBlockState.getLightDampening() > 0;
                        if (!hasOpacity && surfaceBlockState.canOcclude() && surfaceBlockState.useShapeForLightOcclusion()) {
                            voxelShape = surfaceBlockState.getFaceOcclusionShape(Direction.DOWN);
                            hasOpacity = Shapes.faceShapeOccludes(voxelShape, Shapes.empty());
                            voxelShape = surfaceBlockState.getFaceOcclusionShape(Direction.UP);
                            hasOpacity = hasOpacity || Shapes.faceShapeOccludes(Shapes.empty(), voxelShape);
                        }
                    }

                    if (surfaceHeight == transparentHeight) {
                        transparentHeight = Short.MIN_VALUE;
                        transparentBlockState = BlockRepository.air.defaultBlockState();
                        foliageBlockState = world.getBlockState(blockPos.withXYZ(startX + imageX, surfaceHeight, startZ + imageY));
                    }

                    if (foliageBlockState.getBlock() == Blocks.SNOW) {
                        surfaceBlockState = foliageBlockState;
                        foliageBlockState = BlockRepository.air.defaultBlockState();
                    }

                    if (foliageBlockState == transparentBlockState) {
                        foliageBlockState = BlockRepository.air.defaultBlockState();
                    }

                    if (foliageBlockState != null && !(foliageBlockState.getBlock() instanceof AirBlock)) {
                        foliageHeight = surfaceHeight + 1;
                    } else {
                        foliageHeight = Short.MIN_VALUE;
                    }

                    Block material = surfaceBlockState.getBlock();
                    if (material == Blocks.WATER || material == Blocks.ICE) {
                        seafloorHeight = surfaceHeight;

                        for (seafloorBlockState = world.getBlockState(blockPos.withXYZ(startX + imageX, surfaceHeight - 1, startZ + imageY)); seafloorBlockState.getLightDampening() < 5 && !(seafloorBlockState.getBlock() instanceof LeavesBlock)
                                && seafloorHeight > world.getMinY() + 1; seafloorBlockState = world.getBlockState(blockPos.withXYZ(startX + imageX, seafloorHeight - 1, startZ + imageY))) {
                            material = seafloorBlockState.getBlock();
                            if (transparentHeight == Short.MIN_VALUE && material != Blocks.ICE && material != Blocks.WATER && Heightmap.Types.MOTION_BLOCKING.isOpaque().test(seafloorBlockState)) {
                                transparentHeight = seafloorHeight;
                                transparentBlockState = seafloorBlockState;
                            }

                            if (foliageHeight == Short.MIN_VALUE && seafloorHeight != transparentHeight && transparentBlockState != seafloorBlockState && material != Blocks.ICE && material != Blocks.WATER && !(material instanceof AirBlock) && material != Blocks.BUBBLE_COLUMN) {
                                foliageHeight = seafloorHeight;
                                foliageBlockState = seafloorBlockState;
                            }

                            --seafloorHeight;
                        }

                        if (seafloorBlockState.getBlock() == Blocks.WATER) {
                            seafloorBlockState = BlockRepository.air.defaultBlockState();
                        }
                    }
                } else {
                    surfaceHeight = getNetherHeight(startX + imageX, startZ + imageY);
                    surfaceBlockState = world.getBlockState(blockPos.withXYZ(startX + imageX, surfaceHeight - 1, startZ + imageY));
                    surfaceBlockStateID = BlockRepository.getStateId(surfaceBlockState);
                    foliageHeight = surfaceHeight + 1;
                    blockPos.setXYZ(startX + imageX, foliageHeight - 1, startZ + imageY);
                    foliageBlockState = world.getBlockState(blockPos);
                    Block material = foliageBlockState.getBlock();
                    if (material != Blocks.SNOW && !(material instanceof AirBlock) && material != Blocks.LAVA && material != Blocks.WATER) {
                        foliageBlockStateID = BlockRepository.getStateId(foliageBlockState);
                    } else {
                        foliageHeight = Short.MIN_VALUE;
                    }
                }

                surfaceBlockStateID = BlockRepository.getStateId(surfaceBlockState);
                if (options.biomes && surfaceBlockState != mapData[zoom].getBlockstate(imageX, imageY)) {
                    surfaceBlockChangeForcedTint = true;
                }

                mapData[zoom].setHeight(imageX, imageY, surfaceHeight);
                mapData[zoom].setBlockstateID(imageX, imageY, surfaceBlockStateID);
                if (options.biomes && transparentBlockState != mapData[zoom].getTransparentBlockstate(imageX, imageY)) {
                    transparentBlockChangeForcedTint = true;
                }

                mapData[zoom].setTransparentHeight(imageX, imageY, transparentHeight);
                transparentBlockStateID = BlockRepository.getStateId(transparentBlockState);
                mapData[zoom].setTransparentBlockstateID(imageX, imageY, transparentBlockStateID);
                if (options.biomes && foliageBlockState != mapData[zoom].getFoliageBlockstate(imageX, imageY)) {
                    foliageBlockChangeForcedTint = true;
                }

                mapData[zoom].setFoliageHeight(imageX, imageY, foliageHeight);
                foliageBlockStateID = BlockRepository.getStateId(foliageBlockState);
                mapData[zoom].setFoliageBlockstateID(imageX, imageY, foliageBlockStateID);
                if (options.biomes && seafloorBlockState != mapData[zoom].getOceanFloorBlockstate(imageX, imageY)) {
                    seafloorBlockChangeForcedTint = true;
                }

                mapData[zoom].setOceanFloorHeight(imageX, imageY, seafloorHeight);
                seafloorBlockStateID = BlockRepository.getStateId(seafloorBlockState);
                mapData[zoom].setOceanFloorBlockstateID(imageX, imageY, seafloorBlockStateID);
            } else {
                surfaceHeight = mapData[zoom].getHeight(imageX, imageY);
                surfaceBlockStateID = mapData[zoom].getBlockstateID(imageX, imageY);
                surfaceBlockState = BlockRepository.getStateById(surfaceBlockStateID);
                transparentHeight = mapData[zoom].getTransparentHeight(imageX, imageY);
                transparentBlockStateID = mapData[zoom].getTransparentBlockstateID(imageX, imageY);
                transparentBlockState = BlockRepository.getStateById(transparentBlockStateID);
                foliageHeight = mapData[zoom].getFoliageHeight(imageX, imageY);
                foliageBlockStateID = mapData[zoom].getFoliageBlockstateID(imageX, imageY);
                foliageBlockState = BlockRepository.getStateById(foliageBlockStateID);
                seafloorHeight = mapData[zoom].getOceanFloorHeight(imageX, imageY);
                seafloorBlockStateID = mapData[zoom].getOceanFloorBlockstateID(imageX, imageY);
                seafloorBlockState = BlockRepository.getStateById(seafloorBlockStateID);
            }

            if (surfaceHeight == Short.MIN_VALUE) {
                surfaceHeight = lastY + 1;
                solid = true;
            }

            if (surfaceBlockState.getBlock() == Blocks.LAVA) {
                solid = false;
            }

            if (options.biomes) {
                surfaceColor = colorManager.getBlockColor(blockPos, surfaceBlockStateID, biome);
                int tint;
                if (!needTint && !surfaceBlockChangeForcedTint) {
                    tint = mapData[zoom].getBiomeTint(imageX, imageY);
                } else {
                    tint = colorManager.getBiomeTint(mapData[zoom], world, surfaceBlockState, surfaceBlockStateID, blockPos.withXYZ(startX + imageX, surfaceHeight - 1, startZ + imageY), tempBlockPos, startX, startZ);
                    mapData[zoom].setBiomeTint(imageX, imageY, tint);
                }

                if (tint != -1) {
                    surfaceColor = ColorUtils.colorMultiplier(surfaceColor, tint);
                }
            } else {
                surfaceColor = colorManager.getBlockColorWithDefaultTint(blockPos, surfaceBlockStateID);
            }

            surfaceColor = applyHeight(surfaceColor, caves, world, zoom, multi, startX, startZ, imageX, imageY, surfaceHeight, solid, 1);
            int light;
            if (needLight) {
                light = getLight(surfaceColor, surfaceBlockState, world, startX + imageX, startZ + imageY, surfaceHeight, solid);
                mapData[zoom].setLight(imageX, imageY, light);
            } else {
                light = mapData[zoom].getLight(imageX, imageY);
            }

            if (light == 0) {
                surfaceColor = 0;
            } else if (light != 255) {
                surfaceColor = ColorUtils.colorMultiplier(surfaceColor, light);
            }

            if (options.waterTransparency && seafloorHeight != Short.MIN_VALUE) {
                if (!options.biomes) {
                    seafloorColor = colorManager.getBlockColorWithDefaultTint(blockPos, seafloorBlockStateID);
                } else {
                    seafloorColor = colorManager.getBlockColor(blockPos, seafloorBlockStateID, biome);
                    int tint;
                    if (!needTint && !seafloorBlockChangeForcedTint) {
                        tint = mapData[zoom].getOceanFloorBiomeTint(imageX, imageY);
                    } else {
                        tint = colorManager.getBiomeTint(mapData[zoom], world, seafloorBlockState, seafloorBlockStateID, blockPos.withXYZ(startX + imageX, seafloorHeight - 1, startZ + imageY), tempBlockPos, startX, startZ);
                        mapData[zoom].setOceanFloorBiomeTint(imageX, imageY, tint);
                    }

                    if (tint != -1) {
                        seafloorColor = ColorUtils.colorMultiplier(seafloorColor, tint);
                    }
                }

                seafloorColor = applyHeight(seafloorColor, caves, world, zoom, multi, startX, startZ, imageX, imageY, seafloorHeight, solid, 0);
                int seafloorLight;
                if (needLight) {
                    seafloorLight = getLight(seafloorColor, seafloorBlockState, world, startX + imageX, startZ + imageY, seafloorHeight, solid);
                    blockPos.setXYZ(startX + imageX, seafloorHeight, startZ + imageY);
                    BlockState blockStateAbove = world.getBlockState(blockPos);
                    Block materialAbove = blockStateAbove.getBlock();
                    if (options.dynamicLighting && materialAbove == Blocks.ICE) {
                        int multiplier = minecraft.options.ambientOcclusion().get() ? 200 : 120;
                        seafloorLight = ColorUtils.colorMultiplier(seafloorLight, 0xFF000000 | multiplier << 16 | multiplier << 8 | multiplier);
                    }

                    mapData[zoom].setOceanFloorLight(imageX, imageY, seafloorLight);
                } else {
                    seafloorLight = mapData[zoom].getOceanFloorLight(imageX, imageY);
                }

                if (seafloorLight == 0) {
                    seafloorColor = 0;
                } else if (seafloorLight != 255) {
                    seafloorColor = ColorUtils.colorMultiplier(seafloorColor, seafloorLight);
                }
            }

            if (options.blockTransparency) {
                if (transparentHeight != Short.MIN_VALUE && transparentBlockState != null && transparentBlockState != BlockRepository.air.defaultBlockState()) {
                    if (options.biomes) {
                        transparentColor = colorManager.getBlockColor(blockPos, transparentBlockStateID, biome);
                        int tint;
                        if (!needTint && !transparentBlockChangeForcedTint) {
                            tint = mapData[zoom].getTransparentBiomeTint(imageX, imageY);
                        } else {
                            tint = colorManager.getBiomeTint(mapData[zoom], world, transparentBlockState, transparentBlockStateID, blockPos.withXYZ(startX + imageX, transparentHeight - 1, startZ + imageY), tempBlockPos, startX, startZ);
                            mapData[zoom].setTransparentBiomeTint(imageX, imageY, tint);
                        }

                        if (tint != -1) {
                            transparentColor = ColorUtils.colorMultiplier(transparentColor, tint);
                        }
                    } else {
                        transparentColor = colorManager.getBlockColorWithDefaultTint(blockPos, transparentBlockStateID);
                    }

                    transparentColor = applyHeight(transparentColor, caves, world, zoom, multi, startX, startZ, imageX, imageY, transparentHeight, solid, 3);
                    int transparentLight;
                    if (needLight) {
                        transparentLight = getLight(transparentColor, transparentBlockState, world, startX + imageX, startZ + imageY, transparentHeight, solid);
                        mapData[zoom].setTransparentLight(imageX, imageY, transparentLight);
                    } else {
                        transparentLight = mapData[zoom].getTransparentLight(imageX, imageY);
                    }

                    if (transparentLight == 0) {
                        transparentColor = 0;
                    } else if (transparentLight != 255) {
                        transparentColor = ColorUtils.colorMultiplier(transparentColor, transparentLight);
                    }
                }

                if (foliageHeight != Short.MIN_VALUE && foliageBlockState != null && foliageBlockState != BlockRepository.air.defaultBlockState()) {
                    if (!options.biomes) {
                        foliageColor = colorManager.getBlockColorWithDefaultTint(blockPos, foliageBlockStateID);
                    } else {
                        foliageColor = colorManager.getBlockColor(blockPos, foliageBlockStateID, biome);
                        int tint;
                        if (!needTint && !foliageBlockChangeForcedTint) {
                            tint = mapData[zoom].getFoliageBiomeTint(imageX, imageY);
                        } else {
                            tint = colorManager.getBiomeTint(mapData[zoom], world, foliageBlockState, foliageBlockStateID, blockPos.withXYZ(startX + imageX, foliageHeight - 1, startZ + imageY), tempBlockPos, startX, startZ);
                            mapData[zoom].setFoliageBiomeTint(imageX, imageY, tint);
                        }

                        if (tint != -1) {
                            foliageColor = ColorUtils.colorMultiplier(foliageColor, tint);
                        }
                    }

                    foliageColor = applyHeight(foliageColor, caves, world, zoom, multi, startX, startZ, imageX, imageY, foliageHeight, solid, 2);
                    int foliageLight;
                    if (needLight) {
                        foliageLight = getLight(foliageColor, foliageBlockState, world, startX + imageX, startZ + imageY, foliageHeight, solid);
                        mapData[zoom].setFoliageLight(imageX, imageY, foliageLight);
                    } else {
                        foliageLight = mapData[zoom].getFoliageLight(imageX, imageY);
                    }

                    if (foliageLight == 0) {
                        foliageColor = 0;
                    } else if (foliageLight != 255) {
                        foliageColor = ColorUtils.colorMultiplier(foliageColor, foliageLight);
                    }
                }
            }

            if (seafloorColor != 0 && seafloorHeight > Short.MIN_VALUE) {
                color24 = seafloorColor;
                if (foliageColor != 0 && foliageHeight <= surfaceHeight) {
                    color24 = ColorUtils.colorAdder(foliageColor, seafloorColor);
                }

                if (transparentColor != 0 && transparentHeight <= surfaceHeight) {
                    color24 = ColorUtils.colorAdder(transparentColor, color24);
                }

                color24 = ColorUtils.colorAdder(surfaceColor, color24);
            } else {
                color24 = surfaceColor;
            }

            if (foliageColor != 0 && foliageHeight > surfaceHeight) {
                color24 = ColorUtils.colorAdder(foliageColor, color24);
            }

            if (transparentColor != 0 && transparentHeight > surfaceHeight) {
                color24 = ColorUtils.colorAdder(transparentColor, color24);
            }

            if (options.biomeOverlay == 2) {
                int bc = 0;
                if (biome != null) {
                    bc = ARGB.toABGR(BiomeRepository.getBiomeColor(biome));
                }

                bc = 2130706432 | bc;
                color24 = ColorUtils.colorAdder(bc, color24);
            }

        }
        MutableBlockPosCache.release(blockPos);
        MutableBlockPosCache.release(tempBlockPos);
        return MapUtils.doSlimeAndGrid(color24, world, startX + imageX, startZ + imageY);
    }

    private int getBlockHeight(boolean caves, Level world, int x, int z) {
        MutableBlockPos blockPos = MutableBlockPosCache.get();
        int playerHeight = GameVariableAccessShim.yCoord();
        blockPos.setXYZ(x, playerHeight, z);
        LevelChunk chunk = (LevelChunk) world.getChunk(blockPos);
        int height = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, blockPos.getX() & 15, blockPos.getZ() & 15) + 1;
        BlockState blockState = world.getBlockState(blockPos.withXYZ(x, height - 1, z));
        FluidState fluidState = blockState.getFluidState();
        if (fluidState != Fluids.EMPTY.defaultFluidState()) {
            blockState = fluidState.createLegacyBlock();
        }

        while (blockState.getLightDampening() == 0 && height > world.getMinY()) {
            --height;
            blockState = world.getBlockState(blockPos.withXYZ(x, height - 1, z));
            fluidState = blockState.getFluidState();
            if (fluidState != Fluids.EMPTY.defaultFluidState()) {
                blockState = fluidState.createLegacyBlock();
            }
        }
        MutableBlockPosCache.release(blockPos);
        return caves && height > playerHeight ? getNetherHeight(x, z) : height;
    }

    private int getNetherHeight(int x, int z) {
        MutableBlockPos blockPos = MutableBlockPosCache.get();
        int y = lastY;
        blockPos.setXYZ(x, y, z);
        BlockState blockState = world.getBlockState(blockPos);
        if (blockState.getLightDampening() == 0 && blockState.getBlock() != Blocks.LAVA) {
            while (y > world.getMinY()) {
                --y;
                blockPos.setXYZ(x, y, z);
                blockState = world.getBlockState(blockPos);
                if (blockState.getLightDampening() > 0 || blockState.getBlock() == Blocks.LAVA) {
                    MutableBlockPosCache.release(blockPos);
                    return y + 1;
                }
            }
            MutableBlockPosCache.release(blockPos);
            return y;
        } else {
            while (y <= lastY + 10 && y < world.getMaxY()) {
                ++y;
                blockPos.setXYZ(x, y, z);
                blockState = world.getBlockState(blockPos);
                if (blockState.getLightDampening() == 0 && blockState.getBlock() != Blocks.LAVA) {
                    MutableBlockPosCache.release(blockPos);
                    return y;
                }
            }
            MutableBlockPosCache.release(blockPos);
            return Short.MIN_VALUE;
        }
    }

    private int getSeafloorHeight(Level world, int x, int z, int height) {
        MutableBlockPos blockPos = MutableBlockPosCache.get();
        for (BlockState blockState = world.getBlockState(blockPos.withXYZ(x, height - 1, z)); blockState.getLightDampening() < 5 && !(blockState.getBlock() instanceof LeavesBlock) && height > world.getMinY() + 1; blockState = world.getBlockState(blockPos.withXYZ(x, height - 1, z))) {
            --height;
        }
        MutableBlockPosCache.release(blockPos);
        return height;
    }

    private int getTransparentHeight(boolean caves, Level world, int x, int z, int height) {
        MutableBlockPos blockPos = MutableBlockPosCache.get();
        int transHeight;
        if (!caves) {
            transHeight = world.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, blockPos.withXYZ(x, height, z)).getY();
            if (transHeight <= height) {
                transHeight = Short.MIN_VALUE;
            }
        } else {
            transHeight = Short.MIN_VALUE;
        }

        BlockState blockState = world.getBlockState(blockPos.withXYZ(x, transHeight - 1, z));
        Block material = blockState.getBlock();
        if (transHeight == height + 1 && material == Blocks.SNOW) {
            transHeight = Short.MIN_VALUE;
        }

        if (material == Blocks.BARRIER) {
            ++transHeight;
            blockState = world.getBlockState(blockPos.withXYZ(x, transHeight - 1, z));
            material = blockState.getBlock();
            if (material instanceof AirBlock) {
                transHeight = Short.MIN_VALUE;
            }
        }
        MutableBlockPosCache.release(blockPos);
        return transHeight;
    }

    private int applyHeight(int color24, boolean caves, Level world, int zoom, int multi, int startX, int startZ, int imageX, int imageY, int height, boolean solid, int layer) {
        if (color24 != colorManager.getAirColor() && color24 != 0 && (options.heightmap || options.slopemap) && !solid) {
            int heightComp = -1;
            int diff;
            double sc = 0.0;
            if (!options.slopemap) {
                diff = height - lastY;
                sc = Math.log10(Math.abs(diff) / 8.0 + 1.0) / 1.8;
                if (diff < 0) {
                    sc = 0.0 - sc;
                }
            } else {
                if (imageX > 0 && imageY < 32 * multi - 1) {
                    if (layer == 0) {
                        heightComp = mapData[zoom].getOceanFloorHeight(imageX - 1, imageY + 1);
                    }

                    if (layer == 1) {
                        heightComp = mapData[zoom].getHeight(imageX - 1, imageY + 1);
                    }

                    if (layer == 2) {
                        heightComp = height;
                    }

                    if (layer == 3) {
                        heightComp = mapData[zoom].getTransparentHeight(imageX - 1, imageY + 1);
                        if (heightComp == Short.MIN_VALUE) {
                            Block block = BlockRepository.getStateById(mapData[zoom].getTransparentBlockstateID(imageX, imageY)).getBlock();
                            if (block == Blocks.GLASS || block instanceof StainedGlassBlock) {
                                heightComp = mapData[zoom].getHeight(imageX - 1, imageY + 1);
                            }
                        }
                    }
                } else {
                    if (layer == 0) {
                        int baseHeight = getBlockHeight(caves, world, startX + imageX - 1, startZ + imageY + 1);
                        heightComp = getSeafloorHeight(world, startX + imageX - 1, startZ + imageY + 1, baseHeight);
                    }

                    if (layer == 1) {
                        heightComp = getBlockHeight(caves, world, startX + imageX - 1, startZ + imageY + 1);
                    }

                    if (layer == 2) {
                        heightComp = height;
                    }

                    if (layer == 3) {
                        int baseHeight = getBlockHeight(caves, world, startX + imageX - 1, startZ + imageY + 1);
                        heightComp = getTransparentHeight(caves, world, startX + imageX - 1, startZ + imageY + 1, baseHeight);
                        if (heightComp == Short.MIN_VALUE) {
                            MutableBlockPos blockPos = MutableBlockPosCache.get();
                            BlockState blockState = world.getBlockState(blockPos.withXYZ(startX + imageX, height - 1, startZ + imageY));
                            MutableBlockPosCache.release(blockPos);
                            Block block = blockState.getBlock();
                            if (block == Blocks.GLASS || block instanceof StainedGlassBlock) {
                                heightComp = baseHeight;
                            }
                        }
                    }
                }

                if (heightComp == Short.MIN_VALUE) {
                    heightComp = height;
                }

                diff = heightComp - height;
                if (diff != 0) {
                    sc = diff > 0 ? 1.0 : -1.0;
                    sc /= 8.0;
                }

                if (options.heightmap) {
                    diff = height - lastY;
                    double heightsc = Math.log10(Math.abs(diff) / 8.0 + 1.0) / 3.0;
                    sc = diff > 0 ? sc + heightsc : sc - heightsc;
                }
            }

            int alpha = color24 >> 24 & 0xFF;
            int r = color24 >> 16 & 0xFF;
            int g = color24 >> 8 & 0xFF;
            int b = color24 & 0xFF;
            if (sc > 0.0) {
                r += (int) (sc * (255 - r));
                g += (int) (sc * (255 - g));
                b += (int) (sc * (255 - b));
            } else if (sc < 0.0) {
                sc = Math.abs(sc);
                r -= (int) (sc * r);
                g -= (int) (sc * g);
                b -= (int) (sc * b);
            }

            color24 = alpha * 16777216 + r * 65536 + g * 256 + b;
        }

        return color24;
    }

    private int getLight(int color24, BlockState blockState, Level world, int x, int z, int height, boolean solid) {
        int combinedLight = 0xffffffff;
        if (solid) {
            combinedLight = 0;
        } else if (color24 != colorManager.getAirColor() && color24 != 0 && options.dynamicLighting) {
            MutableBlockPos blockPos = MutableBlockPosCache.get();
            blockPos.setXYZ(x, Math.max(Math.min(height, world.getMaxY()), world.getMinY()), z);
            int blockLight = world.getBrightness(LightLayer.BLOCK, blockPos);
            int skyLight = world.getBrightness(LightLayer.SKY, blockPos);
            if (blockState.getBlock() == Blocks.LAVA || blockState.getBlock() == Blocks.MAGMA_BLOCK) {
                blockLight = 14;
            }
            MutableBlockPosCache.release(blockPos);
            combinedLight = getLightmapColor(skyLight, blockLight);
        }

        return ARGB.toABGR(combinedLight);
    }
}
