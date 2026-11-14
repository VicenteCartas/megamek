/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Scorch reaches installed paint and prop batches through the ordinary incremental scene update. */
@Tag("on-demand")
class GpuScorchedScenerySmokeTest {
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void authoredAndBlastScorchRemoveInstalledSceneryInBothGraphicsModes(boolean columns) throws Exception {
        var board = BoardScorchTest.board();
        var source = BoardScenes.scene(board);
        var looks = new HashMap<>(board.getHex(BoardScorchTest.AT).getAppearance());
        looks.put("scorch", new HexAppearance("scorch/charred", null, null, null));
        board.getHex(BoardScorchTest.AT).setAppearance(looks);
        var authored = BoardScenes.scene(board);
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(640, 480);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                var terrain = new GpuTerrain();
                try {
                    var camera = new BoardCamera();
                    camera.resize(640, 480);
                    camera.fit(source);
                    terrain.setColumns(columns);
                    terrain.setEditable(true);
                    terrain.update(source);
                    assertTrue(props(terrain, BoardScorchTest.AT) > 0, "The fixture installs traffic controls and a prop");
                    assertTrue(paint(terrain).values().stream().flatMap(List::stream)
                          .anyMatch(stamp -> stamp.owner().equals(BoardScorchTest.AT)));
                    terrain.update(authored);
                    assertCleared(terrain, authored);
                    terrain.update(source);
                    assertTrue(props(terrain, BoardScorchTest.AT) > 0, "Erasing editor scorch restores authored objects");
                    terrain.setEditable(false);
                    terrain.update(source);
                    terrain.scorch(source, new GpuGroundDamage.Scorch(BoardGeometry.center(BoardScorchTest.AT, 0),
                          BoardGeometry.width() * .7f, 0));
                    terrain.update(source);
                    assertCleared(terrain, source);
                    var stable = terrain.presentation(source).tiles();
                    terrain.update(source);
                    assertSame(stable, terrain.presentation(source).tiles());
                    assertFalse(terrain.busy());
                    // A direct-fire scar marks the ground and leaves the hex's dressing; an area round clears it.
                    var landing = new megamek.common.units.UnitLocation(megamek.common.units.Entity.NONE, BoardScorchTest.AT, 0, 0, 0);
                    var artillery = new megamek.common.ResolvedAttack.Shot("", java.util.Set.of(), true, false, 1, 0, true,
                          null, null, landing, true, 20, false, false, false, null, 20);
                    for (var profile : java.util.Arrays.asList(null, artillery)) {
                        for (String effect : List.of("flame", "missile")) {
                            terrain.boardChanged();
                            terrain.update(source);
                            assertTrue(props(terrain, BoardScorchTest.AT) > 0);
                            var point = BoardGeometry.center(BoardScorchTest.AT, 0);
                            var face = GpuGroundDamage.groundBeneath(terrain.tacticalSurface(BoardScorchTest.AT).top(), point.x, point.y);
                            assertNotNull(face);
                            point.z = face.height(point.x, point.y);
                            var attack = GroundDamagePlaybackTest.shot(profile == null ? "ISAC5" : "ISLongTom", false, profile);
                            attack.landscape = ray -> terrain.hit(source, ray);
                            terrain.impact(source, new GpuGroundDamage.Impact(attack, effect, point.cpy().add(0, 0, 100),
                                  point, effect, profile));
                            terrain.groundDamage().upload();
                            terrain.update(source);
                            GpuReviewFrame.settle(terrain, null, source, camera);
                            if (profile == null) {
                                assertTrue(props(terrain, BoardScorchTest.AT) > 0, "A direct-fire scar keeps the hex's props");
                                assertTrue(paint(terrain).values().stream().flatMap(List::stream)
                                      .anyMatch(stamp -> stamp.owner().equals(BoardScorchTest.AT)), "and its decals");
                            } else {
                                assertCleared(terrain, source);
                            }
                        }
                    }
                    terrain.render(camera.camera);
                    terrain.renderTransparent(camera.camera);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Scorched scenery, columns=" + columns, failure.get()); }
    }

    private static void assertCleared(GpuTerrain terrain, BoardScene source) throws Exception {
        assertTrue(terrain.ready(source));
        var shown = terrain.presentation(source);
        assertTrue(shown.tile(BoardScorchTest.AT).features().isEmpty());
        assertEquals(0, props(terrain, BoardScorchTest.AT), "No traffic light or prop remains in installed batches");
        assertTrue(paint(terrain).values().stream().flatMap(List::stream)
              .noneMatch(stamp -> stamp.owner().equals(BoardScorchTest.AT)), "No projected decal remains, regardless of kind");
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            for (Object tile : ((Map<?, ?>) GpuTestSupport.field(chunk, "tileMeshes")).values()) {
                for (Object decal : (List<?>) GpuTestSupport.field(tile, "paint")) {
                    var stamp = (BoardDecals.Stamp) GpuTestSupport.field(decal, "stamp");
                    assertNotEquals(BoardScorchTest.AT, stamp.owner(), "Installed paint meshes release the removed decal");
                }
            }
        }
        assertTrue(props(terrain, BoardScorchTest.FAR) > 0, "Unaffected props still draw");
        assertTrue(paint(terrain).values().stream().flatMap(List::stream)
              .anyMatch(stamp -> stamp.owner().equals(BoardScorchTest.FAR)), "Unaffected decals still draw");
        assertFalse(source.tile(BoardScorchTest.AT).features().isEmpty(), "The game snapshot is not mutated");
    }

    private static int props(GpuTerrain terrain, Coords at) throws Exception {
        int count = 0;
        for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
            for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) {
                if (at.equals(GpuTestSupport.field(prop, "coords"))) { count++; }
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private static Map<Coords, List<BoardDecals.Stamp>> paint(GpuTerrain terrain) throws Exception {
        return (Map<Coords, List<BoardDecals.Stamp>>) GpuTestSupport.field(terrain, "installedPaint");
    }
}
