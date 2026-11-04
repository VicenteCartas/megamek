/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.lobby.LobbyBattlefield;
import megamek.client.lobby.LobbyForces;
import megamek.client.lobby.LobbyGameMaster;
import megamek.client.lobby.LobbyReadiness;
import megamek.client.lobby.LobbyRequisition;
import megamek.client.lobby.LobbySnapshot;
import megamek.client.ui.clientGUI.boardview.gpu.LobbyBattlefieldController.Command;
import megamek.client.ui.gdx.UiTestStage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Clicks the battlefield column's map tools through the stage, as a pointer would: the column has no menu, and the
 * dice and random map settings show only for a map rolled at launch.
 */
@Tag("on-demand")
class LobbyBattlefieldToolsSmokeTest {

    @Test
    void theMapToolsChangeThePreviewAndARolledMapsDiceAndSettings() {
        UiTestStage.run(ui -> {
            List<Command> commands = new ArrayList<>();
            LobbyBattlefieldPanel battlefield = new LobbyBattlefieldPanel(ui.kit, new LobbyBattlefieldPanel.Actions() {
                @Override
                public void command(Command command) {
                    commands.add(command);
                }

                @Override
                public void autoResolve(boolean chances, int runs, int threads) {
                }
            });
            Table panel = battlefield.panel();
            ui.window.addActor(panel);
            panel.setBounds(100, 50, 360, 900);

            show(ui, battlefield, panel, false, true);
            Actor generate = panel.findActor("lobby-battlefield-generate");
            assertFalse(generate.getParent().isVisible(), "a fixed map has no dice to roll");
            click(ui, panel.findActor("lobby-battlefield-change-map"));
            click(ui, panel.findActor("lobby-battlefield-preview-3d"));
            assertEquals(List.of(Command.MAP_SETUP, Command.PREVIEW_3D), commands);

            commands.clear();
            show(ui, battlefield, panel, true, false);
            assertTrue(generate.getParent().isVisible(), "a rolled map shows its dice and settings");
            click(ui, generate);
            assertEquals(List.of(), commands, "a map with a surprise board cannot be built now");
            click(ui, panel.findActor("lobby-battlefield-random-map"));
            assertEquals(List.of(Command.RANDOM_MAP), commands);

            show(ui, battlefield, panel, true, true);
            click(ui, generate);
            assertEquals(List.of(Command.RANDOM_MAP, Command.GENERATE), commands);
            panel.remove();
        });
    }

    private static void show(UiTestStage ui, LobbyBattlefieldPanel battlefield, Table panel, boolean rolled,
          boolean generable) {
        LobbyBattlefield field = new LobbyBattlefield("32 × 34 hexes", "Daylight", false, generable, rolled, "3025",
              List.of(), "Last side standing", LobbyBattlefield.AutoResolve.DEFAULT);
        battlefield.show(new LobbySnapshot(1, false, false, LobbyGameMaster.OFF, List.of(), List.of(), List.of(), "",
              "", LobbyForces.EMPTY, field, LobbyReadiness.EMPTY, LobbyRequisition.EMPTY));
        panel.validate();
        ui.draw();
    }

    private static void click(UiTestStage ui, Actor actor) {
        assertNotNull(actor, "the control shows");
        Vector2 point = actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        point = ui.stage.stageToScreenCoordinates(point);
        int x = Math.round(point.x);
        int y = Math.round(point.y);
        ui.stage.mouseMoved(x, y);
        ui.stage.touchDown(x, y, 0, Input.Buttons.LEFT);
        ui.stage.touchUp(x, y, 0, Input.Buttons.LEFT);
        ui.draw();
    }
}
