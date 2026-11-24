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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Map;

import megamek.common.Hex;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.EquipmentTypeLookup;
import megamek.common.equipment.Mounted;
import megamek.common.exceptions.LocationFullException;
import megamek.common.game.Game;
import megamek.common.net.marshalling.SanityInputFilter;
import megamek.common.options.OptionsConstants;
import megamek.common.units.DeploymentSetup.Stance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The choices a unit makes as it deploys (Core Rulebook p. 224): which it is offered, and that applying them takes
 * effect at once rather than at the next End Phase.
 */
class DeploymentSetupTest {

    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    private static Game game(String... options) {
        Game game = new Game();
        for (String option : options) {
            game.getOptions().getOption(option).setValue(true);
        }
        return game;
    }

    private static <T extends Entity> T join(Game game, T entity) {
        entity.setGame(game);
        return entity;
    }

    private static Mounted<?> add(Entity entity, String internalName, int location) throws LocationFullException {
        EquipmentType type = EquipmentType.get(internalName);
        assertNotNull(type, internalName);
        return entity.addEquipment(type, location);
    }

    private static Hex fortified() {
        Hex hex = new Hex();
        hex.addTerrain(new Terrain(Terrains.FORTIFIED, 1));
        return hex;
    }

    @Test
    void mekMayStartStandingProneOrHullDown() {
        Game game = game();
        BipedMek mek = join(game, new BipedMek());

        assertEquals(List.of(Stance.STANDING, Stance.PRONE, Stance.HULL_DOWN),
              DeploymentSetup.options(game, mek, null).stances());

        Runnable restore = DeploymentSetup.of(game, mek, null).withStance(Stance.PRONE).apply(game, mek, null);
        assertTrue(mek.isProne());
        assertEquals(Stance.PRONE, DeploymentSetup.of(game, mek, null).stance());

        restore.run();
        assertFalse(mek.isProne());
    }

    @Test
    void lamInFighterModeHasNoStance() {
        Game game = game();
        LandAirMek lam = join(game, new LandAirMek(Mek.GYRO_STANDARD, Mek.COCKPIT_STANDARD, LandAirMek.LAM_STANDARD));
        lam.setConversionMode(LandAirMek.CONV_MODE_FIGHTER);

        assertTrue(DeploymentSetup.options(game, lam, null).stances().isEmpty());
    }

    @Test
    void infantryDigsInOnlyWithTheOptionAndOutsideFortifications() {
        Infantry infantry = new ConvInfantry();
        Game plain = game();
        Game digIn = game(OptionsConstants.ADVANCED_TAC_OPS_DIG_IN);

        assertTrue(DeploymentSetup.options(plain, join(plain, infantry), null).stances().isEmpty());
        assertEquals(List.of(Stance.STANDING, Stance.DUG_IN),
              DeploymentSetup.options(digIn, join(digIn, infantry), null).stances());
        assertTrue(DeploymentSetup.options(digIn, infantry, fortified()).stances().isEmpty(),
              "A fortified hex already protects the platoon");

        DeploymentSetup.of(digIn, infantry, null).withStance(Stance.DUG_IN).apply(digIn, infantry, null);
        assertEquals(Infantry.DUG_IN_COMPLETE, infantry.getDugIn());
    }

    @Test
    void tankGoesHullDownOnlyInAFortifiedHex() {
        Game game = game(OptionsConstants.ADVANCED_GROUND_MOVEMENT_TAC_OPS_HULL_DOWN);
        Tank tank = join(game, new Tank());
        tank.setMovementMode(EntityMovementMode.TRACKED);

        assertTrue(DeploymentSetup.options(game, tank, new Hex()).stances().isEmpty());
        assertEquals(List.of(Stance.STANDING, Stance.HULL_DOWN),
              DeploymentSetup.options(game, tank, fortified()).stances());
        assertTrue(DeploymentSetup.of(game, tank, null).withStance(Stance.HULL_DOWN)
              .isAllowed(game, tank, fortified()));
    }

