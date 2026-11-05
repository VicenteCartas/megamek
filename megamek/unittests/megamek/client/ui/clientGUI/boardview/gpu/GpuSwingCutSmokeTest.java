/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.BorderLayout;
import java.awt.Window;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import megamek.client.Client;
import megamek.client.ui.clientGUI.AbstractClientGUI;
import megamek.client.ui.clientGUI.BoardViewsContainer;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommandBarPanel;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.RulerDialog;
import megamek.client.ui.clientGUI.unitDisplay.UnitDisplayState;
import megamek.client.ui.dialogs.BotCommands.BotCommandsDialog;
import megamek.client.ui.dialogs.BotCommands.BotCommandsPanel;
import megamek.client.ui.dialogs.PlayerListDialog;
import megamek.client.ui.dialogs.RoundsInAirDialog;
import megamek.client.ui.dialogs.forceDisplay.ForceDisplayDialog;
import megamek.client.ui.dialogs.miniReport.MiniReportDisplayDialog;
import megamek.client.ui.dialogs.miniReport.MiniReportDisplayPanel;
import megamek.client.ui.dialogs.minimap.MinimapDialog;
import megamek.client.ui.dialogs.unitDisplay.UnitDisplayDialog;
import megamek.client.ui.dialogs.unitDisplay.UnitDisplayPanel;
import megamek.common.Player;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Swing cut (I3) in a scripted battle over the real native window. Through every phase, with each auxiliary
 * window's preference switched on and the client's phase rules run, no Swing window of the client shows over the
 * battle window: the HUD stands for the minimap, the players, the rounds in the air, the force display, the bot
 * commands, the Unit Display and the report. A Ctrl measurement shows MegaMek's ruler, which line of sight stays.
 * A Swing dialog outside the allowlist is not raised. With the default
 * preferences the minimap keeps the player's choice through the report phases. Back on the Legacy 2D board every window
 * shows again as its preference says.
 */
@Tag("on-demand")
class GpuSwingCutSmokeTest {
    /** The phases of a round, in order, as the scripted battle plays them. */
    private static final List<GamePhase> ROUND = List.of(GamePhase.INITIATIVE_REPORT, GamePhase.DEPLOYMENT,
          GamePhase.MOVEMENT, GamePhase.MOVEMENT_REPORT, GamePhase.FIRING, GamePhase.FIRING_REPORT,
          GamePhase.PHYSICAL, GamePhase.PHYSICAL_REPORT, GamePhase.END_REPORT);
    /** The preferences that show the client's auxiliary windows, each switched on in every phase. */
    private static final List<String> WINDOWS = List.of(GUIPreferences.MINI_MAP_ENABLED,
          GUIPreferences.PLAYER_LIST_ENABLED, GUIPreferences.ROUNDS_IN_AIR_ENABLED,
          GUIPreferences.FORCE_DISPLAY_ENABLED, GUIPreferences.UNIT_DISPLAY_ENABLED,
          GUIPreferences.MINI_REPORT_ENABLED, GUIPreferences.BOT_COMMANDS_ENABLED);
    /** The phase rules' auto-display choices, set to show their windows in every phase. */
    private static final List<String> SHOWN = List.of(GUIPreferences.MINI_MAP_AUTO_DISPLAY_REPORT_PHASE,
          GUIPreferences.MINI_MAP_AUTO_DISPLAY_NON_REPORT_PHASE,
          GUIPreferences.PLAYER_LIST_AUTO_DISPLAY_REPORT_PHASE,
          GUIPreferences.PLAYER_LIST_AUTO_DISPLAY_NON_REPORT_PHASE,
          GUIPreferences.FORCE_DISPLAY_AUTO_DISPLAY_REPORT_PHASE,
          GUIPreferences.FORCE_DISPLAY_AUTO_DISPLAY_NON_REPORT_PHASE,
          GUIPreferences.UNIT_DISPLAY_AUTO_DISPLAY_REPORT_PHASE,
          GUIPreferences.UNIT_DISPLAY_AUTO_DISPLAY_NON_REPORT_PHASE,
          GUIPreferences.MINI_REPORT_AUTO_DISPLAY_REPORT_PHASE,
          GUIPreferences.MINI_REPORT_AUTO_DISPLAY_NON_REPORT_PHASE,
          GUIPreferences.BOT_COMMANDS_AUTO_DISPLAY_REPORT_PHASE,
          GUIPreferences.BOT_COMMANDS_AUTO_DISPLAY_NON_REPORT_PHASE);

