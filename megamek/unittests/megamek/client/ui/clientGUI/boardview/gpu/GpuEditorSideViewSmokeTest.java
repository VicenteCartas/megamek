/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.client.ui.gdx.UiButton;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The side view starts fully open at 1280x800, then at 1920x1080 and 1280x800, open and collapsed: its header follows the camera's yaw, the pills stack
 * without overlapping, hovering a shared pill highlights the Layers rows and board outlines of all its objects (and a
 * Layers row its pill), and with a card armed a click on a guide places it on that support and a click in the air at a fixed level.
 * An idle open side view renders no new image and the idle editor reads no installed objects; dragging a shared pill moves every car it stands for in one undo step;
 * a hex with more items than the column holds keeps every pill inside the side view without overlaps.
 */
@Tag("on-demand")
class GpuEditorSideViewSmokeTest {
    @Test void showsPillsHoverAndPlacesByClickAtBothSizes() throws Exception {
        Coords at = new Coords(2, 2);
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(6, 6);
            for (int x = 0; x < board.getWidth(); x++) {
                for (int y = 0; y < board.getHeight(); y++) { board.getHex(x, y).setTheme("lunar"); }
            }
            Hex hex = new Hex(1, "pavement:1;bridge:1:9;bridge_cf:40;bridge_elev:3", "lunar");
            hex.setDecorations(List.of(
                  new BoardDecoration("deck-car", "prop", "scenery/vehicles/car", null, -.15, 0, 0, false, .4,
                        BoardDecoration.Placement.surface("bridge", "deck", 0), 0),
                  new BoardDecoration("deck-car-2", "prop", "scenery/vehicles/car", null, .15, 0, 0, false, .4,
                        BoardDecoration.Placement.surface("bridge", "deck", 0), 0),
                  new BoardDecoration("ground-car", "prop", "scenery/vehicles/car", null, 0, .2, 0, false, .4,
                        BoardDecoration.Placement.ground(), 0)));
            board.setHex(at, hex);
            // A busy hex: twelve differently labelled cars on the ground, more than the pill column holds.
            Hex busy = new Hex(0, "", "lunar");
            busy.setDecorations(java.util.stream.IntStream.range(0, CARS.size()).mapToObj(i -> new BoardDecoration("car-" + i, "prop",
                  "scenery/vehicles/car", null, -.4 + i * .07, 0, 0, false, .3, BoardDecoration.Placement.ground(), 0)
                  .withColours(BoardDecoration.Colours.of(CARS.get(i)))).toList());
            board.setHex(BUSY, busy);
            editor.game().setBoard(board); editor.pointer(at, 0, 0, false);
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 120_000_000_000L;
                int step, renders, reads;
                long nextFrame;

                private GpuTerrain terrain() throws ReflectiveOperationException {
                    var field = GpuBattleView.class.getDeclaredField("terrain"); field.setAccessible(true);
                    return (GpuTerrain) field.get(this);
                }

                private void command(Action action, String value) {
                    source.editorCommand(new Command(action, value), source.takeFrame().boardGeneration());
                }

                private void next(int frames) { step++; nextFrame = frames() + frames; }

                @Override public void create() { super.create(); boardCamera.setIsometric(true); }

                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, () -> "Side view check stalled at step " + step);
                        if (GpuBoardTestUi.loading(this) || terrain().busy() || frames() < nextFrame) { return; }
                        var root = GpuBoardTestUi.stage().getRoot();
                        switch (step) {
                            case 0 -> {
                                assertEquals(1280, Gdx.graphics.getWidth());
                                assertEquals(at.getBoardNum() + " · " + section().looking(), summary(),
                                      "The initial header must describe the open section");
                                assertNotNull(root.findActor("editor-section-reveal"), "The section starts fully open, including Reveal");
                                GpuBoardTestUi.capture(new File(output, "editor-side-view-startup-1280.png"));
                                next(4); Gdx.graphics.setWindowedMode(1920, 1080);
                            }
                            case 1 -> {
                                assertEquals(1920, Gdx.graphics.getWidth());
                                GpuHexSection section = section();
                                assertEquals(at.getBoardNum() + " · " + section.looking(), summary(),
                                      "The open header names the hex and the direction it looks along");
                                assertTrue(section.looking().equals("looking NW"), "The isometric camera looks north-west");
                                assertPills(section, List.of("ground", "ground-car", "bridge", "deck-car"));
                                assertEquals(section.pill("deck-car"), section.pill("deck-car-2"), "Alike items at one level share a pill");
                                hover(section, section.pill("deck-car"));
                                next(2);
                            }
                            case 2 -> {
                                GpuHexSection section = section();
                                assertEquals("deck-car", section.hovered());
                                assertTrue(((UiButton) root.findActor("editor-content-deck-car")).isOver()
                                      && ((UiButton) root.findActor("editor-content-deck-car-2")).isOver(), "The shared pill's Layers rows show the hover");
                                assertEquals(List.of("deck-car", "deck-car-2"), ((GpuMapHud) hud()).editorHoverObjects(),
                                      "The board outlines every object of the hovered shared pill");
                                GpuBoardTestUi.capture(new File(output, "editor-side-view-1920.png"));
                                // From Layers: the bridge row's hover highlights its pill.
                                hover(root.findActor("editor-component-bridge"), null);
                                next(2);
                            }
                            case 3 -> {
                                assertEquals("bridge", highlighted(section()), "The Layers row's item is highlighted in the side view");
                                command(Action.ASSET, "scenery/vehicles/car"); next(4);
                            }
                            case 4 -> {
                                GpuHexSection section = section();
                                hover(section, new Vector2(section.getWidth() / 3, section.y(4) + 3));
                                next(2);
                            }
                            case 5 -> {
                                GpuHexSection section = section();
                                GpuBoardTestUi.capture(new File(output, "editor-side-view-armed.png"));
                                click(section, new Vector2(section.getWidth() / 3, section.y(4) + 3));
                                next(8);
                            }
                            case 6 -> {
                                var placed = placed();
                                assertEquals("bridge", placed.placement().receiver().terrain(), "A click on the deck's guide places on the deck");
                                assertEquals(4, source.editorState().objects().size());
                                GpuHexSection section = section();
                                click(section, new Vector2(section.getWidth() / 3, section.y(6)));
                                next(8);
                            }
                            case 7 -> {
                                var placed = placed();
                                assertEquals("absolute", placed.placement().mode(), "A click in the air places at a fixed level");
                                assertEquals(6, placed.placement().level(), .001);
                                command(Action.UNDO, ""); next(2);
                            }
                            case 8 -> {
                                command(Action.UNDO, ""); command(Action.TOOL, "SELECT"); next(8);
                            }
                            case 9 -> {
                                assertEquals(3, source.editorState().objects().size(), "Each placement is one undo step");
                                GpuBoardTestUi.click("editor-side-view-toggle"); next(4);
                            }
                            case 10 -> {
                                assertNull(root.findActor("editor-hex-section"), "Collapsing removes the section");
                                assertNull(root.findActor("editor-section-reveal"), "Collapsing removes Reveal");
                                assertTrue(root.findActor("editor-side-view").getHeight() < 44, "A collapsed side view is one line");
                                assertTrue(summary().startsWith(at.getBoardNum() + " · Ground L1"), "The collapsed line summarises the hex");
                                GpuBoardTestUi.capture(new File(output, "editor-side-view-collapsed-1920.png"));
                                next(8); Gdx.graphics.setWindowedMode(1280, 800);
                            }
                            case 11 -> {
                                assertEquals(1280, Gdx.graphics.getWidth());
                                assertNull(root.findActor("editor-hex-section"));
                                GpuBoardTestUi.capture(new File(output, "editor-side-view-collapsed-1280.png"));
                                GpuBoardTestUi.click("editor-side-view-toggle"); next(8);
                            }
                            case 12 -> {
                                GpuHexSection section = section();
                                Actor side = root.findActor("editor-side-view");
                                assertTrue(side.getRight() < root.findActor("editor-inspector").getX(), "The side view stays beside the right column");
                                assertTrue(side.getTop() < root.findActor("editor-library").getTop());
                                assertPills(section, List.of("ground", "ground-car", "bridge", "deck-car"));
                                GpuBoardTestUi.capture(new File(output, "editor-side-view-1280.png"));
                                renders = section.renders; reads = editor().installedReads; next(30);
                            }
                            case 13 -> {
                                GpuHexSection section = section();
                                assertEquals(renders, section.renders, "An idle side view renders its image again: " + (section.renders - renders));
                                assertEquals(reads, editor().installedReads, "An idle editor reads the installed objects again");
                                // A shared pill moves every item it stands for: both deck cars rise one level, one undo step.
                                Vector2 pill = section.pill("deck-car");
                                drag(section, pill, new Vector2(pill.x, pill.y + section.y(5) - section.y(4)));
                                next(8);
                            }
                            case 14 -> {
                                assertEquals(List.of(1.0, 1.0), deckCarOffsets(), "Dragging the shared pill moves both cars");
                                assertTrue(source.editorState().canUndo());
                                command(Action.UNDO, ""); next(8);
                            }
                            case 15 -> {
                                assertEquals(List.of(0.0, 0.0), deckCarOffsets(), "The drag was one undo step");
                                assertTrue(!source.editorState().canUndo());
                                source.editorCommand(new Command(Action.SELECT_AT, BUSY.getX() + "," + BUSY.getY(), ""),
                                      source.takeFrame().boardGeneration());
                                next(8);
                            }
                            case 16 -> {
                                GpuHexSection section = section();
                                // Too many for the column: the cars at one level share one pill, which stays inside.
                                List<Vector2> shown = new java.util.ArrayList<>();
                                for (int i = 0; i < CARS.size(); i++) {
                                    Vector2 pill = section.pill("car-" + i);
                                    assertNotNull(pill, "Every car has a pill");
                                    if (!shown.contains(pill)) { shown.add(pill); }
                                }
                                shown.add(section.pill("ground"));
                                shown.sort(java.util.Comparator.comparingDouble(point -> point.y));
                                for (int i = 0; i < shown.size(); i++) {
                                    Vector2 pill = shown.get(i);
                                    assertTrue(pill.y > 9 && pill.y < section.getHeight() - 9, "Each pill lies inside the side view: " + shown);
                                    assertTrue(i == 0 || pill.y >= shown.get(i - 1).y + 17.5f, "No pills overlap: " + shown);
                                }
                                GpuBoardTestUi.capture(new File(output, "editor-side-view-busy-1280.png"));
                                Gdx.app.exit();
                            }
                            default -> throw new AssertionError("Unexpected side view step " + step);
                        }
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }

                private GpuBoardEditor editor() throws ReflectiveOperationException {
                    var field = GpuMapHud.class.getDeclaredField("editor"); field.setAccessible(true);
                    return (GpuBoardEditor) field.get(hud());
                }

                private GpuBoardHud hud() throws ReflectiveOperationException {
                    var field = GpuBattleView.class.getDeclaredField("ui"); field.setAccessible(true);
                    return (GpuBoardHud) field.get(this);
                }

                private List<Double> deckCarOffsets() {
                    return source.editorState().objects().stream().filter(object -> object.id().startsWith("deck-car"))
                          .map(object -> object.placement().offset()).toList();
                }

                /** The object the last placement selected. */
                private BoardDecoration placed() {
                    var state = source.editorState();
                    return state.objects().stream().filter(object -> object.id().equals(state.object())).findFirst().orElseThrow();
                }
            }, configuration());
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Side view failed", failure.get()); }
    }

    private static final Coords BUSY = new Coords(4, 2);
    /** Paints of the busy hex's cars: teal, orange, azure, blue, gray, green, magenta, maroon, mustard, ochre, olive, pink. */
    private static final List<String> CARS = List.of("#248f8a", "#996121", "#245c99", "#142ba6", "#707070", "#14ab1a",
          "#8f2159", "#54120f", "#806b1a", "#73612e", "#7a701a", "#7a3d5c");

    private static GpuHexSection section() {
        var section = (GpuHexSection) GpuBoardTestUi.stage().getRoot().findActor("editor-hex-section");
        assertNotNull(section, "The side view is open");
        return section;
    }

    private static String summary() {
        return ((Label) GpuBoardTestUi.stage().getRoot().findActor("editor-side-view-summary")).getText().toString();
    }

    private static String highlighted(GpuHexSection section) throws ReflectiveOperationException {
        var field = GpuHexSection.class.getDeclaredField("highlight"); field.setAccessible(true);
        return (String) field.get(section);
    }

    /** Each key has a pill inside the widget, in the order of the levels; no two pills overlap. */
    private static void assertPills(GpuHexSection section, List<String> keys) {
        float previous = -Float.MAX_VALUE;
        for (String key : keys) {
            Vector2 pill = section.pill(key);
            assertNotNull(pill, "A pill for " + key);
            assertTrue(pill.x > 0 && pill.x < section.getWidth() && pill.y > 0 && pill.y < section.getHeight(), key + " lies inside the side view");
            assertTrue(pill.y >= previous + 17.5f, key + " stacks above the pill below it without overlapping");
            previous = pill.y;
        }
    }

    /** Moves the mouse to a point of {@code actor}, its centre when {@code local} is null. */
    private static void hover(Actor actor, Vector2 local) {
        var stage = GpuBoardTestUi.stage();
        Vector2 point = stage.stageToScreenCoordinates(actor.localToStageCoordinates(
              local == null ? new Vector2(actor.getWidth() / 2, actor.getHeight() / 2) : local.cpy()));
        Gdx.input.getInputProcessor().mouseMoved(Math.round(point.x), Math.round(point.y));
    }

    /** Presses at {@code from}, drags in steps to {@code to} and releases there, in {@code actor}'s coordinates. */
    private static void drag(Actor actor, Vector2 from, Vector2 to) {
        var stage = GpuBoardTestUi.stage();
        var input = Gdx.input.getInputProcessor();
        Vector2 start = stage.stageToScreenCoordinates(actor.localToStageCoordinates(from.cpy()));
        input.touchDown(Math.round(start.x), Math.round(start.y), 0, Input.Buttons.LEFT);
        Vector2 point = start;
        for (int i = 1; i <= 4; i++) {
            point = stage.stageToScreenCoordinates(actor.localToStageCoordinates(from.cpy().lerp(to, i / 4f)));
            input.touchDragged(Math.round(point.x), Math.round(point.y), 0);
        }
        input.touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
    }

    private static void click(Actor actor, Vector2 local) {
        var stage = GpuBoardTestUi.stage();
        Vector2 point = stage.stageToScreenCoordinates(actor.localToStageCoordinates(local.cpy()));
        var input = Gdx.input.getInputProcessor();
        input.touchDown(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
        input.touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
    }

    private static com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration configuration() {
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1280, 800); return config;
    }
}
