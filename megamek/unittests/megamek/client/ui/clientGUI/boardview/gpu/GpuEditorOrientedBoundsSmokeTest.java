/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The installed authored shell, selection box and real pointer handle retain one pose through a turn and rebuild. */
@Tag("on-demand")
class GpuEditorOrientedBoundsSmokeTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void tiltedBuildingBoxAndHandleFollowTheObjectThroughCameraOrbitDragReleaseAndUndo(boolean perspective) throws Exception {
        var at = new Coords(6, 6);
        var stampAt = new Coords(3, 3);
        var setup = new FutureTask<GpuMapSource>(() -> {
            Board board = Board.createEmptyBoard(12, 12);
            board.getHex(stampAt).setDecorations(List.of(new BoardDecoration("emblem", "decal", "decal/emblems/star-league", null,
                  0, 0, 37, true, 1, BoardDecoration.Placement.ground(), 0, false, 23, -31)
                  .withStretch(new BoardDecoration.Stretch(1.3, .8, 1))));
            var editor = new BoardEditorSession(); editor.open(board, null);
            editor.command(new Command(Action.ASSET, "buildings/debug/debug-authored"), null);
            for (var value : new String[][] {{"scale", "2"}, {"rotation", "23"}, {"rotationX", "45"}, {"rotationY", "-31"}}) {
                editor.command(new Command(Action.BRUSH_VALUE, "object:" + value[0], value[1]), null);
            }
            editor.pointer(at, 0, 0, false); editor.finishStroke();
            String id = editor.board().getHex(at).getDecorations().getFirst().id();
            editor.command(new Command(Action.SELECT_AT, "6,6", ""), null);
            editor.command(new Command(Action.SELECT_OBJECT, id), null);
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        var source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        File output = new File("build/gpu-board-review/oriented-selection/" + (perspective ? "perspective" : "orthographic"));
        Files.createDirectories(output.toPath());
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1400, 950); config.setInitialVisible(false);
        try (var previews = mockStatic(GpuEditorTerrainPreviews.class, CALLS_REAL_METHODS)) {
            previews.when(GpuEditorTerrainPreviews::kinds).thenAnswer(ignored -> List.of(source.takeFrame().scene()));
            new Lwjgl3Application(new GpuBattleView(source) {
                long deadline, nextFrame; int step;
                Grip held;
                Vector3 beforeOrbit, beforeRelease;
                String id;
                @Override public void create() { super.create(); deadline = System.nanoTime() + 180_000_000_000L; }
                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, () -> "Oriented selection step " + step + ": " + GpuBoardTestUi.loadingState(this));
                        if (GpuBoardTestUi.loading(this) || GpuBoardTestUi.busy(this) || frames() < nextFrame) { return; }
                        if (step == 0) {
                            id = source.editorState().objects().getFirst().id();
                            assertEquals(45, source.editorState().objects().getFirst().rotationX());
                            assertEquals(-31, source.editorState().objects().getFirst().rotationY());
                            beforeOrbit = attached(this, source, at, id).base();
                            boardCamera.setPerspective(perspective); boardCamera.setFieldOfView(70);
                            boardCamera.center(BoardGeometry.center(at, 2)); boardCamera.zoom(.7f); boardCamera.orbit(60, 0);
                        } else if (step == 1) {
                            held = attached(this, source, at, id);
                            assertTrue(beforeOrbit.epsilonEquals(held.base(), .001f), "Orbiting the camera must not move the attachment");
                            GpuBoardTestUi.capture(new File(output, "before.png"));
                            Vector2 point = screen(this, held.tip());
                            GpuBoardTestUi.withModifiers(0, () -> Gdx.input.getInputProcessor().touchDown(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT));
                            drag(this, held, 20);
                        } else if (step == 2) {
                            assertEquals(43, source.editorState().objects().getFirst().rotation(), 1, "A tilted handle uses the plane through its tip");
                            assertGripFollowsSweep(this, held, attached(this, source, at, id), source.editorState().objects().getFirst().rotation() - 23);
                            drag(this, held, 52);
                        } else if (step == 3) {
                            assertEquals(75, source.editorState().objects().getFirst().rotation(), 1);
                            Grip turned = attached(this, source, at, id);
                            assertGripFollowsSweep(this, held, turned, source.editorState().objects().getFirst().rotation() - 23);
                            beforeRelease = turned.tip();
                            dump(this, id, "held");
                            GpuBoardTestUi.capture(new File(output, "held.png"));
                            Vector2 point = screen(this, swept(held, 52));
                            Gdx.input.getInputProcessor().touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
                        } else if (step == 4) {
                            Grip released = attached(this, source, at, id);
                            dump(this, id, "released");
                            GpuBoardTestUi.capture(new File(output, "released.png"));
                            assertGripPosition(this, beforeRelease, released.tip(), "Release and the sliced rebuild must not snap the handle");
                            source.editorCommand(new Command(Action.UNDO), source.takeFrame().boardGeneration());
                        } else if (step == 5) {
                            source.editorCommand(new Command(Action.SELECT_AT, "6,6", ""), source.takeFrame().boardGeneration());
                            source.editorCommand(new Command(Action.SELECT_OBJECT, id), source.takeFrame().boardGeneration());
                        } else if (step == 6) {
                            assertEquals(23, source.editorState().objects().getFirst().rotation(), .001, "One undo restores the complete turn");
                            attached(this, source, at, id);
                            source.editorCommand(new Command(Action.SELECT_AT, "3,3", ""), source.takeFrame().boardGeneration());
                            source.editorCommand(new Command(Action.SELECT_OBJECT, "emblem"), source.takeFrame().boardGeneration());
                            boardCamera.center(BoardGeometry.center(stampAt, 0));
                        } else if (step == 7) {
                            held = attached(this, source, stampAt, "emblem");
                            GpuBoardTestUi.capture(new File(output, "decal-before.png"));
                            Vector2 point = screen(this, held.tip());
                            GpuBoardTestUi.withModifiers(0, () -> Gdx.input.getInputProcessor().touchDown(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT));
                            drag(this, held, 90);
                        } else if (step == 8) {
                            assertEquals(127, source.editorState().objects().getFirst().rotation(), 1, "The projected decal turns with its handle");
                            Grip turned = attached(this, source, stampAt, "emblem");
                            assertGripFollowsSweep(this, held, turned, source.editorState().objects().getFirst().rotation() - 37);
                            beforeRelease = turned.tip();
                            Vector2 point = screen(this, swept(held, 90));
                            Gdx.input.getInputProcessor().touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
                        } else {
                            assertGripPosition(this, beforeRelease, attached(this, source, stampAt, "emblem").tip(), "Decal release keeps the same attachment");
                            GpuBoardTestUi.capture(new File(output, "decal-released.png"));
                            Gdx.app.exit();
                        }
                        step++; nextFrame = frames() + 8; deadline = System.nanoTime() + 120_000_000_000L;
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Oriented selection and rotation handle", failure.get()); }
    }

    private record Grip(Vector3 base, Vector3 tip, Vector3 pivot) { }

    private static void dump(GpuBattleView view, String id, String label) throws Exception {
        var field = GpuBattleView.class.getDeclaredField("terrain"); field.setAccessible(true);
        Object terrain = field.get(view);
        field = GpuTerrain.class.getDeclaredField("chunks"); field.setAccessible(true);
        for (Object chunk : (Iterable<?>) field.get(terrain)) {
            var props = chunk.getClass().getDeclaredField("props"); props.setAccessible(true);
            for (Object prop : (Iterable<?>) props.get(chunk)) {
                var identity = prop.getClass().getDeclaredField("decorationId"); identity.setAccessible(true);
                if (!id.equals(identity.get(prop))) { continue; }
                StringBuilder line = new StringBuilder("BOUNDS " + label);
                for (String name : List.of("coords", "editorBounds", "decorationFeature", "previewFeature")) {
                    var value = prop.getClass().getDeclaredField(name); value.setAccessible(true);
                    Object data = value.get(prop);
                    if (data instanceof BoardScene.Feature feature) { data = feature.decoration().rotation() + " / base " + feature.elevation(); }
                    line.append(' ').append(name).append('=').append(data);
                }
                System.out.println(line);
            }
        }
    }

    private static Grip attached(GpuBattleView view, GpuMapSource source, Coords at, String id) throws Exception {
        var field = GpuBattleView.class.getDeclaredField("terrain"); field.setAccessible(true);
        var drawn = ((GpuTerrain) field.get(view)).editorObjects(at).stream().filter(o -> o.id().equals(id)).findFirst().orElseThrow();
        var object = source.editorState().objects().getFirst();
        var axes = new Matrix4().rotate(Vector3.Z, (float) object.rotation()).rotate(Vector3.Y, (float) object.rotationY())
              .rotate(Vector3.X, (float) object.rotationX()).scale(object.mirror() ? -1 : 1, 1, 1);
        Vector3 axisX = new Vector3(Vector3.X).rot(axes), axisY = new Vector3(Vector3.Y).rot(axes), axisZ = new Vector3(Vector3.Z).rot(axes);
        if (drawn.decal()) { axisX.z = 0; axisY.z = 0; axisZ.set(Vector3.Z); }
        axisX.nor(); axisY.nor(); axisZ.nor();
        var box = drawn.outline();
        Vector3 origin = box.getCorner000(new Vector3());
        assertTrue(axisX.epsilonEquals(box.getCorner100(new Vector3()).sub(origin).nor(), .0001f), "Box X follows the full pose");
        assertTrue(axisY.epsilonEquals(box.getCorner010(new Vector3()).sub(origin).nor(), .0001f), "Box Y follows the full pose");
        if (!drawn.decal()) { assertTrue(axisZ.epsilonEquals(box.getCorner001(new Vector3()).sub(origin).nor(), .0001f), "Box Z tilts with the building"); }
        var method = GpuBattleView.class.getDeclaredMethod("rotationHandle"); method.setAccessible(true);
        Object handle = method.invoke(view); assertNotNull(handle);
        var grip = new Grip(component(handle, "base"), component(handle, "tip"), component(handle, "pivot"));
        Vector3 local = new Vector3(grip.base()).mul(new Matrix4(box.getTransform()).inv());
        Vector3 centre = box.getBounds().getCenter(new Vector3());
        assertEquals(box.getBounds().max.x, local.x, .002, "Handle starts on the box's +X side");
        assertEquals(centre.y, local.y, .002); assertEquals(centre.z, local.z, .002);
        assertTrue(new Vector3(grip.tip()).sub(grip.base()).nor().epsilonEquals(axisX, .0001f), "The stem follows the object, not the camera");
        return grip;
    }

    private static Vector3 component(Object record, String name) throws Exception {
        var method = record.getClass().getDeclaredMethod(name); method.setAccessible(true);
        return new Vector3((Vector3) method.invoke(record));
    }

    private static Vector3 swept(Grip grip, float degrees) {
        return new Vector3(grip.tip()).sub(grip.pivot()).rotate(Vector3.Z, degrees).add(grip.pivot());
    }

    private static void assertGripFollowsSweep(GpuBattleView view, Grip original, Grip turned, double degrees) {
        assertGripPosition(view, swept(original, (float) degrees), turned.tip(), "The grip must stay on the pointer's rotation arc");
    }

    private static void assertGripPosition(GpuBattleView view, Vector3 expected, Vector3 actual, String message) {
        float drift = screen(view, expected).dst(screen(view, actual));
        assertTrue(drift < .75f, () -> message + "; drift was " + drift + " pixels");
    }

    private static void drag(GpuBattleView view, Grip grip, float degrees) {
        Vector2 point = screen(view, swept(grip, degrees));
        Gdx.input.getInputProcessor().touchDragged(Math.round(point.x), Math.round(point.y), 0);
    }

    private static Vector2 screen(GpuBattleView view, Vector3 world) {
        var camera = view.boardCamera.camera;
        Vector3 point = camera.project(new Vector3(world), 0, 0, camera.viewportWidth, camera.viewportHeight);
        return new Vector2(point.x, Gdx.graphics.getHeight() - point.y);
    }
}
