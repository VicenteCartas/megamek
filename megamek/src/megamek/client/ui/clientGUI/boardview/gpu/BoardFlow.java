/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.math.Vector2;
import megamek.common.board.Coords;

/** Visual currents inferred from connected surface elevations. Never supplies game movement or terrain rules. */
final class BoardFlow {
    private static final int WATERFALL_APPROACH_HEXES = 3;

    /** Texture displacement per second; zero leaves the authored surface animation in place. */
    record Current(float u, float v) {
        static final Current STILL = new Current(0, 0);
    }

    private BoardFlow() { }

    static Map<Coords, Current> calculate(BoardScene scene) {
        Map<Coords, List<BoardScene.Tile>> neighbors = new HashMap<>();
        Set<Coords> lakes = new HashSet<>();
        for (BoardScene.Tile tile : scene.tiles()) {
            if (!tile.liquid().present() || tile.frozen()) { continue; }
            List<BoardScene.Tile> adjacent = new ArrayList<>();
            for (int direction = 0; direction < 6; direction++) {
                BoardScene.Tile neighbor = scene.tile(tile.coords().translated(direction));
                if (neighbor != null && !neighbor.frozen() && tile.joins(neighbor, direction)) {
                    adjacent.add(neighbor);
                }
            }
            neighbors.put(tile.coords(), adjacent);
        }
        // Water open on five or more sides at its own level is a lake's body, and so is the water beside it. A river
        // stays open on fewer sides, even where it widens to two hexes or runs through shallows: it keeps its current.
        for (var entry : neighbors.entrySet()) {
            int elevation = scene.tile(entry.getKey()).elevation();
            List<BoardScene.Tile> level = entry.getValue().stream()
                  .filter(other -> other.elevation() == elevation).toList();
            if (level.size() < 5) { continue; }
            lakes.add(entry.getKey());
            level.forEach(other -> lakes.add(other.coords()));
        }
        Map<Coords, Coords> downstream = new HashMap<>();
        Set<Coords> visited = new HashSet<>();
        for (BoardScene.Tile start : scene.tiles()) {
            if (!neighbors.containsKey(start.coords()) || !visited.add(start.coords())) { continue; }
            List<BoardScene.Tile> plateau = new ArrayList<>();
            ArrayDeque<BoardScene.Tile> pending = new ArrayDeque<>();
            pending.add(start);
            while (!pending.isEmpty()) {
                BoardScene.Tile tile = pending.removeFirst();
                plateau.add(tile);
                for (BoardScene.Tile neighbor : neighbors.get(tile.coords())) {
                    if (neighbor.elevation() == start.elevation() && visited.add(neighbor.coords())) { pending.add(neighbor); }
                }
            }
            Map<Coords, Integer> distance = new HashMap<>();
            List<BoardScene.Tile> inlets = new ArrayList<>();
            List<BoardScene.Tile> boundary = new ArrayList<>();
            for (BoardScene.Tile tile : plateau) {
                BoardScene.Tile lowest = null;
                boolean hasInlet = false;
                for (BoardScene.Tile neighbor : neighbors.get(tile.coords())) {
                    hasInlet |= neighbor.elevation() > tile.elevation();
                    if (neighbor.elevation() < tile.elevation() && (lowest == null || neighbor.elevation() < lowest.elevation())) {
                        lowest = neighbor;
                    }
                }
                if (hasInlet) { inlets.add(tile); }
                if (lowest != null) {
                    downstream.put(tile.coords(), lowest.coords());
                    distance.put(tile.coords(), 0);
                    pending.add(tile);
                }
                if (edge(scene, tile.coords())) { boundary.add(tile); }
            }
            // Height is the first source of direction. Carry an incoming descent along every branch of its flat
            // receiving reach; routing everything back from one outlet instead turns the other arms into sources.
            if (!inlets.isEmpty()) {
                spreadFromInlets(scene, plateau, inlets, neighbors, downstream, lakes);
                continue;
            }
            // With no height evidence, a narrow river entering at the map edge feeds a wider lake/ocean. An equally
            // narrow edge-to-edge reach remains ambiguous, and the open lake body keeps its local waves, not a drift.
            if (pending.isEmpty() && boundary.stream().anyMatch(tile -> neighbors.get(tile.coords()).size() <= 2)) {
                for (BoardScene.Tile tile : plateau) {
                    if (lakes.contains(tile.coords())) { distance.put(tile.coords(), 0); pending.add(tile); }
                }
            }
            while (!pending.isEmpty()) {
                BoardScene.Tile tile = pending.removeFirst();
                for (BoardScene.Tile neighbor : neighbors.get(tile.coords())) {
                    if (neighbor.elevation() == tile.elevation() && !distance.containsKey(neighbor.coords())) {
                        distance.put(neighbor.coords(), distance.get(tile.coords()) + 1);
                        downstream.put(neighbor.coords(), tile.coords());
                        pending.add(neighbor);
                    }
                }
            }
        }
        Map<Coords, Current> result = new HashMap<>();
        for (var entry : downstream.entrySet()) {
            BoardScene.Tile tile = scene.tile(entry.getKey()), target = scene.tile(entry.getValue());
            if (lakes.contains(tile.coords()) && (target == null || target.elevation() == tile.elevation())) { continue; }
            Vector2 direction = direction(tile.coords(), entry.getValue());
            Vector2 incoming = new Vector2();
            for (BoardScene.Tile neighbor : neighbors.get(tile.coords())) {
                if (tile.coords().equals(downstream.get(neighbor.coords()))) { incoming.add(direction(neighbor.coords(), tile.coords())); }
            }
            if (!incoming.isZero()) { direction.add(incoming.nor()).nor(); }
            float speed = tile.liquid().molten() ? 0.025f : 0.10f + 0.04f * tile.liquid().rapids();
            speed *= waterfallSpeed(scene, tile, downstream, lakes);
            // A river's head, where it ends against its own banks inside the board, runs slow. At the board's edge the
            // water runs on as if into the hex beyond.
            if (neighbors.get(tile.coords()).size() <= 1 && !edge(scene, tile.coords())) { speed *= .4f; }
            // UV V points toward world -Y. Offsetting against the velocity moves the painted features downstream.
            result.put(tile.coords(), new Current(-direction.x * speed, direction.y * speed * BoardGeometry.width() / BoardGeometry.height()));
        }
        return Map.copyOf(result);
    }

