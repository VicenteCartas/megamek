/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Vector3;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.units.Targetable;
import megamek.common.units.UnitLocation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Weapon fire as the board lights it in play (GpuAttackEffects.lights): a long laser, a PPC and a flamer across calm
 * water at night and over grass, from a low and a top-down camera, and a laser and a PPC onto a unit with another
 * standing beside the beam. A beam must light and mirror along its whole length, not only at its ends (a PPC evenly,
 * not in pools, and round the weapon while it charges), and a flamer's stream along the length it has reached.
 */
@Tag("on-demand")
class GpuBeamLightSmokeTest {
    private static final int WIDTH = 1200, HEIGHT = 900;
    private static final BoardAtmosphere.Settings NIGHT = new BoardAtmosphere.Settings(0, 0, 0,
          BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);
    /** The laser and PPC cross ten hexes; the flamer reaches three. */
    private static final Coords SHOOTER = new Coords(2, 5), TARGET = new Coords(12, 5);
    private static final Coords NOZZLE = new Coords(6, 5), FLAMED = new Coords(9, 5);

    @Test
    void beamsAndFlamerStreamsLightAndMirrorAlongTheirLength() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "beam-light");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(WIDTH, HEIGHT);
        configuration.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(NIGHT);
                var effects = new GpuAttackEffects();
                var batch = new ModelBatch(GpuUnitShader.provider());
                var box = new ModelBuilder().createBox(16, 16, 26,
                      new Material(ColorAttribute.createDiffuse(.6f, .6f, .62f, 1)),
                      VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
                try { review(terrain, frame, effects, batch, box, output); }
                catch (Throwable error) { failure.set(error); }
                finally {
                    effects.dispose(); frame.dispose(); terrain.dispose(); batch.dispose(); box.dispose();
                    Gdx.app.exit();
                }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Beam light", failure.get()); }
    }

    private static void review(GpuTerrain terrain, GpuReviewFrame frame, GpuAttackEffects effects, ModelBatch batch,
          Model box, File output) throws Exception {
        BoardScene lake = board(true), grass = board(false);
        Vector3 middle = BoardGeometry.center(SHOOTER, 0).lerp(BoardGeometry.center(TARGET, 0), .5f);
        BoardCamera low = camera(middle, true, .55f), top = camera(middle, false, .72f);
        var report = new StringBuilder();

        UnitAttack laser = attack("ISLargeLaser", SHOOTER, TARGET, 1);
        List<GpuLavaLighting.Source> laserLights = fire(effects, laser, beamSeconds(laser), low);
        show(terrain, lake);
        terrain.setEffectLights(List.of());
        int[] waterDark = capture(terrain, frame, low, lake, List.of(), batch, output, "water-unlit-low");
        int[] waterDarkTop = capture(terrain, frame, top, lake, List.of(), batch, output, "water-unlit-top");
        terrain.setEffectLights(laserLights);
        int[] waterLaser = capture(terrain, frame, low, lake, List.of(), batch, output, "water-laser-low");
        int[] waterLaserTop = capture(terrain, frame, top, lake, List.of(), batch, output, "water-laser-top");
        UnitAttack ppc = attack("ISPPC", SHOOTER, TARGET, 2);
        List<GpuLavaLighting.Source> ppcLights = fire(effects, ppc, beamSeconds(ppc), low);
        terrain.setEffectLights(ppcLights);
        capture(terrain, frame, low, lake, List.of(), batch, output, "water-ppc-low");
        capture(terrain, frame, top, lake, List.of(), batch, output, "water-ppc-top");
        UnitAttack flamer = attack("ISFlamer", NOZZLE, FLAMED, 3);
        List<GpuLavaLighting.Source> growing = fire(effects, flamer, streamSeconds(flamer, .45f), low);
        terrain.setEffectLights(growing);
        capture(terrain, frame, low, lake, List.of(), batch, output, "water-flamer-growing-low");
        List<GpuLavaLighting.Source> stream = fire(effects, flamer, streamSeconds(flamer, 1), low);
        terrain.setEffectLights(stream);
        capture(terrain, frame, low, lake, List.of(), batch, output, "water-flamer-low");
        capture(terrain, frame, top, lake, List.of(), batch, output, "water-flamer-top");

        show(terrain, grass);
        terrain.setEffectLights(List.of());
        int[] grassDarkTop = capture(terrain, frame, top, grass, List.of(), batch, output, "ground-unlit-top");
        capture(terrain, frame, low, grass, List.of(), batch, output, "ground-unlit-low");
        terrain.setEffectLights(laserLights);
        int[] grassLaserTop = capture(terrain, frame, top, grass, List.of(), batch, output, "ground-laser-top");
        capture(terrain, frame, low, grass, List.of(), batch, output, "ground-laser-low");
        terrain.setEffectLights(ppcLights);
        int[] grassPpcTop = capture(terrain, frame, top, grass, List.of(), batch, output, "ground-ppc-top");
        capture(terrain, frame, low, grass, List.of(), batch, output, "ground-ppc-low");
        var ppcShot = new ResolvedAttack.Shot("", Set.of(), false, false, 1, 0, false, null, null, null, false, 0, true,
              false, false, null, 10);
        UnitAttack charging = attack("ISPPC", SHOOTER, TARGET, 4, ppcShot);
        terrain.setEffectLights(fire(effects, charging, UnitAttack.ANTICIPATION_SECONDS * .9f, low));
        int[] grassChargeTop = capture(terrain, frame, top, grass, List.of(), batch, output, "ground-ppc-charge-top");
        terrain.setEffectLights(stream);
        int[] grassFlamerTop = capture(terrain, frame, top, grass, List.of(), batch, output, "ground-flamer-top");
        capture(terrain, frame, low, grass, List.of(), batch, output, "ground-flamer-low");

        // The laser onto a unit, its near face where the beam strikes, and a second unit beside the beam's middle,
        // across the beam from the camera so the face it turns to the beam shows.
        Vector3 start = beamStart(laserLights), finish = beamEnd(laserLights);
        Vector3 along = finish.cpy().sub(start).nor();
        var target = new ModelInstance(box);
        target.transform.setToTranslation(finish.x + along.x * 8, finish.y + along.y * 8, 13);
        var bystander = new ModelInstance(box);
        Vector3 nearTarget = start.cpy().lerp(finish, .75f);
        nearTarget.z = 0;
        BoardCamera close = camera(nearTarget, true, .3f);
        float away = close.camera.position.y < middle.y ? 1 : -1;
        Vector3 beside = start.cpy().lerp(finish, .5f).add(0, away * BoardGeometry.width() * .25f, 0);
        bystander.transform.setToTranslation(beside.x, beside.y, 13);
        var facing = new Vector3(beside.x, beside.y - away * 8.5f, 12);
        List<ModelInstance> units = List.of(target, bystander);
        terrain.setEffectLights(List.of());
        int[] unitsDark = capture(terrain, frame, close, grass, units, batch, output, "unit-unlit-low");
        terrain.setEffectLights(laserLights);
        int[] unitsLaser = capture(terrain, frame, close, grass, units, batch, output, "unit-laser-low");
        capture(terrain, frame, top, grass, units, batch, output, "unit-laser-top");
        terrain.setEffectLights(ppcLights);
        capture(terrain, frame, close, grass, units, batch, output, "unit-ppc-low");
        terrain.setEffectLights(List.of());
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());

        // The checks follow the captures, so a failing run still leaves them for review.
        float water = BoardGeometry.waterZ(lake.tile(new Coords(7, 5)));
        Vector3 beamMiddle = start.cpy().lerp(finish, .5f);
        double mirrored = brightness(waterLaser, low, mirror(low, beamMiddle, water))
              - brightness(waterDark, low, mirror(low, beamMiddle, water));
        double mirroredTop = brightness(waterLaserTop, top, mirror(top, beamMiddle, water))
              - brightness(waterDarkTop, top, mirror(top, beamMiddle, water));
        double below = brightness(grassLaserTop, top, ground(beamMiddle))
              - brightness(grassDarkTop, top, ground(beamMiddle));
        double side = brightness(unitsLaser, close, facing) - brightness(unitsDark, close, facing);
        report.append(String.format("laser %s to %s, %d lights%n", start, finish, laserLights.size()))
              .append(String.format("mid-beam mirror on water: low +%.1f, top +%.1f; grass below +%.1f;"
                    + " unit beside +%.1f%n", mirrored, mirroredTop, below, side));
        Vector3 nozzle = beamStart(stream), tip = beamEnd(stream), reached = beamEnd(growing);
        double streamMiddle = brightness(grassFlamerTop, top, ground(nozzle.cpy().lerp(tip, .5f)))
              - brightness(grassDarkTop, top, ground(nozzle.cpy().lerp(tip, .5f)));
        report.append(String.format("flamer %s: reached %s at .45, %s at full; grass under its middle +%.1f%n",
              nozzle, reached, tip, streamMiddle));
        // The PPC's ground light between its muzzle glow and its strike, at thirteen points along the beam.
        Vector3 muzzle = beamStart(ppcLights), strike = beamEnd(ppcLights);
        double least = Double.MAX_VALUE, most = 0;
        for (int sample = 0; sample <= 12; sample++) {
            Vector3 point = ground(muzzle.cpy().lerp(strike, .2f + sample * .05f));
            double lit = brightness(grassPpcTop, top, point) - brightness(grassDarkTop, top, point);
            least = Math.min(least, lit);
            most = Math.max(most, lit);
        }
        // A light size beside the weapon, beyond the reach of its charge's glow.
        Vector3 besideGun = ground(muzzle).add(0, BoardGeometry.width() * .35f, 0);
        double charged = brightness(grassChargeTop, top, besideGun) - brightness(grassDarkTop, top, besideGun);
        report.append(String.format("ppc %s to %s: grass under the beam +%.1f to +%.1f, beside it charging +%.1f%n",
              muzzle, strike, least, most, charged));
        System.out.print("BEAM light: " + report);
        Files.writeString(new File(output, "report.txt").toPath(), report);
        assertTrue(mirrored > 6, "A long laser mirrors on calm water at its middle: " + mirrored);
        assertTrue(mirroredTop > 1, "...also seen from straight above: " + mirroredTop);
        assertTrue(below > 1, "A long laser lights the ground under its middle: " + below);
        assertTrue(side > 1, "A unit beside the beam's middle is lit: " + side);
        assertTrue(least > 1 && least > .5 * most, "A PPC lights the ground along its beam evenly, not in pools: +"
              + least + " to +" + most);
        assertTrue(charged > 5, "A charging PPC lights the ground round it, beyond its glow: +" + charged);
        assertTrue(streamMiddle > 1, "A flamer's stream lights the ground along it: " + streamMiddle);
        assertTrue(nozzle.dst(reached) < .7f * nozzle.dst(tip) && nozzle.dst(reached) > .2f * nozzle.dst(tip),
              "A growing stream lights only as far as it reaches");
    }

    /** The attack's weapon lights at a moment of its playback, as the board takes them before its next frame. */
    private static List<GpuLavaLighting.Source> fire(GpuAttackEffects effects, UnitAttack attack, float seconds,
          BoardCamera camera) {
        attack.seconds = seconds;
        effects.update(attack, null, Map.of());
        effects.render(camera.camera);
        return effects.lights();
    }

    /** Halfway through a beam's firing, at full strength. */
    private static float beamSeconds(UnitAttack attack) {
        return (UnitAttack.ANTICIPATION_SECONDS + attack.contactSeconds) / 2;
    }

    /** The flamer's stream when it has gone the given share of its way. */
    private static float streamSeconds(UnitAttack attack, float share) {
        return UnitAttack.ANTICIPATION_SECONDS + share * Math.max(.18f, attack.flightSeconds);
    }

    /** Where a beam or stream starts, as its lights show it. */
    private static Vector3 beamStart(List<GpuLavaLighting.Source> lights) {
        return extreme(lights, true);
    }

    private static Vector3 beamEnd(List<GpuLavaLighting.Source> lights) {
        return extreme(lights, false);
    }

    /** The westmost or eastmost light point: the shots run east along one row. */
    private static Vector3 extreme(List<GpuLavaLighting.Source> lights, boolean west) {
        Vector3 result = null;
        for (var light : lights) {
            for (Vector3 point : endpoints(light)) {
                if (result == null || (west ? point.x < result.x : point.x > result.x)) { result = point; }
            }
        }
        assertTrue(result != null, "The weapon lights the board");
        return result.cpy();
    }

    /** A light's position, and a segment's far end. */
    private static List<Vector3> endpoints(GpuLavaLighting.Source light) {
        var points = new ArrayList<Vector3>();
        points.add(light.position());
        if (light.end() != null) { points.add(light.end()); }
        return points;
    }

    private static UnitAttack attack(String weapon, Coords from, Coords to, long id) {
        return attack(weapon, from, to, id, null);
    }

    /** A shot, with the game's shot profile where given (a PPC's charges only with one). */
    private static UnitAttack attack(String weapon, Coords from, Coords to, long id, ResolvedAttack.Shot shot) {
        var shooter = new BoardScene.Unit(1, -1, "Shooter", new BoardScene.Waypoint(from, 0, 0), null, false, null, 2,
              false);
        var target = new BoardScene.Unit(2, -1, "Target", new BoardScene.Waypoint(to, 0, 0), null, false, null, 2,
              false);
        var result = new ResolvedAttack(new UUID(7, id), ResolvedAttack.Kind.SHOT, new UnitLocation(1, from, 0, 0, 0),
              new UnitLocation(2, to, 0, 0, 0), Targetable.TYPE_ENTITY, 4, weapon, 4, true, null, shot);
        return new UnitAttack(new BoardScene.Combat(result, shooter, target, target.location()));
    }

    /** A low perspective view from the south, across the shots, or the straight-down view. */
    private static BoardCamera camera(Vector3 center, boolean low, float zoom) {
        var camera = new BoardCamera();
        camera.resize(WIDTH, HEIGHT);
        camera.setIsometric(low);
        if (low) {
            camera.setPerspective(true);
            camera.orbit(-45, 20);
        }
        camera.camera.zoom = zoom;
        camera.center(center);
        camera.update();
        return camera;
    }

    private static void show(GpuTerrain terrain, BoardScene scene) {
        terrain.update(scene);
        terrain.animate(.5f, List.of());
    }

    private static int[] capture(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
          List<ModelInstance> objects, ModelBatch batch, File output, String name) {
        if (objects.isEmpty()) { frame.render(terrain, camera, scene); }
        else { frame.render(terrain, camera, scene, objects, batch); }
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

    /** Where calm water at height {@code water} shows the point's mirror image to the camera. */
    private static Vector3 mirror(BoardCamera camera, Vector3 point, float water) {
        var image = new Vector3(point.x, point.y, 2 * water - point.z);
        Vector3 eye = camera.camera.position;
        Vector3 ray = camera.perspective() ? image.cpy().sub(eye) : camera.camera.direction.cpy();
        Vector3 from = camera.perspective() ? eye : image;
        return from.cpy().mulAdd(ray, (water - from.z) / ray.z);
    }

    private static Vector3 ground(Vector3 point) { return new Vector3(point.x, point.y, 0); }

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

    /** Level grass, with a two-deep lake round the shots' row where asked. */
    private static BoardScene board(boolean lake) {
        var image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 84; x++) { image.setRGB(x, y, 0xff6f7a52); }
        }
        var pixels = new BoardScene.Pixels(image);
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 10; y++) {
                boolean water = lake && x >= 1 && x <= 14 && y >= 2 && y <= 8;
                tiles.add(BoardScenes.newTile(new Coords(x, y), 0, water ? 2 : -1, false, 0, BoardScene.Surface.GRASS, List.of(), List.of(),
                      water ? BoardLiquid.WATER : BoardLiquid.NONE, null));
            }
        }
        return new BoardScene(0, 16, 10, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
