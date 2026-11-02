/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.awt.event.InputEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The map preview's HUD on the real view: the hovered hex's card lists the hex and
 * the terrains a player sees, without the automated inclines of the rise beside it or terrain codes; and the preview
 * measures line of sight with MegaMek's ruler, whose line the board draws.
 */
@Tag("on-demand")
class GpuMapPreviewSmokeTest {
    /** Light woods at the foot of a two-level rise. */
    private static final Coords WOODS = new Coords(3, 3);

    @Test
    void theHexCardAndTheRulersLineShowOverThePreview() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires an OpenGL window");
        AtomicReference<GpuMapSource> source = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Game game = new Game();
            Hex[] hexes = new Hex[81];
            for (int y = 0; y < 9; y++) {
                for (int x = 0; x < 9; x++) {
                    Coords coords = new Coords(x, y);
                    hexes[y * 9 + x] = new Hex(x <= 2 ? 2 : 0, coords.equals(WOODS) ? "woods:1;foliage_elev:2" : "",
                          "grass", coords);
                }
            }
            game.setBoard(new Board(9, 9, hexes));
            BoardSceneryLayouts.convert(game.getBoard());
            source.set(new GpuMapSource(game, null, null));
        });
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1200, 850);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var view = new GpuBattleView(source.get());
                try {
                    view.create();
                    GpuBoardTestUi.present(view);
                    view.boardCamera.center(BoardGeometry.center(WOODS, 0));
                    settle(view, source.get());
                    boardClick(view, WOODS, Input.Buttons.LEFT, 0);
                    settle(view, source.get());
                    assertNull(source.get().takeFrame().context(), "Left-click must not pin the hex inspector");
                    assertFalse(source.get().takeFrame().panels().los().open(), "Plain click does not start LOS");
                    List<String> card = texts(GpuBoardTestUi.stage().getRoot().findActor("map-hex"));
                    assertEquals(List.of("HEX 0404", "Level 0 · grass", "Light woods", "TF 50",
                          "Woods/Jungle elevation: 2"), card, "The hex and what a player sees there");
                    var stage = GpuBoardTestUi.stage();
                    Actor utilities = stage.getRoot().findActor("map-utilities");
                    Actor hexCard = stage.getRoot().findActor("map-hex");
                    var top = utilities.localToStageCoordinates(new com.badlogic.gdx.math.Vector2());
                    var bottom = hexCard.localToStageCoordinates(new com.badlogic.gdx.math.Vector2());
                    assertEquals(GpuBoardHud.GAP, stage.getWidth() - top.x - utilities.getWidth(), .5f);
                    assertEquals(GpuBoardHud.GAP, stage.getHeight() - top.y - utilities.getHeight(), .5f);
                    assertEquals(GpuBoardHud.GAP, stage.getWidth() - bottom.x - hexCard.getWidth(), .5f);
                    assertEquals(GpuBoardHud.GAP, bottom.y, .5f);
                    GpuBoardTestUi.capture(new File(output, "map-preview-card.png"));
                    assertHoverInspection(view, source.get());

                    source.get().measure(new Coords(5, 7), InputEvent.ALT_DOWN_MASK, Float.NaN);
                    settle(view, source.get());
                    assertNull(GpuBoardTestUi.stage().getRoot().findActor("map-hint"));
                    assertTrue(source.get().takeFrame().panels().los().open());
                    source.get().measure(new Coords(5, 1), 0, Float.NaN);
                    settle(view, source.get());
                    Actor panel = GpuBoardTestUi.stage().getRoot().findActor("los-panel");
                    assertTrue(panel.isVisible(), "A plain click ends the measurement and shows the native ruler");
                    GpuBoardTestUi.capture(new File(output, "map-preview-ruler.png"));
                    assertTrue(panel.getWidth() <= 240, "The ruler is a compact floating card");
                    assertTrue(panel.getHeight() <= 140, "Extra information stays behind Details: " + panel.getHeight());
                    assertNotNull(source.get().takeFrame().scene().tactical().ruler());
                    Actor hex = GpuBoardTestUi.stage().getRoot().findActor("map-hex");
                    var position = hex.localToStageCoordinates(new com.badlogic.gdx.math.Vector2());
                    assertTrue(position.x > GpuBoardTestUi.stage().getWidth() / 2);
                    assertTrue(position.y < 40);
                    GpuBoardTestUi.capture(new File(output, "map-preview-ruler.png"));
                    GpuBoardTestUi.click("los-start-height-caption");
                    settle(view, source.get());
                    assertTrue(GpuBoardTestUi.stage().getRoot().findActor("los-start-height-popup").isVisible());
                    assertEquals("0", ((Label) stage.getRoot().findActor("los-start-height-slider-value")).getText().toString());
                    GpuBoardTestUi.capture(new File(output, "map-preview-los-slider.png"));
                    GpuBoardTestUi.stage().keyDown(com.badlogic.gdx.Input.Keys.ESCAPE);
                    // A drag on the same UIKit caption previews on the client thread without replacing the control.
                    Actor caption = GpuBoardTestUi.stage().getRoot().findActor("los-start-height-caption");
                    var drag = caption.localToStageCoordinates(new com.badlogic.gdx.math.Vector2(10, 8));
                    GpuBoardTestUi.stage().stageToScreenCoordinates(drag);
                    var input = Gdx.input.getInputProcessor();
                    input.touchDown((int) drag.x, (int) drag.y, 0, com.badlogic.gdx.Input.Buttons.LEFT);
                    input.touchDragged((int) drag.x + 40, (int) drag.y, 0);
                    settle(view, source.get());
                    assertTrue(source.get().takeFrame().panels().los().start().height() > 1);
                    assertEquals(Integer.toString(source.get().takeFrame().panels().los().start().height()),
                          ((Label) stage.getRoot().findActor("los-start-height-slider-value")).getText().toString());
                    GpuBoardTestUi.capture(new File(output, "map-preview-los-slider-drag.png"));
                    input.touchUp((int) drag.x + 40, (int) drag.y, 0, com.badlogic.gdx.Input.Buttons.LEFT);
                    settle(view, source.get());
                    assertEquals(caption, GpuBoardTestUi.stage().getRoot().findActor("los-start-height-caption"));
                    var field = (com.badlogic.gdx.scenes.scene2d.ui.TextField)
                          GpuBoardTestUi.stage().getRoot().findActor("los-end-height-field");
                    GpuBoardTestUi.stage().setKeyboardFocus(field); field.setText("7");
                    GpuBoardTestUi.stage().keyDown(com.badlogic.gdx.Input.Keys.ENTER);
                    settle(view, source.get());
                    assertEquals(7, source.get().takeFrame().panels().los().end().height());
                    for (boolean tactical : new boolean[] { false, true }) {
                        view.boardCamera.setTactical(tactical, source.get().takeFrame().scene());
                        view.boardCamera.setIsometric(true);
                        settle(view, source.get());
                        GpuBoardTestUi.capture(new File(output, "map-preview-los-tethers-" + (tactical ? "tactical" : "3d") + ".png"));
                    }
                    view.boardCamera.setTactical(false, source.get().takeFrame().scene());
                    settle(view, source.get());
                    GpuBoardTestUi.click("los-start-lock"); settle(view, source.get());
                    assertTrue(source.get().takeFrame().panels().los().start().locked());
                    GpuBoardTestUi.capture(new File(output, "map-preview-los-lock.png"));
                    GpuBoardTestUi.click("los-flip"); settle(view, source.get());
                    assertEquals(new Coords(5, 1), source.get().takeFrame().panels().los().start().coords());
                    assertEquals(7, source.get().takeFrame().panels().los().start().height());
                    assertTrue(source.get().takeFrame().panels().los().end().locked());
                    GpuBoardTestUi.click("los-end-lock"); settle(view, source.get());
                    assertFalse(source.get().takeFrame().panels().los().end().locked());
                    GpuBoardTestUi.click("los-details"); settle(view, source.get());
                    GpuBoardTestUi.click("los-compare"); settle(view, source.get());
                    assertEquals(3, source.get().takeFrame().panels().los().comparison().size());
                    GpuBoardTestUi.capture(new File(output, "map-preview-los-compare.png"));
                    GpuBoardTestUi.click("los-details"); settle(view, source.get());
                    assertTrue(source.get().takeFrame().panels().los().comparison().isEmpty());
                    assertTrue(panel.getHeight() <= 140, "Collapsing restores the compact card");
                    GpuBoardTestUi.click("los-close"); settle(view, source.get());
                    assertNull(source.get().takeFrame().scene().tactical().ruler());
                    rightClick(view, new Coords(5, 3)); settle(view, source.get());
                    assertTrue(GpuBoardTestUi.stage().getRoot().findActor("map-context-menu").isVisible());
                    assertEquals(new Coords(5, 3), source.get().takeFrame().context().coords(), "The open menu pins its hex");
                    GpuBoardTestUi.capture(new File(output, "map-preview-context.png"));
                    assertFalse(((megamek.client.ui.gdx.UiButton) GpuBoardTestUi.stage().getRoot()
                          .findActor("map-view-top")).isChecked(), "Camera presets are actions, not checkboxes");
                    assertFalse(((megamek.client.ui.gdx.UiButton) GpuBoardTestUi.stage().getRoot()
                          .findActor("map-view-isometric")).isChecked());
                    GpuBoardTestUi.click("map-view-top"); settle(view, source.get());
                    assertTrue(view.boardCamera.isTopDown());
                    assertFalse(view.boardCamera.tactical(), "Top view must preserve the renderer mode");
                    var wireframe = (megamek.client.ui.gdx.UiButton) GpuBoardTestUi.stage().getRoot().findActor("utility-wireframe");
                    if (!wireframe.isDisabled()) { GpuBoardTestUi.click("utility-wireframe"); settle(view, source.get()); }
                    for (boolean tactical : new boolean[] { false, true }) {
                        view.boardCamera.setTactical(tactical, source.get().takeFrame().scene());
                        settle(view, source.get());
                        boolean wasWireframe = wireframe.isChecked();
                        rightClick(view, new Coords(5, 3)); settle(view, source.get());
                        GpuBoardTestUi.click("map-view-isometric"); settle(view, source.get());
                        assertEquals(tactical, ((megamek.client.ui.gdx.UiButton) GpuBoardTestUi.stage().getRoot()
                              .findActor("map-view-isometric")).isDisabled(), "Disabled in the Tactical View only");
                        assertEquals(!tactical, view.boardCamera.isIsometric(), "The Tactical View keeps its top-down angle");
                        assertEquals(tactical, view.boardCamera.tactical());
                        assertEquals(wasWireframe, wireframe.isChecked());
                        rightClick(view, new Coords(5, 3)); settle(view, source.get());
                        GpuBoardTestUi.click("map-view-top"); settle(view, source.get());
                        assertTrue(view.boardCamera.isTopDown());
                        assertEquals(tactical, view.boardCamera.tactical());
                        assertEquals(wasWireframe, wireframe.isChecked());
                    }
                    view.boardCamera.setTactical(false, source.get().takeFrame().scene());
                    if (wireframe.isChecked()) { GpuBoardTestUi.click("utility-wireframe"); settle(view, source.get()); }
                    rightClick(view, new Coords(5, 3)); settle(view, source.get());
                    GpuBoardTestUi.click("map-line-of-sight"); settle(view, source.get());
                    assertEquals(InputEvent.ALT_DOWN_MASK, source.get().takeFrame().panels().los().pending());
                    assertEquals(new Coords(5, 3), source.get().takeFrame().panels().los().start().coords());
                    source.get().measure(WOODS, 0, Float.NaN); settle(view, source.get());
                    Actor modifiers = stage.getRoot().findActor("los-modifiers");
                    assertTrue(GpuBoardTestUi.shown(modifiers), "Modifiers are visible with Details collapsed");
                    assertEquals("+1 · target in light woods", ((Label) modifiers).getText().toString());
                    assertTrue(panel.getHeight() <= 160, "A single modifier adds only one compact row");
                    GpuBoardTestUi.capture(new File(output, "map-preview-los-modifiers.png"));
                    GpuBoardTestUi.click("los-flip"); settle(view, source.get());
                    assertFalse(GpuBoardTestUi.shown(modifiers), "Flip refreshes the modifier for the new target");
                    GpuBoardTestUi.click("los-flip"); settle(view, source.get());
                    assertTrue(GpuBoardTestUi.shown(modifiers));
                    stage.setKeyboardFocus(field); field.setText("3"); stage.keyDown(Input.Keys.ENTER);
                    settle(view, source.get());
                    assertFalse(GpuBoardTestUi.shown(modifiers), "Raising the target above woods removes their modifier");
                    assertTrue(panel.getHeight() <= 140, "No empty modifier row remains");
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    view.dispose();
                    Gdx.app.exit();
                }
            }
        }, configuration);
        SwingUtilities.invokeAndWait(() -> source.get().close());
        if (failure.get() != null) { throw new AssertionError("Map preview HUD", failure.get()); }
    }

    private static void rightClick(GpuBattleView view, Coords coords) {
        boardClick(view, coords, Input.Buttons.RIGHT, 0);
    }

    private static void assertHoverInspection(GpuBattleView view, GpuMapSource source) throws Exception {
        Coords clear = new Coords(5, 3);
        List<String> clearCard = List.of("HEX 0604", "Level 0 · grass", "Clear");
        List<String> woodsCard = texts(GpuBoardTestUi.stage().getRoot().findActor("map-hex"));
        hover(view, source, clear);
        assertEquals(clearCard, texts(GpuBoardTestUi.stage().getRoot().findActor("map-hex")),
              "Hover changes both the hex number and terrain after a left-click");

        rightClick(view, clear); settle(view, source);
        Actor menu = GpuBoardTestUi.stage().getRoot().findActor("map-context-menu");
        assertTrue(menu.isVisible());
        hover(view, source, WOODS);
        assertEquals(clearCard, texts(GpuBoardTestUi.stage().getRoot().findActor("map-hex")),
              "The right-clicked hex stays visible while hovering another hex with the menu open");
        Gdx.input.getInputProcessor().keyDown(Input.Keys.ESCAPE);
        Gdx.input.getInputProcessor().keyUp(Input.Keys.ESCAPE);
        settle(view, source);
        assertFalse(menu.isVisible());
        assertNull(source.takeFrame().context());
        assertEquals(woodsCard, texts(GpuBoardTestUi.stage().getRoot().findActor("map-hex")),
              "Escape restores the current hovered hex without another mouse move");

        rightClick(view, clear); settle(view, source);
        boardClick(view, WOODS, Input.Buttons.LEFT, 0); settle(view, source);
        assertFalse(menu.isVisible());
        hover(view, source, clear);
        assertEquals(clearCard, texts(GpuBoardTestUi.stage().getRoot().findActor("map-hex")),
              "Clicking outside the menu restores hover without pinning the clicked hex");

        rightClick(view, clear); settle(view, source);
        GpuBoardTestUi.click("map-view-top"); settle(view, source);
        assertFalse(menu.isVisible());
        assertNull(source.takeFrame().context(), "Choosing an action releases the menu's inspected hex");
        hover(view, source, WOODS);
        assertEquals(woodsCard, texts(GpuBoardTestUi.stage().getRoot().findActor("map-hex")));
    }

    private static void hover(GpuBattleView view, GpuMapSource source, Coords coords) throws Exception {
        var at = GpuNameplates.project(view.boardCamera.camera, BoardGeometry.center(coords, 0));
        assertNotNull(at);
        Gdx.input.getInputProcessor().mouseMoved(Math.round(at.x), Gdx.graphics.getHeight() - Math.round(at.y));
        settle(view, source);
    }

    private static void boardClick(GpuBattleView view, Coords coords, int button, int modifiers) {
        var at = GpuNameplates.project(view.boardCamera.camera, BoardGeometry.center(coords, 0));
        assertNotNull(at);
        int x = Math.round(at.x), y = Gdx.graphics.getHeight() - Math.round(at.y);
        var input = Gdx.input.getInputProcessor();
        GpuBoardTestUi.withModifiers(modifiers, () -> {
            input.mouseMoved(x, y);
            input.touchDown(x, y, 0, button);
            input.touchUp(x, y, 0, button);
        });
    }

    @Test
    void canyonLosCardLeavesTheRayClearInBothViewsAndWhenExpanded() throws Exception {
        AtomicReference<GpuMapSource> source = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Board board = BoardFile.loadSheet(new File("data/boards/Map Pack Savannahs/16x17 Box Canyon (Savannah).board"), false, true);
            Game game = new Game(); game.setBoard(board);
            BoardSceneryLayouts.convert(game.getBoard());
            source.set(new GpuMapSource(game, null, null));
        });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1440, 1000);
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        try {
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override public void create() {
                    var view = new GpuBattleView(source.get());
                    try {
                        view.create(); GpuBoardTestUi.present(view);
                        source.get().measure(new Coords(8, 12), InputEvent.ALT_DOWN_MASK, 8 * BoardGeometry.level());
                        source.get().measure(new Coords(9, 10), 0, 0);
                        settle(view, source.get());
                        assertTrue(source.get().takeFrame().panels().los().clear(), "The canyon's clear level-0 hex must not block");
                        for (boolean iso : new boolean[] { false, true }) {
                            view.boardCamera.setIsometric(iso);
                            view.boardCamera.camera.zoom = .32f;
                            view.boardCamera.center(BoardGeometry.center(new Coords(9, 11), 4));
                            settle(view, source.get());
                            for (boolean details : new boolean[] { false, true }) {
                                if (details) { GpuBoardTestUi.click("los-details"); settle(view, source.get()); }
                                assertRulerClearance(view, source.get());
                                GpuBoardTestUi.capture(new File(output, "canyon-los-" + (iso ? "iso" : "top")
                                      + (details ? "-details" : "") + ".png"));
                            }
                            // Recalculate placement as the ray rotates; expanded details still keep it visible.
                            view.boardCamera.orbit(90, 0); settle(view, source.get());
                            assertRulerClearance(view, source.get());
                            GpuBoardTestUi.click("los-details"); settle(view, source.get());
                        }
                    } catch (Throwable error) { failure.set(error); }
                    finally { view.dispose(); Gdx.app.exit(); }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source.get()::close); }
        if (failure.get() != null) { throw new AssertionError("Canyon LOS clearance", failure.get()); }
    }

    private static void assertRulerClearance(GpuBattleView view, GpuMapSource source) {
        var ruler = source.takeFrame().scene().tactical().ruler();
        Vector2 from = GpuNameplates.project(view.boardCamera.camera, BoardGeometry.center(ruler.start(), ruler.startHeight()));
        Vector2 to = GpuNameplates.project(view.boardCamera.camera, BoardGeometry.center(ruler.end(), ruler.endHeight()));
        assertNotNull(from); assertNotNull(to);
        var stage = GpuBoardTestUi.stage();
        from.scl(stage.getWidth() / Gdx.graphics.getWidth(), stage.getHeight() / Gdx.graphics.getHeight());
        to.scl(stage.getWidth() / Gdx.graphics.getWidth(), stage.getHeight() / Gdx.graphics.getHeight());
        Actor panel = stage.getRoot().findActor("los-panel");
        float gap = GpuLosPanel.LINE_CLEARANCE - .5f;
        Rectangle clearance = new Rectangle(panel.getX() - gap, panel.getY() - gap,
              panel.getWidth() + 2 * gap, panel.getHeight() + 2 * gap);
        assertFalse(Intersector.intersectSegmentRectangle(from, to, clearance), "Panel must leave the whole line and endpoints clear");
        assertTrue(panel.getX() >= 0 && panel.getRight() <= stage.getWidth());
        assertTrue(panel.getY() >= 0 && panel.getTop() <= stage.getHeight());
    }

    /** Lets the source's EDT work land, then draws a few frames with it. */
    private static void settle(GpuBattleView view, GpuMapSource source) throws Exception {
        for (int frame = 0; frame < 3; frame++) {
            SwingUtilities.invokeAndWait(source::refresh);
            view.render();
        }
    }

    /** The non-empty label texts under an actor, in drawing order. */
    private static List<String> texts(Actor actor) {
        List<String> texts = new ArrayList<>();
        if (actor instanceof Label label && !label.getText().isEmpty()) {
            texts.add(label.getText().toString());
        } else if (actor instanceof Group group) {
            group.getChildren().forEach(child -> texts.addAll(texts(child)));
        }
        return texts;
    }
}
