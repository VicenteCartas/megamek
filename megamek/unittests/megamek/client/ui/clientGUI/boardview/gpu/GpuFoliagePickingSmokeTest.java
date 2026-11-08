/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Real canopy intersections and native hover/click/brush routing, with the physical impact query retained. */
@Tag("on-demand")
class GpuFoliagePickingSmokeTest {
    private static final Coords WOODS = new Coords(3, 3);

    @ParameterizedTest
    @CsvSource({ "1, false", "3, false", "1, true", "3, true" })
    void pointerReachesTheBoardBehindShrubsAndTrees(int height, boolean editor) throws Exception {
        Board board = new Board(7, 7);
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                board.setHex(new Coords(x, y), new Hex(0));
            }
        }
        board.setHex(WOODS, new Hex(0, "woods:1;foliage_elev:" + height, ""));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (var fixture = GpuBoardFixture.create(board)) {
            BoardSource source = mock(BoardSource.class);
            when(source.isEditor()).thenReturn(editor);
            when(source.uiPreferences()).thenAnswer(ignored -> fixture.source.uiPreferences());
            when(source.phaseStatus()).thenAnswer(ignored -> fixture.source.phaseStatus());
            when(source.takeFrame()).thenAnswer(ignored -> fixture.source.takeFrame());
            // A mock's default methods return null; the editor view draws the trace overlay every frame.
            when(source.trace()).thenReturn(GpuTraceOverlay.Settings.NONE);
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(1200, 900);
            new Lwjgl3Application(new GpuBattleView(source) {
                private int tick;
                private final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(2);

                @Override
                public void render() {
                    try {
                        assertTrue(System.nanoTime() < deadline, "The native pointer fixture must finish loading and checking input");
                        super.render();
                        if (frames() == 0) { return; }
                        if (++tick == 1) {
                            boardCamera.setPerspective(false);
                            boardCamera.setIsometric(true);
                            boardCamera.camera.zoom = .3f;
                            boardCamera.center(BoardGeometry.center(WOODS, 0));
                        } else {
                            checkPointer(this, source, height, tick != 4);
                            if (tick == 2) {
                                boardCamera.setPerspective(true);
                            } else if (tick == 3) {
                                boardCamera.setPerspective(false);
                                boardCamera.setIsometric(false);
                            } else {
                                Gdx.app.exit();
                            }
                        }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        Gdx.app.exit();
                    }
                }
            }, config);
        }
        if (failure.get() != null) {
            throw new AssertionError("Foliage pointer routing, height=" + height + ", editor=" + editor, failure.get());
        }
    }

    private static void checkPointer(GpuBattleView view, BoardSource source, int height, boolean requireOtherHex) throws Exception {
        var scene = (BoardScene) field(view, "scene");
        var terrain = (GpuTerrain) field(view, "terrain");
        var ui = (GpuBoardHud) field(view, "ui");
        var camera = view.boardCamera.camera;
        Vector3 pointer = canopyPointer(view, scene, terrain, ui, height, requireOtherHex);
        assertNotNull(pointer, "The test must hit real foliage in front of " + (requireOtherHex ? "another hex" : "the ground"));
        int x = (int) pointer.x, y = (int) pointer.y;
        var ray = camera.getPickRay(x, y, 0, 0, camera.viewportWidth, camera.viewportHeight);
        var ground = BoardGeometry.hit(scene, ray, scene.tiles(), BoardGeometry.floor(scene), terrain::tacticalSurface);
        assertNotNull(ground);
        var impact = terrain.hit(scene, ray);
        assertNotNull(impact);
        assertEquals(WOODS, impact.coords(), "Attack effects must still hit the actual canopy");
        assertTrue(impact.distance() < ground.distance(), "The foliage must be in front of the board");
        assertEquals(ground, terrain.selectionHit(scene, ray), "Pointer depth and ownership must come from the board");
        assertEquals(ground.coords(), terrain.pick(scene, ray));

        Input original = Gdx.input;
        Input pointerInput = mock(Input.class);
        when(pointerInput.getX()).thenReturn(x);
        when(pointerInput.getY()).thenReturn(y);
        when(pointerInput.getInputProcessor()).thenReturn(original.getInputProcessor());
        Gdx.input = pointerInput;
        try {
            var processor = original.getInputProcessor();
            long generation = (long) field(view, "boardGeneration");
            clearInvocations(source);
            processor.mouseMoved(x, y);
            assertEquals(ground.coords(), field(view, "hovered"));
            verify(source).setHover(eq(ground.coords()), anyFloat());
            if (source.isEditor()) {
                processor.touchDown(x, y, 0, Input.Buttons.LEFT);
                processor.touchDragged(x, y, 0);
                processor.touchUp(x, y, 0, Input.Buttons.LEFT);
                // The press's editor pointer event (BoardSource.editorPointer) lands on the hex behind the canopy.
                verify(source).editorPointer(eq(ground.coords()), anyDouble(), anyDouble(), eq(false), isNull(),
                      eq(false), any(), eq(false), eq(generation));
                verify(source).endEditorStroke();
            } else {
                // A map preview inspects the hex behind the canopy on a right click.
                processor.touchDown(x, y, 0, Input.Buttons.RIGHT);
                processor.touchUp(x, y, 0, Input.Buttons.RIGHT);
                verify(source).inspect(ground.coords());
            }
        } finally {
            Gdx.input = original;
        }
    }

    private static Vector3 canopyPointer(GpuBattleView view, BoardScene scene, GpuTerrain terrain,
          GpuBoardHud ui, int height, boolean requireOtherHex) {
        var camera = view.boardCamera.camera;
        Vector3 center = BoardGeometry.center(WOODS, height * .75f);
        // Probe the authored canopy, then round to real pointer pixels. Angled views must reproduce the wrong-hex hit.
        for (int dx = -48; dx <= 48; dx += 4) {
            for (int dy = -48; dy <= 48; dy += 4) {
                Vector3 screen = camera.project(new Vector3(center).add(dx, dy, 0), 0, 0,
                      camera.viewportWidth, camera.viewportHeight);
                int x = Math.round(screen.x), y = Gdx.graphics.getHeight() - Math.round(screen.y);
                if (ui.hit(x, y)) { continue; }
                var ray = camera.getPickRay(x, y, 0, 0, camera.viewportWidth, camera.viewportHeight);
                var impact = terrain.hit(scene, ray);
                var ground = BoardGeometry.hit(scene, ray, scene.tiles(), BoardGeometry.floor(scene), terrain::tacticalSurface);
                if (impact != null && ground != null && impact.coords().equals(WOODS)
                      && impact.distance() + 1 < ground.distance()
                      && (!requireOtherHex || !ground.coords().equals(WOODS))
                      && scene.units().stream().noneMatch(unit -> unit.footprint().contains(ground.coords()))) {
                    return new Vector3(x, y, 0);
                }
            }
        }
        return null;
    }
}
