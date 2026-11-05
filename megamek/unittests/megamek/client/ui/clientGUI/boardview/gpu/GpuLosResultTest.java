/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.event.InputEvent;
import java.io.File;
import javax.swing.SwingUtilities;

import megamek.common.Player;
import megamek.common.board.Coords;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import megamek.utils.BoardLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Native measurement integration: menu ownership, endpoint heights, plain completion and legacy isolation. */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuLosResultTest {
    private static final Coords OWN = new Coords(5, 5);
    private static final Coords ENEMY = new Coords(5, 3);

    /** A clear 8 x 8 board with the fixture's own Atlas at 0606 and an enemy Atlas at 0604. */
    private static GpuBoardFixture battle() throws Exception {
        StringBuilder data = new StringBuilder("size 8 8\n");
        for (int y = 1; y <= 8; y++) {
            for (int x = 1; x <= 8; x++) {
                data.append(String.format("hex %02d%02d 0 \"\" \"\"%n", x, y));
            }
        }
        GpuBoardFixture fixture = GpuBoardFixture.create(BoardLoader.initializeBoard(data.append("end").toString()));
        SwingUtilities.invokeAndWait(() -> {
            Player enemy = new Player(1, "Opponent");
            enemy.setTeam(2);
            fixture.game.addPlayer(enemy.getId(), enemy);
            Entity atlas = unit("Atlas AS7-D.mtf", 2, enemy);
            atlas.setPosition(ENEMY);
            fixture.game.addEntity(atlas, false);
        });
        return fixture;
    }

    @Test
    void menuMeasuresFromTheChosenOwnUnitAndRejectsInvalidEndpoints() throws Exception {
        try (GpuBoardFixture fixture = battle()) {
            GpuLosResult los = fixture.source.los();
            los.lineOfSight(1, ENEMY, Float.NaN);
            SwingUtilities.invokeAndWait(() -> { });
            var result = fixture.source.takeFrame().panels().los();
            assertEquals(OWN, result.start().coords());
            assertEquals(1, result.start().entityId());
            assertEquals(ENEMY, result.end().coords());
            org.junit.jupiter.api.Assertions.assertTrue(result.entityBased());
            los.lineOfSight(1, OWN, Float.NaN);
            los.lineOfSight(2, OWN, Float.NaN);
            los.lineOfSight(1, new Coords(20, 20), Float.NaN);
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(result, fixture.source.takeFrame().panels().los());
            Coords floor = new Coords(5, 1);
            los.lineOfSight(1, floor, 2 * BoardGeometry.level() + .1f);
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(2, fixture.source.takeFrame().panels().los().end().height());
            org.junit.jupiter.api.Assertions.assertNull(fixture.view.getRuler(), "The Swing ruler receives no measurement");
            org.junit.jupiter.api.Assertions.assertNull(fixture.view.getFirstLOS());
        }
    }

    @Test
    void onlyAltClickStartsLosAndPlainClickCompletesItsPendingEndpoint() throws Exception {
        try (GpuBoardFixture fixture = battle()) {
            fixture.source.measure(OWN, InputEvent.CTRL_DOWN_MASK, Float.NaN);
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(0, pending(fixture));
            fixture.source.measure(OWN, InputEvent.ALT_DOWN_MASK, Float.NaN);
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(InputEvent.ALT_DOWN_MASK, pending(fixture));
            fixture.source.measure(ENEMY, 0, Float.NaN);
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(0, pending(fixture));
            assertEquals(2, fixture.source.takeFrame().panels().los().distance());
        }
    }

    private static Entity unit(String file, int id, Player owner) {
        try {
            Entity unit = new MekFileParser(new File("testresources/megamek/common/units/" + file)).getEntity();
            unit.setId(id);
            unit.setOwner(owner);
            unit.setDeployed(true);
            return unit;
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    /** The published modifier of the measurement waiting for its second point, after a capture. */
    private static int pending(GpuBoardFixture fixture) throws Exception {
        SwingUtilities.invokeAndWait(fixture.source::refresh);
        return fixture.source.takeFrame().panels().los().pending();
    }

}
