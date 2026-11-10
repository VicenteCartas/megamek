/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;

/** Placement inputs and finished terrain support, shared by the terrain worker and retained plant snapshots. */
final class BoardVegetation {
    record Site(int elevation, BoardScene.Surface family, BoardScene.Biome biome, boolean natural,
          boolean liquid, int waterDepth, boolean frozen, int exits, BoardRoad.Kind road, int rails, RailRoute.Corridor corridor) { }
    record Key(List<Site> sites) { }
    /** Finished support and accepted mineral soil footprints; no terrain builder or placement cache is retained. */
    record Ground(List<BoardSurface.Face> faces, Map<Vector3, Vector3> normals, Map<Vector3, Float> waterHeights,
          List<BoardSurface.Face> water, List<BoardScene.Feature> minerals) {
        Ground(List<BoardSurface.Face> faces, Map<Vector3, Vector3> normals) {
            this(faces, normals, Map.of(), List.of(), List.of());
        }
    }

    private BoardVegetation() { }

    /** Finish supporting neighbours once before planting, including those outside this chunk or reused by an edit. */
    static Map<Coords, BoardSurface> prepare(BoardScene scene, List<BoardScene.Tile> owners, TerrainLod lod,
          float floor, Map<Coords, BoardSurface> finished) {
        Map<Coords, BoardSurface> sources = new HashMap<>(finished);
        if (!carriesGrass(lod)) { return sources; }
        var needed = new LinkedHashSet<BoardSurface>();
        for (var tile : owners) {
            if (!GpuGroundCover.grows(scene, tile)) { continue; }
            var own = sources.computeIfAbsent(tile.coords(), at -> new BoardSurface(scene, tile, lod));
            needed.add(own);
            for (int direction = 0; direction < 6; direction++) {
                var neighbor = scene.neighbor(tile, direction);
                if (borrows(tile, neighbor)) {
                    var source = sources.computeIfAbsent(neighbor.coords(), at -> new BoardSurface(scene, neighbor, lod));
                    needed.add(source);
                }
            }
        }
        // All tops are ready before any wall borrows its neighbour. This phase precedes parallel snapshot reads.
        for (var source : needed) { source.walls(scene, floor, sources); }
        return sources;
    }

    /**
     * Whether a chunk at this detail carries grass (BoardPlants): from medium, or coarse where a level draws meadows
     * but takes detail late.
     */
    static boolean carriesGrass(TerrainLod lod) {
        return switch (lod) {
            case FULL, MEDIUM -> true;
            case COARSE -> TerrainLod.lateDetail() && BoardQuality.current.meadowShare > 0;
            case DISTANT -> false;
        };
    }

    /** Whether a hex's sculpted support carries ground cover at this detail. */
    static boolean covers(BoardScene scene, BoardScene.Tile tile, TerrainLod lod) {
        return carriesGrass(lod) && GpuGroundCover.grows(scene, tile);
    }

    /** A root belongs to its hex, even where a neighbour's finished slope or shallow bank extends over it. */
    static Ground ground(BoardScene scene, BoardSurface own, float floor, Map<Coords, BoardSurface> sources) {
        if (!covers(scene, own.tile, own.lod())) { return null; }
        List<BoardSurface.Face> faces = new ArrayList<>();
        List<BoardSurface.Face> water = new ArrayList<>(own.waterFaces);
        Map<Vector3, Vector3> normals = new IdentityHashMap<>();
        Map<Vector3, Float> waterHeights = new IdentityHashMap<>();
        Map<BoardSurface, Map<Vector3, Vector3>> topNormals = new IdentityHashMap<>();
        for (var face : own.faces) {
            if (groundFace(own.tile, face)) { add(scene, own, face, faces, normals, waterHeights, topNormals); }
        }
        if (BoardGeometry.tuning().stepsBetweenTops()) {
            for (var face : BoardTacticalGeometry.lying(own.walls(scene, floor, sources))) {
                add(scene, own, face, faces, normals, waterHeights, topNormals);
            }
        }
        float x = BoardGeometry.centerX(own.tile.coords()), y = BoardGeometry.centerY(own.tile.coords());
        float halfWidth = BoardGeometry.width() * .5f, halfHeight = BoardGeometry.height() * .5f;
        for (int direction = 0; direction < 6; direction++) {
            var neighbor = scene.neighbor(own.tile, direction);
            if (!borrows(own.tile, neighbor)) { continue; }
            var source = sources.get(neighbor.coords());
            if (source == null) { continue; }
            water.addAll(source.waterFaces);
            List<BoardSurface.Face> incoming = new ArrayList<>();
            // Curved shores cross the canonical hex edge in either direction. Both owners use the drawn ground.
            if (GpuGroundCover.shallowShore(own.tile) || GpuGroundCover.shallowShore(neighbor)) {
                for (var face : source.faces) {
                    if (groundFace(neighbor, face)) { incoming.add(face); }
                }
            }
            if (BoardGeometry.tuning().stepsBetweenTops() && higherNatural(own.tile, neighbor)) {
                incoming.addAll(BoardTacticalGeometry.lying(source.walls(scene, floor, sources)));
            }
            for (var face : incoming) {
                if (Math.max(face.a().x, Math.max(face.b().x, face.c().x)) < x - halfWidth
                      || Math.min(face.a().x, Math.min(face.b().x, face.c().x)) > x + halfWidth
                      || Math.max(face.a().y, Math.max(face.b().y, face.c().y)) < y - halfHeight
                      || Math.min(face.a().y, Math.min(face.b().y, face.c().y)) > y + halfHeight) { continue; }
                add(scene, source, face, faces, normals, waterHeights, topNormals);
            }
        }
        return new Ground(List.copyOf(faces), Collections.unmodifiableMap(normals),
              Collections.unmodifiableMap(waterHeights), List.copyOf(water), GpuMineralGround.accepted(scene, own.tile, own));
    }

