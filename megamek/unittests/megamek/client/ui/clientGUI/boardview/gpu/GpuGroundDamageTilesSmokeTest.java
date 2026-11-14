/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Checks the real CPU rasterizer and shader indirection, without constructing 35,000 hexes of terrain geometry. */
@Tag("on-demand")
class GpuGroundDamageTilesSmokeTest {
    @Test
    void longBoardsKeepLocalResolutionAndContinuousTileSeams() throws Exception {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(256, 256);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                try { review(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Sparse ground scars", failure.get()); }
    }

    private static void review() throws Exception {
        var smallScene = flat(50, 50);
        var longScene = flat(50, 700);
        var small = new GpuGroundDamage();
        var large = new GpuGroundDamage();
        try {
            small.update(smallScene);
            large.update(longScene);
            small.upload();
            large.upload();
            assertEquals(0, large.tileCount(), "Untouched ground must allocate no image tiles");
            assertTrue(large.texture().getDepth() < 10, "The long board initially needs only a compact tile directory");
            assertEquals(small.tileWorldSize(), large.tileWorldSize(), "Board size must never lower texels per metre");

            // Both points lie exactly on four mask tiles' shared corner; the second is near the far end of the long board.
            var near = new Vector3(BoardRelief.metres(320), -BoardRelief.metres(320), 0);
            var far = new Vector3(near.x, -BoardRelief.metres(16_320), 0);
            var mark = GroundDamagePlaybackTest.impact("ISAC20", null);
            paint(small, smallScene, mark, near, "same");
            paint(large, longScene, mark, far, "same");
            assertEquals(small.tileCount(), large.tileCount());
            assertTrue(large.tileCount() <= 9, "One impact must touch only its local tiles");
            int[] a = draw(small, near), b = draw(large, far);
            captureCoverage("seam-near", a);
            captureCoverage("seam-far", b);
            int changed = 0, visible = 0;
            for (int i = 0; i < a.length; i++) {
                if ((a[i] >>> 24) > 20) { visible++; }
                for (int shift = 0; shift < 32; shift += 8) {
                    if (Math.abs((a[i] >>> shift & 255) - (b[i] >>> shift & 255)) > 3) { changed++; break; }
                }
            }
            assertTrue(visible > 1000, "The GPU must actually resolve the far-away directory entry and draw the scar");
            assertTrue(changed < a.length / 100, "The same scar must survive relocation along a 50x700 map without changing detail");
            // A dark gouge crosses the exact four-tile join: a missing/cleared border would leave a visible zero stripe.
            for (int y = 124; y <= 131; y++) {
                for (int x = 124; x <= 131; x++) {
                    assertTrue((a[y * 256 + x] >>> 24) > 100, "Tile seams must not cut holes in the image");
                }
            }
            int originalLayers = large.texture().getDepth();
            for (int i = 0; i < 12; i++) {
                paint(large, longScene, mark, far.cpy().add(BoardRelief.metres(24 * (i + 1)), 0, 0), "growth-" + i);
            }
            assertTrue(large.texture().getDepth() > originalLayers);
            assertArrayEquals(b, draw(large, far), "Growing the array must retain older image layers and their directory entries");
            System.out.printf("50x700 scars: %d damaged tiles, %d GPU layers; %d/%d differing pixels after 16km relocation%n",
                  large.tileCount(), large.texture().getDepth(), changed, a.length);
            balancedGouges(small, smallScene, near);
            perpendicularStamps(small, smallScene, near);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally { small.dispose(); large.dispose(); }
    }

    /** Compare actual mask pixels: equal nominal sizes must not hide radically different artwork padding. */
    private static void balancedGouges(GpuGroundDamage damage, BoardScene scene, Vector3 point) {
        long least = Long.MAX_VALUE, most = 0;
        for (String weapon : List.of("ISMediumLaser", "ISLightPPC", "ISAC5")) {
            damage.dispose();
            paint(damage, scene, GroundDamagePlaybackTest.impact(weapon, null), point, "balance");
            long coverage = 0;
            int left = 256, right = -1, top = 256, bottom = -1;
            int[] pixels = draw(damage, point);
            captureCoverage("gouge-" + weapon, pixels);
            for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) {
                int alpha = pixels[y * 256 + x] >>> 24;
                coverage += alpha;
                if (alpha < 64) { continue; }
                left = Math.min(left, x); right = Math.max(right, x);
                top = Math.min(top, y); bottom = Math.max(bottom, y);
            }
            assertTrue(right - left >= 95 && right - left <= 110, weapon + " must span its visible damage-derived length");
            assertTrue(bottom - top >= 17 && bottom - top <= 23, weapon + " must retain the common readable gouge width");
            least = Math.min(least, coverage); most = Math.max(most, coverage);
        }
        assertTrue(most < least * 1.25, "Equal-damage gouges must have comparable visible footprints");
    }

    private static void perpendicularStamps(GpuGroundDamage damage, BoardScene scene, Vector3 point) {
        for (String weapon : List.of("ISMediumLaser", "ISLightPPC", "ISAC5", "ISAC20")) {
            var mark = GroundDamagePlaybackTest.impact(weapon, null);
            float gougeWidth = 0;
            for (float horizontal : new float[] { 1.01f, 0, .99f }) {
                damage.dispose();
                paint(damage, scene, mark, point, "incidence",
                      point.cpy().add(-BoardRelief.metres(10) * horizontal, 0, BoardRelief.metres(10)));
                int left = 256, right = -1, top = 256, bottom = -1, gloss = 0;
                int[] pixels = draw(damage, point);
                for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) {
                    int sample = pixels[y * 256 + x];
                    gloss = Math.max(gloss, sample >>> 8 & 255);
                    if ((sample >>> 24) < 64) { continue; }
                    left = Math.min(left, x); right = Math.max(right, x);
                    top = Math.min(top, y); bottom = Math.max(bottom, y);
                }
                assertTrue(right - left > 20, "The authored impact must be present in the uploaded mask");
                float aspect = (float) (bottom - top) / (right - left);
                if (horizontal <= 1) {
                    assertTrue(aspect > .7f && aspect < 1.4f, weapon + " must stamp its round variant near perpendicular");
                    assertEquals(2f, (right - left) / gougeWidth, .25f,
                          weapon + " blast diameter must be about twice the visible gouge width, allowing for alpha fringes");
                } else {
                    assertTrue(aspect < .3f, weapon + " must retain its long gouge outside the threshold");
                    gougeWidth = bottom - top;
                }
                assertEquals(mark.thermal(), gloss > 0, "Both shapes preserve the thermal or ballistic material");
            }
        }
    }

    /** Show the actual uploaded mask's coverage channel; shaded material captures belong to the ground-damage test. */
    private static void captureCoverage(String name, int[] pixels) {
        if (!Boolean.getBoolean("megamek.gpu.materialMigrationReview")) { return; }
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "damage-tiles");
        assertTrue(output.isDirectory() || output.mkdirs());
        var image = new Pixmap(256, 256, Pixmap.Format.RGBA8888);
        try {
            for (int y = 0; y < 256; y++) {
                for (int x = 0; x < 256; x++) {
                    int coverage = pixels[y * 256 + x] >>> 24;
                    image.drawPixel(x, y, coverage << 24 | coverage << 16 | coverage << 8 | 255);
                }
            }
            PixmapIO.writePNG(new FileHandle(new File(output, name + "-coverage.png")), image);
        } finally { image.dispose(); }
    }

