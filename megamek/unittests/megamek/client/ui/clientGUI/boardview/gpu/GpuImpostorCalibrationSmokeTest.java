/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.common.Configuration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Where the game switches a plant to its impostor (TreeLod's smallest threshold), the impostor looks as the far mesh it
 * replaces: in the board's own frame (lit, shadowed and composited, every plant alone in its hex), at 8, 13 and 17 h,
 * from above and from the isometric view, it covers the pixels the far mesh covers and is as bright, on average over
 * those views. With {@code -Dmegamek.gpu.impostorCalibration=FILE} it writes instead, for each plant file, the
 * impostor's alpha cutoff at which the two cover the same (a winter form shares its plant file's), and for each
 * impostor the ratio of the far mesh's colour to its own, which mm-data's tools/calibrate_impostors.py writes into the
 * GLBs and their atlases.
 */
@Tag("on-demand")
class GpuImpostorCalibrationSmokeTest {
    private static final float SWITCH = 24;
    private static final int SPACING = 3, ROW = 12, CROP = 48;
    private static final float[] HOURS = { 8, 13, 17 };
    private static final String[] VIEWS = { "top", "iso" };
    /** The cutoffs the calibration tries; coverage falls as the cutoff rises. */
    private static final float[] CUTOFFS = { .1f, .2f, .3f, .4f, .5f, .6f, .7f };

