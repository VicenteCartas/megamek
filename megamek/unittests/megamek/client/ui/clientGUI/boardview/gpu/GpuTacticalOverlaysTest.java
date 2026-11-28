/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.Side.OWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

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
        Gdx.graphics = mock(Graphics.class, withSettings().stubOnly());
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

    /**
     * The Overlays popover (11-16) in either view: H and the utility bar's Overlays button open and close it, Esc closes
     * it first; a Tactical-only setting greys out in the 3D view, a View row does not. Its open state is the HUD's own,
     * so nothing reaches the client's settings; the hint line names H Overlays.
     */
    @Test
    void theOverlaysPopoverOpensFromItsButtonAndHInEitherView() {
        for (boolean tactical : new boolean[] { false, true }) {
            String view = tactical ? "the Tactical View" : "the 3D view";
            show(1920, 1080, tactical, GamePhase.MOVEMENT, Overlays.DEFAULTS, true);
            assertFalse(shown(panel()), view + ": closed until asked for");
            assertTrue(hud.keyDown(Input.Keys.H, KeyEvent.VK_H, 0), view + ": H is the HUD's");
            hud.keyUp(Input.Keys.H, KeyEvent.VK_H);
            show(1920, 1080, tactical, GamePhase.MOVEMENT, Overlays.DEFAULTS, true);
            assertTrue(shown(panel()), view + ": H opens the popover");
            assertTrue(expanded("overlay-envelope"), view + ": with its rows");
            assertEquals(tactical ? 1 : .45f, find("overlay-statusBadges").getColor().a, .01f,
                  view + ": status badges, a Tactical-only setting");
            assertEquals(1, find("overlay-envelope").getColor().a, .01f, view + ": the envelope, a View row");
            assertTrue(hud.keyDown(Input.Keys.H, KeyEvent.VK_H, 0));
            hud.keyUp(Input.Keys.H, KeyEvent.VK_H);
            show(1920, 1080, tactical, GamePhase.MOVEMENT, Overlays.DEFAULTS, true);
            assertFalse(shown(panel()), view + ": H closes it");
            click(find("utility-overlays"));
            show(1920, 1080, tactical, GamePhase.MOVEMENT, Overlays.DEFAULTS, true);
            assertTrue(shown(panel()), view + ": so does its button");
            assertTrue(hud.keyDown(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE, 0), view + ": Esc is the HUD's");
            hud.keyUp(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE);
            show(1920, 1080, tactical, GamePhase.MOVEMENT, Overlays.DEFAULTS, true);
            assertFalse(shown(panel()), view + ": Esc closes it first");
        }
        verify(source, never()).command(any());

        List<String> hint = GpuHintLine.items(new GpuHud.Inputs(frame(GamePhase.MOVEMENT, viewMenu()),
              new GpuHud.HudView(true, false, Map.of(), Map.of(), Map.of(), null, Entity.NONE, 0), null,
              preferences(Overlays.DEFAULTS, true), GpuHud.Metrics.of(1920, 1080), List.of()));
        int key = hint.indexOf("H");
        assertTrue(key >= 0 && hint.get(key + 1).equals("Overlays"), "the hint line names H Overlays: " + hint);
    }

    /**
     * At 1920 x 1080, 1280 x 800 and 960 x 640 (the smallest stage, DisplayScale), with the minimap and the contacts on
     * or off and beside the firing phase's weapons panel: the open popover hangs one gap below its button, its right
     * edge at the button's or further left, inside the window, with all thirteen rows; it lies over the panels, so the
     * right column keeps its places.
     */
    @Test
    void theOverlaysPopoverHangsBelowItsButtonAndTheRightColumnKeepsItsPlaces() {
        List<String> rows = List.of("statusBadges", "links", "botOrders", "envelope", "rearArcs", "losShading",
              "sensorRanges", "fields", "jamming", "coordinates", "types", "levelShading", "distanceRings");
        for (int[] size : new int[][] { { 1920, 1080 }, { 1280, 800 }, { 960, 640 } }) {
            for (Object[] layout : new Object[][] { { GamePhase.MOVEMENT, true, true }, { GamePhase.MOVEMENT, false, true },
                  { GamePhase.MOVEMENT, false, false }, { GamePhase.FIRING, true, true } }) {
                GamePhase phase = (GamePhase) layout[0];
                boolean minimap = (Boolean) layout[1];
                boolean contacts = (Boolean) layout[2];
                String at = size[0] + "x" + size[1] + " " + phase + (minimap ? " minimap" : "")
                      + (contacts ? " contacts" : "");
                show(size[0], size[1], true, phase, Overlays.DEFAULTS, minimap, contacts);
                String column = phase.isFiring() ? "weapons-panel" : contacts ? "contacts-panel" : null;
                Rectangle before = column == null ? null : bounds(find(column));
                assertTrue(hud.keyDown(Input.Keys.H, KeyEvent.VK_H, 0));
                hud.keyUp(Input.Keys.H, KeyEvent.VK_H);
                show(size[0], size[1], true, phase, Overlays.DEFAULTS, minimap, contacts);
                assertTrue(shown(panel()), "open at " + at);
                Rectangle button = bounds(find("utility-overlays"));
                Rectangle panel = bounds(panel());
                assertEquals(button.y - GpuUtilityBar.GAP, panel.y + panel.height, .5f, "one gap below its button at " + at);
                assertEquals(Math.min(button.x + button.width, size[0] - GpuUtilityBar.GAP), panel.x + panel.width, .5f,
                      "its right edge at the button's, inside the window, at " + at);
                assertTrue(panel.x >= GpuUtilityBar.GAP - .5f && panel.y >= 0, "inside the window at " + at + ": " + panel);
                for (String row : rows) {
                    assertTrue(expanded("overlay-" + row), row + " at " + at);
                }
                if (before != null) {
                    assertEquals(before, bounds(find(column)), column + " keeps its place at " + at);
                }
                assertTrue(hud.keyDown(Input.Keys.H, KeyEvent.VK_H, 0));
                hud.keyUp(Input.Keys.H, KeyEvent.VK_H);
            }
        }
    }

    /**
     * The initiative card and the popover need no shared room: at 1280 x 800 and 1920 x 1080 the popover opens with its
     * rows while the card shows, over it (its layer draws after the panels'), and the card keeps its place.
     */
    @Test
    void theOverlaysPopoverOpensOverTheInitiativeCardWhichKeepsItsPlace() {
        for (int[] size : new int[][] { { 1280, 800 }, { 1920, 1080 } }) {
            String at = size[0] + "x" + size[1];
            show(size[0], size[1], true, GamePhase.INITIATIVE_REPORT, Overlays.DEFAULTS, true, true);
            Actor initiative = find("initiative-card");
            assertTrue(shown(initiative), "the initiative card at " + at);
            Rectangle card = bounds(initiative);
            assertTrue(hud.keyDown(Input.Keys.H, KeyEvent.VK_H, 0));
            hud.keyUp(Input.Keys.H, KeyEvent.VK_H);
            show(size[0], size[1], true, GamePhase.INITIATIVE_REPORT, Overlays.DEFAULTS, true, true);
            assertTrue(shown(panel()) && expanded("overlay-envelope"), "the popover with its rows at " + at);
            assertEquals(card, bounds(find("initiative-card")), "the card keeps its place at " + at);
            assertTrue(layer(panel()) > layer(initiative), "the popover over the card at " + at);
            assertTrue(hud.keyDown(Input.Keys.H, KeyEvent.VK_H, 0));
            hud.keyUp(Input.Keys.H, KeyEvent.VK_H);
        }
    }

    /** The draw order of the stage's layer that holds {@code actor}: its top-level group's index. */
    private int layer(Actor actor) {
        Actor top = actor;
        while (top.getParent() != hud.stage.getRoot()) {
            top = top.getParent();
        }
        return hud.stage.getRoot().getChildren().indexOf(top, true);
    }

    /** Runs the button's click as a pointer's click would, through its ClickListener. */
    private static void click(Actor actor) {
        for (EventListener listener : actor.getListeners()) {
            if (listener instanceof ClickListener click) { click.clicked(new InputEvent(), 0, 0); }
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
