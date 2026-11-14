/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import megamek.common.board.Coords;
import megamek.common.board.RailRoute;

/** What a hex's reeds are planted by: the hexes around it, as their placement reads them. */
final class BoardVegetation {
    record Site(int elevation, BoardScene.Surface family, BoardScene.Biome biome, boolean natural,
          boolean liquid, int waterDepth, boolean frozen, int exits, BoardRoad.Kind road, int rails, RailRoute.Corridor corridor) { }
    record Key(List<Site> sites) { }

    private BoardVegetation() { }

    static Key key(BoardScene scene, BoardScene.Tile owner) {
        // A root on a shared slope can lie in a neighbour, whose cover samples its own neighbours.
        // Distant shoreline inputs have already been resolved into the published support triangles.
        List<Site> sites = new ArrayList<>(19);
        Coords at = owner.coords();
        for (int x = at.getX() - 2; x <= at.getX() + 2; x++) {
            for (int y = at.getY() - 3; y <= at.getY() + 3; y++) {
                if (at.distance(x, y) > 2) { continue; }
                var tile = scene.tile(new Coords(x, y));
                sites.add(tile == null ? null : new Site(tile.elevation(), tile.surface(), BoardBiome.kind(tile),
                      BoardSurfaceBlend.natural(tile), tile.liquid().present(), tile.waterDepth(), tile.frozen(),
                      tile.wayExits(), tile.road(), tile.railExits(), tile.corridor()));
            }
        }
        return new Key(Collections.unmodifiableList(sites));
    }

}
