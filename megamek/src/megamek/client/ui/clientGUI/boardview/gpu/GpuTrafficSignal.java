/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.g3d.Material;

/** Selects the out-of-service signals' amber lenses for the shared model-light controls. */
final class GpuTrafficSignal {
    private GpuTrafficSignal() { }

    /** Called on a placement's own materials, never on the shared GLB model. */
    static void mark(String asset, Material material) {
        if ((asset.equals("scenery/road/traffic-signal") || asset.equals("scenery/road/traffic-signal-gantry"))
              && material.id.equals("Amber") && !material.has(GpuModelLight.TYPE)) {
            // One second per cycle, half on and half off, with a dark lens between flashes.
            material.set(new GpuModelLight(1, 1, .06f));
        }
    }
}
