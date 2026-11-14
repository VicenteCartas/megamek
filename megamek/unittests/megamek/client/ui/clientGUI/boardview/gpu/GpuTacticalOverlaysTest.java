/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side.OWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Files;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.EventListener;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.clientGUI.boardview.gpu.BoardSource.UiPreferences.Overlays;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The Tactical View's Overlays panel in the real HUD, without a GL context: the panel shows only in the Tactical View,
 * H and the header switch its remembered open state, and the layout places it at the top right, left of the right
 * column's top panel or at the right edge, as the header alone while a card beside the right column needs the room.
 */
class GpuTacticalOverlaysTest {
    /** The rows' View-menu items, by action command. */
    private static final List<String> VIEW_ITEMS = List.of(ClientGUI.VIEW_TOGGLE_UNIT_LINKS, ClientGUI.VIEW_MOVE_ENV,
          ClientGUI.VIEW_TOGGLE_FOV_DARKEN, ClientGUI.VIEW_TOGGLE_SENSOR_RANGE, ClientGUI.VIEW_TOGGLE_HEX_COORDS,
          ClientGUI.VIEW_TOGGLE_HEX_TYPES, ClientGUI.VIEW_TOGGLE_FOV_HIGHLIGHT);

    private Application application;
    private Files files;
    private Graphics graphics;
    private GL20 gl;
    private GL20 gl20;
    private Input input;
    private final GpuBoardSource source = mock(GpuBoardSource.class);
    private GpuBoardSkin theme;
    private GpuHud hud;

    @BeforeAll
    static void loadMathNatives() {
        GdxNativesLoader.load();
    }

    @BeforeEach
    void createHud() {
        application = Gdx.app;
        files = Gdx.files;
        graphics = Gdx.graphics;
        gl = Gdx.gl;
        gl20 = Gdx.gl20;
        input = Gdx.input;
        // As GpuHudInputTest: Scene2D and the HUD skin read these statics; nothing is drawn.
        Gdx.app = mock(Application.class);
        Gdx.files = mock(Files.class);
        when(Gdx.files.classpath(anyString())).thenAnswer(call -> new Lwjgl3Files().classpath(call.getArgument(0)));
        Gdx.graphics = mock(Graphics.class);
        Gdx.gl = mock(GL20.class);
        AtomicInteger textures = new AtomicInteger();
        when(Gdx.gl.glGenTexture()).thenAnswer(call -> textures.incrementAndGet());
        Gdx.gl20 = Gdx.gl;
        Gdx.input = mock(Input.class);
        when(source.record()).thenReturn(mock(GpuUnitRecord.class));
        theme = new GpuBoardSkin();
        hud = new GpuHud(source, theme.skin, mock(Batch.class), new BoardCamera(), mock(GpuBoardTuning.class),
              new GpuPlaybackHistory(new UnitPlayback()));
    }

    @AfterEach
    void restoreGdx() {
        try {
            hud.dispose();
            theme.dispose();
        } finally {
            Gdx.app = application;
            Gdx.files = files;
            Gdx.graphics = graphics;
            Gdx.gl = gl;
            Gdx.gl20 = gl20;
            Gdx.input = input;
        }
    }

