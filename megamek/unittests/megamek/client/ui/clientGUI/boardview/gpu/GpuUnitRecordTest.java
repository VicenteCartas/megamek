/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.SwingUtilities;

import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.MegaMekGUI;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.client.ui.clientGUI.tooltip.TipUtil;
import megamek.client.ui.clientGUI.unitDisplay.UnitDisplayState;
import megamek.client.ui.clientGUI.unitDisplay.UnitEquipmentActions;
import megamek.client.ui.panels.phaseDisplay.FiringDisplay;
import megamek.client.ui.util.MegaMekController;
import megamek.common.Configuration;
import megamek.common.CriticalSlot;
import megamek.common.Player;
import megamek.common.battleArmor.BattleArmor;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.enums.WeaponSortOrder;
import megamek.common.equipment.AmmoMounted;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.Mounted;
import megamek.common.equipment.WeaponMounted;
import megamek.common.game.Game;
import megamek.common.game.GameTurn;
import megamek.common.interfaces.IEntityRemovalConditions;
import megamek.common.interfaces.ILocationExposureStatus;
import megamek.common.loaders.MekFileParser;
import megamek.common.options.OptionsConstants;
import megamek.common.rules.RulesManager;
import megamek.common.units.Aero;
import megamek.common.units.Crew;
import megamek.common.units.CrewType;
import megamek.common.units.Entity;
import megamek.common.units.LandAirMek;
import megamek.common.units.Mek;
import megamek.common.units.Tank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

