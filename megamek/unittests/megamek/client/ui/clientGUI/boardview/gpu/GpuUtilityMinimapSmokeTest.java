/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiCursorCapture;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.preference.PreferenceManager;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The G4 components in the component harness: the top-right utilities, the Tactical View chip and north mark, the hint
 * line and the minimap over the shipped board in 3D and in the Tactical View, beside shots 01, 07, 08 and 15; their
 * layout at the three reference sizes; and their commands: the utilities and the minimap's close button run the
 * camera, the View menu's minimap item and the HUD's own toggles, the minimap takes its presses and its drag moves
 * only the camera, up to the board's edge, its hexes show their tileset art's colours, and the Tactical View zooms
 * within the 3D view's limits.
 */
@Tag("on-demand")
class GpuUtilityMinimapSmokeTest {
    private static final int[][] SIZES = { { 900, 600 }, { 1280, 720 }, { 1920, 1080 } };
    /** The Atlas's planned route of the fixture: three hexes north. */
    private static final List<GpuMovePlan.Step> ROUTE = Stream.of(new Coords(14, 13), new Coords(14, 12),
          new Coords(14, 11), new Coords(14, 10))
          .map(coords -> new GpuMovePlan.Step(coords, 0, 0, 0, GpuMovePlan.Band.WALK, false)).toList();
    private static final GpuBoardSource.UiPreferences PREFERENCES = preferences();

    /** The three components as GpuHud builds them, with the View menu's minimap item they run. */
    private static final class Parts {
        final GpuHudState state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
        final GpuBoardSource source = mock(GpuBoardSource.class);
        final AtomicInteger minimapRuns = new AtomicInteger();
        final List<BoardScene.Command> global;
        final GpuUtilityBar utilities;
        final GpuHintLine hint;
        final GpuMinimap minimap;
        /** The client's preferences the frames carry. */
        GpuBoardSource.UiPreferences preferences = PREFERENCES;

        Parts(GpuHudTestStage hud, BoardCamera camera, BoardScene scene) {
            BoardScene.Command item = new BoardScene.Command("View:View/" + ClientGUI.VIEW_MINI_MAP + ":Minimap",
                  "Minimap", "", true, false, false, List.of(), minimapRuns::incrementAndGet, "Ctrl+M", true);
            global = List.of(new BoardScene.Command("View:View", "View", "", true, false, false, List.of(item),
                  () -> { }, "", null));
            utilities = new GpuUtilityBar(hud.kit, source, state, camera, new GpuBoardTuning(hud.kit.ui.skin),
                  GpuUtilityBar.utility(hud.kit.ui, "tune", "GpuBoard.hud.tuning.title", "tuning-button"),
                  () -> camera.fit(scene));
            hint = new GpuHintLine(hud.kit, source, state, camera);
            minimap = new GpuMinimap(hud.kit, source, state, camera);
            for (Actor actor : List.of(utilities.north(), utilities.actor(), utilities.chip(), hint.actor(),
                  minimap.actor(), utilities.popover())) {
                hud.window.addActor(actor);
            }
        }

        /** One frame's snapshots, as GpuHud hands them to every component. */
        GpuHud.Inputs update(BoardScene scene, GpuBattleStatus.Snapshot status, GpuMovePlan.Snapshot move,
              GpuReportLog.Snapshot reports, boolean tactical, int width, int height) {
            // As GpuBattleView before the HUD: the history takes the log and presents the steps no shot waits for.
            state.history.accept(List.of(), scene, reports, unit -> false);
            state.history.advance(0, playback -> true);
            state.update(status, GpuUnitRecord.Snapshot.EMPTY, false);
            GpuBoardSource.Frame frame = new GpuBoardSource.Frame(scene, List.of(), null, global, "", null, 0,
                  "", null, reports, status, GpuHudFixtures.panels(move, GpuFireOrders.Snapshot.EMPTY,
                        GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY));
            GpuHud.Inputs inputs = new GpuHud.Inputs(frame, new GpuHud.HudView(tactical, false, Map.of(), Map.of(),
                  Map.of(), null, Entity.NONE, 0), null, preferences, GpuHud.Metrics.of(width, height), List.of());
            utilities.update(inputs);
            hint.update(inputs);
            minimap.update(inputs);
            return inputs;
        }

        /**
         * Places the components as GpuHud does: utilities at the top-right gap, the minimap one gap below them
         * in the right column's width, the chip centred at top 20, the
         * Tactical View's north mark centred at its own top, and the hint line centred at bottom 7, hidden at
         * W <= 1350.
         */
        void place(GpuHud.Metrics metrics) {
            float width = metrics.width();
            float height = metrics.height();
            Table bar = (Table) utilities.actor();
            bar.pack();
            bar.setPosition(width - metrics.gap() - bar.getWidth(), height - metrics.gap() - bar.getHeight());
            Table map = (Table) minimap.actor();
            map.setSize(metrics.right(), map.getPrefHeight());
            map.validate();
            map.setPosition(width - metrics.gap() - metrics.right(), bar.getY() - metrics.gap() - map.getHeight());
            Table chip = (Table) utilities.chip();
            chip.pack();
            // As GpuHud places it: centred in the top row's room left of the utility bar.
            float chipLeft = metrics.gap() + metrics.left();
            float chipRight = bar.getX() - metrics.gap();
            chip.setPosition(Math.round((chipLeft + chipRight - chip.getWidth()) / 2), height - 20 - chip.getHeight());
            var north = (com.badlogic.gdx.scenes.scene2d.ui.Container<?>) utilities.north();
            north.pack();
            north.setPosition(Math.round((width - north.getWidth()) / 2),
                  height - GpuUtilityBar.NORTH_TOP - north.getHeight());
            Table line = (Table) hint.actor();
            line.pack();
            line.setPosition(Math.round((width - line.getWidth()) / 2), 7);
            line.setVisible(!metrics.narrow());
        }
    }

