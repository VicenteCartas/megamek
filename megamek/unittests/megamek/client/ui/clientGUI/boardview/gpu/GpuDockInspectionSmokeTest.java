/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuUnitPanelFixture.ATLAS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuUnitPanelFixture.TANK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.gdx.UiButton;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real Scene2D layout and pointer checks for the shared chat footer and both inspectors. */
@Tag("on-demand")
class GpuDockInspectionSmokeTest {
    @Test
    void chatAndBothInspectorsFitBesideReportsAndWeaponsAtEveryReferenceSize() throws Exception {
        try (GpuUnitPanelFixture fixture = GpuUnitPanelFixture.create()) {
            GpuBoardSource.Frame own = fixture.frame(ATLAS);
            GpuBoardSource.Frame enemy = fixture.frame(TANK);
            SwingUtilities.invokeAndWait(fixture.board.source::refresh);
            BoardScene scene = fixture.board.source.takeFrame().scene();
            GpuHudTestStage.run(harness -> {
                GpuBoardSource source = mock(GpuBoardSource.class);
                when(source.record()).thenReturn(mock(GpuUnitRecord.class));
                SpriteBatch batch = new SpriteBatch();
                GpuHud hud = new GpuHud(source, harness.theme.skin, batch, new BoardCamera(),
                      mock(GpuBoardTuning.class), new GpuPlaybackHistory(new UnitPlayback()));
                try {
                    float density = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
                    for (int[] size : List.of(new int[] { 900, 600 }, new int[] { 1280, 720 },
                          new int[] { 1920, 1080 })) {
                        harness.size(size[0], size[1]);
                        hud.resize(Math.round(size[0] / density), Math.round(size[1] / density), 1 / density);
                        for (GamePhase phase : List.of(GamePhase.FIRING_REPORT, GamePhase.FIRING)) {
                            GpuBoardSource.Frame frame = frame(own, enemy, scene, phase);
                            hud.state.overview = false;
                            hud.state.chatOpen = false;
                            if (hud.state.logOpen() != phase.isReport()) { hud.state.toggleLog(); }
                            show(hud, frame);
                            hud.state.pick(ATLAS);
                            hud.select(TANK);
                            show(hud, frame);
                            Actor primary = find(hud, "unit-card");
                            Actor secondary = find(hud, "other-unit-card");
                            Actor column = find(hud, phase.isReport() ? "log-panel" : "weapons-panel");
                            Actor dock = find(hud, "command-dock");
                            harness.assertLayout(primary, bounds(secondary), bounds(dock));
                            harness.assertLayout(secondary, bounds(column), bounds(dock));
                            harness.assertLayout(column, bounds(secondary));
                            Actor minimap = find(hud, "minimap");
                            if (minimap.isVisible() && minimap.getParent().isVisible()) {
                                harness.assertLayout(minimap, bounds(column), bounds(secondary));
                            }
                            assertEquals(ATLAS, hud.state.ownCardUnit());
                            assertEquals(TANK, hud.state.otherCardUnit());
                            UiButton chat = (UiButton) find(hud, "chat-button");
                            assertTrue(chat.getText().isEmpty());
                            assertTrue(chat.isDescendantOf(dock));
                            assertTrue(bounds(dock).contains(bounds(chat)));
                            Group footer = chat.getParent();
                            assertEquals(chat, footer.getChildren().peek(), "chat is always the last footer control");
                            click(hud, chat);
                            show(hud, frame);
                            Actor panel = find(hud, "chat-panel");
                            assertDocked(dock, panel);
                            assertFalse(find(hud, "hint-line").getParent().isVisible());
                            TextField field = (TextField) find(hud, "chat-input");
                            field.setText("Draft survives resizing");
                            Actor grip = find(hud, "chat-resize");
                            Vector2 start = hud.stage.stageToScreenCoordinates(
                                  grip.localToStageCoordinates(new Vector2(grip.getWidth() / 2, grip.getHeight() / 2)));
                            float before = panel.getHeight();
                            assertTrue(hud.stage.touchDown(Math.round(start.x), Math.round(start.y), 0, Input.Buttons.LEFT));
                            hud.stage.touchDragged(Math.round(start.x), Math.round(start.y + 50 / density), 0);
                            hud.stage.touchUp(Math.round(start.x), Math.round(start.y + 50 / density), 0, Input.Buttons.LEFT);
                            show(hud, frame);
                            assertEquals(Math.max(144, before - 50), panel.getHeight(), 1,
                                  "dragging the top edge resizes chat down to its minimum height");
                            assertDocked(dock, panel);
                            assertEquals("Draft survives resizing", field.getText());
                            harness.assertLayout(panel, bounds(primary), bounds(secondary), bounds(dock));
                            harness.capture("hud-dock-inspection-" + phase + "-" + size[0]).dispose();
                            click(hud, find(hud, "other-unit-card-close"));
                            show(hud, frame);
                            assertEquals(ATLAS, hud.state.ownCardUnit());
                            assertFalse(hud.state.turnLocked(frame.status()));
                            assertFalse(secondary.isVisible());
                            assertTrue(bounds(column).height > 0);
                            hud.state.overview = true;
                            show(hud, frame);
                            assertTrue(bounds(dock).contains(bounds(chat)));
                            Vector2 fieldPoint = field.localToStageCoordinates(new Vector2(10, 10));
                            assertTrue(hud.stage.hit(fieldPoint.x, fieldPoint.y, true).isDescendantOf(panel),
                                  "chat remains usable over the forces overview");
                        }
                    }
                } finally {
                    hud.dispose();
                    batch.dispose();
                }
            });
        }
    }

