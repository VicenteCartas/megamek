/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureArray;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.utils.ScreenUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import megamek.common.Configuration;
import megamek.common.board.Board;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.opengl.GL11;

/** Real button, sibling asset sync, EDT capture, GL uploads and repeated disposal in a running battle view. */
@Tag("on-demand")
class GpuAssetReloadSmokeTest {
    @TempDir
    Path directory;

    @Test
    void reloadsEditedAssetsAndCanRetryWithoutReopeningTheBoard() throws Exception {
        File data = Configuration.dataDir();
        File images = Configuration.imagesDir();
        File fonts = Configuration.fontsDir();
        Path authoring = Files.createDirectories(directory.resolve("mm-data/data"));
        directory = Files.createDirectories(directory.resolve("repository/megamek/data"));
        Path models = data.toPath().resolve("models");
        try (var paths = Files.walk(models)) {
            for (Path path : paths.toList()) {
                Path target = directory.resolve("models").resolve(models.relativize(path));
                if (Files.isDirectory(path)) { Files.createDirectories(target); }
                else { Files.copy(path, target); }
            }
        }
        // The merged board editor initializes its schema even when this test opens a battle view.
        Path blueprint = directory.resolve("board-editor/blueprint.json");
        Files.createDirectories(blueprint.getParent());
        Files.copy(data.toPath().resolve("board-editor/blueprint.json"), blueprint);
        var failure = new AtomicReference<Throwable>();
        Configuration.setImagesDir(images);
        Configuration.setFontsDir(fonts);
        Configuration.setDataDir(directory.toFile());
        try (var fixture = GpuBoardFixture.create(Board.createEmptyBoard(6, 6))) {
            var glb = new RigidGlbTest();
            glb.directory = Files.createDirectories(directory.resolve("models/reload"));
            glb.embedded("png");
            Files.writeString(glb.directory.resolve("component.json"), """
                  {"schema":2,"kind":"equipment","family":"laser","mesh":"mesh.glb",
                   "bounds":{"min":[0,0,0],"max":[4,4,4]},"rig":"rigid-v1",
                   "joints":{"root":"root"},"locations":{},"hardpoints":[],"emitters":[]}
                  """);
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                final long deadline = System.nanoTime() + 180_000_000_000L;
                int step;
                GpuTerrain originalTerrain;
                Texture originalTexture;
                GpuAssets.MaterialArrays originalSculpt;
                GpuAssets.MaterialArrays originalMagma;
                GpuAssets.LavaEmission originalEmission;
                TextureArray originalRoughness;
                List<String> sculptLayers;
                float sculptTile, asphaltCoverage;
                GpuUnitModels.ModularAsset originalUnit;
                ModelInstance originalInstance;
                float treeHeight, rockHeight, scatterHeight, otherScatterHeight, unitHeight;
                BoardGeometry.Tuning tuning;
                Path tileset = directory.resolve("models/board/classic/tileset/classic.tileset");
                byte[] tilesetBytes;
                Path rockMesh = directory.resolve("models/board/rocks/block-0.glb");
                byte[] rockBytes;
                long generation;
                Path shaders = directory.resolve("shaders");
                String compositeSource;
                String unitSource;
                Map<String, ShaderProgram> originalPrograms;

                @Override
                public void create() {
                    super.create();
                    boardCamera.setIsometric(false);
                    GpuBoardTestUi.<Slider>tuning(this, "Unit scale").setValue(1.15f);
                }

                @Override
                public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Asset reload must finish");
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "GL state during reload step " + step);
                        // The tuning model's Reload assets and its status, which the Tuning panel's footer shows.
                        var model = GpuBoardTestUi.tuning(this);
                        TextButton button = model.reloadAssets();
                        Label status = model.assetReloadStatus();
                        if (step == 0 && frames() >= 5 && !terrain().busy()) {
                            originalTerrain = terrain();
                            originalTexture = assets().material("terrain/classic/rock");
                            sculptLayers = sculptLayers();
                            originalSculpt = assets().sculptArray(sculptLayers);
                            originalMagma = assets().fullSizeMaps();
                            originalEmission = assets().lavaEmission();
                            originalRoughness = assets().finishArrays(surfaces(sculptLayers), sculptLayers.size()).maps();
                            sculptTile = assets().sculptTile("grassland-ground");
                            asphaltCoverage = assets().roadCoverage("roads/asphalt");
                            treeHeight = assets().model("vegetation/tree").calculateBoundingBox(new BoundingBox()).getHeight();
                            rockHeight = BoardRocks.rock(true, 0).height();
                            scatterHeight = BoardScatter.bush(BoardScene.Surface.SAND, 0).height();
                            otherScatterHeight = BoardScatter.bush(BoardScene.Surface.SAND, 1).height();
                            originalUnit = library().modular("reload/component.json");
                            assertNotNull(originalUnit);
                            unitHeight = originalUnit.model().calculateBoundingBox(new BoundingBox()).getHeight();
                            originalInstance = instances().get("1:-1");
                            assertNotNull(originalInstance);
                            tuning = BoardGeometry.tuning();
                            generation = fixture.source.takeFrame().boardGeneration();
                            originalPrograms = programs();
                            assertTrue(originalPrograms.keySet().stream().anyMatch(key -> key.startsWith("units")));
                            Files.createDirectories(shaders);
                            String composite = GpuShaderSource.read("atmosphere-composite.frag");
                            String outputColor = "fragColor = vec4(color,";
                            assertTrue(composite.contains(outputColor), "The reload fixture must edit the actual display output");
                            compositeSource = composite.replace(outputColor,
                                  "fragColor = vec4(vec3(0.85, 0.15, 0.65) + color * 0.001,");
                            unitSource = GpuShaderSource.read("unit-material.frag")
                                  .replace("return diffuse;", "return diffuse.bgr;");
                            Files.writeString(shaders.resolve("atmosphere-composite.frag"), compositeSource);
                            Files.writeString(shaders.resolve("unit-material.frag"), unitSource);
                            // Compilation drops comments (GpuGlsl.withoutComments), so the shared include's edit is a define.
                            Files.writeString(shaders.resolve("light-model.glsl"),
                                  GpuShaderSource.read("light-model.glsl") + "\n#define ASSET_RELOAD_INCLUDE\n");
                            Path textures = directory.resolve("models/board/textures");
                            Path authoredRock = authoring.resolve("models/board/textures/terrain/classic/rock_albedo.png");
                            Files.createDirectories(authoredRock.getParent());
                            GpuAssetReloadTest.png(authoredRock, 0xff0000ff);
                            // A finished export changes every map, wider than its catalogued 512 pixels: at the same
                            // texel density one repeat now covers 1.25 times the ground. Deliberately distinct
                            // height/AO values expose accidental sRGB conversion of numerical data.
                            Path surfaces = textures.resolve("terrain/surfaces");
                            square(surfaces.resolve("grassland-ground_albedo.png"), 640, 0x1080e0, false);
                            square(surfaces.resolve("grassland-ground_heightmap.png"), 640, 0x404040, true);
                            // Godot's normal: the board's 0x40c0f0 with green flipped (+G up the image).
                            square(surfaces.resolve("grassland-ground_normal.png"), 640, 0x403ff0, false);
                            // ORM: occlusion 0x60 and a roughness map that varies, so it is authored (times 0.5).
                            var orm = new java.awt.image.BufferedImage(640, 640, java.awt.image.BufferedImage.TYPE_INT_RGB);
                            for (int y = 0; y < 640; y++) {
                                for (int x = 0; x < 640; x++) { orm.setRGB(x, y, x == 639 ? 0x60ff00 : 0x608000); }
                            }
                            assertTrue(javax.imageio.ImageIO.write(orm, "png", surfaces.resolve("grassland-ground_orm.png").toFile()));
                            Path grass = surfaces.resolve("grassland-ground.tres");
                            Files.writeString(grass, Files.readString(grass).replace("roughness = 1.0", "roughness = 0.5"));
                            Path lava = textures.resolve("terrain/lava");
                            GpuAssetReloadTest.png(lava.resolve("lava-flow_emission.png"), 0xff204080);
                            GpuAssetReloadTest.png(lava.resolve("lava-flow_coverage.png"), 0xff404040);
                            Path molten = lava.resolve("lava-flow.tres");
                            Files.writeString(molten, Files.readString(molten).replace("emission_energy = 5.0", "emission_energy = 4.0"));
                            // The catalogue is data too: grass takes twice the metres per repeat (half its density),
                            // and asphalt is catalogued at half its width, so the same texture covers twice the road.
                            Path catalog = textures.resolve("materials.json");
                            var catalogMapper = new ObjectMapper();
                            var entries = (ObjectNode) catalogMapper.readTree(catalog.toFile());
                            var loose = (ObjectNode) entries.get("loose");
                            var grassEntry = (ObjectNode) loose.get("terrain/surfaces/grassland-ground");
                            grassEntry.put("metres", grassEntry.get("metres").floatValue() * 2);
                            var asphaltEntry = (ObjectNode) loose.get("roads/asphalt");
                            asphaltEntry.put("pixels", asphaltEntry.get("pixels").intValue() / 2);
                            catalogMapper.writerWithDefaultPrettyPrinter().writeValue(catalog.toFile(), entries);
                            assertEquals(asphaltCoverage, assets().roadCoverage("roads/asphalt"),
                                  "Edits wait for the actual Reload action");
                            assertSame(originalEmission, assets().lavaEmission(), "Edits wait for the actual Reload action");
                            Path authoredTree = authoring.resolve("models/board/vegetation/tree.glb");
                            Files.createDirectories(authoredTree.getParent());
                            Files.copy(directory.resolve("models/board/vegetation/tree.glb"), authoredTree);
                            scaleMesh(authoredTree);
                            scaleMesh(directory.resolve("models/board/rocks/block-0.glb"));
                            scaleMesh(directory.resolve("models/board/scatter/bush-0.glb"));
                            scaleMesh(glb.directory.resolve("mesh.glb"));
                            // The HUD's Tuning utility opens the panel whose footer shows Reload assets.
                            GpuBoardTestUi.click("tuning-button");
                            GpuBoardTestUi.stage().draw();
                            GpuBoardTestUi.pressTuning(GpuBoardTestUi.tuning(this, "tuning-reload-assets"));
                            assertTrue(button.isDisabled());
                            step = 1;
                        } else if (step == 1 && !button.isDisabled()) {
                            assertEquals("OK", status.getText().toString());
                            assertEquals(-1, Files.mismatch(
                                  authoring.resolve("models/board/textures/terrain/classic/rock_albedo.png"),
                                  directory.resolve("models/board/textures/terrain/classic/rock_albedo.png")),
                                  "Reload must stage the sibling checkout's texture before uploading it");
                            Map<String, ShaderProgram> reloaded = programs();
                            assertEquals(originalPrograms.keySet(), reloaded.keySet());
                            originalPrograms.forEach((name, shader) -> assertNotSame(shader, reloaded.get(name), name));
                            for (var entry : reloaded.entrySet()) {
                                assertTrue(entry.getValue().isCompiled(), entry.getKey());
                                if (entry.getKey().startsWith("units")) {
                                    assertTrue(entry.getValue().getFragmentShaderSource().contains("return diffuse.bgr;"));
                                    assertTrue(entry.getValue().getFragmentShaderSource().contains("ASSET_RELOAD_INCLUDE"));
                                }
                            }
                            assertCompositePixels();
                            assertNotSame(originalTerrain, terrain());
                            assertEquals(0, originalTexture.getTextureObjectHandle(), "Old textures must be released");
                            assertEquals(0, originalSculpt.color().getTextureObjectHandle(), "Old colour array must be released");
                            assertEquals(0, originalSculpt.data().getTextureObjectHandle(), "Old numerical array must be released");
                            var sculpt = assets().sculptArray(sculptLayers);
                            assertNotSame(originalSculpt, sculpt);
                            assertEquals(sculptTile * 2 * 1.25f, assets().sculptTile("grassland-ground"), .0001f,
                                  "Reload must re-read the catalogue and the material's width as well as its maps");
                            assertEquals(asphaltCoverage * 2, assets().roadCoverage("roads/asphalt"),
                                  "A catalogue edit reaches the road's repeat after Reload assets");
                            int grassLayer = sculptLayers.indexOf("grassland-ground") * 2;
                            assertTrue(grassLayer >= 0);
                            // FBO readback returns stored bytes: RGB is now encoded sRGB; height, normal and AO stay raw.
                            assertArrayEquals(new float[] { 16f / 255f, 128f / 255f, 224f / 255f, 64f / 255f },
                                  arrayPixel(sculpt.color(), grassLayer / 2), .0006f);
                            assertArrayEquals(new float[] { 64f / 255f, 192f / 255f, 240f / 255f, 96f / 255f },
                                  arrayPixel(sculpt.data(), grassLayer / 2), .0006f);
                            assertEquals(0, originalRoughness.getTextureObjectHandle(), "Old roughness arrays must be released");
                            var authored = assets().finishArrays(surfaces(sculptLayers), sculptLayers.size());
                            assertNotSame(originalRoughness, authored.maps());
                            // Rows of 639 texels at 128 and one at 255, times the 0.5 factor: 64 and 128, sent with their mean.
                            assertEquals((639 * 64 + 128) / 640f / 255, authored.surface()[grassLayer * 2], .00001f,
                                  "A varying roughness map is authored as a map, beside its mean");
                            int roughnessLayer = (int) authored.surface()[grassLayer * 2 + 3];
                            assertTrue(roughnessLayer >= 0, "Reload must retain the authored map binding");
                            assertArrayEquals(new float[] { 64f / 255, 0, 0, 1 },
                                  arrayPixel(authored.maps(), roughnessLayer), .0006f,
                                  "The new raw surface data must reach GPU memory after the actual Reload assets action");
                            assertEquals(0, originalMagma.color().getTextureObjectHandle());
                            assertEquals(0, originalMagma.data().getTextureObjectHandle());
                            var magma = assets().fullSizeMaps();
                            assertNotSame(originalMagma, magma);
                            assertArrayEquals(new float[] { 32f / 255, 64f / 255, 128f / 255, 64f / 255 },
                                  arrayPixel(magma.data(), (int) GpuAssets.LAVA_DATA + 2), .0006f, "Emitted RGB and coverage remain raw numeric data");
                            var emission = assets().lavaEmission();
                            assertNotSame(originalEmission, emission);
                            assertEquals(4, emission.scale());
                            assertEquals(128f / 255, emission.red(), 1e-7);
                            assertEquals(256f / 255, emission.green(), 1e-7);
                            assertEquals(512f / 255, emission.blue(), 1e-7);
                            assertSame(assets(), field(field(terrain(), "lavaLighting"), "assets"),
                                  "The replaced spill-light snapshot borrows the same reloaded material owner");
                            Texture texture = assets().material("terrain/classic/rock");
                            ByteBuffer pixels = ByteBuffer.allocateDirect(texture.getWidth() * texture.getHeight() * 4);
                            texture.bind();
                            GL11.glGetTexImage(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixels);
                            assertEquals(0x0000ffff, pixels.getInt(0), "The new image must reach GPU memory");
                            assertEquals(treeHeight * 2, assets().model("vegetation/tree").calculateBoundingBox(new BoundingBox()).getHeight(), .001f);
                            assertEquals(rockHeight * 2, BoardRocks.rock(true, 0).height(), .001f);
                            assertEquals(scatterHeight * 2, BoardScatter.bush(BoardScene.Surface.SAND, 0).height(), .001f);
                            assertEquals(otherScatterHeight, BoardScatter.bush(BoardScene.Surface.SAND, 1).height(),
                                  "Editing one scatter file leaves the other variants unchanged");
                            var unit = library().modular("reload/component.json");
                            assertNotSame(originalUnit, unit);
                            assertNotNull(unit);
                            assertEquals(unitHeight * 2, unit.model().calculateBoundingBox(new BoundingBox()).getHeight(), .001f);
                            assertNotSame(originalInstance, instances().get("1:-1"));
                            assertEquals(tuning, BoardGeometry.tuning());
                            assertFalse(boardCamera.isIsometric());
                            assertEquals(generation, fixture.source.takeFrame().boardGeneration());
                            tilesetBytes = Files.readAllBytes(tileset);
                            Files.delete(tileset);
                            GpuBoardTestUi.pressTuning(GpuBoardTestUi.tuning(this, "tuning-reload-assets"));
                            step = 2;
                        } else if (step == 2 && !button.isDisabled()) {
                            assertEquals("FAIL (logs)", status.getText().toString());
                            Files.write(tileset, tilesetBytes);
                            rockBytes = Files.readAllBytes(rockMesh);
                            Files.writeString(rockMesh, "unfinished GLB export");
                            GpuBoardTestUi.pressTuning(GpuBoardTestUi.tuning(this, "tuning-reload-assets"));
                            step = 3;
                        } else if (step == 3 && !button.isDisabled()) {
                            assertEquals("FAIL (logs)", status.getText().toString());
                            // A failure after GPU disposal still leaves the panel usable and the board unpickable.
                            Gdx.input.getInputProcessor().mouseMoved(100, 200);
                            Gdx.input.getInputProcessor().touchDown(100, 200, 0, com.badlogic.gdx.Input.Buttons.LEFT);
                            Gdx.input.getInputProcessor().touchUp(100, 200, 0, com.badlogic.gdx.Input.Buttons.LEFT);
                            Files.write(rockMesh, rockBytes);
                            GpuBoardTestUi.pressTuning(GpuBoardTestUi.tuning(this, "tuning-reload-assets"));
                            step = 4;
                        } else if (step == 4 && !button.isDisabled()) {
                            Files.writeString(shaders.resolve("atmosphere-composite.frag"), "invalid shader source");
                            GpuBoardTestUi.pressTuning(GpuBoardTestUi.tuning(this, "tuning-reload-assets"));
                            step = 5;
                        } else if (step == 5 && !button.isDisabled()) {
                            assertEquals("FAIL (logs)", status.getText().toString());
                            Files.writeString(shaders.resolve("atmosphere-composite.frag"), compositeSource);
                            Files.writeString(shaders.resolve("unit-material.frag"), "invalid shader source");
                            GpuBoardTestUi.pressTuning(GpuBoardTestUi.tuning(this, "tuning-reload-assets"));
                            step = 6;
                        } else if (step == 6 && !button.isDisabled()) {
                            assertEquals("FAIL (logs)", status.getText().toString());
                            // This failure occurs inside the scene framebuffer, after a ModelBatch has begun.
                            assertEquals(0, GL11.glGetInteger(GL20.GL_FRAMEBUFFER_BINDING));
                            GpuBoardTestUi.stage().draw();
                            Files.writeString(shaders.resolve("unit-material.frag"), unitSource);
                            GpuBoardTestUi.pressTuning(GpuBoardTestUi.tuning(this, "tuning-reload-assets"));
                            step = 7;
                        } else if (step == 7 && !button.isDisabled()) {
                            assertCompositePixels();
                            assertEquals(tuning, BoardGeometry.tuning());
                            assertFalse(boardCamera.isIsometric());
                            assertEquals(generation, fixture.source.takeFrame().boardGeneration());
                            String nativePlan = System.getProperty("megamek.gpu.nativeMaterialPlan", "");
                            if (nativePlan.isEmpty()) { Gdx.app.exit(); }
                            else {
                                originalTerrain = terrain();
                                originalSculpt = assets().sculptArray(sculptLayers);
                                originalRoughness = assets().finishArrays(surfaces(sculptLayers), sculptLayers.size()).maps();
                                originalMagma = assets().fullSizeMaps();
                                originalEmission = assets().lavaEmission();
                                copyNativeBatch(Path.of(nativePlan), authoring);
                                Files.delete(shaders.resolve("atmosphere-composite.frag"));
                                Files.delete(shaders.resolve("unit-material.frag"));
                                Files.delete(shaders.resolve("light-model.glsl"));
                                GpuBoardTestUi.pressTuning(GpuBoardTestUi.tuning(this, "tuning-reload-assets"));
                                assertTrue(button.isDisabled());
                                step = 8;
                            }
                        } else if (step == 8 && !button.isDisabled() && !terrain().busy()) {
                            assertFalse(status.getText().toString().startsWith("FAIL"));
                            assertNotSame(originalTerrain, terrain());
                            assertEquals(0, originalSculpt.color().getTextureObjectHandle());
                            assertEquals(0, originalSculpt.data().getTextureObjectHandle());
                            assertEquals(0, originalRoughness.getTextureObjectHandle());
                            assertEquals(0, originalMagma.color().getTextureObjectHandle());
                            assertEquals(0, originalMagma.data().getTextureObjectHandle());
                            assertNotSame(originalEmission, assets().lavaEmission());
                            assertSame(assets(), field(field(terrain(), "lavaLighting"), "assets"));
                            checkNativeBatch(assets(), sculptLayers, directory);
                            assertEquals(tuning, BoardGeometry.tuning());
                            assertFalse(boardCamera.isIsometric());
                            assertEquals(generation, fixture.source.takeFrame().boardGeneration());
                            GpuBoardTestUi.capture(new File(System.getProperty("megamek.gpu.screenshots"),
                                  "official-native-batch-after-reload.png"));
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }

                @SuppressWarnings("unchecked")
                List<String> sculptLayers() throws Exception {
                    return (List<String>) field(terrain(), "SCULPT_LAYERS");
                }

                GpuTerrain terrain() throws Exception { return (GpuTerrain) field(this, "terrain"); }
                GpuAssets assets() throws Exception { return (GpuAssets) field(terrain(), "assets"); }
                GpuUnitModels library() throws Exception { return (GpuUnitModels) field(this, "unitModels"); }

                Map<String, ShaderProgram> programs() throws Exception {
                    Map<String, ShaderProgram> result = new LinkedHashMap<>();
                    Object atmosphere = field(this, "atmosphere");
                    result.put("fog", (ShaderProgram) field(atmosphere, "fogShader"));
                    result.put("composite", (ShaderProgram) field(atmosphere, "compositeShader"));
                    result.put("outlines", (ShaderProgram) field(field(this, "unitVisibility"), "shader"));
                    result.put("hex text", (ShaderProgram) field(field(this, "hexText"), "shader"));
                    addPrograms(result, "units", (ModelBatch) field(this, "unitBatch"));
                    addPrograms(result, "terrain", (ModelBatch) field(terrain(), "batch"));
                    return result;
                }
                @SuppressWarnings("unchecked")
                Map<String, ModelInstance> instances() throws Exception {
                    return (Map<String, ModelInstance>) field(this, "unitInstances");
                }
            }, GpuBoardWindow.configuration(false));
            if (failure.get() != null) { throw new AssertionError("Asset/shader reload failed", failure.get()); }
        } finally {
            Configuration.setDataDir(data);
            Configuration.setImagesDir(images);
            Configuration.setFontsDir(fonts);
            BoardRocks.reload();
            BoardScatter.reload();
            BoardGeometry.tune(BoardGeometry.DEFAULTS);
            SwingUtilities.invokeAndWait(() -> { });
        }
    }

    /** A fixed, externally verified native export set; copies only into this fixture's disposable data root. */
    private static void copyNativeBatch(Path planFile, Path destination) throws Exception {
        var plan = new ObjectMapper().readTree(planFile.toFile());
        assertTrue(plan.path("project_count").asInt() > 0 && !plan.path("files").isEmpty());
        Path source = planFile.getParent().resolve("candidate").toAbsolutePath().normalize();
        Path root = destination.toAbsolutePath().normalize();
        var paths = new java.util.HashSet<Path>();
        // Validate the complete set before replacing any temporary input.
        for (var entry : plan.path("files")) {
            Path relative = Path.of(entry.path("path").asText());
            Path from = source.resolve(relative).normalize(), to = root.resolve(relative).normalize();
            assertTrue(!relative.isAbsolute() && relative.normalize().equals(relative)
                  && from.startsWith(source) && to.startsWith(root));
            assertTrue(relative.startsWith(Path.of("models/board/textures")));
            assertTrue(paths.add(relative), "Native output names are unique");
            // Godot 4 Standard exports (resources and maps) and the catalogue of loose materials.
            assertTrue(relative.toString().endsWith(".png") || relative.toString().endsWith(".tres")
                  || relative.toString().equals(Path.of("models/board/textures/materials.json").toString()));
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                  .digest(Files.readAllBytes(from)));
            assertEquals(entry.path("after").asText(), hash, relative.toString());
        }
        for (Path relative : paths) {
            Path to = root.resolve(relative);
            Files.createDirectories(to.getParent());
            Files.copy(source.resolve(relative), to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The sculpt layers as the board material names the finish arrays take. */
    private static List<String> surfaces(List<String> layers) {
        return layers.stream().map(name -> "terrain/surfaces/" + name).toList();
    }

    /** Compare actual completed uploads with the packed native maps, retaining the RGB/data domain boundary. */
    private static void checkNativeBatch(GpuAssets assets, List<String> layers, Path data) throws Exception {
        var arrays = assets.sculptArray(layers);
        var authored = assets.finishArrays(surfaces(layers), layers.size());
        Path textures = data.resolve("models/board/textures");
        var catalog = GpuMaterialCatalog.read(textures.resolve("materials.json").toFile());
        assertEquals(34, layers.size(), "Current main terrain roles; scatter-stone and sand-verge are separate");
        assertTrue(layers.contains("tundra-ground-variant"), "The current array includes the second terrestrial tundra material");
        for (int index = 0; index < layers.size(); index++) {
            String name = layers.get(index), material = "terrain/surfaces/" + name;
            var definition = GodotMaterial.read(textures.resolve(material + ".tres").toFile(), textures.toFile());
            for (boolean color : new boolean[] { true, false }) {
                var layout = color ? GpuMaterialMaps.colorLayout(material) : GpuMaterialMaps.normalLayout(material);
                var packed = GpuMaterialMaps.pack(definition, layout);
                try {
                    TextureArray array = color ? arrays.color() : arrays.data();
                    assertEquals(packed.getWidth(), array.getWidth());
                    assertEquals(packed.getHeight(), array.getHeight());
                    int rgba = packed.getPixel(0, 0);
                    assertArrayEquals(new float[] { (rgba >>> 24) / 255f, (rgba >>> 16 & 255) / 255f,
                          (rgba >>> 8 & 255) / 255f, (rgba & 255) / 255f }, arrayPixel(array, index), .0006f,
                          "Actual post-Reload native " + name + (color ? " color/height" : " normal/AO"));
                } finally { packed.dispose(); }
            }
            assertEquals(catalogued(catalog, material, definition), assets.sculptTile(name), .000001f, name);
            var finish = GpuAssets.authoredFinish(definition);
            for (int channel = 0; channel < 3; channel++) {
                assertEquals(finish.surface()[channel], authored.surface()[index * 4 + channel], .000001f,
                      name + " sends its authored finish");
            }
            assertEquals(finish.map() != null, authored.surface()[index * 4 + 3] >= 0,
                  name + " has a surface layer exactly when its finish varies");
            assertEquals(finish.emission() != null, authored.tint()[index * 4 + 3] >= 0,
                  name + " has an emission layer exactly when its emission map varies");
        }
        for (String name : List.of("scatter-stone", "sand-verge")) {
            String material = "terrain/surfaces/" + name;
            var definition = GodotMaterial.read(textures.resolve(material + ".tres").toFile(), textures.toFile());
            assertEquals(catalogued(catalog, material, definition), assets.sculpt(name).tile(), .000001f, name);
        }
        // The earlier edit uses energy 4 and small heat maps. The official batch must restore the current
        // authored pair together and refresh the same emission owner used by spill lights.
        var molten = GodotMaterial.read(textures.resolve("terrain/lava/lava-flow.tres").toFile(), textures.toFile());
        var heat = GpuMaterialMaps.pack(molten, GpuMaterialMaps.Layout.MOLTEN_HEAT);
        var emission = assets.lavaEmission();
        try {
            var lava = assets.fullSizeMaps();
            int packed = heat.getPixel(0, 0);
            assertArrayEquals(new float[] { (packed >>> 24) / 255f, (packed >>> 16 & 255) / 255f,
                  (packed >>> 8 & 255) / 255f, (packed & 255) / 255f }, arrayPixel(lava.data(), (int) GpuAssets.LAVA_DATA + 2), .0006f,
                  "Reload restores authored emission and coverage in the actual lava array");
            assertEquals(5f, emission.scale(), "The current official lava definition restores energy 5");
            long red = 0, green = 0, blue = 0;
            for (int y = 0; y < heat.getHeight(); y++) {
                for (int x = 0; x < heat.getWidth(); x++) {
                    int pixel = heat.getPixel(x, y);
                    red += pixel >>> 24;
                    green += pixel >>> 16 & 255;
                    blue += pixel >>> 8 & 255;
                }
            }
            double factor = 5.0 / (255.0 * heat.getWidth() * heat.getHeight());
            assertArrayEquals(new float[] { (float) (red * factor), (float) (green * factor), (float) (blue * factor) },
                  new float[] { emission.red(), emission.green(), emission.blue() }, .000001f,
                  "The restored spill source comes from the same authored linear RGB image");
        } finally { heat.dispose(); }
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Actual native batch after Reload");
        System.out.println("Native batch Reload: 34 current color/data array layers; 36 catalogued repeats and "
              + "4 square vegetation images verified; every layer sends its authored finish.");
    }

    /** A sculpted material's repeat: its catalogued metres, widened with its albedo (GpuMaterialCatalog). */
    private static float catalogued(GpuMaterialCatalog catalog, String material, GodotMaterial definition) {
        int[] size = GpuMaterialMaps.size(definition.albedo());
        return catalog.loose(material).metres() * catalog.coverage(material, size[0], size[1]);
    }

    /** A square PNG of one colour: RGB, or eight-bit gray from the colour's red. */
    private static void square(Path file, int size, int rgb, boolean gray) throws Exception {
        var image = new java.awt.image.BufferedImage(size, size,
              gray ? java.awt.image.BufferedImage.TYPE_BYTE_GRAY : java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (gray) { image.getRaster().setSample(x, y, 0, rgb >>> 16 & 255); } else { image.setRGB(x, y, rgb); }
            }
        }
        assertTrue(javax.imageio.ImageIO.write(image, "png", file.toFile()));
    }

    /** Read one real array layer without allocating a copy of the entire terrain array. */
    private static float[] arrayPixel(TextureArray array, int layer) {
        int read = GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING);
        int draw = GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int target = org.lwjgl.opengl.GL30.glGenFramebuffers();
        try {
            org.lwjgl.opengl.GL30.glBindFramebuffer(GL20.GL_FRAMEBUFFER, target);
            org.lwjgl.opengl.GL30.glFramebufferTextureLayer(GL20.GL_FRAMEBUFFER, GL20.GL_COLOR_ATTACHMENT0,
                  array.getTextureObjectHandle(), 0, layer);
            assertEquals(GL20.GL_FRAMEBUFFER_COMPLETE,
                  org.lwjgl.opengl.GL30.glCheckFramebufferStatus(GL20.GL_FRAMEBUFFER));
            var pixel = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asFloatBuffer();
            GL11.glReadPixels(0, 0, 1, 1, GL20.GL_RGBA, GL20.GL_FLOAT, pixel);
            return new float[] { pixel.get(0), pixel.get(1), pixel.get(2), pixel.get(3) };
        } finally {
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, read);
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, draw);
            org.lwjgl.opengl.GL30.glDeleteFramebuffers(target);
        }
    }

    private static void addPrograms(Map<String, ShaderProgram> result, String name, ModelBatch batch) throws Exception {
        int index = 0;
        var provider = batch.getShaderProvider();
        if (provider instanceof GpuShaderProvider editable) { provider = editable.active(); }
        for (Object shader : (Iterable<?>) field(provider, "shaders")) {
            result.put(name + index++, (ShaderProgram) field(shader, "program"));
        }
    }

    /** The recompiled composite must actually reach the window, beyond merely changing cached source strings. */
    private static void assertCompositePixels() {
        byte[] pixels = ScreenUtils.getFrameBufferPixels(false);
        int edited = 0;
        for (int i = 0; i < pixels.length; i += 4) {
            if (Math.abs(Byte.toUnsignedInt(pixels[i]) - 217) <= 1
                  && Math.abs(Byte.toUnsignedInt(pixels[i + 1]) - 38) <= 1
                  && Math.abs(Byte.toUnsignedInt(pixels[i + 2]) - 166) <= 1) { edited++; }
        }
        assertTrue(edited > pixels.length / 40, "The edited composite should cover visible board pixels");
    }

    /** Edit authored node transforms while retaining the original GLB binary payload. */
    private static void scaleMesh(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int length = input.getInt(12);
        ObjectMapper mapper = new ObjectMapper();
        var document = mapper.readTree(new String(bytes, 20, length, StandardCharsets.UTF_8));
        for (var node : document.get("nodes")) {
            if (!node.has("mesh")) { continue; }
            var scale = mapper.createArrayNode();
            for (int i = 0; i < 3; i++) { scale.add(2 * (node.has("scale") ? node.get("scale").get(i).asDouble() : 1)); }
            ((ObjectNode) node).set("scale", scale);
        }
        byte[] json = mapper.writeValueAsBytes(document);
        int padded = (json.length + 3) & ~3;
        ByteBuffer output = ByteBuffer.allocate(bytes.length - length + padded).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(0x46546c67).putInt(2).putInt(output.capacity()).putInt(padded).putInt(0x4e4f534a).put(json);
        while (output.position() < 20 + padded) { output.put((byte) ' '); }
        output.put(bytes, 20 + length, bytes.length - 20 - length);
        Files.write(file, output.array());
    }
}