/**
 * The unit record service with real units: what it captures (the critical table and hit boxes as the record sheet
 * shows them, the unit's vitals and the Unit Display's content), and that each unit action sends what the Unit
 * Display's control sends (UnitDisplayActionsTest records those packets from the Swing panels).
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuUnitRecordTest {
    private static final String UNITS = "testresources/megamek/common/units/";
    private static final List<Integer> STANDARD_TICKS = List.of(5, 8, 10, 13, 14, 15, 17, 18, 19, 20, 22, 23, 24, 25,
          26, 27, 28, 29, 30);

    @Test
    void heatTicksAreTheLevelsWhereTheMekHeatEffectsChange() throws Exception {
        Game game = new Game();
        Entity atlas = unit("Atlas AS7-D.mtf");
        assertEquals(STANDARD_TICKS, GpuUnitRecord.heatTicks(game, atlas));
        assertEquals(31, GpuUnitRecord.heatScale(GpuUnitRecord.heatTicks(game, atlas)));

        Entity tsm = unit("Hachiwara HCA-6P.mtf");
        List<Integer> tsmTicks = List.of(5, 8, 9, 10, 13, 14, 15, 17, 18, 19, 20, 22, 23, 24, 25, 26, 27, 28, 29, 30);
        assertEquals(tsmTicks, GpuUnitRecord.heatTicks(game, tsm));
        assertEquals(31, GpuUnitRecord.heatScale(tsmTicks));

        game.getOptions().getOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_HEAT).setValue(true);
        List<Integer> tacOps = List.of(5, 8, 10, 13, 14, 15, 17, 18, 19, 20, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32,
              33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50);
        assertEquals(tacOps, GpuUnitRecord.heatTicks(game, atlas));
        assertEquals(51, GpuUnitRecord.heatScale(tacOps), "TacOps heat above 30 fits on the bar");

        Entity tank = unit("Bulldog Medium Tank.blk");
        assertEquals(List.of(), GpuUnitRecord.heatTicks(game, tank));
        assertEquals(0, GpuUnitRecord.heatScale(List.of()));
    }

    @Test
    void anOwnUnitsActionsSendWhatTheUnitDisplaySends() throws Exception {
        RulesManager rules = Game.rulesManager;
        try (GpuBoardFixture fixture = GpuBoardFixture.create(); Session session = new Session(fixture)) {
            // The game changes on the EDT, where the source's refresh timer reads it.
            Entity atlas = fixture.entity;
            int id = atlas.getId();
            Mounted<?> ecm = GpuTestSupport.onSwing(() -> atlas.addEquipment(EquipmentType.get("ISGuardianECMSuite"),
                  Mek.LOC_LEFT_ARM));
            Mounted<?> blade = GpuTestSupport.onSwing(() -> atlas.addEquipment(EquipmentType.get("ISMediumVibroblade"),
                  Mek.LOC_RIGHT_ARM));
            // ECM modes are shared type state that each game sets from its options, as it does when a unit joins it.
            SwingUtilities.invokeAndWait(atlas::setGameOptions);
            int ecmNum = atlas.getEquipmentNum(ecm);
            int bladeNum = atlas.getEquipmentNum(blade);
            session.source.setCardUnit(id);
            GpuUnitRecord.Snapshot record = session.record();
            assertEquals(id, record.unitId());
            assertTrue(record.own());
            assertFalse(record.removed());
            assertEquals("biped", record.paperdoll());
            assertEquals(List.of("HD", "CT", "RT", "LT", "RA", "LA", "RL", "LL"),
                  record.locations().stream().map(GpuUnitRecord.Location::abbr).toList());
            GpuUnitRecord.Location centerTorso = record.locations().get(1);
            assertEquals(List.of("CT", "Center Torso", 47, 47, 14, 14, 31, 31, false, false, 12),
                  List.of(centerTorso.abbr(), centerTorso.name(), centerTorso.armor(), centerTorso.maxArmor(),
                        centerTorso.rear(), centerTorso.maxRear(), centerTorso.internal(), centerTorso.maxInternal(),
                        centerTorso.destroyed(), centerTorso.breached(), centerTorso.slotCount()));
            assertEquals(STANDARD_TICKS, record.heatTicks());
            assertEquals(31, record.heatScale());
            // The weapon list's names and shots: the loaded bin's and those of every bin the weapon can switch to
            assertEquals(List.of("Medium Laser LA", "Medium Laser RA", "LRM 20 LT 6/12", "SRM 6 LT 15/15",
                  "AC/20 RT 5/10", "Medium Laser (R) CT", "Medium Laser (R) CT"), weapons(record));
            assertEquals(new GpuUnitRecord.Equipment(ecmNum, "ECM Suite (Guardian)", "LA", List.of("ECM", "Off"), 0, 0,
                  -1, true), equipment(record, ecmNum));
            assertTrue(record.readout().contains("Atlas"), record.readout());
            // The readout as rows: a title, plain lines, labeled values and tables, with their first cells
            assertEquals(List.of("TITLE Atlas AS7-D", "TEXT BattleMek", "SPACE", "LABELED Base Tech Level",
                  "LABELED Tech Rating", "LABELED Earliest Tech Date", "SPACE", "TABLE_HEADER Availability",
                  "TABLE_ROW Prototype"), record.readoutRows().stream().limit(9).map(row -> (row.kind() + " "
                  + (row.cells().isEmpty() ? "" : row.cells().getFirst())).strip()).toList());
            SwingUtilities.invokeAndWait(session.source::refresh);
            assertSame(record, session.record(), "An unchanged unit keeps its record instance");

            // Equipment modes: the Systems tab's mode list (UnitEquipmentActions.changeMode), which acts only on a changed
            // selection, so the second click on the entry now selected sends nothing
            session.source.record().setMode(id, ecmNum, 1);
            session.source.record().setMode(id, ecmNum, 1);
            session.flush();
            verify(session.client, times(1)).sendModeChange(id, ecmNum, 1);
            verify(session.gui, times(1)).systemMessage("ECM Suite (Guardian) will switch to Off at end of turn.");
            verify(session.gui, times(1)).addToast(ToastLevel.INFO, "ECM Suite (Guardian) -> Off", atlas);
            GpuUnitRecord.Snapshot switched = session.record();
            assertNotSame(record, switched);
            assertEquals(List.of(1, 0, 1), List.of(equipment(switched, ecmNum).selected(),
                  equipment(switched, ecmNum).mode(), equipment(switched, ecmNum).pendingMode()));
            session.source.record().setMode(id, bladeNum, 1);
            session.flush();
            verify(session.client, never()).sendModeChange(id, bladeNum, 1);
            verify(session.gui).systemMessage(Messages.getString("MekDisplay.VibrobladeModePhase"));

            // Sensors, active heat sinks and weapon order (ExtraPanel, WeaponPanel); the game sets the sensor in use
            // at the start of a round, which this fixture has not run
            assertEquals(new GpuUnitRecord.SystemControl(GpuUnitRecord.SENSORS,
                  Messages.getString("MekDisplay.CurrentSensors"),
                  List.of("Mek Radar", "Mek IR", "Mek Magscan", "Mek Seismic"), 0, -1, true),
                  system(switched, GpuUnitRecord.SENSORS));
            session.source.record().setSystem(id, GpuUnitRecord.SENSORS, 1);
            session.source.record().setSystem(id, GpuUnitRecord.HEAT_SINKS, 10);
            session.source.record().setSystem(id, GpuUnitRecord.WEAPON_ORDER,
                  WeaponSortOrder.DAMAGE_HIGH_LOW.ordinal());
            session.flush();
            verify(session.client).sendSensorChange(id, 1);
            verify(session.gui).systemMessage("Active Sensors will switch to Mek IR at end of turn.");
            verify(session.client).sendSinksChange(id, 10);
            verify(session.client).sendEntityWeaponOrderUpdate(atlas);
            GpuUnitRecord.Snapshot ordered = session.record();
            assertEquals(List.of("AC/20 RT 5/10", "Medium Laser LA", "Medium Laser RA", "Medium Laser (R) CT",
                  "Medium Laser (R) CT", "LRM 20 LT 6/12", "SRM 6 LT 15/15"), weapons(ordered));
            assertEquals(List.of(10, 20), List.of(system(ordered, GpuUnitRecord.HEAT_SINKS).selected(),
                  system(ordered, GpuUnitRecord.HEAT_SINKS).current()), "Ten sinks from the next round, twenty now");

            // Weapon order by moving one weapon (the list drag): a custom order, sent later by the phase display
            session.source.record().moveWeapon(id, ordered.weapons().getFirst().eqNum(), 1);
            session.flush();
            assertEquals(WeaponSortOrder.CUSTOM, atlas.getWeaponSortOrder());
            assertEquals(List.of("Medium Laser LA", "AC/20 RT 5/10", "Medium Laser RA", "Medium Laser (R) CT",
                  "Medium Laser (R) CT", "LRM 20 LT 6/12", "SRM 6 LT 15/15"), weapons(session.record()));
            verify(session.client).sendEntityWeaponOrderUpdate(atlas);

            // Hidden activation and command console roles
            SwingUtilities.invokeAndWait(() -> {
                atlas.setHidden(true);
                atlas.setCrew(new Crew(CrewType.COMMAND_CONSOLE));
            });
            assertEquals(List.of(Messages.getString("MekDisplay.ActivateHidden.StopActivating"),
                  GamePhase.MOVEMENT.toString(), GamePhase.FIRING.toString(), GamePhase.PHYSICAL.toString()),
                  system(session.record(), GpuUnitRecord.HIDDEN).choices());
            session.source.record().setSystem(id, GpuUnitRecord.HIDDEN, 2);
            session.source.record().setSystem(id, GpuUnitRecord.CONSOLE_ROLES, 1);
            session.flush();
            verify(session.client).sendActivateHidden(id, GamePhase.FIRING);
            verify(session.client).sendUpdateEntity(atlas);
            assertTrue(atlas.getCrew().getSwapConsoleRoles());

            // Ammunition dumping after movement, once confirmed (UnitEquipmentActions.toggleDump)
            Mounted<?> ammo = atlas.getCritical(Mek.LOC_RIGHT_TORSO, 10).getMount();
            int ammoNum = atlas.getEquipmentNum(ammo);
            assertEquals(new GpuUnitRecord.AmmoBin(ammoNum, "AC/20 Ammo", "RT", 5, 5, false, false, false, false,
                  false, "MekDisplay.DumpBlocked.phase"), bin(session.record(), ammoNum), "Not in the movement phase");
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.initializeRulesManager(OptionsConstants.RULES_TW);
                fixture.game.setRoundCount(1);
                fixture.game.setPhase(GamePhase.FIRING);
            });
            assertEquals(new GpuUnitRecord.AmmoBin(ammoNum, "AC/20 Ammo", "RT", 5, 5, false, false, false, false,
                  true, ""), bin(session.record(), ammoNum));
            session.source.record().setDumping(id, ammoNum, true);
            session.source.record().setDumping(id, ammoNum, true);
            session.flush();
            verify(session.gui).doYesNoDialog(Messages.getString("MekDisplay.Dump.title"),
                  Messages.getString("MekDisplay.Dump.message", ammo.getName()));
            verify(session.client).sendModeChange(id, ammoNum, -1);
            assertTrue(bin(session.record(), ammoNum).dumping());
            session.source.record().setDumping(id, ammoNum, false);
            session.flush();
            verify(session.client).sendModeChange(id, ammoNum, 0);
            assertFalse(ammo.isPendingDump());

            // A one-shot launcher's ammunition has no critical slot, so the Systems tab never lists it: the rules
            // alone would let it be dumped, yet it has no bin and its dump sends nothing
            Mounted<?> launcher = GpuTestSupport.onSwing(() -> atlas.addEquipment(EquipmentType.get("RL10"), Mek.LOC_LEFT_TORSO));
            Mounted<?> oneShot = launcher.getLinked();
            int oneShotNum = atlas.getEquipmentNum(oneShot);
            assertEquals(Entity.LOC_NONE, oneShot.getLocation());
            assertTrue(GpuTestSupport.onSwing(() -> UnitEquipmentActions.canDump(session.client, atlas, oneShot)));
            assertTrue(session.record().ammo().stream().noneMatch(bin -> bin.eqNum() == oneShotNum));
            session.source.record().setDumping(id, oneShotNum, true);
            session.flush();
            verify(session.client, never()).sendModeChange(id, oneShotNum, -1);
            assertFalse(oneShot.isPendingDump());
        } finally {
            Game.rulesManager = rules;
        }
    }

    @Test
    void aCommandActsOnTheUnitItNamesNotOnTheLatestCapture() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create(); Session session = new Session(fixture)) {
            Entity atlas = fixture.entity;
            Entity enemy = session.addEnemy(fixture);
            // The card has moved on to the enemy while the sheet still shows the Atlas
            session.source.setCardUnit(enemy.getId());
            assertEquals(enemy.getId(), session.record().unitId());
            session.source.record().setSystem(atlas.getId(), GpuUnitRecord.HEAT_SINKS, 10);
            session.flush();
            verify(session.client).sendSinksChange(atlas.getId(), 10);
        }
    }

    @Test
    void anAmmunitionChoiceLoadsTheBinAsTheUnitDisplaysListDoesForOwnUnitsOnly() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create(); Session session = new Session(fixture)) {
            Entity atlas = fixture.entity;
            int id = atlas.getId();
            WeaponMounted lrm = atlas.getWeaponList().get(2);
            List<AmmoMounted> bins = atlas.getAmmo().stream()
                  .filter(bin -> bin.getType().getAmmoType() == lrm.getType().getAmmoType()).toList();
            // One shot fired from the second bin tells the list's two entries apart
            SwingUtilities.invokeAndWait(() -> bins.get(1).setShotsLeft(5));
            session.source.setCardUnit(id);
            session.source.record().setSheetOpen(true);
            GpuUnitRecord.RecordWeapon weapon = session.record().weapons().get(2);
            assertEquals(List.of(new GpuUnitRecord.AmmoChoice(id, 12, "[LT] LRM 20  (6)"),
                  new GpuUnitRecord.AmmoChoice(id, 13, "[LT] LRM 20  (5)")), weapon.ammoChoices());
            assertEquals(List.of(10, 0), List.of(weapon.eqNum(), weapon.loadedAmmo()));

            session.source.record().setAmmo(id, weapon.eqNum(), weapon.ammoChoices().get(1));
            session.flush();
            // What the Unit Display sends for the same pick (UnitDisplayStateTest)
            verify(session.client).sendAmmoChange(1, 10, 13, 1, 0);
            assertSame(bins.get(1), GpuTestSupport.onSwing(lrm::getLinkedAmmo));
            assertEquals(1, session.record().weapons().get(2).loadedAmmo());

            // The bin loaded already, a bin the weapon's list does not offer and another player's weapon: nothing
            session.source.record().setAmmo(id, weapon.eqNum(), weapon.ammoChoices().get(1));
            int srmBin = atlas.getEquipmentNum(atlas.getWeaponList().get(3).getLinkedAmmo());
            session.source.record().setAmmo(id, weapon.eqNum(), new GpuUnitRecord.AmmoChoice(id, srmBin, ""));
            Entity enemy = session.addEnemy(fixture);
            session.source.record().setAmmo(enemy.getId(), 10, new GpuUnitRecord.AmmoChoice(enemy.getId(), 12, ""));
            session.flush();
            verify(session.client, times(1)).sendAmmoChange(anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
            assertSame(bins.get(1), GpuTestSupport.onSwing(lrm::getLinkedAmmo));
        }
    }

    @Test
    void dumpingOneBinOfASlotWithTwoSendsTheCommandForThatBinOnly() throws Exception {
        RulesManager rules = Game.rulesManager;
        try (GpuBoardFixture fixture = GpuBoardFixture.create(); Session session = new Session(fixture)) {
            // A superheavy Mek's slot with two bins
            Entity omega = official("meks/3145/NTNU RS/NTNU/Omega SHP-5R.mtf");
            CriticalSlot dual = omega.getCritical(Mek.LOC_LEFT_ARM, 6);
            GpuUnitRecord.Slot slot = location(session.capture(fixture, omega, unit -> { }), "LA").slots().get(6);
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.initializeRulesManager(OptionsConstants.RULES_TW);
                fixture.game.setRoundCount(1);
                fixture.game.setPhase(GamePhase.FIRING);
            });
            session.source.record().setDumping(omega.getId(), slot.eqNum2(), true);
            session.flush();
            verify(session.client).sendModeChange(omega.getId(), omega.getEquipmentNum(dual.getMount2()), -1);
            verify(session.client, never()).sendModeChange(omega.getId(), omega.getEquipmentNum(dual.getMount()), -1);
            assertEquals(List.of(false, true), GpuTestSupport.onSwing(() -> List.of(dual.getMount().isPendingDump(),
                  dual.getMount2().isPendingDump())));
        } finally {
            Game.rulesManager = rules;
        }
    }

    @Test
    void aRecordHasTheUnitDisplaysArmorSystemsWeaponsPilotAndExtras() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create(); Session session = new Session(fixture)) {
            Entity atlas = fixture.entity;
            SwingUtilities.invokeAndWait(() -> {
                atlas.getCritical(Mek.LOC_CENTER_TORSO, 0).setDestroyed(true);
                atlas.getCritical(Mek.LOC_CENTER_TORSO, 10).setHit(true);
                atlas.getCritical(Mek.LOC_CENTER_TORSO, 10).getMount().setDestroyed(true);
                atlas.getCritical(Mek.LOC_RIGHT_TORSO, 0).setHit(true);
                atlas.getCritical(Mek.LOC_RIGHT_TORSO, 0).getMount().setDestroyed(true);
                atlas.getCritical(Mek.LOC_LEFT_ARM, 0).setMissing(true);
                atlas.setLocationStatus(Mek.LOC_LEFT_LEG, ILocationExposureStatus.BREACHED);
                atlas.getCritical(Mek.LOC_LEFT_LEG, 0).setBreached(true);
                atlas.getWeaponList().getFirst().setJammedImmediately(true);
                atlas.setExternalSearchlight(true);
                atlas.getCrew().setHits(2, 0);
                atlas.getCrew().getOptions().getOption(OptionsConstants.PILOT_MELEE_SPECIALIST).setValue(true);
            });
            session.source.setCardUnit(atlas.getId());
            session.source.record().setSheetOpen(true);
            GpuUnitRecord.Snapshot record = session.record();

            // The critical table: every slot under its record sheet name, states as flags beside it
            GpuUnitRecord.Location centerTorso = record.locations().get(Mek.LOC_CENTER_TORSO);
            assertEquals(List.of("Fusion Engine", "Fusion Engine", "Fusion Engine", "Gyro", "Gyro", "Gyro", "Gyro",
                  "Fusion Engine", "Fusion Engine", "Fusion Engine", "Medium Laser (R)", "Medium Laser (R)"),
                  texts(centerTorso));
            assertEquals(List.of(0, 10), indices(centerTorso, GpuUnitRecord.Slot::hit));
            int rearLaser = atlas.getEquipmentNum(atlas.getCritical(Mek.LOC_CENTER_TORSO, 10).getMount());
            assertEquals(new GpuUnitRecord.Slot(10, "Medium Laser (R)", rearLaser, -1, -1, true, true, false, false,
                  false, false, false, -1, -1, false, false), centerTorso.slots().get(10));
            assertEquals(new GpuUnitRecord.Slot(0, "Fusion Engine", -1, -1, Mek.SYSTEM_ENGINE, true, true, false,
                  false, false, false, false, -1, -1, false, false), centerTorso.slots().getFirst());
            assertEquals(new GpuUnitRecord.HitTrack(GpuUnitRecord.Track.ENGINE, 1, 3), record.hitTracks().getFirst());
            GpuUnitRecord.Location leftArm = record.locations().get(Mek.LOC_LEFT_ARM);
            assertEquals(List.of("Shoulder", "Upper Arm Actuator", "Lower Arm Actuator", "Hand Actuator", "Heat Sink",
                  "Medium Laser", "Roll Again", "Roll Again", "Roll Again", "Roll Again", "Roll Again", "Roll Again"),
                  texts(leftArm), "A Mek's empty slots are on its table");
            assertTrue(leftArm.slots().get(6).empty());
            // An AC/20 destroyed by one critical hit is destroyed in all ten slots though one took the hit; a missing
            // shoulder; a breached hip in a breached leg
            GpuUnitRecord.Location rightTorso = record.locations().get(Mek.LOC_RIGHT_TORSO);
            assertEquals(List.of(0), indices(rightTorso, GpuUnitRecord.Slot::hit));
            assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), indices(rightTorso, GpuUnitRecord.Slot::destroyed));
            assertEquals("AC/20", rightTorso.slots().get(9).text());
            assertEquals(List.of(0), indices(leftArm, GpuUnitRecord.Slot::missing));
            GpuUnitRecord.Location leftLeg = record.locations().get(Mek.LOC_LEFT_LEG);
            assertEquals(List.of(false, true), List.of(centerTorso.breached(), leftLeg.breached()));
            assertEquals(List.of(0), indices(leftLeg, GpuUnitRecord.Slot::breached));
            assertEquals(List.of("CT", "RA", "LT"), List.of(rightTorso.transferTo(), rightTorso.dependent(),
                  leftLeg.transferTo()));

            // Weapons tab: the weapon list's row and the weapon display's statistics with the loaded ammunition
            assertEquals(List.of("j Medium Laser [LA]  | 3 5 1 - 3 jammed", "Medium Laser [RA]  | 3 5 1 - 3",
                  "LRM 20 [LT] (6/12)  | 6 Missile 1 - 7", "SRM 6 [LT] (15/15) | 4 Missile 1 - 3",
                  "*AC/20 [RT] (5/10) | 7 20 1 - 3 destroyed", "*Medium Laser (R) [CT]  | 3 5 1 - 3 destroyed",
                  "Medium Laser (R) [CT]  | 3 5 1 - 3"), record.weapons().stream().map(weapon -> weapon.row().text()
                  + " | " + weapon.stats().heat() + " " + weapon.stats().damage() + " " + weapon.stats().shortRange()
                  + (weapon.jammed() ? " jammed" : "") + (weapon.destroyed() ? " destroyed" : "")).toList());
            GpuUnitRecord.RecordWeapon lrm = record.weapons().get(2);
            assertEquals(List.of(6, 7, 14, 21, 28), lrm.ranges());
            assertEquals(List.of("[LT] LRM 20  (6)", "[LT] LRM 20  (6)"),
                  lrm.ammoChoices().stream().map(GpuUnitRecord.AmmoChoice::label).toList());
            assertEquals(0, lrm.loadedAmmo());

            assertEquals(12, record.equipment().stream().filter(item -> item.name().equals("Heat Sink")
                  && item.location().isEmpty()).count(), "The engine's heat sinks have no location");

            // Pilot tab and Extras tab
            GpuUnitRecord.CrewSeat pilot = record.crew().getFirst();
            assertEquals(List.of(4, 5, 2, "2 hits", true, false), List.of(pilot.gunnery(), pilot.piloting(),
                  pilot.hits(), pilot.status(), pilot.active(), pilot.missing()));
            assertNotNull(pilot.portrait());
            assertEquals(List.of(new TipUtil.OptionGroup("Advantages", List.of("Melee Specialist (CamOps)"))),
                  record.abilities());
            assertEquals(List.of("Medium Laser is Jammed", "Left Leg Breached"), record.conditions());
            assertEquals(List.of("Searchlight (off)"), record.carried());
            assertEquals("One BA-MagClamp squad\nOne protomek\nOne protomek", record.unused());
            assertEquals("", record.lastTarget());
            // The unit's details from the unit tooltip, its base piloting roll (a breached leg counts as destroyed)
            assertEquals(new GpuUnitRecord.InfoRow(GpuUnitRecord.InfoSection.PSR, "9",
                  "5 (Base piloting skill) + 4 (Left Leg destroyed)", Entity.NONE), info(record,
                  GpuUnitRecord.InfoSection.PSR).getFirst());
            assertEquals(List.of("Has not yet moved"), info(record, GpuUnitRecord.InfoSection.MOVEMENT).stream()
                  .map(GpuUnitRecord.InfoRow::text).toList());

            // With the sheet closed, the record leaves out what only the sheet shows
            session.source.record().setSheetOpen(false);
            GpuUnitRecord.Snapshot closed = session.record();
            assertNull(closed.weapons().get(2).stats());
            assertEquals(List.of(), closed.weapons().get(2).ammoChoices());
            assertEquals(List.of(), closed.info());
            assertEquals(record.locations(), closed.locations(), "The card shows the slots' hits");
        }
    }

    @Test
    void anEnemyRecordShowsNoActionsAndItsCommandsSendNothing() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create(); Session session = new Session(fixture)) {
            Entity enemy = session.addEnemy(fixture);
            session.source.setCardUnit(enemy.getId());
            session.source.record().setSheetOpen(true);
            GpuUnitRecord.Snapshot record = session.record();
            assertEquals(enemy.getId(), record.unitId());
            assertFalse(record.own());
            assertEquals(8, record.locations().size());
            assertEquals(12, record.locations().get(Mek.LOC_CENTER_TORSO).slots().size());
            assertEquals(7, record.weapons().size());
            assertTrue(record.weapons().stream().allMatch(weapon -> weapon.ammoChoices().isEmpty()
                  && (weapon.stats() != null)), "Statistics for any unit, ammunition choices only for the owner");
            assertEquals(List.of(), record.equipment());
            assertEquals(List.of(), record.systems());
            // As in the Unit Display, the crew and the ammunition show for any unit; only the owner acts on them.
            assertEquals(1, record.crew().size());
            assertFalse(record.crew().getFirst().swappable());
            assertEquals(List.of("LRM 20 Ammo LT 6", "LRM 20 Ammo LT 6", "SRM 6 Ammo LT 15", "AC/20 Ammo RT 5",
                  "AC/20 Ammo RT 5"), record.ammo().stream().map(bin -> bin.name() + " " + bin.location() + " "
                  + bin.shots()).toList());
            assertTrue(record.ammo().stream().noneMatch(GpuUnitRecord.AmmoBin::canDump));
            assertTrue(record.readout().contains("Atlas"));
            SwingUtilities.invokeAndWait(() -> enemy.setArmor(20, Mek.LOC_CENTER_TORSO));
            GpuUnitRecord.Snapshot damaged = session.record();
            assertEquals(20, damaged.locations().get(Mek.LOC_CENTER_TORSO).armor());
            assertNotEquals(record.readout(), damaged.readout(), "The readout follows the damage");

            int id = enemy.getId();
            session.source.record().setSystem(id, GpuUnitRecord.SENSORS, 1);
            session.source.record().setSystem(id, GpuUnitRecord.WEAPON_ORDER, WeaponSortOrder.RANGE_LOW_HIGH.ordinal());
            session.source.record().setMode(id, 0, 1);
            session.source.record().moveWeapon(id, enemy.getEquipmentNum(enemy.getWeaponList().getFirst()), 1);
            session.source.record().setDumping(id, enemy.getEquipmentNum(enemy.getAmmo().getFirst()), true);
            session.flush();
            verify(session.client, never()).sendSensorChange(anyInt(), anyInt());
            verify(session.client, never()).sendModeChange(anyInt(), anyInt(), anyInt());
            verify(session.client, never()).sendEntityWeaponOrderUpdate(any());
            assertEquals(WeaponSortOrder.DEFAULT, enemy.getWeaponSortOrder());
        }
    }

    @Test
    void aUnitThatLeftTheGameKeepsItsRecordWithoutControls() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create(); Session session = new Session(fixture)) {
            Entity atlas = fixture.entity;
            int id = atlas.getId();
            SwingUtilities.invokeAndWait(() -> fixture.game.removeEntity(id,
                  IEntityRemovalConditions.REMOVE_SALVAGEABLE));
            GpuUnitRecord.Snapshot record = GpuTestSupport.onSwing(() -> session.source.record().capture(id));
            assertEquals(List.of(id, true, true, 8, 7), List.of(record.unitId(), record.own(), record.removed(),
                  record.locations().size(), record.weapons().size()));
            assertEquals(List.of(), record.equipment());
            assertEquals(List.of(), record.systems());
            session.source.record().setSystem(id, GpuUnitRecord.HEAT_SINKS, 10);
            session.flush();
            verify(session.client, never()).sendSinksChange(anyInt(), anyInt());
        }
    }

    @Test
    void theCriticalTableHasTheRecordSheetsSlotsHitBoxesAndCase() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create(); Session session = new Session(fixture)) {
            GpuUnitRecord.Snapshot record = session.capture(fixture, official("meks/3050U/Atlas AS7-K.mtf"), atlas -> {
                atlas.destroyLocation(Mek.LOC_LEFT_ARM);
                atlas.applyDamage();
                atlas.damageSystem(CriticalSlot.TYPE_SYSTEM, Mek.SYSTEM_ENGINE, Mek.LOC_CENTER_TORSO, 1);
                ((AmmoMounted) atlas.getCritical(Mek.LOC_LEFT_TORSO, 8).getMount()).setShotsLeft(4);
            });
            GpuUnitRecord.Location leftArm = location(record, "LA");
            assertEquals(List.of("Shoulder", "Upper Arm Actuator", "Lower Arm Actuator", "Hand Actuator", "Heat Sink",
                  "Heat Sink", "ER Large Laser", "ER Large Laser", "Anti-Missile System", "Roll Again", "Roll Again",
                  "Roll Again"), texts(leftArm));
            assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8), indices(leftArm, GpuUnitRecord.Slot::hit));
            assertEquals(List.of(true, "LT"), List.of(leftArm.destroyed(), leftArm.transferTo()));
            GpuUnitRecord.Location centerTorso = location(record, "CT");
            assertEquals(List.of("XL Fusion Engine", "XL Fusion Engine", "XL Fusion Engine", "Gyro", "Gyro", "Gyro",
                  "Gyro", "XL Fusion Engine", "XL Fusion Engine", "XL Fusion Engine", "Medium Pulse Laser (R)",
                  "Medium Pulse Laser (R)"), texts(centerTorso));
            assertEquals(List.of(0), indices(centerTorso, GpuUnitRecord.Slot::hit));
            assertEquals(List.of(new GpuUnitRecord.HitTrack(GpuUnitRecord.Track.ENGINE, 1, 3),
                  new GpuUnitRecord.HitTrack(GpuUnitRecord.Track.GYRO, 0, 2),
                  new GpuUnitRecord.HitTrack(GpuUnitRecord.Track.SENSORS, 0, 2),
                  new GpuUnitRecord.HitTrack(GpuUnitRecord.Track.LIFE_SUPPORT, 0, 1)), record.hitTracks());
            GpuUnitRecord.Location leftTorso = location(record, "LT");
            assertEquals("CASE", leftTorso.caseTag());
            assertEquals(List.of(11), indices(leftTorso, GpuUnitRecord.Slot::filler), "CASE takes no critical hit");
            assertEquals(List.of("Ammo (LRM 20) 4/6", "Ammo (LRM 20) 6/6", "Ammo (AMS) 12/12"), ammoSlots(leftTorso));
            assertEquals(List.of("Ammo (Gauss) 8/8", "Ammo (Gauss) 8/8"), ammoSlots(location(record, "RA")));
            assertEquals(List.of("Hip", "Upper Leg Actuator", "Lower Leg Actuator", "Foot Actuator", "Heat Sink",
                  "Heat Sink"), texts(location(record, "LL")));
        }
    }

    @Test
    void aShieldFollowsItsCriticalHitsAndItsArm() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create(); Session session = new Session(fixture)) {
            String centurion = "meks/3145/Davion/Centurion CN11-OD.mtf";
            Entity whole = official(centurion);
            GpuUnitRecord.Snapshot record = session.capture(fixture, whole, unit -> { });
            int shield = whole.getEquipmentNum(whole.getCritical(Mek.LOC_LEFT_ARM, 4).getMount());
            assertEquals(List.of(new GpuUnitRecord.Shield("LA", shield, "Shield (Medium)", true, 5, 5, 18, 18)),
                  record.shields());
            GpuUnitRecord.Location leftTorso = location(record, "LT");
            assertEquals("CASE II", leftTorso.caseTag());
            assertEquals(List.of(8, 9, 10, 11), indices(leftTorso, GpuUnitRecord.Slot::filler), "CASE II, Endo Steel");
            assertEquals(List.of("Ammo (MML 9/LRM Artemis) 13/13", "Ammo (MML 9/SRM Artemis) 11/11"),
                  ammoSlots(leftTorso));

            // One critical hit on the shield and a breached leg
            GpuUnitRecord.Snapshot hit = session.capture(fixture, official(centurion), unit -> {
                unit.getCritical(Mek.LOC_LEFT_ARM, 4).setHit(true);
                unit.setLocationStatus(Mek.LOC_LEFT_LEG, ILocationExposureStatus.BREACHED);
                for (int slot = 0; slot < unit.getNumberOfCriticalSlots(Mek.LOC_LEFT_LEG); slot++) {
                    if (unit.getCritical(Mek.LOC_LEFT_LEG, slot) != null) {
                        unit.getCritical(Mek.LOC_LEFT_LEG, slot).setBreached(true);
                    }
                }
            });
            assertEquals(new GpuUnitRecord.Shield("LA", shield, "Shield (Medium)", true, 4, 5, 13, 18),
                  hit.shields().getFirst());
            GpuUnitRecord.Location leftLeg = location(hit, "LL");
            assertTrue(leftLeg.breached());
            assertEquals(List.of(0, 1, 2, 3, 4, 5), indices(leftLeg, GpuUnitRecord.Slot::breached));

            // A blown-off arm: MegaMek's capacity getter does not see it, the shield no longer works
            GpuUnitRecord.Snapshot blownOff = session.capture(fixture, official(centurion),
                  unit -> unit.destroyLocation(Mek.LOC_LEFT_ARM, true));
            assertEquals(new GpuUnitRecord.Shield("LA", shield, "Shield (Medium)", false, 5, 5, 18, 18),
                  blownOff.shields().getFirst());
            assertTrue(location(blownOff, "LA").blownOff());
        }
    }

    @Test
    void theHitBoxesAndSlotsFollowTheMeksSystems() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create(); Session session = new Session(fixture)) {
            // A torso-mounted cockpit: sensors in the head and the center torso, destroyed by the third hit
            GpuUnitRecord.Snapshot turtle = session.capture(fixture, unit("Great Turtle GTR-1.mtf"), unit -> {
                unit.damageSystem(CriticalSlot.TYPE_SYSTEM, Mek.SYSTEM_SENSORS, Mek.LOC_HEAD, 1);
                unit.damageSystem(CriticalSlot.TYPE_SYSTEM, Mek.SYSTEM_SENSORS, Mek.LOC_CENTER_TORSO, 1);
            });
            assertEquals(new GpuUnitRecord.HitTrack(GpuUnitRecord.Track.SENSORS, 2, 3),
                  track(turtle, GpuUnitRecord.Track.SENSORS));
            assertEquals("Torso-Mounted Cockpit", location(turtle, "CT").slots().get(8).text());

            // No gyro, no gyro box
            GpuUnitRecord.Snapshot ryoken = session.capture(fixture,
                  official("meks/XTRs/Republic III/Ryoken III-XP Prime.mtf"), unit -> { });
            assertEquals(List.of(GpuUnitRecord.Track.ENGINE, GpuUnitRecord.Track.SENSORS,
                  GpuUnitRecord.Track.LIFE_SUPPORT), ryoken.hitTracks().stream().map(GpuUnitRecord.HitTrack::track)
                  .toList());

            // A LAM's avionics and landing gear
            GpuUnitRecord.Snapshot lam = session.capture(fixture, unit("Shadow Hawk LAM SHD-X2.mtf"), unit -> {
                unit.damageSystem(CriticalSlot.TYPE_SYSTEM, LandAirMek.LAM_AVIONICS, Mek.LOC_HEAD, 1);
                unit.damageSystem(CriticalSlot.TYPE_SYSTEM, LandAirMek.LAM_LANDING_GEAR, Mek.LOC_CENTER_TORSO, 1);
            });
            assertEquals(List.of(new GpuUnitRecord.HitTrack(GpuUnitRecord.Track.AVIONICS, 1, 3),
                  new GpuUnitRecord.HitTrack(GpuUnitRecord.Track.LANDING_GEAR, 1, 3)), lam.hitTracks().subList(4, 6));

            // An armored component keeps its mark once its armor is gone
            Entity devastator = unit("Devastator DVS-X10 MUSE EARTH.mtf");
            GpuUnitRecord.Snapshot armored = session.capture(fixture, devastator, unit -> { });
            assertEquals(List.of("Compact Gyro true false", "Compact Gyro true false"), armoredSlots(armored, 3, 5));
            GpuUnitRecord.Snapshot armorHit = session.capture(fixture, unit("Devastator DVS-X10 MUSE EARTH.mtf"),
                  unit -> unit.getCritical(Mek.LOC_CENTER_TORSO, 3).hitArmored());
            assertEquals(List.of("Compact Gyro true true", "Compact Gyro true false"), armoredSlots(armorHit, 3, 5));

            // A superheavy Mek's slot with two bins: their shots together, both equipment numbers
            Entity omega = official("meks/3145/NTNU RS/NTNU/Omega SHP-5R.mtf");
            CriticalSlot dual = omega.getCritical(Mek.LOC_LEFT_ARM, 6);
            GpuUnitRecord.Snapshot superheavy = session.capture(fixture, omega,
                  unit -> ((AmmoMounted) dual.getMount2()).setShotsLeft(7));
            GpuUnitRecord.Slot slot = location(superheavy, "LA").slots().get(6);
            assertEquals(List.of("Ammo (LB 10-X)", omega.getEquipmentNum(dual.getMount()),
                  omega.getEquipmentNum(dual.getMount2()), 17, 20), List.of(slot.text(), slot.eqNum(), slot.eqNum2(),
                  slot.shots(), slot.fullShots()));
        }
    }

    @Test
    void vehiclesAndInfantryHaveTheirVitals() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create(); Session session = new Session(fixture)) {
            GpuUnitRecord.Snapshot tank = session.capture(fixture, unit("Bulldog Medium Tank.blk"), unit -> {
                Tank bulldog = (Tank) unit;
                bulldog.lockTurret(bulldog.getLocTurret());
                bulldog.addMovementDamage(2);
            });
            assertEquals(List.of("STABILIZER BD 0/1", "STABILIZER FR 0/1", "STABILIZER RS 0/1", "STABILIZER LS 0/1",
                  "STABILIZER RR 0/1", "STABILIZER TU 0/1", "TURRET TU 1/1", "SYSTEM ENGINE 0/1", "SYSTEM SENSORS 0/4",
                  "MOTIVE MINOR_MOTIVE 0/1", "MOTIVE MODERATE_MOTIVE 1/1", "MOTIVE HEAVY_MOTIVE 0/1",
                  "SYSTEM COMMANDER 0/1", "SYSTEM DRIVER 0/1", "MP MP 3/4 4/6/0"), vitals(tank, group -> true));
            GpuUnitRecord.Snapshot vtol = session.capture(fixture, unit("SOAR VTOL.blk"),
                  unit -> ((Tank) unit).setMotiveDamage(1));
            assertEquals(List.of("MOTIVE ROTOR 1/8"), vitals(vtol, GpuUnitRecord.VitalGroup.MOTIVE::equals));
            assertEquals(4, location(vtol, "FR").bar(), "Support vehicle armor's BAR");

            GpuUnitRecord.Snapshot armor = session.capture(fixture, unit("Elemental BA [Laser] (Sqd5).blk"), unit -> {
                unit.destroyLocation(BattleArmor.LOC_TROOPER_2);
                unit.applyDamage();
            });
            assertEquals(List.of("TROOPERS TROOPERS 4/5"), vitals(armor, GpuUnitRecord.VitalGroup.TROOPERS::equals));
            assertTrue(location(armor, "Trooper 2").destroyed());
            GpuUnitRecord.Snapshot platoon = session.capture(fixture, unit("Foot Platoon (AFFS) (Laser 3067+).blk"),
                  unit -> { });
            assertEquals(List.of("TROOPERS TROOPERS 28/28", "KIT ARMOR 0/-1 2.0E"), vitals(platoon,
                  group -> (group == GpuUnitRecord.VitalGroup.TROOPERS) || (group == GpuUnitRecord.VitalGroup.KIT)));
        }
    }

    @Test
    void aerospaceUnitsHaveTheirThresholdsAndVitals() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create(); Session session = new Session(fixture)) {
            GpuUnitRecord.Snapshot fighter = session.capture(fixture, unit("Cheetah F-11.blk"),
                  unit -> ((Aero) unit).setAvionicsHits(1));
            assertEquals(List.of(3, 2, 2, 3, 0, 0), fighter.locations().stream().map(GpuUnitRecord.Location::threshold)
                  .toList());
            assertEquals(List.of("AERO SI 12/12", "AERO ENGINE 0/3", "AERO AVIONICS 1/-1", "AERO FCS 0/-1",
                  "AERO SENSORS 0/-1", "AERO GEAR 0/1"), vitals(fighter, GpuUnitRecord.VitalGroup.AERO::equals));

            GpuUnitRecord.Snapshot warship = session.capture(fixture, unit("Aegis Heavy Cruiser (2372).blk"),
                  unit -> { });
            assertEquals(List.of("NOS", "FLS", "FRS", "AFT", "ALS", "ARS", "HULL", "LBS", "RBS"),
                  warship.locations().stream().map(GpuUnitRecord.Location::abbr).toList());
            assertEquals(List.of("SHIP KF 16/16", "SHIP SAIL 5/5", "SHIP DC 4/4"),
                  vitals(warship, GpuUnitRecord.VitalGroup.SHIP::equals));
            assertTrue(vitals(warship, GpuUnitRecord.VitalGroup.AERO::equals).contains("AERO CIC 0/-1"));
            assertFalse(vitals(warship, GpuUnitRecord.VitalGroup.AERO::equals).contains("AERO GEAR 0/1"));

            GpuUnitRecord.Snapshot dropship = session.capture(fixture, unit("Union (3055).blk"), unit -> { });
            assertEquals(List.of("SHIP KF_BOOM 0/1", "SHIP COLLAR 0/1"),
                  vitals(dropship, GpuUnitRecord.VitalGroup.SHIP::equals));
        }
    }

    @Test
    void inspectingAnEnemyDuringFiringKeepsTheFiringDisplaysWeaponSelection() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create(); Session session = new Session(fixture)) {
            Entity enemy = session.addEnemy(fixture);
            SwingUtilities.invokeAndWait(() -> fixture.game.setPhase(GamePhase.FIRING));
            MegaMekController controller = mock(MegaMekController.class);
            AtomicReference<MockedStatic<MegaMekGUI>> keys = new AtomicReference<>();
            UnitDisplayState display = new UnitDisplayState(session.gui);
            FiringDisplay firing = GpuTestSupport.onSwing(() -> {
                // Stubbed on the EDT, where the source's refresh timer reads the same mocks.
                when(session.gui.getUnitDisplayState()).thenReturn(display);
                when(session.gui.getDisplayedUnit()).thenReturn(fixture.entity);
                when(session.client.isMyTurn()).thenReturn(true);
                when(session.client.getMyTurn()).thenReturn(new GameTurn(fixture.player.getId()));
                session.gui.controller = controller;
                keys.set(mockStatic(MegaMekGUI.class, CALLS_REAL_METHODS));
                keys.get().when(MegaMekGUI::getKeyDispatcher).thenReturn(controller);
                FiringDisplay phase = new FiringDisplay(session.gui);
                session.view.addBoardViewListener(phase);
                phase.selectEntity(fixture.entity.getId());
                return phase;
            });
            try {
                int weapon = GpuTestSupport.onSwing(() -> display.getSelectedWeaponNum());
                assertEquals(fixture.entity.getId(), display.getSelectedEntityId());
                session.source.setCardUnit(enemy.getId());
                session.source.record().setSheetOpen(true);
                assertEquals(enemy.getId(), session.record().unitId());
                assertEquals(fixture.entity.getId(), GpuTestSupport.onSwing(display::getSelectedEntityId),
                      "The record sheet never shows its unit in the firing display's weapon panel");
                assertEquals(weapon, (int) GpuTestSupport.onSwing(display::getSelectedWeaponNum));
            } finally {
                GpuTestSupport.onSwing(() -> {
                    firing.removeAllListeners();
                    keys.get().close();
                    return null;
                });
            }
        }
    }

    private static List<String> weapons(GpuUnitRecord.Snapshot record) {
        return record.weapons().stream().map(weapon -> (weapon.name() + " " + weapon.location() + " " + weapon.shots())
              .strip()).toList();
    }

    private static List<String> texts(GpuUnitRecord.Location location) {
        return location.slots().stream().map(GpuUnitRecord.Slot::text).toList();
    }

    /** The location's ammunition slots: name, shots and full shots. */
    private static List<String> ammoSlots(GpuUnitRecord.Location location) {
        return location.slots().stream().filter(slot -> slot.shots() >= 0)
              .map(slot -> slot.text() + " " + slot.shots() + "/" + slot.fullShots()).toList();
    }

    /** The center torso's slots from {@code from} to {@code to} (exclusive): name, armored, armor hit. */
    private static List<String> armoredSlots(GpuUnitRecord.Snapshot record, int from, int to) {
        return location(record, "CT").slots().subList(from, to).stream()
              .map(slot -> slot.text() + " " + slot.armored() + " " + slot.armorHit()).toList();
    }

    /** The indices of the location's slots in the given state. */
    private static List<Integer> indices(GpuUnitRecord.Location location, Predicate<GpuUnitRecord.Slot> state) {
        return location.slots().stream().filter(state).map(GpuUnitRecord.Slot::index).toList();
    }

    private static GpuUnitRecord.Location location(GpuUnitRecord.Snapshot record, String abbr) {
        return record.locations().stream().filter(location -> location.abbr().equals(abbr)).findFirst()
              .orElseThrow();
    }

    private static GpuUnitRecord.HitTrack track(GpuUnitRecord.Snapshot record, GpuUnitRecord.Track track) {
        return record.hitTracks().stream().filter(hits -> hits.track() == track).findFirst().orElseThrow();
    }

    /** The vitals of the groups the filter accepts, as "GROUP CODE value/max text". */
    private static List<String> vitals(GpuUnitRecord.Snapshot record, Predicate<GpuUnitRecord.VitalGroup> groups) {
        return record.vitals().stream().filter(vital -> groups.test(vital.group()))
              .map(vital -> (vital.group() + " " + vital.code() + " " + vital.value() + "/" + vital.max() + " "
                    + vital.text()).strip()).toList();
    }

    private static List<GpuUnitRecord.InfoRow> info(GpuUnitRecord.Snapshot record,
          GpuUnitRecord.InfoSection section) {
        return record.info().stream().filter(row -> row.section() == section).toList();
    }

    private static GpuUnitRecord.AmmoBin bin(GpuUnitRecord.Snapshot record, int eqNum) {
        return record.ammo().stream().filter(bin -> bin.eqNum() == eqNum).findFirst().orElseThrow();
    }

    private static GpuUnitRecord.Equipment equipment(GpuUnitRecord.Snapshot record, int eqNum) {
        return record.equipment().stream().filter(item -> item.eqNum() == eqNum).findFirst().orElseThrow();
    }

    private static GpuUnitRecord.SystemControl system(GpuUnitRecord.Snapshot record, String id) {
        return record.systems().stream().filter(control -> control.id().equals(id)).findFirst().orElseThrow();
    }

    private static Entity unit(String file) throws Exception {
        return new MekFileParser(new File(UNITS + file)).getEntity();
    }

    /** A unit from the staged data's official units. */
    private static Entity official(String entry) throws Exception {
        return new MekFileParser(new File(Configuration.dataDir(), "mekfiles/unit_files.zip"), entry).getEntity();
    }

    /** A board source over the fixture's view whose client GUI and client are mocks, as a local player's game. */
    private static final class Session implements AutoCloseable {
        final Client client = mock(Client.class);
        final ClientGUI gui = mock(ClientGUI.class);
        final BoardClientState view;
        final GpuBoardSource source;
        private int nextId = 100;

        Session(GpuBoardFixture fixture) throws Exception {
            when(client.getGame()).thenReturn(fixture.game);
            when(client.getLocalPlayer()).thenReturn(fixture.player);
            when(gui.getClient()).thenReturn(client);
            when(gui.doYesNoDialog(anyString(), anyString())).thenReturn(true);
            CommonMenuBar menu = mock(CommonMenuBar.class);
            when(menu.getComponents()).thenReturn(new Component[0]);
            when(gui.getMenuBar()).thenReturn(menu);
            view = spy(fixture.view);
            doReturn(gui).when(view).getClientgui();
            source = GpuTestSupport.onSwing(() -> {
                fixture.source.close();
                return new GpuBoardSource(view, () -> fixture.panel);
            });
        }

        Entity addEnemy(GpuBoardFixture fixture) throws Exception {
            Player enemy = new Player(2, "Enemy");
            enemy.setTeam(2);
            Entity atlas = unit("Atlas AS7-D.mtf");
            GpuTestSupport.onSwing(() -> {
                fixture.game.addPlayer(enemy.getId(), enemy);
                atlas.setId(42);
                atlas.setOwner(enemy);
                atlas.setPosition(new Coords(5, 3));
                atlas.setDeployed(true);
                fixture.game.addEntity(atlas, false);
                atlas.addBeenSeenBy(fixture.player);
                return null;
            });
            return atlas;
        }

        /** EDT: adds the unit as the local player's, damages it, and captures its record. */
        GpuUnitRecord.Snapshot capture(GpuBoardFixture fixture, Entity unit, Consumer<Entity> damage)
              throws Exception {
            return GpuTestSupport.onSwing(() -> {
                unit.setId(nextId++);
                unit.setOwner(fixture.player);
                unit.setPosition(new Coords(2, 2));
                unit.setDeployed(true);
                fixture.game.addEntity(unit, false);
                damage.accept(unit);
                return source.record().capture(unit.getId());
            });
        }

        /** EDT: captures now and returns the frame's record. */
        GpuUnitRecord.Snapshot record() throws Exception {
            SwingUtilities.invokeAndWait(source::refresh);
            return source.takeFrame().panels().record();
        }

        /** Lets every command posted so far run on the EDT. */
        void flush() throws Exception {
            SwingUtilities.invokeAndWait(() -> { });
        }

        @Override
        public void close() throws Exception {
            SwingUtilities.invokeAndWait(source::close);
        }
    }
}