    @Test
    void thePanelShowsOnlyInTheTacticalViewAndHAndItsHeaderSwitchItsOpenState() {
        show(1920, 1080, false, GamePhase.MOVEMENT, Overlays.DEFAULTS, true);
        assertFalse(shown(panel()), "the 3D view has no Overlays panel");
        assertFalse(hud.keyDown(Input.Keys.H, KeyEvent.VK_H, 0), "outside the Tactical View H goes on to MegaMek");
        verify(source, never()).command(any());

        show(1920, 1080, true, GamePhase.MOVEMENT, Overlays.DEFAULTS, true);
        assertTrue(shown(panel()));
        assertTrue(expanded("overlay-envelope"), "expanded as saved");
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean open = preferences.getTacticalOverlaysOpen();
        try {
            assertTrue(hud.keyDown(Input.Keys.H, KeyEvent.VK_H, 0), "H is the HUD's in the Tactical View");
            hud.keyUp(Input.Keys.H, KeyEvent.VK_H);
            ArgumentCaptor<Runnable> command = ArgumentCaptor.forClass(Runnable.class);
            verify(source).command(command.capture());
            command.getValue().run();
            assertEquals(!open, preferences.getTacticalOverlaysOpen(), "H flips the remembered open state");
            clearInvocations(source);
            Actor header = hud.stage.getRoot().findActor("overlays-header");
            for (EventListener listener : header.getListeners()) {
                if (listener instanceof ClickListener click) { click.clicked(new InputEvent(), 0, 0); }
            }
            verify(source).command(command.capture());
            command.getValue().run();
            assertEquals(open, preferences.getTacticalOverlaysOpen(), "so does a click on the header");
        } finally {
            if (preferences.getTacticalOverlaysOpen() != open) { preferences.toggleTacticalOverlaysOpen(); }
        }
        // Collapsed: the header only, in the same place and width; the right column keeps its places.
        Rectangle expanded = bounds(panel());
        float contactsTop = top(find("contacts-panel"));
        show(1920, 1080, true, GamePhase.MOVEMENT,
              new Overlays(false, true, true, true, true, false, true, false, true), true);
        assertTrue(shown(panel()));
        assertFalse(expanded("overlay-envelope"), "collapsed, the header only");
        Rectangle collapsed = bounds(panel());
        assertTrue(collapsed.height < 60, "the header only: " + collapsed);
        assertEquals(expanded.y + expanded.height, top(panel()), .5f, "the same top");
        assertEquals(expanded.x, collapsed.x, .5f, "the same place");
        assertEquals(expanded.width, collapsed.width, .5f, "the same width");
        assertEquals(contactsTop, top(find("contacts-panel")), .5f, "the contacts stay");

        hud.state.overview = true;
        show(1920, 1080, true, GamePhase.MOVEMENT, Overlays.DEFAULTS, true);
        assertFalse(shown(panel()), "the forces overview covers the right column");
        hud.state.overview = false;

        List<String> hint = GpuHintLine.items(new GpuHud.Inputs(frame(GamePhase.MOVEMENT, viewMenu()),
              new GpuHud.HudView(true, false, Map.of(), Map.of(), Map.of(), null, Entity.NONE, 0), null,
              preferences(Overlays.DEFAULTS, true), GpuHud.Metrics.of(1920, 1080), List.of()));
        int key = hint.indexOf("H");
        assertTrue(key >= 0 && hint.get(key + 1).equals("Overlays"), "the hint line names H Overlays: " + hint);
    }

    /**
     * The user's placement at 1920 x 1080 and 1280 x 800: one column at the top right, left of the minimap at its top;
     * with the minimap closed, left of the contacts, which move up into its place; with no panel on the right, at the
     * window's right edge. The right column keeps its places, the panel covers no other panel and stays in the board's
     * right half, also beside the weapons panel of the firing phase. Also at 960 x 640, the smallest stage
     * (DisplayScale), where the expanded column shares the middle band with the dock.
     */
    @Test
    void thePanelStandsLeftOfTheRightColumnsTopOrAtTheRightEdge() {
        for (int[] size : new int[][] { { 1920, 1080 }, { 1280, 800 }, { 960, 640 } }) {
            float gap = GpuHud.Metrics.of(size[0], size[1]).gap();
            String at = size[0] + "x" + size[1];
            show(size[0], size[1], true, GamePhase.MOVEMENT, Overlays.DEFAULTS, false, true);
            Rectangle utilities = bounds(find("utility-bar"));
            float rightStart = utilities.y - gap;
            float contactsAlone = top(find("contacts-panel"));
            assertEquals(rightStart, contactsAlone, .5f, "the contacts in the minimap's place at " + at);

            show(size[0], size[1], true, GamePhase.MOVEMENT, Overlays.DEFAULTS, true, true);
            Rectangle minimap = bounds(find("minimap"));
            Rectangle panel = bounds(panel());
            assertTrue(expanded("overlay-distanceRings"), "expanded at " + at);
            assertEquals(top(find("minimap")), top(panel()), .5f, "level with the minimap's top at " + at);
            assertEquals(minimap.x - gap, panel.x + panel.width, .5f, "left of the minimap at " + at);
            assertTrue(panel.height > 350 && panel.height < 405, "one column of twelve rows at " + at + ": " + panel);
            assertTrue(panel.width > 110 && panel.width < 260, "a narrow column at " + at + ": " + panel);
            assertTrue(top(find("contacts-panel")) < top(find("minimap")), "the contacts stay under the minimap");
            assertAlone(size[0], at);

            show(size[0], size[1], true, GamePhase.MOVEMENT, Overlays.DEFAULTS, false, true);
            Rectangle contacts = bounds(find("contacts-panel"));
            assertEquals(contactsAlone, top(find("contacts-panel")), .5f, "the contacts move up at " + at);
            assertEquals(top(find("contacts-panel")), top(panel()), .5f, "level with the contacts' top at " + at);
            assertEquals(contacts.x - gap, bounds(panel()).x + bounds(panel()).width, .5f, "beside them at " + at);
            assertAlone(size[0], at);

            show(size[0], size[1], true, GamePhase.MOVEMENT, Overlays.DEFAULTS, false, false);
            assertEquals(size[0] - gap, bounds(panel()).x + bounds(panel()).width, .5f, "at the right edge at " + at);
            assertEquals(rightStart, top(panel()), .5f, "under the utilities at " + at);
            assertEquals(panel.width, bounds(panel()).width, .5f, "the same width at " + at);
            assertAlone(size[0], at);

            // The firing phase: the weapons panel in the contacts' place, the panel beside the minimap, expanded.
            show(size[0], size[1], true, GamePhase.FIRING, Overlays.DEFAULTS, true, true);
            assertTrue(shown(find("weapons-panel")));
            assertTrue(expanded("overlay-distanceRings"), "expanded beside the weapons at " + at);
            assertEquals(top(find("minimap")), top(panel()), .5f, at);
            assertAlone(size[0], at);
        }
    }