    @Test
    void shutdownNeedsTheOption() {
        Game plain = game();
        Game shutdown = game(OptionsConstants.RPG_BEGIN_SHUTDOWN);
        BipedMek mek = new BipedMek();

        assertFalse(DeploymentSetup.options(plain, join(plain, mek), null).shutdown());
        assertTrue(DeploymentSetup.options(shutdown, join(shutdown, mek), null).shutdown());

        Runnable restore = DeploymentSetup.of(shutdown, mek, null).withShutdown(true).apply(shutdown, mek, null);
        assertTrue(mek.isShutDown());
        restore.run();
        assertFalse(mek.isShutDown());
        assertFalse(mek.isStartupThisPhase(), "Undoing a choice is not a startup roll");
    }

    @Test
    void heatSinksSwitchOffAtOnce() throws LocationFullException {
        Game game = game();
        BipedMek mek = join(game, new BipedMek());
        for (int sink = 0; sink < 10; sink++) {
            add(mek, EquipmentTypeLookup.SINGLE_HS, Entity.LOC_NONE);
        }

        DeploymentSetup.Options options = DeploymentSetup.options(game, mek, null);
        assertEquals(10, options.sinks());
        assertTrue(options.equipment().isEmpty(), "Heat sinks are set by their count, not one by one");

        DeploymentSetup.of(game, mek, null).withActiveSinks(6).apply(game, mek, null);
        assertEquals(6, mek.getActiveSinks(), "The unit starts with these sinks, no End Phase to wait for");
        assertEquals(6, mek.getHeatCapacity(false, false));
    }

    @Test
    void ppcCapacitorStartsCharged() throws LocationFullException {
        Game game = game();
        BipedMek mek = join(game, new BipedMek());
        Mounted<?> capacitor = add(mek, "ISPPCCapacitor", Mek.LOC_RIGHT_ARM);
        int equipmentNum = mek.getEquipmentNum(capacitor);
        int charge = DeploymentSetup.modeIndex(capacitor, "Charge");

        assertEquals(List.of(equipmentNum), DeploymentSetup.options(game, mek, null).equipment());

        DeploymentSetup setup = DeploymentSetup.of(game, mek, null).withMode(equipmentNum, charge);
        assertTrue(setup.isAllowed(game, mek, null));
        setup.apply(game, mek, null);
        assertEquals("Charge", capacitor.curMode().getName());
        assertEquals(Mounted.MODE_NONE, capacitor.pendingMode().getName());
    }

    @Test
    void secondEcmSuiteIsAConflict() throws LocationFullException {
        Game game = game();
        BipedMek mek = join(game, new BipedMek());
        Mounted<?> first = add(mek, "ISGuardianECMSuite", Mek.LOC_RIGHT_TORSO);
        Mounted<?> second = add(mek, "ISGuardianECMSuite", Mek.LOC_LEFT_TORSO);
        int off = DeploymentSetup.modeIndex(second, Mounted.MODE_OFF);

        assertEquals(DeploymentSetup.Conflict.TWO_ECM, DeploymentSetup.conflict(mek));

        DeploymentSetup.of(game, mek, null).withMode(mek.getEquipmentNum(second), off).apply(game, mek, null);
        assertNull(DeploymentSetup.conflict(mek));
        assertFalse(first.isModeTurnedOff());
    }

    @Test
    void equipmentNotOfferedIsRefused() {
        Game game = game();
        BipedMek mek = join(game, new BipedMek());

        assertFalse(DeploymentSetup.of(game, mek, null).withMode(0, 1).isAllowed(game, mek, null));
    }

    @Test
    void setupPassesThePacketFilter() throws Exception {
        DeploymentSetup setup = new DeploymentSetup(Stance.PRONE, true, 7, Map.of(3, 1, 4, 0));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(setup);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            in.setObjectInputFilter(new SanityInputFilter());
            assertEquals(setup, in.readObject());
        }
    }
}
