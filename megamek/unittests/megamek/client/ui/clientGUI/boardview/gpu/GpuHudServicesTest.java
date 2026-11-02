/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.panels.phaseDisplay.FiringDisplay;
import megamek.client.ui.util.KeyCommandBind;
import megamek.client.ui.widget.MegaMekButton;
import megamek.common.Configuration;
import megamek.common.Player;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The HUD service contract of the board source: frame wiring, identity reuse, the input guard and lifecycle. */
@Timeout(120)
class GpuHudServicesTest {
    private static final long WAIT_SECONDS = 20;
    private static File originalDataDir;

    /** Earlier test classes can leave the data folder on testresources; the fixture needs the staged data. */
    @BeforeAll
    static void useStagedData() {
        originalDataDir = Configuration.dataDir();
        Configuration.setDataDir(null);
    }

    @AfterAll
    static void restoreDataDir() {
        Configuration.setDataDir(originalDataDir);
    }

    @Test
    void framesCarryTheServiceSnapshotsAndKeepUnchangedInstances() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            GpuBoardSource.Frame first = fixture.source.takeFrame();
            GpuHudData panels = first.panels();
            // The fixture's phase panel is a plain panel: no status text, no Done, Skip or clear command and no turn
            // details. Its condition lines come from the board's planetary conditions, so they are not asserted here.
            assertEquals(List.of("", "", "", ""), List.of(panels.phase().status(), panels.phase().doneId(),
                  panels.phase().skipId(), panels.phase().clearId()));
            assertEquals(List.of(), panels.phase().turnDetails());
            assertSame(GpuMovePlan.Snapshot.EMPTY, panels.move());
            assertSame(GpuFireOrders.Snapshot.EMPTY, panels.fire());
            assertSame(GpuPhysicalOptions.Snapshot.EMPTY, panels.physical());
            assertSame(GpuUnitRecord.Snapshot.EMPTY, panels.record());
            assertSame(GpuUnitRecord.Snapshot.EMPTY, panels.ownRecord());
            assertSame(GpuFirePreview.Snapshot.NONE, panels.preview());
            assertSame(GpuChat.Snapshot.EMPTY, panels.chat());
            assertSame(GpuToasts.Snapshot.EMPTY, panels.toasts());
            assertSame(RulerModel.Snapshot.NONE, panels.los());
            assertEquals(List.of("GPU review"), panels.players().players().stream().map(GpuPlayers.PlayerRow::name)
                  .toList(), "The players panel lists the fixture's one player");

