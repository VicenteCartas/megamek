/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.swing.SwingUtilities;

import megamek.client.event.BoardViewEvent;
import megamek.client.event.BoardViewListenerAdapter;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.LabelDisplayStyle;
import megamek.client.ui.clientGUI.boardview.UnitStatusWords;
import megamek.client.ui.clientGUI.boardview.UnitStatusWords.Severity;
import megamek.client.ui.panels.phaseDisplay.FiringDisplay;
import megamek.client.ui.util.PlayerColour;
import megamek.common.HexTarget;
import megamek.common.Player;
import megamek.common.Team;
import megamek.common.actions.ChargeAttackAction;
import megamek.common.actions.RamAttackAction;
import megamek.common.actions.SearchlightAttackAction;
import megamek.common.actions.TorsoTwistAction;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.IArmorState;
import megamek.common.equipment.NarcPod;
import megamek.common.force.Force;
import megamek.common.game.Game;
import megamek.common.game.GameTurn;
import megamek.common.game.InitiativeRoll;
import megamek.common.loaders.MekFileParser;
import megamek.common.options.OptionsConstants;
import megamek.common.planetaryConditions.Fog;
import megamek.common.turns.SpecificEntityTurn;
import megamek.common.turns.UnloadStrandedTurn;
import megamek.common.units.Aero;
import megamek.common.units.AeroSpaceFighter;
import megamek.common.units.Entity;
import megamek.common.units.EntityClassTurn;
import megamek.common.units.EntityMovementType;
import megamek.common.units.EntityWeightClass;
import megamek.common.units.Infantry;
import megamek.common.units.InfantryCompartment;
import megamek.common.units.Mek;
import megamek.common.units.Tank;
import megamek.common.units.Targetable;
import megamek.server.totalWarfare.TWGameManager;
import org.junit.jupiter.api.Test;