    private static GpuBoardSource.Frame frame(GpuBoardSource.Frame own, GpuBoardSource.Frame enemy,
          BoardScene scene, GamePhase phase) {
        GpuBattleStatus.Snapshot status = enemy.status();
        GpuFireOrders.Snapshot fire = phase.isReport() ? GpuFireOrders.Snapshot.EMPTY
              : new GpuFireOrders.Snapshot(true, true, ATLAS, GpuFireOrders.Focus.NONE, -1, List.of(), List.of(),
                    List.of(), 0, false, false, "", null, null, null, List.of(), null, Map.of(), 1, 0, null);
        GpuHudData p = enemy.panels();
        GpuHudData panels = new GpuHudData(p.phase(), p.move(), fire, p.physical(), p.record(), p.preview(),
              p.chat(), p.toasts(), p.los(), p.players(), own.panels().record());
        return new GpuBoardSource.Frame(scene, List.of(), null, List.of(), "", null, 0, "", null,
              GpuReportLog.Snapshot.EMPTY, new GpuBattleStatus.Snapshot(status.round(), phase, !phase.isReport(),
              status.localPlayerId(), phase.isReport() ? Entity.NONE : ATLAS, List.of(), 0,
              status.units(), List.of(), false), panels);
    }

    private static void show(GpuHud hud, GpuBoardSource.Frame frame) {
        // A second frame settles wrapped labels at their new column widths, as in the live render loop.
        for (int i = 0; i < 2; i++) {
            hud.update(frame, GpuHud.HudView.EMPTY, null, GpuHudInputTest.preferences());
            ScreenUtils.clear(.12f, .16f, .14f, 1, true);
            hud.draw();
        }
    }

    private static Actor find(GpuHud hud, String name) {
        return hud.stage.getRoot().findActor(name);
    }

    private static Rectangle bounds(Actor actor) {
        return GpuHudTestStage.bounds(actor);
    }

    private static void assertDocked(Actor dock, Actor chat) {
        Rectangle d = bounds(dock), c = bounds(chat);
        assertEquals(d.x, c.x, .5f);
        assertEquals(d.width, c.width, .5f);
        assertEquals(d.y + d.height + 12, c.y, .5f, "the standard gap separates chat from the dock");
    }

    private static void click(GpuHud hud, Actor actor) {
        Vector2 point = hud.stage.stageToScreenCoordinates(
              actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
        assertTrue(hud.stage.touchDown(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT));
        hud.stage.touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
    }
}
