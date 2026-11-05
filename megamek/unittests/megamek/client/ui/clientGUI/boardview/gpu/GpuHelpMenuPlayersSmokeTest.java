/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent;
import com.badlogic.gdx.utils.Pools;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHudState.Dialog;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiTestStage;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Help, Menu and Players dialogs (C.1 G14, C.2, plan A.15 O1-O3) in the component harness, centred as the HUD
 * places them: Help lists the binds' current keys by group with the mouse gestures, in the r1 3.19 dialog look; the
 * Menu shows the client's captured menu bar with shortcuts and check marks, keyboard and groups, runs every item's own
 * action except the C.2 redirects, which open the native surfaces; Players lists the players with the bots' commands.
 */
@Tag("on-demand")
class GpuHelpMenuPlayersSmokeTest {
    private static final String OFF = " (off)";

    /** A dialog under test with the services it posts to. */
    private static final class Dialogs {
        final GpuBoardSource source = mock(GpuBoardSource.class);
        final GpuLosResult los = mock(GpuLosResult.class);
        final GpuPlayers players = mock(GpuPlayers.class);
        final GpuHudState state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
        final BoardCamera camera = new BoardCamera();

        Dialogs() {
            when(source.los()).thenReturn(los);
            when(source.players()).thenReturn(players);
            camera.resize(1100, 750);
        }
    }

    @Test
    void helpCategoriesStayCompactAndShowCurrentBindings() {
        GpuHudTestStage.run(hud -> {
            Dialogs dialogs = new Dialogs();
            GpuHelpDialog help = new GpuHelpDialog(hud.kit, dialogs.source, dialogs.state);
            Table root = (Table) help.actor();
            hud.window.addActor(root);
            GpuBoardSource.UiPreferences preferences = GpuBoardSource.UiPreferences.capture();
            dialogs.state.dialog = Dialog.HELP;
            help.update(inputs(hud, frame(List.of(), GpuPlayers.Snapshot.EMPTY), preferences, false));
            centre(hud, root);
            List<String> lines = lines(root);
            assertEquals(List.of("CONTROLS", "Camera", "Selection", "Movement", "Weapons", "Playback", "Panels",
                  "Mouse"), lines.subList(0, 8));
            assertPair(lines, key(preferences, KeyCommandBind.SCROLL_NORTH), "KeyBinds.cmdNames.scrollN");
            assertPair(lines, key(preferences, KeyCommandBind.CAMERA_TILT_UP), "KeyBinds.cmdNames.cameraTiltUp");
            assertPair(lines, key(preferences, KeyCommandBind.CAMERA_FIT_BOARD), "KeyBinds.cmdNames.cameraFitBoard");
            assertFalse(lines.contains(Messages.getString("KeyBinds.cmdNames.moveModeWalk")));
            Rectangle area = GpuHudTestStage.bounds(root);
            assertEquals(620, area.width, .01f);
            assertTrue(area.height < 450, "a compact dialog rather than a window-height sheet");
            for (int[] size : List.of(new int[] {900, 600}, new int[] {1280, 720}, new int[] {1920, 1080})) {
                hud.size(size[0], size[1]);
                for (String category : List.of("Camera", "Selection", "Movement", "Weapons", "Playback", "Panels", "Mouse")) {
                    choose(item(root, category));
                    centre(hud, root);
                    hud.draw();
                    hud.assertLayout(root);
                    assertEquals(List.of(category), highlighted(root));
                    assertTrue(lines(root).stream().noneMatch(line -> line.contains("GPU Battle View")));
                    hud.capture("help-" + category.toLowerCase() + "-" + size[0]).dispose();
                }
            }
            assertTrue(lines(root).contains(Messages.getString("GpuBoard.hud.help.leftClick")));
            choose(item(root, "Movement"));
            assertPair(lines(root), key(preferences, KeyCommandBind.MOVE_MODE_WALK), "KeyBinds.cmdNames.moveModeWalk");
            choose(item(root, "Panels"));

            // A new key shows at once; an unbound command drops out.
            List<GpuBoardSource.Bind> binds = new ArrayList<>(preferences.binds());
            binds.replaceAll(bind -> bind.command() == KeyCommandBind.KEY_BINDS
                  ? new GpuBoardSource.Bind(bind.command(), bind.keyCode(), bind.modifiers(), "F12")
                  : bind.command() == KeyCommandBind.FORCES_GRID
                  ? new GpuBoardSource.Bind(bind.command(), 0, 0, "") : bind);
            GpuBoardSource.UiPreferences changed = GpuHudFixtures.with(preferences, Map.of("binds", binds));
            help.update(inputs(hud, frame(List.of(), GpuPlayers.Snapshot.EMPTY), changed, false));
            lines = lines(root);
            assertPair(lines, "F12", "KeyBinds.cmdNames.toggleKeybinds");
            assertFalse(lines.contains(Messages.getString("KeyBinds.cmdNames.forcesGrid")));
        });
    }

