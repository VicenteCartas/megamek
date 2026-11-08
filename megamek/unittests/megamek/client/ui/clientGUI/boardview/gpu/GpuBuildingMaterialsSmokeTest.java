/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native catalog selection, shared materials and gameplay heights of building kits, tanks and industrial scenery. */
@Tag("on-demand")
class GpuBuildingMaterialsSmokeTest {
    @Test
    void selectedBuildingFamiliesUseTheirKitAndIndustrialMaterials() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                Hex[] hexes = new Hex[9 * 9];
                for (int y = 0; y < 9; y++) {
                    for (int x = 0; x < 9; x++) {
                        Hex hex = new Hex(0);
                        if ((x & 1) == 1 && y <= 4 && (y & 1) == 0) {
                            hex.addTerrain(new Terrain(Terrains.BUILDING, (x + 1) / 2, true, 0));
                            hex.addTerrain(new Terrain(Terrains.BLDG_CF, 100));
                            hex.addTerrain(new Terrain(Terrains.BLDG_ELEV, (x + 1) / 2));
                            hex.addTerrain(new Terrain(Terrains.BLDG_CLASS, y / 2));
                        } else if ((x == 1 || x == 3) && y == 6) {
                            hex.addTerrain(new Terrain(Terrains.FUEL_TANK, x == 1 ? 2 : 4, true, 0));
                            hex.addTerrain(new Terrain(Terrains.FUEL_TANK_ELEV, 2));
                            hex.addTerrain(new Terrain(Terrains.FUEL_TANK_CF, 40));
                            hex.addTerrain(new Terrain(Terrains.FUEL_TANK_MAGN, 100));
                        } else if (x == 5 && y == 6) {
                            hex.addTerrain(new Terrain(Terrains.INDUSTRIAL, 2));
                        } else if (x == 4 && y == 7) {
                            // A road bank at deck height attaches to the span below; a span without an attached
                            // road uses the bridge type captured by BoardScene.
                            hex.setLevel(2);
                            hex.addTerrain(new Terrain(Terrains.ROAD, 1, true, 1 << 3));
                        } else if (x == 4 && y == 8) {
                            hex.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, 9));
                            hex.addTerrain(new Terrain(Terrains.BRIDGE_CF, 100));
                            hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 2));
                        }
                        hexes[y * 9 + x] = hex;
                    }
                }
                fixture.game.setBoard(new Board(9, 9, hexes));
                BoardSceneryLayouts.convert(fixture.game.getBoard());
                fixture.source.refresh();
            });
            BoardScene captured = fixture.source.takeFrame().scene();
            BoardScene scene = new BoardScene(0, 9, 9, captured.tiles(), List.of(), List.of(), -1, "", List.of(),
                  new BoardScene.Light(-24, -30));
            assertEquals(15, scene.tiles().stream().flatMap(tile -> tile.features().stream())
                  .filter(feature -> feature.asset().startsWith("buildings/")).count());
            new Lwjgl3Application(new ApplicationAdapter() {
                GpuTerrain terrain;
                GpuSceneTestOutput outputFrame;
                BoardCamera camera;
                int frames;

                @Override
                public void create() {
                    try {
                        checkMaterials(scene);
                        terrain = new GpuTerrain();
                        outputFrame = new GpuSceneTestOutput();
                        camera = new BoardCamera();
                        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                        camera.setIsometric(true);
                        camera.fit(scene);
                        installScene();
                        checkHeights(scene, terrain);
                        BoardGeometry.Tuning previous = BoardGeometry.tuning();
                        try {
                            BoardGeometry.tune(new BoardGeometry.Tuning(1.5f, 1, 1, 12, previous.gridShade()));
                            installScene();
                            checkHeights(scene, terrain);
                        } finally {
                            BoardGeometry.tune(previous);
                            installScene();
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }

                @Override
                public void render() {
                    if (failure.get() != null) {
                        return;
                    }
                    try {
                        draw();
                        File output = new File(System.getProperty("megamek.gpu.screenshots"));
                        if (++frames == 4) {
                            GpuBoardTestUi.capture(new File(output, "building-material-families.png"));
                            camera.zoom(0.5f);
                            camera.center(BoardGeometry.center(new Coords(4, 0), 1));
                        } else if (frames == 7) {
                            GpuBoardTestUi.capture(new File(output, "building-material-construction.png"));
                            camera.center(BoardGeometry.center(new Coords(4, 2), 1));
                        } else if (frames == 10) {
                            GpuBoardTestUi.capture(new File(output, "building-material-hangars.png"));
                            camera.center(BoardGeometry.center(new Coords(4, 4), 1));
                        } else if (frames == 13) {
                            GpuBoardTestUi.capture(new File(output, "building-material-fortresses.png"));
                            camera.center(BoardGeometry.center(new Coords(3, 6), 1));
                        } else if (frames == 16) {
                            GpuBoardTestUi.capture(new File(output, "building-material-tanks-industrial.png"));
                            assertEquals(java.util.Map.of(), terrain.takeMissingAssets(), "Catalog entries must load their real geometry");
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }

                private void draw() {
                    terrain.animate(0, List.of());
                    terrain.renderShadows(List.of());
                    outputFrame.begin(0.035f, 0.055f, 0.075f, 1);
                    terrain.render(camera.camera);
                    outputFrame.display();
                }

                private void installScene() throws Exception {
                    terrain.update(scene);
                    // A geometry-scale change invalidates material pages; fill them before testing runtime picking.
                    draw();
                    terrain.update(scene);
                    GpuReviewFrame.settle(terrain, null, scene, camera);
                    assertTrue(terrain.ready(scene), "Catalog rendering and picking require the completed loading stage");
                }

                @Override
                public void dispose() {
                    if (outputFrame != null) { outputFrame.close(); }
                    if (terrain != null) {
                        terrain.dispose();
                    }
                }
            }, GpuBoardWindow.configuration(false));
        }
        if (failure.get() != null) { throw new AssertionError("Building catalog rendering", failure.get()); }
    }

    private static void checkMaterials(BoardScene scene) throws IOException {
        GpuAssets assets = new GpuAssets();
        try {
            for (var tile : scene.tiles()) {
                for (var feature : tile.features()) {
                    if (!feature.asset().startsWith("buildings/")) { continue; }
                    assertTrue(tile.detailedGround(), "Structures must not replace the native ground: " + tile.coords());
                    assertEquals(BoardScene.Surface.GRASS, tile.surface());
                    if (BoardIndustrial.supports(feature)) {
                        // A generator key is not a standalone GLB: inspect the actual generated plant.
                        var model = assets.industrial(BoardIndustrial.layout(scene, tile, feature));
                        for (var material : model.materials) {
                            var diffuse = material.get(TextureAttribute.class, TextureAttribute.Diffuse);
                            assertNotNull(diffuse, feature.asset() + " " + material.id);
                            assertSame(assets.material("structures/industrial/" + material.id),
                                  diffuse.textureDescription.texture, feature.asset() + " " + material.id);
                        }
                    } else {
                        var building = assets.building(feature.asset(), Math.round(feature.height()),
                              ConnectedKit.reach(scene, tile, Math.round(feature.height())), GpuBuilding.seed(tile, feature), false);
                        assertNotNull(building, "Catalog entry must supply a building kit: " + feature.asset());
                        for (int lod = 0; lod < building.lodCount(); lod++) {
                            var instance = building.instance(lod);
                            assertTrue(instance.calculateBoundingBox(new BoundingBox()).isValid(), feature.asset() + " LOD " + lod);
                            if (feature.kind() == BoardScene.FeatureKind.BUILDING) {
                                assertTrue(java.util.stream.StreamSupport.stream(instance.materials.spliterator(), false)
                                      .anyMatch(material -> material.has(GpuBuildingPaint.TYPE)),
                                      feature.asset() + " LOD " + lod + " retains its paintable surfaces after upload");
                            }
                        }
                    }
                }
            }
            assertEquals(java.util.Map.of(), assets.takeMissing(), "Catalog material checks must not use missing-model cubes");
            for (var surface : BoardScene.Surface.values()) {
                // Classic walls are the Classic tiles in terrain/classic; each loads at its authored size.
                var source = ImageIO.read(new File(Configuration.dataDir(), "models/board/textures/" + surface.classicWall + "_albedo.png"));
                assertNotNull(source, surface.classicWall);
                Texture texture = assets.material(surface.classicWall);
                assertEquals(source.getWidth(), texture.getWidth(), surface.classicWall + " keeps its authored width");
                assertEquals(source.getHeight(), texture.getHeight(), surface.classicWall + " keeps its authored height");
            }
        } finally {
            assets.dispose();
        }
    }

    private static void checkHeights(BoardScene scene, GpuTerrain terrain) {
        for (var tile : scene.tiles()) {
            for (var feature : tile.features()) {
                if (!feature.asset().startsWith("buildings/")) { continue; }
                BoundingBox placed = terrain.roofBounds(tile.coords());
                assertNotNull(placed, feature.asset());
                float storeys = feature.height() * BoardGeometry.level();
                if (megamek.common.board.TilesetScenery.customBuildingFile(feature.asset()).isFile()) {
                    // A kit's storeys stand at the rules height and its roof is a cap above them.
                    assertTrue(placed.getDepth() >= storeys - .001f && placed.getDepth() < storeys + BoardGeometry.level(),
                          feature.asset() + " " + placed.getDepth());
                } else {
                    assertEquals(storeys, placed.getDepth(), .001f,
                          "Authored height must not multiply the game's building/tank/industrial level count");
                }
            }
        }
        Coords coords = new Coords(4, 8);
        assertEquals(1, scene.tile(coords).features().stream().filter(f -> f.asset().equals("bridge")).count());
        // Generated deck material follows the attached paved road; its live texture combinations are covered by
        // GpuBridgeSmokeTest.bridgeMaterialsFollowApproachesAndDistantEdits.
        var bridge = BoardBridge.deck(scene, scene.tile(coords));
        assertNotNull(bridge);
        assertEquals(BoardRoad.Kind.PAVED, bridge.kind());
        BoundingBox placed = terrain.roofBounds(coords);
        assertNotNull(placed, "Bridge terrain must install one complete generated deck");
        float deck = 2 * BoardGeometry.level() + GpuRoads.SURFACE_LIFT * BoardGeometry.hexScale();
        // Generated slab and rails retain the bridge placement contract at both tested board scales.
        assertEquals(deck - 1.5f * BoardGeometry.hexScale(), placed.min.z, .15f * BoardGeometry.hexScale());
        // A banked span carries the authored terminal block above its rails.
        assertEquals(deck + Math.max(2.5f, BoardBridgeFooting.terminalHeight()) * BoardGeometry.hexScale(), placed.max.z,
              .15f * BoardGeometry.hexScale());
        Ray ray = new Ray(BoardGeometry.center(coords, 0).add(0, 10 * BoardGeometry.hexScale(), deck + 50),
              new Vector3(0, 0, -1));
        BoardGeometry.Hit hit = terrain.hit(scene, ray);
        assertNotNull(hit);
        assertEquals(coords, hit.coords());
        assertEquals(50, Math.sqrt(hit.distance()), .001, "Picking must hit the elevated bridge deck");
    }
}
