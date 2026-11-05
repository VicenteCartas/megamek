/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import megamek.client.ui.clientGUI.GUIPreferences;

/** The saved Display choices, applied identically by the full board view and an embedded map inspection. */
record BoardDisplaySettings(boolean normalQuality, float resolution, boolean antiAliasing, boolean highShadows,
      boolean specularAntiAliasing, boolean normalMaps, boolean terrainParallax, boolean tessellation,
      boolean ambientOcclusion, boolean depthCues, boolean vignette) {
    /** Normal quality leaves out the costly options; the saved choices stay, and High brings them back. */
    BoardDisplaySettings {
        if (normalQuality) {
            highShadows = false;
            specularAntiAliasing = false;
            terrainParallax = false;
            tessellation = false;
            ambientOcclusion = false;
        }
    }

    static BoardDisplaySettings capture(GUIPreferences preferences) {
        return new BoardDisplaySettings("NORMAL".equalsIgnoreCase(preferences.getBoardQuality()),
              preferences.getBoardRenderResolution(), "SMAA".equalsIgnoreCase(preferences.getBoardAntiAliasing()),
              "HIGH".equalsIgnoreCase(preferences.getBoardShadows()), preferences.getBoardSpecularAntiAliasing(),
              preferences.getBoardNormalMaps(), preferences.getBoardTerrainParallax(), preferences.getBoardTessellation(),
              preferences.getBoardAmbientOcclusion(), preferences.getBoardDepthCues(), preferences.getBoardVignette());
    }

    /** GPU thread, before terrain preparation: these flags also select the material programs to prepare. */
    void apply(GpuTerrain terrain, GpuAtmosphere atmosphere, GpuAmbientOcclusion occlusion,
          int pixelsWide, int pixelsHigh, boolean tactical) {
        terrain.setNormalQuality(normalQuality);
        GpuEnvironmentLighting.specularAntialiasing = specularAntiAliasing;
        terrain.setShadowScene(Math.max(pixelsWide, pixelsHigh) * resolution, highShadows);
        terrain.setNormalMaps(normalMaps);
        terrain.setTerrainParallax(terrainParallax);
        terrain.setTessellation(tessellation);
        occlusion.configure(terrain.environment(), ambientOcclusion);
        atmosphere.setAntiAliasing(antiAliasing);
        atmosphere.setDepthCues(depthCues && !tactical ? GpuDepthCues.STRENGTH : 0);
        atmosphere.setVignette(vignette);
    }
}
