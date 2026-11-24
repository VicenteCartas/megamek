/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.ui.panels.phaseDisplay;

import java.util.List;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.game.Game;
import megamek.common.units.DeploymentSetup;
import megamek.common.units.Entity;

/**
 * The starting setup (stance, power, heat sinks, activated equipment; Core Rulebook p. 224) the player is choosing for
 * the unit deploying now, whether it is placed or walks on: which unit, and the setup it had when picked. A choice
 * takes effect on the unit at once so the board and unit card show it; picking another unit puts the last one back if
 * it has not deployed, and deploying sends what differs.
 */
final class DeploymentSetupChoice {

    /** What a unit without a choice, or one not choosing now, may choose: nothing. */
    static final DeploymentSetup.Options NONE = new DeploymentSetup.Options(List.of(), false, 0, List.of());

    private final ClientGUI clientgui;
    private final Game game;
    private int unit = Entity.NONE;
    private @Nullable DeploymentSetup baseline;

    DeploymentSetupChoice(ClientGUI clientgui, Game game) {
        this.clientgui = clientgui;
        this.game = game;
    }

    /** Starts choosing for {@code entity}, putting the unit picked before back if it has not deployed. */
    void pick(Entity entity) {
        if (entity.getId() == unit) {
            return;
        }
        restore();
        unit = entity.getId();
        baseline = DeploymentSetup.of(game, entity, hex(entity));
    }

    /** Whether {@code entity} is the unit whose setup is being chosen. */
    boolean isPicked(@Nullable Entity entity) {
        return (entity != null) && (entity.getId() == unit);
    }

    /** What {@code entity} may choose in the hex it is placed in (none while it isn't placed). */
    DeploymentSetup.Options options(Entity entity) {
        return isPicked(entity) ? DeploymentSetup.options(game, entity, hex(entity)) : NONE;
    }

    /** How {@code entity} starts, as the player set it so far. */
    DeploymentSetup current(Entity entity) {
        return DeploymentSetup.of(game, entity, hex(entity));
    }

    /** How {@code entity} started out before the player changed its setup; null when it isn't the picked unit. */
    @Nullable DeploymentSetup baseline(@Nullable Entity entity) {
        return isPicked(entity) ? baseline : null;
    }

    /**
     * Puts {@code entity} into {@code setup}. A setup it may not choose is ignored; one that would put two ECM suites
     * on, or stealth armor without one, is refused with a toast.
     */
    void set(Entity entity, DeploymentSetup setup) {
        Hex hex = hex(entity);
        if (!isPicked(entity) || !setup.isAllowed(game, entity, hex)) {
            return;
        }
        DeploymentSetup.Conflict before = DeploymentSetup.conflict(entity);
        Runnable undo = setup.apply(game, entity, hex);
        DeploymentSetup.Conflict after = DeploymentSetup.conflict(entity);
        if ((after != null) && (after != before)) {
            undo.run();
            clientgui.addToast(ToastLevel.WARNING, Messages.getString("DeploymentDisplay.setupConflict." + after),
                  entity);
            return;
        }
        redraw(entity);
    }

    /** The setup to send as {@code entity} deploys: null when it starts as it was when picked. */
    @Nullable DeploymentSetup changed(Entity entity) {
        DeploymentSetup current = current(entity);
        return !isPicked(entity) || current.equals(baseline) ? null : current;
    }

    /** Ends the choice without putting the unit back: it deployed with its setup. */
    void forget() {
        unit = Entity.NONE;
        baseline = null;
    }

    /** Puts the unit last picked, if still undeployed, back into the setup it had when picked. */
    void restore() {
        Entity previous = (unit == Entity.NONE) ? null : game.getEntity(unit);
        if ((previous != null) && (baseline != null) && !previous.isDeployed()
              && !baseline.equals(DeploymentSetup.of(game, previous, hex(previous)))) {
            baseline.restore(game, previous);
            redraw(previous);
        }
    }

    private void redraw(Entity entity) {
        clientgui.boardStates().forEach(BoardClientState::redrawAllEntities);
        clientgui.getUnitDisplayState().displayEntity(entity);
        clientgui.updateFiringArc(entity);
        clientgui.boardStates().forEach(BoardClientState::repaint);
    }

    /** The hex the unit is placed in, or null while it isn't placed. */
    private @Nullable Hex hex(Entity entity) {
        Board board = (entity.getPosition() == null) ? null : game.getBoard(entity.getBoardId());
        return (board == null) || !board.contains(entity.getPosition()) ? null : board.getHex(entity.getPosition());
    }
}
