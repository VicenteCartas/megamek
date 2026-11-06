/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Hex;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class GpuSurfaceBlendTest {
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void tessellationContactsPreserveMaterialsAfterSubdivision(boolean blended) throws Exception {
        var scene = BoardScenes.installedScene("AGoAC Maps/16x17 Grassland 2 (Low Bridges).board");
        // Dry ground beside the ford: its edge shared with the water stays fixed, its own bank curves.
        var tile = scene.tile(7, 6);
        var surface = new BoardSurface(scene, tile);
        var plan = GpuTerrain.prepareSculpt(scene, tile, surface, BoardGeometry.floor(scene), TerrainLod.FULL,
              new java.util.HashMap<>());
        int pinned = 0, curved = 0;
        var groups = new ArrayList<>(plan.blended().entrySet());
        groups.addAll(plan.blendedTops().entrySet());
        for (var group : groups) {
            var plain = new MeshBuilder();
            var tessellated = new MeshBuilder();
            var attributes = blended ? GpuSurfaceBlend.VERTICES : MeshBuilder.createAttributes(
                  VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal | VertexAttributes.Usage.ColorPacked
                        | VertexAttributes.Usage.TextureCoordinates);
            plain.begin(attributes, GL20.GL_TRIANGLES);
            tessellated.begin(GpuTessellation.contacts(attributes), GL20.GL_TRIANGLES);
            for (var triangle : group.getValue()) {
                if (blended) {
                    triangle.write(plain, group.getKey(), null);
                    triangle.write(tessellated, group.getKey(), surface);
                } else {
                    for (var point : List.of(triangle.a(), triangle.b(), triangle.c())) {
                        plain.vertex(point.vertex());
                        GpuTessellation.vertex(tessellated, point.vertex(), surface);
                    }
                }
            }
            int stride = attributes.vertexSize / Float.BYTES;
            float[] original = new float[plain.getNumVertices() * stride];
            float[] actual = new float[tessellated.getNumVertices() * (stride + 1)];
            plain.getVertices(original, 0);
            tessellated.getVertices(actual, 0);
            assertEquals(plain.getNumVertices(), tessellated.getNumVertices());
            for (int i = 0; i < plain.getNumVertices(); i++) {
                for (int channel = 0; channel < stride; channel++) {
                    assertEquals(original[i * stride + channel], actual[i * (stride + 1) + channel],
                          "Contact data must preserve water optics, normals, distances and blended materials");
                }
                if (actual[i * (stride + 1) + stride] == 1) { pinned++; }
                else { curved++; }
            }
        }
        assertTrue(pinned > 0 && curved > 0, "Pin contacts while retaining the surrounding ground's curvature");
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 4 })
    void submergedBanksRetainTheAdjacentLandsGeology(int level) {
        var center = BoardScenes.CENTER;
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
              c.equals(center) ? BoardScene.Surface.GRASS : BoardScene.Surface.LUNAR,
              c.equals(center) ? 0 : level, c.equals(center) ? 1 : -1, 0));
        var water = scene.tile(center);
        var surface = new BoardSurface(scene, water);
        var plan = GpuTerrain.prepareSculpt(scene, water, surface, -BoardGeometry.level(), TerrainLod.FULL,
              new java.util.HashMap<>());
        int checked = 0;
        for (var triangle : BoardScenes.blendedTriangles(plan)) {
            for (var point : List.of(triangle.a(), triangle.b(), triangle.c())) {
                assertEquals(1, point.cover().lunar(), .00001f,
                      "The land's geology must continue across its banks and bed below the waterline");
                checked++;
            }
        }
        assertTrue(checked > 0, "Exercise the prepared submerged bank vertices");
    }

    @ParameterizedTest
    @CsvSource({ "0, false", "0, true", "1, false", "1, true" })
    void authoredWaterMixturesKeepTheAdjacentLandsCoverOnTheirBanks(int depth, boolean adjacentWater) {
        var center = BoardScenes.CENTER;
        var tropical = center.translated(2);
        var scene = BoardScenes.scene(c -> {
            boolean wet = c.equals(center) || adjacentWater && c.equals(center.translated(5));
            var hex = new Hex(0, wet ? "water:" + depth + ";ground_fluff:3:2"
                  : c.equals(tropical) ? "ground_fluff:1:3" : "",
                  wet ? "desert" : c.equals(tropical) ? "tropical" : "grass", c);
            var artwork = new BoardArtwork.HexImage(c, null, null, List.of(), Map.of(), null);
            return BoardScene.captureTile(hex, artwork, null, new BoardScene.PixelPool(), neighbour -> null);
        });
        var water = scene.tile(center);
        var land = scene.tile(center.translated(0));
        var surface = new BoardSurface(scene, water);
        var plan = GpuTerrain.prepareSculpt(scene, water, surface, -BoardGeometry.level(), TerrainLod.FULL,
              new java.util.HashMap<>());
        assertTrue(plan.bed().isEmpty(), "Every natural bed sector must use the shared field, including water-to-water edges");
        int checked = 0;
        for (var triangle : BoardScenes.blendedTriangles(plan)) {
            for (var point : List.of(triangle.a(), triangle.b(), triangle.c())) {
                if (point.vertex().color.b >= .125f) { continue; }
                var p = point.vertex().position;
                var expected = BoardSurfaceBlend.sample(scene, land, p.x, p.y, p.z);
                for (var family : BoardScene.Surface.values()) {
                    assertEquals(expected.weight(family), point.cover().weight(family), .00001f,
                          "The same bank position must keep its land material on the water-owned mesh: " + p);
                }
                checked++;
            }
        }
        assertTrue(checked > 0, "Exercise the prepared bank's material vertices");
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void homogeneousFlatTopsKeepSixTrianglesThroughMaterialPreparation(TerrainLod lod) {
        for (var family : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.DIRT, BoardScene.Surface.SAND,
              BoardScene.Surface.ROCK, BoardScene.Surface.SNOW)) {
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c, family, 0, -1, 0));
            var tile = scene.tile(BoardScenes.CENTER);
            var center = BoardGeometry.center(tile.coords(), 0);
            var faces = new ArrayList<BoardSurface.Face>();
            for (int edge = 0; edge < 6; edge++) {
                faces.add(new BoardSurface.Face(center, BoardGeometry.corner(tile.coords(), 0, edge),
                      BoardGeometry.corner(tile.coords(), 0, edge + 1), BoardSurface.Finish.TOP));
            }
            var groups = GpuSurfaceBlend.prepare(scene, tile, faces, p -> new MeshPartBuilder.VertexInfo()
                  .setPos(p).setNor(Vector3.Z).setCol(1, 0, 0, .3f).setUV(99, 99), GpuSurfaceBlend.spacing(lod));
            assertEquals(6, groups.values().stream().mapToInt(List::size).sum(),
                  family + " must keep its flat top budget in the emitted material mesh at " + lod);
            assertEquals(1, groups.size(), "Uniform ground needs one material group");
            assertTrue(groups.values().stream().flatMap(List::stream)
                  .flatMap(t -> List.of(t.a(), t.b(), t.c()).stream())
                  .allMatch(p -> p.vertex().position.z == center.z && p.cover().weight(family) == 1),
                  "Material preparation must preserve the exact flat surface and its homogeneous cover");
        }
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void concreteSlabsKeepTheirTopAndPanelTriangleBudgetAfterMaterialSampling(TerrainLod lod) {
        var center = BoardScenes.CENTER;
        for (int level : new int[] { 0, 4 }) {
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
                  c.equals(center) ? BoardScene.Surface.CONCRETE : BoardScene.Surface.SAND,
                  c.equals(center) ? level : 4 - level, -1, 0));
            var tile = scene.tile(center);
            var surface = new BoardSurface(scene, tile, lod);
            var tops = surface.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.TOP).toList();
            var ground = GpuSurfaceBlend.prepare(scene, tile, tops, p -> new MeshPartBuilder.VertexInfo()
                  .setPos(p).setNor(Vector3.Z).setCol(1, 0, 0, 1).setUV(0, 0), GpuSurfaceBlend.spacing(lod));
            assertEquals(6, ground.values().stream().mapToInt(List::size).sum(),
                  "Concrete beside a higher cliff must not regain a dense material mesh");
            assertTrue(ground.values().stream().flatMap(List::stream)
                  .flatMap(t -> List.of(t.a(), t.b(), t.c()).stream()).allMatch(p -> p.cover().concrete() == 1),
                  "The poured surface must retain its concrete cover");
            if (level == 0) { continue; }
            var panels = surface.walls(scene, -BoardGeometry.level());
            var walls = GpuSurfaceBlend.prepare(scene, tile, panels, p -> {
                var shade = surface.relief.shade(p);
                return new MeshPartBuilder.VertexInfo().setPos(p).setNor(shade.normal())
                      .setCol(1, shade.level(), .5f, shade.tint()).setUV(shade.rim(), shade.foot());
            }, GpuSurfaceBlend.spacing(lod));
            assertEquals(panels.size(), walls.values().stream().mapToInt(List::size).sum(),
                  "Material sampling preserves the complete concrete panels without subdivision");
        }
    }

    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void steepContactsKeepTheirMaterialsAndSurfaceAreaAtEveryLod(TerrainLod lod) {
        var center = BoardScenes.CENTER;
        var neighbour = center.translated(BoardGeometry.edgeDirection(0));
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
              c.equals(neighbour) ? BoardScene.Surface.SAND : BoardScene.Surface.GRASS,
              c.equals(center) || c.equals(neighbour) ? 4 : 0, -1, 0));
        var tile = scene.tile(center);
        var surface = new BoardSurface(scene, tile, lod);
        var faces = surface.walls(scene, -BoardGeometry.level()).stream()
              .filter(f -> surface.relief.shade(f.a()) != null
                    && surface.relief.shade(f.a()).kind() == BoardRelief.Kind.CLIFF).toList();
        var groups = GpuSurfaceBlend.prepare(scene, tile, faces, p -> {
            var shade = surface.relief.shade(p);
            return new MeshPartBuilder.VertexInfo().setPos(p).setNor(shade.normal())
                  .setUV(shade.rim(), shade.foot()).setCol(1, shade.level(), .5f, shade.tint());
        }, GpuSurfaceBlend.spacing(lod));
        var triangles = groups.values().stream().flatMap(List::stream).toList();
        double before = faces.stream().mapToDouble(f -> area(f.a(), f.b(), f.c())).sum();
        double after = triangles.stream().mapToDouble(t -> area(t.a().vertex().position,
              t.b().vertex().position, t.c().vertex().position)).sum();
        assertEquals(before, after, before * .00001, "Material sampling cannot change the LOD silhouette");
        assertTrue(triangles.stream().flatMap(t -> List.of(t.a(), t.b(), t.c()).stream()).anyMatch(p ->
              p.vertex().normal.z < .6f && p.cover().sand() > .1f && p.cover().grass() > .1f),
              "Steep rock faces need both materials in " + lod);
    }

    @Test
    void coincidentCliffAndGroundVerticesKeepTheirDistinctShadingRoles() {
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
              c.getX() < 4 ? BoardScene.Surface.SAND : BoardScene.Surface.GRASS, 0, -1, 0));
        var tile = scene.tile(BoardScenes.CENTER);
        var a = BoardGeometry.center(tile.coords(), 0);
        var b = BoardGeometry.corner(tile.coords(), 0, 0);
        var c = BoardGeometry.corner(tile.coords(), 0, 1);
        var roles = new IdentityHashMap<Vector3, Float>();
        var ground = new BoardSurface.Face(a, b, c, BoardSurface.Finish.TOP);
        var cliff = new BoardSurface.Face(new Vector3(a), new Vector3(b), new Vector3(c), BoardSurface.Finish.CAP);
        for (var p : List.of(ground.a(), ground.b(), ground.c())) { roles.put(p, 0f); }
        for (var p : List.of(cliff.a(), cliff.b(), cliff.c())) { roles.put(p, .5f); }
        var groups = GpuSurfaceBlend.prepare(scene, tile, List.of(ground, cliff), p -> new MeshPartBuilder.VertexInfo()
              .setPos(p).setNor(Vector3.Z).setUV(0, 0).setCol(1, 0, roles.get(p), 1));
        var triangles = groups.values().stream().flatMap(List::stream).toList();
        assertTrue(triangles.stream().anyMatch(t -> t.a().vertex().color.b == 0));
        assertTrue(triangles.stream().anyMatch(t -> t.a().vertex().color.b == .5f));
        for (var t : triangles) {
            assertEquals(t.a().vertex().color.b, t.b().vertex().color.b);
            assertEquals(t.a().vertex().color.b, t.c().vertex().color.b);
        }
    }

    @Test
    void authoredTransitionsAtCrowdedCliffsKeepABoundedMesh() {
        var themes = List.of("grass", "snow", "dirt", "rock", "lunar");
        var scene = BoardScenes.scene(c -> {
            var hex = new Hex(c.getY() < 4 ? 4 : 0, "ground_fluff:1:3",
                  themes.get(Math.floorMod(c.getX() + 2 * c.getY(), themes.size())), c);
            var artwork = new BoardArtwork.HexImage(c, null, null, List.of(), Map.of(), null);
            return BoardScene.captureTile(hex, artwork, null, new BoardScene.PixelPool(), neighbour -> null);
        });
        for (int x = 2; x <= 6; x++) {
            var tile = scene.tile(new Coords(x, 3));
            var surface = new BoardSurface(scene, tile);
            var faces = surface.walls(scene, -BoardGeometry.level()).stream()
                  .filter(f -> surface.relief.shade(f.a()) != null
                        && surface.relief.shade(f.a()).kind() == BoardRelief.Kind.CLIFF).toList();
            var groups = GpuSurfaceBlend.prepare(scene, tile, faces, p -> {
                var shade = surface.relief.shade(p);
                return new MeshPartBuilder.VertexInfo().setPos(p).setNor(shade.normal())
                      .setUV(shade.rim(), shade.foot()).setCol(1, shade.level(), .5f, shade.tint());
            });
            assertTrue(!groups.isEmpty());
            assertTrue(groups.values().stream().mapToInt(List::size).sum() < faces.size() * 8,
                  "Authored mixtures must not cause runaway palette subdivision");
        }
    }

    @Test
    void crowdedCliffsKeepEveryContributingFamilyInTheirPalettes() {
        var families = List.of(BoardScene.Surface.GRASS, BoardScene.Surface.SAND, BoardScene.Surface.SNOW,
              BoardScene.Surface.DIRT, BoardScene.Surface.ROCK);
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
              families.get(Math.floorMod(c.getX() + 2 * c.getY(), families.size())), c.getY() < 4 ? 4 : 0, -1, 0));
        for (int x = 2; x <= 6; x++) {
            var tile = scene.tile(new Coords(x, 3));
            var surface = new BoardSurface(scene, tile);
            var faces = surface.walls(scene, -BoardGeometry.level()).stream()
                  .filter(f -> surface.relief.shade(f.a()) != null
                        && surface.relief.shade(f.a()).kind() == BoardRelief.Kind.CLIFF).toList();
            var groups = GpuSurfaceBlend.prepare(scene, tile, faces, p -> {
                var shade = surface.relief.shade(p);
                return new MeshPartBuilder.VertexInfo().setPos(p).setNor(shade.normal())
                      .setUV(shade.rim(), shade.foot()).setCol(1, shade.level(), .5f, shade.tint());
            });
            assertTrue(!groups.isEmpty());
            for (var group : groups.entrySet()) {
                var palette = group.getKey();
                for (var triangle : group.getValue()) {
                    for (var point : List.of(triangle.a(), triangle.b(), triangle.c())) {
                        float represented = point.cover().weight(palette.base());
                        if (palette.first() != palette.base()) { represented += point.cover().weight(palette.first()); }
                        if (palette.second() != palette.base()) { represented += point.cover().weight(palette.second()); }
                        if (palette.third() != palette.base()) { represented += point.cover().weight(palette.third()); }
                        assertEquals(1, represented, .00001f, "Cliff junctions must not drop a material");
                    }
                }
            }
        }
    }

    @Test
    void crowdedBoundariesPreserveAreaHeightAndEveryContributingMaterial() {
        var families = List.of(BoardScene.Surface.GRASS, BoardScene.Surface.SAND, BoardScene.Surface.SNOW,
              BoardScene.Surface.DIRT, BoardScene.Surface.ROCK);
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
              families.get(Math.floorMod(c.getX() + 2 * c.getY(), families.size())), 0, -1, 0));
        int original = 0, refined = 0;
        float maximumError = 0;
        double totalError = 0;
        int samples = 0;
        for (int direction = -1; direction < 6; direction++) {
            var coords = direction < 0 ? BoardScenes.CENTER : BoardScenes.CENTER.translated(direction);
            var tile = scene.tile(coords);
            var surface = new BoardSurface(scene, tile);
            var faces = surface.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.TOP).toList();
            var palettes = GpuSurfaceBlend.prepare(scene, tile, faces, p -> new MeshPartBuilder.VertexInfo()
                  .setPos(p).setNor(Vector3.Z).setCol(Color.WHITE).setUV(0, 0));
            double before = faces.stream().mapToDouble(f -> area(f.a(), f.b(), f.c())).sum();
            double after = 0;
            original += faces.size();
            for (var group : palettes.entrySet()) {
                var palette = group.getKey();
                for (var triangle : group.getValue()) {
                    refined++;
                    after += area(triangle.a().vertex().position, triangle.b().vertex().position, triangle.c().vertex().position);
                    for (int a = 1; a < 5; a++) {
                        for (int b = 1; a + b < 5; b++) {
                            float wa = a / 5f, wb = b / 5f, wc = 1 - wa - wb;
                            var p = new Vector3(triangle.a().vertex().position).scl(wa)
                                  .mulAdd(triangle.b().vertex().position, wb).mulAdd(triangle.c().vertex().position, wc);
                            var actual = BoardSurfaceBlend.sample(scene, tile, p.x, p.y, p.z);
                            for (var family : BoardScene.Surface.values()) {
                                float interpolated = triangle.a().cover().weight(family) * wa
                                      + triangle.b().cover().weight(family) * wb + triangle.c().cover().weight(family) * wc;
                                float error = Math.abs(interpolated - actual.weight(family));
                                maximumError = Math.max(maximumError, error);
                                totalError += error;
                                samples++;
                            }
                        }
                    }
                    for (var point : List.of(triangle.a(), triangle.b(), triangle.c())) {
                        var p = point.vertex().position;
                        assertTrue(faces.stream().anyMatch(f -> Math.abs(f.height(p.x, p.y) - p.z) < .01f),
                              "Refinement must stay on an original ground triangle, including below outcrops");
                        float represented = point.cover().weight(palette.base());
                        if (palette.first() != palette.base()) { represented += point.cover().weight(palette.first()); }
                        if (palette.second() != palette.base()) { represented += point.cover().weight(palette.second()); }
                        if (palette.third() != palette.base()) { represented += point.cover().weight(palette.third()); }
                        assertEquals(1, represented, .00001f, "No contributing material may be dropped at a junction");
                    }
                }
            }
            assertEquals(before, after, before * .00001, "Subdivision must not open gaps or overlap faces");
        }
        System.out.printf("Surface blending, seven crowded hexes: %d -> %d triangles; cover max error=%.4f mean=%.4f%n",
              original, refined, maximumError, totalError / samples);
        assertTrue(refined > original);
        // A six-triangle fan needs detail at its material borders, not throughout its broad interior. The previous
        // fixed-spacing path emitted 2,957 triangles for these seven flat hexes.
        assertTrue(refined < 2000, "Material interpolation must leave broad interiors sparse: " + refined);
        assertTrue(maximumError < .125f, "The cover boundary must remain near its sampled field: " + maximumError);
        assertTrue(totalError / samples < .007, "Broad material coverage must remain accurate");
    }

    @Test
    void materialBordersKeepTheirSampledWidthAndPureTileCentres() {
        var center = BoardScenes.CENTER;
        for (int direction = 0; direction < 6; direction++) {
            var neighbor = center.translated(direction);
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c,
                  c.equals(neighbor) ? BoardScene.Surface.SAND : BoardScene.Surface.GRASS, 0, -1, 0));
            var tile = scene.tile(center);
            var surface = new BoardSurface(scene, tile);
            var faces = surface.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.TOP).toList();
            assertEquals(6, faces.size());
            var triangles = GpuSurfaceBlend.prepare(scene, tile, faces, p -> new MeshPartBuilder.VertexInfo()
                  .setPos(p).setNor(Vector3.Z).setCol(Color.WHITE).setUV(0, 0)).values().stream().flatMap(List::stream).toList();
            var anchor = BoardGeometry.center(center, 0);
            var boundary = new Vector3(anchor).lerp(BoardGeometry.center(neighbor, 0), .5f);
            float maxError = 0;
            for (int step = 0; step <= 100; step++) {
                var p = new Vector3(anchor).lerp(boundary, step / 100f);
                float actual = BoardSurfaceBlend.sample(scene, tile, p.x, p.y, p.z).sand();
                float interpolated = interpolatedCover(triangles, p, BoardScene.Surface.SAND);
                maxError = Math.max(maxError, Math.abs(actual - interpolated));
                if (step <= 25) {
                    assertEquals(0, interpolated, .025f, "The wider contact must still leave the tile's centre pure");
                }
            }
            assertTrue(maxError < .125f, "The material boundary must retain its sampled shape: " + maxError);
        }
    }

    @Test
    void baseAliasesRequireMatchingFamiliesAndEveryMapAndScale() {
        assertEquals(3, repeatedBlend(BoardScene.Surface.GRASS.ordinal()).baseAliases());
        assertEquals(0, repeatedBlend(BoardScene.Surface.CONCRETE.ordinal()).baseAliases(),
              "Constructed concrete is not its transported aggregate even when the maps agree");
        for (int slot = 1; slot <= 2; slot++) {
            int remaining = 3 ^ (1 << (slot - 1));
            var family = repeatedBlend(BoardScene.Surface.GRASS.ordinal());
            family.families[slot] = BoardScene.Surface.DIRT.ordinal();
            assertEquals(remaining, family.baseAliases());
            for (int component = 0; component < 4; component++) {
                var scale = repeatedBlend(BoardScene.Surface.GRASS.ordinal());
                scale.tiles[slot][component] += 1;
                assertEquals(remaining, scale.baseAliases(), "Every authored repeat must agree");
                var map = repeatedBlend(BoardScene.Surface.GRASS.ordinal());
                map.layers[slot][component] += 2;
                assertEquals(remaining, map.baseAliases(), "Every semantic material map must agree");
            }
        }
        var numeric = repeatedBlend(BoardScene.Surface.GRASS.ordinal());
        numeric.tiles[0][0] = 0; numeric.tiles[1][0] = -0f; numeric.tiles[2][0] = 0;
        assertEquals(3, numeric.baseAliases(), "GLSL vector equality treats signed zero alike");
        numeric.layers[0][1] = Float.NaN; numeric.layers[1][1] = Float.NaN; numeric.layers[2][1] = Float.NaN;
        assertEquals(0, numeric.baseAliases(), "Matching NaN storage cannot prove GLSL equality");
        var fourth = repeatedBlend(BoardScene.Surface.GRASS.ordinal());
        fourth.layers[3][0] += 2; fourth.tiles[3][0] += 1;
        assertEquals(3, fourth.baseAliases(), "This optimization must not change the fourth slot's existing rule");
    }

    @Test
    void familyRequestsAndDrawCompatibilitySeparateProvenAliasesFromOverrides() {
        String previous = System.getProperty(GpuFamilyPrograms.PROPERTY);
        System.setProperty(GpuFamilyPrograms.PROPERTY, "true");
        var programs = new GpuFamilyPrograms(true, FloatAttribute.Shininess, material -> 0);
        try {
            var base = new Material(new FloatAttribute(FloatAttribute.Shininess, BoardScene.Surface.GRASS.ordinal()),
                  repeatedBlend(BoardScene.Surface.GRASS.ordinal()));
            var override = new Material(base);
            override.get(GpuSurfaceBlend.class, GpuSurfaceBlend.TYPE).tiles[1][0] += 1;
            var anotherOverride = new Material(override);
            anotherOverride.get(GpuSurfaceBlend.class, GpuSurfaceBlend.TYPE).tiles[1][0] += 1;
            var vertices = new VertexAttributes(VertexAttribute.Position());
            programs.request(base, 3, vertices);
            programs.request(new Material(base), 3, vertices);
            assertEquals(1, programs.requested(), "Equal material proofs share a requested program");
            programs.request(override, 3, vertices);
            programs.request(anotherOverride, 3, vertices);
            assertEquals(2, programs.requested(), "An override needs a different proof, not a program for each uniform value");
            assertTrue(GpuFamilyPrograms.draws(null, 0, base, FloatAttribute.Shininess));
            assertTrue(GpuFamilyPrograms.draws(null, 0, override, FloatAttribute.Shininess));
            assertEquals("", GpuFamilyPrograms.defines(base, FloatAttribute.Shininess), "Generic programs keep runtime dispatch");
            base.set(new GpuFamilyPrograms.Constants()); override.set(new GpuFamilyPrograms.Constants());
            float[] families = GpuFamilyPrograms.constants(base, FloatAttribute.Shininess);
            assertTrue(GpuFamilyPrograms.draws(families, 3, base, FloatAttribute.Shininess));
            assertFalse(GpuFamilyPrograms.draws(families, 3, override, FloatAttribute.Shininess),
                  "A program that eliminated slot B must reject its actual map/scale override");
            assertTrue(GpuFamilyPrograms.draws(families, 2, override, FloatAttribute.Shininess));
            assertFalse(GpuFamilyPrograms.draws(families, 2, base, FloatAttribute.Shininess));
            assertFalse(GpuFamilyPrograms.draws(null, 0, base, FloatAttribute.Shininess));
            String reused = GpuFamilyPrograms.defines(base, FloatAttribute.Shininess);
            String changed = GpuFamilyPrograms.defines(override, FloatAttribute.Shininess);
            assertTrue(reused.contains("#define COVER_B_IS_BASE") && reused.contains("#define COVER_C_IS_BASE"));
            assertFalse(changed.contains("#define COVER_B_IS_BASE"));
            assertTrue(changed.contains("#define COVER_C_IS_BASE"));
        } finally {
            programs.dispose();
            if (previous == null) { System.clearProperty(GpuFamilyPrograms.PROPERTY); }
            else { System.setProperty(GpuFamilyPrograms.PROPERTY, previous); }
        }
    }

    private static GpuSurfaceBlend repeatedBlend(float family) {
        float[] tiles = { 8, 4, 8, 16 }, layers = { 0, 2, 4, 6 };
        return new GpuSurfaceBlend(null, new float[] { family, family, family, family }, new float[4],
              new float[][] { tiles, tiles, tiles, tiles }, new float[][] { layers, layers, layers, layers });
    }

    private static float interpolatedCover(List<GpuSurfaceBlend.Triangle> triangles, Vector3 p, BoardScene.Surface family) {
        for (var triangle : triangles) {
            var a = triangle.a().vertex().position;
            var b = triangle.b().vertex().position;
            var c = triangle.c().vertex().position;
            double total = area(a, b, c);
            double wa = area(p, b, c) / total, wb = area(a, p, c) / total, wc = area(a, b, p) / total;
            if (Math.abs(wa + wb + wc - 1) < .00001) {
                return (float) (triangle.a().cover().weight(family) * wa + triangle.b().cover().weight(family) * wb
                      + triangle.c().cover().weight(family) * wc);
            }
        }
        throw new AssertionError("No material triangle at " + p);
    }

    private static double area(Vector3 a, Vector3 b, Vector3 c) {
        return new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).len() * .5;
    }
}
