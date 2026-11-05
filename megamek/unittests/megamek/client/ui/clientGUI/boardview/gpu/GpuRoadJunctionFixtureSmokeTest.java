/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native views of every saved fixture form, labelled by the same authoritative masks as the editable board. */
@Tag("on-demand")
class GpuRoadJunctionFixtureSmokeTest {
    @Test
    void everyRoadRailCombinationAndTheSharedRoundabout() throws Exception {
        var scene = BoardScenes.scene(RoadJunctionFixture.create());
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "junction-fixture");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1600, 1600);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(false);
                    camera.camera.zoom = .75f;
                    camera.center(BoardGeometry.center(RoadJunctionFixture.center(RoadJunctionFixture.Panel.ROAD, 9), 0));
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene);
                    for (var panel : RoadJunctionFixture.Panel.values()) {
                        if (!selected(panel.name().toLowerCase(java.util.Locale.ROOT))) { continue; }
                        List<File> quarters = new ArrayList<>();
                        for (int row = 0; row < 2; row++) {
                            for (int column = 0; column < 2; column++) {
                                int first = row * 32 + column * 4;
                                Vector3 a = BoardGeometry.center(RoadJunctionFixture.center(panel, first), 0);
                                Vector3 b = BoardGeometry.center(RoadJunctionFixture.center(panel, first + 27), 0);
                                camera.center(a.lerp(b, .5f));
                                camera.camera.zoom = .75f;
                                camera.update();
                                GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                                frame.render(terrain, camera, scene);
                                File file = new File(output, panel.name().toLowerCase(java.util.Locale.ROOT)
                                      + "-" + row + "-" + column + ".png");
                                GpuReviewFrame.save(file);
                                BufferedImage image = ImageIO.read(file);
                                Graphics2D labels = labels(image);
                                title(labels, panel.label + " — exit masks " + first + " to " + (first + 27));
                                for (int y = 0; y < 4; y++) {
                                    for (int x = 0; x < 4; x++) {
                                        int mask = first + y * 8 + x;
                                        Coords at = RoadJunctionFixture.center(panel, mask);
                                        Vector3 screen = camera.camera.project(BoardGeometry.center(at, 0));
                                        label(labels, "mask " + mask + "  " + at.getBoardNum(),
                                              Math.round(screen.x) - 76, image.getHeight() - Math.round(screen.y) + 170);
                                    }
                                }
                                labels.dispose();
                                ImageIO.write(image, "png", file);
                                quarters.add(file);
                                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                            }
                        }
                        sheet(quarters, new File(output, panel.name().toLowerCase(java.util.Locale.ROOT) + "-all-64.png"), 2, 1200);
                    }
                    cases(terrain, frame, camera, scene, output, "width-comparison", List.of(
                          new RoadJunctionFixture.Case("road", "Plain road: two full traffic lanes",
                                RoadJunctionFixture.center(RoadJunctionFixture.Panel.ROAD, 9)),
                          new RoadJunctionFixture.Case("centered", "Centered rail: extra width for track and two full traffic lanes",
                                RoadJunctionFixture.center(RoadJunctionFixture.Panel.CENTERED, 9)),
                          new RoadJunctionFixture.Case("beside", "Beside rail: full road width plus separate track",
                                RoadJunctionFixture.center(RoadJunctionFixture.Panel.BESIDE, 9))), false);
                    cases(terrain, frame, camera, scene, output, "rail-terminals", List.of(
                          new RoadJunctionFixture.Case("bend", "Beside rail: bend and matching terminal lengths",
                                RoadJunctionFixture.center(RoadJunctionFixture.Panel.BESIDE, 10)),
                          new RoadJunctionFixture.Case("branch", "Beside rail: branch and matching terminal lengths",
                                RoadJunctionFixture.center(RoadJunctionFixture.Panel.BESIDE, 11))), false);
                    cases(terrain, frame, camera, scene, output, "mixed", RoadJunctionFixture.mixedCases(), false);
                    cases(terrain, frame, camera, scene, output, "triangles", RoadJunctionFixture.triangleCases(), false);
                    cases(terrain, frame, camera, scene, output, "seaport-variants", RoadJunctionFixture.triangleVariants(), false);
                    cases(terrain, frame, camera, scene, output, "width-top", RoadJunctionFixture.widthCases(), false);
                    cases(terrain, frame, camera, scene, output, "width-oblique", RoadJunctionFixture.widthCases(), true);
                    cases(terrain, frame, camera, scene, output, "controls", RoadJunctionFixture.controlCases(), false);
                    cases(terrain, frame, camera, scene, output, "controls-oblique", RoadJunctionFixture.controlCases(), true);
                    if (selected("seaport-board")) {
                        var seaport = GpuRoadSourceTest.scene("Map Set 7/16x17 Seaport.board");
                        terrain.update(seaport);
                        cases(terrain, frame, camera, seaport, output, "seaport-board", List.of(
                              new RoadJunctionFixture.Case("left", "Seaport.board: left three-hex junction", new Coords(9, 12)),
                              new RoadJunctionFixture.Case("right", "Seaport.board: right three-hex junction", new Coords(12, 7))), false);
                    }
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Road and rail fixture rendering", failure.get()); }
    }

    private static void cases(GpuTerrain terrain, GpuReviewFrame frame, BoardCamera camera, BoardScene scene,
          File output, String name, List<RoadJunctionFixture.Case> cases, boolean angled) throws Exception {
        if (!selected(name) && !(name.equals("controls-oblique") && selected("controls"))) { return; }
        List<File> images = new ArrayList<>();
        camera.setIsometric(angled);
        if (angled) { camera.orbit(25 - camera.azimuth(), 55 - camera.tilt()); }
        for (var sample : cases) {
            // These placements occupy one hex; frame their bays and canopies closely enough to inspect the setback.
            camera.camera.zoom = sample.id().equals("trees") || sample.id().equals("parking") ? .1f
                  : name.startsWith("controls") ? .2f : .34f;
            camera.center(BoardGeometry.center(sample.at(), 0));
            camera.update();
            GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
            frame.render(terrain, camera, scene);
            File file = new File(output, name + "-" + sample.id() + ".png");
            GpuReviewFrame.save(file);
            BufferedImage image = ImageIO.read(file);
            Graphics2D labels = labels(image);
            title(labels, sample.label());
            labels.dispose();
            ImageIO.write(image, "png", file);
            images.add(file);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        }
        sheet(images, new File(output, name + "-contact.png"), 2, 800);
    }

    /** Optional focused visual review; the default still captures every combination. */
    private static boolean selected(String group) {
        String selected = System.getProperty("megamek.gpu.fixtureGroups", "");
        return selected.isBlank() || List.of(selected.split(",")).contains(group);
    }

    private static Graphics2D labels(BufferedImage image) {
        Graphics2D labels = image.createGraphics();
        labels.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        labels.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        return labels;
    }

    private static void title(Graphics2D labels, String text) { label(labels, text, 20, 34); }

    private static void label(Graphics2D labels, String text, int x, int y) {
        labels.setColor(new Color(15, 20, 24, 220));
        labels.fillRect(x - 7, y - 24, labels.getFontMetrics().stringWidth(text) + 14, 32);
        labels.setColor(Color.WHITE);
        labels.drawString(text, x, y);
    }

    private static void sheet(List<File> sources, File target, int columns, int size) throws Exception {
        BufferedImage sheet = new BufferedImage(columns * size, ((sources.size() + columns - 1) / columns) * size,
              BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = sheet.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        for (int i = 0; i < sources.size(); i++) {
            graphics.drawImage(ImageIO.read(sources.get(i)), (i % columns) * size, (i / columns) * size, size, size, null);
        }
        graphics.dispose();
        ImageIO.write(sheet, "png", target);
    }
}
