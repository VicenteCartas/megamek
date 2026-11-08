/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;
import java.util.UUID;

import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.units.Targetable;
import megamek.common.units.UnitLocation;

/** Units, scenes and attacks for the playback tests. */
final class UnitPlaybackFixture {
    private UnitPlaybackFixture() { }

    static BoardScene.Combat attack(BoardScene.Unit attacker, BoardScene.Unit target, ResolvedAttack.Kind kind, boolean hit) {
        var result = new ResolvedAttack(UUID.randomUUID(), kind,
              new UnitLocation(attacker.id(), attacker.location().coords(), 0, 0, 0),
              new UnitLocation(target.id(), target.location().coords(), 0, 0, 0), Targetable.TYPE_ENTITY, 0, "ISMediumLaser", 4, hit);
        return new BoardScene.Combat(result, attacker, target, target.location());
    }

    static BoardScene scene(BoardScene.Unit... units) {
        var tile = new BoardScene.Tile(new Coords(0, 0), 0, -1, false, 0, null, null, List.of(), List.of());
        return new BoardScene(0, 1, 1, List.of(tile), List.of(units), List.of(), -1, "MOVEMENT", List.of());
    }

    static BoardScene.Unit unit(int id, int row) {
        return new BoardScene.Unit(id, -1, "Unit " + id, new BoardScene.Waypoint(new Coords(0, row), 0, 0),
              null, false, null, 2, false);
    }
}