    /**
     * A card beside the right column needs the panel's room: the initiative card, centred between the columns of the
     * 1280-wide window, shows the panel as its header for the frame and stacks below it; the saved state stays. At
     * 1920 the card leaves the panel room.
     */
    @Test
    void theInitiativeCardCollapsesThePanelForTheFrameWhereItNeedsTheRoom() {
        show(1280, 800, true, GamePhase.INITIATIVE_REPORT, Overlays.DEFAULTS, true, true);
        Actor initiative = find("initiative-card");
        assertTrue(shown(initiative));
        assertFalse(expanded("overlay-envelope"), "the header only while the initiative card shows");
        assertTrue(bounds(panel()).height < 60, "the header: " + bounds(panel()));
        assertTrue(top(initiative) <= bounds(panel()).y - 12 + .5f, "the card below the header");
        assertTrue(GUIPreferences.getInstance().getDefaultBoolean(GUIPreferences.TACTICAL_OVERLAYS_OPEN));
        assertAlone(1280, "1280x800 initiative");

        show(1920, 1080, true, GamePhase.INITIATIVE_REPORT, Overlays.DEFAULTS, true, true);
        assertTrue(shown(find("initiative-card")));
        assertTrue(expanded("overlay-envelope"), "room for both at 1920");
        assertAlone(1920, "1920x1080 initiative");
    }

    /** The panel overlaps no other shown panel and stays in the right half of the window. */
    private void assertAlone(int width, String at) {
        Rectangle panel = bounds(panel());
        assertTrue(panel.x > width / 2f, "in the right half at " + at + ": " + panel);
        for (Rectangle other : hud.panelBounds()) {
            boolean self = other.x == panel.x && other.y == panel.y && other.width == panel.width;
            assertTrue(self || !other.overlaps(panel), "the panel " + panel + " covers " + other + " at " + at);
        }
    }

    private void show(int width, int height, boolean tactical, GamePhase phase, Overlays overlays, boolean minimap) {
        show(width, height, tactical, phase, overlays, minimap, true);
    }

    private void show(int width, int height, boolean tactical, GamePhase phase, Overlays overlays, boolean minimap,
          boolean contacts) {
        update(width, height, tactical, phase, preferences(overlays, minimap, contacts), viewMenu());
    }

    private void update(int width, int height, boolean tactical, GamePhase phase,
          GpuBoardSource.UiPreferences preferences, List<BoardScene.Command> global) {
        hud.resize(width, height, 1);
        GpuHud.HudView view = new GpuHud.HudView(tactical, false, Map.of(), Map.of(), Map.of(), null, Entity.NONE,
              0);
        hud.update(frame(phase, global), view, null, preferences);
        hud.update(frame(phase, global), view, null, preferences);
    }

