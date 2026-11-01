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
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import com.badlogic.gdx.utils.GdxNativesLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardFungusTest {
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
                assertEquals(density < 1 ? 0 : density >= 3 ? 6 : density == 2 ? 4 : 2, cover.size());
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
    void lightHeavyAndUltraUseDistinctSmallerColonies() {
        for (int density = 1; density <= 3; density++) {
            for (int height : new int[] { 1, 2 }) {
                Hex hex = new Hex(0);
                hex.setTheme("fungus");
                hex.addTerrain(new Terrain(Terrains.WOODS, density));
                hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, height));
                var cover = BoardFeatures.capture(hex, new Coords(5, 8), Map.of()).stream()
                      .filter(f -> f.kind() == BoardScene.FeatureKind.TREE).toList();
                assertEquals(2 * density, cover.size());
                assertEquals(height, cover.getFirst().height());
                // At most one shorter colony in light woods and two in heavier woods; the rest reach the woods' height.
                long shorter = cover.stream().filter(f -> f.height() < height).count();
                assertEquals(density == 1 ? 1 : 2, shorter);
                assertEquals(cover.size() - shorter, cover.stream().filter(f -> f.height() == height).count());
                assertEquals(density, hex.terrainLevel(Terrains.WOODS));
                // Ordinary forests keep their existing representation.
                hex.setTheme("grass");
                assertEquals(density == 1 ? 3 : density == 2 ? 9 : 16,
                      BoardFeatures.capture(hex, new Coords(5, 8), Map.of()).stream()
                            .filter(f -> f.kind() == BoardScene.FeatureKind.TREE).count());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "fungus", "mars" })
    void solidColoniesDoNotPassThroughEachOtherWithinOrAcrossHexes(String theme) {
        // Low woods and light woods at height 2 have room for every colony apart; taller, denser woods slim theirs.
        for (int density = 1; density <= 3; density++) {
            assertEquals(0, worstOverlap(theme, density, 1), .5f, theme + " woods " + density + " at height 1");
        }
        assertEquals(0, worstOverlap(theme, 1, 2), .5f, theme + " light woods at height 2");
        Hex crowded = new Hex(0, "woods:3;foliage_elev:3", theme);
        var colonies = BoardFeatures.capture(crowded, new Coords(6, 6), Map.of(), Set.of(), neighbour -> crowded)
              .stream().filter(f -> f.kind() == BoardScene.FeatureKind.TREE).toList();
        assertEquals(6, colonies.size());
        assertTrue(colonies.stream().anyMatch(f -> f.stretch().x() < 1), "Crowded colonies grow slimmer");
        assertTrue(colonies.stream().allMatch(f -> f.stretch().x() > .79 && f.stretch().z() == 1
              && f.height() <= 3), "A colony slims to four fifths of its width at most, its height kept");
    }

    /**
     * The worst overlap (in hex units) of two solid colonies' footprints in seven hexes of contiguous woods: a hex and
     * its neighbours, each placed with the others as its board.
     */
    private static float worstOverlap(String theme, int density, int height) {
        Hex woods = new Hex(0, "woods:" + density + ";foliage_elev:" + height, theme);
        Coords centre = new Coords(6, 6);
        List<float[]> colonies = new ArrayList<>();
        for (int direction = -1; direction < 6; direction++) {
            Coords at = direction < 0 ? centre : centre.translated(direction);
            float x = HexTile.centerX(at, BoardGeometry.TILE_WIDTH), y = HexTile.centerY(at, BoardGeometry.TILE_HEIGHT);
            for (var colony : BoardFeatures.capture(woods, at, Map.of(), Set.of(), neighbour -> woods)) {
                if (colony.kind() == BoardScene.FeatureKind.TREE) {
                    colonies.add(new float[] { x + colony.x(), y + colony.y(), BoardFeatures.footprint(colony) });
                }
            }
        }
        float worst = 0;
        for (int i = 0; i < colonies.size(); i++) {
            for (int j = i + 1; j < colonies.size(); j++) {
                float[] a = colonies.get(i), b = colonies.get(j);
                worst = Math.max(worst, a[2] + b[2] - (float) Math.hypot(a[0] - b[0], a[1] - b[1]));
            }
        }
        return worst;
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
