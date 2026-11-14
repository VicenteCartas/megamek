/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

/**
 * Board-view-owned presentation state, surviving replacement of its GPU window and its capture source: the game's
 * ground holder, so its fitted outline and grades are computed once per ground, its planting, and the capture's record
 * of the hexes that have lost what shaped them ({@link #captured}); the history itself is in each tile
 * ({@link BoardScene.Tile.Footing}). Only the GL thread updates the ground and the planting; workers read immutable
 * ground snapshots and the planting's explicitly shared construction data. No GL resources or authoritative game rules
 * live here.
 */
public final class GpuBoardState {
    private final BoardTiles.History captured = new BoardTiles.History();
    private BoardScene previous;
    private BoardGround ground;
    private BoardPlanting planting;
    private TerrainSettings settings;

    BoardScene update(BoardScene scene, TerrainSettings settings) {
        boolean other = previous == null || previous.boardId() != scene.boardId() || previous.width() != scene.width()
              || previous.height() != scene.height();
        ground = BoardGround.since(other ? null : ground, scene.tiles());
        if (other) {
            planting = new BoardPlanting(scene.withGround(ground));
        } else if (previous.tiles() != scene.tiles()) {
            planting.remove(scene);
        }
        // Placed poses are world positions: a change of the board's geometry places every model again.
        if (this.settings != null && !GpuTerrain.sameSettings(this.settings, settings)) { planting.poses.clear(); }
        this.settings = settings;
        previous = scene;
        return scene.withGround(ground);
    }

    BoardPlanting planting() { return planting; }

    /** The capture's record of what this view's hexes have lost; Swing thread only. */
    BoardTiles.History captured() { return captured; }
}