    /** The View menu with the rows' items, every one selected but the distance rings. */
    private static List<BoardScene.Command> viewMenu() {
        List<BoardScene.Command> items = new ArrayList<>();
        for (String item : VIEW_ITEMS) {
            items.add(new BoardScene.Command("View:View/" + item + ":" + item, item, "", true, false, false,
                  List.of(), () -> { }, "", !item.equals(ClientGUI.VIEW_TOGGLE_FOV_HIGHLIGHT)));
        }
        return List.of(new BoardScene.Command("View:View", "View", "", true, false, false, items, () -> { }, "",
              null));
    }

    /**
     * The local turn of an own unit; in FIRING the fire orders are active, so the weapons panel shows; else the move's
     * fire preview is, so the contacts panel shows.
     */
    private static GpuBoardSource.Frame frame(GamePhase phase, List<BoardScene.Command> global) {
        GpuFireOrders.Snapshot fire = !phase.isFiring() ? GpuFireOrders.Snapshot.EMPTY
              : new GpuFireOrders.Snapshot(true, true, 1, GpuFireOrders.Focus.NONE, -1, List.of(), List.of(),
                    List.of(), 0, false, false, "", null, null, null, List.of(), null, Map.of(), 0, 0, null);
        GpuFirePreview.Snapshot preview = phase.isFiring() ? GpuFirePreview.Snapshot.NONE
              : new GpuFirePreview.Snapshot(true, true, 1, false, new Coords(5, 5), 0, 0, "None", 0, 0, "", false, 0,
                    0, List.of());
        GpuBattleStatus.Snapshot status = GpuHudFixtures.status(3, phase, true, 1, 0,
              GpuHudFixtures.unit(1, OWN, true, true));
        return new GpuBoardSource.Frame(mock(BoardScene.class), List.of(), null, global, "", null, 0, "", null,
              GpuReportLog.Snapshot.EMPTY, status, new GpuHudData(GpuBoardActions.PhaseInfo.EMPTY,
                    GpuMovePlan.Snapshot.EMPTY, fire, GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY,
                    preview, GpuChat.Snapshot.EMPTY, GpuToasts.Snapshot.EMPTY, RulerModel.Snapshot.NONE,
                    GpuPlayers.Snapshot.EMPTY));
    }

    /** MegaMek's default keys, H shown as "H", with the given overlays and minimap, and the contacts panel. */
    private static GpuBoardSource.UiPreferences preferences(Overlays overlays, boolean minimap) {
        return preferences(overlays, minimap, true);
    }

    private static GpuBoardSource.UiPreferences preferences(Overlays overlays, boolean minimap, boolean contacts) {
        List<GpuBoardSource.Bind> binds = Stream.of(KeyCommandBind.values())
              .map(bind -> new GpuBoardSource.Bind(bind, bind.keyDefault, bind.modifiersDefault,
                    bind == KeyCommandBind.TACTICAL_OVERLAYS ? "H" : "")).toList();
        return GpuHudFixtures.with(GpuHudFixtures.preferences(),
              Map.of("minimapEnabled", minimap, "contactsEnabled", contacts, "binds", binds, "overlays", overlays));
    }

    private Actor find(String name) {
        Actor actor = hud.stage.getRoot().findActor(name);
        assertNotNull(actor, name);
        return actor;
    }

    private Actor panel() {
        return find("tactical-overlays");
    }

    /** Whether the panel shows the row: a collapsed panel leaves its rows out. */
    private boolean expanded(String name) {
        Actor row = hud.stage.getRoot().findActor(name);
        return row != null && shown(row);
    }

    /** The actor's stage bounds, y up. */
    private static Rectangle bounds(Actor actor) {
        Vector2 corner = actor.localToStageCoordinates(new Vector2());
        return new Rectangle(corner.x, corner.y, actor.getWidth(), actor.getHeight());
    }

    private static float top(Actor actor) {
        Rectangle area = bounds(actor);
        return area.y + area.height;
    }

    /** On the stage with every group above it visible. */
    private static boolean shown(Actor actor) {
        for (Actor current = actor; current != null; current = current.getParent()) {
            if (!current.isVisible()) {
                return false;
            }
        }
        return actor.getStage() != null;
    }
}