    private static void paint(GpuGroundDamage damage, BoardScene scene, GpuGroundDamage.Impact mark, Vector3 point, String key) {
        paint(damage, scene, mark, point, key, point.cpy().add(-BoardRelief.metres(20), 0, BoardRelief.metres(5)));
    }

    private static void paint(GpuGroundDamage damage, BoardScene scene, GpuGroundDamage.Impact mark, Vector3 point,
          String key, Vector3 origin) {
        damage.impact(scene, new GpuGroundDamage.Impact(mark.attack(), key, origin, point, mark.effect(), mark.shot()),
              GpuGroundDamageTilesSmokeTest::support);
        damage.upload();
    }

    private static BoardTacticalGeometry.Surface support(Coords at) {
        var faces = new ArrayList<BoardSurface.Face>();
        for (int i = 0; i < 6; i++) {
            faces.add(new BoardSurface.Face(BoardGeometry.center(at, 0), BoardGeometry.corner(at, 0, i),
                  BoardGeometry.corner(at, 0, (i + 1) % 6), BoardSurface.Finish.TOP));
        }
        return new BoardTacticalGeometry.Surface(faces, List.of(), faces, List.of(), List.of(), List.of());
    }

    private static BoardScene flat(int width, int height) {
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                tiles.add(BoardScenes.newTile(new Coords(x, y), 0, -1, false, 0, BoardScene.Surface.GRASS,
                      List.of(), List.of()));
            }
        }
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static int[] draw(GpuGroundDamage damage, Vector3 at) {
        var shader = GpuGlsl.compile("Ground scar tiles", """
              in vec3 a_position;
              uniform vec4 u_region;
              out vec2 v_world;
              void main() {
                  gl_Position = vec4(a_position, 1.0);
                  v_world = u_region.xy + a_position.xy * u_region.zw;
              }
              """, "#define GROUND_DAMAGE_FRAGMENT\n" + GpuGroundDamage.shaderSource() + """
              in vec2 v_world;
              out vec4 fragColor;
              void main() { fragColor = groundDamageAt(v_world); }
              """);
        var target = new FrameBuffer(Pixmap.Format.RGBA8888, 256, 256, false);
        var mesh = new Mesh(true, 4, 6, VertexAttribute.Position());
        try {
            mesh.setVertices(new float[] { -1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0 });
            mesh.setIndices(new short[] { 0, 1, 2, 2, 3, 0 });
            target.begin();
            Gdx.gl.glDisable(GL20.GL_BLEND);
            shader.bind();
            damage.texture().bind(0);
            shader.setUniformi("u_groundDamage", 0);
            shader.setUniformf("u_groundDamageSize", damage.width(), damage.height());
            shader.setUniformf("u_groundDamageGrid", damage.tileWorldSize(), damage.tileColumns(), damage.tileRows());
            shader.setUniformf("u_region", at.x, at.y, BoardRelief.metres(8), BoardRelief.metres(8));
            mesh.render(shader, GL20.GL_TRIANGLES);
            var pixels = Pixmap.createFromFrameBuffer(0, 0, 256, 256);
            try {
                int[] result = new int[256 * 256];
                for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) { result[y * 256 + x] = pixels.getPixel(x, y); }
                return result;
            } finally { pixels.dispose(); target.end(); }
        } finally { mesh.dispose(); target.dispose(); shader.dispose(); }
    }
}
