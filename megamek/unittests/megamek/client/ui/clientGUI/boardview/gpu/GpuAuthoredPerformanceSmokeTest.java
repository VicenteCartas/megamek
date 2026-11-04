/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.utils.GdxNativesLoader;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.board.Board;
import megamek.common.board.BuildingVolumes;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Repeatable authored-edit CPU and native frame timings; opt-in, with no machine-dependent time assertions. */
@Tag("on-demand")
@Tag("gpu-benchmark")
class GpuAuthoredPerformanceSmokeTest {
    @Test void timesNativePlacementFrames() throws Exception {
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession(); editor.open(Board.createEmptyBoard(32, 28), null);
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        var source = setup.get();
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1200, 900);
        config.setInitialVisible(false); config.useVsync(false); config.setForegroundFPS(60);
        var assets = List.of("buildings/debug/debug-authored", "buildings/authored/hpg-headquarters",
              "buildings/authored/xenobiology-station");
        var positions = List.of(new Coords(8, 6), new Coords(14, 18), new Coords(26, 6));
        Path output = Path.of("build/gpu-board-review/authored-buildings/placement-"
              + System.getProperty("megamek.gpu.authoredRun", "review") + ".jfr");
        try (var recording = new Recording(Configuration.getConfiguration("profile"));
              var previews = org.mockito.Mockito.mockStatic(GpuEditorTerrainPreviews.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            previews.when(GpuEditorTerrainPreviews::kinds).thenAnswer(ignored -> List.of(source.takeFrame().scene()));
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 180_000_000_000L;
                long warm, started, first, revision, previousFrame;
                double maximum, interval, ready;
                int index, phase;
                @Override public void render() {
                    try {
                        long before = System.nanoTime();
                        if (phase > 0 && previousFrame != 0) { interval = Math.max(interval, millis(previousFrame)); }
                        previousFrame = before;
                        super.render();
                        double elapsed = millis(before);
                        if (phase > 0) {
                            maximum = Math.max(maximum, elapsed);
                            if (elapsed > 100) { System.out.printf("AUTHORED stall asset=%s phase=%d frame=%.1fms%n", assets.get(index), phase, elapsed); }
                            var pointer = boardCamera.camera.project(BoardGeometry.center(positions.get(index), 0));
                            Gdx.input.getInputProcessor().mouseMoved(Math.round(pointer.x), Gdx.graphics.getHeight() - Math.round(pointer.y));
                        }
                        assertTrue(System.nanoTime() < deadline, "Authored placement stalled at " + index + "/" + phase);
                        if (GpuBoardTestUi.loading(this) || GpuBoardTestUi.busy(this)) { return; }
                        if (warm == 0) {
                            // Palette category thumbnails are startup work, independent of the measured placement.
                            var samples = field(field(field(this, "ui"), "editor"), "samples");
                            if (field(samples, "current") != null || !((Map<?, ?>) field(samples, "queue")).isEmpty()) { return; }
                        }
                        if (warm == 0) { warm = frames() + 60; return; }
                        if (frames() < warm) { return; }
                        var at = positions.get(index);
                        if (phase == 0) {
                            if (index == 0) { recording.start(); }
                            boardCamera.center(BoardGeometry.center(at, 1)); boardCamera.camera.zoom = index == 1 ? 1 : .3f;
                            source.editorCommand(new Command(Action.ASSET, assets.get(index)), source.takeFrame().boardGeneration());
                            source.editorHover(at, 0, 0, "ground");
                            started = System.nanoTime(); first = frames(); maximum = interval = 0; phase = 1;
                        } else if (phase == 1) {
                            var preview = field(this, "editorPreview");
                            if (frames() < first + 15 || source.editorPreview().routes().isEmpty()
                                  || field(preview, "comparing") != null || field(preview, "built") != source.editorPreview()) { return; }
                            System.out.printf("AUTHORED preview asset=%s elapsed=%.1fms max-frame=%.1fms%n", assets.get(index), millis(started), maximum);
                            revision = source.editorState().revision(); first = frames(); maximum = interval = ready = 0;
                            started = System.nanoTime(); previousFrame = 0; phase = 2;
                            source.editorPointer(at, 0, 0, false, source.takeFrame().boardGeneration()); source.endEditorStroke();
                        } else {
                            if (source.editorState().revision() == revision) { return; }
                            var installed = (BoardScene) field(field(this, "terrain"), "coverScene");
                            if (installed == null || installed.tiles().stream().flatMap(t -> t.features().stream())
                                  .noneMatch(f -> assets.get(index).equals(f.asset()) && GpuAuthoredBuilding.supports(f))) { return; }
                            if (ready == 0) { ready = millis(started); }
                            if (frames() < first + 45 || millis(started) < 3000) { return; }
                            assertTrue(source.takeFrame().scene().tiles().stream().flatMap(t -> t.features().stream())
                                  .anyMatch(f -> assets.get(index).equals(f.asset()) && GpuAuthoredBuilding.supports(f)));
                            System.out.printf("AUTHORED placed asset=%s ready=%.1fms observation=%.1fms max-render=%.1fms max-frame-interval=%.1fms%n",
                                  assets.get(index), ready, millis(started), maximum, interval);
                            phase = 0; index++;
                            if (index == assets.size()) { Gdx.app.exit(); }
                        }
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, config);
            if (recording.getState() == jdk.jfr.RecordingState.RUNNING) {
                Files.createDirectories(output.getParent()); recording.stop(); recording.dump(output);
            }
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Native authored placement timing", failure.get()); }
    }

    private static Object field(Object object, String name) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object); }
            catch (NoSuchFieldException ignored) { /* Inspect the view's superclass. */ }
        }
        throw new NoSuchFieldException(name);
    }

    @Test void timesPaintHoverPublication() throws Exception {
        try (var recording = new Recording(Configuration.getConfiguration("profile"))) {
            recording.start();
            SwingUtilities.invokeAndWait(() -> {
                var editor = new BoardEditorSession(); editor.open(Board.createEmptyBoard(32, 24), null);
                editor.command(new Command(Action.ASSET, "buildings/authored/hpg-headquarters"), null);
                var source = new GpuMapSource(editor.game(), null, editor);
                try {
                    for (int i = 0; i < 12; i++) {
                        source.editorHover(new Coords(13 + i % 3, 10), 0, 0, "ground");
                        long start = System.nanoTime(); source.refresh(); source.takeFrame();
                        System.out.printf("PERF authored paint-publication run=%d time=%.2fms%n", i, millis(start));
                        assertFalse(source.editorPreview().routes().isEmpty());
                    }
                    Coords at = new Coords(13, 10);
                    editor.pointer(at, 0, 0, false); editor.finishStroke();
                    editor.command(new Command(Action.TOOL, "SELECT"), null);
                    editor.command(new Command(Action.SELECT_AT, "13,10", ""), null);
                    editor.command(new Command(Action.SELECT_OBJECT, editor.board().getHex(at).getDecorations().getFirst().id()), null);
                    source.refresh();
                    for (int i = 0; i < 12; i++) {
                        long start = System.nanoTime();
                        editor.command(new Command(Action.OBJECT_VALUE, "rotation", Integer.toString(i * 3)), null, true);
                        source.refresh(); source.takeFrame();
                        System.out.printf("PERF authored rotation-publication run=%d time=%.2fms%n", i, millis(start));
                    }
                } finally { source.close(); }
            });
            Path output = Path.of("build/gpu-board-review/authored-buildings/publication.jfr");
            Files.createDirectories(output.getParent()); recording.stop(); recording.dump(output);
        }
    }

    @Test void timesPlacementDragRotationAndMeshPreparation() throws Exception {
        GdxNativesLoader.load();
        try (var recording = new Recording(Configuration.getConfiguration("profile"))) {
            recording.start();
            for (boolean edge : List.of(false, true)) {
                var editor = new BoardEditorSession();
                editor.open(Board.createEmptyBoard(32, 24), null);
                Coords at = new Coords(edge ? 0 : 13, 10);
                editor.command(new Command(Action.ASSET, "buildings/authored/hpg-headquarters"), null);
                for (int repeat = 0; repeat < 4; repeat++) {
                    long start = System.nanoTime();
                    if (repeat == 0) { editor.pointer(at, 0, 0, false); editor.finishStroke(); }
                    else { editor.command(new Command(Action.OBJECT_VALUE, "rotation", Integer.toString(repeat * 11)), null); }
                    double edit = millis(start);
                    var object = editor.board().getHex(at).getDecorations().getFirst();
                    editor.command(new Command(Action.TOOL, "SELECT"), null);
                    editor.command(new Command(Action.SELECT_AT, at.getX() + "," + at.getY(), ""), null);
                    editor.command(new Command(Action.SELECT_OBJECT, object.id()), null);
                    start = System.nanoTime();
                    for (int i = 0; i < 20; i++) {
                        BuildingVolumes.onBoardFraction(at, object.transform(i * .01, 0, object.rotation(), false, 1, object.placement()), 32, 24);
                    }
                    double validation = millis(start) / 20;
                    start = System.nanoTime();
                    editor.pointer(at, 0, 0, false, object.id());
                    for (int i = 0; i < 20; i++) { editor.pointer(at, i * .005, 0, true, object.id()); }
                    double drag = millis(start) / 20;
                    start = System.nanoTime(); editor.finishStroke(); double commit = millis(start);
                    BoardScene scene = new BoardScene(0, 32, 24, BoardTiles.captureAll(editor.board()), List.of(), List.of(), -1, "", List.of());
                    var groups = scene.tiles().stream().filter(t -> t.features().stream().anyMatch(GpuAuthoredBuilding::supports))
                          .collect(Collectors.groupingBy(t -> new Coords(t.coords().getX() / TerrainLod.CHUNK_SIZE, t.coords().getY() / TerrainLod.CHUNK_SIZE)));
                    start = System.nanoTime();
                    int slices = 0, vertices = 0;
                    for (var tiles : groups.values()) {
                        Map<GpuAuthoredBuilding.Layout, GpuAuthoredBuilding.Prepared> prepared = GpuAuthoredBuilding.prepare(scene, tiles);
                        slices += prepared.size();
                        for (var part : prepared.values()) {
                            for (var mesh : part.levels().getFirst().meshes) { vertices += mesh.vertices.length / RigidGlb.STRIDE; }
                        }
                    }
                    double meshes = millis(start);
                    assertTrue(slices > 0); assertTrue(vertices > 0);
                    System.out.printf("PERF authored edge=%s run=%d edit=%.2fms validation=%.2fms drag=%.2fms commit=%.2fms meshes=%.2fms slices=%d vertices=%d%n",
                          edge, repeat, edit, validation, drag, commit, meshes, slices, vertices);
                }
            }
            Path output = Path.of("build/gpu-board-review/authored-buildings/performance.jfr");
            Files.createDirectories(output.getParent()); recording.stop(); recording.dump(output);
        }
    }

    private static double millis(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
}