            GpuBoardSource.UiPreferences preferences = fixture.source.uiPreferences;
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            // The render thread and the report panel compare these by identity.
            assertSame(panels, fixture.source.takeFrame().panels(), "An unchanged capture keeps the panel bundle");
            assertSame(preferences, fixture.source.uiPreferences, "Unchanged preferences are not republished");
        }
    }

    @Test
    void bothInspectorRecordsAreCapturedAndReuseTheirOwnCaches() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Entity enemy = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
            SwingUtilities.invokeAndWait(() -> {
                Player player = new Player(2, "Other player");
                player.setTeam(2);
                fixture.game.addPlayer(player.getId(), player);
                enemy.setOwner(player);
                enemy.setId(2);
                enemy.setPosition(new Coords(6, 6));
                fixture.game.addEntity(enemy);
                fixture.source.setCardUnit(enemy.getId());
                fixture.source.setOwnCardUnit(fixture.entity.getId());
                fixture.source.refresh();
            });
            GpuHudData first = fixture.source.takeFrame().panels();
            assertEquals(enemy.getId(), first.record().unitId());
            assertEquals(fixture.entity.getId(), first.ownRecord().unitId());
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            GpuHudData same = fixture.source.takeFrame().panels();
            assertSame(first.record(), same.record());
            assertSame(first.ownRecord(), same.ownRecord());
            fixture.source.setOwnCardUnit(enemy.getId());
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            assertSame(GpuUnitRecord.Snapshot.EMPTY, fixture.source.takeFrame().panels().ownRecord(),
                  "the own-card channel rechecks ownership on the EDT");
        }
    }

    @Test
    void keyBindingEditsReachTheNextCaptureWithoutAPreferenceEvent() throws Exception {
        KeyCommandBind bind = KeyCommandBind.MOVE_MODE_WALK;
        int key = bind.key;
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            GpuBoardSource.UiPreferences before = fixture.source.uiPreferences;
            SwingUtilities.invokeAndWait(() -> {
                bind.key = KeyEvent.VK_4;
                fixture.source.refresh();
            });
            GpuBoardSource.UiPreferences after = fixture.source.uiPreferences;
            assertNotSame(before, after);
            assertEquals(new GpuBoardSource.Bind(bind, KeyEvent.VK_4, 0, "4"),
                  after.binds().stream().filter(entry -> entry.command() == bind).findFirst().orElseThrow());
        } finally {
            bind.key = key;
        }
    }

    @Test
    void theNameplateBindMatchesTheAltPressThatRealInputDelivers() {
        KeyCommandBind nameplates = KeyCommandBind.SHOW_NAMEPLATES;
        // Swing: the press of Alt carries ALT_DOWN_MASK, its release no modifier.
        assertTrue(KeyCommandBind.getBindByKey(KeyEvent.VK_ALT, InputEvent.ALT_DOWN_MASK).contains(nameplates));
        assertTrue(KeyCommandBind.getBindByKey(KeyEvent.VK_ALT, 0).contains(nameplates));
        // GL: libGDX marks ALT_LEFT as pressed before it delivers ALT_LEFT's own keyDown.
        Input original = Gdx.input;
        Input keyboard = mock(Input.class);
        when(keyboard.isKeyPressed(Input.Keys.ALT_LEFT)).thenReturn(true);
        Gdx.input = keyboard;
        try {
            int alt = GpuBattleView.awtKey(Input.Keys.ALT_LEFT);
            assertTrue(KeyCommandBind.getAllBindsByKey(alt, GpuBattleView.modifiers()).contains(nameplates));
            when(keyboard.isKeyPressed(Input.Keys.CONTROL_LEFT)).thenReturn(true);
            assertFalse(KeyCommandBind.getAllBindsByKey(alt, GpuBattleView.modifiers()).contains(nameplates),
                  "Ctrl+Alt is another chord");
        } finally {
            Gdx.input = original;
        }
        // Only the pressed key's own mask is ignored: Alt+Up stays a called shot and is not Up (previous weapon).
        assertEquals(List.of(KeyCommandBind.CALLED_SHOT_HIGH),
              KeyCommandBind.getAllBindsByKey(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK));
    }

    @Test
    void aMoveCommandQueuedBeforeADialogIsDropped() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            // Without the refresh timer, only a command that runs republishes the scene.
            SwingUtilities.invokeAndWait(() -> timer(fixture.source).stop());
            GpuMovePlan moves = fixture.source.moves();
            Coords hex = new Coords(3, 3);
            BoardScene idle = scene(fixture);
            moves.planTo(hex, 0, false);
            SwingUtilities.invokeAndWait(() -> { });
            BoardScene accepted = scene(fixture);
            assertNotSame(idle, accepted, "Without a dialog the command runs and republishes");

            // The command lands on the queue just before phase code opens a dialog on the EDT.
            FutureTask<DialogAnswer> ask = new FutureTask<>(() -> {
                moves.planTo(hex, 0, false);
                return fixture.source.ask(new DialogRequest(0, DialogKind.MESSAGE, "Confirm", "Continue?", false,
                      List.of("Yes", "No"), 0, 1, List.of(), List.of(), "", false, "", null, null, List.of()));
            });
            SwingUtilities.invokeLater(ask);
            DialogRequest shown = awaitDialog(fixture.source);
            SwingUtilities.invokeAndWait(() -> { });
            assertSame(accepted, scene(fixture), "The queued command did not run inside the dialog's loop");
            fixture.source.answer(shown.id(), new DialogAnswer(0, List.of(), null, false, List.of()));
            ask.get(WAIT_SECONDS, SECONDS);
            assertNull(fixture.source.dialog());

            moves.planTo(hex, 0, false);
            SwingUtilities.invokeAndWait(() -> { });
            assertNotSame(accepted, scene(fixture), "Commands are accepted again once the dialog returned");
        }
    }

    @Test
    void closingTheSourceRemovesEveryGameListenerItAdded() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            // The fixture's view already holds its lazily created 3D tileset, which listens for the view's lifetime.
            int[] counts = onSwing(() -> {
                int before = fixture.game.getGameListeners().size();
                GpuBoardSource source = new GpuBoardSource(fixture.view, () -> fixture.panel);
                int open = fixture.game.getGameListeners().size();
                source.close();
                return new int[] { before, open, fixture.game.getGameListeners().size() };
            });
            assertTrue(counts[1] > counts[0], "The source and its services listen to the game while open");
            assertEquals(counts[0], counts[2], "Closing removes the source's and its services' listeners");
        }
    }

    @Test
    void phaseInfoNamesDoneAndSkipByStableIdsWhileTheirTextsChange() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            // Mocks are created on the test thread; the inline mock maker may fail to attach on the EDT.
            FiringDisplay phase = mock(FiringDisplay.class);
            MegaMekButton done = onSwing(() -> new MegaMekButton("<html><b>Fire</b></html>"));
            MegaMekButton skip = onSwing(() -> new MegaMekButton("<html><b>Skip firing</b></html>"));
            JPanel donePanel = onSwing(() -> {
                JPanel panel = new JPanel();
                panel.add(done);
                panel.add(skip);
                return panel;
            });
            when(phase.getComponents()).thenReturn(new Component[] { donePanel });
            when(phase.getActionButtons()).thenReturn(List.of());
            when(phase.getCompletionButtons()).thenReturn(List.of(done, skip));
            when(phase.getButDone()).thenReturn(done);
            GpuBoardActions actions = new GpuBoardActions(fixture.view, () -> phase, () -> false, () -> { });

            GpuBoardActions.PhaseInfo info = onSwing(() -> actions.phaseInfo(actions.phaseCommands()));
            assertEquals(List.of(GpuBoardActions.DONE_ID, GpuBoardActions.SKIP_ID, GpuBoardActions.CLEAR_ID),
                  List.of(info.doneId(), info.skipId(), info.clearId()));
            assertEquals("Fire", label(onSwing(actions::phaseCommands), info.doneId()));
            SwingUtilities.invokeAndWait(() -> done.setText("<html><b>Done</b></html>"));
            assertEquals("Done", label(onSwing(actions::phaseCommands), info.doneId()));
            assertEquals("Skip firing", label(onSwing(actions::phaseCommands), info.skipId()));
        }
    }

    @Test
    void menuCommandsCarryTheItemsAcceleratorAndToggleState() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean minimap = preferences.getMinimapEnabled();
        boolean unitDisplay = preferences.getUnitDisplayEnabled();
        boolean miniReport = preferences.getMiniReportEnabled();
        boolean playerList = preferences.getPlayerListEnabled();
        boolean roundsInAir = preferences.getRoundsInAirEnabled();
        boolean forceDisplay = preferences.getForceDisplayEnabled();
        ClientGUI gui = mock(ClientGUI.class);
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            BoardClientState view = spy(fixture.view);
            doReturn(gui).when(view).getClientgui();
            CommonMenuBar menus = onSwing(() -> {
                CommonMenuBar bar = CommonMenuBar.getMenuBarForGame();
                bar.setPhase(GamePhase.MOVEMENT);
                return bar;
            });
            when(gui.getMenuBar()).thenReturn(menus);
            try {
                GpuBoardActions actions = new GpuBoardActions(view, () -> fixture.panel, () -> false, () -> { });
                BoardScene.Command map = GpuBoardActions.menuItem(onSwing(actions::globalCommands),
                      ClientGUI.VIEW_MINI_MAP);
                assertEquals(KeyCommandBind.getDesc(KeyCommandBind.MINIMAP), map.shortcut());
                assertEquals(Boolean.FALSE, map.selected());
                SwingUtilities.invokeAndWait(() -> preferences.setMinimapEnabled(true));
                assertEquals(Boolean.TRUE, GpuBoardActions.menuItem(onSwing(actions::globalCommands),
                      ClientGUI.VIEW_MINI_MAP).selected());
                BoardScene.Command viewMenu = onSwing(actions::globalCommands).stream()
                      .filter(command -> command.children().stream().anyMatch(child -> child.id().equals(map.id())))
                      .findFirst().orElseThrow();
                assertEquals("", viewMenu.shortcut(), "A submenu has no accelerator");
                assertNull(viewMenu.selected(), "A submenu is not a toggle");
            } finally {
                SwingUtilities.invokeAndWait(() -> {
                    menus.die();
                    preferences.setMinimapEnabled(minimap);
                    preferences.setUnitDisplayEnabled(unitDisplay);
                    preferences.setMiniReportEnabled(miniReport);
                    preferences.setPlayerListEnabled(playerList);
                    preferences.setRoundsInAirEnabled(roundsInAir);
                    preferences.setForceDisplayEnabled(forceDisplay);
                });
            }
        }
    }

    private static String label(List<BoardScene.Command> commands, String id) {
        return commands.stream().filter(command -> command.id().equals(id)).findFirst().orElseThrow().label();
    }

    private static BoardScene scene(GpuBoardFixture fixture) {
        return fixture.source.takeFrame().scene();
    }

    private static Timer timer(GpuBoardSource source) {
        try {
            Field field = GpuBoardSource.class.getDeclaredField("timer");
            field.setAccessible(true);
            return (Timer) field.get(source);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    /** Polls the published dialog as the GL thread does every frame. */
    private static DialogRequest awaitDialog(GpuBoardSource source) throws InterruptedException {
        long deadline = System.nanoTime() + SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            DialogRequest shown = source.dialog();
            if (shown != null) {
                return shown;
            }
            Thread.sleep(5);
        }
        return fail("No dialog was shown");
    }

    private static <T> T onSwing(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
