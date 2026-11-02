/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BoardFungusTest {
    @Test
    void everyAuthoredBowlHasItsOwnOutlet() {
        int[] counts = { 1, 2, 1, 2 };
        for (int i = 0; i < BoardFungus.CUPS.size(); i++) {
            assertEquals(counts[i], BoardFungus.vents(BoardFungus.CUPS.get(i)).size(), BoardFungus.CUPS.get(i));
        }
        var forked = BoardFungus.vents("fungus/cup-forked");
        // Independently inspected in Blender: separate rims centred near (-4.2,-.7) and (6,1.6).
        assertEquals(-4.2f, forked.get(0).origin().x, .05f);
        assertEquals(6f, forked.get(1).origin().x, .05f);
        // Origins lie deep in each cavity, below the lowest rim; smoke must emerge through the opening.
        assertTrue(forked.get(0).origin().z > 14.6f && forked.get(0).origin().z < 24);
        assertTrue(forked.get(1).origin().z > 10.8f && forked.get(1).origin().z < 18);
        var trumpet = BoardFungus.vents("fungus/cup-trumpet");
        assertTrue(Math.abs(trumpet.get(0).origin().z - trumpet.get(1).origin().z) > 13,
              "The small basal bowl must not emit from the main trumpet's height");
    }

    @Test
    void outletsFollowTheFinalPlantTransformWithoutChangingTheirSourceGeometry() {
        var before = BoardFungus.vents("fungus/cup-forked").stream().map(v -> v.origin().cpy()).toList();
        var transform = new Matrix4().setToTranslation(41, -23, 17).rotate(Vector3.Z, 137).scale(.7f, 1.3f, 1.9f);
        var emitters = GpuFungus.emitters("fungus/cup-forked", transform);
        for (int i = 0; i < emitters.size(); i++) {
            assertTrue(before.get(i).cpy().mul(transform).epsilonEquals(emitters.get(i).origin(), .0001f));
            assertEquals(before.get(i), BoardFungus.vents("fungus/cup-forked").get(i).origin());
            float radius = BoardFungus.vents("fungus/cup-forked").get(i).radius();
            assertEquals(radius * 1.9f * 6, emitters.get(i).rise(), .0001f,
                  "A narrow plant retains enough vertical travel to rise through its bowl");
            assertEquals(Math.max(.25f * BoardGeometry.hexScale(), radius * .7f), emitters.get(i).radius(), .0001f);
        }
        assertEquals(emitters, GpuFungus.emitters("fungus/cup-forked", transform), "Rebuilding a chunk preserves plume phase");
        for (String asset : BoardFungus.SCATTER) { assertTrue(GpuFungus.emitters(asset, transform).isEmpty()); }
        for (String asset : BoardFungus.CLIFF) { assertTrue(GpuFungus.emitters(asset, transform).isEmpty()); }
        assertTrue(GpuFungus.emitters("vegetation/tree", transform).isEmpty());
    }

    @Test
    void porousSporeBodiesHaveNoIndividualMistEmitters() {
        var transform = new Matrix4().setToTranslation(41, -23, 17).rotate(Vector3.Z, 137).scale(.7f, 1.3f, 1.9f);
        for (String asset : BoardFungus.SPORES) {
            assertTrue(BoardFungus.vents(asset).isEmpty(), asset);
            assertTrue(GpuFungus.emitters(asset, transform).isEmpty(), asset);
            assertTrue(RigidGlb.sporeVents(new File("data/models/board/" + asset + ".glb")).isEmpty(), asset);
        }
    }

    /** Loading a glowing fungal material decodes its emission map with libGDX. */
    @BeforeAll
    static void natives() { GdxNativesLoader.load(); }

    @Test
    void referenceBoardUsesFungalColoniesAndPreservesItsWoodsRules() {
        Board board = new Board();
        board.load(new File("data/boards/Alien Worlds/32x17 Fungal Crevasse.board"));
        assertEquals(32, board.getWidth());
        assertEquals(17, board.getHeight());
        Set<String> used = new HashSet<>();
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                Coords at = new Coords(x, y);
                Hex hex = board.getHex(at);
                assertEquals(BoardScene.Surface.FUNGUS, BoardFeatures.surface(hex));
                assertEquals(1, BoardSurfaceBlend.capture(hex).fungus());
                var features = BoardFeatures.capture(hex, at, Map.of());
                int density = hex.terrainLevel(Terrains.WOODS);
                var cover = features.stream().filter(f -> f.kind() == BoardScene.FeatureKind.TREE).toList();
                assertEquals(density < 1, cover.isEmpty());
                assertTrue(cover.size() <= BoardTreeCoverage.colonyLimit(density));
                assertTrue(cover.stream().filter(f -> BoardFungus.SPORES.contains(f.asset())).count() <= Math.max(0, density));
                if (!cover.isEmpty()) {
                    assertEquals(hex.terrainLevel(Terrains.FOLIAGE_ELEV), cover.getFirst().height());
                    assertTrue(BoardFungus.CUPS.contains(cover.getFirst().asset()));
                }
                for (var feature : features) {
                    if (feature.kind() == BoardScene.FeatureKind.TREE) {
                        assertTrue(BoardFungus.COVER.contains(feature.asset()));
                        assertTrue(feature.height() <= hex.terrainLevel(Terrains.FOLIAGE_ELEV));
                        if (feature.asset().startsWith("fungus/spore-")) {
                            assertTrue(feature.height() <= .63f * hex.terrainLevel(Terrains.FOLIAGE_ELEV));
                        }
                    } else if (feature.kind() == BoardScene.FeatureKind.SCATTER) {
                        assertTrue(BoardFungus.SCATTER.contains(feature.asset()));
                        assertTrue(Math.hypot(feature.x(), feature.y()) > 15);
                        assertTrue(feature.height() < .24f);
                    }
                    used.add(feature.asset());
                }
                assertEquals(features, BoardFeatures.capture(hex, at, Map.of()));
                assertEquals(density, hex.terrainLevel(Terrains.WOODS));
            }
        }
        assertTrue(used.containsAll(BoardFungus.COVER));
        assertTrue(used.containsAll(BoardFungus.SCATTER));
    }

    @Test
    void shortSporeBodiesAppearWithinTheirOwnTierBudgets() {
        for (int height = 1; height <= 3; height++) {
            for (var at : List.of(new Coords(3, 3), new Coords(11, 7), new Coords(19, 14))) {
                for (int density = 1; density <= 3; density++) {
                    var hex = new Hex(0, "woods:" + density + ";foliage_elev:" + height, "fungus", at);
                    var trees = BoardTreeDistributionTest.capture(hex, at, true);
                    long spores = trees.stream().filter(tree -> BoardFungus.SPORES.contains(tree.asset())).count();
                    assertTrue(spores > 0 && spores <= density, "Spore budget at " + at + " h" + height + " d" + density);
                    assertTrue(BoardFungus.CUPS.contains(trees.getFirst().asset()), "Tall cups still lead the colony");
                    assertEquals(height, trees.getFirst().height(), "Short pods cannot replace the rules' cover height");
                }
            }
        }
    }

    @Test
    void lightHeavyAndUltraUseDistinctSmallerColonies() {
        for (int density = 1; density <= 3; density++) {
            for (int height : new int[] { 1, 2 }) {
                Hex hex = new Hex(0);
                hex.setTheme("fungus");
                hex.addTerrain(new Terrain(Terrains.WOODS, density));
                hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, height));
                var cover = BoardFeatures.capture(hex, new Coords(5, 8), Map.of()).stream()
                      .filter(f -> f.kind() == BoardScene.FeatureKind.TREE).toList();
                assertTrue(!cover.isEmpty() && cover.size() <= BoardTreeCoverage.colonyLimit(density));
                assertEquals(height, cover.getFirst().height());
                // Fungal stands retain small fruiting bodies; ordinary trees retain their full cover height.
                long shorter = cover.stream().filter(f -> f.height() < height).count();
                assertTrue(shorter > 0 && shorter < cover.size());
                assertEquals(cover.size() - shorter, cover.stream().filter(f -> f.height() == height).count());
                assertEquals(density, hex.terrainLevel(Terrains.WOODS));
                // Ordinary forests keep their existing representation.
                hex.setTheme("grass");
                assertTrue(BoardFeatures.capture(hex, new Coords(5, 8), Map.of()).stream()
                      .filter(f -> f.kind() == BoardScene.FeatureKind.TREE).allMatch(f -> f.height() >= height));
            }
        }
    }

    @Test
    void explicitSurfaceOverridesAndOtherThemesKeepTheirAssets() {
        Hex hex = new Hex(0);
        hex.setTheme("FuNgUs");
        for (var terrain : Map.of(Terrains.SNOW, BoardScene.Surface.SNOW,
              Terrains.PAVEMENT, BoardScene.Surface.CONCRETE, Terrains.MAGMA, BoardScene.Surface.ROCK).entrySet()) {
            hex.addTerrain(new Terrain(terrain.getKey(), 1));
            assertEquals(terrain.getValue(), BoardFeatures.surface(hex));
            hex.removeTerrain(terrain.getKey());
        }
        hex.setTheme("grass");
        hex.addTerrain(new Terrain(Terrains.WOODS, 2));
        hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
        assertTrue(BoardFeatures.capture(hex, new Coords(3, 2), Map.of()).stream()
              .noneMatch(f -> BoardFungus.asset(f.asset())));
    }

    @Test
    void everyFungalGlbLoadsThroughTheRuntimeLoaderWithItsExternalAtlas() {
        var root = new File("data/models/board").toPath().toAbsolutePath();
        Stream.of(BoardFungus.COVER, BoardFungus.CLIFF, BoardFungus.SCATTER).flatMap(List::stream).forEach(name -> {
            var levels = RigidGlb.loadLods(new FileHandle(root.resolve(name + ".glb").toFile()), root);
            assertEquals(4, levels.size());
            assertFalse(levels.getFirst().meshes.isEmpty(), name);
            boolean textured = false;
            for (var material : levels.getFirst().materials) {
                textured |= material.textures != null && material.textures.size > 0;
            }
            assertTrue(textured, name);
        });
    }

    @Test
    void hangingMyceliumGlowsByItsMaterialsEmissionMap() {
        var root = new File("data/models/board").toPath().toAbsolutePath();
        var near = RigidGlb.loadLods(new FileHandle(root.resolve("fungus/cliff-mycelium.glb").toFile()), root).getFirst();
        List<String> emission = new ArrayList<>();
        for (var material : near.materials) {
            if (!material.id.equals("mycelium")) { continue; }
            for (var texture : material.textures) {
                if (texture.usage == com.badlogic.gdx.graphics.g3d.model.data.ModelTexture.USAGE_EMISSIVE) {
                    emission.add(texture.fileName);
                }
            }
        }
        assertEquals(1, emission.size());
        assertTrue(emission.getFirst().endsWith("mycelium_emission.png"), emission.getFirst());
    }

    @Test
    void cliffColoniesMountOnTheFinishedWallAndFaceOutwards() {
        Coords center = new Coords(4, 4);
        BoardScene scene = BoardSurfaceBlendTest.scene(at -> BoardSurfaceBlendTest.tile(at,
              BoardScene.Surface.FUNGUS, at.equals(center) ? 4 : 0, -1, 0));
        var tile = scene.tile(center);
        var surface = new BoardSurface(scene, tile);
        List<BoardSurface.Face> walls = new ArrayList<>(surface.faces);
        walls.addAll(surface.walls(scene, BoardGeometry.floor(scene)));
        var placements = BoardFungus.cliffs(scene, tile, walls);
        assertFalse(placements.isEmpty(), "Exposed cliffs receive the requested side-growing colonies");
        var repeated = BoardFungus.cliffs(scene, tile, walls);
        assertEquals(placements.size(), repeated.size());
        for (int index = 0; index < placements.size(); index++) {
            assertEquals(placements.get(index).asset(), repeated.get(index).asset());
            assertArrayEquals(placements.get(index).transform().val, repeated.get(index).transform().val);
        }
        Map<String, BoardShape> shapes = new HashMap<>();
        int contacts = 0;
        for (var placement : placements) {
            Vector3 anchor = placement.transform().getTranslation(new Vector3());
            Vector3 outward = new Vector3(0, -1, 0).rot(placement.transform()).nor();
            assertTrue(outward.dot(anchor.cpy().sub(BoardGeometry.center(center, 4))) > 0);
            // Reproduce the floating-tier failure using the actual GLB, not a point at its origin. Every
            // rear vertex must see rock when looking out of its mounting side, including the lower shelves.
            var shape = shapes.computeIfAbsent(placement.asset(), BoardShape::loadModel);
            var roots = shape.polygons().stream().flatMap(p -> Stream.of(p.points())).distinct()
                  .filter(p -> p.y >= 0).toList();
            assertFalse(roots.isEmpty());
            for (Vector3 root : roots) {
                Vector3 mounted = root.cpy().mul(placement.transform()), hit = new Vector3();
                Ray ray = new Ray(mounted, outward);
                assertTrue(walls.stream().anyMatch(face -> face.finish() == BoardSurface.Finish.WALL
                      && Intersector.intersectRayTriangle(ray, face.a(), face.b(), face.c(), hit)
                      && hit.dst(mounted) < 8 * BoardGeometry.hexScale()),
                      () -> placement.asset() + " has an unsupported root at " + mounted);
                contacts++;
            }
        }
        assertTrue(contacts > 100, "Check the broad attachment area, not just a few origins");
        var flat = BoardSurfaceBlendTest.scene(at -> BoardSurfaceBlendTest.tile(at, BoardScene.Surface.FUNGUS, 0, -1, 0));
        assertTrue(BoardFungus.cliffs(flat, flat.tile(center), walls).isEmpty());
        var grass = BoardSurfaceBlendTest.scene(at -> BoardSurfaceBlendTest.tile(at,
              BoardScene.Surface.GRASS, at.equals(center) ? 4 : 0, -1, 0));
        assertTrue(BoardFungus.cliffs(grass, grass.tile(center), walls).isEmpty());
    }
}