    /** The client's windows, real Swing dialogs owned by its Swing frame. */
    private record Client2d(JFrame frame, CommonMenuBar menus, MinimapDialog minimap, PlayerListDialog players,
          RoundsInAirDialog rounds, ForceDisplayDialog forces, BotCommandsDialog bots, UnitDisplayDialog unit,
          MiniReportDisplayDialog report, RulerDialog ruler) {
        List<Window> windows() {
            return List.of(minimap, players, rounds, forces, bots, unit, report, ruler);
        }
    }

    @AfterEach
    void awaitHostClosed() throws Exception {
        // The next suite's own window must not start while the host's thread still shuts GLFW down
        await(() -> !GpuHost.isOpen());
    }

    @Test
    void aScriptedBattleShowsNoSwingWindowOverTheNativeWindowAndTheLegacy2DBoardGetsThemBack() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        Map<String, String> saved = new LinkedHashMap<>();
        for (String key : Stream.of(WINDOWS, SHOWN, List.of(GUIPreferences.BOT_COMMANDS_LOCATION)).flatMap(List::stream).toList()) {
            saved.put(key, preferences.getString(key));
        }
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            ClientGUI gui = onSwing(() -> client(fixture));
            Client2d client = onSwing(() -> windows(gui, fixture));
            Application previous = Gdx.app;
            try {
                onSwing(() -> {
                    preferences.setBotCommandsLocation(ClientGUI.BOT_COMMANDS_LOCATION_FLOATING);
                    preferences.addPreferenceChangeListener(gui);
                    GpuBoardWindow.open(gui, () -> fixture.panel);
                    return null;
                });
                await(() -> Gdx.app != null && Gdx.app != previous);
                await(() -> onSwing(() -> GpuBoardWindow.drawsDialogsFor(gui)));
                await(() -> onGl(() -> frames() > 5));

                // Default preferences: the minimap stays on through the report phases (the Legacy 2D board's phase rule
                // would hide it there).
                onSwing(() -> {
                    preferences.setValue(GUIPreferences.MINI_MAP_AUTO_DISPLAY_REPORT_PHASE, GUIPreferences.HIDE);
                    preferences.setValue(GUIPreferences.MINI_MAP_AUTO_DISPLAY_NON_REPORT_PHASE, GUIPreferences.SHOW);
                    preferences.setMinimapEnabled(true);
                    return null;
                });
                for (GamePhase phase : List.of(GamePhase.INITIATIVE_REPORT, GamePhase.FIRING_REPORT,
                      GamePhase.END_REPORT)) {
                    enter(fixture, client, gui, phase);
                    assertTrue(preferences.getMinimapEnabled(), phase + ": the player's minimap choice is kept");
                    assertTrue(onGl(() -> GpuBoardTestUi.shown(GpuBoardTestUi.stage().getRoot().findActor("minimap"))),
                          phase + ": the HUD's minimap shows");
                }

                // Every window wanted in every phase, and a measurement: only the native window and the ruler.
                onSwing(() -> {
                    SHOWN.forEach(key -> preferences.setValue(key, GUIPreferences.SHOW));
                    return null;
                });
                Coords from = fixture.entity.getPosition();
                Coords to = new Coords(from.getX(), from.getY() - 3);
                for (GamePhase phase : ROUND) {
                    enter(fixture, client, gui, phase);
                    onSwing(() -> {
                        for (String key : WINDOWS) {
                            preferences.setValue(key, false);
                            preferences.setValue(key, true);
                        }
                        fixture.view.checkLOS(from);
                        fixture.view.checkLOS(to);
                        return null;
                    });
                    settle();
                    assertEquals(List.of(client.ruler()), shown(client),
                          phase + ": no Swing window over the native window but the measurement's ruler");
                    if (phase == GamePhase.MOVEMENT) {
                        onGl(() -> {
                            File directory = new File(System.getProperty("megamek.gpu.screenshots",
                                  "build/gpu-board-review"));
                            assertTrue(directory.isDirectory() || directory.mkdirs());
                            GpuBoardTestUi.capture(new File(directory, "swing-cut-movement.png"));
                            return null;
                        });
                    }
                }

                // A dialog outside the allowlist is logged at error level and not raised; it still opens.
                JDialog probe = onSwing(() -> {
                    JDialog dialog = new JDialog(client.frame(), "Swing cut probe", false);
                    dialog.setSize(160, 90);
                    dialog.setVisible(true);
                    return dialog;
                });
                settle();
                assertTrue(onSwing(probe::isShowing));
                assertFalse(onSwing(probe::isAlwaysOnTop), "A dialog outside the allowlist is not raised");
                onSwing(() -> {
                    probe.dispose();
                    return null;
                });

                // Back on the Legacy 2D board each window shows as its preference says.
                onSwing(() -> {
                    GpuBoardWindow.showLegacy2D(gui);
                    return null;
                });
                await(() -> onSwing(() -> client.frame().isVisible() && !GpuBoardWindow.isActiveFor(gui)));
                for (Window window : List.of(client.minimap(), client.players(), client.rounds(), client.forces(),
                      client.bots(), client.unit(), client.report())) {
                    assertTrue(onSwing(window::isShowing), window.getClass().getSimpleName() + " shows again");
                }
            } finally {
                onSwing(() -> {
                    preferences.removePreferenceChangeListener(gui);
                    GpuBoardWindow.closeFor(gui);
                    return null;
                });
                await(() -> !GpuHost.isOpen());
                onSwing(() -> {
                    client.windows().forEach(Window::dispose);
                    client.frame().dispose();
                    client.menus().die();
                    saved.forEach(preferences::setValue);
                    return null;
                });
            }
        }
    }

    /**
     * EDT: the phase begins as on the client: its phase display's rules and the auxiliary windows' presentation run
     * (ClientGUI.switchPanel and refreshAuxiliaryWindows); then the native window draws it.
     */
    private static void enter(GpuBoardFixture fixture, Client2d client, ClientGUI gui, GamePhase phase)
          throws Exception {
        onSwing(() -> {
            fixture.game.setPhase(phase);
            client.menus().setPhase(phase);
            gui.maybeShowUnitDisplay();
            gui.maybeShowForceDisplay();
            gui.refreshAuxiliaryWindows();
            return null;
        });
        settle();
    }

    /** The client's windows that show, its Swing frame included. */
    private static List<Window> shown(Client2d client) throws Exception {
        Set<Window> own = Stream.concat(client.windows().stream(), Stream.of(client.frame()))
              .collect(Collectors.toSet());
        return onSwing(() -> Arrays.stream(Window.getWindows()).filter(Window::isShowing).filter(own::contains)
              .toList());
    }

    /**
     * A client of the fixture's game whose window presentation is ClientGUI's own: its auxiliary windows' setters,
     * phase rules and preference handling, and the ruler's close. Its board state is a real one over the fixture's
     * board.
     */
    private static ClientGUI client(GpuBoardFixture fixture) throws Exception {
        fixture.source.close();
        Set<String> real = Set.of("refreshAuxiliaryWindows", "setMapVisible", "setPlayerListVisible", "showPlayerList",
              "setRoundsInAirVisible", "setForceDisplayVisible", "setBotCommandsLocation", "setUnitDisplayVisible",
              "maybeShowUnitDisplay", "maybeShowForceDisplay", "preferenceChange", "closeRuler");
        ClientGUI gui = mock(ClientGUI.class, invocation -> real.contains(invocation.getMethod().getName())
              ? invocation.callRealMethod() : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation));
        Client client = mock(Client.class);
        when(client.getGame()).thenReturn(fixture.game);
        when(client.getLocalPlayer()).thenReturn(fixture.player);
        when(gui.getClient()).thenReturn(client);
        when(gui.getMainPanel()).thenReturn(new JPanel());
        BoardViewsContainer container = mock(BoardViewsContainer.class);
        when(container.isLegacy2DEnabled()).thenAnswer(invocation -> !GpuBoardWindow.isActiveFor(gui));
        set(AbstractClientGUI.class, gui, "boardViewsContainer", container);
        fixture.view.close();
        fixture.view = new BoardClientState(fixture.game, null, gui, 0, null);
        fixture.view.setLocalPlayer(fixture.player);
        doReturn(Optional.of(fixture.view)).when(gui).getCurrentBoardState();
        doReturn(List.of(fixture.view)).when(gui).boardStates();
        doReturn(fixture.view).when(gui).getBoardState();
        doReturn(fixture.view).when(gui).getBoardState(any(BoardLocation.class));
        doReturn(fixture.view).when(gui).getBoardState(any(Entity.class));
        Player bot = new Player(77, "Bot");
        bot.setBot(true);
        fixture.game.addPlayer(bot.getId(), bot);
        return gui;
    }

    /**
     * The client's real windows over its Swing frame, as ClientGUI builds them; the Unit Display and the report
     * observe a Legacy 2D board's request on their dialogs (this client has no Swing split panes).
     */
    private static Client2d windows(ClientGUI gui, GpuBoardFixture fixture) {
        JFrame frame = new JFrame("MegaMek - Swing cut test");
        frame.setSize(960, 700);
        CommonMenuBar menus = CommonMenuBar.getMenuBarForGame();
        frame.setJMenuBar(menus);
        when(gui.getFrame()).thenReturn(frame);
        when(gui.getMenuBar()).thenReturn(menus);
        MinimapDialog minimap = new MinimapDialog(frame);
        minimap.add(new JPanel());
        minimap.setSize(240, 200);
        set(AbstractClientGUI.class, gui, "miniMaps", new HashMap<>(Map.of(0, minimap)));
        when(gui.getMiniMapDialog()).thenReturn(minimap);
        PlayerListDialog players = new PlayerListDialog(frame, gui.getClient(), false);
        when(gui.getPlayerListDialog()).thenReturn(players);
        RoundsInAirDialog rounds = new RoundsInAirDialog(frame, gui.getClient());
        when(gui.getRoundsInAirDialog()).thenReturn(rounds);
        ForceDisplayDialog forces = new ForceDisplayDialog(frame, gui);
        when(gui.getForceDisplayDialog()).thenReturn(forces);
        BotCommandsDialog bots = new BotCommandsDialog(frame, gui);
        when(gui.getBotCommandsDialog()).thenReturn(bots);
        CommandBarPanel bar = new CommandBarPanel(gui);
        JPanel top = new JPanel(new BorderLayout());
        top.add(bar, BorderLayout.NORTH);
        set(ClientGUI.class, gui, "botCommandsPanel", new BotCommandsPanel(gui.getClient(), null, null, gui));
        set(ClientGUI.class, gui, "commandBarPanel", bar);
        set(ClientGUI.class, gui, "panTop", top);
        when(gui.getUnitDisplayState()).thenReturn(new UnitDisplayState(gui));
        UnitDisplayPanel unitPanel = new UnitDisplayPanel(gui);
        UnitDisplayDialog unit = new UnitDisplayDialog(frame, gui);
        when(gui.getUnitDisplay()).thenReturn(unitPanel);
        when(gui.getUnitDisplayDialog()).thenReturn(unit);
        unitPanel.displayEntity(fixture.entity);
        MiniReportDisplayDialog report = new MiniReportDisplayDialog(frame, gui);
        when(gui.getMiniReportDisplayDialog()).thenReturn(report);
        when(gui.getMiniReportDisplay()).thenReturn(mock(MiniReportDisplayPanel.class));
        doAnswer(invocation -> legacy2DOrReal(gui, invocation, unit)).when(gui).setUnitDisplayLocation(anyBoolean());
        doAnswer(invocation -> legacy2DOrReal(gui, invocation, report)).when(gui).setMiniReportLocation(anyBoolean());
        RulerDialog ruler = new RulerDialog(frame, fixture.view, fixture.game);
        set(ClientGUI.class, gui, "rulers", new HashMap<>(Map.of(0, ruler)));
        frame.setVisible(true);
        return new Client2d(frame, menus, minimap, players, rounds, forces, bots, unit, report, ruler);
    }

    /** ClientGUI's own placement over the native window; on the Legacy 2D board the request shows on the dialog. */
    private static Object legacy2DOrReal(ClientGUI gui, org.mockito.invocation.InvocationOnMock invocation,
          JDialog dialog) throws Throwable {
        if (GpuBoardWindow.isActiveFor(gui)) {
            return invocation.callRealMethod();
        }
        dialog.setVisible(invocation.getArgument(0));
        return null;
    }

    private static void set(Class<?> owner, Object instance, String name, Object value) {
        try {
            var field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(instance, value);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    /** Lets the EDT and the native window catch up: posted Swing events, then a few frames. */
    private static void settle() throws Exception {
        onSwing(() -> null);
        long before = onGl(() -> frames());
        await(() -> onGl(() -> frames() > before + 3));
        onSwing(() -> null);
    }

    /** GL thread: the shown board's frame count, or -1 until the host has started the board's scene. */
    private static long frames() {
        return GpuHost.sceneListener() instanceof GpuBattleView battle ? battle.frames() : -1;
    }

    private static <T> T onGl(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Gdx.app.postRunnable(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (!condition.call()) {
            assertTrue(System.nanoTime() < deadline, "Timed out waiting for the native window");
            Thread.sleep(25);
        }
    }
}
