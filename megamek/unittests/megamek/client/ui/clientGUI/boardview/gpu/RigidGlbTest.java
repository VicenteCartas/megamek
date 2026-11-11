/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.model.data.ModelTexture;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The file/CPU boundary, independently encoded with interleaved attributes and normalized byte colors. */
class RigidGlbTest {
    @TempDir
    Path directory;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void emissionOffPreservesTheAuthoredLightAndSurvivesLodCopies() throws Exception {
        var document = levels();
        var material = (ObjectNode) document.get("materials").get(0);
        material.putArray("emissiveFactor").add(.4).add(.2).add(.1);
        var extras = material.putObject("extras").put("mmEmission", "off");
        for (var level : RigidGlb.loadLods(file(document, false))) {
            var data = (RigidGlb.Data) level;
            assertEquals(RigidGlb.Emission.OFF, data.emissions.get("paint"));
            assertEquals(.4f, data.materials.first().emissive.r, 1e-6);
            var sliced = new RigidGlb.Data(); sliced.inheritMaterials(data);
            assertEquals(data.emissions, sliced.emissions);
        }
        extras.put("mmEmission", "night");
        for (var level : RigidGlb.loadLods(file(document, false))) {
            assertEquals(RigidGlb.Emission.NIGHT, ((RigidGlb.Data) level).emissions.get("paint"));
            assertEquals(.4f, ((RigidGlb.Data) level).surfaces.get("paint").glow()[0], 1e-6, "Night restores the factor as a night colour");
        }
        extras.put("mmEmission", "invalid");
        var invalid = file(document, false);
        assertThrows(IllegalArgumentException.class, () -> RigidGlb.loadLods(invalid));
    }

    @Test
    void everyNightLightIsAColourOnItsMaterialsOwnProgram() throws Exception {
        var document = levels();
        var material = (ObjectNode) document.get("materials").get(0);
        material.putArray("emissiveFactor").add(.4).add(.2).add(.1);
        material.putObject("extras").put("mmEmission", "night");
        for (var level : RigidGlb.loadLods(file(document, false))) {
            var data = (RigidGlb.Data) level;
            assertNull(data.materials.first().emissive, "A colour attribute would select another program");
            assertArrayEquals(new float[] { .4f, .2f, .1f }, data.surfaces.get("paint").glow(), 1e-6f);
        }
    }

    @Test
    void nightEmissionIsOptInAndSurvivesLodAndGeometryCopies() throws Exception {
        var document = levels();
        var material = (ObjectNode) document.get("materials").get(0);
        material.putObject("extras").put("mmEmission", "night");
        for (var level : RigidGlb.loadLods(file(document, false))) {
            var data = (RigidGlb.Data) level;
            assertEquals(RigidGlb.Emission.NIGHT, data.emissions.get("paint"));
            var sliced = new RigidGlb.Data();
            sliced.inheritMaterials(data);
            assertEquals(data.emissions, sliced.emissions);
        }
        material.remove("extras");
        for (var level : RigidGlb.loadLods(file(document, false))) {
            assertTrue(((RigidGlb.Data) level).emissions.isEmpty());
        }
    }

    private ObjectNode document() throws Exception {
        return (ObjectNode) json.readTree("""
              {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
               "nodes":[{"name":"root","children":[1]},
                        {"name":"arm","mesh":0,"translation":[2,3,4],"scale":[1,2,3],
                         "rotation":[0,0.70710677,0,0.70710677]}],
               "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"COLOR_0":2,"TEXCOORD_0":3},
                           "indices":4,"material":0}]}],
               "materials":[{"name":"paint","pbrMetallicRoughness":{"baseColorFactor":[1,1,1,1],
                              "metallicFactor":0,"roughnessFactor":1}}],
               "buffers":[{"byteLength":116}],
               "bufferViews":[{"buffer":0,"byteLength":108,"byteStride":36},
                              {"buffer":0,"byteOffset":108,"byteLength":6}],
               "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
                            {"bufferView":0,"byteOffset":12,"componentType":5126,"count":3,"type":"VEC3"},
                            {"bufferView":0,"byteOffset":24,"componentType":5121,"normalized":true,"count":3,"type":"VEC4"},
                            {"bufferView":0,"byteOffset":28,"componentType":5126,"count":3,"type":"VEC2"},
                            {"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}]}
              """);
    }

    private FileHandle file(ObjectNode document, boolean invalidIndex) throws Exception {
        return file(document, invalidIndex, new byte[0]);
    }

