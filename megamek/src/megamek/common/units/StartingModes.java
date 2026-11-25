/*
 * Copyright (c) 2026 - The MegaMek Team. All Rights Reserved.
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
package megamek.common.units;

import java.util.List;

import jakarta.annotation.Nullable;

/**
 * The modes a unit that converts may start the game in: a LAM's Mek, AirMek (standard LAMs only) or Fighter, a QuadVee's
 * Mek or Vehicle. The lobby offers them, and deployment lets the player change the choice before the unit first deploys.
 */
public final class StartingModes {

    private StartingModes() { }

    /** The conversion modes {@code entity} may start in, empty for a unit that has no choice. */
    public static List<Integer> of(Entity entity) {
        if (entity instanceof LandAirMek lam) {
            return lam.getLAMType() == LandAirMek.LAM_BIMODAL
                  ? List.of(LandAirMek.CONV_MODE_MEK, LandAirMek.CONV_MODE_FIGHTER)
                  : List.of(LandAirMek.CONV_MODE_MEK, LandAirMek.CONV_MODE_AIR_MEK, LandAirMek.CONV_MODE_FIGHTER);
        } else if (entity instanceof QuadVee) {
            return List.of(QuadVee.CONV_MODE_MEK, QuadVee.CONV_MODE_VEHICLE);
        }
        return List.of();
    }

    /**
     * Puts the undeployed {@code entity} into the starting {@code mode}, on the ground: a Fighter landed, an AirMek or
     * Mek at elevation 0, so its deployment then picks the height. Does nothing for a mode {@link #of} doesn't offer.
     *
     * @return whether {@code mode} is one the unit may start in
     */
    public static boolean apply(Entity entity, int mode) {
        if (!of(entity).contains(mode)) {
            return false;
        }
        if (mode == entity.getConversionMode()) {
            return true;
        }
        if (entity instanceof LandAirMek lam) {
            // An airborne fighter keeps its altitude through a conversion, and any altitude makes a unit airborne
            lam.setAltitude(0);
            lam.setConversionMode(mode);
            if (mode == LandAirMek.CONV_MODE_FIGHTER) {
                lam.land();
                lam.setProne(false);
                lam.setHullDown(false);
            } else {
                lam.setElevation(0);
            }
        } else {
            entity.setConversionMode(mode);
        }
        return true;
    }

    /**
     * Puts {@code entity}, which never deployed, into the starting {@code mode} as {@link #apply} does.
     *
     * @return what restores the unit's earlier mode and height, or null when nothing changed (the unit deployed
     *       before, is in that mode already, or may not start in it)
     */
    public static @Nullable Runnable choose(Entity entity, int mode) {
        if (!entity.wasNeverDeployed() || (mode == entity.getConversionMode()) || !of(entity).contains(mode)) {
            return null;
        }
        int previousMode = entity.getConversionMode();
        int previousAltitude = entity.getAltitude();
        int previousElevation = entity.getElevation();
        apply(entity, mode);
        return () -> {
            apply(entity, previousMode);
            if ((entity instanceof IAero aero) && entity.isAero() && (previousAltitude > 0)) {
                aero.liftOff(previousAltitude);
            } else if (!entity.isAero()) {
                entity.setElevation(previousElevation);
            }
        };
    }

    /**
     * The starting mode in which {@code entity} moves in {@code movementMode}: a walk-on unit's entry step carries the
     * movement mode it enters the map in, which tells its starting mode.
     *
     * @return the mode, or null when the unit has no choice or no starting mode moves that way
     */
    public static @Nullable Integer forMovementMode(Entity entity, EntityMovementMode movementMode) {
        Integer mode = null;
        if (entity instanceof LandAirMek lam) {
            mode = lam.getConversionModeFor(movementMode);
        } else if (entity instanceof QuadVee) {
            mode = movementMode.isTrackedOrWheeled() ? QuadVee.CONV_MODE_VEHICLE : QuadVee.CONV_MODE_MEK;
        }
        return (mode != null) && of(entity).contains(mode) ? mode : null;
    }
}