    @Test
    void impostorsLookAsTheirFarMeshesAtTheSwitch() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1920, 1080);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var manager = new GpuShaderManager();
                try {
                    manager.run(GpuImpostorCalibrationSmokeTest::measure);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    GpuTreeInstances.Stand.forced = -1;
                    manager.close();
                    Gdx.app.exit();
                }
            }
        }, configuration);
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }

    /** A plant's place in the scene, and the sums over the views that compare its far mesh and impostor. */
    private static final class Plant {
        final String name;
        final File file;
        Vector3 centre;
        float diameter;
        double coverage;
        int views;
        /** Its impostor material's alpha test, on the models the terrains share. */
        FloatAttribute impostor;
        /** The far mesh's and the impostor's linear RGB summed over the pixels both cover, in every view. */
        final double[] mesh = new double[3], card = new double[3];
        final double[] swept = new double[CUTOFFS.length];

        Plant(String name, File file) {
            this.name = name;
            this.file = file;
        }
    }

    private static void measure() {
        File root = new File(Configuration.dataDir(), "models/board");
        List<Plant> plants = plants(root);
        BoardScene scene = scene(plants);
        var frame = new GpuReviewFrame(settings(13));
        // The first terrain owns the models the others borrow, so it is disposed last.
        GpuTerrain owner = terrain(null, scene, frame), calibrated = null;
        String target = System.getProperty("megamek.gpu.impostorCalibration");
        try {
            place(owner, plants);
            GpuAssets assets = (GpuAssets) field(owner, "assets");
            // Each form's impostor material: a winter form kept in its plant's file has a model of its own.
            for (Plant plant : plants) { plant.impostor = alphaTest(assets.lodModel(plant.name, 3).getMaterial("impostor")); }
            Map<File, Float> cutoffs = new TreeMap<>();
            if (target != null) {
                // Each cutoff on the shared models; a terrain's trees copy their materials, so each takes a new one.
                for (int step = 0; step < CUTOFFS.length; step++) {
                    for (Plant plant : plants) { plant.impostor.value = CUTOFFS[step]; }
                    GpuTerrain trial = terrain(owner, scene, frame);
                    try {
                        for (float hour : HOURS) {
                            frame.configure(settings(hour));
                            for (String view : VIEWS) {
                                for (Plant plant : plants) {
                                    BoardCamera camera = camera(scene, plant, view);
                                    int[] reference = crop(trial, frame, camera, scene, TreeLod.LEVELS);
                                    plant.swept[step] += Math.log(Math.max(covered(crop(trial, frame, camera, scene, 3), reference), 1)
                                          / (double) Math.max(covered(crop(trial, frame, camera, scene, 2), reference), 1));
                                }
                            }
                        }
                    } finally {
                        trial.dispose();
                    }
                }
                // One cutoff per file: its forms share the material.
                Map<File, double[]> curves = new LinkedHashMap<>();
                for (Plant plant : plants) {
                    double[] curve = curves.computeIfAbsent(plant.file, file -> new double[CUTOFFS.length]);
                    for (int step = 0; step < CUTOFFS.length; step++) { curve[step] += plant.swept[step]; }
                }
                curves.forEach((file, curve) -> cutoffs.put(file, fit(curve)));
                for (Plant plant : plants) { plant.impostor.value = cutoffs.get(plant.file); }
                calibrated = terrain(owner, scene, frame);
            }
            GpuTerrain terrain = calibrated != null ? calibrated : owner;
            for (float hour : HOURS) {
                frame.configure(settings(hour));
                for (String view : VIEWS) {
                    for (Plant plant : plants) {
                        BoardCamera camera = camera(scene, plant, view);
                        compare(plant, crop(terrain, frame, camera, scene, 2), crop(terrain, frame, camera, scene, 3),
                              crop(terrain, frame, camera, scene, TreeLod.LEVELS));
                    }
                }
            }
            report(plants, cutoffs, target);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        } finally {
            frame.dispose();
            if (calibrated != null) { calibrated.dispose(); }
            owner.dispose();
        }
    }

    /**
     * One view: the pixels the far mesh and the impostor cover (differ from the frame without them, whose shadow map
     * still holds the plant at its own level, as the game's does when it switches), and their colours where both cover,
     * in linear RGB.
     */
    private static void compare(Plant plant, int[] far, int[] impostor, int[] reference) {
        int farCovers = covered(far, reference);
        if (farCovers == 0) { return; }
        plant.coverage += Math.log(Math.max(covered(impostor, reference), 1) / (double) farCovers);
        plant.views++;
        // Pooled over every view: a view where both show only a few pixels weighs as little.
        for (int i = 0; i < reference.length; i++) {
            if (!differs(far[i], reference[i]) || !differs(impostor[i], reference[i])) { continue; }
            for (int c = 0; c < 3; c++) {
                plant.mesh[c] += GpuColor.linear((far[i] >> 16 - 8 * c & 255) / 255f);
                plant.card[c] += GpuColor.linear((impostor[i] >> 16 - 8 * c & 255) / 255f);
            }
        }
    }

    private static void report(List<Plant> plants, Map<File, Float> cutoffs, String target) {
        List<String> errors = new ArrayList<>();
        for (Plant plant : plants) {
            assertTrue(plant.views > 0 && luma(plant.mesh) > 0, plant.name + " shows in no view");
            double coverage = Math.exp(plant.coverage / plant.views), brightness = luma(plant.card) / luma(plant.mesh);
            System.out.printf(Locale.ROOT, "Impostor %s: covers %.2f of its far mesh, %.2f as bright%n", plant.name, coverage, brightness);
            // A winter form shares its plant file's cutoff, so the two forms' coverages can only meet about it.
            if (coverage < .8 || coverage > 1.25 || brightness < .88 || brightness > 1.12) {
                errors.add(String.format(Locale.ROOT, "%s impostor covers %.2f of its far mesh and is %.2f as bright", plant.name,
                      coverage, brightness));
            }
        }
        if (target == null) {
            assertTrue(errors.isEmpty(), String.join("\n", errors));
            return;
        }
        // Each plant file's cutoff, and each impostor's colour ratio (its far mesh's to its own, linear RGB) by plant.
        File root = new File(Configuration.dataDir(), "models/board");
        List<String> fitted = new ArrayList<>(), colours = new ArrayList<>();
        cutoffs.forEach((file, cutoff) -> fitted.add(String.format(Locale.ROOT, "\"%s\": %.3f",
              root.toPath().relativize(file.toPath()).toString().replace('\\', '/').replace(".glb", ""), cutoff)));
        for (Plant plant : plants) {
            double[] ratio = new double[3];
            // A channel the impostor never shows stays as it is.
            for (int c = 0; c < 3; c++) { ratio[c] = plant.card[c] > 0 ? plant.mesh[c] / plant.card[c] : 1; }
            colours.add(String.format(Locale.ROOT, "\"%s\": [%.4f, %.4f, %.4f]", plant.name, ratio[0], ratio[1], ratio[2]));
        }
        String json = String.format("{\"cutoffs\": {%n  %s},%n\"colours\": {%n  %s}}%n", String.join(String.format(",%n  "), fitted),
              String.join(String.format(",%n  "), colours));
        try {
            Files.writeString(new File(target).toPath(), json);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** The cutoff at which the impostor covers what the far mesh does: where the summed log ratio crosses zero. */
    private static float fit(double[] curve) {
        if (curve[0] <= 0) { return CUTOFFS[0]; }
        for (int step = 1; step < CUTOFFS.length; step++) {
            if (curve[step] <= 0) {
                double share = curve[step - 1] / (curve[step - 1] - curve[step]);
                return (float) (CUTOFFS[step - 1] + (CUTOFFS[step] - CUTOFFS[step - 1]) * share);
            }
        }
        return CUTOFFS[CUTOFFS.length - 1];
    }

    /**
     * The plants with an impostor (each GLB of the plant folders whose last level is one) and the winter forms their
     * files hold ({@code <name>-snow}, whose impostor shows its own atlas).
     */
    private static List<Plant> plants(File root) {
        List<Plant> plants = new ArrayList<>();
        for (String folder : List.of("vegetation", "fungus", "mars")) {
            File[] files = new File(root, folder).listFiles((directory, name) -> name.endsWith(".glb"));
            for (File file : files == null ? new File[0] : files) {
                String name = folder + "/" + file.getName().replace(".glb", "");
                String atlas = impostorAtlas(root, name);
                if (atlas == null) { continue; }
                plants.add(new Plant(name, file));
                String winter = name + "-snow";
                if (!new File(root, winter + ".glb").isFile() && !atlas.equals(impostorAtlas(root, winter))) {
                    plants.add(new Plant(winter, file));
                }
            }
        }
        plants.sort((a, b) -> a.name.compareTo(b.name));
        assertTrue(!plants.isEmpty(), "No plant impostors under " + root);
        return plants;
    }

    /** The colour atlas of a plant's impostor, or null without one. */
    private static String impostorAtlas(File root, String name) {
        var levels = RigidGlb.loadLods(RigidGlb.source(root, name), root.toPath());
        for (var material : levels.getLast().materials) {
            if (material.id.equals("impostor") && material.textures != null) { return material.textures.first().fileName; }
        }
        return null;
    }

    private static FloatAttribute alphaTest(Material material) {
        return material.get(FloatAttribute.class, FloatAttribute.AlphaTest);
    }

    private static BoardAtmosphere.Settings settings(float hour) {
        return new BoardAtmosphere.Settings(hour, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);
    }

    /** A settled terrain of the scene, sharing the lender's models when there is one. */
    private static GpuTerrain terrain(GpuTerrain lender, BoardScene scene, GpuReviewFrame frame) {
        var terrain = lender == null ? new GpuTerrain() : new GpuTerrain(lender);
        terrain.setQuality(BoardQuality.MEDIUM);
        terrain.setMaterialCache(false);
        terrain.setAtmosphere(frame.lighting());
        terrain.setWind(BoardAtmosphere.Effects.NONE);
        terrain.update(scene);
        terrain.animate(.37f, List.of());
        return terrain;
    }

    /** Every plant alone in the middle of its own patch of grass. */
    private static BoardScene scene(List<Plant> plants) {
        int columns = ROW * SPACING, rows = (plants.size() + ROW - 1) / ROW * SPACING;
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < columns; x++) {
            for (int y = 0; y < rows; y++) {
                int index = y / SPACING * ROW + x / SPACING;
                List<BoardScene.Feature> features = x % SPACING == 1 && y % SPACING == 1 && index < plants.size()
                      ? List.of(new BoardScene.Feature(plants.get(index).name, 0, 0, 0, 1, 2, 0, BoardScene.FeatureKind.TREE))
                      : List.of();
                tiles.add(new BoardScene.Tile(new Coords(x, y), 0, -1, false, 0, BoardScene.Surface.GRASS, null, null, features,
                      List.of(), BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, columns, rows, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** Each plant's centre and diagonal as the terrain placed it. */
    private static void place(GpuTerrain terrain, List<Plant> plants) throws ReflectiveOperationException {
        Map<String, Plant> byName = new LinkedHashMap<>();
        for (Plant plant : plants) { byName.put(plant.name, plant); }
        for (Object chunk : (List<?>) field(terrain, "chunks")) {
            for (Object prop : (List<?>) field(chunk, "props")) {
                Plant plant = byName.get((String) field(prop, "treeAsset"));
                if (plant == null) { continue; }
                plant.centre = ((BoundingBox) field(prop, "bounds")).getCenter(new Vector3());
                plant.diameter = (float) field(prop, "treeDiameter");
            }
        }
        for (Plant plant : plants) { assertTrue(plant.centre != null, plant.name + " was not placed"); }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException missing) {
                // Declared further up.
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** The plant at the switch's size in the middle of the window, from above or from the isometric view. */
    private static BoardCamera camera(BoardScene scene, Plant plant, String view) {
        var camera = new BoardCamera();
        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.fit(scene);
        camera.setIsometric(view.equals("iso"));
        if (!view.equals("iso")) { camera.tilt(0); }
        camera.camera.zoom = plant.diameter * BoardCamera.scenePixelScale() / SWITCH;
        camera.center(plant.centre);
        camera.update();
        return camera;
    }

    /** The settled frame's middle with every tree at one level (TreeLod.LEVELS: none), as packed RGB. */
    private static int[] crop(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene, int level) {
        GpuTreeInstances.Stand.forced = level;
        try {
            Field detail = GpuTerrain.class.getDeclaredField("detailPixelsPerUnit");
            detail.setAccessible(true);
            detail.setFloat(terrain, Float.NaN);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
        // A level change alone keeps the shadow map: redraw it, at the level its texel chooses.
        terrain.refreshShadows();
        frame.prepare(terrain, camera, scene);
        long deadline = System.nanoTime() + 600_000_000_000L;
        while (terrain.refine(camera.camera) || terrain.busy() || !terrain.ready(scene)) {
            assertTrue(System.nanoTime() < deadline, "The scene did not settle");
            frame.render(terrain, camera, scene);
        }
        for (int i = 0; i < 3; i++) { frame.render(terrain, camera, scene); }
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        Pixmap image = Pixmap.createFromFrameBuffer((width - CROP) / 2, (height - CROP) / 2, CROP, CROP);
        try {
            int[] pixels = new int[CROP * CROP];
            for (int i = 0; i < pixels.length; i++) { pixels[i] = image.getPixel(i % CROP, i / CROP) >>> 8; }
            return pixels;
        } finally {
            image.dispose();
            GpuTreeInstances.Stand.forced = -1;
        }
    }

    /** Whether a pixel differs from the frame without trees by more than 8 levels in a channel. */
    private static boolean differs(int pixel, int reference) {
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs((pixel >> shift & 255) - (reference >> shift & 255)) > 8) { return true; }
        }
        return false;
    }

    private static int covered(int[] pixels, int[] reference) {
        int count = 0;
        for (int i = 0; i < pixels.length; i++) { if (differs(pixels[i], reference[i])) { count++; } }
        return count;
    }

    private static double luma(double[] rgb) {
        return .2126 * rgb[0] + .7152 * rgb[1] + .0722 * rgb[2];
    }
}