    FileHandle file(ObjectNode document, boolean invalidIndex, byte[] image) throws Exception {
        int binaryLength = (116 + image.length + 3) & ~3;
        ((ObjectNode) document.get("buffers").get(0)).put("byteLength", 116 + image.length);
        byte[] text = json.writeValueAsString(document).getBytes(StandardCharsets.UTF_8);
        int padded = (text.length + 3) & ~3;
        ByteBuffer bytes = ByteBuffer.allocate(28 + padded + binaryLength).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0x46546c67).putInt(2).putInt(bytes.capacity()).putInt(padded).putInt(0x4e4f534a).put(text);
        while (bytes.position() < 20 + padded) { bytes.put((byte) ' '); }
        bytes.putInt(binaryLength).putInt(0x004e4942);
        for (int i = 0; i < 3; i++) {
            bytes.putFloat(i == 1 ? 2 : 1).putFloat(2).putFloat(i == 2 ? 2 : 3);
            bytes.putFloat(0).putFloat(1).putFloat(0);
            bytes.put((byte) 128).put((byte) 255).put((byte) 0).put((byte) 255);
            bytes.putFloat(.25f).putFloat(.75f);
        }
        bytes.putShort((short) 0).putShort((short) 1).putShort((short) (invalidIndex ? 3 : 2));
        bytes.position(28 + padded + 116);
        bytes.put(image);
        Path file = directory.resolve("mesh.glb");
        Files.write(file, bytes.array());
        return new FileHandle(file.toFile());
    }

    @Test
    void loadsEmptySquadWithoutAllocatingAnEmptyMesh() throws Exception {
        byte[] text = """
              {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
               "nodes":[{"name":"squad-0-lod0","children":[1]},{"name":"root"}]}
              """.getBytes(StandardCharsets.UTF_8);
        int padded = (text.length + 3) & ~3;
        ByteBuffer bytes = ByteBuffer.allocate(20 + padded).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0x46546c67).putInt(2).putInt(bytes.capacity()).putInt(padded).putInt(0x4e4f534a).put(text);
        while (bytes.hasRemaining()) { bytes.put((byte) ' '); }
        Path path = directory.resolve("squad-0.glb");
        Files.write(path, bytes.array());
        var levels = RigidGlb.loadLods(new FileHandle(path.toFile()));
        assertEquals(0, levels.getFirst().meshes.size);
        assertEquals("root", levels.getFirst().nodes.first().id);
        assertSame(levels.get(0), levels.get(1));
        assertSame(levels.get(0), levels.get(2));
    }

    @Test
    void preservesRigidHierarchyAndLinearColorsWhileConvertingAxesWithoutOpenGl() throws Exception {
        var document = document();
        ((ObjectNode) document.get("materials").get(0).get("pbrMetallicRoughness"))
              .putArray("baseColorFactor").add(.02).add(.18).add(.5).add(.4);
        var data = RigidGlb.load(file(document, false));
        var factor = data.materials.first().diffuse;
        assertEquals(.02f, factor.r, 1e-7);
        assertEquals(.18f, factor.g, 1e-7);
        assertEquals(.5f, factor.b, 1e-7);
        assertEquals(.4f, factor.a, 1e-7);
        var node = data.nodes.first().children[0];
        assertEquals("arm", node.id);
        assertEquals("paint", node.parts[0].materialId);
        assertEquals(2, node.translation.x);
        assertEquals(-4, node.translation.y);
        assertEquals(3, node.translation.z);
        assertEquals(3, node.scale.y);
        assertEquals(2, node.scale.z);
        assertEquals(.70710677f, node.rotation.z, 1e-6);
        float[] vertices = data.meshes.first().vertices;
        assertEquals(1, vertices[0]);
        assertEquals(-3, vertices[1]);
        assertEquals(2, vertices[2]);
        assertEquals(1, vertices[5]);
        assertEquals(128 / 255f, vertices[6], 1e-6);
        assertEquals(1, vertices[7]);
        assertEquals(0, vertices[8]);
        assertEquals(.25f, vertices[10]);
        assertEquals(.75f, vertices[11]);
        assertEquals(3, data.meshes.first().parts[0].indices.length);
    }

    @Test
    void rejectsOutOfRangeIndicesBeforeGraphicsAllocation() throws Exception {
        var file = file(document(), true);
        assertThrows(IllegalArgumentException.class, () -> RigidGlb.load(file));
    }

    @Test
    void refusesExternalBuffersAndUnsupportedRequiredExtensions() throws Exception {
        var external = document();
        ((ObjectNode) external.get("buffers").get(0)).put("uri", "https://invalid.example/mesh.bin");
        var file = file(external, false);
        assertThrows(IllegalArgumentException.class, () -> RigidGlb.load(file));
        var compressed = document();
        compressed.putArray("extensionsRequired").add("KHR_draco_mesh_compression");
        var compressedFile = file(compressed, false);
        assertThrows(IllegalArgumentException.class, () -> RigidGlb.load(compressedFile));
    }

    FileHandle sided(boolean doubleSided) throws Exception {
        var document = document();
        ((ObjectNode) document.get("materials").get(0)).put("doubleSided", doubleSided);
        var node = (ObjectNode) document.get("nodes").get(1);
        node.remove(java.util.List.of("translation", "rotation", "scale"));
        return file(document, false);
    }

    @Test
    void doubleSidedMaterialsPreservePaintAndReverseWindingAndNormals() throws Exception {
        for (boolean indexed : new boolean[] { true, false }) {
            var document = document();
            ((ObjectNode) document.get("materials").get(0)).put("doubleSided", true);
            if (!indexed) { ((ObjectNode) document.get("meshes").get(0).get("primitives").get(0)).remove("indices"); }
            var mesh = RigidGlb.load(file(document, false)).meshes.first();
            var indices = mesh.parts[0].indices;
            assertEquals(6, indices.length);
            for (int corner = 0; corner < 3; corner++) {
                int front = Short.toUnsignedInt(indices[corner]) * RigidGlb.STRIDE;
                int back = Short.toUnsignedInt(indices[5 - corner]) * RigidGlb.STRIDE;
                for (int attribute = 0; attribute < RigidGlb.STRIDE; attribute++) {
                    float expected = mesh.vertices[front + attribute];
                    if (attribute >= 3 && attribute < 6) { expected = -expected; }
                    assertEquals(expected, mesh.vertices[back + attribute], .00001f,
                          "Reverse faces preserve position, vertex colour and UV, and flip only the normal");
                }
            }
        }
    }

    @Test
    void mixedMaterialsKeepSingleSidedFacesAndShareReverseVertices() throws Exception {
        var document = document();
        var materials = (com.fasterxml.jackson.databind.node.ArrayNode) document.get("materials");
        materials.addObject().put("name", "two-sided").put("doubleSided", true);
        var primitives = (com.fasterxml.jackson.databind.node.ArrayNode) document.get("meshes").get(0).get("primitives");
        primitives.add(primitives.get(0).deepCopy());
        primitives.add(primitives.get(0).deepCopy());
        ((ObjectNode) primitives.get(1)).put("material", 1);
        ((ObjectNode) primitives.get(2)).put("material", 1);
        var mesh = RigidGlb.load(file(document, false)).meshes.first();
        assertArrayEquals(new short[] { 0, 1, 2 }, mesh.parts[0].indices);
        assertEquals(6, mesh.parts[1].indices.length);
        assertArrayEquals(mesh.parts[1].indices, mesh.parts[2].indices);
        assertEquals(6 * RigidGlb.STRIDE, mesh.vertices.length, "Shared attributes need only one reverse vertex set");
        assertEquals(3 * RigidGlb.STRIDE, RigidGlb.load(sided(false)).meshes.first().vertices.length);
    }

    @Test
    void cutoutsKeepTheirAuthoredThresholdWithoutBlendingOutsideBoardGlass() throws Exception {
        var document = document();
        ((ObjectNode) document.get("materials").get(0)).put("doubleSided", true)
              .put("alphaMode", "MASK").put("alphaCutoff", .37f);
        var data = (RigidGlb.Data) RigidGlb.load(file(document, false));
        assertEquals(.37f, data.alphaTests.get(data.materials.first().id), .0001f);
        assertEquals(6, data.meshes.first().parts[0].indices.length, "Cutout backs remain visible");
        ((ObjectNode) document.get("materials").get(0)).put("alphaMode", "BLEND");
        var file = file(document, false);
        assertThrows(IllegalArgumentException.class, () -> RigidGlb.load(file), "Only board glass blends");
    }

    private ObjectNode levels() throws Exception {
        var document = document();
        var nodes = (com.fasterxml.jackson.databind.node.ArrayNode) document.get("nodes");
        var meshes = (com.fasterxml.jackson.databind.node.ArrayNode) document.get("meshes");
        meshes.add(meshes.get(0).deepCopy());
        ((ObjectNode) meshes.get(0).get("primitives").get(0)).putObject("extras").put("mmPart", "arm-paint");
        ((ObjectNode) meshes.get(1).get("primitives").get(0)).putObject("extras").put("mmPart", "arm-paint");
        nodes.addObject().put("name", "root").putArray("children").add(3);
        var arm = nodes.get(1).deepCopy();
        ((ObjectNode) arm).put("mesh", 1);
        nodes.add(arm);
        nodes.addObject().put("name", "mesh-lod0").putArray("children").add(0);
        nodes.addObject().put("name", "mesh-lod2").putArray("children").add(2);
        ((ObjectNode) document.get("scenes").get(0)).putArray("nodes").add(4).add(5);
        return document;
    }

    @Test
    void isolatesLevelsWithRepeatedJointAndPartNamesAndResolvesMissingLevelsOnce() throws Exception {
        var levels = RigidGlb.loadLods(file(levels(), false));
        assertSame(levels.get(0), levels.get(1), "Missing LOD1 reuses LOD0 data");
        assertNotSame(levels.get(1), levels.get(2), "An authored LOD2 remains available after a gap");
        for (var level : levels) {
            assertEquals("root", level.nodes.first().id, "The packaging group is stripped from the rig");
            assertEquals("arm", level.nodes.first().children[0].id);
            assertEquals("arm-paint", level.nodes.first().children[0].parts[0].meshPartId);
            assertEquals(1, level.meshes.first().parts.length, "Other levels must not inflate this level's budget");
            assertEquals(1, UnitModelDescriptor.triangleCount(level));
        }
    }

    /** Only a group's {@code -lodN} ending counts: {@code mesh.glb} may hold {@code a-lod0} and {@code a-lod2}. */
    @Test
    void aLevelIsItsGroupsEndingWhateverTheFileIsCalled() throws Exception {
        var document = levels();
        ((ObjectNode) document.get("nodes").get(4)).put("name", "a-lod0");
        ((ObjectNode) document.get("nodes").get(5)).put("name", "a-lod2");
        var levels = RigidGlb.loadLods(file(document, false));
        assertSame(levels.get(0), levels.get(1), "Missing LOD1 reuses LOD0 data");
        assertNotSame(levels.get(1), levels.get(2), "The authored LOD2 loads");
    }

    @Test
    void requiresLod0AndIdentityPackagingGroups() throws Exception {
        var absent = levels();
        ((ObjectNode) absent.get("nodes").get(4)).put("name", "mesh-lod1");
        var missing = file(absent, false);
        assertThrows(IllegalArgumentException.class, () -> RigidGlb.loadLods(missing));
        var moved = levels();
        ((ObjectNode) moved.get("nodes").get(4)).putArray("translation").add(0).add(1).add(0);
        var translated = file(moved, false);
        assertThrows(IllegalArgumentException.class, () -> RigidGlb.loadLods(translated));
    }

    @Test
    void refusesAFileWhoseLevelsAreNotNamedGroups() throws Exception {
        // The same geometry as the grouped file, but without its <name>-lodN packaging groups.
        var ungrouped = file(document(), false);
        var error = assertThrows(IllegalArgumentException.class, () -> RigidGlb.loadLods(ungrouped));
        assertTrue(error.getMessage().contains("-lod0"), error.getMessage());
        // The single-level reader used for kits still reads it.
        assertEquals("arm", RigidGlb.load(ungrouped).nodes.first().children[0].id);
    }

    @Test
    void resolvesSharedLocalTextureWithoutAllocatingItAndRejectsRemoteImages() throws Exception {
        var textured = document();
        textured.putArray("images").addObject().put("uri", "foliage.png");
        textured.putArray("textures").addObject().put("source", 0);
        ((ObjectNode) textured.get("materials").get(0).get("pbrMetallicRoughness"))
              .putObject("baseColorTexture").put("index", 0);
        Files.write(directory.resolve("foliage.png"), encodedImage("png"));
        var data = RigidGlb.load(file(textured, false));
        assertEquals(directory.resolve("foliage.png").toString(), data.materials.first().textures.first().fileName);
        ((ObjectNode) textured.get("images").get(0)).put("uri", "https://invalid.example/foliage.png");
        var remote = file(textured, false);
        assertThrows(IllegalArgumentException.class, () -> RigidGlb.load(remote));
    }

    @Test
    void retainsLinearSurfaceFactorsAndPackedMapWithGltfDefaults() throws Exception {
        var document = document();
        var pbr = (ObjectNode) document.get("materials").get(0).get("pbrMetallicRoughness");
        pbr.put("roughnessFactor", .37).put("metallicFactor", .8);
        document.putArray("images").addObject().put("uri", "surface.png");
        document.putArray("textures").addObject().put("source", 0);
        var map = pbr.putObject("metallicRoughnessTexture").put("index", 0);
        Files.write(directory.resolve("surface.png"), encodedImage("png"));
        var data = (RigidGlb.Data) RigidGlb.load(file(document, false));
        var surface = data.surfaces.get("paint");
        assertEquals(.37f, surface.roughness(), 1e-6);
        assertEquals(.8f, surface.metallic(), 1e-6);
        assertEquals(directory.resolve("surface.png").toString(), surface.map());
        assertTrue(data.images.containsKey(surface.map()), "The texture library must be able to resolve the packed map");
        map.put("texCoord", 1);
        assertThrows(IllegalArgumentException.class, () -> RigidGlb.load(file(document, false)));
        pbr.remove(List.of("roughnessFactor", "metallicFactor", "metallicRoughnessTexture"));
        var defaults = ((RigidGlb.Data) RigidGlb.load(file(document, false))).surfaces.get("paint");
        assertEquals(1, defaults.roughness());
        assertEquals(1, defaults.metallic());
    }

    @Test
    void aBoardAlbedoMakesTheMaterialItsWholeGodotMaterial() throws Exception {
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        var previous = megamek.common.Configuration.dataDir();
        megamek.common.Configuration.setDataDir(directory.resolve("data").toFile());
        try {
            Path folder = Files.createDirectories(directory.resolve("data/models/board/textures/test"));
            var image = new java.awt.image.BufferedImage(2, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, 0x808080);
            image.setRGB(1, 0, 0x404040);
            for (String map : List.of("albedo", "orm", "normal", "heightmap", "emission")) {
                BoardMaterialFiles.write(folder, "panel_" + map + ".png", image);
            }
            BoardMaterialFiles.tres(folder, "panel", "orm", "normal", "heightmap", "ao", "metallic=0.5",
                  "metallic_texture=ExtResource( 2 )", "metallic_texture_channel=2", "emission_enabled=true",
                  "emission=Color(1, 0.5, 0, 1)", "emission_energy_multiplier=3.0", "emission_texture=ExtResource( 5 )",
                  "heightmap_scale=2.0", "heightmap_deep_parallax=true", "heightmap_min_layers=4",
                  "heightmap_max_layers=16", "heightmap_flip_tangent=true");
            Path tres = folder.resolve("panel.tres");
            Files.writeString(tres, Files.readString(tres).replace("[resource]",
                  "[ext_resource path=\"panel_emission.png\" type=\"Texture\" id=5]\n[resource]"));
            var document = document();
            document.putArray("images").addObject().put("uri", "data/models/board/textures/test/panel_albedo.png");
            document.putArray("textures").addObject().put("source", 0);
            var pbr = (ObjectNode) document.get("materials").get(0).get("pbrMetallicRoughness");
            pbr.put("roughnessFactor", .3).putObject("baseColorTexture").put("index", 0);
            var data = (RigidGlb.Data) RigidGlb.load(file(document, false));
            var surface = data.surfaces.get("paint");
            // The .tres, not the GLB's factor, is the finish: its ORM map carries the factors.
            assertEquals(1, surface.roughness());
            assertEquals(1, surface.metallic());
            assertEquals(1, surface.scatter(), "The finish map's red channel holds the (disabled, so zero) scattering");
            assertEquals(folder.resolve("panel_orm.png").toString(), surface.map());
            assertArrayEquals(new float[] { 3, 3, 3 }, surface.tint(), "Godot's add operator: the map times the energy");
            assertArrayEquals(new float[] { 3, 3 * GpuColor.linear(.5f), 0 }, surface.glow(), 1e-6f,
                  "Godot's add operator: the colour times the energy, decoded from sRGB");
            assertEquals(new RigidGlb.Relief(folder.resolve("panel_heightmap.png").toString(), -.02f, .02f, 4, 16),
                  surface.relief(), "Godot's depth is heightmap_scale / 100 in UV, flipped along the tangent");
            var roles = new java.util.HashMap<Integer, String>();
            data.materials.first().textures.forEach(texture -> roles.put(texture.usage, texture.fileName));
            assertEquals(folder.resolve("panel_normal.png").toString(), roles.get(ModelTexture.USAGE_NORMAL));
            assertEquals(folder.resolve("panel_orm.png").toString(), roles.get(ModelTexture.USAGE_AMBIENT));
            assertEquals(folder.resolve("panel_emission.png").toString(), roles.get(ModelTexture.USAGE_EMISSIVE));
            for (String key : roles.values()) { assertTrue(data.images.containsKey(key), key); }
            assertTrue(data.images.containsKey(surface.relief().map()));
        } finally { megamek.common.Configuration.setDataDir(previous); }
    }

    /** A level named mesh-lod0 whose one node, arm, draws material "paint" on the albedo of the board material test/glass. */
    private ObjectNode onBoardMaterial(String... features) throws Exception {
        Path folder = Files.createDirectories(directory.resolve("data/models/board/textures/test"));
        // Two colours: a uniform emission map joins the glow (GpuMaterialMaps).
        var image = new java.awt.image.BufferedImage(2, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x808080);
        image.setRGB(1, 0, 0x404040);
        BoardMaterialFiles.write(folder, "glass_albedo.png", image);
        BoardMaterialFiles.write(folder, "glass_emission.png", image);
        BoardMaterialFiles.tres(folder, "glass", features);
        Path tres = folder.resolve("glass.tres");
        Files.writeString(tres, Files.readString(tres).replace("[resource]",
              "[ext_resource path=\"glass_emission.png\" type=\"Texture\" id=5]\n[resource]"));
        var document = document();
        document.putArray("images").addObject().put("uri", "data/models/board/textures/test/glass_albedo.png");
        document.putArray("textures").addObject().put("source", 0);
        ((ObjectNode) document.get("materials").get(0).get("pbrMetallicRoughness")).putObject("baseColorTexture").put("index", 0);
        ((com.fasterxml.jackson.databind.node.ArrayNode) document.get("nodes")).addObject().put("name", "mesh-lod0").putArray("children").add(1);
        ((ObjectNode) document.get("scenes").get(0)).putArray("nodes").add(2);
        return document;
    }

    private static boolean emissionMapped(RigidGlb.Data data, String id) {
        for (var material : data.materials) {
            if (material.id.equals(id)) {
                for (var texture : material.textures) {
                    if (texture.usage == ModelTexture.USAGE_EMISSIVE) { return true; }
                }
            }
        }
        return false;
    }

    private RigidGlb.Data level(ObjectNode document) throws Exception {
        return (RigidGlb.Data) RigidGlb.loadLods(file(document, false)).getFirst();
    }

    @Test
    void aMaterialsEmissionOverridesItsBoardMaterial() throws Exception {
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        var previous = megamek.common.Configuration.dataDir();
        megamek.common.Configuration.setDataDir(directory.resolve("data").toFile());
        try {
            var document = onBoardMaterial("emission_enabled=true", "emission=Color(1, 0.5, 0, 1)", "emission_texture=ExtResource( 5 )",
                  "metadata/mmEmission=\"night\"", "metadata/mmPaintMask=true");
            var material = (ObjectNode) document.get("materials").get(0);
            var library = level(document);
            assertEquals("test/glass", library.surfaces.get("paint").board());
            assertEquals(RigidGlb.Emission.NIGHT, library.emissions.get("paint"), "The .tres says when it glows");
            assertTrue(library.paintMasks.contains("paint"), "The .tres says its alpha is a paint mask");
            assertTrue(emissionMapped(library, "paint"));

            var extras = material.putObject("extras").put("mmEmission", "always");
            var always = level(document);
            assertEquals(RigidGlb.Emission.ALWAYS, always.emissions.get("paint"), "The GLB's choice wins over a night-only .tres");
            assertArrayEquals(library.surfaces.get("paint").glow(), always.surfaces.get("paint").glow(), "The library's own glow, now by day too");
            assertTrue(emissionMapped(always, "paint"));

            material.putArray("emissiveFactor").add(.4).add(.2).add(.1);
            var own = level(document);
            assertEquals(.4f, own.materials.first().emissive.r, 1e-6);
            assertArrayEquals(new float[3], own.surfaces.get("paint").glow(), "An own factor replaces the library's glow");
            assertFalse(emissionMapped(own, "paint"), "An own factor replaces the library's emission map");

            extras.put("mmEmission", "night");
            var night = level(document);
            assertNull(night.materials.first().emissive);
            assertArrayEquals(new float[] { .4f, .2f, .1f }, night.surfaces.get("paint").glow(), 1e-6f);

            extras.put("mmEmission", "off");
            assertEquals(RigidGlb.Emission.OFF, level(document).emissions.get("paint"));

            extras.put("mmEmission", "Night");
            var wrongCase = file(document, false);
            assertThrows(IllegalArgumentException.class, () -> RigidGlb.loadLods(wrongCase), "The tools read these values exactly");
        } finally { megamek.common.Configuration.setDataDir(previous); }
    }

    @Test
    void aGlbsAlphaModeDecidesOverItsBoardMaterialsTransparency() throws Exception {
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        var previous = megamek.common.Configuration.dataDir();
        megamek.common.Configuration.setDataDir(directory.resolve("data").toFile());
        try {
            var document = onBoardMaterial("transparency=1", "blend_mode=4");
            var material = ((ObjectNode) document.get("materials").get(0)).put("alphaMode", "MASK").put("alphaCutoff", .3);
            var plant = level(document);
            assertFalse(plant.blends.containsKey("paint"), "A plant stays a cutout on a blended .tres, or the ground shows through it");
            assertEquals(.3f, plant.alphaTests.get("paint"), 1e-6f);
            material.put("alphaMode", "BLEND").remove("alphaCutoff");
            var glass = level(document);
            assertEquals(4, glass.blends.get("paint"), "Glass blends in its .tres's mode (premultiplied: its reflection stays whole)");
            assertFalse(glass.alphaTests.containsKey("paint"));
        } finally { megamek.common.Configuration.setDataDir(previous); }
    }

    @Test
    void aGlbsPaintMaskOnlyAppliesToAnImageOfItsOwn() throws Exception {
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        var previous = megamek.common.Configuration.dataDir();
        megamek.common.Configuration.setDataDir(directory.resolve("data").toFile());
        try {
            var document = onBoardMaterial();
            var material = (ObjectNode) document.get("materials").get(0);
            material.putObject("extras").put("mmPaintMask", true);
            assertTrue(level(document).paintMasks.isEmpty(), "A library material's alpha is what its .tres says");
            ((ObjectNode) material.get("pbrMetallicRoughness")).remove("baseColorTexture");
            assertTrue(level(document).paintMasks.contains("paint"));
        } finally { megamek.common.Configuration.setDataDir(previous); }
    }

    /** The kit of one piece, node arm of the level {@code document} holds. */
    private List<RigidGlb.Data> kit(ObjectNode document) throws Exception {
        var modelled = RigidGlb.loadLods(file(document, false));
        return GeneratedKit.lods("kit", true, modelled, List.of("wall"), List.of(new KitOutline.Piece("", null, List.of(), List.of())),
              java.util.Map.of(), (role, piece, lod) -> {
                  var mesh = new GeneratedMesh();
                  mesh.piece(modelled.get(lod), "arm", new com.badlogic.gdx.math.Matrix4(), 0);
                  return java.util.Map.of("", mesh);
              });
    }

    @Test
    void aKitDrawsEachPieceMaterialAsItsBoardMaterialInItsOwnFinish() throws Exception {
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        var previous = megamek.common.Configuration.dataDir();
        megamek.common.Configuration.setDataDir(directory.resolve("data").toFile());
        try {
            var document = onBoardMaterial("emission_enabled=true", "emission=Color(1, 0.5, 0, 1)", "emission_texture=ExtResource( 5 )",
                  "metadata/mmEmission=\"night\"");
            var material = ((ObjectNode) document.get("materials").get(0)).put("name", "test/glass");
            var extras = material.putObject("extras").put("mmBuildingPaint", true);
            var library = kit(document).getFirst();
            assertTrue(library.buildingPaint.contains("test/glass"));
            assertEquals(RigidGlb.Emission.NIGHT, library.emissions.get("test/glass"), "From the .tres");
            assertTrue(emissionMapped(library, "test/glass"));

            extras.put("mmEmission", "always");
            material.putArray("emissiveFactor").add(.4).add(.2).add(.1);
            var own = kit(document).getFirst();
            assertEquals(RigidGlb.Emission.ALWAYS, own.emissions.get("test/glass"));
            assertArrayEquals(new float[] { .4f, .2f, .1f }, own.surfaces.get("test/glass").glow(), 1e-6f);
            assertFalse(emissionMapped(own, "test/glass"), "As on a rigid model, an own factor replaces the library's emission map");

            // Each finish of one board material draws apart, under its own id (authored_examples#material_id).
            var materials = (com.fasterxml.jackson.databind.node.ArrayNode) document.get("materials");
            materials.add(material.deepCopy().put("name", "test/glass#off"));
            ((ObjectNode) materials.get(1).get("extras")).put("mmEmission", "off");
            var primitives = (com.fasterxml.jackson.databind.node.ArrayNode) document.get("meshes").get(0).get("primitives");
            primitives.add(((ObjectNode) primitives.get(0).deepCopy()).put("material", 1));
            var finishes = kit(document).getFirst();
            assertEquals(RigidGlb.Emission.ALWAYS, finishes.emissions.get("test/glass"));
            assertEquals(RigidGlb.Emission.OFF, finishes.emissions.get("test/glass#off"));
            primitives.remove(1);
            materials.remove(1);

            material.put("name", "paint");
            var unnamed = assertThrows(IllegalArgumentException.class, () -> kit(document));
            assertTrue(unnamed.getMessage().contains("not named after its board material"), unnamed.getMessage());
            material.put("name", "test/glass");

            ((ObjectNode) material.get("pbrMetallicRoughness")).remove("baseColorTexture");
            var plain = assertThrows(IllegalArgumentException.class, () -> kit(document));
            assertTrue(plain.getMessage().contains("library materials only"), plain.getMessage());
        } finally { megamek.common.Configuration.setDataDir(previous); }
    }

    @Test
    void retainsLinearEmissionWithoutAddingItToOrdinaryMaterials() throws Exception {
        var document = document();
        assertNull(RigidGlb.load(file(document, false)).materials.first().emissive,
              "A missing emissive factor is black and needs no material flag");
        var material = (ObjectNode) document.get("materials").get(0);
        material.putArray("emissiveFactor").add(.02).add(.18).add(.5);
        var emission = RigidGlb.load(file(document, false)).materials.first().emissive;
        assertNotNull(emission);
        assertEquals(.02f, emission.r, 1e-7);
        assertEquals(.18f, emission.g, 1e-7);
        assertEquals(.5f, emission.b, 1e-7);
        assertEquals(1f, emission.a);
        material.putArray("emissiveFactor").add(0).add(0).add(0);
        assertNull(RigidGlb.load(file(document, false)).materials.first().emissive);
    }

    @Test
    void emissiveTexturesKeepTheirFactorAndShareImagesAcrossRolesAndLevels() throws Exception {
        var document = levels();
        document.putArray("images").addObject().put("uri", "lamp.png");
        document.putArray("textures").addObject().put("source", 0);
        Files.write(directory.resolve("lamp.png"), encodedImage("png"));
        var material = (ObjectNode) document.get("materials").get(0);
        ((ObjectNode) material.get("pbrMetallicRoughness")).putObject("baseColorTexture").put("index", 0);
        var texture = material.putObject("emissiveTexture").put("index", 0);
        material.putArray("emissiveFactor").add(.1).add(.25).add(.5);
        var levels = RigidGlb.loadLods(file(document, false));
        var near = (RigidGlb.Data) levels.getFirst();
        var far = (RigidGlb.Data) levels.get(2);
        var source = near.materials.first();
        assertEquals(new Color(.1f, .25f, .5f, 1), source.emissive);
        assertEquals(source.emissive, far.materials.first().emissive);
        assertEquals(2, source.textures.size);
        assertEquals(ModelTexture.USAGE_EMISSIVE, source.textures.get(1).usage);
        assertEquals(source.textures.first().fileName, source.textures.get(1).fileName);
        assertEquals(1, near.images.size(), "Base color and emission share one authored image");
        String key = source.textures.get(1).fileName;
        assertSame(near.images.get(key), far.images.get(key), "The library keeps ownership across LODs");
        material.remove("emissiveFactor");
        assertEquals(Color.BLACK, RigidGlb.loadLods(file(document, false)).getFirst().materials.first().emissive,
              "A texture with glTF's default black factor must not emit light");
        texture.put("texCoord", 1);
        assertThrows(IllegalArgumentException.class, () -> RigidGlb.load(file(document, false)));
    }

    @Test
    void retainsAuthoredNormalTextureAlongsideColorWithoutAllocatingGraphics() throws Exception {
        var textured = document();
        var images = textured.putArray("images");
        images.addObject().put("uri", "skin.png");
        images.addObject().put("uri", "skin-normal.png");
        var textures = textured.putArray("textures");
        textures.addObject().put("source", 0);
        textures.addObject().put("source", 1);
        var material = (ObjectNode) textured.get("materials").get(0);
        ((ObjectNode) material.get("pbrMetallicRoughness")).putObject("baseColorTexture").put("index", 0);
        material.putObject("normalTexture").put("index", 1);
        Files.write(directory.resolve("skin.png"), encodedImage("png"));
        Files.write(directory.resolve("skin-normal.png"), encodedImage("png"));
        var data = RigidGlb.load(file(textured, false));
        assertEquals(2, data.materials.first().textures.size);
        assertEquals(com.badlogic.gdx.graphics.g3d.model.data.ModelTexture.USAGE_NORMAL,
              data.materials.first().textures.get(1).usage);
        assertEquals(directory.resolve("skin-normal.png").toString(),
              data.materials.first().textures.get(1).fileName);
        ((ObjectNode) material.get("normalTexture")).put("scale", .2);
        var unsupported = file(textured, false);
        assertThrows(IllegalArgumentException.class, () -> RigidGlb.load(unsupported));
    }

    @Test
    void retainsPackedOcclusionTextureAlongsideColorAndNormal() throws Exception {
        var textured = document();
        var images = textured.putArray("images");
        var textures = textured.putArray("textures");
        for (String name : List.of("leaf.png", "leaf-normal.png", "leaf-surface.png")) {
            images.addObject().put("uri", name);
            textures.addObject().put("source", images.size() - 1);
            Files.write(directory.resolve(name), encodedImage("png"));
        }
        var material = (ObjectNode) textured.get("materials").get(0);
        ((ObjectNode) material.get("pbrMetallicRoughness")).putObject("baseColorTexture").put("index", 0);
        material.putObject("normalTexture").put("index", 1);
        var occlusion = material.putObject("occlusionTexture").put("index", 2);
        var data = RigidGlb.load(file(textured, false));
        assertEquals(3, data.materials.first().textures.size);
        var surface = data.materials.first().textures.get(2);
        assertEquals(com.badlogic.gdx.graphics.g3d.model.data.ModelTexture.USAGE_AMBIENT, surface.usage);
        assertEquals(directory.resolve("leaf-surface.png").toString(), surface.fileName);
        occlusion.put("texCoord", 1);
        assertThrows(IllegalArgumentException.class, () -> RigidGlb.load(file(textured, false)));
        occlusion.put("texCoord", 0).put("strength", .3);
        assertThrows(IllegalArgumentException.class, () -> RigidGlb.load(file(textured, false)));
    }

    static byte[] encodedImage(String format) throws Exception {
        var pixels = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        pixels.setRGB(0, 0, 0x669933);
        pixels.setRGB(1, 1, 0x3366cc);
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(pixels, format, bytes);
        return bytes.toByteArray();
    }

    FileHandle embedded(String format) throws Exception {
        return embedded(format, encodedImage(format));
    }

    FileHandle embedded(String format, byte[] bytes) throws Exception {
        var document = levels();
        int view = document.get("bufferViews").size();
        ((com.fasterxml.jackson.databind.node.ArrayNode) document.get("bufferViews")).addObject()
              .put("buffer", 0).put("byteOffset", 116).put("byteLength", bytes.length);
        document.putArray("images").addObject().put("bufferView", view)
              .put("mimeType", format.equals("png") ? "image/png" : "image/jpeg");
        document.putArray("samplers").addObject().put("wrapS", 33071).put("wrapT", 33648)
              .put("minFilter", 9987).put("magFilter", 9728);
        document.putArray("textures").addObject().put("source", 0).put("sampler", 0);
        var pbr = (ObjectNode) document.get("materials").get(0).get("pbrMetallicRoughness");
        pbr.putObject("baseColorTexture").put("index", 0);
        pbr.putObject("metallicRoughnessTexture").put("index", 0);
        return file(document, false, bytes);
    }

    @Test
    void importsEmbeddedPngAndJpegAndSharesImageSourcesAcrossLevels() throws Exception {
        for (String format : new String[] { "png", "jpg" }) {
            var levels = RigidGlb.loadLods(embedded(format));
            var lod0 = (RigidGlb.Data) levels.getFirst();
            var lod2 = (RigidGlb.Data) levels.get(2);
            var image = lod0.images.values().iterator().next();
            assertArrayEquals(encodedImage(format), image.encoded());
            assertSame(image, lod2.images.get(image.key()));
            assertEquals(33071, image.wrapS());
            assertEquals(33648, image.wrapT());
            assertEquals(9728, image.magFilter());
        }
    }

    /**
     * The level count a placed object's level choice reads from the JSON alone (b10-f1) is the decoder's: each counted
     * level its own mesh, every further level the last one again.
     */
    @Test
    void theJsonLevelCountIsTheDecodedLevels() {
        var root = megamek.common.Configuration.dataDir().toPath().resolve("models/board");
        var expected = java.util.Map.of("scenery/roofs/ledge", 1, "scenery/maglev/cab", 2, "fungus/cliff-cups", 3,
              "vegetation/tree-broad", 4, "mars/brain-lobes", 4);
        for (var entry : expected.entrySet()) {
            var file = megamek.common.board.BoardModelBounds.file(root.toFile(), entry.getKey());
            int levels = RigidGlb.levels(file);
            assertEquals((int) entry.getValue(), levels, entry.getKey());
            var data = RigidGlb.loadLods(new FileHandle(file), root);
            for (int level = 0; level < data.size(); level++) {
                assertEquals(Math.min(level, levels - 1), data.indexOf(data.get(level)), entry.getKey() + " level " + level);
            }
        }
    }

    /**
     * The shipped cargo crate: a label's characters take their atlas cells on the middle places of each side, the outer
     * places stay on the blank cell, and nothing but the label moves; its body's albedo alpha is a paint mask.
     */
    @Test
    void aLabelWritesItsCharactersOnTheMiddlePlacesAndLeavesTheRestBlank() {
        var root = megamek.common.Configuration.dataDir().toPath().resolve("models/board");
        var file = root.resolve("scenery/military/cargo-crate.glb").toFile();
        var crate = (RigidGlb.Data) RigidGlb.loadLods(new FileHandle(file), root).getFirst();
        assertEquals(java.util.Set.of("cargo-crate"), crate.paintMasks);
        assertEquals(java.util.Set.of("cargo-crate-stencil"), crate.labels);
        var slotted = (RigidGlb.Data) RigidGlb.colourSlotMesh(crate);
        assertEquals(crate.paintMasks, slotted.paintMasks, "Shared colour meshes retain the albedo paint mask");
        assertEquals(crate.labels, slotted.labels);
        var label = java.util.Arrays.stream(crate.meshes.first().parts).filter(part -> part.id.equals("cargo-crate-label"))
              .findFirst().orElseThrow();
        java.util.Set<Integer> places = new java.util.TreeSet<>();
        for (short index : label.indices) { places.add(Short.toUnsignedInt(index)); }
        float[] blank = crate.meshes.first().vertices.clone();
        RigidGlb.label(crate, "47");
        float[] written = crate.meshes.first().vertices;
        int four = RigidGlb.LABEL_CHARACTERS.indexOf('4'), seven = RigidGlb.LABEL_CHARACTERS.indexOf('7');
        for (int vertex = 0; vertex < blank.length / RigidGlb.STRIDE; vertex++) {
            int at = vertex * RigidGlb.STRIDE + 10;
            int place = (int) Math.floor(blank[at]);
            if (!places.contains(vertex) || place == 0 || place == 3) {
                assertArrayEquals(java.util.Arrays.copyOfRange(blank, at - 10, at + 2),
                      java.util.Arrays.copyOfRange(written, at - 10, at + 2), "vertex " + vertex);
                continue;
            }
            int character = place == 1 ? four : seven;
            float u = (blank[at] - place) * 8, v = blank[at + 1] * 8;
            assertEquals(place + (character % 8 + u) / 8, written[at], 1e-5f);
            assertEquals((character / 8 + v) / 8, written[at + 1], 1e-5f);
        }
    }

    /** The shipped car: one mesh whose paint slot takes each row's paint; nothing else changes colour. */
    @Test
    void aSharedMeshTakesItsReplacementColoursInItsSlotsOnly() {
        var root = megamek.common.Configuration.dataDir().toPath().resolve("models/board");
        var file = root.resolve("scenery/vehicles/car.glb").toFile();
        var slots = RigidGlb.colourSlots(file);
        assertEquals(List.of("Paint"), slots.stream().map(RigidGlb.ColourSlot::name).toList());
        var own = (RigidGlb.Data) RigidGlb.loadLods(new FileHandle(file), root).getFirst();
        var silver = (RigidGlb.Data) RigidGlb.loadLods(new FileHandle(file), root).getFirst();
        RigidGlb.recolour(silver, megamek.common.board.BoardDecoration.Colours.of("#b8b8b8"));
        float[] before = own.meshes.first().vertices, after = silver.meshes.first().vertices;
        var paint = com.badlogic.gdx.graphics.Color.valueOf(slots.getFirst().colour());
        paint.r = linear(paint.r); paint.g = linear(paint.g); paint.b = linear(paint.b);
        int painted = 0;
        for (int vertex = 0; vertex < own.slots.length; vertex++) {
            int at = vertex * RigidGlb.STRIDE + 6;
            if (own.slots[vertex] == 0) {
                assertArrayEquals(java.util.Arrays.copyOfRange(before, at, at + 4), java.util.Arrays.copyOfRange(after, at, at + 4));
            } else if (Math.abs(before[at] - paint.r) + Math.abs(before[at + 1] - paint.g) + Math.abs(before[at + 2] - paint.b) < .003f) {
                // The default paint itself becomes the replacement, to within an 8-bit step.
                assertEquals(linear(0xb8 / 255f), after[at], .5f / 255);
                assertEquals(linear(0xb8 / 255f), after[at + 2], .5f / 255);
                painted++;
            }
        }
        assertTrue(painted > 0, "The car has painted vertices");
    }

    /**
     * The editor's one shared mesh (colourSlotMesh) and a placement's packed colours give, by the shader's rule
     * (model-colour-slots.glsl), the vertex colours of the CPU copy recolour makes: the car, and a pool's four slots
     * with one kept. A far colour checks the shade ratio; the rest of each vertex is unchanged.
     */
    @Test
    void theSharedMeshAndPackedColoursGiveTheRecolouredCopysVertexColours() {
        var root = megamek.common.Configuration.dataDir().toPath().resolve("models/board");
        var cases = java.util.Map.of("scenery/vehicles/car", megamek.common.board.BoardDecoration.Colours.of("#1ee0f0"),
              "scenery/pools/freeform", megamek.common.board.BoardDecoration.Colours.of("#63753d", null, "#200a04", "#123456"));
        for (var entry : cases.entrySet()) {
            var file = root.resolve(entry.getKey() + ".glb").toFile();
            var own = (RigidGlb.Data) RigidGlb.loadLods(new FileHandle(file), root).getFirst();
            var copy = (RigidGlb.Data) RigidGlb.loadLods(new FileHandle(file), root).getFirst();
            RigidGlb.recolour(copy, entry.getValue());
            var shared = RigidGlb.colourSlotMesh(own).meshes.first();
            float[] packed = RigidGlb.packed(entry.getValue());
            int stride = RigidGlb.STRIDE + 4, slotted = 0;
            float[] ownVertices = own.meshes.first().vertices, expected = copy.meshes.first().vertices;
            assertEquals(ownVertices.length / RigidGlb.STRIDE * stride, shared.vertices.length);
            for (int vertex = 0; vertex < ownVertices.length / RigidGlb.STRIDE; vertex++) {
                int at = vertex * stride;
                assertArrayEquals(java.util.Arrays.copyOfRange(ownVertices, vertex * RigidGlb.STRIDE, (vertex + 1) * RigidGlb.STRIDE),
                      java.util.Arrays.copyOfRange(shared.vertices, at, at + RigidGlb.STRIDE), "The shared mesh keeps the model's own vertex");
                int slot = Math.round(shared.vertices[at + RigidGlb.STRIDE + 3]) - 1;
                for (int channel = 0; channel < 3; channel++) {
                    float colour = shared.vertices[at + 6 + channel];
                    if (slot >= 0 && packed[slot] >= 0) {
                        int rgb = Math.round(packed[slot]);
                        colour = shared.vertices[at + RigidGlb.STRIDE + channel] * linear((rgb >> (16 - 8 * channel) & 255) / 255f);
                        slotted++;
                    }
                    assertEquals(expected[vertex * RigidGlb.STRIDE + 6 + channel], colour, 1e-5f, entry.getKey() + " vertex " + vertex);
                }
            }
            assertTrue(slotted > 0, entry.getKey() + " has recoloured vertices");
        }
        assertEquals(null, RigidGlb.packed(megamek.common.board.BoardDecoration.Colours.NONE), "Own colours pass nothing");
    }

    private static float linear(float display) {
        return display <= .04045f ? display / 12.92f : (float) Math.pow((display + .055) / 1.055, 2.4);
    }

    /** A tree's winter form is its bare file read with the snow material variant: snow cards, untinted crown. */
    @Test
    void aWinterFormIsTheBareTreesSnowVariant() {
        var root = megamek.common.Configuration.dataDir().toPath().resolve("models/board").toFile();
        var source = RigidGlb.source(root, "vegetation/pine-snow");
        assertEquals(new java.io.File(root, "vegetation/pine.glb"), source.file());
        assertTrue(source.snow());
        var bare = RigidGlb.loadLods(RigidGlb.source(root, "vegetation/pine"), root.toPath());
        var snow = RigidGlb.loadLods(source, root.toPath());
        com.badlogic.gdx.graphics.g3d.model.data.ModelMaterial crown = null;
        for (var material : snow.getFirst().materials) { if (material.id.equals("canopy-snow-cutout")) { crown = material; } }
        assertTrue(crown.textures.first().fileName.replace('\\', '/').contains("textures/vegetation/conifer-snow-cutout_albedo.png"));
        float[] bareVertices = bare.getFirst().meshes.first().vertices, snowVertices = snow.getFirst().meshes.first().vertices;
        assertEquals(bareVertices.length, snowVertices.length, "The winter form keeps the bare geometry");
        boolean tinted = false;
        for (var part : snow.getFirst().meshes.first().parts) {
            if (!part.id.contains("canopy")) { continue; }
            for (short index : part.indices) {
                int at = Short.toUnsignedInt(index) * RigidGlb.STRIDE + 6;
                // Snow cards keep the bare cards' shade without the species' pigment: grey.
                assertEquals(snowVertices[at], snowVertices[at + 1], .01f);
                assertEquals(snowVertices[at], snowVertices[at + 2], .01f);
                tinted |= Math.abs(bareVertices[at] - bareVertices[at + 2]) > .05f;
            }
        }
        assertTrue(tinted, "The bare crown is tinted");
        // The distant cards: everything they show is snow, so all of it takes light as a hard surface (green 1).
        for (int vertex = 0; vertex < snow.get(3).meshes.first().vertices.length / RigidGlb.STRIDE; vertex++) {
            assertEquals(1, snow.get(3).meshes.first().vertices[vertex * RigidGlb.STRIDE + 7], 1e-4f);
        }
    }
}