    private static void spreadFromInlets(BoardScene scene, List<BoardScene.Tile> plateau,
          List<BoardScene.Tile> inlets, Map<Coords, List<BoardScene.Tile>> neighbors,
          Map<Coords, Coords> downstream, Set<Coords> lakes) {
        Map<Coords, Integer> distance = new HashMap<>();
        ArrayDeque<BoardScene.Tile> pending = new ArrayDeque<>(inlets);
        inlets.forEach(tile -> distance.put(tile.coords(), 0));
        while (!pending.isEmpty()) {
            BoardScene.Tile tile = pending.removeFirst();
            for (BoardScene.Tile neighbor : neighbors.get(tile.coords())) {
                if (neighbor.elevation() == tile.elevation() && !distance.containsKey(neighbor.coords())) {
                    distance.put(neighbor.coords(), distance.get(tile.coords()) + 1);
                    pending.add(neighbor);
                }
            }
        }
        for (BoardScene.Tile tile : plateau) {
            // Direct downhill outlets retain priority. The lake body can receive several inflows without acquiring
            // a single board-wide translation; isolated/closed pools are animated by their liquid's wave field.
            if (downstream.containsKey(tile.coords()) || lakes.contains(tile.coords())) { continue; }
            for (BoardScene.Tile neighbor : neighbors.get(tile.coords())) {
                if (neighbor.elevation() == tile.elevation()
                      && distance.get(neighbor.coords()) == distance.get(tile.coords()) + 1) {
                    downstream.put(tile.coords(), neighbor.coords());
                    break;
                }
            }
            // A fed branch may leave the board even when another branch has an on-board lower outlet. Closed tips
            // have no invented outlet through their banks and instead keep their local surface circulation.
            if (!downstream.containsKey(tile.coords()) && edge(scene, tile.coords()) && distance.get(tile.coords()) > 0) {
                for (int direction = 0; direction < 6; direction++) {
                    Coords outside = tile.coords().translated(direction);
                    if (scene.tile(outside) == null) { downstream.put(tile.coords(), outside); break; }
                }
            }
        }
    }

    /** Follow the connected stream, not straight-line proximity to an unrelated waterfall. */
    private static float waterfallSpeed(BoardScene scene, BoardScene.Tile tile, Map<Coords, Coords> downstream, Set<Coords> lakes) {
        for (int distance = 0; distance < WATERFALL_APPROACH_HEXES; distance++) {
            Coords next = downstream.get(tile.coords());
            BoardScene.Tile target = next == null ? null : scene.tile(next);
            if (target == null) { break; }
            int drop = tile.elevation() - target.elevation();
            if (drop > 0) {
                // Sloping streams gather whitewater on their descent, not over the level approach (BoardSurface).
                if (BoardSurface.waterSlope(tile, target)) { return 1; }
                // Bounded artistic acceleration, fading upstream over three hexes; no change to GIF timing.
                float proximity = 1 - distance / (float) WATERFALL_APPROACH_HEXES;
                return 1 + 1.25f * (float) Math.sqrt(Math.min(drop, 4)) * proximity;
            }
            if (lakes.contains(tile.coords())) { break; }
            tile = target;
        }
        return 1;
    }

    private static boolean edge(BoardScene scene, Coords coords) {
        return coords.getX() == 0 || coords.getX() == scene.width() - 1 || coords.getY() == 0
              || coords.getY() == scene.height() - 1;
    }

    private static Vector2 direction(Coords from, Coords to) {
        return new Vector2(BoardGeometry.centerX(to) - BoardGeometry.centerX(from),
              BoardGeometry.centerY(to) - BoardGeometry.centerY(from)).nor();
    }
}
