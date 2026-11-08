/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The shared local lights (lava-lighting.glsl) on water: scattered by its body and foam, and mirrored by its surface.
 * Night captures of burning hexes, a fireball, lava and a laser beside calm water, a fireball before a waterfall, and
 * the same lake by day and night without local light.
 */
@Tag("on-demand")
class GpuWaterLocalLightSmokeTest {
    private static final int WIDTH = 1200, HEIGHT = 900;
    private static final BoardAtmosphere.Settings NIGHT = new BoardAtmosphere.Settings(0, 0, 0,
          BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);
    private static final BoardAtmosphere.Settings DAY = new BoardAtmosphere.Settings(13, 0, 0,
          BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);

    @Test
    void localLightsMirrorOnWaterAndLeaveItUnchangedWhenGone() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
              "water-local-light");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(WIDTH, HEIGHT);
        configuration.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(DAY);
                var explosions = new GpuExplosionEffects();
                try { review(terrain, frame, explosions, output); }
                catch (Throwable error) { failure.set(error); }
                finally { explosions.dispose(); frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Local light on water", failure.get()); }
    }

    private static void review(GpuTerrain terrain, GpuReviewFrame frame, GpuExplosionEffects explosions, File output) {
        BoardScene calm = lake(Map.of(), Set.of());
        var overview = new BoardCamera();
        overview.resize(WIDTH, HEIGHT);
        overview.setIsometric(true);
        overview.camera.zoom = .45f;
        overview.center(BoardGeometry.center(new Coords(5, 4), 0));
        overview.update();
        var low = new BoardCamera();
        low.resize(WIDTH, HEIGHT);
        low.setIsometric(true);
        low.setPerspective(true);
        low.orbit(0, 30);
        low.camera.zoom = .3f;
        low.center(BoardGeometry.center(new Coords(5, 4), 0));
        low.update();

        show(terrain, calm);
        capture(terrain, frame, overview, calm, output, "day");
        frame.configure(NIGHT);
        int[] dark = capture(terrain, frame, overview, calm, output, "night");
        capture(terrain, frame, low, calm, output, "night-low");

        // A fireball above the lake, from the effect that lights the board in play.
        BoardScene.Tile under = calm.tile(new Coords(5, 4));
        Vector3 burst = BoardGeometry.center(under.coords(), 0);
        burst.z = BoardGeometry.waterZ(under) + BoardRelief.metres(10);
        explosions.begin();
        explosions.burst(burst, BoardRelief.metres(9), .2f, 1);
        var fireball = new ArrayList<GpuLavaLighting.Source>();
        explosions.lights(fireball);
        assertEquals(1, fireball.size(), "One burning volume, one light");
        terrain.setEffectLights(fireball);
        int[] lit = capture(terrain, frame, overview, calm, output, "night-explosion");
        capture(terrain, frame, low, calm, output, "night-explosion-low");

        // A laser across calm water between two wading units: its light along the beam and its strike, as
        // GpuAttackEffects lights a beam (no beam ribbon or units are drawn here).
        Vector3 laser = GpuAttackEffects.laserLight(GpuAttackEffects.laserTint(null, false));
        BoardScene.Tile shooter = calm.tile(new Coords(3, 4));
        Vector3 muzzle = BoardGeometry.center(shooter.coords(), 0);
        muzzle.z = BoardGeometry.waterZ(shooter) + BoardRelief.metres(9);
        BoardScene.Tile target = calm.tile(new Coords(6, 4));
        Vector3 hit = BoardGeometry.center(target.coords(), 0);
        hit.z = BoardGeometry.waterZ(target) + BoardRelief.metres(6);
        var beam = new ArrayList<GpuLavaLighting.Source>();
        GpuAttackEffects.beamLights(muzzle, hit, .65f, laser, GpuAttackEffects.BEAM_LIGHT,
              GpuAttackEffects.STRIKE_LIGHT, beam);
        terrain.setEffectLights(beam);
        capture(terrain, frame, overview, calm, output, "night-laser");
        capture(terrain, frame, low, calm, output, "night-laser-low");

        terrain.setEffectLights(List.of());
        frame.render(terrain, overview, calm);
        int[] cleared = pixels();

        BoardScene burning = lake(Map.of(new Coords(2, 3), 3, new Coords(2, 4), 2), Set.of());
        show(terrain, burning);
        capture(terrain, frame, overview, burning, output, "night-burning");
        capture(terrain, frame, low, burning, output, "night-burning-low");
        BoardScene volcanic = lake(Map.of(), Set.of(new Coords(2, 3), new Coords(2, 4)));
        show(terrain, volcanic);
        capture(terrain, frame, overview, volcanic, output, "night-lava");
        capture(terrain, frame, low, volcanic, output, "night-lava-low");

        // A fireball before a waterfall's curtain: the falling sheet mirrors it as it does the sun.
        BoardScene falls = BoardScenes.dropScene(BoardScene.Surface.GRASS);
        show(terrain, falls);
        Vector3 plunge = BoardGeometry.center(new Coords(4, 3), 0);
        BoardScene.Tile pool = falls.tile(new Coords(4, 3));
        Vector3 nearCurtain = plunge.cpy().lerp(BoardGeometry.center(new Coords(4, 2), 0), .35f);
        nearCurtain.z = BoardGeometry.waterZ(pool) + BoardRelief.metres(10);
        explosions.begin();
        explosions.burst(nearCurtain, BoardRelief.metres(10), .2f, 2);
        var nearFall = new ArrayList<GpuLavaLighting.Source>();
        explosions.lights(nearFall);
        var fallView = new BoardCamera();
        fallView.resize(WIDTH, HEIGHT);
        fallView.setIsometric(true);
        fallView.setPerspective(true);
        fallView.orbit(0, 25);
        fallView.camera.zoom = .16f;
        fallView.center(plunge);
        fallView.update();
        capture(terrain, frame, fallView, falls, output, "night-waterfall");
        terrain.setEffectLights(nearFall);
        capture(terrain, frame, fallView, falls, output, "night-waterfall-explosion");
        terrain.setEffectLights(List.of());
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());

        // The checks follow the captures, so a failing run still leaves them for review.
        // Calm water reflects the fireball toward the camera: at the light's mirror image on the water it brightens
        // more than at the point as far beyond the light's foot, which receives the same diffuse light.
        Vector3 direction = overview.camera.direction;
        float water = BoardGeometry.waterZ(under);
        Vector3 light = fireball.getFirst().position();
        float run = (light.z - water) / -direction.z;
        Vector3 mirror = new Vector3(light.x - direction.x * run, light.y - direction.y * run, water);
        Vector3 beyond = new Vector3(2 * light.x - mirror.x, 2 * light.y - mirror.y, water);
        double reflected = brightness(lit, overview, mirror) - brightness(dark, overview, mirror);
        double scattered = brightness(lit, overview, beyond) - brightness(dark, overview, beyond);
        System.out.printf("WATER local light: mirror +%.1f, beyond +%.1f%n", reflected, scattered);
        assertTrue(scattered > 1, "The fireball lights the water around it: " + scattered);
        assertTrue(reflected > 2 * scattered, "Its reflection reads on the water: " + reflected + " vs " + scattered);
        assertArrayEquals(dark, cleared, "Without local light the water returns exactly to its unlit look");
    }

    private static void show(GpuTerrain terrain, BoardScene scene) {
        terrain.update(scene);
        terrain.animate(.5f, List.of());
    }

    /** Renders and saves one frame, returning its pixels. */
    private static int[] capture(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
          File output, String name) {
        frame.render(terrain, camera, scene);
        int[] result = pixels();
        GpuReviewFrame.save(new File(output, name + ".png"));
        return result;
    }

    /** The last frame, bottom row first, as the camera projects. */
    private static int[] pixels() {
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, WIDTH, HEIGHT);
        try {
            int[] result = new int[WIDTH * HEIGHT];
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) { result[y * WIDTH + x] = image.getPixel(x, y); }
            }
            return result;
        } finally { image.dispose(); }
    }

    /** Mean red, green and blue sum over a 7 by 7 pixel window at a world point. */
    private static double brightness(int[] pixels, BoardCamera camera, Vector3 point) {
        Vector3 screen = camera.camera.project(point.cpy());
        int cx = Math.round(screen.x), cy = Math.round(screen.y);
        assertTrue(cx >= 3 && cy >= 3 && cx < WIDTH - 3 && cy < HEIGHT - 3, "Sample on screen: " + screen);
        long sum = 0;
        for (int y = cy - 3; y <= cy + 3; y++) {
            for (int x = cx - 3; x <= cx + 3; x++) {
                int pixel = pixels[y * WIDTH + x];
                sum += (pixel >>> 24) + ((pixel >>> 16) & 255) + ((pixel >>> 8) & 255);
            }
        }
        return sum / 49.0;
    }

    /** A two-deep lake on level grass, with burning hexes and molten lava where given. */
    private static BoardScene lake(Map<Coords, Integer> fire, Set<Coords> lava) {
        var image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 84; x++) { image.setRGB(x, y, 0xff6f7a52); }
        }
        var pixels = new BoardScene.Pixels(image);
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < 10; x++) {
            for (int y = 0; y < 8; y++) {
                var at = new Coords(x, y);
                boolean water = x >= 3 && x <= 7 && y >= 2 && y <= 6;
                var liquid = water ? BoardLiquid.WATER
                      : lava.contains(at) ? new BoardLiquid(BoardLiquid.Kind.MAGMA, "", 0) : BoardLiquid.NONE;
                tiles.add(new BoardScene.Tile(at, 0, water ? 2 : -1, false, 0, BoardScene.Surface.GRASS, null, null,
                      List.of(), List.of(), liquid, null, true, BoardRoad.Kind.NONE,
                      fire.containsKey(at) ? new BoardFireSmoke(fire.get(at), 0) : BoardFireSmoke.NONE));
            }
        }
        return new BoardScene(0, 10, 8, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
