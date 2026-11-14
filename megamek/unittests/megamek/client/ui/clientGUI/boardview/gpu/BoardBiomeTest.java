/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardBiomeTest {
    @Test
    void indexedRootsKeepThePublishedBankHeightIncludingTriangleEdges() {
        var original = BoardGeometry.tuning();
        try {
            BoardScenes.tune(.94f, true);
            var scene = BoardScenes.scene(c -> c.getX() < 4
                  ? BoardScenes.tile(c, BoardScene.Surface.DIRT, 0, 0, 0) : BoardScenes.tile(c, BoardScene.Biome.MARSH, 0));
            var random = new Random(417);
            for (var lod : TerrainLod.values()) {
                var surface = new BoardSurface(scene, scene.tile(new Coords(3, 4)), lod);
                var support = new GpuBiomeVegetation.Support(surface.faces);
                for (var face : surface.faces) {
                    assertEquals(BoardSurface.sampleHeight(surface.faces, face.a().x, face.a().y, Float.NaN),
                          support.height(face.a().x, face.a().y), .00001f);
                }
                var center = BoardGeometry.center(new Coords(3, 4), 0);
                for (int i = 0; i < 500; i++) {
                    float x = center.x + (random.nextFloat() - .5f) * BoardGeometry.width();
                    float y = center.y + (random.nextFloat() - .5f) * BoardGeometry.height();
                    assertEquals(BoardSurface.sampleHeight(surface.faces, x, y, Float.NaN), support.height(x, y), .00001f);
                }
            }
        } finally { BoardGeometry.tune(original); }
    }

    @Test
    void reedsExtendWithoutDuplicateRootsAtEveryDensity() {
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Biome.MARSH, 0));
        var tile = scene.tile(new Coords(4, 4));
        var surface = BoardTacticalGeometry.Surface.of(new BoardSurface(scene, tile), scene, -1);
        var progressive = new GpuBiomeVegetation.Patch(scene, tile, surface, null);
        progressive.prepare(scene, tile, .08f);
        var planted = progressive.roots;
        for (int i = 3; i < planted.size; i += 4) { assertTrue(planted.items[i] < .08f); }
        int distant = planted.size;
        assertTrue(distant > 0);
        progressive.prepare(scene, tile, .35f);
        assertTrue(planted.size > distant);
        progressive.prepare(scene, tile, 1);
        var full = new GpuBiomeVegetation.Patch(scene, tile, surface, null);
        full.prepare(scene, tile, 1);
        assertEquals(full.roots, planted, "LOD preparation order must keep one deterministic lattice");
    }
    /** A hex of the fixture wholly within a wheat parcel. */
    static final Coords WHEAT = new Coords(1, 5);

    @Test
    void fieldsUseCropBiomeWithoutLegacyPropsAndWetGroundKeepsItsOwnBiome() {
        Hex hex = new Hex(0);
        hex.addTerrain(new Terrain(Terrains.FIELDS, 1));
        assertEquals(BoardScene.Biome.FIELD, BoardFeatures.biome(hex));
        assertEquals(BoardScene.Surface.GRASS, BoardFeatures.surface(hex), "Field banks retain the native grass mantle");
        assertTrue(BoardFeatures.capture(hex, new Coords(0, 0), Map.of()).stream().noneMatch(f -> f.asset().equals("vegetation/field")));
        hex.addTerrain(new Terrain(Terrains.FORTIFIED, 1));
        assertTrue(BoardFeatures.capture(hex, new Coords(0, 0), Map.of()).stream().noneMatch(f -> f.asset().equals("vegetation/field")));
        hex.removeAllTerrains();
        for (int level = 1; level <= 3; level++) {
            hex.addTerrain(new Terrain(Terrains.SWAMP, level));
            assertEquals(level == 1 ? BoardScene.Biome.MARSH : BoardScene.Biome.QUICKSAND, BoardFeatures.biome(hex));
            assertEquals(level == 1 ? BoardScene.Surface.GRASS : BoardScene.Surface.DIRT, BoardFeatures.surface(hex));
            assertFalse(BoardLiquid.capture(hex).present(), "Shallow visual pools do not invent gameplay water");
        }
        hex.addTerrain(new Terrain(Terrains.WATER, 1));
        assertEquals(BoardScene.Biome.NONE, BoardFeatures.biome(hex), "The actual waterbed keeps its material");
        hex.removeAllTerrains();
        hex.addTerrain(new Terrain(Terrains.MUD, 1));
        assertEquals(BoardScene.Biome.MUD, BoardFeatures.biome(hex));
        hex.addTerrain(new Terrain(Terrains.HAZARDOUS_LIQUID, 1));
        assertEquals(BoardScene.Biome.NONE, BoardFeatures.biome(hex));
        assertEquals(BoardLiquid.Kind.HAZARDOUS, BoardLiquid.capture(hex).kind());
    }

    @Test
    void connectedFieldsAndWetlandsHaveNoInternalHexSeamsAndStopBelowThePlateau() {
        Coords center = new Coords(4, 4);
        for (var kind : List.of(BoardScene.Biome.FIELD, BoardScene.Biome.MARSH, BoardScene.Biome.QUICKSAND, BoardScene.Biome.MUD)) {
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c, kind, 1));
            for (int edge = 0; edge < 6; edge++) {
                var corner = BoardGeometry.corner(center, 1, edge);
                assertEquals(1, BoardBiome.coverage(scene, kind, corner.x, corner.y, corner.z), .00001);
                assertEquals(0, BoardBiome.coverage(scene, kind, corner.x, corner.y, corner.z - BoardRelief.metres(1.3f)));
            }
            var single = BoardScenes.scene(c -> BoardScenes.tile(c, c.equals(center) ? kind : BoardScene.Biome.NONE, 1));
            var p = BoardGeometry.center(center, 1);
            assertEquals(1, BoardBiome.coverage(single, kind, p.x, p.y, p.z), .00001);
            p.lerp(BoardGeometry.center(center.translated(2), 1), .5f);
            assertEquals(kind == BoardScene.Biome.MARSH ? 1 : .5,
                  BoardBiome.coverage(single, kind, p.x, p.y, p.z), .00001);
        }
    }

    @Test
    void fieldsAreStraightEdgedParcelsOfEveryCropAndDirection() {
        var codes = new HashSet<Integer>();
        var random = new Random(5);
        for (int i = 0; i < 4000; i++) {
            float x = random.nextFloat() * 1500, y = -random.nextFloat() * 1500;
            long parcel = BoardFields.parcel(x, y);
            codes.add(BoardFields.code(parcel));
            // Straight edges: a parcel is convex, so the midpoint of two of its points lies in it.
            float x2 = x + (random.nextFloat() - .5f) * 60, y2 = y + (random.nextFloat() - .5f) * 60;
            if (BoardFields.parcel(x2, y2) == parcel) {
                assertEquals(parcel, BoardFields.parcel((x + x2) / 2, (y + y2) / 2), "Parcels have straight edges");
            }
        }
        assertEquals(6, codes.size(), "A large board holds parcels of every crop and direction");
    }

    @Test
    void reedsLeavePoolsOpenAndNeverDuplicateRootsAcrossHexes() {
        Coords center = BoardScenes.CROPS;
        float metre = BoardRelief.metres(1);
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Biome.MARSH, 0));
        var all = new HashSet<String>();
        int count = 0;
        for (var coords : List.of(center, center.translated(2))) {
            var tile = scene.tile(coords);
            var surface = BoardTacticalGeometry.Surface.of(new BoardSurface(scene, tile), scene, -1);
            var patch = new GpuBiomeVegetation.Patch(scene, tile, surface, null);
            patch.prepare(scene, tile, 1);
            var roots = patch.roots;
            assertFalse(roots.isEmpty(), "Both populated hexes must exercise placement");
            for (int i = 0; i < roots.size; i += 4) {
                float x = roots.items[i], y = roots.items[i + 1], z = roots.items[i + 2];
                assertTrue(all.add(x + ":" + y), "Shared edges cannot duplicate roots");
                assertEquals(BoardSurface.sampleHeight(surface.top(), x, y, Float.NaN), z + .018f * metre, .0001);
                float wet = BoardBiome.wetness(x / metre, y / metre);
                assertTrue(wet >= .48f && wet <= .80f, "Reeds grow on wet edges and hummocks, leaving the pools open");
                count++;
            }
        }
        assertTrue(count > 30, "Reed roots: " + count);
    }

    @Test
    void changingSpecialTerrainInvalidatesItsGeometryCache() {
        Coords center = new Coords(4, 4);
        var before = BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Biome.MARSH, 0));
        var after = BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Biome.QUICKSAND, 0));
        assertFalse(before.tile(center).sameGeometry(after.tile(center)));
        assertNotEquals(BoardSurface.geometryKey(before, before.tile(center)), BoardSurface.geometryKey(after, after.tile(center)));
    }

    @Test
    void marshReedsContinueAcrossExposedBanksButNeverUseTheWaterPlaneForSupport() {
        var original = BoardGeometry.tuning();
        try {
            BoardScenes.tune(.94f, true);
            var scene = BoardScenes.scene(c -> c.getX() < 4
                  ? BoardScenes.tile(c, BoardScene.Surface.DIRT, 0, 0, 0) : BoardScenes.tile(c, BoardScene.Biome.MARSH, 0));
            var removed = BoardScenes.scene(c -> c.getX() < 4
                  ? BoardScenes.tile(c, BoardScene.Surface.DIRT, 0, 0, 0) : BoardScenes.tile(c, BoardScene.Biome.NONE, 0));
            int bankRoots = 0, landRoots = 0;
            var positions = new HashSet<String>();
            float metre = BoardRelief.metres(1);
            for (int x = 2; x <= 4; x++) {
                for (int y = 2; y <= 6; y++) {
                    var owner = scene.tile(new Coords(x, y));
                    var surface = BoardTacticalGeometry.Surface.of(new BoardSurface(scene, owner), scene, -1);
                    if (BoardBiome.plantKind(scene, owner) != BoardScene.Biome.MARSH) { continue; }
                    var patch = new GpuBiomeVegetation.Patch(scene, owner, surface, null);
                    patch.prepare(scene, owner, 1);
                    for (int i = 0; i < patch.roots.size; i += 4) {
                        float px = patch.roots.items[i], py = patch.roots.items[i + 1], z = patch.roots.items[i + 2] + .018f * metre;
                        assertTrue(positions.add(px + ":" + py), "Each root has one owner across the shore join");
                        assertEquals(BoardSurface.sampleHeight(patch.ground, px, py, Float.NaN), z, .0001);
                        if (owner.liquid().present()) {
                            float water = BoardSurface.sampleHeight(surface.water(), px, py, Float.NEGATIVE_INFINITY);
                            assertTrue(z > water + .015f * metre, "No floating or submerged reed roots");
                            bankRoots++;
                        } else { landRoots++; }
                    }
                    if (owner.liquid().present()) {
                        assertNotEquals(BoardVegetation.key(scene, owner), BoardVegetation.key(removed, removed.tile(owner.coords())),
                              "Removing the neighbour's marsh must invalidate its bank reeds too");
                        assertEquals(BoardScene.Biome.NONE, BoardBiome.plantKind(removed, removed.tile(owner.coords())));
                    }
                }
            }
            assertTrue(bankRoots > 20, "Reeds must cross onto the exposed bank: " + bankRoots);
            assertTrue(landRoots > 20, "The same clumps continue into the marsh: " + landRoots);
        } finally { BoardGeometry.tune(original); }
    }
}
