/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.g3d.Material;

/**
 * A copy of a material that is not changed once it is first hashed, so that it hashes once: the copies a chunk snapshot
 * or a fall makes of drawn materials (GpuTerrain.Chunk#opaqueMaterials, TerrainFalls#fallMaterial), which the instanced
 * paths look up by value for every part (a city's tens of thousands). Whoever makes one finishes it before it is drawn.
 */
final class GpuFixedMaterial extends Material {
    private int hash;
    private boolean hashed;

    GpuFixedMaterial(Material copied) { super(copied); }

    @Override
    public int hashCode() {
        if (!hashed) {
            hash = super.hashCode();
            hashed = true;
        }
        return hash;
    }
}