    @Test
    void utilitiesChipHintAndMinimapBesideTheMock() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        System.out.println("Tileset " + PreferenceManager.getClientPreferences().getMapTileset());
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene);
            Parts parts = new Parts(hud, board.camera, scene);
            try {
                GpuBattleStatus.Snapshot fixture = GpuHudFixtures.status();
                GpuBattleStatus.Snapshot initiative = status(fixture, GamePhase.INITIATIVE_REPORT, false, Entity.NONE);
                GpuBattleStatus.Snapshot firing = status(fixture, GamePhase.FIRING, true, GpuHudFixtures.ATLAS);

                GpuMovePlan.Snapshot none = GpuMovePlan.Snapshot.EMPTY;
                GpuReportLog.Snapshot quiet = GpuReportLog.Snapshot.EMPTY;
                // Shot 01: initiative in 3D, the minimap filling its panel, Map and Contacts pressed.
                render(hud, board, parts, scene, initiative, none, quiet, false, 1920, 1080,
                      "g4-initiative-1920x1080", "01-initiative.jpg", true);
                // Shot 03: the own movement turn with a planned route on the minimap and the planner's hint.
                render(hud, board, parts, scene, fixture, move(), quiet, false, 1920, 1080,
                      "g4-movement-1920x1080", "03-waypoint-route.jpg", true);
                // Shot 07: the Tactical View while declaring attacks, with the chip and the Map overview.
                render(hud, board, parts, scene, firing, none, quiet, true, 1920, 1080,
                      "g4-tactical-1920x1080", "07-tactical-view.jpg", true);
                // Shot 08: the Log utility's badge counts the round's reviewable events the board has presented,
                // none while the volley's shots wait in the playback.
                GpuBattleStatus.Snapshot report = status(fixture, GamePhase.FIRING_REPORT, false, Entity.NONE);
                List<BoardScene.Combat> shots = shots(scene, 4);
                GpuReportLog.Snapshot volley = volley(shots);
                parts.state.history.accept(List.copyOf(shots), scene, volley, unit -> false);
                parts.update(scene, report, none, volley, false, 1920, 1080);
                assertEquals(null, badge(parts.utilities), "No badge before the shots land");
                for (int step = 0; step < 200 && parts.state.history.running(); step++) {
                    parts.state.history.advance(.1, playback -> true);
                }
                render(hud, board, parts, scene, report, none, volley, false, 1920, 1080, "g4-playback-1920x1080",
                      "08-weapon-playback.jpg", false);
                assertEquals("4", badge(parts.utilities), "The Log utility counts the presented events");
                // Shot 15: the compact 1280 x 720 window: narrow utilities and a 120-unit canvas (GpuHud hides the
                // hint line there).
                render(hud, board, parts, scene, firing, none, quiet, false, 1280, 720,
                      "g4-compact-1280x720", "15-compact-1280x720.jpg", true);

                // E rule 6: every part in the window, clear of the others and of the tuning utility's room.
                for (int[] size : SIZES) {
                    hud.size(size[0], size[1]);
                    GpuHud.Metrics metrics = GpuHud.Metrics.of(size[0], size[1]);
                    for (boolean tactical : new boolean[] { false, true }) {
                        parts.update(scene, firing, move(), GpuReportLog.Snapshot.EMPTY, tactical, size[0], size[1]);
                        parts.place(metrics);
                        assertLayout(hud, parts, metrics, tactical);
                    }
                }
            } finally {
                parts.minimap.dispose();
                board.dispose();
            }
        });
    }

    @Test
    void theCommandsMoveOnlyTheCameraAndRunTheMenuItemAndTheMinimapShowsTheTerrainColours() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene);
            Parts parts = new Parts(hud, board.camera, scene);
            try {
                GpuBattleStatus.Snapshot moving = GpuHudFixtures.status();
                board.view(false);
                show(hud, board, parts, scene, moving, move(), false);
                assertEquals(null, badge(parts.utilities), "No badge without reviewable events");
                checkUtilities(hud, board, parts, scene, moving);
                checkMinimapDrag(hud, board, parts, scene, moving);
                checkTileColours(hud, board, parts, scene, moving);
                checkTacticalZoom(board);
                checkHint(parts, scene);
            } finally {
                parts.minimap.dispose();
                board.dispose();
            }
        });
    }

    /**
     * Item 33.4: the Contacts utility shows MekBay's enemy icon and is pressed while the client's contacts preference
     * shows the panel; the row is captured pressed and released, and the minimap and chip as the user asked them.
     */
    @Test
    void theContactsUtilityFollowsThePreferenceWithTheEnemyIcon() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene);
            Parts parts = new Parts(hud, board.camera, scene);
            try {
                UiButton contacts = hud.stage.getRoot().findActor("utility-contacts");
                assertSame(hud.kit.ui.skin.getDrawable("icon-enemy"), ((Image) contacts.icons.getFirst())
                      .getDrawable(), "MekBay's enemy icon");
                GpuBattleStatus.Snapshot moving = GpuHudFixtures.status();
                for (boolean enabled : new boolean[] { true, false }) {
                    parts.preferences = contacts(PREFERENCES, enabled);
                    board.view(false);
                    board.camera.zoom(.55f);
                    show(hud, board, parts, scene, moving, move(), false);
                    assertEquals(enabled, contacts.isChecked(), "pressed while the panel shows");
                    hud.capture("ux2-utilities-contacts-" + (enabled ? "on" : "off")).dispose();
                }
                parts.preferences = PREFERENCES;
                board.view(true);
                show(hud, board, parts, scene, moving, move(), true);
                hud.capture("ux2-tactical-chip").dispose();
            } finally {
                parts.minimap.dispose();
                board.dispose();
            }
        });
    }

    /** The preferences with the contacts panel shown or hidden. */
    private static GpuBoardSource.UiPreferences contacts(GpuBoardSource.UiPreferences p, boolean enabled) {
        return GpuHudFixtures.with(p, Map.of("contactsEnabled", enabled));
    }

    /** The utilities run the camera's Tactical View, the View menu's minimap item and the HUD's panel toggles. */
    private static void checkUtilities(GpuHudTestStage hud, GpuBoardSpaceHarness board, Parts parts,
          BoardScene scene, GpuBattleStatus.Snapshot status) {
        click(hud, "utility-map");
        assertEquals(1, parts.minimapRuns.get(), "Map runs the View menu's minimap item");
        click(hud, "minimap-close");
        assertEquals(2, parts.minimapRuns.get(), "The minimap's close button runs the same item");
        click(hud, "utility-log");
        assertTrue(parts.state.logOpen());
        click(hud, "utility-menu");
        assertEquals(GpuHudState.Dialog.MENU, parts.state.dialog);
        click(hud, "utility-menu");
        assertEquals(GpuHudState.Dialog.NONE, parts.state.dialog);
        click(hud, "utility-log");
        assertFalse(parts.state.logOpen());
        List<String> row = new ArrayList<>();
        ((Table) parts.utilities.actor()).getCells().forEach(cell -> row.add(cell.getActor().getName()));
        assertEquals(List.of("utility-view", "utility-tactical", "utility-map", "utility-contacts", "utility-log",
              "utility-menu"), row);
        Actor popover = hud.stage.getRoot().findActor("utility-view-menu");
        assertFalse(popover.isVisible(), "View starts closed");
        click(hud, "utility-view");
        assertTrue(popover.isVisible(), "View opens its popover");
        List<String> items = new ArrayList<>();
        ((Table) popover).getCells().forEach(cell -> items.add(cell.getActor().getName()));
        assertEquals(List.of("map-view-top", "map-view-isometric", "map-view-fit", "utility-wireframe",
              "tuning-button"), items);
        Rectangle view = GpuHudTestStage.bounds(hud.stage.getRoot().findActor("utility-view"));
        Rectangle hanging = GpuHudTestStage.bounds(popover);
        assertTrue(hanging.y + hanging.height <= view.y, "The popover hangs below the row");
        click(hud, "map-view-top");
        assertTrue(board.camera.isTopDown());
        assertFalse(popover.isVisible(), "A camera preset closes the popover");
        click(hud, "utility-view");
        click(hud, "map-view-isometric");
        assertFalse(board.camera.isTopDown());
        click(hud, "utility-view");
        click(hud, "map-view-fit");
        Vector3 fitted = board.camera.focus.cpy();
        board.camera.center(BoardGeometry.center(new Coords(1, 1), 0));
        click(hud, "utility-view");
        click(hud, "map-view-fit");
        assertTrue(fitted.epsilonEquals(board.camera.focus, .01f), "Fit restores the board framing after a pan");
        click(hud, "utility-view");
        click(hud, "utility-log");
        assertFalse(popover.isVisible(), "A press outside the popover closes it");
        assertTrue(parts.state.logOpen(), "and still reaches what it pressed");
        click(hud, "utility-log");

        click(hud, "utility-tactical");
        assertTrue(board.camera.tactical(), "Tactical view enters the Tactical View");
        show(hud, board, parts, scene, status, move(), true);
        assertTrue(parts.utilities.chip().isVisible());
        Button isometric = hud.stage.getRoot().findActor("map-view-isometric");
        assertTrue(isometric.isDisabled(), "The Tactical View keeps its top-down angle");
        click(hud, "utility-view");
        click(hud, "map-view-isometric");
        assertTrue(board.camera.isTopDown(), "The disabled preset cannot incline the Tactical View");
        assertTrue(board.camera.tactical(), "Camera presets keep the Tactical View active");
        click(hud, "utility-view");
        // The chip names the mode only: the pressed utility switches back.
        List<String> chip = new ArrayList<>();
        boolean button = false;
        for (Actor child : ((Table) parts.utilities.chip()).getChildren()) {
            if (child instanceof Label label) {
                chip.add(label.getText().toString());
            }
            button |= child instanceof Button;
        }
        assertEquals(List.of(UiTheme.upper(text("GpuBoard.hud.util.tactical"))), chip, "The chip's caption");
        assertFalse(button, "No Back to 3D button");
        click(hud, "utility-tactical");
        assertFalse(board.camera.tactical(), "Tactical view again returns to the 3D view");
        show(hud, board, parts, scene, status, move(), false);
        assertFalse(isometric.isDisabled());
        assertFalse(parts.utilities.chip().isVisible());
        verifyNoInteractions(parts.source);
        // The Contacts utility posts the switch of the client's contacts preference to the Swing thread.
        click(hud, "utility-contacts");
        verify(parts.source).command(any());
        clearInvocations(parts.source);
    }

    /**
     * A press on the minimap centres the camera on that ground point and a drag follows the pointer, in both views,
     * up to the board's edge. The canvas takes the press and the drag, so the board's click path, which would plan a
     * route, never gets them, and the minimap calls nothing of the client: the planned route stays a draft.
     */
    private static void checkMinimapDrag(GpuHudTestStage hud, GpuBoardSpaceHarness board, Parts parts,
          BoardScene scene, GpuBattleStatus.Snapshot status) {
        for (boolean tactical : new boolean[] { false, true }) {
            board.view(tactical);
            show(hud, board, parts, scene, status, move(), tactical);
            Actor canvas = hud.stage.getRoot().findActor("minimap-canvas");
            Rectangle area = GpuHudTestStage.bounds(canvas);
            float azimuth = board.camera.azimuth();
            float tilt = board.camera.tilt();
            float z = board.camera.focus.z;
            Vector2 middle = new Vector2(area.x + area.width / 2, area.y + area.height / 2);
            // GpuHud.hit keeps a press from the board wherever its stage finds an actor: here the canvas.
            assertSame(canvas, hud.stage.hit(middle.x, middle.y, true), "The canvas takes presses on it");
            Vector2 screen = hud.stage.stageToScreenCoordinates(middle.cpy());
            int x = Math.round(screen.x);
            int y = Math.round(screen.y);
            assertTrue(hud.stage.touchDown(x, y, 0, Input.Buttons.LEFT), "The canvas consumes the press");
            Vector2 pressed = hud.stage.screenToStageCoordinates(new Vector2(x, y));
            Vector3 centre = board.camera.focus.cpy();
            // The board is centred on the canvas, so its middle lies under the canvas's middle.
            float units = (BoardCamera.boardWidth(scene) + BoardCamera.boardHeight(scene)) / (area.width + area.height);
            assertEquals(BoardCamera.boardWidth(scene) / 2, centre.x, 2 * units, "Press centres the camera on x");
            assertEquals(-BoardCamera.boardHeight(scene) / 2, centre.y, 2 * units, "Press centres the camera on y");
            assertEquals(z, centre.z, "The camera keeps its focus plane");
            assertTrue(hud.stage.touchDragged(x + 40, y, 0), "The canvas consumes the drag");
            assertTrue(board.camera.focus.x > centre.x + units, "A drag east moves the camera east");
            assertEquals(centre.y, board.camera.focus.y, .01f * units, "A drag east keeps the camera's north");
            // Far past the canvas's lower left, the camera stops at the board's west and south edges.
            assertTrue(hud.stage.touchDragged(x - 1000, y + 1000, 0), "The canvas keeps the drag off the canvas");
            assertEquals(0, board.camera.focus.x, .001f, "A drag off the canvas stops at the west edge");
            assertEquals(-BoardCamera.boardHeight(scene), board.camera.focus.y, .001f,
                  "A drag off the canvas stops at the south edge");
            hud.stage.touchUp(x - 1000, y + 1000, 0, Input.Buttons.LEFT);
            assertEquals(azimuth, board.camera.azimuth(), "The drag pans only");
            assertEquals(tilt, board.camera.tilt(), "The drag pans only");
            assertEquals(z, board.camera.focus.z, "The drag keeps the focus plane");
            assertFalse(UiCursorCapture.active(), "The left drag follows the visible pointer");
            // A middle drag rotates both views, with inclination only in 3D, as the board's does.
            assertTrue(hud.stage.touchDown(x, y, 0, Input.Buttons.MIDDLE), "The canvas takes the middle press");
            assertTrue(UiCursorCapture.active(), "An orbit holds the cursor");
            assertTrue(hud.stage.touchDragged(x + 40, y + 20, 0), "The canvas consumes the orbit drag");
            assertNotEquals(azimuth, board.camera.azimuth(), "A middle drag rotates in both views");
            if (tactical) {
                assertTrue(board.camera.isTopDown(), "The Tactical View rotates without inclining");
            } else {
                assertTrue(board.camera.tilt() > tilt, "A middle drag also tilts in 3D");
            }
            assertEquals(tactical, board.camera.tactical(), "Orbiting keeps the renderer mode");
            hud.stage.touchUp(x + 40, y + 20, 0, Input.Buttons.MIDDLE);
            assertFalse(UiCursorCapture.active(), "Releasing the orbit shows the cursor again");
            // One gesture at a time: a second button neither starts an orbit nor outlives the first.
            float orbited = board.camera.azimuth();
            hud.stage.touchDown(x, y, 0, Input.Buttons.LEFT);
            hud.stage.touchDown(x, y, 0, Input.Buttons.MIDDLE);
            assertFalse(UiCursorCapture.active(), "A middle press during a left drag does not orbit");
            hud.stage.touchUp(x, y, 0, Input.Buttons.MIDDLE);
            hud.stage.touchDragged(x + 40, y, 0);
            assertEquals(orbited, board.camera.azimuth(), "The left drag stays a pan");
            hud.stage.touchUp(x + 40, y, 0, Input.Buttons.LEFT);
            hud.stage.touchDown(x, y, 0, Input.Buttons.MIDDLE);
            hud.stage.touchDown(x, y, 0, Input.Buttons.LEFT);
            hud.stage.touchUp(x, y, 0, Input.Buttons.MIDDLE);
            assertFalse(UiCursorCapture.active(), "Releasing the middle button ends the orbit");
            hud.stage.touchDragged(x + 40, y, 0);
            assertEquals(orbited, board.camera.azimuth(), "Nothing orbits after the release");
            hud.stage.touchUp(x + 40, y, 0, Input.Buttons.LEFT);
            // The wheel over the map zooms the camera by the board's step, about the view's centre (the user's
            // decision of 2026-10-03); off the map the wheel is not the map's.
            // The stage fires enter and exit in act, as each frame does.
            hud.stage.mouseMoved(x, y);
            hud.stage.act(0);
            Vector3 focus = board.camera.focus.cpy();
            float zoom = board.camera.camera.zoom;
            assertTrue(hud.stage.scrolled(0, 2), "The map takes the wheel under the pointer");
            assertEquals(zoom * GpuBattleView.wheelZoom(2), board.camera.camera.zoom, zoom * 1e-4f,
                  "Two notches zoom out by the board's step");
            assertTrue(focus.epsilonEquals(board.camera.focus, .001f), "The wheel keeps the view's centre");
            hud.stage.mouseMoved(x - 1000, y);
            hud.stage.act(0);
            assertFalse(hud.stage.scrolled(0, 1), "Off the map the wheel is not the map's");
            System.out.printf("Minimap press at %s centred the camera at %s (%s view)%n", pressed, centre,
                  tactical ? "tactical" : "3D");
        }
        board.view(false);
        verifyNoInteractions(parts.source);
    }

    /**
     * Each hex shows its terrain's colour, found through the minimap's own mapping: a press gives the ground point
     * under the pointer. An open hex shows {@link GpuMinimap#colour}; a hex with woods differs from it; a water hex
     * shows water whatever art its liquid names, and a hazardous liquid's hex differs from water. The captures have no
     * route or units, and the sampled hexes lie three hexes inside the board, clear of the frustum.
     */
    private static void checkTileColours(GpuHudTestStage hud, GpuBoardSpaceHarness board, Parts parts,
          BoardScene scene, GpuBattleStatus.Snapshot status) {
        show(hud, board, parts, scene, withoutUnits(status), GpuMovePlan.Snapshot.EMPTY, false);
        Pixmap image = hud.capture("g4-minimap-colours");
        try {
            Rectangle area = GpuHudTestStage.bounds(hud.stage.getRoot().findActor("minimap-canvas"));
            Vector2 first = new Vector2(area.x + area.width / 2, area.y + area.height / 2);
            Vector2 second = first.cpy().add(40, 20);
            Vector3 origin = press(hud, board, first);
            Vector3 other = press(hud, board, second);
            Vector2 scale = new Vector2((second.x - first.x) / (other.x - origin.x),
                  (second.y - first.y) / (other.y - origin.y));
            List<BoardScene.Tile> inner = scene.tiles().stream().filter(tile -> tile.coords().getX() >= 3
                  && tile.coords().getY() >= 3 && tile.coords().getX() < scene.width() - 3
                  && tile.coords().getY() < scene.height() - 3).toList();
            BoardScene.Tile open = inner.stream().filter(tile -> !wooded(tile) && !tile.water()
                  && BoardBridge.feature(tile) == null && tile.tileset() != null).findFirst().orElseThrow();
            BoardScene.Tile wooded = inner.stream().filter(GpuUtilityMinimapSmokeTest::wooded).findFirst()
                  .orElseThrow();
            int openColour = sample(image, first, origin, scale, open.coords());
            int expected = GpuMinimap.colour(open);
            System.out.printf("Open hex %s: minimap #%08X, terrain colour #%08X%n", open.coords(), openColour, expected);
            for (int shift : new int[] { 24, 16, 8 }) {
                assertEquals(expected >>> shift & 255, openColour >>> shift & 255, 6,
                      "A hex without trees shows its terrain's colour");
            }
            int woodedColour = sample(image, first, origin, scale, wooded.coords());
            System.out.printf("Wooded hex %s: minimap #%08X%n", wooded.coords(), woodedColour);
            assertNotEquals(openColour, woodedColour, "A wooded hex shows its trees");

            // Three more bare hexes made depth-1 liquid: water shows water, also when its liquid names art that does
            // not exist; a hazardous liquid differs from it.
            List<BoardScene.Tile> dry = inner.stream().filter(tile -> !tile.water()
                  && tile.coords().distance(open.coords()) >= 3).toList();
            BoardScene.Tile wet = dry.getFirst();
            BoardScene.Tile unreadable = dry.stream().filter(tile -> tile.coords().distance(wet.coords()) >= 3)
                  .findFirst().orElseThrow();
            BoardScene.Tile hazardous = dry.stream().filter(tile -> tile.coords().distance(wet.coords()) >= 3
                  && tile.coords().distance(unreadable.coords()) >= 3).findFirst().orElseThrow();
            board.view(false);
            BoardScene flooded = withLiquid(withLiquid(withLiquid(scene, wet.coords(), BoardLiquid.WATER),
                  unreadable.coords(), new BoardLiquid(BoardLiquid.Kind.WATER, "missing", 0)), hazardous.coords(),
                  new BoardLiquid(BoardLiquid.Kind.HAZARDOUS, "", 0));
            show(hud, board, parts, flooded, withoutUnits(status), GpuMovePlan.Snapshot.EMPTY, false);
            Pixmap liquids = hud.capture("g4-minimap-water");
            try {
                int waterColour = sample(liquids, first, origin, scale, wet.coords());
                int water = GpuMinimap.colour(tile(flooded, wet.coords()));
                int hazardousColour = sample(liquids, first, origin, scale, hazardous.coords());
                int unreadableColour = sample(liquids, first, origin, scale, unreadable.coords());
                int missing = GpuMinimap.colour(tile(flooded, unreadable.coords()));
                System.out.printf("Water hex %s: minimap #%08X, terrain colour #%08X; missing art #%08X; hazardous"
                      + " #%08X%n", wet.coords(), waterColour, water, unreadableColour, hazardousColour);
                assertEquals(water, missing, "The art a liquid names does not change its colour");
                for (int shift : new int[] { 24, 16, 8 }) {
                    assertEquals(water >>> shift & 255, waterColour >>> shift & 255, 6, "The water hex shows water");
                    assertEquals(water >>> shift & 255, unreadableColour >>> shift & 255, 6,
                          "A liquid naming missing art shows water");
                }
                assertNotEquals(waterColour, hazardousColour, "A hazardous liquid differs from water");
            } finally {
                liquids.dispose();
            }
        } finally {
            image.dispose();
        }
    }

    private static BoardScene.Tile tile(BoardScene scene, Coords coords) {
        return scene.tiles().stream().filter(tile -> tile.coords().equals(coords)).findFirst().orElseThrow();
    }

    private static boolean wooded(BoardScene.Tile tile) {
        return tile.features().stream().anyMatch(feature -> feature.kind() == BoardScene.FeatureKind.TREE);
    }

    /** The scene with one hex turned into open liquid of depth 1, as the source captures a water hex. */
    private static BoardScene withLiquid(BoardScene scene, Coords coords, BoardLiquid liquid) {
        List<BoardScene.Tile> tiles = scene.tiles().stream().map(tile -> !tile.coords().equals(coords) ? tile
              : new BoardScene.Tile(tile.coords(), tile.elevation(), 1, false, tile.roadExits(), tile.surface(),
                    tile.decals(), tile.decalsWithoutLimbs(),
                    tile.features(), tile.text(), liquid, tile.tileset())).toList();
        return new BoardScene(scene.boardId(), scene.width(), scene.height(), tiles, scene.units(),
              scene.plannedPath(), scene.selectedId(), scene.phase(), scene.commands(), scene.light(),
              scene.firingLines(), scene.rangeBorders(), scene.markers(), scene.tactical(), scene.rangeLabels(),
              scene.fieldOfView());
    }

    /**
     * The Tactical View zooms within the 3D view's limits, one rule that does not depend on the board, so a big board
     * zooms in as far as in 3D: here well past four times the fitted board, the prototype's flat-view limit.
     */
    private static void checkTacticalZoom(GpuBoardSpaceHarness board) {
        board.view(false);
        float[] threeD = zoomLimits(board.camera);
        board.view(true);
        float fitted = board.camera.camera.zoom;
        float[] tactical = zoomLimits(board.camera);
        assertEquals(threeD[0], tactical[0], "The Tactical View zooms in as far as the 3D view");
        assertEquals(threeD[1], tactical[1], "The Tactical View zooms out as far as the 3D view");
        assertTrue(tactical[0] < fitted / 4, "Zooming in goes past four times the fitted board");
        board.view(false);
    }

    /** The camera's zoom after zooming all the way in, then after zooming all the way out. */
    private static float[] zoomLimits(BoardCamera camera) {
        for (int step = 0; step < 200; step++) {
            camera.zoom(1 / 1.25f);
        }
        float in = camera.camera.zoom;
        for (int step = 0; step < 200; step++) {
            camera.zoom(1.25f);
        }
        return new float[] { in, camera.camera.zoom };
    }

    /** The hint line's gestures follow the view, the phase and the local turn, with the current keys. */
    private static void checkHint(Parts parts, BoardScene scene) {
        GpuBattleStatus.Snapshot fixture = GpuHudFixtures.status();
        GpuHud.Inputs tactical = parts.update(scene, status(fixture, GamePhase.FIRING, true, GpuHudFixtures.ATLAS),
              move(), GpuReportLog.Snapshot.EMPTY, true, 1920, 1080);
        assertEquals(List.of(text("GpuBoard.hud.mouse.leftClick"), text("GpuBoard.hud.hint.select"),
              text("GpuBoard.hud.mouse.rightDrag"), text("GpuBoard.hud.hint.panMap"),
              text("GpuBoard.hud.mouse.orbitShort"), text("GpuBoard.hud.hint.rotate"), text("GpuBoard.hud.mouse.wheel"),
              text("GpuBoard.hud.hint.zoom"), "H", text("GpuBoard.hud.hint.overlays"), "T",
              text("GpuBoard.hud.util.backTo3d")), GpuHintLine.items(tactical));
        GpuHud.Inputs planning = parts.update(scene, fixture, move(), GpuReportLog.Snapshot.EMPTY, false, 1920, 1080);
        assertEquals(List.of(text("GpuBoard.hud.mouse.leftClick"), text("GpuBoard.hud.hint.planMove"),
              text("GpuBoard.hud.mouse.ctrlClick"), text("GpuBoard.hud.hint.waypoint"),
              text("GpuBoard.hud.mouse.shiftClick"), text("GpuBoard.hud.hint.orientation"),
              text("GpuBoard.hud.mouse.rightDrag"), text("GpuBoard.hud.hint.pan"),
              text("GpuBoard.hud.mouse.orbitShort"), text("GpuBoard.hud.hint.orbit")),
              GpuHintLine.items(planning));
        GpuHud.Inputs waiting = parts.update(scene, status(fixture, GamePhase.MOVEMENT, false, 6), move(),
              GpuReportLog.Snapshot.EMPTY, false, 1920, 1080);
        assertEquals(text("GpuBoard.hud.hint.select"), GpuHintLine.items(waiting).get(1));
        GpuHud.Inputs review = parts.update(scene, status(fixture, GamePhase.END_REPORT, false, Entity.NONE), move(),
              GpuReportLog.Snapshot.EMPTY, false, 1920, 1080);
        assertEquals(text("GpuBoard.hud.hint.select"), GpuHintLine.items(review).get(1));
    }

    /** Updates, places and draws the parts over the board in one view, at 1920 x 1080. */
    private static void show(GpuHudTestStage hud, GpuBoardSpaceHarness board, Parts parts, BoardScene scene,
          GpuBattleStatus.Snapshot status, GpuMovePlan.Snapshot move, boolean tactical) {
        hud.size(1920, 1080);
        parts.update(scene, status, move, GpuReportLog.Snapshot.EMPTY, tactical, 1920, 1080);
        parts.place(GpuHud.Metrics.of(1920, 1080));
        board.draw(camera -> { });
        hud.drawStage();
    }

    /**
     * Renders one screen of the prototype and writes it with each part beside the same area of the mock. The 3D view
     * is zoomed in from the fitted board, as the prototype's camera, so the minimap's frustum lies on the board.
     */
    private static void render(GpuHudTestStage hud, GpuBoardSpaceHarness board, Parts parts, BoardScene scene,
          GpuBattleStatus.Snapshot status, GpuMovePlan.Snapshot move, GpuReportLog.Snapshot reports,
          boolean tactical, int width, int height, String name, String mock, boolean everything) {
        board.view(tactical);
        if (!tactical) {
            board.camera.zoom(.55f);
        }
        hud.size(width, height);
        parts.update(scene, status, move, reports, tactical, width, height);
        parts.place(GpuHud.Metrics.of(width, height));
        board.draw(camera -> { });
        hud.drawStage();
        Pixmap image = hud.capture(name);
        try {
            List<Actor> shown = new ArrayList<>(List.of(parts.utilities.actor()));
            if (everything) {
                shown.add(parts.minimap.actor());
                shown.add(parts.utilities.chip());
                shown.add(parts.utilities.north());
                shown.add(parts.hint.actor());
            }
            for (Actor actor : shown) {
                if (actor.isVisible()) {
                    Rectangle area = GpuHudTestStage.bounds(actor);
                    hud.compare(name + "-" + actor.getName(), image, actor, mock, Math.round(area.x),
                          Math.round(height - area.y - area.height));
                    System.out.println(name + " " + actor.getName() + " at " + area);
                }
            }
        } finally {
            image.dispose();
        }
    }

    /** E rule 6 for one window size and view: in the window, clear of each other and of the tuning utility. */
    private static void assertLayout(GpuHudTestStage hud, Parts parts, GpuHud.Metrics metrics, boolean tactical) {
        Actor bar = parts.utilities.actor();
        Actor map = parts.minimap.actor();
        Actor chip = parts.utilities.chip();
        Rectangle barArea = GpuHudTestStage.bounds(bar);
        List<Rectangle> others = new ArrayList<>(List.of(GpuHudTestStage.bounds(map)));
        // GpuHud hides the chip where the top row has no room for it (below about 1000 units wide).
        boolean chipShown = tactical && metrics.width() >= 1280;
        assertEquals(tactical, chip.isVisible());
        if (chipShown) {
            others.add(GpuHudTestStage.bounds(chip));
        }
        hud.assertLayout(bar, others.toArray(new Rectangle[0]));
        hud.assertLayout(map, barArea);
        if (chipShown) {
            hud.assertLayout(chip, barArea, GpuHudTestStage.bounds(map));
        }
        // The north mark shows in the Tactical View whether or not the chip has room.
        Actor north = parts.utilities.north();
        assertEquals(tactical, north.isVisible());
        if (tactical) {
            others.add(barArea);
            hud.assertLayout(north, others.toArray(new Rectangle[0]));
        }
        if (parts.hint.actor().isVisible()) {
            hud.assertLayout(parts.hint.actor(), barArea, GpuHudTestStage.bounds(map));
        }
        System.out.printf("%.0f x %.0f %s: utilities %s, minimap %s, chip %s, north %s, hint %s%n", metrics.width(),
              metrics.height(), tactical ? "tactical" : "3D", barArea, GpuHudTestStage.bounds(map),
              chipShown ? GpuHudTestStage.bounds(chip) : "hidden",
              tactical ? GpuHudTestStage.bounds(north) : "hidden",
              parts.hint.actor().isVisible() ? GpuHudTestStage.bounds(parts.hint.actor()) : "hidden");
    }

    /** Presses the minimap at a stage point and returns where the camera centred. */
    private static Vector3 press(GpuHudTestStage hud, GpuBoardSpaceHarness board, Vector2 point) {
        Vector2 screen = hud.stage.stageToScreenCoordinates(point.cpy());
        int x = Math.round(screen.x);
        int y = Math.round(screen.y);
        point.set(hud.stage.screenToStageCoordinates(new Vector2(x, y)));
        hud.stage.touchDown(x, y, 0, Input.Buttons.LEFT);
        hud.stage.touchUp(x, y, 0, Input.Buttons.LEFT);
        return board.camera.focus.cpy();
    }

    /** The captured colour where the minimap draws the centre of {@code coords}. */
    private static int sample(Pixmap image, Vector2 pressed, Vector3 origin, Vector2 scale, Coords coords) {
        float x = pressed.x + (BoardGeometry.centerX(coords) - origin.x) * scale.x;
        float y = pressed.y + (BoardGeometry.centerY(coords) - origin.y) * scale.y;
        return image.getPixel(Math.round(x), Math.round(y));
    }

    private static void click(GpuHudTestStage hud, String name) {
        Actor actor = hud.stage.getRoot().findActor(name);
        Vector2 screen = hud.stage.stageToScreenCoordinates(
              actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
        hud.stage.touchDown(Math.round(screen.x), Math.round(screen.y), 0, Input.Buttons.LEFT);
        hud.stage.touchUp(Math.round(screen.x), Math.round(screen.y), 0, Input.Buttons.LEFT);
    }

    /** The text of the Log utility's shown count badge (its label other than the caption), or null. */
    private static String badge(GpuUtilityBar utilities) {
        UiButton log = ((Table) utilities.actor()).findActor("utility-log");
        for (Actor child : log.getChildren()) {
            if (child instanceof Label label && label != log.getLabel() && label.isVisible()) {
                return label.getText().toString();
            }
        }
        return null;
    }

    private static GpuBattleStatus.Snapshot status(GpuBattleStatus.Snapshot fixture, GamePhase phase, boolean myTurn,
          int actor) {
        return new GpuBattleStatus.Snapshot(fixture.round(), phase, myTurn, fixture.localPlayerId(), actor,
              fixture.turns(), fixture.turnIndex(), fixture.units(), List.of(), false);
    }

    private static GpuBattleStatus.Snapshot withoutUnits(GpuBattleStatus.Snapshot fixture) {
        return new GpuBattleStatus.Snapshot(fixture.round(), fixture.phase(), fixture.myTurn(),
              fixture.localPlayerId(), fixture.actorId(), fixture.turns(), fixture.turnIndex(), List.of(), List.of(),
              false);
    }

    /** The Atlas's plan: planner movement with the fixture route. */
    private static GpuMovePlan.Snapshot move() {
        GpuMovePlan.Snapshot empty = GpuMovePlan.Snapshot.EMPTY;
        return new GpuMovePlan.Snapshot(true, true, false, GpuHudFixtures.ATLAS, GpuMovePlan.Mode.AUTO, false, "",
              ROUTE, List.of(), List.of(), ROUTE.getLast().coords(), 0, 3, 3, EntityMovementType.MOVE_WALK, "",
              true, 1, 1, true, List.of(), true, true, true, empty.envelope(), Set.of(), 0, false);
    }

    /** A volley of {@code count} weapon attacks of this round, as the report log keeps them for review. */
    private static GpuReportLog.Snapshot volley(List<BoardScene.Combat> shots) {
        List<GpuReportLog.CombatEvent> combat = new ArrayList<>();
        for (BoardScene.Combat shot : shots) {
            combat.add(new GpuReportLog.CombatEvent(shot.result().id(), 3, GamePhase.FIRING,
                  ResolvedAttack.Kind.SHOT, shot.attacker().id(), shot.target().id(), "Medium Laser", "LA",
                  shot.result().hit(), 5, List.of(), null, 0));
        }
        return new GpuReportLog.Snapshot(3, GamePhase.FIRING_REPORT, List.of(), Map.of(), combat, List.of(),
              List.of(), List.of());
    }

    /** The Atlas's {@code count} medium laser shots at the King Crab, every other one a hit. */
    private static List<BoardScene.Combat> shots(BoardScene scene, int count) {
        BoardScene.Unit atlas = scene.units().stream().filter(unit -> unit.id() == GpuHudFixtures.ATLAS)
              .findFirst().orElseThrow();
        BoardScene.Unit enemy = scene.units().stream().filter(unit -> unit.id() == 7).findFirst().orElseThrow();
        List<BoardScene.Combat> shots = new ArrayList<>();
        for (int shot = 0; shot < count; shot++) {
            shots.add(UnitPlaybackFixture.attack(atlas, enemy, ResolvedAttack.Kind.SHOT, shot % 2 == 0));
        }
        return shots;
    }

    /** MegaMek's default key of every bind with its display text; the minimap shown. */
    private static GpuBoardSource.UiPreferences preferences() {
        List<GpuBoardSource.Bind> binds = Stream.of(KeyCommandBind.values())
              .map(bind -> new GpuBoardSource.Bind(bind, bind.keyDefault, bind.modifiersDefault,
                    KeyCommandBind.getDesc(bind.keyDefault, bind.modifiersDefault))).toList();
        return GpuHudFixtures.with(GpuHudFixtures.preferences(), Map.of("binds", binds));
    }

    private static String text(String key) {
        return Messages.getString(key);
    }
}
