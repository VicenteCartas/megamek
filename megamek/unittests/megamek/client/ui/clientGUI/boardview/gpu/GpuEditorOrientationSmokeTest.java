/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.client.ui.gdx.UiHexSides;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native pointer input through all six sides, document undo, and compass projection through camera changes. */
@Tag("on-demand")
class GpuEditorOrientationSmokeTest {
    @Test void editsHexEdgesAndKeepsNorthOnTheSelectedHex() throws Exception {
        Coords selected = new Coords(2, 2);
        FutureTask<GpuMapSource> setup = new FutureTask<>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(5, 5);
            board.setHex(selected, new Hex(1, "road:1:9", ""));
            editor.game().setBoard(board); editor.pointer(selected, 0, 0, false);
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup); GpuMapSource source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review")); output.mkdirs();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1600, 1000); config.useVsync(false);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 150_000_000_000L;
                int step; long after;
                private GpuTerrain terrain() throws ReflectiveOperationException {
                    var field = GpuBattleView.class.getDeclaredField("terrain"); field.setAccessible(true); return (GpuTerrain) field.get(this);
                }
                private void command(Action action, String target, String value) throws Exception {
                    source.editorCommand(new Command(action, target, value), source.takeFrame().boardGeneration());
                    SwingUtilities.invokeAndWait(() -> { });
                }
                private UiHexSides edges(String terrain) {
                    UiHexSides sides = GpuBoardTestUi.stage().getRoot().findActor("editor-edges-" + terrain);
                    assertNotNull(sides);
                    ScrollPane scroll = GpuBoardTestUi.stage().getRoot().findActor("editor-inspector-scroll");
                    Vector2 at = sides.localToAscendantCoordinates(scroll.getActor(), new Vector2());
                    scroll.scrollTo(at.x, at.y, sides.getWidth(), sides.getHeight(), false, true);
                    scroll.updateVisualScroll(); GpuBoardTestUi.stage().draw();
                    return sides;
                }
                private void click(Actor actor, float x, float y) throws Exception {
                    Vector2 point = actor.localToStageCoordinates(new Vector2(x, y));
                    if (actor instanceof UiHexSides) {
                        assertSame(actor, GpuBoardTestUi.stage().hit(point.x, point.y, true), "The edge is visible for input");
                    }
                    GpuBoardTestUi.stage().stageToScreenCoordinates(point);
                    Gdx.input.getInputProcessor().mouseMoved((int) point.x, (int) point.y);
                    Gdx.input.getInputProcessor().touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
                    Gdx.input.getInputProcessor().touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
                    SwingUtilities.invokeAndWait(() -> { });
                }
                private void side(UiHexSides sides, int side) throws Exception {
                    // Pointer on the visible edge, not its label: N then clockwise through all six exits.
                    float radius = Math.min(sides.getWidth() - 48, sides.getHeight() - 40) / 2;
                    double angle = Math.toRadians(90 - 60 * side);
                    click(sides, sides.getWidth() / 2 + (float) Math.cos(angle) * radius * .8660254f,
                          sides.getHeight() / 2 + (float) Math.sin(angle) * radius * .8660254f);
                }
                private boolean edgePixel(int side, float radius, boolean gold) {
                    Vector3 point = BoardGeometry.center(selected, 0).lerp(BoardGeometry.center(selected.translated(side), 0), radius / 2);
                    if (side == 0) { point.x += BoardGeometry.width() * .12f; } // Keep clear of the north arrow.
                    point.z = BoardTacticalGeometry.floatingZ(source.takeFrame().scene(), selected);
                    Vector2 screen = GpuNameplates.project(boardCamera.camera, point);
                    float scale = (float) Gdx.graphics.getBackBufferWidth() / Gdx.graphics.getWidth();
                    Pixmap pixels = Pixmap.createFromFrameBuffer(Math.round(screen.x * scale) - 1,
                          Math.round(screen.y * scale) - 1, 3, 3);
                    try {
                        for (int x = 0; x < 3; x++) {
                            for (int y = 0; y < 3; y++) {
                                int pixel = pixels.getPixel(x, y), r = pixel >>> 24, g = pixel >>> 16 & 255, b = pixel >>> 8 & 255;
                                if (gold ? r > 230 && g > 180 && g < 240 && b > 60 && b < 125
                                      : r > 80 && r < 160 && g > 235 && b > 175 && b < 230) { return true; }
                            }
                        }
                        return false;
                    } finally { pixels.dispose(); }
                }
                private void drawReady() throws ReflectiveOperationException {
                    do {
                        assertTrue(System.nanoTime() < deadline, "Terrain update timed out");
                        super.render();
                    } while (GpuBoardTestUi.loading(this) || terrain().busy());
                }
                private void cliffStrokes(int mask) throws ReflectiveOperationException {
                    drawReady();
                    GpuBoardTestUi.capture(new File(output, "editor-cliff-strokes-" + mask + ".png"));
                    for (int side = 0; side < 6; side++) {
                        assertEquals((mask & (1 << side)) != 0, edgePixel(side, .85f, false),
                              "Only applied cliff modifiers have a second stroke, side " + side);
                    }
                }
                private void cliffHover(UiHexSides sides, int side) throws ReflectiveOperationException {
                    Label label = (Label) sides.getChildren().get(side);
                    Vector2 point = label.localToStageCoordinates(new Vector2(label.getWidth() / 2, label.getHeight() / 2));
                    GpuBoardTestUi.stage().stageToScreenCoordinates(point);
                    var field = GpuBattleView.class.getDeclaredField("ui"); field.setAccessible(true);
                    GpuBoardHud hud = (GpuBoardHud) field.get(this);
                    assertEquals(side, hud.cliffEdgeAt((int) point.x, (int) point.y));
                    Input original = Gdx.input, pointer = mock(Input.class);
                    when(pointer.getInputProcessor()).thenReturn(original.getInputProcessor());
                    when(pointer.getX()).thenReturn((int) point.x); when(pointer.getY()).thenReturn((int) point.y);
                    Gdx.input = pointer;
                    try {
                        drawReady();
                        GpuBoardTestUi.capture(new File(output, "editor-cliff-hover-" + side + ".png"));
                        for (int direction = 0; direction < 6; direction++) {
                            assertEquals(direction == side, edgePixel(direction, .9f, true), "Hover " + side + ", board edge " + direction);
                        }
                        when(pointer.getX()).thenReturn(0); when(pointer.getY()).thenReturn(0);
                        drawReady();
                        assertEquals(-1, hud.cliffEdgeAt(0, 0));
                        assertFalse(edgePixel(side, .9f, true), "Leaving the control clears the board highlight");
                    } finally { Gdx.input = original; }
                }
                private Vector2 compass() {
                    Group north = GpuBoardTestUi.stage().getRoot().findActor("editor-north");
                    assertTrue(north.isVisible());
                    Vector2 tip = north.localToStageCoordinates(new Vector2(40, 40));
                    Label n = (Label) north.getChildren().peek();
                    Vector2 text = n.localToStageCoordinates(new Vector2(n.getWidth() / 2, n.getHeight() / 2));
                    Vector3 center = BoardGeometry.center(selected, 0);
                    center.z = BoardTacticalGeometry.floatingZ(source.takeFrame().scene(), selected);
                    Vector2 origin = GpuNameplates.project(boardCamera.camera, center);
                    Vector2 expected = GpuNameplates.project(boardCamera.camera, center.cpy().add(0, BoardGeometry.height() / 2, 0));
                    float scale = GpuBoardTestUi.stage().getWidth() / boardCamera.camera.viewportWidth;
                    expected.scl(scale); origin.scl(scale);
                    assertEquals(0, tip.dst(expected), .01f, "The arrow is centered on the north edge");
                    assertTrue(text.cpy().sub(tip).nor().dot(expected.sub(origin).nor()) > .999,
                          "N lies outside the north edge, including orbit and perspective");
                    assertNull(GpuBoardTestUi.stage().hit(tip.x, tip.y, true), "The compass cannot intercept map clicks");
                    return tip;
                }
                @Override public void create() { super.create(); boardCamera.setIsometric(false); }
                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Orientation check timed out at " + step);
                        if (GpuBoardTestUi.loading(this) || terrain().busy() || frames() < after) { return; }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        if (step < 6) {
                            var sides = edges("cliff_top");
                            assertEquals((1 << step) - 1, sides.selected(), source.editorState().message());
                            assertNull(sides.hit(sides.getWidth() / 2, sides.getHeight() / 2, true), "The centre is not an edge");
                            cliffStrokes((1 << step) - 1);
                            cliffHover(edges("cliff_top"), step);
                            side(edges("cliff_top"), step);
                        } else {
                            switch (step) {
                                case 6 -> {
                                    assertEquals(63, source.editorState().property("cliff_top").exits());
                                    assertEquals(63, edges("cliff_top").selected()); compass();
                                    cliffStrokes(63);
                                    super.render(); // Clear the extra HUD draw used to settle scroll coordinates.
                                    GpuBoardTestUi.capture(new File(output, "editor-hex-edges-top.png"));
                                    command(Action.UNDO, "", "");
                                }
                                case 7 -> {
                                    assertEquals(31, edges("cliff_top").selected());
                                    cliffHover(edges("cliff_top"), 2); // Undo clears selection, but leaves the inspector open.
                                    command(Action.COMPONENT, "", "ground");
                                    cliffStrokes(31);
                                    command(Action.REDO, "", "");
                                }
                                case 8 -> {
                                    var sides = edges("cliff_top"); assertEquals(63, sides.selected());
                                    command(Action.COMPONENT, "", "ground");
                                    cliffStrokes(63);
                                    Label north = (Label) edges("cliff_top").getChildren().get(0);
                                    click(north, north.getWidth() / 2, north.getHeight() / 2);
                                }
                                case 9 -> {
                                    assertEquals(62, edges("cliff_top").selected());
                                    boardCamera.setIsometric(true); boardCamera.orbit(120, 0);
                                    boardCamera.setPerspective(true);
                                }
                                case 10 -> {
                                    compass();
                                    cliffHover(edges("cliff_top"), 2);
                                    GpuBoardTestUi.assertHorizontalBounds(GpuBoardTestUi.stage().getRoot().findActor("editor-inspector"),
                                          GpuBoardTestUi.stage().getRoot().findActor("editor-inspector"));
                                    GpuBoardTestUi.capture(new File(output, "editor-hex-edges-orbit.png"));
                                    command(Action.COMPONENT, "", "road");
                                }
                                case 11 -> { assertEquals(9, edges("road").selected()); side(edges("road"), 1); }
                                case 12 -> assertEquals(11, source.editorState().property("road").exits());
                                case 13 -> {
                                    command(Action.COMPONENT, "", "ground");
                                    boardCamera.setPerspective(false); boardCamera.setIsometric(true);
                                    after = frames() + 8; step++;
                                    Gdx.graphics.setWindowedMode(1280, 800); return;
                                }
                                case 14 -> {
                                    edges("cliff_top"); compass();
                                    super.render();
                                    assertEquals(1280, Gdx.graphics.getWidth());
                                    assertEquals(800, Gdx.graphics.getHeight());
                                    GpuBoardTestUi.capture(new File(output, "editor-hex-edges-compact.png"));
                                    Gdx.app.exit(); return;
                                }
                                default -> throw new AssertionError(step);
                            }
                        }
                        step++; after = frames() + 8;
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Editor orientation", failure.get()); }
    }
}
