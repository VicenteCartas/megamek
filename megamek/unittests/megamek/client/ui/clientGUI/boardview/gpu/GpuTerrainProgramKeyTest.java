/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Renderable;
import org.junit.jupiter.api.Test;

/**
 * A board without biomes compiles its terrain programs without the biome stage; a board with them keeps it. The
 * presence is a feature of the terrain environment's lighting (GpuEnvironmentLighting.flags), which the programs'
 * canRender and the family programs' lighting configuration compare, so the two boards never share a program.
 */
class GpuTerrainProgramKeyTest {
    @Test
    void boardsWithAndWithoutBiomesNeverShareAProgram() {
        var dry = BoardScenes.scene(coords -> BoardScenes.tile(coords, BoardScene.Surface.DIRT, 0, 0, 0));
        var marsh = BoardScenes.scene(coords -> coords.getX() < 4
              ? BoardScenes.tile(coords, BoardScene.Surface.DIRT, 0, 0, 0)
              : BoardScenes.tile(coords, BoardScene.Biome.MARSH, 0));
        assertFalse(GpuBiomeSurface.active(dry));
        assertTrue(GpuBiomeSurface.active(marsh));

        var environment = new Environment();
        environment.set(new GpuEnvironmentLighting());
        var sample = new Renderable();
        sample.environment = environment;
        GpuBiomeSurface.mark(environment, GpuBiomeSurface.active(dry));
        int dryFlags = GpuEnvironmentLighting.flags(environment);
        assertEquals("", GpuBiomeSurface.prefix(sample), "The dry board's programs leave the biome stage out");
        GpuBiomeSurface.mark(environment, GpuBiomeSurface.active(marsh));
        assertNotEquals(dryFlags, GpuEnvironmentLighting.flags(environment),
              "so the marsh board's programs never draw it");
        assertEquals("#define biomeMaskFlag\n", GpuBiomeSurface.prefix(sample));
        GpuBiomeSurface.mark(environment, GpuBiomeSurface.active(dry));
        assertEquals(dryFlags, GpuEnvironmentLighting.flags(environment),
              "A board that loses its biomes returns to the dry programs");
    }
}
