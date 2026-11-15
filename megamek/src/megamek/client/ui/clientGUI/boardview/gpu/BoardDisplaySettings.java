/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import megamek.client.ui.clientGUI.GUIPreferences;

/**
 * The saved Display choices, applied identically by the full board view and an embedded map inspection: the quality
 * switches (a preset's or a Custom set, BoardSwitches) and the choices beside them.
 */
record BoardDisplaySettings(BoardSwitches switches, float resolution, boolean antiAliasing, boolean normalMaps,
      boolean depthCues, boolean vignette) {
    static BoardDisplaySettings capture(GUIPreferences preferences) {
        return new BoardDisplaySettings(BoardSwitches.saved(preferences), preferences.getBoardRenderResolution(),
              "SMAA".equalsIgnoreCase(preferences.getBoardAntiAliasing()), preferences.getBoardNormalMaps(),
              preferences.getBoardDepthCues(), preferences.getBoardVignette());
    }

    /** GPU thread, before terrain preparation: these flags also select the material programs to prepare. */
    void apply(GpuTerrain terrain, GpuAtmosphere atmosphere, GpuAmbientOcclusion occlusion,
          int pixelsWide, int pixelsHigh, boolean tactical) {
        terrain.setSwitches(switches);
        terrain.setShadowScene(Math.max(pixelsWide, pixelsHigh) * resolution, switches.on(BoardSwitch.HIGH_SHADOWS));
        terrain.setNormalMaps(normalMaps);
        terrain.setTerrainParallax(switches.on(BoardSwitch.TERRAIN_PARALLAX));
        terrain.setTessellation(switches.on(BoardSwitch.TESSELLATION));
        occlusion.configure(terrain.environment(), switches.on(BoardSwitch.AMBIENT_OCCLUSION));
        atmosphere.setAntiAliasing(antiAliasing);
        atmosphere.setDepthCues(depthCues && !tactical ? GpuDepthCues.STRENGTH : 0);
        atmosphere.setVignette(vignette);
    }
}
