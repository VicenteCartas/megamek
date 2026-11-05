/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.ResolvedAttack;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Independent material toggles, native mask uploads, persistent/replayed impacts and top/isometric captures. */
@Tag("on-demand")
class GpuGroundDamageSmokeTest {
    @Test
    void naturalWearAndCombatScarsHaveIndependentControls() throws Exception {
        Board board = BoardFile.loadSheet(new File("data/boards/Map Pack Savannahs/16x17 Box Canyon (Savannah).board"), false, true);
        BoardScene scene = BoardScenes.capture(board);
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1440, 1080);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { review(scene); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Ground damage review", failure.get()); }
    }

    private static void review(BoardScene scene) throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        Files.createDirectories(output.toPath());
        var report = new StringBuilder(Gdx.gl.glGetString(GL20.GL_RENDERER)).append("; 1440x1080\n");
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
              BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
        try {
            terrain.update(scene);
            var camera = new BoardCamera();
            camera.resize(1440, 1080);
            Coords at = new Coords(13, 1);
            camera.camera.zoom = .065f;
            camera.center(BoardGeometry.center(at, scene.tile(at).elevation()));
            settle(terrain, frame, scene, camera);
            // A displayed board has prepared its empty damage texture before combat starts.
            frame.render(terrain, camera, scene);
            assertTrue(terrain.ready(scene), "The displayed terrain must remain ready before impact picking");
            var damage = terrain.groundDamage();
            assertEquals(0, damage.marks());
            var points = points(terrain, at);
            assertTrue(points.size() >= 6);
            List<GpuGroundDamage.Impact> impacts = new ArrayList<>();
            for (int i = 0; i < points.size(); i++) {
                var shot = GroundDamagePlaybackTest.shot("ISAC5", false, null);
                shot.landscape = ray -> terrain.hit(scene, ray);
                impacts.add(new GpuGroundDamage.Impact(shot, "review-" + i,
                      points.get(i).cpy().add(-40, 20, 30), points.get(i), List.of("laser", "bullet", "missile").get(i % 3), null));
            }
            long stampStart = System.nanoTime();
            for (var impact : impacts) {
                damage.impact(scene, impact, terrain::tacticalSurface);
                int count = damage.marks(), queued = pending(damage);
                damage.impact(scene, impact, terrain::tacticalSurface);
                assertEquals(count, damage.marks(), "An attack replay cannot repaint its mark");
                assertEquals(queued, pending(damage), "An attack replay cannot queue its mark twice");
            }
            report.append(impacts.size()).append(" CPU impact admissions ms=").append((System.nanoTime() - stampStart) / 1e6).append('\n');
            assertEquals(impacts.size(), pending(damage), "Every ground impact must be admitted exactly once");
            Gdx.gl.glFinish();
            long uploadStart = System.nanoTime();
            int uploadFrames = uploadAll(damage);
            Gdx.gl.glFinish();
            report.append("batch raster, upload and mip generation frames=").append(uploadFrames)
                  .append("; wall ms=").append((System.nanoTime() - uploadStart) / 1e6).append('\n');
            assertTrue(damage.marks() >= 6, "Markers must land on the actual installed ground");
            assertEquals(impacts.size(), damage.marks(), "Every admitted unique ground impact must finish rasterizing");
            int marks = damage.marks();
            var above = GroundDamagePlaybackTest.shot("ISAC5", false, null);
            above.landscape = ray -> new BoardGeometry.Hit(at, BoardRelief.metres(.1f) * BoardRelief.metres(.1f));
            damage.impact(scene, new GpuGroundDamage.Impact(above, "floating", points.getFirst(),
                  points.getFirst().cpy().add(0, 0, 20), "bullet", null), terrain::tacticalSurface);
            assertEquals(marks, damage.marks(), "Props using the generic ground receiver cannot scar the soil below them");
            assertEquals(0, pending(damage), "A floating prop contact must not queue a ground scar");
            for (boolean iso : new boolean[] { false, true }) {
                camera.setIsometric(iso);
                camera.center(BoardGeometry.center(at, scene.tile(at).elevation()));
                settle(terrain, frame, scene, camera);
                List<int[]> images = new ArrayList<>();
                String prefix = iso ? "iso-" : "top-";
                for (int pass = 0; pass < 2; pass++) {
                    terrain.setCombatScars(pass == 1);
                    frame.render(terrain, camera, scene);
                    images.add(pixels());
                    String name = prefix + List.of("worn", "combat").get(pass);
                    GpuReviewFrame.save(new File(output, name + ".png"));
                    if (Boolean.getBoolean("megamek.gpu.measureGroundDamage")
                          && !Boolean.getBoolean("megamek.gpu.measureAccumulatedScars")) {
                        measure(frame, terrain, camera, scene, report, name);
                    }
                }
                assertTrue(differences(images.get(0), images.get(1)) > 100, "Combat scars must be visible");
                terrain.setCombatScars(false);
                frame.render(terrain, camera, scene);
                assertEquals(0, differences(images.get(0), pixels()), "Scars off restores the worn terrain shading");
                assertEquals(marks, damage.marks(), "Camera/refinement and toggles retain the same marks");
            }
            Coords rock = new Coords(12, 13);
            for (boolean iso : new boolean[] { false, true }) {
                camera.setIsometric(iso);
                camera.camera.zoom = .045f;
                camera.center(BoardGeometry.center(rock, scene.tile(rock).elevation()));
                settle(terrain, frame, scene, camera);
                frame.render(terrain, camera, scene);
                GpuReviewFrame.save(new File(output, "rock-" + (iso ? "iso" : "top") + ".png"));
            }
            assertEquals(GpuGroundDamage.TILE_SIZE, damage.texture().getWidth());
            assertEquals(GpuGroundDamage.TILE_SIZE, damage.texture().getHeight());
            report.append("mask=").append(damage.texture().getWidth()).append('x').append(damage.texture().getHeight())
                  .append("; marks=").append(damage.marks()).append('\n');
            // Exercise a sub-image update after allocation, not just the first full texture upload.
            var extra = GroundDamagePlaybackTest.shot("ISAC5", false, null);
            extra.landscape = ray -> terrain.hit(scene, ray);
            Gdx.gl.glFinish();
            long updateStart = System.nanoTime();
            damage.impact(scene, new GpuGroundDamage.Impact(extra, "late", points.getFirst(), points.getFirst(), "ppc", null), terrain::tacticalSurface);
            damage.upload();
            Gdx.gl.glFinish();
            report.append("later impact, subimage upload and mip generation wall ms=")
                  .append((System.nanoTime() - updateStart) / 1e6).append('\n');
            assertEquals(marks + 1, damage.marks());
            int beforeBurst = damage.marks();
            for (int i = 0; i < 25; i++) {
                var burst = GroundDamagePlaybackTest.shot("ISAC5", false, null);
                burst.landscape = extra.landscape;
                damage.impact(scene, new GpuGroundDamage.Impact(burst, "burst", points.getFirst(), points.getFirst(), "bullet", null),
                      terrain::tacticalSurface);
            }
            assertEquals(25, pending(damage), "The whole salvo must be admitted before raster work starts");
            // Large gouges can reach the texel budget before the per-frame stamp count.
            uploadAll(damage);
            assertEquals(beforeBurst + 25, damage.marks(), "Bounded uploads must eventually finish the whole salvo");
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            if (Boolean.getBoolean("megamek.gpu.measureAccumulatedScars")) {
                measureAccumulatedScars(frame, terrain, camera, scene, report);
            }
            reviewDamageScars(scene, terrain, frame, camera, output);
            terrain.boardChanged();
            damage.upload();
            assertEquals(0, damage.marks());
            assertEquals(null, damage.texture());
            Files.writeString(new File(output, "ground-damage-cost.txt").toPath(), report);
        } finally { terrain.dispose(); frame.dispose(); }
    }

    private record ScarCoverage(long soot, long pigment, long glass, long relief) { }

    @SuppressWarnings("unchecked")
    private static Map<Integer, GpuGroundDamage.Tile> tiles(GpuGroundDamage damage) throws Exception {
        var field = GpuGroundDamage.class.getDeclaredField("tiles");
        field.setAccessible(true);
        return (Map<Integer, GpuGroundDamage.Tile>) field.get(damage);
    }

    /** Inspect the actual rasterized mask as well as saving the scar on the installed terrain. */
    private static void reviewDamageScars(BoardScene scene, GpuTerrain terrain, GpuReviewFrame frame,
          BoardCamera camera, File output) throws Exception {
        Coords at = new Coords(13, 1);
        var points = points(terrain, at);
        var center = points.get(points.size() / 2);
        camera.setIsometric(false);
        camera.camera.zoom = .055f;
        camera.center(center);
        settle(terrain, frame, scene, camera);
        terrain.setCombatScars(true);
        var coverage = new java.util.HashMap<String, ScarCoverage>();
        for (String[] sample : new String[][] {
              { "small-laser", "ISSmallLaser", null }, { "large-laser", "ISLargeLaser", null },
              { "medium-laser", "ISMediumLaser", null }, { "light-ppc", "ISLightPPC", null },
              { "ppc", "ISPPC", null },
              { "light-plasma", "ISLightPlasmaRifle", null }, { "plasma", "ISPlasmaRifle", null },
              { "heavy-plasma", "ISHeavyPlasmaRifle", null }, { "plasma-cannon", "CLPlasmaCannon", "CLPlasmaCannonAmmo" },
              { "ac2", "ISAC2", null }, { "ac20", "ISAC20", null },
              { "lrm5", "ISLRM5", "IS Ammo LRM-5" }, { "lrm15", "ISLRM15", "IS Ammo LRM-15" },
              { "srm2", "ISSRM2", "IS Ammo SRM-2" }, { "srm6", "ISSRM6", "IS Ammo SRM-6" },
              { "thunderbolt5", "ISThunderbolt5", "IS Ammo Thunderbolt-5" },
              { "thunderbolt20", "ISThunderbolt20", "IS Ammo Thunderbolt-20" },
              { "thumper", "ISThumper", null }, { "sniper", "ISSniper", null }, { "long-tom", "ISLongTom", null },
              { "flame", "Flamer", null }, { "heavy-flame", "Heavy Flamer", null }
        }) {
            var damage = terrain.groundDamage();
            damage.dispose();
            var mark = GroundDamagePlaybackTest.impact(sample[1], sample[2]);
            mark.attack().landscape = ray -> terrain.hit(scene, ray);
            var origin = center.cpy().add(-BoardRelief.metres(10), 0, BoardRelief.metres(5));
            damage.impact(scene, new GpuGroundDamage.Impact(mark.attack(), mark.key(), origin, center,
                  mark.effect(), mark.shot()), terrain::tacticalSurface);
            renderUntilPainted(frame, terrain, camera, scene, 1, sample[0] + " must reach the installed ground");
            long soot = 0, chips = 0, glass = 0, relief = 0;
            int peak = 0;
            for (var tile : tiles(damage).values()) {
                // Only interiors count; shared border samples must not inflate the measured footprint.
                var image = tile.pixels();
                for (int y = GpuGroundDamage.TILE_BORDER; y < GpuGroundDamage.TILE_SIZE - GpuGroundDamage.TILE_BORDER; y++) {
                    for (int x = GpuGroundDamage.TILE_BORDER; x < GpuGroundDamage.TILE_SIZE - GpuGroundDamage.TILE_BORDER; x++) {
                        int value = image.getPixel(x, y);
                        soot += value >>> 24;
                        chips += value >>> 16 & 255;
                        glass += value >>> 8 & 255;
                        relief += value & 255;
                        peak = Math.max(peak, value >>> 24);
                    }
                }
            }
            if (mark.burn()) { assertTrue(peak > 140 && peak <= 166, "Flamers remain readable with fixed, moderate opacity"); }
            assertTrue(soot > 0, sample[0] + " must leave visible soot");
            coverage.put(sample[0], new ScarCoverage(soot, chips, glass, relief));
            GpuReviewFrame.save(new File(output, "scar-" + sample[0] + ".png"));
        }
        assertTrue(coverage.get("large-laser").soot() > coverage.get("small-laser").soot());
        assertTrue(coverage.get("light-ppc").glass() > 0, "PPCs retain their vitrified material");
        assertTrue(coverage.get("ppc").glass() > coverage.get("light-ppc").glass());
        var plasma = coverage.get("plasma");
        assertTrue(plasma.glass() > 0, "Plasma must melt the receiving ground");
        assertTrue(plasma.pigment() > 0, "Plasma retains the authored image's light/dark variation");
        assertTrue(plasma.relief() < plasma.glass() / 3, "Plasma scorches the surface without excavating a deep crater");
        assertTrue(coverage.get("heavy-plasma").soot() > plasma.soot());
        assertTrue(plasma.soot() > coverage.get("light-plasma").soot());
        assertTrue(coverage.get("plasma-cannon").glass() > 0, "Zero-damage plasma still leaves a small melted mark");
        assertTrue(coverage.get("ac20").relief() > 0, "Ballistics tear the soil");
        assertEquals(0, coverage.get("ac20").glass(), "Ballistics do not vitrify the core");
        assertTrue(coverage.get("ac20").soot() > coverage.get("ac2").soot());
        assertTrue(coverage.get("ac20").soot() > coverage.get("large-laser").soot() * 2,
              "The AC/20's longer, wider furrow must cover substantially more ground than a Large Laser");
        assertEquals(coverage.get("lrm5"), coverage.get("lrm15"));
        assertEquals(coverage.get("srm2"), coverage.get("srm6"));
        assertTrue(coverage.get("srm2").soot() > coverage.get("lrm5").soot());
        assertTrue(coverage.get("thunderbolt20").soot() > coverage.get("thunderbolt5").soot());
        assertTrue(coverage.get("long-tom").soot() > coverage.get("sniper").soot());
        assertTrue(coverage.get("sniper").soot() > coverage.get("thumper").soot());
        assertEquals(coverage.get("thunderbolt20"), coverage.get("sniper"));
        assertTrue(coverage.get("heavy-flame").soot() > coverage.get("flame").soot(), "A stronger flamer burns more turf");
        assertEquals(0, coverage.get("flame").relief(), "Burns do not excavate soil");
        assertEquals(0, coverage.get("flame").glass(), "Burns stay matte");
        assertTrue(coverage.get("srm2").pigment() > 0, "Blast scars retain their authored detail");
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
    }

    @Test
    void scarsClearGrassAtItsRootsAndRestoreItWhenHidden() throws Exception {
        var board = Board.createEmptyBoard(3, 3);
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) {
            board.setHex(new Coords(x, y), new Hex(0, "", "grass"));
        }
        var scene = BoardScenes.capture(board);
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1200, 900);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                try { reviewGrassScars(scene); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Grass clearing review", failure.get()); }
    }

    private static void reviewGrassScars(BoardScene scene) throws Exception {
        var terrain = new GpuTerrain();
        var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
              BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
        try {
            terrain.update(scene);
            terrain.setGrass(true);
            var camera = new BoardCamera();
            camera.resize(1200, 900);
            camera.setIsometric(false);
            camera.camera.zoom = BoardRelief.metres(30) / 1200;
            var at = new Coords(1, 1);
            var center = BoardGeometry.center(at, 0);
            camera.center(center);
            settle(terrain, frame, scene, camera);
            var surface = terrain.tacticalSurface(at);
            center.z = surface.top().stream().map(face -> face.height(center.x, center.y))
                  .filter(Float::isFinite).max(Float::compare).orElseThrow();
            camera.center(center);
            frame.render(terrain, camera, scene);
            Thread.sleep(550); // Let late-arriving roots finish the existing half-second growth transition.
            frame.render(terrain, camera, scene);
            int[] cleanGrass = pixels();
            File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
            GpuReviewFrame.save(new File(output, "scar-grass-before.png"));
            terrain.setGrass(false);
            frame.render(terrain, camera, scene);
            int[] cleanBare = pixels();
            int cleanCore = coreDifferences(cleanGrass, cleanBare, 1200, 900);
            assertTrue(cleanCore > 100, "The reference must contain visible blades at the impact point");
            terrain.setGrass(true);
            var mark = GroundDamagePlaybackTest.impact("ISLongTom", null);
            mark.attack().landscape = ray -> terrain.hit(scene, ray);
            int painted = terrain.groundDamage().marks();
            terrain.groundDamage().impact(scene, new GpuGroundDamage.Impact(mark.attack(), mark.key(), center, center,
                  mark.effect(), mark.shot()), terrain::tacticalSurface);
            renderUntilPainted(frame, terrain, camera, scene, painted + 1, "The Long Tom's scar is painted");
            int[] scarGrass = pixels();
            GpuReviewFrame.save(new File(output, "scar-grass-after.png"));
            terrain.setGrass(false);
            frame.render(terrain, camera, scene);
            int[] scarBare = pixels();
            assertTrue(coreDifferences(scarGrass, scarBare, 1200, 900) < cleanCore / 10,
                  "The damaged core must show the ground without surviving grass blades");
            float radiusPixels = mark.radius() / BoardRelief.metres(30) * 1200;
            assertTrue(rimDifferences(scarGrass, scarBare, radiusPixels)
                        > rimDifferences(cleanGrass, cleanBare, radiusPixels) / 10,
                  "Some grass must survive across the ejecta fringe instead of leaving a sharply cleared disk");
            assertTrue(differences(scarGrass, scarBare) > 1000, "Grass outside the scar remains visible");
            assertSame(surface, terrain.tacticalSurface(at), "A scar must not rebuild receiving geometry");
            terrain.setGrass(true);
            terrain.setCombatScars(false);
            frame.render(terrain, camera, scene);
            assertEquals(0, differences(cleanGrass, pixels()), "Hiding scars restores both turf and blades");
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally { terrain.dispose(); frame.dispose(); }
    }

    private static int coreDifferences(int[] a, int[] b, int width, int height) {
        int changed = 0;
        for (int y = height / 2 - 60; y < height / 2 + 60; y++) {
            for (int x = width / 2 - 60; x < width / 2 + 60; x++) {
                if (a[y * width + x] != b[y * width + x]) { changed++; }
            }
        }
        return changed;
    }

    private static int rimDifferences(int[] a, int[] b, float radius) {
        int changed = 0;
        float inner = radius * .6f, outer = radius * .85f;
        for (int y = 0; y < 900; y++) {
            for (int x = 0; x < 1200; x++) {
                float distanceSquared = (x - 600) * (x - 600) + (y - 450) * (y - 450);
                if (distanceSquared >= inner * inner && distanceSquared <= outer * outer
                      && a[y * 1200 + x] != b[y * 1200 + x]) { changed++; }
            }
        }
        return changed;
    }

    /** Native cost review only: settled history and new mask updates, excluding flying missile/explosion rendering. */
    private static void measureAccumulatedScars(GpuReviewFrame frame, GpuTerrain terrain, BoardCamera camera,
          BoardScene scene, StringBuilder report) throws Exception {
        var damage = terrain.groundDamage();
        damage.dispose();
        damage.update(scene);
        damage.upload();
        List<List<Vector3>> clusters = new ArrayList<>();
        for (int y = 2; y < scene.height() - 1; y += 3) {
            for (int x = 2; x < scene.width() - 1; x += 4) {
                var cluster = points(terrain, new Coords(x, y));
                if (!cluster.isEmpty()) { clusters.add(cluster); }
            }
        }
        assertTrue(clusters.size() >= 4);
        var profile = new ResolvedAttack.Shot("", Set.of(), false, false, 1, 20, false, 0);
        // Twenty single-rack arrivals followed by twenty four-rack arrivals leave exactly 1000 accepted scars.
        // The playback regression checks the LRM20's ten-mark selection separately; here endpoints are already known.
        for (int racks : new int[] { 1, 4 }) {
            List<Double> admission = new ArrayList<>(), cpu = new ArrayList<>(), wall = new ArrayList<>();
            for (int repeat = 0; repeat < 20; repeat++) {
                List<GpuGroundDamage.Impact> impacts = new ArrayList<>();
                for (int rack = 0; rack < racks; rack++) {
                    var shot = GroundDamagePlaybackTest.shot("ISLRM20", false, profile);
                    shot.landscape = ray -> terrain.hit(scene, ray);
                    var cluster = clusters.get((repeat + rack * clusters.size() / racks) % clusters.size());
                    for (int missile = 0; missile < 10; missile++) {
                        Vector3 point = cluster.get((repeat + missile) % cluster.size());
                        impacts.add(new GpuGroundDamage.Impact(shot, "missile-" + missile,
                              point.cpy().add(-40, 20, 30), point, "missile", profile));
                    }
                }
                int previous = damage.marks();
                long start = System.nanoTime();
                for (var impact : impacts) { damage.impact(scene, impact, terrain::tacticalSurface); }
                admission.add((System.nanoTime() - start) / 1e6);
                for (int pending = impacts.size(); pending > 0; pending -= GpuGroundDamage.STAMPS_PER_FRAME) {
                    Gdx.gl.glFinish();
                    start = System.nanoTime();
                    damage.upload();
                    cpu.add((System.nanoTime() - start) / 1e6);
                    Gdx.gl.glFinish();
                    wall.add((System.nanoTime() - start) / 1e6);
                }
                assertEquals(previous + impacts.size(), damage.marks(), "All synthetic ground arrivals must be admitted");
            }
            report.append("LRM20 mask updates: racks=").append(racks).append("; marks/volley=").append(racks * 10)
                  .append("; update frames/volley=").append((racks * 10 + GpuGroundDamage.STAMPS_PER_FRAME - 1)
                        / GpuGroundDamage.STAMPS_PER_FRAME).append('\n');
            report.append("operation,samples,medianMs,p95Ms,maxMs\n");
            times(report, "admission per volley", admission);
            times(report, "raster/upload submit per update frame", cpu);
            times(report, "raster/upload/mips synchronized wall per update frame", wall);
        }
        assertEquals(1000, damage.marks());
        report.append("Accumulated scars=").append(damage.marks()).append("; mask=")
              .append(damage.texture().getWidth()).append('x').append(damage.texture().getHeight()).append('\n');
        Coords at = new Coords(13, 1);
        camera.camera.zoom = .065f;
        for (boolean iso : new boolean[] { false, true }) {
            camera.setIsometric(iso);
            camera.center(BoardGeometry.center(at, scene.tile(at).elevation()));
            settle(terrain, frame, scene, camera);
            for (int repeat = 0; repeat < 3; repeat++) {
                for (int ordinal = 0; ordinal < 2; ordinal++) {
                    // Alternate the order between repeats to reduce clock/temperature/order bias.
                    int mode = (ordinal + repeat) % 2;
                    terrain.setCombatScars(mode == 1);
                    measure(frame, terrain, camera, scene, report, "1000-scars-" + (iso ? "iso-" : "top-")
                          + List.of("worn", "combat").get(mode) + "-repeat-" + repeat);
                }
            }
        }
        assertEquals(1000, damage.marks(), "Settled frames do not rerasterize old marks");
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
    }

    private static void times(StringBuilder report, String label, List<Double> values) {
        values.sort(Double::compare);
        report.append(String.format(Locale.ROOT, "%s,%d,%.4f,%.4f,%.4f%n", label, values.size(),
              values.get(values.size() / 2), values.get(Math.min(values.size() - 1, (int) (values.size() * .95))), values.getLast()));
    }

    private static int pending(GpuGroundDamage damage) throws Exception {
        var field = GpuGroundDamage.class.getDeclaredField("pending");
        field.setAccessible(true);
        return ((java.util.Collection<?>) field.get(damage)).size();
    }

    private static int uploadAll(GpuGroundDamage damage) throws Exception {
        int frames = 0;
        while (pending(damage) > 0) {
            int queued = pending(damage), completed = damage.marks();
            damage.upload();
            int stamped = damage.marks() - completed;
            assertTrue(stamped > 0 && stamped <= GpuGroundDamage.STAMPS_PER_FRAME,
                  "Each upload must make progress without exceeding the stamp limit");
            assertEquals(queued - stamped, pending(damage), "Uploaded marks must leave the pending queue");
            frames++;
        }
        return frames;
    }

    /** Prepare the displayed lighting before waiting for installed terrain and its material families. */
    /** Renders until the ground damage has painted {@code marks} marks: a large mark resumes over a few frames. */
    private static void renderUntilPainted(GpuReviewFrame frame, GpuTerrain terrain, BoardCamera camera,
          BoardScene scene, int marks, String message) {
        for (int i = 0; i < 16 && terrain.groundDamage().marks() < marks; i++) {
            frame.render(terrain, camera, scene);
        }
        assertEquals(marks, terrain.groundDamage().marks(), message);
    }

    private static void settle(GpuTerrain terrain, GpuReviewFrame frame, BoardScene scene, BoardCamera camera)
          throws Exception {
        frame.prepare(terrain, camera, scene);
        GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
    }

    private static List<Vector3> points(GpuTerrain terrain, Coords at) {
        var center = BoardGeometry.center(at, 0);
        var surface = terrain.tacticalSurface(at);
        List<Vector3> points = new ArrayList<>();
        for (float x : new float[] { -7, -2, 4 }) {
            for (float y : new float[] { -6, 0, 6 }) {
                var point = center.cpy().add(BoardRelief.metres(x), BoardRelief.metres(y), 0);
                point.z = surface.top().stream().map(face -> face.height(point.x, point.y)).max(Float::compare).orElse(Float.NaN);
                if (Float.isFinite(point.z)) { points.add(point); }
            }
        }
        return points;
    }

    private static int[] pixels() {
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            int[] values = new int[image.getWidth() * image.getHeight()];
            image.getPixels().asIntBuffer().get(values);
            return values;
        } finally { image.dispose(); }
    }

    private static int differences(int[] a, int[] b) {
        int count = 0;
        for (int i = 0; i < a.length; i++) { if (a[i] != b[i]) { count++; } }
        return count;
    }

    private static void measure(GpuReviewFrame frame, GpuTerrain terrain, BoardCamera camera, BoardScene scene,
          StringBuilder report, String name) {
        try (var timings = new GpuStageTimings()) {
            for (int i = -90; i < 240; i++) {
                timings.beginFrame(i >= 0);
                timings.stage("terrain frame");
                frame.render(terrain, camera, scene);
                timings.stage(null);
                Gdx.gl.glFinish();
            }
            timings.appendReport(report, name);
        }
        var profiler = new GLProfiler(Gdx.graphics);
        GL20 raw = Gdx.gl20;
        profiler.enable();
        try {
            frame.render(terrain, camera, scene);
            report.append(name).append(" draws=").append(profiler.getDrawCalls()).append(" submitted vertices=")
                  .append(profiler.getVertexCount().total).append('\n');
        } finally { GpuStageTimings.stopCounting(profiler, raw); }
    }
}
