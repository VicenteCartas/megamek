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
 * Microsoft's Xbox Game Studios Game Content Usage Rules
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import megamek.client.ui.Messages;
import megamek.common.equipment.Mounted;
import megamek.common.interfaces.ILocationExposureStatus;
import megamek.common.units.Entity;
import megamek.common.units.Mek;

/**
 * The damage summary of the native damage editor's {@link UnitDamageForm}: every flagged item on the unit - a blown off or breached location, then each piece of
 * equipment that is destroyed, missing, breached, jammed, fired or mount-locked - with the location whose controls it
 * opens. The locations show one at a time, so without this list a gamemaster looking for the jammed gun would have to
 * page through every location to find it.
 */
public final class UnitDamageSummary {

    /**
     * One summary line.
     *
     * @param item     what is flagged: a location's name, or a piece of equipment's name with its location's
     *                 abbreviation
     * @param states   its states, in the order they are worth seeing
     * @param location the location whose controls the line opens
     */
    public record Line(String item, List<String> states, int location) {
        public Line {
            states = List.copyOf(states);
        }

        /** The states joined for reading, such as {@code Jammed, Fired}. */
        public String statesText() {
            return String.join(", ", states);
        }

        /** The whole line, {@code item: states}. */
        public String text() {
            return item + ": " + statesText();
        }
    }

    private UnitDamageSummary() {
    }

    /**
     * The summary of {@code entity}, listing only the equipment for which {@code listed} holds, so that every line has
     * a row to open; empty when nothing is flagged.
     */
    public static List<Line> lines(Entity entity, Predicate<Mounted<?>> listed) {
        List<Line> lines = new ArrayList<>();
        if (entity instanceof Mek) {
            for (int location = 0; location < entity.locations(); location++) {
                if (entity.isLocationBlownOff(location)) {
                    lines.add(new Line(entity.getLocationName(location),
                          List.of(Messages.getString("UnitEditorDialog.state.blownOff")), location));
                } else if (entity.getLocationStatus(location) == ILocationExposureStatus.BREACHED) {
                    lines.add(new Line(entity.getLocationName(location),
                          List.of(Messages.getString("UnitEditorDialog.state.breached")), location));
                }
            }
        }
        for (Mounted<?> mounted : entity.getEquipment()) {
            if (!listed.test(mounted)) {
                continue;
            }
            List<String> states = states(mounted);
            if (!states.isEmpty()) {
                String item = mounted.getName() + " (" + entity.getLocationAbbr(mounted.getLocation()) + ")";
                lines.add(new Line(item, states, mounted.getLocation()));
            }
        }
        return lines;
    }

    /**
     * The states a piece of equipment is in that keep it from working as built, in the order they are worth seeing:
     * destroyed, missing, breached, jammed, fired (one-shot weapons only), and a locked Directional Torso Mount. Empty
     * for equipment in working order.
     */
    public static List<String> states(Mounted<?> mounted) {
        List<String> states = new ArrayList<>();
        if (mounted.isDestroyed()) {
            states.add(Messages.getString("UnitEditorDialog.state.destroyed"));
        }
        if (mounted.isMissing()) {
            states.add(Messages.getString("UnitEditorDialog.state.missing"));
        }
        if (mounted.isBreached()) {
            states.add(Messages.getString("UnitEditorDialog.state.breached"));
        }
        if (mounted.isJammed()) {
            states.add(Messages.getString("UnitEditorDialog.state.jammed"));
        }
        if (mounted.isOneShot() && mounted.isFired()) {
            states.add(Messages.getString("UnitEditorDialog.state.fired"));
        }
        if (mounted.hasDirectionalTorsoMount() && mounted.isDirectionalMountLocked()) {
            states.add(Messages.getString("UnitEditorDialog.state.mountLocked"));
        }
        return states;
    }
}
