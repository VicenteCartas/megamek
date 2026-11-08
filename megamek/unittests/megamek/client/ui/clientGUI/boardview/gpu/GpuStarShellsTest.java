/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.Vector;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.math.Vector3;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.Flare;
import org.junit.jupiter.api.Test;

/**
 * Illumination rounds on the 3D board: the light pool's hex arithmetic agrees with the game's own hex distance, the
 * scene carries the flares over its board, a flare that drifts glides and one that burns out sputters away, and the
 * round itself bursts as a star shell.
 */
class GpuStarShellsTest {
    /** The light pool counts hexes as the rules do: for every pair of hexes on a board, the game's own distance. */
    @Test
    void theLightPoolCountsHexesAsTheGameDoes() {
        for (int columnA = 0; columnA < 14; columnA++) {
            for (int rowA = 0; rowA < 14; rowA++) {
                Coords a = new Coords(columnA, rowA);
                for (int columnB = 0; columnB < 14; columnB++) {
                    for (int rowB = 0; rowB < 14; rowB++) {
                        assertEquals(a.distance(new Coords(columnB, rowB)),
                              GpuStarShells.hexDistance(columnA, rowA, columnB, rowB), a + " to " + columnB + "," + rowB);
                    }
                }
            }
        }
    }

    /** The hex under any point is the one whose centre is nearest, which is how a hex grid divides the board. */
    @Test
    void theHexUnderAPointIsTheNearestHexCentre() {
        Random random = new Random(7);
        float width = BoardGeometry.width() * .75f * 12, height = BoardGeometry.height() * 12;
        for (int sample = 0; sample < 2000; sample++) {
            float x = BoardGeometry.width() + random.nextFloat() * width;
            float y = -(BoardGeometry.height() + random.nextFloat() * height);
            Coords nearest = null;
            float best = Float.MAX_VALUE;
            for (int column = 0; column < 16; column++) {
                for (int row = 0; row < 16; row++) {
                    Coords hex = new Coords(column, row);
                    float dx = x - BoardGeometry.centerX(hex), dy = y - BoardGeometry.centerY(hex);
                    if (dx * dx + dy * dy < best) {
                        best = dx * dx + dy * dy;
                        nearest = hex;
                    }
                }
            }
            assertEquals(nearest, GpuStarShells.hexAt(x, y));
        }
    }

    @Test
    void theSceneCarriesTheFlaresOverItsBoardOnly() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Coords over = fixture.entity.getPosition();
            SwingUtilities.invokeAndWait(() -> {
                Vector<Flare> flares = new Vector<>();
                flares.add(new Flare(over, fixture.view.getBoardId(), 4, 2, Flare.F_IGNITED | Flare.F_DRIFTING));
                flares.add(new Flare(over, fixture.view.getBoardId() + 1, 5, 3, Flare.F_IGNITED));
                fixture.game.setFlares(flares);
                fixture.source.refresh();
            });
            List<BoardScene.StarShell> shells = fixture.source.takeFrame().scene().starShells();
            assertEquals(List.of(new BoardScene.StarShell(over, 2, 4, true)), shells, "Only the flare over this board");
        }
    }

    /** A flare the wind moves glides to its new hex; one that burns out stays a moment, sputtering, then is gone. */
    @Test
    void aDriftingFlareGlidesAndABurntOutOneSputtersAway() {
        var starShells = new GpuStarShells();
        Coords start = new Coords(4, 4);
        starShells.update(scene(new BoardScene.StarShell(start, 3, 5, true)), Map.of(), .1f);
        assertEquals(1, starShells.size());
        starShells.update(scene(new BoardScene.StarShell(start.translated(3), 3, 4, true)), Map.of(), .1f);
        assertEquals(1, starShells.size(), "The same flare, moved, not a second one");
        starShells.update(scene(), Map.of(), .1f);
        assertEquals(1, starShells.size(), "Sputtering out");
        starShells.update(scene(), Map.of(), 2);
        assertEquals(0, starShells.size(), "Gone");
    }

    /** A flare starts high and sinks a step each of its five turns, lowest on its last. */
    @Test
    void theFlareSinksThroughItsFiveTurns() {
        assertEquals(GpuStarShells.HANG_HEXES, GpuStarShells.hangHexes(GpuStarShells.BURN_TURNS), 1e-4f);
        for (int turnsLeft = GpuStarShells.BURN_TURNS; turnsLeft > 1; turnsLeft--) {
            assertTrue(GpuStarShells.hangHexes(turnsLeft - 1) < GpuStarShells.hangHexes(turnsLeft),
                  "Lower with " + (turnsLeft - 1) + " turns left than with " + turnsLeft);
        }
        assertTrue(GpuStarShells.hangHexes(1) > GpuStarShells.HANG_HEXES * .25f, "Still well above the ground");
        var starShells = new GpuStarShells();
        Coords over = new Coords(4, 4);
        starShells.update(scene(new BoardScene.StarShell(over, 3, 5, true)), Map.of(), .1f);
        starShells.update(scene(new BoardScene.StarShell(over, 3, 4, true)), Map.of(), .1f);
        assertEquals(1, starShells.size(), "The same flare, a turn lower, not a second one");
    }

    /** An artillery illumination round bursts high as a star shell and digs no crater; its burst is where its flare starts. */
    @Test
    void anIlluminationRoundBurstsAsAStarShell() {
        EquipmentType.initializeTypes();
        var round = new ResolvedAttack.Shot("", Set.of("M_FLARE"), true, false, 1, 0, true, null, null, null, true, 20,
              false, false, false, null, 20);
        assertTrue(GpuArtilleryBlast.isStarShell(round));
        assertTrue(GpuArtilleryBlast.drawsLanding(round));
        assertTrue(GpuArtilleryBlast.leavesNoCrater("ISLongTom", round));
        assertTrue(GpuArtilleryBlast.openingHeight("ISLongTom", round) > 0);
        var notArtillery = new ResolvedAttack.Shot("", Set.of("M_FLARE"), false, false, 1, 0, true, null, null, null,
              true, 20, false, false, false, null, 20);
        assertFalse(GpuArtilleryBlast.isStarShell(notArtillery), "A mortar's flare keeps its own glow");
        var attack = GroundDamagePlaybackTest.shot("ISLongTom", false, round);
        var blasts = new GpuArtilleryBlast();
        Coords target = attack.event.destination().coords();
        blasts.detonate(BoardGeometry.center(target, 0), round, attack, 3);
        Vector3 burst = blasts.starShellOpenings().get(target);
        assertEquals(BoardGeometry.height() * GpuStarShells.HANG_HEXES, burst.z, 1, "High over its target");
    }

    private static BoardScene scene(BoardScene.StarShell... shells) {
        BoardScene base = UnitPlaybackFixture.scene();
        return new BoardScene(base.boardId(), base.width(), base.height(), base.tiles(), base.units(),
              base.plannedPath(), base.selectedId(), base.phase(), base.commands(), base.light(), base.firingLines(),
              base.rangeBorders(), base.markers(), base.tactical(), base.rangeLabels(), base.fieldOfView(),
              base.electronicWarfare(), base.designations(), List.of(shells));
    }
}
