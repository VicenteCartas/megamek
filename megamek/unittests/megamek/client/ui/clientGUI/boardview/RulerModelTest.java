/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.event.InputEvent;
import java.io.File;

import megamek.common.LosEffects;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RulerModelTest {
    private static final Coords START = new Coords(0, 0), END = new Coords(0, 6);

    @Test
    void flipReversesBlockerAndKeepsHeightsAndLockAttachedToTheirEndpoints() {
        Game game = new Game();
        game.setBoard(Board.createEmptyBoard(1, 7));
        game.getBoard().getHex(new Coords(0, 2)).setLevel(3);
        game.getBoard().getHex(new Coords(0, 4)).setLevel(3);
        RulerModel ruler = new RulerModel(game, 0, null);
        ruler.addPoint(START, 2);
        assertEquals(InputEvent.ALT_DOWN_MASK, ruler.capture().pending());
        ruler.addPoint(END, 2);
        ruler.lock(true, true);
        assertEquals(new Coords(0, 2), ruler.capture().ruler().blockedAt());
        ruler.flip();
        var flipped = ruler.capture();
        assertEquals(END, flipped.start().coords());
        assertEquals(new Coords(0, 4), flipped.ruler().blockedAt());
        assertTrue(flipped.end().locked());
        ruler.addPoint(new Coords(0, 5), 4);
        assertEquals(START, ruler.capture().end().coords());
        assertEquals(4, ruler.capture().start().height());
        ruler.height(true, 5); ruler.height(false, 5);
        assertTrue(ruler.capture().clear());
        assertNull(ruler.capture().ruler().blockedAt());
        ruler.clear();
        assertSame(RulerModel.Snapshot.NONE, ruler.capture());
    }

    @Test
    void measurementAndRuleComparisonUseTheSelectedBoardAndRestoreGameOptions() {
        Game game = new Game();
        game.setBoard(Board.createEmptyBoard(1, 7));
        Board other = Board.createEmptyBoard(1, 7);
        other.getHex(new Coords(0, 3)).setLevel(8);
        game.setBoard(4, other);
        game.getOptions().getOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_LOS1).setValue(true);
        RulerModel ruler = new RulerModel(game, 4, null);
        ruler.measure(START, END, Entity.NONE, null);
        ruler.compare(true);
        var snapshot = ruler.capture();
        assertFalse(snapshot.clear());
        assertEquals(new Coords(0, 3), snapshot.ruler().blockedAt());
        assertEquals(3, snapshot.comparison().size());
        assertTrue(snapshot.comparison().stream().allMatch(row -> row.forward().contains("blocked")));
        assertTrue(game.getOptions().booleanOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_LOS1));
        assertFalse(game.getOptions().booleanOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_DEAD_ZONES));
        assertEquals("Diagrammed LOS", snapshot.mode());
    }

    @Test
    void losingVisualContactDropsIdentityHeightAndStateFromTheNativeCard() throws Exception {
        Game game = new Game();
        game.setBoard(Board.createEmptyBoard(1, 7));
        Player local = new Player(0, "Local"), enemy = new Player(1, "Enemy");
        local.setTeam(1); enemy.setTeam(2);
        game.addPlayer(0, local); game.addPlayer(1, enemy);
        game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(true);
        game.getOptions().getOption(OptionsConstants.ADVANCED_TAC_OPS_SENSORS).setValue(true);
        Entity target = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
        target.setId(1); target.setOwner(enemy); target.setPosition(END); target.setDeployed(true);
        target.setProne(true); game.addEntity(target, false);
        target.addBeenSeenBy(local); target.addBeenDetectedBy(local);
        RulerModel ruler = new RulerModel(game, 0, local);
        ruler.measure(START, END, Entity.NONE, null);
        ruler.height(false, 7);
        assertEquals(target.getDisplayName(), ruler.capture().end().name());
        assertTrue(ruler.capture().end().detail().contains("Prone"));
        target.clearSeenBy();
        var sensor = ruler.capture();
        assertEquals(megamek.client.ui.Messages.getString("BoardView1.sensorReturn"), sensor.end().name());
        assertEquals(0, sensor.end().height());
        assertFalse(sensor.end().detail().contains("Prone"));
        assertFalse(sensor.forward().contains("prone"));
        game.getOptions().getOption(OptionsConstants.ADVANCED_HIDDEN_UNITS).setValue(true);
        target.setHidden(true);
        var hidden = ruler.capture();
        assertEquals(Entity.NONE, hidden.end().entityId());
        assertEquals(1, hidden.end().choices().size());
        assertFalse(hidden.end().name().contains("Atlas"));
    }

    @Test
    void surfaceEndpointsDoNotBecomeUndergroundInTheCalculation() {
        Game game = new Game();
        game.setBoard(Board.createEmptyBoard(1, 7));
        RulerModel ruler = new RulerModel(game, 0, null);
        ruler.addPoint(START, 0);
        ruler.addPoint(END, 0);
        var result = ruler.capture();
        assertTrue(result.clear(), "Level-0 ground must not block two level-0 endpoints on a clear board");
        assertEquals(0, result.start().absoluteHeight());
        assertEquals(0, result.ruler().startHeight());
        game.getBoard().getHex(new Coords(0, 1)).setLevel(1);
        assertFalse(ruler.capture().clear(), "An adjacent level-1 hill does block the ground-level sightline");
    }

    @Test
    void boxCanyonSurfaceMeasurementMatchesTheEngineAndFindsARealRaisedBlocker() {
        Game game = new Game();
        Board board = new Board();
        board.load(new File("data/boards/Map Pack Savannahs/16x17 Box Canyon (Savannah).board"));
        game.setBoard(board);
        Coords from = new Coords(8, 12), to = new Coords(9, 10), middle = new Coords(9, 11);
        assertEquals(8, board.getHex(from).getLevel());
        assertEquals(0, board.getHex(to).getLevel());
        assertEquals(0, board.getHex(middle).getLevel());
        RulerModel ruler = new RulerModel(game, 0, null);
        ruler.addPoint(from, 0); ruler.addPoint(to, 0); ruler.compare(true);
        var result = ruler.capture();
        assertEquals(2, result.distance());
        assertEquals(8, result.start().absoluteHeight());
        assertEquals(0, result.end().absoluteHeight());
        assertTrue(result.clear(), "The screenshot's level-0 intervening hex is not a hill above the target");
        assertNull(result.ruler().blockedAt());
        assertEquals(result.forward(), result.comparison().getFirst().forward());
        var attack = LosEffects.prepLosAttackInfo(game, null, null, from, to, 0, false, false);
        assertEquals(8, attack.attackAbsHeight);
        assertEquals(0, attack.targetAbsHeight);
        assertEquals(LosEffects.calculateLos(game, attack, true).canSee(), result.clear());

        board.getHex(middle).setLevel(8);
        assertFalse(ruler.capture().clear());
        assertEquals(middle, ruler.capture().ruler().blockedAt());
    }

    @Test
    void minesScreenshotCountsBothLightWoodsAtTwoLevelEndpointHeights() {
        Game game = new Game();
        Board board = new Board();
        board.load(new File("data/boards/Deserts/16x17 Mines 1.board"));
        game.setBoard(board);
        Coords from = new Coords(13, 4), to = new Coords(6, 2);
        assertEquals(1, board.getHex(from).getLevel());
        assertEquals(3, board.getHex(to).getLevel());
        for (Coords woods : new Coords[] { new Coords(7, 2), new Coords(8, 3) }) {
            assertEquals(3, board.getHex(woods).getLevel());
            assertEquals(1, board.getHex(woods).terrainLevel(Terrains.WOODS));
            assertEquals(2, board.getHex(woods).terrainLevel(Terrains.FOLIAGE_ELEV));
        }

        RulerModel ruler = new RulerModel(game, 0, null);
        ruler.addPoint(from, 2); ruler.addPoint(to, 2); ruler.compare(true);
        var result = ruler.capture();
        assertEquals(7, result.distance());
        assertTrue(result.clear());
        assertEquals(3, result.ruler().startHeight());
        assertEquals(5, result.ruler().endHeight());
        assertEquals("+2 · 2 intervening light woods", result.modifierSummary());
        assertEquals(new Coords(8, 3), result.ruler().modifierAt());
        assertTrue(result.forward().contains("2 intervening light woods"));
        assertEquals(result.forward(), result.reverse());
        assertEquals(result.forward(), result.comparison().getFirst().forward());
        assertEquals(result.reverse(), result.comparison().getFirst().reverse());
        ruler.flip();
        assertEquals(new Coords(7, 2), ruler.capture().ruler().modifierAt());
        assertEquals(result.reverse(), ruler.capture().forward());
        assertEquals(result.forward(), ruler.capture().reverse());

        ruler.height(true, 3); ruler.height(false, 3);
        var raised = ruler.capture();
        assertTrue(raised.clear());
        assertTrue(raised.modifierSummary().isEmpty());
        assertNull(raised.ruler().modifierAt());
        assertEquals(raised.forward(), raised.reverse());
        assertEquals(raised.forward(), raised.comparison().getFirst().forward());
    }

    @ParameterizedTest(name = "density {0}, foliage height {1}, endpoint height {2}, modifier {3}")
    @CsvSource({ "1, 2, 2, 1", "1, 1, 2, 0", "2, 2, 2, 2", "3, 3, 3, -1", "3, 3, 4, 0" })
    void interveningFoliageUsesItsPhysicalHeight(int density, int foliageHeight, int endpointHeight,
          int expectedModifier) {
        Game game = new Game();
        game.setBoard(Board.createEmptyBoard(1, 7));
        Coords woods = new Coords(0, 3);
        game.getBoard().getHex(woods).addTerrain(new Terrain(Terrains.WOODS, density));
        game.getBoard().getHex(woods).addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, foliageHeight));
        RulerModel ruler = new RulerModel(game, 0, null);
        ruler.addPoint(START, endpointHeight); ruler.addPoint(END, endpointHeight);
        var result = ruler.capture();
        assertEquals(expectedModifier >= 0, result.clear());
        assertEquals(expectedModifier > 0 ? woods : null, result.ruler().modifierAt());
        assertEquals(result.forward(), result.reverse());
        if (expectedModifier < 0) {
            assertEquals(woods, result.ruler().blockedAt());
            assertTrue(result.forward().contains("blocked"));
        } else if (expectedModifier == 0) {
            assertTrue(result.modifierSummary().isEmpty());
        } else {
            assertTrue(result.modifierSummary().startsWith("+" + expectedModifier + " ·"));
            assertTrue(result.forward().contains("intervening"));
        }
    }

    @ParameterizedTest
    @CsvSource({ "SOAR VTOL.blk, false, Elevation", "Slayer SL-CX1.blk, true, Altitude" })
    void mixedManualEndpointsKeepKnownUnitElevationAndAltitude(String file, boolean altitude, String label)
          throws Exception {
        Game game = new Game();
        game.setBoard(Board.createEmptyBoard(1, 7));
        game.getBoard().getHex(new Coords(0, 1)).setLevel(2);
        Player owner = new Player(0, "Local"); game.addPlayer(owner.getId(), owner);
        Entity unit = new MekFileParser(new File("testresources/megamek/common/units/" + file)).getEntity();
        unit.setId(1); unit.setOwner(owner); unit.setPosition(START); unit.setDeployed(true);
        if (altitude) { unit.setAltitude(2); } else { unit.setElevation(2); }
        game.addEntity(unit, false);
        RulerModel ruler = new RulerModel(game, 0, owner);
        ruler.measure(START, END, unit.getId(), 2);
        var result = ruler.capture();
        assertFalse(result.entityBased());
        assertEquals(label, result.start().heightLabel());
        assertEquals(2, result.start().height());
        assertTrue(result.clear(), "A level-2 hill is below the unit's unchanged engine sight height");
        ruler.height(true, 1);
        assertFalse(ruler.capture().clear(), "Lowering the known endpoint makes the adjacent hill block LOS");
    }

    @Test
    void compactModifiersFollowTheDirectionHeightAndBlockingState() {
        Game game = new Game(); game.setBoard(Board.createEmptyBoard(1, 7));
        game.getBoard().getHex(END).addTerrain(new Terrain(Terrains.WOODS, 2));
        game.getBoard().getHex(END).addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
        RulerModel ruler = new RulerModel(game, 0, null);
        ruler.measure(START, END, Entity.NONE, null);
        assertEquals("+2 · target in heavy woods", ruler.capture().modifierSummary());
        assertNull(ruler.capture().ruler().modifierAt(), "Target terrain is not an intervening obstacle");
        ruler.flip();
        assertTrue(ruler.capture().modifierSummary().isEmpty(), "The woods are now at the start, not the target");
        ruler.flip(); ruler.height(false, 3);
        assertTrue(ruler.capture().modifierSummary().isEmpty(), "An endpoint above the woods loses their modifier");
        ruler.height(false, 0);
        game.getBoard().getHex(new Coords(0, 3)).setLevel(4);
        assertFalse(ruler.capture().clear());
        assertTrue(ruler.capture().modifierSummary().isEmpty(), "Blocked LOS must not retain a previous penalty");
    }

    @Test
    void actualUnitsAndComparisonsUseTheGameLosPathAtTheirActualSightHeights() throws Exception {
        Game game = new Game();
        game.setBoard(Board.createEmptyBoard(1, 7));
        game.getBoard().getHex(new Coords(0, 3)).addTerrain(new Terrain(Terrains.WOODS, 1));
        game.getBoard().getHex(new Coords(0, 3)).addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
        Player owner = new Player(0, "Local"); game.addPlayer(owner.getId(), owner);
        Entity attacker = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
        Entity target = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
        attacker.setId(1); attacker.setOwner(owner); attacker.setPosition(START); attacker.setDeployed(true);
        target.setId(2); target.setOwner(owner); target.setPosition(END); target.setDeployed(true);
        game.addEntity(attacker, false); game.addEntity(target, false);
        RulerModel ruler = new RulerModel(game, 0, owner);
        ruler.measure(START, END, attacker.getId(), null); ruler.compare(true);
        for (boolean prone : new boolean[] { false, true }) {
            target.setProne(prone);
            var result = ruler.capture();
            assertTrue(result.entityBased());
            assertEquals(attacker.relHeight() + 1, result.start().height());
            assertEquals(target.relHeight() + 1, result.end().height());
            assertEquals(target.relHeight() + 1, result.ruler().endHeight());
            assertTrue(result.forward().contains("1 intervening light woods"));
            assertEquals(new Coords(0, 3), result.ruler().modifierAt());
            assertEquals(LOSModifierCalculator.computeEntityBasedModifiers(game, attacker, target), result.forward());
            assertEquals(result.forward(), result.comparison().getFirst().forward());
        }
        ruler.height(false, 4);
        assertFalse(ruler.capture().entityBased());
        assertEquals(4, ruler.capture().ruler().endHeight());

        target.setProne(false);
        ruler.select(false, Entity.NONE); ruler.height(false, 2);
        var mixed = ruler.capture();
        assertFalse(mixed.entityBased());
        assertEquals(LOSModifierCalculator.computeEntityBasedModifiers(game, attacker, target), mixed.forward());
        ruler.select(true, Entity.NONE); ruler.height(true, 2);
        var manual = ruler.capture();
        assertFalse(manual.entityBased());
        assertEquals(mixed.forward(), manual.forward());
        assertEquals("+1 · 1 intervening light woods", manual.modifierSummary());

        game.getBoard().getHex(START).addTerrain(new Terrain(Terrains.WATER, 2));
        attacker.setElevation(-2);
        ruler.select(true, attacker.getId());
        assertEquals(0, ruler.capture().start().height());
        assertFalse(ruler.capture().clear(), "A submerged unit's physical height 0 must retain its negative sight level");
        attacker.setElevation(-1);
        assertEquals(1, ruler.capture().start().height());
        assertTrue(ruler.capture().clear(), "A unit reaching the water surface can see the manual target again");
    }
}
