/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.badlogic.gdx.utils.GdxNativesLoader;
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

/** Repeatable authored-edit CPU timings; deliberately opt-in, with no machine-dependent time assertions. */
@Tag("on-demand")
@Tag("gpu-benchmark")
class GpuAuthoredPerformanceSmokeTest {
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
