/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.JComponent;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent;
import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.MegaMekGUI;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.clientGUI.unitDisplay.UnitDisplayState;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.panels.phaseDisplay.DeploymentDisplay;
import megamek.client.ui.panels.phaseDisplay.ReportDisplay;
import megamek.client.ui.util.MegaMekController;
import megamek.common.enums.GamePhase;
import megamek.common.game.GameTurn;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * No command is lost with the old UI (plan I2 T2). Every item of the client's menu bar as GpuBoardActions captures it,
 * with the game commands and the maps, has its row in the HUD's Menu once its groups are open; only View > Reset window
 * positions is left out, as it places Swing windows that no longer show. And in a turn of each kind, every command of
 * MegaMek's real phase display is reachable in the HUD: as the dock's main or second button, a button of its row, an
 * entry of its More, or the native control that stands for it (plan C.2).
 */
@Tag("on-demand")
class GpuMenuCoverageSmokeTest {
    /** The phase display's "clear" command where MegaMek's own CANCEL key, which the HUD forwards, runs it. */
    private static final String CANCEL_KEY = "the CANCEL key";
    /** The club attack: a physical option of a unit that carries a club; the scene's Atlas carries none. */
    private static final String WITH_A_CLUB = "a club's physical option";

    @Test
    void everyMenuBarItemHasItsRowInTheMenu() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            List<String> ran = new CopyOnWriteArrayList<>();
            List<BoardScene.Command> menuBar = GpuHelpMenuPlayersSmokeTest.menuBar(fixture, ran);
            GpuHudTestStage.run(harness -> {
                GpuHudState state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
                GpuMenuPanel menu = new GpuMenuPanel(harness.kit, mock(GpuBoardSource.class), state, new BoardCamera());
                Table root = (Table) menu.actor();
                harness.window.addActor(root);
                GpuHud.Inputs inputs = new GpuHud.Inputs(frame(menuBar), GpuHud.HudView.EMPTY, null,
                      GpuHudFixtures.preferences(), GpuHud.Metrics.of(harness.width(), harness.height()), List.of());
                state.dialog = GpuHudState.Dialog.MENU;
                Runnable show = () -> {
                    menu.update(inputs);
                    root.setBounds(0, 0, harness.width(), harness.height());
                    root.validate();
                    harness.draw();
                };
                show.run();
                // Opens each closed group, a frame at a time, until every group shows its items.
                for (UiButton group = closedGroup(root, menuBar, ""); group != null;
                      group = closedGroup(root, menuBar, "")) {
                    group.fire(new ChangeEvent());
                    show.run();
                }
                Map<String, String> expected = new LinkedHashMap<>();
                rows(menuBar, "", expected);
                assertTrue(expected.size() > 60, "the captured menu bar's " + expected.size() + " items");
                for (Map.Entry<String, String> row : expected.entrySet()) {
                    UiButton item = root.findActor(row.getKey());
                    assertNotNull(item, "no row for " + row.getKey());
                    assertEquals(row.getValue(), item.getText().toString(), row.getKey());
                }
                BoardScene.Command positions = GpuBoardActions.menuItem(menuBar, ClientGUI.VIEW_RESET_WINDOW_POSITIONS);
                assertNotNull(positions, "the menu bar has the window positions item");
                assertNull(root.findActor(key(menuBar, positions)), "which the Menu leaves out");
                assertEquals(List.of(), ran, "opening groups runs no command");
            });
        }
    }

    @Test
    void everyPhaseCommandOfARealPhaseDisplayIsReachable() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            moving.board.panel = moving.display;
            assertReachable("movement", moving.board.source, Map.of(
                  "moveWalk", "dock-mode-walk", "moveJump", "dock-mode-jump", "moveTurn", "dock-turn-left",
                  "moveBackUp", "dock-more", "moveNext", "forces-next", "clear", "dock-more"));
        }
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            // The weapons panel picks the weapon (Skip is MegaMek's next weapon), its target, mode and called shot.
            assertReachable("firing", firing.board.source, Map.of(
                  "fireTwist", "dock-twist-left", "fireFire", "dock-main", "fireNext", "forces-next",
                  "fireSkip", "weapons-panel", "fireNextTarg", "weapons-panel", "fireMode", "weapons-panel",
                  "fireCalled", "weapons-panel", "clear", "dock-clear"));
        }
        try (GpuPhysicalOptionsTest.Scene physical = GpuPhysicalOptionsTest.Scene.create(yesClient(),
              "testresources/megamek/common/units/Atlas AS7-D.mtf", 0)) {
            // The punches and kicks are options per limb; a club is one for a unit that carries a club.
            assertReachable("physical", physical.source, Map.of("next", "forces-next",
                  "punch", "dock-option-punchLeft", "kick", "dock-option-kickLeft", "club", WITH_A_CLUB,
                  "clear", CANCEL_KEY));
        }
        try (Display deployment = new Display(GamePhase.DEPLOYMENT, DeploymentDisplay::new)) {
            assertReachable("deployment", deployment.board.source, Map.of("clear", CANCEL_KEY));
        }
        // The log and the Menu stand for MegaMek's report and player list toggles.
        Map<String, String> reports = Map.of("reportReport", "utility-log", "reportPlayerList", "utility-menu",
              "clear", CANCEL_KEY);
        for (GamePhase phase : List.of(GamePhase.FIRING_REPORT, GamePhase.END_REPORT)) {
            try (Display report = new Display(phase, ReportDisplay::new)) {
                assertReachable(phase.toString(), report.board.source, reports);
            }
        }
        try (Display initiative = new Display(GamePhase.INITIATIVE_REPORT, ReportDisplay::new)) {
            // MegaMek offers the reroll to a player with a tactical genius; the dock shows it as its second button.
            onSwing(() -> {
                ((ReportDisplay) initiative.display).setRerollInitiativeEnabled(true);
                return null;
            });
            Map<String, String> standIns = new LinkedHashMap<>(reports);
            standIns.put("reportRerollInitiative", "dock-secondary");
            assertReachable(GamePhase.INITIATIVE_REPORT.toString(), initiative.board.source, standIns);
        }
    }

    /**
     * Shows the source's settled frame in a real HUD and checks each of the phase display's commands: the phase
     * display's Done and Skip are the dock's main and second buttons, any other command a button of the dock's row,
     * an entry of its More, or the control {@code standIns} names for it (by its actor's name), which must show.
     */
    private static void assertReachable(String phase, GpuBoardSource source, Map<String, String> standIns)
          throws Exception {
        GpuBoardSource.Frame frame = localTurn(settled(source));
        GpuHudTestStage.run(harness -> {
            SpriteBatch batch = new SpriteBatch();
            GpuHud hud = new GpuHud(source, harness.theme.skin, batch, new BoardCamera(),
                  new GpuBoardTuning(harness.theme.skin), new GpuPlaybackHistory(new UnitPlayback()));
            try {
                float density = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
                hud.resize(Math.round(harness.width() / density), Math.round(harness.height() / density),
                      1 / density);
                for (int pass = 0; pass < 2; pass++) {
                    hud.update(frame, GpuHud.HudView.EMPTY, null, source.uiPreferences);
                    hud.draw();
                }
                GpuCommandDock dock = field(hud, "dock");
                GpuCommandDock.More more = dock.more();
                // MegaMek's commands in More, and its own entries: a physical option beyond the row, for one.
                Set<String> inMore = more == null ? Set.of() : Stream.concat(more.commands().stream(),
                      more.items().stream()).map(BoardScene.Command::id).collect(Collectors.toSet());
                GpuBoardActions.PhaseInfo info = frame.panels().phase();
                List<String> missing = new ArrayList<>();
                for (BoardScene.Command command : frame.scene().commands()) {
                    String id = command.id();
                    String standIn = standIns.get(id);
                    boolean reachable = id.equals(info.doneId()) ? shown(hud, "dock-main")
                          : id.equals(info.skipId()) ? shown(hud, "dock-secondary") || shown(hud, "dock-main")
                          : shown(hud, "dock-command-" + id) || shown(hud, "dock-option-" + id)
                                || inMore.contains(id) || inMore.contains("dock.option." + id)
                                || CANCEL_KEY.equals(standIn) || WITH_A_CLUB.equals(standIn)
                                || standIn != null && shown(hud, standIn);
                    if (!reachable) {
                        missing.add(id + (standIn == null ? "" : " (" + standIn + ")"));
                    }
                }
                List<String> ids = frame.scene().commands().stream().map(BoardScene.Command::id).toList();
                assertFalse(ids.isEmpty(), phase + ": the phase display has commands");
                assertTrue(ids.containsAll(standIns.keySet()), phase + ": the stand-ins name its commands " + ids);
                assertEquals(List.of(), missing, phase + ": unreachable of " + ids + "; done " + info.doneId()
                      + ", skip " + info.skipId() + ", More " + inMore + ", the dock's controls "
                      + names(hud.stage.getRoot().findActor("command-dock")));
            } finally {
                hud.dispose();
                batch.dispose();
            }
        });
    }

    /** The source's frame once its capture saw the last change and the events it asked for ran. */
    private static GpuBoardSource.Frame settled(GpuBoardSource source) throws Exception {
        for (int pass = 0; pass < 3; pass++) {
            onSwing(() -> {
                source.refresh();
                return null;
            });
            for (int event = 0; event < 3; event++) {
                onSwing(() -> null);
            }
        }
        return onSwing(() -> {
            source.refresh();
            return source.takeFrame();
        });
    }

    /**
     * The frame as the local player's client presents it: the fixtures' board state has no client, so its capture
     * cannot tell that the turn is the local player's, which the fixtures' client reports.
     */
    private static GpuBoardSource.Frame localTurn(GpuBoardSource.Frame frame) {
        GpuBattleStatus.Snapshot status = frame.status();
        GpuBattleStatus.Snapshot mine = new GpuBattleStatus.Snapshot(status.round(), status.phase(), true,
              status.localPlayerId(), status.actorId(), status.turns(), status.turnIndex(), status.units(),
              status.initiative(), status.turnOrderHidden());
        return new GpuBoardSource.Frame(frame.scene(), frame.timeline(), frame.context(), frame.globalCommands(),
              frame.tooltip(), frame.centerRequest(), frame.boardGeneration(), frame.actorName(),
              frame.scenarioAtmosphere(), frame.reports(), mine, frame.panels());
    }

    /** The names of the shown actors at or below {@code actor}. */
    private static List<String> names(Actor actor) {
        List<String> names = new ArrayList<>();
        if (actor != null && actor.isVisible()) {
            if (actor.getName() != null) {
                names.add(actor.getName());
            }
            if (actor instanceof com.badlogic.gdx.scenes.scene2d.Group group) {
                group.getChildren().forEach(child -> names.addAll(names(child)));
            }
        }
        return names;
    }

    /** Whether the HUD shows the actor of that name. */
    private static boolean shown(GpuHud hud, String name) {
        return GpuBoardTestUi.shown(hud.stage.getRoot().findActor(name));
    }

    /**
     * The rows the Menu should show for {@code commands} under {@code parent}, by their keys (GpuCommandTree's
     * "/"-joined ids) with their labels: every item and group, and the rows of an enabled group; without the window
     * positions item, and the isometric item named after the Tactical View it switches.
     */
    private static void rows(List<BoardScene.Command> commands, String parent, Map<String, String> rows) {
        for (BoardScene.Command command : commands) {
            String action = action(command.id());
            if (action.equals(ClientGUI.VIEW_RESET_WINDOW_POSITIONS)) {
                continue;
            }
            String key = parent + "/" + command.id();
            rows.put(key, action.equals(ClientGUI.VIEW_TOGGLE_ISOMETRIC)
                  ? Messages.getString("GpuBoard.hud.util.tactical") : command.label());
            if (command.enabled()) {
                rows(command.children(), key, rows);
            }
        }
    }

    /** An item's action command: the last key of its id is "{action command}:{text}". */
    private static String action(String id) {
        String key = id.substring(id.lastIndexOf('/') + 1);
        int colon = key.indexOf(':');
        return colon < 0 ? "" : key.substring(0, colon);
    }

    /** The Menu's row key of {@code target}: the ids of its groups and its own, each after a "/". */
    private static String key(List<BoardScene.Command> commands, BoardScene.Command target) {
        for (BoardScene.Command command : commands) {
            if (command == target) {
                return "/" + command.id();
            }
            String child = key(command.children(), target);
            if (child != null) {
                return "/" + command.id() + child;
            }
        }
        return null;
    }

    /** An enabled group's row that is not open yet, or null. */
    private static UiButton closedGroup(Actor root, List<BoardScene.Command> commands, String parent) {
        for (BoardScene.Command command : commands) {
            String key = parent + "/" + command.id();
            if (command.children().isEmpty() || !command.enabled()) {
                continue;
            }
            UiButton row = ((Table) root).findActor(key);
            if (row != null && ((Table) root).findActor(key + "/" + command.children().getFirst().id()) == null) {
                return row;
            }
            UiButton nested = closedGroup(root, command.children(), key);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    /** A frame with the captured menu bar, as GpuBoardSource publishes it. */
    private static GpuBoardSource.Frame frame(List<BoardScene.Command> menuBar) {
        GpuHudData panels = new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY, GpuMovePlan.Snapshot.EMPTY,
              GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY,
              GpuFirePreview.Snapshot.NONE, GpuChat.Snapshot.EMPTY, GpuToasts.Snapshot.EMPTY,
              RulerModel.Snapshot.NONE, GpuPlayers.Snapshot.EMPTY);
        return new GpuBoardSource.Frame(null, List.of(), null, menuBar, "", null, 0, "", null,
              GpuReportLog.Snapshot.EMPTY, GpuBattleStatus.Snapshot.EMPTY, panels);
    }

    /** A client whose yes/no questions answer yes, as the physical scene's tests use it. */
    private static ClientGUI yesClient() {
        ClientGUI gui = mock(ClientGUI.class);
        when(gui.doYesNoDialog(anyString(), anyString())).thenReturn(true);
        return gui;
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(owner);
    }

    /**
     * A real phase display that {@code create} builds over the board fixture's game in {@code phase} on the local
     * player's turn, with the client and key dispatcher it asks for; the fixture's source shows it. Build and close it
     * on the test thread; the display runs on the EDT.
     */
    private static final class Display implements AutoCloseable {
        final GpuBoardFixture board;
        final JComponent display;
        private final MockedStatic<MegaMekGUI> keys;

        Display(GamePhase phase, Function<ClientGUI, JComponent> create) throws Exception {
            board = GpuBoardFixture.create();
            ClientGUI gui = GpuDialogFixture.routingClient();
            Client client = mock(Client.class);
            MegaMekController controller = mock(MegaMekController.class);
            CommonMenuBar menu = mock(CommonMenuBar.class);
            when(menu.getComponents()).thenReturn(new Component[0]);
            when(client.getGame()).thenReturn(board.game);
            when(client.getLocalPlayer()).thenReturn(board.player);
            when(client.isMyTurn()).thenReturn(true);
            when(client.getMyTurn()).thenReturn(new GameTurn(board.player.getId()));
            when(gui.getClient()).thenReturn(client);
            when(gui.getMenuBar()).thenReturn(menu);
            when(gui.getUnitDisplayState()).thenReturn(mock(UnitDisplayState.class));
            when(gui.boardStates()).thenReturn(List.of(board.view));
            when(gui.getBoardState()).thenReturn(board.view);
            when(gui.getBoardState(anyInt())).thenReturn(board.view);
            gui.controller = controller;
            // The key dispatcher mock is thread-local: the display registers its keys on the EDT.
            keys = onSwing(() -> mockStatic(MegaMekGUI.class, CALLS_REAL_METHODS));
            display = onSwing(() -> {
                keys.when(MegaMekGUI::getKeyDispatcher).thenReturn(controller);
                board.game.setPhase(phase);
                board.game.setTurnVector(List.of(new GameTurn(board.player.getId())));
                board.game.setTurnIndex(0, board.player.getId());
                return create.apply(gui);
            });
            board.panel = display;
        }

        @Override
        public void close() throws Exception {
            try {
                onSwing(() -> {
                    keys.close();
                    return null;
                });
            } finally {
                board.close();
            }
        }
    }
}
