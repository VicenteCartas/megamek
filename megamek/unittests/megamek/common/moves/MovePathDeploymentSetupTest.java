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
package megamek.common.moves;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.units.BipedMek;
import megamek.common.units.DeploymentSetup;
import megamek.common.units.DeploymentSetup.Stance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A walk-on unit's move carries the setup it chose before it entered to the server (Core Rulebook p. 224). */
class MovePathDeploymentSetupTest {

    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    private static MovePath pathWithSetup(DeploymentSetup setup) {
        Game game = new Game();
        BipedMek mek = new BipedMek();
        mek.setGame(game);
        MovePath path = new MovePath(game, mek);
        path.setDeploymentSetup(setup);
        return path;
    }

    @Test
    void copyKeepsTheSetup() {
        DeploymentSetup setup = DeploymentSetup.of(new Game(), new BipedMek(), null).withStance(Stance.PRONE);

        assertEquals(setup, pathWithSetup(setup).clone().getDeploymentSetup());
    }

    @Test
    void setupTravelsWithThePath() throws IOException, ClassNotFoundException {
        DeploymentSetup setup = DeploymentSetup.of(new Game(), new BipedMek(), null).withStance(Stance.HULL_DOWN);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(pathWithSetup(setup));
        }

        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertEquals(setup, ((MovePath) in.readObject()).getDeploymentSetup());
        }
    }
}