    @Test
    void menuShowsTheCapturedMenuBarWithShortcutsCheckMarksGroupsAndKeys() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            List<String> ran = new CopyOnWriteArrayList<>();
            List<BoardScene.Command> menuBar = menuBar(fixture, ran);
            GpuHudTestStage.run(hud -> {
                Dialogs dialogs = new Dialogs();
                GpuMenuPanel menu = new GpuMenuPanel(hud.kit, dialogs.source, dialogs.state, dialogs.camera);
                Table root = (Table) menu.actor();
                hud.window.addActor(root);
                GpuBoardSource.UiPreferences preferences = GpuBoardSource.UiPreferences.capture();
                GpuHud.Inputs inputs = inputs(hud, frame(menuBar, GpuPlayers.Snapshot.EMPTY), preferences, true);
                dialogs.state.overview = true;
                dialogs.state.dialog = Dialog.MENU;
                menu.update(inputs);
                centre(hud, root);
                assertEquals(List.of("MENU", players() + " [" + key(preferences, KeyCommandBind.BOT_COMMANDS) + "]",
                      "---", menu("FileMenu") + " ›", menu("GameMenu") + " ›", menu("BoardMenu") + " ›",
                      menu("ViewMenu") + " ›", menu("HelpMenu") + " ›",
                      Messages.getString("GameCommands.title") + " ›", "Maps ›"), lines(root),
                      "the Players row, then one group per menu of the menu bar, the commands and the maps");
                hud.draw();
                hud.capture("menu-panel").dispose();

                // The keyboard: Down from no highlight starts at the first row; Enter opens a group in place.
                assertSame(root, hud.stage.getKeyboardFocus(), "the open menu takes the keyboard");
                for (int row = 0; row < 5; row++) {
                    hud.stage.keyDown(Input.Keys.DOWN);
                }
                assertEquals(List.of(menu("ViewMenu")), highlighted(root));
                assertTrue(hud.stage.keyDown(Input.Keys.ENTER));
                menu.update(inputs);
                centre(hud, root);
                List<String> lines = lines(root);
                assertTrue(lines.contains(menu("ViewMenu") + " ⌄"), "an open group: " + lines);
                assertEquals(List.of(menu("ViewMenu")), highlighted(root), "the highlight stays on the group");
                BoardScene.Command view = menuBar.stream().filter(command -> command.label().equals(menu("ViewMenu")))
                      .findFirst().orElseThrow();
                int first = lines.indexOf(menu("ViewMenu") + " ⌄") + 1;
                int next = lines.indexOf(menu("HelpMenu") + " ›");
                assertEquals(view.children().size() - 1, next - first,
                      "every View item but the window positions, which place Swing windows");
                assertFalse(lines.contains(menu("viewResetWindowPos")));
                assertTrue(lines.contains(menu("viewClientSettings") + " ["
                      + key(preferences, KeyCommandBind.CLIENT_SETTINGS) + "]"), "a shortcut: " + lines);
                assertTrue(lines.contains("✓ " + menu("viewForceDisplay") + " ["
                      + key(preferences, KeyCommandBind.FORCE_DISPLAY) + "]"), "checked: the overview is open");
                assertTrue(lines.contains("✓ " + Messages.getString("GpuBoard.hud.util.tactical") + " ["
                      + key(preferences, KeyCommandBind.TOGGLE_ISO) + "]"),
                      "the isometric item is the Tactical View, by name and check mark");
                assertTrue(lines.contains(menu("viewMekDisplay") + " ["
                      + key(preferences, KeyCommandBind.UNIT_DISPLAY) + "]"), "no sheet is open");
                hud.stage.keyDown(Input.Keys.DOWN);
                assertEquals(List.of(view.children().getFirst().label()), highlighted(root));

                // A nested group (the bot commands panel's place) opens below its row, its radio items checked.
                choose(item(root, menu("viewBotCommands")));
                menu.update(inputs);
                centre(hud, root);
                lines = lines(root);
                int bots = lines.indexOf(menu("viewBotCommands") + " ⌄");
                assertTrue(bots > 0, "the nested group is open: " + lines);
                assertEquals(1, lines.subList(bots + 1, bots + 4).stream().filter(line -> line.startsWith("✓ "))
                      .count(), "one radio item is checked: " + lines.subList(bots + 1, bots + 4));
                Rectangle area = GpuHudTestStage.bounds(root);
                assertEquals(hud.height() - 140, area.height, .01f, "the open View menu scrolls");
                ScrollPane list = root.findActor("menu-list");
                assertTrue(list.getMaxY() > 0);
                hud.draw();
                hud.capture("menu-panel-view").dispose();

                dialogs.state.dialog = Dialog.NONE;
                menu.update(inputs);
                assertTrue(hud.stage.getKeyboardFocus() == null, "a closed menu gives the keyboard back");
                dialogs.state.dialog = Dialog.MENU;
                menu.update(inputs);
                assertTrue(highlighted(root).isEmpty(), "a reopened menu starts without a highlight");
                assertTrue(lines(root).contains(menu("ViewMenu") + " ⌄"), "and keeps its open groups");
                choose(item(root, "Maps"));
                menu.update(inputs);
                choose(item(root, "Map 0"));
                SwingUtilities.invokeAndWait(() -> { });
                assertEquals(List.of("Map 0"), ran, "an item runs its own action");
                assertEquals(Dialog.NONE, dialogs.state.dialog, "choosing an item closes the menu");
            });
        }
    }

    @Test
    void menuRedirectsOpenTheNativeSurfacesAndEveryOtherItemRunsItsSwingAction() throws Exception {
        boolean keybinds = GUIPreferences.getInstance().getShowKeybindsOverlay();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            List<String> ran = new CopyOnWriteArrayList<>();
            List<BoardScene.Command> menuBar = menuBar(fixture, ran);
            GpuHudTestStage.run(hud -> {
                Dialogs dialogs = new Dialogs();
                GpuHudState state = dialogs.state;
                BoardCamera camera = dialogs.camera;
                GpuMenuPanel menu = new GpuMenuPanel(hud.kit, dialogs.source, state, camera);
                Table root = (Table) menu.actor();
                hud.window.addActor(root);
                GpuHud.Inputs inputs = inputs(hud, frame(menuBar, GpuPlayers.Snapshot.EMPTY),
                      GpuBoardSource.UiPreferences.capture(), false);
                Runnable open = () -> {
                    state.dialog = Dialog.MENU;
                    menu.update(inputs);
                    centre(hud, root);
                };
                open.run();
                choose(item(root, menu("ViewMenu")));
                choose(item(root, menu("GameMenu")));

                choose(root, open, menu("viewMekDisplay"));
                assertTrue(state.recordOpen, "Unit Display opens the record sheet of the card unit");
                choose(root, open, menu("viewForceDisplay"));
                assertTrue(state.overview, "Force Display opens the forces overview");
                choose(root, open, menu("viewForceDisplay"));
                assertFalse(state.overview, "and closes it");
                choose(root, open, menu("viewKeyboardShortcuts"));
                assertEquals(Dialog.HELP, state.dialog, "the key bindings overlay is the Help dialog");
                choose(root, open, menu("viewPlayerList"));
                assertEquals(Dialog.PLAYERS, state.dialog, "the player list is the Players panel");
                choose(root, open, menu("viewRoundReport"));
                assertTrue(state.logOpen(), "the round report is the log");
                choose(root, open, menu("viewRoundReport"));
                assertFalse(state.logOpen(), "toggled as the Log utility does");
                choose(root, open, menu("viewRoundsInAir"));
                choose(root, open, menu("viewRoundsInAir"));
                assertTrue(state.logOpen(), "the rounds in the air are the log's artillery in flight; it stays open");
                float zoom = camera.camera.zoom;
                choose(root, open, menu("viewZoomIn"));
                assertEquals(zoom / 1.2f, camera.camera.zoom, 1e-4f, "Zoom In zooms the board camera");
                choose(root, open, menu("viewZoomOut"));
                assertEquals(zoom, camera.camera.zoom, 1e-4f, "Zoom Out zooms back");
                choose(root, open, menu("viewZoomOverviewToggle"));
                assertTrue(Math.abs(camera.camera.zoom - zoom) > 1e-3f, "the overview fits the board");
                choose(root, open, menu("viewZoomOverviewToggle"));
                assertEquals(zoom, camera.camera.zoom, 1e-4f, "and returns");
                choose(root, open, Messages.getString("GpuBoard.hud.util.tactical"));
                assertTrue(camera.tactical(), "the View menu's isometric item is the Tactical View, as T");
                SwingUtilities.invokeAndWait(() -> { });
                assertEquals(List.of(), ran, "no redirected item runs its Swing action");
                assertEquals(keybinds, GUIPreferences.getInstance().getShowKeybindsOverlay(),
                      "the Swing key bindings overlay is untouched");

                open.run();
                choose(item(root, players()));
                assertEquals(Dialog.PLAYERS, state.dialog, "the first row opens the Players panel");
                choose(root, open, menu("viewGameOptions"));
                SwingUtilities.invokeAndWait(() -> { });
                assertEquals(List.of(ClientGUI.VIEW_GAME_OPTIONS), ran, "an item without a redirect runs Swing's");
                // Line of sight stays MegaMek's ruler.
                choose(root, open, menu("viewLOSSetting"));
                SwingUtilities.invokeAndWait(() -> { });
                assertEquals(List.of(ClientGUI.VIEW_GAME_OPTIONS, ClientGUI.VIEW_LOS_SETTING), ran,
                      "the Ruler / LOS Tool runs Swing's ruler");

                GpuMenuPanel boardless = new GpuMenuPanel(hud.kit, dialogs.source, state, camera);
                Table other = (Table) boardless.actor();
                hud.window.addActor(other);
                GpuBoardSource.Frame shown = inputs.frame();
                GpuHud.Inputs noBoard = inputs(hud, new GpuBoardSource.Frame(null, List.of(), null,
                      shown.globalCommands(), "", null, 0, "", null, shown.reports(), shown.status(),
                      shown.panels()), GpuBoardSource.UiPreferences.capture(), false);
                state.dialog = Dialog.MENU;
                boardless.update(noBoard);
                choose(item(other, menu("ViewMenu")));
                boardless.update(noBoard);
                List<String> lines = lines(other);
                String tactical = Messages.getString("GpuBoard.hud.util.tactical");
                for (String cameraItem : List.of(menu("viewZoomIn"), tactical)) {
                    assertTrue(lines.stream().anyMatch(line -> line.contains(cameraItem) && line.endsWith(OFF)),
                          "before the first board its camera items are unavailable: " + lines);
                }
            });
        }
    }

    @Test
    void playersShowEachPlayerAndTheBotsCommandsAsGroups() {
        GpuHudTestStage.run(hud -> {
            Dialogs dialogs = new Dialogs();
            GpuPlayersPanel panel = new GpuPlayersPanel(hud.kit, dialogs.source, dialogs.state);
            Table root = (Table) panel.actor();
            hud.window.addActor(root);
            List<String> ran = new ArrayList<>();
            BoardScene.Command posture = new BoardScene.Command("posture", "Combat Posture", "", true, false,
                  List.of(command("Attack", ran), command("Defend", ran)), () -> { });
            BoardScene.Command maneuver = new BoardScene.Command("Maneuver", "Maneuver", "Quick maneuvers", true,
                  false, List.of(posture, command("Alpha Strike!", ran)), () -> { });
            BoardScene.Command behavior = new BoardScene.Command("Set Behavior", "Set Behavior", "", true, false,
                  List.of(command("BERSERK", ran)), () -> { });
            BoardScene.Command pause = new BoardScene.Command("bot-pause", "Pause Game", "Only bots play", false,
                  false, List.of(), () -> ran.add("pause"));
            GpuPlayers.Snapshot players = new GpuPlayers.Snapshot(List.of(
                  player(0, "GPU review", 1, true, false, false, List.of()),
                  player(3, "Princess", 1, false, true, true, List.of(maneuver, behavior)),
                  player(4, "Opponent", 2, false, false, false, List.of())), pause);
            GpuHud.Inputs inputs = inputs(hud, frame(List.of(), players), GpuBoardSource.UiPreferences.capture(),
                  false);
            dialogs.state.dialog = Dialog.PLAYERS;
            panel.update(inputs);
            verify(dialogs.players).setPanelOpen(true);
            centre(hud, root);
            String waiting = Messages.getString("GpuBoard.hud.players.waiting").toUpperCase();
            String team1 = Messages.getString("GpuBoard.hud.players.team", 1);
            assertEquals(List.of("PLAYERS", "Pause Game" + OFF, "---", "GPU review", waiting, team1, "---",
                  "Princess", Messages.getString("GpuBoard.hud.players.done").toUpperCase(),
                  team1 + " · " + Messages.getString("GpuBoard.hud.players.bot"), "Maneuver ›", "Set Behavior ›",
                  "---", "Opponent", waiting, Messages.getString("GpuBoard.hud.players.team", 2)), lines(root));
            hud.draw();
            hud.capture("players-panel").dispose();

            assertSame(root, hud.stage.getKeyboardFocus());
            hud.stage.keyDown(Input.Keys.DOWN);
            assertEquals(List.of("Maneuver"), highlighted(root), "the unavailable Pause row is skipped");
            hud.stage.keyDown(Input.Keys.ENTER);
            panel.update(inputs);
            centre(hud, root);
            List<String> lines = lines(root);
            assertEquals(List.of("Maneuver ⌄", "Combat Posture ›", "Alpha Strike!", "Set Behavior ›"),
                  lines.subList(lines.indexOf("Maneuver ⌄"), lines.indexOf("Maneuver ⌄") + 4));
            hud.draw();
            hud.capture("players-panel-maneuver").dispose();
            choose(item(root, "Alpha Strike!"));
            assertEquals(List.of("Alpha Strike!"), ran, "a bot command runs its own action");
            assertEquals(Dialog.NONE, dialogs.state.dialog, "and closes the panel for the board");
            panel.update(inputs);
            verify(dialogs.players).setPanelOpen(false);

            // The marks the Swing list shows.
            GpuPlayers.PlayerRow referee = new GpuPlayers.PlayerRow(5, "Referee", 0, 0xFFB9A8FF, false, false,
                  false, true, true, true, true, true, true, List.of());
            dialogs.state.dialog = Dialog.PLAYERS;
            panel.update(inputs(hud, frame(List.of(), new GpuPlayers.Snapshot(List.of(referee))),
                  GpuBoardSource.UiPreferences.capture(), false));
            centre(hud, root);
            assertEquals(List.of("PLAYERS", "Referee", String.join(" · ", List.of("loneWolf", "observer", "ghost",
                        "gameMaster", "seeAll", "singleBlind", "ignoreDoubleBlind").stream()
                        .map(mark -> Messages.getString("GpuBoard.hud.players." + mark)).toList())), lines(root),
                  "neither done nor waiting for an observer or a ghost");
            hud.draw();
            hud.capture("players-panel-marks").dispose();
        });
    }

    @Test
    void theHudOpensTheDialogsFromTheirKeysCentredAndOneAtATime() {
        GpuHudTestStage.run(harness -> {
            SpriteBatch batch = new SpriteBatch();
            Dialogs dialogs = new Dialogs();
            when(dialogs.source.record()).thenReturn(mock(GpuUnitRecord.class));
            GpuHud hud = new GpuHud(dialogs.source, harness.theme.skin, batch, new BoardCamera(),
                  mock(GpuBoardTuning.class), new GpuPlaybackHistory(new UnitPlayback()));
            try {
                float density = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
                hud.resize(Math.round(harness.width() / density), Math.round(harness.height() / density),
                      1 / density);
                GpuBoardSource.Frame frame = GpuHudFixtures.frame(GpuHudFixtures.status(1, GamePhase.MOVEMENT,
                      false, Entity.NONE, 0), GpuHudFixtures.panels(GpuMovePlan.Snapshot.EMPTY,
                      GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY));
                GpuBoardSource.UiPreferences preferences = GpuBoardSource.UiPreferences.capture();
                hud.update(frame, GpuHud.HudView.EMPTY, null, preferences);
                Actor players = hud.stage.getRoot().findActor("players-panel");
                Actor help = hud.stage.getRoot().findActor("help-dialog");
                Actor menu = hud.stage.getRoot().findActor("menu-panel");

                // BOT_COMMANDS (Ctrl+Shift+G) opens the players panel, which captures the bot commands while open.
                assertTrue(hud.keyDown(Input.Keys.G, KeyEvent.VK_G,
                      InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
                hud.update(frame, GpuHud.HudView.EMPTY, null, preferences);
                hud.draw();
                assertTrue(shown(players));
                verify(dialogs.players).setPanelOpen(true);
                Rectangle area = GpuHudTestStage.bounds(players);
                assertEquals(620, area.width, .01f);
                assertEquals((harness.width() - 620) / 2f, area.x, .01f, "centred");
                assertEquals((harness.height() - area.height) / 2, area.y, .51f);

                // KEY_BINDS (Ctrl+K) opens Help instead; Esc closes it at once, as it holds no keyboard focus.
                assertTrue(hud.keyDown(Input.Keys.K, KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK));
                hud.update(frame, GpuHud.HudView.EMPTY, null, preferences);
                assertTrue(shown(help) && !shown(players), "one dialog at a time");
                verify(dialogs.players).setPanelOpen(false);
                assertTrue(hud.isModal());
                assertTrue(hud.keyDown(Input.Keys.W, KeyEvent.VK_W, 0), "camera keys stay in the modal");
                boolean grid = hud.state.forcesGrid;
                assertTrue(hud.keyDown(Input.Keys.G, KeyEvent.VK_G, 0));
                assertEquals(grid, hud.state.forcesGrid, "background panel shortcuts stay blocked");
                assertTrue(hud.keyTyped('w'));
                assertTrue(hud.stage.getKeyboardFocus().isDescendantOf(help.getParent().getParent()));
                assertTrue(hud.hit(2, 2), "even the board outside the dialog is blocked");
                Actor backdrop = hud.stage.getRoot().findActor("dialog-backdrop");
                assertSame(backdrop, hud.stage.hit(2, 2, true));
                hud.stage.setScrollFocus(backdrop);
                assertTrue(hud.stage.scrolled(0, 1), "the backdrop consumes the wheel");
                ScreenUtils.clear(.42f, .5f, .3f, 1, true);
                hud.draw();
                harness.capture("hud-controls-modal").dispose();
                byte[] dimmedBackground = ScreenUtils.getFrameBufferPixels(2, 2, 1, 1, false);
                var request = new GpuBoardWindow.DialogRequest(91, GpuBoardWindow.DialogKind.MESSAGE,
                      "Nested prompt", "This prompt shares the Controls backdrop.", false, List.of("OK"), 0, -1,
                      List.of(), List.of(), "", false, null, null, null, List.of());
                hud.update(frame, GpuHud.HudView.EMPTY, request, preferences);
                ScreenUtils.clear(.42f, .5f, .3f, 1, true);
                hud.draw();
                assertArrayEquals(dimmedBackground, ScreenUtils.getFrameBufferPixels(2, 2, 1, 1, false),
                      "native prompts and centered dialogs share one dimming pass");
                assertTrue(shown(help), "Controls stays beneath the prompt");
                assertSame(hud.stage.getRoot().findActor("modal-dialog"), hud.stage.getKeyboardFocus());
                harness.capture("hud-nested-modal").dispose();
                hud.update(frame, GpuHud.HudView.EMPTY, null, preferences);
                ScreenUtils.clear(.42f, .5f, .3f, 1, true);
                hud.draw();
                assertArrayEquals(dimmedBackground, ScreenUtils.getFrameBufferPixels(2, 2, 1, 1, false));
                assertSame(backdrop, hud.stage.getKeyboardFocus(), "closing the prompt restores the lower modal");
                // Content clicks stay inside; dismissing the backdrop consumes both ends of the click.
                choose(item(help, "Movement"));
                assertEquals(Dialog.HELP, hud.state.dialog);
                Vector2 outside = hud.stage.stageToScreenCoordinates(new Vector2(2, 2));
                assertTrue(hud.stage.touchDown((int) outside.x, (int) outside.y, 0, Input.Buttons.LEFT));
                hud.stage.touchUp((int) outside.x, (int) outside.y, 0, Input.Buttons.LEFT);
                assertEquals(Dialog.NONE, hud.state.dialog);
                hud.update(frame, GpuHud.HudView.EMPTY, null, preferences);
                assertFalse(backdrop.isVisible());
                assertFalse(hud.isModal());
                assertTrue(hud.stage.getKeyboardFocus() == null);
                hud.state.dialog = Dialog.HELP;
                hud.update(frame, GpuHud.HudView.EMPTY, null, preferences);
                assertTrue(hud.keyDown(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE, 0));
                assertEquals(Dialog.NONE, hud.state.dialog);

                // The Menu utility opens the Menu, which takes the keyboard for its list: one Esc closes the Menu
                // with that focus (I1a), as for the context menu.
                hud.state.toggle(Dialog.MENU);
                hud.update(frame, GpuHud.HudView.EMPTY, null, preferences);
                assertTrue(shown(menu));
                assertTrue(hud.stage.getKeyboardFocus().isDescendantOf(menu));
                assertTrue(hud.keyDown(Input.Keys.DOWN, KeyEvent.VK_DOWN, 0), "the open Menu takes the arrows");
                ScreenUtils.clear(.1f, .13f, .13f, 1, true);
                hud.draw();
                assertTrue(hud.isModal());
                assertTrue(backdrop.isVisible());
                // The capture checks the harness stage's texts; the HUD draws on its own stage.
                UiTestStage.assertTexts(hud.stage.getRoot());
                harness.capture("hud-menu-open").dispose();
                assertTrue(hud.keyDown(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE, 0));
                assertEquals(Dialog.NONE, hud.state.dialog);
                assertTrue(hud.stage.getKeyboardFocus() == null, "the closed Menu keeps no focus");
                hud.state.dialog = Dialog.MENU;
                hud.update(frame, GpuHud.HudView.EMPTY, null, preferences);
                assertTrue(hud.stage.touchDown((int) outside.x, (int) outside.y, 0, Input.Buttons.LEFT));
                hud.stage.touchUp((int) outside.x, (int) outside.y, 0, Input.Buttons.LEFT);
                assertEquals(Dialog.NONE, hud.state.dialog, "Menu shares the same outside-click dismissal");
            } finally {
                hud.dispose();
                batch.dispose();
            }
        });
    }

    private static boolean shown(Actor actor) {
        return actor.isVisible() && actor.getParent().isVisible();
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * EDT: the client's menu bar in a movement turn as GpuBoardActions captures it, with the source's maps group;
     * {@code ran} records the action command of every item the menu bar runs and the map item.
     */
    static List<BoardScene.Command> menuBar(GpuBoardFixture fixture, List<String> ran) throws Exception {
        return GpuTestSupport.onSwing(() -> {
            CommonMenuBar menus = CommonMenuBar.getMenuBarForGame();
            menus.setPhase(GamePhase.MOVEMENT);
            menus.addActionListener(event -> ran.add(event.getActionCommand()));
            Client client = mock(Client.class);
            when(client.getGame()).thenReturn(fixture.game);
            when(client.getLocalPlayer()).thenReturn(fixture.player);
            ClientGUI gui = mock(ClientGUI.class);
            when(gui.getMenuBar()).thenReturn(menus);
            when(gui.getClient()).thenReturn(client);
            BoardClientState view = spy(fixture.view);
            doReturn(gui).when(view).getClientgui();
            List<BoardScene.Command> commands = new ArrayList<>(new GpuBoardActions(view, () -> fixture.panel,
                  () -> false, () -> { }).globalCommands());
            // GpuBoardSource.capture adds the boards as "Maps".
            commands.add(new BoardScene.Command("boards", "Maps", "", true, false,
                  List.of(new BoardScene.Command("Map 0", true, () -> ran.add("Map 0"))), () -> { }));
            return commands;
        });
    }

    private static GpuBoardSource.Frame frame(List<BoardScene.Command> menuBar, GpuPlayers.Snapshot players) {
        GpuHudData panels = new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY, GpuMovePlan.Snapshot.EMPTY,
              GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY,
              GpuFirePreview.Snapshot.NONE, GpuChat.Snapshot.EMPTY, GpuToasts.Snapshot.EMPTY,
              RulerModel.Snapshot.NONE, players);
        return new GpuBoardSource.Frame(board(), List.of(), null, menuBar, "", null, 0, "", null,
              GpuReportLog.Snapshot.EMPTY, GpuBattleStatus.Snapshot.EMPTY, panels);
    }

    private static GpuHud.Inputs inputs(GpuHudTestStage hud, GpuBoardSource.Frame frame,
          GpuBoardSource.UiPreferences preferences, boolean tactical) {
        GpuHud.HudView view = new GpuHud.HudView(tactical, false, Map.of(), Map.of(), Map.of(), null, Entity.NONE, 0);
        return new GpuHud.Inputs(frame, view, null, preferences, GpuHud.Metrics.of(hud.width(), hud.height()),
              List.of());
    }

    /** A flat 16 x 17 board, the fixture board's size. */
    private static BoardScene board() {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 17; y++) {
                tiles.add(new BoardScene.Tile(new Coords(x, y), 0, -1, false, 0, null, null, List.of(),
                      List.of()));
            }
        }
        return new BoardScene(0, 16, 17, tiles, List.of(), List.of(), -1, "MOVEMENT", List.of());
    }

    private static GpuPlayers.PlayerRow player(int id, String name, int team, boolean local, boolean done,
          boolean bot, List<BoardScene.Command> commands) {
        return new GpuPlayers.PlayerRow(id, name, team, local ? 0xFF82E2CE : 0xFFEC9189, local, done, bot, false,
              false, false, false, false, false, commands);
    }

    private static BoardScene.Command command(String label, List<String> ran) {
        return new BoardScene.Command(label, label, "", true, false, List.of(), () -> ran.add(label));
    }

    /** Places the dialog as GpuHud does (C.1: centred, at most 620 wide and 140 less than the window high). */
    private static void centre(GpuHudTestStage hud, Table dialog) {
        float width = Math.min(620, hud.width() - 40);
        for (int pass = 0; pass < 2; pass++) {
            dialog.setWidth(width);
            dialog.validate();
            float height = Math.min(dialog.getPrefHeight(), hud.height() - 140);
            dialog.setBounds((hud.width() - width) / 2, (hud.height() - height) / 2, width, height);
            dialog.validate();
        }
    }

    // ---------------------------------------------------------------- reading and choosing

    /** Opens the menu and chooses the item with this text, as a click or Enter does; open groups stay open. */
    private static void choose(Table root, Runnable open, String text) {
        open.run();
        choose(item(root, text));
    }

    /** Chooses an item as a click or the menu list's Enter does. */
    private static void choose(UiButton item) {
        ChangeEvent event = Pools.obtain(ChangeEvent.class);
        item.fire(event);
        Pools.free(event);
    }

    /** The dialog's menu item with this text. */
    private static UiButton item(Actor actor, String text) {
        UiButton item = find(actor, text);
        assertNotNull(item, "no item \"" + text + "\" in " + lines(actor));
        return item;
    }

    /** The first menu item or button with this text under {@code actor}, or null. */
    static UiButton find(Actor actor, String text) {
        if (actor instanceof UiButton item && item.getText().toString().equals(text)) {
            return item;
        } else if (actor instanceof Group group && !(actor instanceof UiButton)) {
            for (Actor child : group.getChildren()) {
                UiButton found = find(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * The dialog as the player reads it, top to bottom: the title, captions and texts; each menu item as its text with
     * "✓ " when checked, " ›" for a closed group and " ⌄" for an open one, its shortcut in brackets and " (off)" when
     * unavailable; "---" for a separator. The close button has no text.
     */
    private static List<String> lines(Actor actor) {
        List<String> lines = new ArrayList<>();
        collect(actor, lines);
        return lines;
    }

    private static void collect(Actor actor, List<String> lines) {
        if (actor instanceof UiButton item) {
            if (item.getText().isEmpty()) {
                return;
            }
            String detail = item.details.isEmpty() ? "" : " [" + item.details.getFirst().getText() + "]";
            boolean checked = item.icons.stream().anyMatch(icon -> icon.isVisible()
                  && ((Image) icon).getDrawable() == item.getSkin().getDrawable("icon-check"));
            String group = item.icons.stream().map(icon -> ((Image) icon).getDrawable())
                  .anyMatch(drawable -> drawable == item.getSkin().getDrawable("icon-chevron-down")) ? " ⌄"
                  : item.icons.stream().map(icon -> ((Image) icon).getDrawable())
                  .anyMatch(drawable -> drawable == item.getSkin().getDrawable("icon-chevron-right")) ? " ›" : "";
            lines.add((checked ? "✓ " : "") + item.getText() + group + detail + (item.isDisabled() ? OFF : ""));
        } else if (actor instanceof Label label && !label.getText().isEmpty()) {
            lines.add(label.getText().toString());
        } else if (actor instanceof Image && actor.getParent() instanceof UiMenuList) {
            lines.add("---");
        } else if (actor instanceof Table table) {
            for (Cell<?> cell : table.getCells()) {
                if (cell.getActor() != null) {
                    collect(cell.getActor(), lines);
                }
            }
        } else if (actor instanceof Group group) {
            group.getChildren().forEach(child -> collect(child, lines));
        }
    }

    /** The texts of the keyboard-highlighted items. */
    private static List<String> highlighted(Actor actor) {
        List<String> texts = new ArrayList<>();
        if (actor instanceof UiButton item) {
            if (item.isChecked()) {
                texts.add(item.getText().toString());
            }
        } else if (actor instanceof Group group) {
            group.getChildren().forEach(child -> texts.addAll(highlighted(child)));
        }
        return texts;
    }

    /** The action name directly followed by its shortcut. */
    private static void assertPair(List<String> lines, String key, String name) {
        boolean found = false;
        for (int index = 0; index + 1 < lines.size() && !found; index++) {
            found = lines.get(index).equals(Messages.getString(name)) && lines.get(index + 1).equals(key);
        }
        assertTrue(found, "\"" + key + "\" beside \"" + Messages.getString(name) + "\" in " + lines);
    }

    private static String key(GpuBoardSource.UiPreferences preferences, KeyCommandBind bind) {
        return GpuHintLine.key(preferences, bind);
    }

    static String menu(String key) {
        return Messages.getString("CommonMenuBar." + key);
    }

    private static String players() {
        return Messages.getString("GpuBoard.hud.players.title");
    }
}
