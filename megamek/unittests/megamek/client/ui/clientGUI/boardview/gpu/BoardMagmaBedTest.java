/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashSet;
import java.util.List;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardMagmaBedTest {
    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 3, 4 })
    void lavaSurfaceAndSupportShareASparseBasinWithoutExposedBedWedges(int map) {
        BoardScene scene = BoardScenes.scene(new File("data/boards/Map Pack Volcanic/16x17 Dome Vent " + map + ".board"));
        int totalBed = 0;
        for (var tile : scene.tiles()) {
            if (!tile.liquid().molten()) { continue; }
            BoardSurface surface = new BoardSurface(scene, tile);
            List<BoardSurface.Face> canonical = bed(surface);
            totalBed += canonical.size();
            assertTrue(canonical.size() <= 228, "Only the contour, coarse plateau and exposed lip need support");
            assertTrue(surface.waterFaces.size() <= 144, "A lava pool must stay sparse, including cliff contacts");
            Vector3 center = BoardGeometry.center(tile.coords(), 0);
            assertEquals(BoardGeometry.groundZ(tile), BoardSurface.sampleHeight(canonical, center.x, center.y, Float.NaN),
                  .001f, "The sparse support keeps the game depth at " + tile.coords());
            var contour = surface.waterGeometry().bedOutline();
            double footprint = 0, covered = 0;
            for (int i = 0; i < contour.length; i++) {
                Vector3 a = contour[i], b = contour[(i + 1) % contour.length];
                footprint += (double) (a.x - center.x) * (b.y - center.y)
                      - (double) (a.y - center.y) * (b.x - center.x);
                for (Vector3 p : List.of(a, new Vector3(a).lerp(b, .5f))) {
                    assertEquals(p.z, BoardSurface.sampleHeight(canonical, p.x, p.y, Float.NaN), .003f,
                          "The sparse bed retains its shore and crest contact at " + tile.coords());
                }
            }
            for (var face : canonical) {
                float area = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z;
                assertTrue(area > 0, "Bed triangles must face up at " + tile.coords());
                covered += area;
                for (int i = 0; i <= 6; i++) {
                    for (int j = 0; i + j <= 6; j++) {
                        Vector3 point = new Vector3(face.a()).scl(1 - (i + j) / 6f)
                              .mulAdd(face.b(), i / 6f).mulAdd(face.c(), j / 6f);
                        float lava = BoardSurface.sampleHeight(surface.waterFaces, point.x, point.y, Float.NaN);
                        assertTrue(!Float.isFinite(lava) || point.z <= lava + .003f,
                              () -> "Bed pierces the lava at " + tile.coords() + ": " + point + " above " + lava);
                    }
                }
            }
            assertEquals(footprint, covered, .05, "The bed must fill its contour without holes or overlapping folds");
        }
        if (map == 1) { assertTrue(totalBed < 4500, "Dome Vent 1 needs sparse canonical support, not just draw culling"); }
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 3, 4 })
    void omittedLavaBedIsHiddenWhileCanonicalGroundRemainsAvailable(int map) {
        BoardScene scene = BoardScenes.scene(new File("data/boards/Map Pack Volcanic/16x17 Dome Vent " + map + ".board"));
        int omitted = 0, retained = 0;
        for (var tile : scene.tiles()) {
            if (!tile.liquid().molten()) { continue; }
            BoardSurface surface = new BoardSurface(scene, tile);
            List<BoardSurface.Face> canonical = bed(surface);
            var rendered = new HashSet<>(surface.renderBed(canonical));
            assertEquals(canonical, bed(surface), "Rendering must preserve the ground used by picking and support");
            retained += rendered.size();
            for (var face : canonical) {
                if (rendered.contains(face)) { continue; }
                omitted++;
                for (int i = 1; i < 5; i++) {
                    for (int j = 1; i + j < 6; j++) {
                        Vector3 point = new Vector3(face.a()).scl(1 - (i + j) / 6f)
                              .mulAdd(face.b(), i / 6f).mulAdd(face.c(), j / 6f);
                        Ray ray = new Ray(point, Vector3.Z);
                        Vector3 hit = new Vector3();
                        assertTrue(surface.waterFaces.stream().anyMatch(top -> Intersector.intersectRayTriangle(ray,
                                    top.a(), top.b(), top.c(), hit) && hit.z > point.z),
                              () -> "Removed ground must be covered by opaque lava at " + tile.coords() + ": " + point);
                    }
                }
            }
        }
        assertTrue(omitted > 1000, "Remove a meaningful amount of hidden geometry on the shipped map");
        if (map == 2) { assertTrue(retained > 0, "Keep the exposed collar beneath Dome Vent 2's falling lips"); }
    }

    @Test
    void openMoltenHexUsesSixTrianglesForItsSurfaceAndCanonicalSupport() {
        BoardLiquid lava = new BoardLiquid(BoardLiquid.Kind.MAGMA, "", 0);
        BoardScene scene = BoardScenes.scene(at -> new BoardScene.Tile(at, 0, 0, false, 0,
              BoardScene.Surface.ROCK, null, null, List.of(), List.of(), lava, null, true));
        for (TerrainLod lod : TerrainLod.values()) {
            BoardSurface surface = new BoardSurface(scene, scene.tile(BoardScenes.CENTER), lod);
            assertEquals(6, surface.waterFaces.size());
            assertEquals(6, bed(surface).size(), "The canonical mesh must also be sparse");
            assertTrue(surface.renderBed(bed(surface)).isEmpty(), "Opaque lava covers the entire matching bed");
        }
    }

    @Test
    void descendingDomeVentLavaDoesNotCreateAnInternalOccludingLip() {
        GdxNativesLoader.load();
        BoardScene scene = BoardScenes.scene(new File("data/boards/Map Pack Volcanic/16x17 Dome Vent 1.board"));
        Coords at = new Coords(15, 13);
        BoardSurface surface = new BoardSurface(scene, scene.tile(at));
        var camera = new BoardCamera();
        camera.resize(1440, 1080);
        camera.setIsometric(true);
        camera.camera.zoom = .14f;
        camera.center(BoardGeometry.center(at, scene.tile(at).elevation()));
        Vector3 previous = null;
        // The reported straight texture line was a silhouette across hidden triangles: a half-pixel step
        // jumped nearly four world units. Test the actual drawn sheet, independently of its shading normals.
        for (float y = 500; y <= 630; y += .5f) {
            Vector3 origin = new Vector3(850 / 1440f * 2 - 1, 1 - y / 1080f * 2, -1)
                  .prj(camera.camera.invProjectionView);
            Ray ray = new Ray(origin, new Vector3(camera.camera.direction));
            float nearest = Float.POSITIVE_INFINITY;
            Vector3 point = null;
            for (var face : surface.waterFaces) {
                Vector3 hit = new Vector3();
                if (Intersector.intersectRayTriangle(ray, face.a(), face.b(), face.c(), hit)
                      && hit.dst2(origin) < nearest) {
                    nearest = hit.dst2(origin);
                    point = hit;
                }
            }
            assertTrue(point != null, "The lava sheet covers the reported internal lip");
            if (previous != null) {
                assertTrue(point.dst(previous) < BoardRelief.metres(.25f),
                      "The sparse flow must not hide a section of itself behind an artificial angular lip");
            }
            previous = point;
        }
    }

    private static List<BoardSurface.Face> bed(BoardSurface surface) {
        return surface.faces.stream().filter(face -> face.finish() == BoardSurface.Finish.BED).toList();
    }
}
