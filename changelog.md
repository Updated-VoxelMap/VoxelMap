## VoxelMap 26.3-1.16.13

- Fixed rendering on Retina / high-DPI displays. (Gamja)
- Fixed world map coordinates on Retina / high-DPI displays. (Gamja)
- Iris compatibility is now handled inside RenderUtils instead of a separate code path. (Gamja)
- Updated Gradle, Fabric, Forge, NeoForge and Fabric API dependencies.
- Fixed the Forge build: the GeckoLib and VoxelConfig dependencies were missing, so the Forge module no longer compiled.
- VoxelConfig is now bundled into the Forge jar, which previously failed to start because the classes were missing at runtime.

Last update: 2026-09-24T18:37:50