class GpuBattleStatusTest {
    @Test
    void ownUnitCarriesItsStatusAndSharesTheSceneArtwork() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                Entity atlas = fixture.entity;
                atlas.getCrew().setName("Tamsin Gray", 0);
                atlas.getCrew().setGunnery(3, 0);
                atlas.getCrew().setPiloting(4, 0);
                join(fixture.game, fixture.player, "Alpha Lance", atlas);
                atlas.heat = 8;
                atlas.moved = EntityMovementType.MOVE_WALK;
                atlas.mpUsed = 4;
                atlas.delta_distance = 3;
                fixture.game.setRoundCount(3);
                fixture.source.refresh();
            });
            GpuBoardSource.Frame frame = fixture.source.takeFrame();
            GpuBattleStatus.Snapshot status = frame.status();
            assertEquals(3, status.round());
            assertEquals(GamePhase.MOVEMENT, status.phase());
            assertEquals(fixture.player.getId(), status.localPlayerId());
            GpuBattleStatus.UnitStatus unit = unit(status, 1);
            assertEquals(GpuBattleStatus.Side.OWN, unit.side());
            assertFalse(unit.sensorContact());
            assertEquals("Atlas AS7-D", unit.name());
            assertEquals("Atlas", unit.chassis());
            assertEquals("AS7-D", unit.model());
            assertEquals(100, unit.tons());
            assertEquals("Assault", unit.weightClass());
            assertEquals("Alpha Lance", unit.formation());
            assertEquals("Tamsin Gray", unit.pilot());
            assertEquals(3, unit.gunnery());
            assertEquals(4, unit.piloting());
            assertEquals(1, unit.armor());
            assertEquals(1, unit.structure());
            assertEquals(8, unit.heat());
            assertEquals("20", unit.heatCapacity(), "Twenty single heat sinks, as the unit tooltip shows them");
            assertEquals(Messages.getString("HeatEffects.8"), unit.heatEffects());
            assertEquals(2, unit.walk(), "Current MP, after the -1 MP of heat 8, as the unit display shows it");
            assertEquals("3", unit.run());
            assertEquals(0, unit.jump());
            assertEquals("Walked", unit.moved());
            assertEquals(4, unit.mpUsed());
            assertEquals(3, unit.hexesMoved(), "Hexes moved differ from MP spent, as the unit tooltip shows");
            assertEquals(1, unit.tmm(), "Three hexes moved");
            assertEquals(Entity.DMG_NONE, unit.damageLevel());
            assertFalse(unit.destroyed());
            assertTrue(unit.destroyedLocations().isEmpty());
            assertEquals(new Coords(5, 5), unit.position());
            assertEquals(0, unit.boardId());
            assertSame(frame.scene().units().getFirst().image(), unit.icon(), "One pixel copy serves board and HUD");

            SwingUtilities.invokeAndWait(() -> {
                fixture.entity.setInternal(IArmorState.ARMOR_DESTROYED, Mek.LOC_LEFT_ARM);
                fixture.entity.setDoomed(true);
                fixture.entity.heat = 0;
                fixture.source.refresh();
            });
            GpuBattleStatus.UnitStatus doomed = unit(fixture.source.takeFrame().status(), 1);
            assertTrue(doomed.destroyed(), "A unit killed this phase stays listed until the next phase, as destroyed");
            assertEquals(List.of("LA"), doomed.destroyedLocations());
            assertEquals("", doomed.heatEffects(), "Heat 0 has no effect, which the heat table words as None");
        }
    }

    /**
     * The Tactical View's facts: the hovered enemy's arcs are its own side table for
     * every hex within reach, kept while nothing they depend on changes, and gone with the pointer; an own spotter links
     * to its target; the own unit carries its twist, its movement colour and whether its modifier shows.
     */
    @Test
    void theHoveredEnemysArcsAreItsSideTableAndSpottersLinkToTheirTargets() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            AtomicReference<Entity> warwolf = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                Player enemy = enemy(fixture.game);
                warwolf.set(add(fixture, enemy, "Warwolf A.mtf", 42, new Coords(8, 8)));
                warwolf.get().setFacing(2);
                fixture.entity.setFacing(0);
                fixture.entity.setSecondaryFacing(1);
                fixture.entity.moved = EntityMovementType.MOVE_RUN;
                fixture.entity.setDone(true);
                fixture.entity.setSpotting(true);
                fixture.entity.setSpotTargetId(42);
                fixture.source.setHover(new Coords(8, 8));
                fixture.source.refresh();
            });
            GpuBattleStatus.Snapshot status = fixture.source.takeFrame().status();
            GpuBattleStatus.Arc arc = status.arc();
            assertEquals(42, arc.unitId(), "The hovered enemy's arcs");
            assertEquals(new Coords(8, 8), arc.origin());
            SwingUtilities.invokeAndWait(() -> {
                var board = fixture.game.getBoard();
                int reach = 0;
                for (int distance = 1; distance <= GpuBattleStatus.Arc.REACH; distance++) {
                    for (Coords hex : new Coords(8, 8).allAtDistance(distance)) {
                        if (!board.contains(hex)) { continue; }
                        reach++;
                        assertEquals(warwolf.get().sideTable(hex), arc.sides().get(hex), "The side table at " + hex);
                    }
                }
                assertEquals(reach, arc.sides().size(), "Every hex on the board within reach, and none beyond");
                fixture.source.refresh();
            });
            assertSame(arc, fixture.source.takeFrame().status().arc(), "Unchanged arcs keep their snapshot");
            assertEquals(List.of(new GpuBattleStatus.Link(1, 42, GpuBattleStatus.Link.Kind.SPOT)), status.links(),
                  "The spotter links to its target");
            GpuBattleStatus.UnitStatus own = unit(status, 1);
            assertEquals(1, own.twist(), "The twist is the secondary facing");
            assertEquals(GUIPreferences.getInstance().getColorForMovement(EntityMovementType.MOVE_RUN).getRGB(),
                  own.moveRgb());
            assertTrue(own.tmmShown(), "Done in the movement phase, the modifier shows");
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.setHover(new Coords(1, 1));
                fixture.source.refresh();
            });
            assertEquals(GpuBattleStatus.Arc.NONE, fixture.source.takeFrame().status().arc(),
                  "No arcs without a hovered or focused enemy");
        }
    }

    @Test
    void enemiesFollowTheClientDisclosureAndSensorContactsStayAnonymous() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                Player ally = ally(fixture);
                Player enemy = enemy(fixture.game);
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(true);
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_TAC_OPS_SENSORS).setValue(true);
                Entity seen = add(fixture, enemy, 42, new Coords(8, 8));
                seen.addBeenSeenBy(fixture.player);
                seen.getCrew().setName("Ria Voss", 0);
                join(fixture.game, enemy, "Red Lance", seen);
                add(fixture, enemy, 43, new Coords(10, 10));
                Entity contact = add(fixture, enemy, 44, new Coords(12, 12));
                contact.addBeenDetectedBy(fixture.player);
                contact.setInternal(IArmorState.ARMOR_DESTROYED, Mek.LOC_LEFT_ARM);
                contact.heat = 12;
                add(fixture, ally, 50, new Coords(3, 3));
                fixture.source.refresh();
            });
            GpuBoardSource.Frame frame = fixture.source.takeFrame();
            GpuBattleStatus.Snapshot status = frame.status();
            assertEquals(Set.of(1, 42, 44, 50), status.units().stream().map(GpuBattleStatus.UnitStatus::id)
                  .collect(Collectors.toSet()), "The unseen enemy is left out");
            GpuBattleStatus.UnitStatus visible = unit(status, 42);
            assertEquals(GpuBattleStatus.Side.ENEMY, visible.side());
            assertEquals("Atlas AS7-D", visible.name());
            assertEquals("Ria Voss", visible.pilot());
            assertEquals("Red Lance", visible.formation());
            assertEquals(1, visible.armor(), "Visible enemies show what the unit display shows");
            assertEquals(GpuBattleStatus.Side.ALLY, unit(status, 50).side());
            GpuBattleStatus.UnitStatus contact = unit(status, 44);
            assertTrue(contact.sensorContact());
            List<String> hidden = List.of(contact.name(), contact.chassis(), contact.model(), contact.weightClass(),
                  contact.formation(), contact.pilot(), contact.heatCapacity(), contact.moved(), contact.heatEffects());
            assertTrue(hidden.stream().allMatch(String::isEmpty), "A contact discloses no identity: " + hidden);
            assertEquals(IArmorState.ARMOR_NA, contact.armor());
            assertEquals(IArmorState.ARMOR_NA, contact.structure());
            assertEquals(0, contact.tons());
            assertEquals(0, contact.heat());
            assertEquals(0, contact.heatRgb());
            assertEquals(-1, contact.facing());
            assertTrue(contact.destroyedLocations().isEmpty());
            assertEquals(new Coords(12, 12), contact.position());
            assertEquals(Player.PLAYER_NONE, contact.ownerId(), "Not even its owner is disclosed");
            assertTrue(contact.statusTiles().isEmpty());
            assertEquals(GpuBattleStatus.Marks.NONE, contact.marks(), "Nor its damage, armor or structure");
            assertSame(frame.scene().units().stream().filter(unit -> unit.id() == 44).findFirst().orElseThrow().image(),
                  contact.icon(), "A contact shows the same radar blip as the board");
        }
    }

    @Test
    void turnSlotsAndFlagsFollowTheClientAndTheRealTurnTypes() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            AtomicReference<GameTurn> mine = new AtomicReference<>();
            AtomicReference<GpuBoardSource> source = new AtomicReference<>();
            AtomicReference<Player> enemy = new AtomicReference<>();
            AtomicReference<Player> ally = new AtomicReference<>();
            ClientGUI gui = GpuUnitHudTest.gui(fixture);
            when(gui.getClient().isMyTurn()).thenAnswer(invocation -> mine.get() != null);
            when(gui.getClient().getMyTurn()).thenAnswer(invocation -> mine.get());
            SwingUtilities.invokeAndWait(() -> {
                source.set(clientSource(fixture, gui, new AtomicReference<>()));
                ally.set(ally(fixture));
                enemy.set(enemy(fixture.game));
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(true);
                add(fixture, enemy.get(), 42, new Coords(8, 8)).addBeenSeenBy(fixture.player);
                add(fixture, enemy.get(), 43, new Coords(10, 10));
                fixture.game.setTurnVector(List.of(new GameTurn(2), new SpecificEntityTurn(0, 1), new GameTurn(1),
                      new SpecificEntityTurn(2, 42), new SpecificEntityTurn(2, 43)));
                fixture.game.setTurnIndex(0, Player.PLAYER_NONE);
                source.get().refresh();
            });
            try {
                GpuBattleStatus.Snapshot status = source.get().takeFrame().status();
                assertEquals(List.of(slot(enemy.get(), GpuBattleStatus.Side.ENEMY, Entity.NONE),
                      slot(fixture.player, GpuBattleStatus.Side.OWN, 1),
                      slot(ally.get(), GpuBattleStatus.Side.ALLY, Entity.NONE),
                      slot(enemy.get(), GpuBattleStatus.Side.ENEMY, 42),
                      slot(enemy.get(), GpuBattleStatus.Side.ENEMY, Entity.NONE)), status.turns(),
                      "Only the seen enemy's turn names its unit; the ally's turn is not an opponent turn");
                assertEquals(0, status.turnIndex());
                assertFalse(status.myTurn());
                assertFlags(status, false, true, false);
                assertFalse(unit(status, 42).canActNow(), "Only the local player's turn lets a unit act now");
                assertTrue(unit(status, 42).pending());

                SwingUtilities.invokeAndWait(() -> {
                    fixture.game.setTurnIndex(1, 2);
                    mine.set(fixture.game.getTurn());
                    source.get().refresh();
                });
                status = source.get().takeFrame().status();
                assertTrue(status.myTurn());
                assertFlags(status, true, true, false);

                SwingUtilities.invokeAndWait(() -> {
                    fixture.entity.setDone(true);
                    source.get().refresh();
                });
                assertFlags(source.get().takeFrame().status(), false, false, true);

                SwingUtilities.invokeAndWait(() -> {
                    fixture.entity.setDone(false);
                    mine.set(null);
                    fixture.game.setTurnVector(List.of(new GameTurn(2), new UnloadStrandedTurn(1)));
                    fixture.game.setTurnIndex(0, Player.PLAYER_NONE);
                    source.get().refresh();
                });
                status = source.get().takeFrame().status();
                assertEquals(new GpuBattleStatus.Slot(Player.PLAYER_NONE, "", 0, GpuBattleStatus.Side.OWN,
                      Entity.NONE), status.turns().get(1), "The stranded unit's owner takes this playerless turn");
                assertFlags(status, false, true, false);

                // The initiative report keeps the round's order in the turn list; a report phase gives no unit a turn
                // (shot 01: "5 / 5 operational" and no unit up next).
                SwingUtilities.invokeAndWait(() -> {
                    fixture.game.setPhase(GamePhase.INITIATIVE_REPORT);
                    fixture.game.setTurnVector(List.of(new GameTurn(1), new GameTurn(2)));
                    fixture.game.setTurnIndex(0, Player.PLAYER_NONE);
                    source.get().refresh();
                });
                status = source.get().takeFrame().status();
                assertEquals(2, status.turns().size(), "The order stays listed");
                assertFlags(status, false, false, false);
            } finally {
                SwingUtilities.invokeAndWait(source.get()::close);
            }
        }
    }

    @Test
    void boardLabelWordsKeepTheirTextAndSeverity() throws Exception {
        // Literal values of the label words before they were extracted from EntitySprite (stage E1 characterization)
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                Entity atlas = fixture.entity;
                assertEquals(List.of(), UnitStatusWords.statusWords(atlas, false));
                atlas.setProne(true);
                assertEquals(List.of(word("PRONE", "PRONE", Severity.CAUTION)), UnitStatusWords.statusWords(atlas, false));
                atlas.setProne(false);
                atlas.setShutDown(true);
                assertEquals(List.of(word("SHUTDOWN", "SD", Severity.WARNING),
                      word("IMMOBILE", "IMMOBILE", Severity.WARNING)), UnitStatusWords.statusWords(atlas, false));
                atlas.setManualShutdown(true);
                assertEquals(List.of(word("SHUTDOWN", "SD", Severity.CAUTION),
                      word("IMMOBILE", "IMMOBILE", Severity.WARNING)), UnitStatusWords.statusWords(atlas, false));
                atlas.setManualShutdown(false);
                atlas.setShutDown(false);
                assertEquals(List.of(word("Jammed", "JAMMED", Severity.CAUTION)),
                      UnitStatusWords.statusWords(atlas, true));

                Infantry infantry = (Infantry) add(fixture, fixture.player, "Foot Platoon (AFFS) (Laser 3067+).blk",
                      60, new Coords(7, 7));
                infantry.setDugIn(Infantry.DUG_IN_WORKING);
                infantry.turnsLayingExplosives = 1;
                assertEquals(List.of(word("Working", "Working", Severity.PRECAUTION),
                      word("Rigging", "Rigging 1/6", Severity.PRECAUTION)), UnitStatusWords.statusWords(infantry, false),
                      "The dug-in and explosives tiles are not words");
                Aero fighter = (Aero) add(fixture, fixture.player, "Chippewa CHP-W7.blk", 62, new Coords(11, 7));
                fighter.setAltitude(3);
                fighter.setEvading(true);
                fighter.setCurrentFuel(0);
                assertEquals(List.of(word("FUEL", "FUEL", Severity.WARNING), word("EVADE", "EVADE", Severity.INFO)),
                      UnitStatusWords.statusWords(fighter, false), "The altitude tiles are not words");
            });
        }
    }

    @Test
    void unitsCarryTheirLabelWordsWeightClassAndDeclaredAttacks() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.setPhase(GamePhase.FIRING);
                Entity jammer = add(fixture, enemy(fixture.game), 42, new Coords(5, 7));
                try {
                    jammer.addEquipment(EquipmentType.get("ISGuardianECMSuite"), Mek.LOC_LEFT_ARM);
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
                fixture.entity.setProne(true);
                Entity atlas = fixture.entity;
                atlas.setElevation(2);
                for (int weapon = 0; weapon < 2; weapon++) {
                    fixture.game.addAction(new WeaponAttackAction(1, 42,
                          atlas.getEquipmentNum(atlas.getWeaponList().get(weapon))));
                }
                fixture.game.addAction(new TorsoTwistAction(1, 1));
                // Illuminating a target is no attack
                fixture.game.addAction(new SearchlightAttackAction(1, Targetable.TYPE_ENTITY, 42));
                // Artillery aimed at a hex: the board shows only the local team's (BoardView.addAttack)
                int hex = new HexTarget(new Coords(8, 8), Targetable.TYPE_HEX_ARTILLERY).getId();
                fixture.game.addAction(new WeaponAttackAction(1, Targetable.TYPE_HEX_ARTILLERY, hex,
                      atlas.getEquipmentNum(atlas.getWeaponList().getFirst())));
                fixture.game.addAction(new WeaponAttackAction(42, Targetable.TYPE_HEX_ARTILLERY, hex,
                      jammer.getEquipmentNum(jammer.getWeaponList().getFirst())));
                // A carried handheld weapon fires for the Atlas, as the board draws it
                Entity handheld = add(fixture, fixture.player, "TestHandheldWeapon.blk", 70, null);
                handheld.setTransportId(1);
                fixture.game.addAction(new WeaponAttackAction(70, 42,
                      handheld.getEquipmentNum(handheld.getWeaponList().getFirst())));
                // Declared while moving, in lists of their own, and resolved at the end of the physical phase
                fixture.game.addDisplacementAttack(new ChargeAttackAction(jammer, atlas));
                Entity fighter = add(fixture, fixture.player, "Chippewa CHP-W7.blk", 62, new Coords(9, 5));
                fixture.game.addRam(new RamAttackAction(fighter, jammer));
                fixture.source.refresh();
            });
            GpuBattleStatus.Snapshot status = fixture.source.takeFrame().status();
            GpuBattleStatus.UnitStatus own = unit(status, 1);
            assertEquals(List.of(word("PRONE", "PRONE", Severity.CAUTION), word("Jammed", "JAMMED", Severity.CAUTION)),
                  own.statusWords(), "The enemy Guardian ECM two hexes away jams it, as its board label says");
            assertEquals(List.of(word("ELEV", "2", Severity.INFO)), own.statusTiles(), "Its label's elevation tile");
            assertEquals(List.of(fixture.player.getId(), 2), List.of(own.ownerId(), unit(status, 42).ownerId()));
            assertEquals(EntityWeightClass.WEIGHT_ASSAULT, own.weightClassIndex());
            assertEquals(4, own.declaredAttacks(),
                  "Two weapon attacks, its artillery attack on a hex and its handheld weapon's; the twist and the"
                        + " searchlight are no attacks");
            assertEquals(List.of(), unit(status, 42).statusWords(), "Its own ECM does not jam the enemy");
            assertEquals(List.of(0, 0), List.of(unit(status, 42).declaredAttacks(), unit(status, 62).declaredAttacks()),
                  "The enemy's artillery attack on a hex stays secret; a charge and a ram are no firing attacks");
            assertFalse(status.turnOrderHidden());
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            assertSame(status, fixture.source.takeFrame().status(),
                  "An unchanged capture keeps its identity for the renderer");

            // Players without a team are enemies (Player.isEnemyOf), so the other one's artillery stays secret
            SwingUtilities.invokeAndWait(() -> {
                fixture.player.setTeam(Player.TEAM_NONE);
                fixture.game.getPlayer(2).setTeam(Player.TEAM_NONE);
                fixture.source.refresh();
            });
            GpuBattleStatus.Snapshot noTeams = fixture.source.takeFrame().status();
            assertEquals(GpuBattleStatus.Side.ENEMY, unit(noTeams, 42).side());
            assertEquals(List.of(4, 0),
                  List.of(unit(noTeams, 1).declaredAttacks(), unit(noTeams, 42).declaredAttacks()));

            SwingUtilities.invokeAndWait(() -> {
                fixture.game.setPhase(GamePhase.PHYSICAL);
                fixture.source.refresh();
            });
            GpuBattleStatus.Snapshot physical = fixture.source.takeFrame().status();
            assertEquals(List.of(0, 1, 1), List.of(unit(physical, 1).declaredAttacks(),
                  unit(physical, 42).declaredAttacks(), unit(physical, 62).declaredAttacks()),
                  "The weapon attacks are resolved; the charge and the ram are this phase's attacks");

            SwingUtilities.invokeAndWait(() -> {
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(true);
                fixture.source.refresh();
            });
            assertTrue(fixture.source.takeFrame().status().turnOrderHidden(),
                  "Under double blind the server does not report the turn order either");
        }
    }

    @Test
    void initiativeSidesAreTheRollsOfTheServersInitiativeReport() throws Exception {
        // No Server runs, so the manager sends nothing; its roll and report code runs as in a game
        TWGameManager manager = spy(new TWGameManager());
        doNothing().when(manager).transmitAllPlayerUpdates();
        Game serverGame = manager.getGame();
        // The manager loads the developer's saved mmconf/gameoptions.xml; this test needs team initiative
        serverGame.getOptions().getOption(OptionsConstants.BASE_TEAM_INITIATIVE).setValue(true);
        serverGame.getOptions().getOption(OptionsConstants.RPG_INDIVIDUAL_INITIATIVE).setValue(false);
        // Names with markup characters: the report writes them raw or inside the player's colour tags
        Player local = new Player(0, "GPU review");
        Player wingman = new Player(1, "Wingman & <Co>");
        Player enemy = new Player(2, "Enemy <3");
        local.setTeam(1);
        wingman.setTeam(1);
        enemy.setTeam(2);
        enemy.setColour(PlayerColour.RED);
        for (Player player : List.of(local, wingman, enemy)) {
            serverGame.addPlayer(player.getId(), player);
        }
        serverGame.setRoundCount(1);
        serverGame.setPhase(GamePhase.INITIATIVE);
        // The server's own roll and report (package-private in TWGameManager)
        Method roll = TWGameManager.class.getDeclaredMethod("rollInitiative");
        Method report = TWGameManager.class.getDeclaredMethod("writeInitiativeReport", boolean.class);
        roll.setAccessible(true);
        report.setAccessible(true);
        roll.invoke(manager);
        report.invoke(manager, false);

        // The client game gets serialized copies of the players and the phase report, which the player update and
        // report packets carry. No packet carries the server's Team rolls (code reading, not checked here).
        Game client = new Game();
        client.setRoundCount(1);
        for (Player player : serverGame.getPlayersList()) {
            client.addPlayer(player.getId(), sent(player));
        }
        client.addReports(sent(new ArrayList<>(manager.getMainPhaseReport())));
        Team ownTeam = serverGame.getTeamForPlayer(local);
        Team enemyTeam = serverGame.getTeamForPlayer(enemy);
        int red = PlayerColour.RED.getColour().getRGB();
        List<GpuBattleStatus.InitiativeSide> sides = initiative(client);
        assertEquals(List.of(side("Team 1", 0, GpuBattleStatus.Side.OWN, ownTeam.getInitiative(), 0, 1),
              side("Enemy <3", red, GpuBattleStatus.Side.ENEMY, enemyTeam.getInitiative(), 2)),
              sides, "The team line stands for both team members, whose own lines are skipped");
        assertSame(sides.get(ownTeam.getInitiative().compareTo(enemyTeam.getInitiative()) > 0 ? 0 : 1),
              GpuBattleStatus.winner(sides), "The server's roll order names the winner");

        // A Tactical Genius reroll: the server rerolls and sends a short second report, which replaces the first
        serverGame.addInitiativeRerollRequest(enemyTeam);
        serverGame.rollInitAndResolveTies();
        manager.getMainPhaseReport().clear();
        report.invoke(manager, true);
        client.addReports(sent(new ArrayList<>(manager.getMainPhaseReport())));
        assertTrue(enemyTeam.getInitiative().toString().contains("Tactical Genius"));
        assertEquals(List.of(side("Team 1", 0, GpuBattleStatus.Side.OWN, ownTeam.getInitiative(), 0, 1),
              side("Enemy <3", red, GpuBattleStatus.Side.ENEMY, enemyTeam.getInitiative(), 2)), initiative(client));
    }

    @Test
    void theInitiativeWinnerHasTheBestRollsAndATieHasNone() {
        GpuBattleStatus.InitiativeSide four = rolled(4);
        GpuBattleStatus.InitiativeSide tenThenFive = rolled(10, 5);
        GpuBattleStatus.InitiativeSide tenThenSeven = rolled(10, 7);
        assertSame(tenThenSeven, GpuBattleStatus.winner(List.of(four, tenThenSeven, tenThenFive)),
              "The higher total, then the higher tie-break");
        assertNull(GpuBattleStatus.winner(List.of(rolled(10), rolled(10), four)), "Two sides share the best total");
        assertNull(GpuBattleStatus.winner(List.of()));
    }

    @Test
    void phaseInfoCarriesTheOverlayLinesAsPlainText() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean defaults = preferences.getPlanetaryConditionsShowDefaults();
        boolean labels = preferences.getPlanetaryConditionsShowLabels();
        boolean values = preferences.getPlanetaryConditionsShowValues();
        boolean indicators = preferences.getPlanetaryConditionsShowIndicators();
        // Mocks are created on the test thread; the inline mock maker may fail to attach on the EDT.
        FiringDisplay phase = mock(FiringDisplay.class);
        when(phase.getTurnDetails()).thenReturn(List.of("#E6E9E9x2  FORWARDS       \u2191  2MP*",
              "#A1A1A1    TURN_LEFT      \u21B0  1MP"));
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            GpuBoardActions actions = new GpuBoardActions(fixture.view, () -> phase, () -> false, () -> { });
            AtomicReference<GpuBoardActions.PhaseInfo> info = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                preferences.setValue(GUIPreferences.PLANETARY_CONDITIONS_SHOW_DEFAULTS, false);
                preferences.setValue(GUIPreferences.PLANETARY_CONDITIONS_SHOW_LABELS, false);
                preferences.setValue(GUIPreferences.PLANETARY_CONDITIONS_SHOW_VALUES, true);
                preferences.setValue(GUIPreferences.PLANETARY_CONDITIONS_SHOW_INDICATORS, false);
                info.set(actions.phaseInfo(List.of()));
            });
            assertEquals(List.of("x2  FORWARDS       \u2191  2MP*", "TURN_LEFT      \u21B0  1MP"),
                  info.get().turnDetails(), "The overlay colour codes are dropped");
            assertEquals(List.of(), info.get().conditions(), "Default conditions are hidden, as on the overlay");

            SwingUtilities.invokeAndWait(() -> {
                fixture.game.getPlanetaryConditions().setFog(Fog.FOG_HEAVY);
                info.set(actions.phaseInfo(List.of()));
            });
            assertEquals(List.of("Heavy Fog"), info.get().conditions());

            // The frame's panel bundle carries the lines and keeps its identity while they do not change
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            GpuHudData panels = fixture.source.takeFrame().panels();
            assertEquals(List.of("Heavy Fog"), panels.phase().conditions());
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            assertSame(panels, fixture.source.takeFrame().panels(), "An unchanged capture keeps the panel bundle");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                preferences.setValue(GUIPreferences.PLANETARY_CONDITIONS_SHOW_DEFAULTS, defaults);
                preferences.setValue(GUIPreferences.PLANETARY_CONDITIONS_SHOW_LABELS, labels);
                preferences.setValue(GUIPreferences.PLANETARY_CONDITIONS_SHOW_VALUES, values);
                preferences.setValue(GUIPreferences.PLANETARY_CONDITIONS_SHOW_INDICATORS, indicators);
            });
        }
    }

    @Test
    void selectAndLocateReachTheBoardOnlyForUnitsTheLocalPlayerMayUse() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            List<Integer> selected = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                fixture.view.addBoardViewListener(new BoardViewListenerAdapter() {
                    @Override
                    public void unitSelected(BoardViewEvent event) {
                        selected.add(event.getEntityId());
                    }
                });
                Player enemy = enemy(fixture.game);
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(true);
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_TAC_OPS_SENSORS).setValue(true);
                add(fixture, enemy, 43, new Coords(10, 10));
                add(fixture, enemy, 44, new Coords(12, 12)).addBeenDetectedBy(fixture.player);
            });
            for (int id : new int[] { 1, 43, 44, 99 }) {
                fixture.source.selectUnit(id);
            }
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(List.of(1), selected, "Hidden units and anonymous contacts are never selected");

            fixture.source.locateUnit(43);
            SwingUtilities.invokeAndWait(() -> { });
            assertNotEquals(new Coords(10, 10), fixture.view.getCenterRequest().coords(),
                  "A hidden unit is not located");
            fixture.source.locateUnit(44);
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(new Coords(12, 12), fixture.view.getCenterRequest().coords(), "Its blip is already shown");
            fixture.source.locateUnit(1);
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(new Coords(5, 5), fixture.view.getCenterRequest().coords());
            assertEquals(fixture.view.getCenterRequest(), fixture.source.takeFrame().centerRequest());

            SwingUtilities.invokeAndWait(fixture.source::close);
            fixture.source.selectUnit(1);
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(List.of(1), selected);
        }
    }

    @Test
    void locateShowsTheBoardOfAnOwnUnitThroughTheClient() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            AtomicReference<GpuBoardSource> source = new AtomicReference<>();
            AtomicReference<BoardClientState> other = new AtomicReference<>();
            AtomicReference<BoardClientState> shown = new AtomicReference<>();
            // The real ClientGUI.centerOnUnit runs; the stubs stand in for its board container.
            ClientGUI gui = GpuUnitHudTest.gui(fixture);
            doCallRealMethod().when(gui).centerOnUnit(any());
            doAnswer(invocation -> {
                shown.set(other.get());
                return null;
            }).when(gui).showBoardView(1);
            when(gui.getBoardState(any(BoardLocation.class))).thenAnswer(invocation -> shown.get());
            SwingUtilities.invokeAndWait(() -> {
                Board second = new Board();
                second.load(new File("data/boards/AGoAC Maps/16x17 Grassland 2.board"));
                second.setBoardId(1);
                fixture.game.setBoard(1, second);
                try {
                    other.set(new BoardClientState(fixture.game, null, null, 1, null));
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
                other.get().setLocalPlayer(fixture.player.getId());
                add(fixture, fixture.player, 2, new Coords(3, 4)).setBoardId(1);
                source.set(clientSource(fixture, gui, shown));
            });
            try {
                source.get().locateUnit(2);
                SwingUtilities.invokeAndWait(() -> { });
                GpuBoardSource.Frame frame = source.get().takeFrame();
                assertEquals(1, frame.scene().boardId(),
                      "The own unit's board is shown, as the Legacy 2D overview does");
                assertEquals(new Coords(3, 4), frame.centerRequest().coords());
            } finally {
                SwingUtilities.invokeAndWait(() -> {
                    source.get().close();
                    other.get().close();
                });
            }
        }
    }

    @Test
    void boardLabelTilesKeepTheirTextAndOrder() throws Exception {
        // Literal values of the label's small tiles before they were extracted from EntitySprite (E1 review fixes)
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                Entity atlas = fixture.entity;
                Player local = fixture.player;
                assertEquals(List.of(), UnitStatusWords.statusTiles(atlas, false, local));
                atlas.attachNarcPod(new NarcPod(2, Mek.LOC_CENTER_TORSO));
                atlas.newRound(1);
                atlas.setElevation(2);
                Entity infantry = add(fixture, local, "Foot Platoon (AFFS) (Laser 3067+).blk", 60, new Coords(7, 7));
                atlas.addTransporter(new InfantryCompartment(5));
                atlas.load(infantry, false);
                List<UnitStatusWords.StatusWord> tiles = List.of(word("ELEV", "2", Severity.INFO),
                      word("T", "T", Severity.CAUTION), word("N", "N", Severity.WARNING));
                assertEquals(tiles, UnitStatusWords.statusTiles(atlas, false, local));
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(true);
                assertEquals(List.of(tiles.get(0), tiles.get(1), word("U", "U", Severity.INFO), tiles.get(2)),
                      UnitStatusWords.statusTiles(atlas, false, local), "Never seen by the enemy");
                atlas.setEverSeenByEnemy(true);
                atlas.setVisibleToEnemy(false);
                assertEquals(List.of(tiles.get(0), tiles.get(1), word("H", "H", Severity.INFO), tiles.get(2)),
                      UnitStatusWords.statusTiles(atlas, false, local), "Seen before, hidden now");
                assertEquals(tiles, UnitStatusWords.statusTiles(atlas, false, null), "Only its own side tracks that");
                Entity enemyAtlas = add(fixture, enemy(fixture.game), 42, new Coords(9, 9));
                enemyAtlas.setElevation(1);
                assertEquals(List.of(word("ELEV", "1", Severity.INFO)),
                      UnitStatusWords.statusTiles(enemyAtlas, false, local));

                Infantry digger = (Infantry) add(fixture, local, "Foot Platoon (AFFS) (Laser 3067+).blk", 61,
                      new Coords(7, 8));
                digger.setDugIn(Infantry.DUG_IN_WORKING);
                digger.turnsLayingExplosives = 1;
                digger.setEverSeenByEnemy(true);
                UnitStatusWords.StatusWord hidden = word("H", "H", Severity.INFO);
                assertEquals(List.of(hidden, word("D", "D", Severity.PRECAUTION), word("E", "E", Severity.PRECAUTION)),
                      UnitStatusWords.statusTiles(digger, false, local));
                Tank tank = (Tank) add(fixture, local, "Bulldog Medium Tank.blk", 63, new Coords(8, 7));
                tank.setEverSeenByEnemy(true);
                tank.beginFortify();
                assertEquals(List.of(hidden, word("D", "D", Severity.PRECAUTION)),
                      UnitStatusWords.statusTiles(tank, false, local));
                Aero fighter = (Aero) add(fixture, local, "Chippewa CHP-W7.blk", 62, new Coords(11, 7));
                fighter.setEverSeenByEnemy(true);
                fighter.setAltitude(3);
                assertEquals(List.of(word("ALT", "3", Severity.INFO), hidden),
                      UnitStatusWords.statusTiles(fighter, false, local));
                assertEquals(List.of(hidden), UnitStatusWords.statusTiles(fighter, true, local),
                      "A space map shows no altitude");
            });
        }
    }

    /**
     * The marks of the Legacy 2D board label, which the nameplates and the unit card show: the damage tile in its
     * level's colour (none while undamaged or while the client hides damage levels) and the armor and structure bars
     * in the colours of their remaining shares.
     */
    @Test
    void unitsCarryTheirBoardLabelMarks() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean showDamageLevel = preferences.getShowDamageLevel();
        int green = new java.awt.Color(16, 196, 16).getRGB();
        int caution = preferences.getCautionColor().getRGB();
        int warning = preferences.getWarningColor().getRGB();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            preferences.setShowDamageLevel(true);
            Entity atlas = fixture.entity;
            List<Runnable> changes = List.of(() -> { }, () -> atlas.setArmor(5, Mek.LOC_HEAD),
                  () -> preferences.setShowDamageLevel(false), () -> {
                      preferences.setShowDamageLevel(true);
                      for (int location = 0; location < atlas.locations(); location++) {
                          if (location != Mek.LOC_HEAD) {
                              atlas.setArmor(0, location);
                              if (atlas.hasRearArmor(location)) {
                                  atlas.setArmor(0, location, true);
                              }
                          }
                      }
                      atlas.setInternal(1, Mek.LOC_CENTER_TORSO);
                      atlas.setInternal(11, Mek.LOC_LEFT_TORSO);
                  });
            List<Integer> levels = new ArrayList<>();
            List<GpuBattleStatus.Marks> marks = new ArrayList<>();
            for (Runnable change : changes) {
                SwingUtilities.invokeAndWait(() -> {
                    change.run();
                    levels.add(atlas.getDamageLevel());
                    fixture.source.refresh();
                });
                marks.add(unit(fixture.source.takeFrame().status(), 1).marks());
            }
            assertEquals(List.of(Entity.DMG_NONE, Entity.DMG_MODERATE, Entity.DMG_MODERATE, Entity.DMG_CRIPPLED),
                  levels, "Undamaged; head armor 5 of 9; the same; two torsos with internal damage");
            assertEquals(List.of(new GpuBattleStatus.Marks(0, green, green),
                  new GpuBattleStatus.Marks(caution, green, green), new GpuBattleStatus.Marks(0, green, green),
                  new GpuBattleStatus.Marks(java.awt.Color.BLACK.getRGB(), warning, caution)), marks,
                  "5 of 304 armor points is at most a quarter, 112 of 152 structure points at most three quarters");
        } finally {
            preferences.setShowDamageLevel(showDamageLevel);
        }
    }

    /** The name of the Legacy 2D board label, which the nameplates show: the unit in the client's label style. */
    @Test
    void unitsCarryTheirLabelInTheClientsLabelStyle() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        LabelDisplayStyle style = preferences.getUnitLabelStyle();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            List<LabelDisplayStyle> styles = List.of(LabelDisplayStyle.FULL, LabelDisplayStyle.ABBREV,
                  LabelDisplayStyle.CHASSIS, LabelDisplayStyle.NICKNAME_AND_ABBREVIATED,
                  LabelDisplayStyle.ONLY_NICKNAME, LabelDisplayStyle.ONLY_STATUS);
            List<String> labels = new ArrayList<>();
            for (LabelDisplayStyle shown : styles) {
                SwingUtilities.invokeAndWait(() -> {
                    fixture.entity.getCrew().setNickname("Ace", 0);
                    preferences.setUnitLabelStyle(shown);
                    fixture.source.refresh();
                });
                labels.add(unit(fixture.source.takeFrame().status(), 1).label());
            }
            assertEquals(List.of("Atlas AS7-D", "AS7-D", "Atlas", "\"ACE\" (AS7-D)", "\"ACE\"", ""), labels);
        } finally {
            preferences.setUnitLabelStyle(style);
        }
    }

    @Test
    void captureRefusesToReadTheGameOffTheSwingThread() {
        assertThrows(IllegalStateException.class, () -> new GpuBattleStatus().capture(new Game(), null, null,
              Entity.NONE, entity -> true, entity -> true, entity -> null));
    }

    /**
     * Aerospace units have deployment turns of their own after every ground turn: until the local player's next turn
     * is one, a fighter waits for the ground units, whoever's turn it is now.
     */
    @Test
    void anAerospaceUnitWaitsForTheGroundUnitsUntilTheLocalPlayersNextTurnTakesIt() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            AtomicReference<List<GpuBattleStatus.Wait>> waits = new AtomicReference<>(new ArrayList<>());
            SwingUtilities.invokeAndWait(() -> {
                Game game = fixture.game;
                game.setPhase(GamePhase.DEPLOYMENT);
                fixture.entity.setPosition(null);
                fixture.entity.setDeployed(false);
                AeroSpaceFighter fighter = new AeroSpaceFighter();
                fighter.setId(2);
                fighter.setOwner(fixture.player);
                fighter.setChassis("Stuka");
                fighter.setAltitude(5);
                game.addEntity(fighter, false);
                int aerospace = EntityClassTurn.CLASS_AERO | EntityClassTurn.CLASS_SMALL_CRAFT
                      | EntityClassTurn.CLASS_DROPSHIP | EntityClassTurn.CLASS_JUMPSHIP
                      | EntityClassTurn.CLASS_WARSHIP | EntityClassTurn.CLASS_SPACE_STATION;
                game.setTurnVector(List.of(new GameTurn(1), new EntityClassTurn(0, ~aerospace),
                      new EntityClassTurn(0, EntityClassTurn.CLASS_AERO)));
                GpuBattleStatus status = new GpuBattleStatus();
                for (int index = 0; index < 3; index++) {
                    game.setTurnIndex(index, Player.PLAYER_NONE);
                    GameTurn active = index == 0 ? null : game.getTurn();
                    GpuBattleStatus.Snapshot shown = status.capture(game, fixture.player, active, Entity.NONE,
                          entity -> true, entity -> true, entity -> null);
                    waits.get().add(unit(shown, 2).deployWait());
                    waits.get().add(unit(shown, fixture.entity.getId()).deployWait());
                }
            });
            assertEquals(List.of(GpuBattleStatus.Wait.AFTER_GROUND, GpuBattleStatus.Wait.NONE,
                        GpuBattleStatus.Wait.AFTER_GROUND, GpuBattleStatus.Wait.NONE,
                        GpuBattleStatus.Wait.NONE, GpuBattleStatus.Wait.NONE), waits.get(),
                  "The opponent's turn and the ground turn hold the fighter back; its own turn and the Mek never wait");
        }
    }

    /**
     * The own units still to act in the phase, which both the movement plan's hold and the fire orders' resolve mode
     * count: the local player's units MegaMek has not marked done, never another player's.
     */
    @Test
    void theUnitsToActAreTheLocalPlayersUnitsThatAreNotDone() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            AtomicReference<List<Integer>> counts = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                Game game = fixture.game;
                Entity second = add(fixture, fixture.player, 2, new Coords(6, 6));
                Player enemy = enemy(game);
                add(fixture, enemy, 42, new Coords(8, 8));
                List<Integer> seen = new ArrayList<>(List.of(GpuBattleStatus.unitsToAct(game, fixture.player)));
                second.setDone(true);
                seen.add(GpuBattleStatus.unitsToAct(game, fixture.player));
                fixture.entity.setDone(true);
                seen.add(GpuBattleStatus.unitsToAct(game, fixture.player));
                seen.add(GpuBattleStatus.unitsToAct(game, enemy));
                seen.add(GpuBattleStatus.unitsToAct(game, null));
                counts.set(seen);
            });
            assertEquals(List.of(2, 1, 0, 1, 0), counts.get(),
                  "Two own units, one done, both done; the enemy's own unit; no local player");
        }
    }

    /** A source over the fixture board whose client and shown board come from {@code gui}, as in the battle window. */
    private static GpuBoardSource clientSource(GpuBoardFixture fixture, ClientGUI gui,
          AtomicReference<BoardClientState> shown) {
        BoardClientState board = spy(fixture.view);
        doReturn(gui).when(board).getClientgui();
        shown.set(board);
        when(gui.getCurrentBoardState()).thenAnswer(invocation -> Optional.of(shown.get()));
        return new GpuBoardSource(board, () -> fixture.panel);
    }

    private static UnitStatusWords.StatusWord word(String key, String label, Severity severity) {
        return new UnitStatusWords.StatusWord(key, label, severity);
    }

    /**
     * The side a server roll reports for {@code playerIds}: its total, its kept dice's sum, the bonus between them
     * and its tie-breaks.
     */
    private static GpuBattleStatus.InitiativeSide side(String name, int rgb, GpuBattleStatus.Side side,
          InitiativeRoll roll, Integer... playerIds) {
        int dice = roll.getKeptDice(0).stream().mapToInt(Integer::intValue).sum();
        return new GpuBattleStatus.InitiativeSide(name, rgb, side, roll.getRoll(0), dice, roll.getRoll(0) - dice,
              List.of(), IntStream.range(1, roll.size()).mapToObj(roll::getRoll).toList(), List.of(playerIds));
    }

    /** An enemy side whose deciding roll totals {@code total}, followed by {@code tieBreaks}. */
    private static GpuBattleStatus.InitiativeSide rolled(int total, Integer... tieBreaks) {
        return new GpuBattleStatus.InitiativeSide("Side " + total, 0, GpuBattleStatus.Side.ENEMY, total, total, 0,
              List.of(), List.of(tieBreaks), List.of());
    }

    /** The initiative sides the local player (id 0) of {@code client} is shown. */
    private static List<GpuBattleStatus.InitiativeSide> initiative(Game client) throws Exception {
        AtomicReference<List<GpuBattleStatus.InitiativeSide>> sides = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> sides.set(new GpuBattleStatus().capture(client, client.getPlayer(0), null,
              Entity.NONE, entity -> true, entity -> true, entity -> null).initiative()));
        return sides.get();
    }

    /** A copy through Java serialization, as the client receives the objects of a packet. */
    @SuppressWarnings("unchecked")
    private static <T> T sent(T object) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(object);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) input.readObject();
        }
    }

    private static void assertFlags(GpuBattleStatus.Snapshot status, boolean canActNow, boolean pending,
          boolean done) {
        GpuBattleStatus.UnitStatus own = unit(status, 1);
        assertEquals(List.of(canActNow, pending, done), List.of(own.canActNow(), own.pending(), own.done()),
              "canActNow, pending, done");
    }

    private static GpuBattleStatus.Slot slot(Player player, GpuBattleStatus.Side side, int entityId) {
        return new GpuBattleStatus.Slot(player.getId(), player.getName(), player.getColour().getColour().getRGB(),
              side, entityId);
    }

    private static GpuBattleStatus.UnitStatus unit(GpuBattleStatus.Snapshot status, int id) {
        return status.units().stream().filter(unit -> unit.id() == id).findFirst().orElseThrow();
    }

    private static Player ally(GpuBoardFixture fixture) {
        Player ally = new Player(1, "Ally");
        ally.setTeam(fixture.player.getTeam());
        fixture.game.addPlayer(ally.getId(), ally);
        return ally;
    }

    private static Player enemy(Game game) {
        Player enemy = new Player(2, "Enemy");
        enemy.setTeam(2);
        enemy.setColour(PlayerColour.RED);
        game.addPlayer(enemy.getId(), enemy);
        return enemy;
    }

    private static void join(Game game, Player owner, String name, Entity entity) {
        int force = game.getForces().addTopLevelForce(Force.createToplevelForce(name, owner), owner);
        game.getForces().addEntity(entity, force);
    }

    private static Entity add(GpuBoardFixture fixture, Player owner, int id, Coords position) {
        return add(fixture, owner, "Atlas AS7-D.mtf", id, position);
    }

    private static Entity add(GpuBoardFixture fixture, Player owner, String file, int id, Coords position) {
        try {
            Entity entity = new MekFileParser(new File("testresources/megamek/common/units/" + file)).getEntity();
            entity.setId(id);
            entity.setOwner(owner);
            entity.setPosition(position);
            entity.setDeployed(true);
            fixture.game.addEntity(entity, false);
            return entity;
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