    private static boolean groundFace(BoardScene.Tile tile, BoardSurface.Face face) {
        return face.finish() == BoardSurface.Finish.TOP || GpuGroundCover.shallowShore(tile)
              && (face.finish() == BoardSurface.Finish.SHORE || face.finish() == BoardSurface.Finish.BED);
    }

    private static boolean borrows(BoardScene.Tile owner, BoardScene.Tile neighbor) {
        return BoardGeometry.tuning().stepsBetweenTops() && higherNatural(owner, neighbor)
              || GpuGroundCover.shallowShore(neighbor)
              || GpuGroundCover.shallowShore(owner) && BoardSurfaceBlend.natural(neighbor);
    }

    /** A higher natural hex, or water standing above this hex, whose slope down to it is this land's own. */
    private static boolean higherNatural(BoardScene.Tile owner, BoardScene.Tile neighbor) {
        return neighbor != null && neighbor.elevation() > owner.elevation() && (BoardSurfaceBlend.natural(neighbor)
              || !owner.liquid().present() && neighbor.liquid().present() && !neighbor.liquid().volcanic() && !neighbor.frozen());
    }

    private static void add(BoardScene scene, BoardSurface source, BoardSurface.Face face,
          List<BoardSurface.Face> faces, Map<Vector3, Vector3> normals, Map<Vector3, Float> waterHeights,
          Map<BoardSurface, Map<Vector3, Vector3>> topNormals) {
        if (BoardSurface.bridgeSupport(scene, source.tile, face)) { return; }
        faces.add(face);
        for (var vertex : List.of(face.a(), face.b(), face.c())) {
            if (GpuGroundCover.shallowShore(source.tile)) {
                // Match the rendered wet-ground reference, including descending streams' exposed outer banks.
                waterHeights.computeIfAbsent(vertex, p -> Math.min(source.waterHeight(p.x, p.y),
                      Math.max(p.z, BoardGeometry.waterZ(source.tile))));
            }
            var shade = source.relief.shade(vertex);
            if (shade != null) { normals.put(vertex, shade.normal()); }
            else if (face.finish() == BoardSurface.Finish.TOP && !source.relief.sculpted()) {
                // The ordinary top renderer uses area-weighted carrier normals.
                var carrier = topNormals.computeIfAbsent(source, key -> BoardSurface.vertexNormals(key.faces.stream()
                      .filter(top -> top.finish() == BoardSurface.Finish.TOP).toList()));
                normals.put(vertex, carrier.getOrDefault(vertex, Vector3.Z));
            } else {
                // Unshaded sculpt vertices use the same upward normal in GpuTerrain.sculptVertex.
                normals.put(vertex, Vector3.Z);
            }
        }
    }

    static Key key(BoardScene scene, BoardScene.Tile owner) {
        // A root on a shared slope can lie in a neighbour, whose cover samples its own neighbours.
        // Distant shoreline inputs have already been resolved into the published support triangles.
        List<Site> sites = new ArrayList<>(19);
        Coords at = owner.coords();
        for (int x = at.getX() - 2; x <= at.getX() + 2; x++) {
            for (int y = at.getY() - 3; y <= at.getY() + 3; y++) {
                if (at.distance(x, y) > 2) { continue; }
                var tile = scene.tile(new Coords(x, y));
                sites.add(tile == null ? null : new Site(tile.elevation(), tile.surface(), BoardBiome.kind(tile),
                      BoardSurfaceBlend.natural(tile), tile.liquid().present(), tile.waterDepth(), tile.frozen(),
                      tile.wayExits(), tile.road(), tile.railExits(), tile.corridor()));
            }
        }
        return new Key(Collections.unmodifiableList(sites));
    }

    /**
     * Whether a hex and every hex within two of it are ground of one biome on one level: on that level the biome's
     * coverage ({@link BoardBiome#coverage}) is 1 all over the hex.
     */
    static boolean uniform(BoardScene scene, BoardScene.Tile tile, BoardScene.Biome kind) {
        return key(scene, tile).sites().stream()
              .allMatch(site -> site != null && site.biome() == kind && site.elevation() == tile.elevation());
    }

    static boolean sameGround(BoardTacticalGeometry.Surface a, BoardTacticalGeometry.Surface b, boolean slopes) {
        return a == b || a.top().equals(b.top()) && (!slopes || a.slopes().equals(b.slopes()));
    }
}
