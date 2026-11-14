/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/** Earthworks are dug into their hex's ground, which every view's support and picking share. */
class BoardGroundCutTest {
    private static final Coords TRENCH = new Coords(2, 2);
    /** A dug-in platoon's holes' floor below its hex's top, in metres. */
    private static final float HOLE = 1.4f;
    /** A strongpoint's vehicle pit's floor below its hex's top, in metres. */
    private static final float PIT = 1.8f;

    /** Formation slots 0, 1, 3 and 4: four figures of a squad that dug in with its third and last trooper gone. */
    private static final int FOUR_HOLES = 0b011011;

    @Test
    void aDugInPlatoonsHolesAreDugToTheirFloorAtEveryStand() {
        BoardScene scene = scene(0, false);
        BoardScene.Tile tile = scene.tile(TRENCH);
        float cx = BoardGeometry.centerX(TRENCH), cy = BoardGeometry.centerY(TRENCH), ground = BoardGeometry.groundZ(tile);
        BoardSurface surface = new BoardSurface(scene, tile);
        assertTrue(surface.dug());
        assertEquals(6, surface.stands().size(), "A stand in each hole");
        for (BoardGroundCut.Stand stand : surface.stands()) {
            float x = cx + stand.x(), y = cy + stand.y();
            assertEquals(BoardGroundCut.Kind.TROOP, stand.kind(), stand.toString());
            assertTrue(surface.faces.stream().noneMatch(face -> face.finish() == BoardSurface.Finish.TOP
                  && Float.isFinite(face.height(x, y))), "No top is left over the hole at " + stand);
            assertEquals(-HOLE, (surface.height(x, y) - ground) / BoardRelief.metres(1), .01f,
                  "A stand is on its hole's floor: " + stand);
            // Facing out, away from the hex centre.
            double out = Math.toDegrees(Math.atan2(stand.x(), stand.y()));
            assertEquals(0, Math.abs((stand.heading() - out + 540) % 360 - 180), 40, stand.toString());
        }
        assertEquals(ground, surface.height(cx, cy), .001f, "The model stands on the hex's own top");
        BoardSurface classic = BoardSurface.columns(scene, tile, TerrainLod.FULL);
        assertFalse(classic.dug(), "A Classic column keeps its flat top");
        assertTrue(classic.stands().isEmpty(), "Troops on a Classic column keep their own layout");
    }

    @Test
    void aPlatoonDigsOnlyTheHolesOfItsFiguresSlots() {
        BoardScene all = scene(0, false);
        var holes = new BoardSurface(all, all.tile(TRENCH)).stands();
        BoardScene.Tile tile = BoardFeatures.withTrench(all.tile(TRENCH).withFeatures(List.of()), FOUR_HOLES);
        BoardSurface surface = surfaceWith(all, tile);
        BoardSurface undug = surfaceWith(all, tile.withFeatures(List.of()));
        float cx = BoardGeometry.centerX(TRENCH), cy = BoardGeometry.centerY(TRENCH), ground = BoardGeometry.groundZ(tile);
        assertEquals(List.of(0, 1, 3, 4), surface.stands().stream().map(BoardGroundCut.Stand::number).toList());
        for (var hole : holes) {
            float floor = (surface.height(cx + hole.x(), cy + hole.y()) - ground) / BoardRelief.metres(1);
            float original = (undug.height(cx + hole.x(), cy + hole.y()) - ground) / BoardRelief.metres(1);
            assertEquals(BoardGroundCut.shown(hole.number(), FOUR_HOLES) ? -HOLE : original, floor, .01f,
                  "Only a figure's slot is dug: " + hole);
        }
    }
    @Test
    void aFortifiedHexsStrongpointTakesItsLevelTopWholeAndShrinksBesideACliff() {
        BoardScene level = scene(0, true);
        BoardScene.Tile tile = level.tile(TRENCH);
        var fortification = tile.features().getLast();
        assertSame(fortification, BoardGroundCut.fitted(level, tile, fortification), "A level hex's top takes it whole");
        assertSame(tile, BoardFeatures.withTrench(tile, 0), "A platoon dug in there takes to the strongpoint, digging no holes");
        BoardSurface surface = new BoardSurface(level, tile);
        float cx = BoardGeometry.centerX(TRENCH), cy = BoardGeometry.centerY(TRENCH), ground = BoardGeometry.groundZ(tile);
        assertEquals(5, kind(surface, BoardGroundCut.Kind.TROOP).size(), "A stand on each fire bay's step");
        var vehicles = kind(surface, BoardGroundCut.Kind.VEHICLE);
        assertEquals(2, vehicles.size(), "Two transports side by side");
        assertEquals(-PIT, (surface.height(cx, cy) - ground) / BoardRelief.metres(1), .01f, "A hull-down tank parks on its floor");
        for (var vehicle : vehicles) {
            assertEquals(-PIT, (surface.height(cx + vehicle.x(), cy + vehicle.y()) - ground) / BoardRelief.metres(1), .01f,
                  "A transport parks on its floor: " + vehicle);
        }
        var hide = kind(surface, BoardGroundCut.Kind.HIDE).getFirst();
        assertEquals(0, (surface.height(cx + hide.x(), cy + hide.y()) - ground) / BoardRelief.metres(1), .01f,
              "A waiting tank's hide is level ground");

        // A cliff three levels up beyond the north edge: the strongpoint keeps to the top short of its foot.
        BoardScene cramped = scene(3, true);
        BoardScene.Tile below = cramped.tile(TRENCH);
        var fitted = BoardGroundCut.fitted(cramped, below, below.features().getLast());
        assertTrue(fitted.stretch().x() < 1 && fitted.stretch().x() == fitted.stretch().y(), fitted.stretch().toString());
        assertEquals(1, fitted.stretch().z(), "As deep as on a level hex");
        BoardRelief relief = new BoardRelief(cramped, below, BoardSurface.ramps(cramped, below));
        HexEdges edges = new HexEdges(TRENCH);
        for (var face : new BoardSurface(cramped, below).faces) {
            if (face.finish() != BoardSurface.Finish.DUG) { continue; }
            for (var point : List.of(face.a(), face.b(), face.c())) {
                for (int e = 0; e < 6; e++) {
                    assertTrue(edges.inset(e, point.x, point.y) >= relief.topInset(e) - .01f,
                          "Dug ground keeps off edge " + e + " beyond the top: " + point);
                }
            }
        }
    }

    @Test
    void aPavedFortifiedHexsSandbagsDigNothingAndHoldItsUnits() {
        BoardScene level = scene(0, false, BoardScene.Surface.CONCRETE);
        BoardScene.Tile tile = level.tile(TRENCH).withFeatures(List.of(new BoardScene.Feature(BoardFeatures.SANDBAGS, 0, 0,
              0, 1, 1, 0, BoardScene.FeatureKind.SCENERY, 0, true)));
        BoardSurface surface = surfaceWith(level, tile);
        assertFalse(surface.dug(), "Pavement is not dug");
        assertEquals(5, kind(surface, BoardGroundCut.Kind.TROOP).size(), "A stand behind each firing notch");
        assertEquals(2, kind(surface, BoardGroundCut.Kind.VEHICLE).size(), "Two transports in the revetment");
        assertEquals(1, kind(surface, BoardGroundCut.Kind.HIDE).size());
        float cx = BoardGeometry.centerX(TRENCH), cy = BoardGeometry.centerY(TRENCH);
        assertEquals(BoardGeometry.groundZ(tile), surface.height(cx, cy), .001f, "Everything stands on the pavement");
    }

    @Test
    void theEarthworksLevelsLoadBesideTheirGroundWork() {
        File root = new File(Configuration.dataDir(), "models/board");
        for (String asset : List.of(BoardFeatures.TRENCH, BoardFeatures.FORTIFICATION)) {
            var levels = RigidGlb.loadLods(RigidGlb.source(root, asset), root.toPath());
            assertEquals(2, levels.stream().distinct().count(), asset);
            assertFalse(RigidGlb.groundwork(new File(root, asset + ".glb")).cut().isEmpty(), asset);
        }
        assertTrue(RigidGlb.groundwork(new File(root, "rough/sawn-stump.glb")).cut().isEmpty(),
              "A model without ground work digs nothing");
    }

    @Test
    void anEarthworksEarthTakesTheColourOfTheGroundDrawnInItsHex() {
        assertEquals(BoardDecoration.Colours.NONE, BoardGroundCut.earth(tile(BoardScene.Surface.GRASS, null)),
              "Soil keeps the model's own earth");
        Color mars = earth(tile(BoardScene.Surface.MARS, null)), lunar = earth(tile(BoardScene.Surface.LUNAR, null));
        assertTrue(mars.getRed() > mars.getBlue() + 20, "Mars digs red earth: " + mars);
        assertTrue(Math.abs(lunar.getRed() - lunar.getBlue()) < Math.abs(mars.getRed() - mars.getBlue()),
              "The Moon digs greyer earth than Mars: " + lunar);
        // Sand on a grassland hex is drawn as sand, though the hex's theme is grass.
        Hex sandy = new Hex(0);
        sandy.addTerrain(new Terrain(Terrains.SAND, 1));
        assertNear(earth(tile(BoardScene.Surface.SAND, null)),
              earth(tile(BoardScene.Surface.GRASS, BoardSurfaceBlend.capture(sandy))), "Sand cover digs sandy earth");
        var strongpoint = fortification();
        assertEquals(BoardGroundCut.earth(tile(BoardScene.Surface.MARS, null)),
              BoardGroundCut.colours(tile(BoardScene.Surface.MARS, null), strongpoint), "A fortified hex's earthwork too");
    }

    @Test
    void aWaitingTankKeepsItsHullInsideTheHexHangingOverThePitInstead() {
        BoardScene level = scene(0, true);
        BoardScene.Tile tile = level.tile(TRENCH);
        var stands = new BoardSurface(level, tile).stands();
        var hide = kind(stands, BoardGroundCut.Kind.HIDE).getFirst();
        Vector3 centre = BoardGeometry.center(TRENCH, 0);
        HexEdges edges = new HexEdges(TRENCH);
        float length = 11.4f * BoardRelief.metres(1), width = 6.1f * BoardRelief.metres(1);
        for (int facing = 0; facing < 6; facing++) {
            float turn = facing * 60 * MathUtils.degreesToRadians;
            Vector3 along = new Vector3(MathUtils.sin(turn), MathUtils.cos(turn), 0), across = new Vector3(along.y, -along.x, 0);
            List<Vector3> hull = new ArrayList<>();
            for (int end : new int[] { -1, 1 }) {
                for (int side : new int[] { -1, 1 }) {
                    hull.add(new Vector3(centre).mulAdd(along, end * length / 2).mulAdd(across, side * width / 2));
                }
            }
            Vector3 offset = BoardGroundCut.hide(tile, stands, hull);
            assertTrue(offset.len() > 0 && offset.dot(hide.x(), hide.y(), 0) > 0, "Toward the hide: " + offset);
            assertTrue(offset.len() <= new Vector3(hide.x(), hide.y(), 0).len() + .01f, "Never past the hide");
            for (Vector3 corner : hull) {
                for (int e = 0; e < 6; e++) {
                    assertTrue(edges.inset(e, corner.x + offset.x, corner.y + offset.y) >= -.01f,
                          "Facing " + facing + ": the hull keeps inside edge " + e);
                }
            }
        }
    }

    private static List<BoardGroundCut.Stand> kind(BoardSurface surface, BoardGroundCut.Kind kind) {
        return kind(surface.stands(), kind);
    }

    private static List<BoardGroundCut.Stand> kind(List<BoardGroundCut.Stand> stands, BoardGroundCut.Kind kind) {
        return stands.stream().filter(stand -> stand.kind() == kind).toList();
    }

    private static Color earth(BoardScene.Tile tile) {
        return Color.decode(BoardGroundCut.earth(tile).slot(0));
    }

    /** Within a few codes: the cover keeps a little of the theme's ground beside the sand. */
    private static void assertNear(Color expected, Color actual, String message) {
        assertTrue(Math.abs(expected.getRed() - actual.getRed()) <= 12 && Math.abs(expected.getGreen() - actual.getGreen()) <= 12
              && Math.abs(expected.getBlue() - actual.getBlue()) <= 12, message + ": " + expected + " " + actual);
    }

    /** Install an edit in the scene's ground snapshot before asking for its surface. */
    private static BoardSurface surfaceWith(BoardScene scene, BoardScene.Tile tile) {
        var edited = scene.withTiles(scene.tiles().stream().map(t -> t.coords().equals(tile.coords()) ? tile : t).toList());
        return new BoardSurface(edited, edited.tile(tile.coords()));
    }

    /** A level dry tile of {@code surface}, its ground cover {@code cover}, or its surface's alone when null. */
    private static BoardScene.Tile tile(BoardScene.Surface surface, BoardSurfaceBlend.Cover cover) {
        return BoardScenes.newTile(TRENCH, 0, -1, false, 0, surface, List.of(), List.of(),
              BoardLiquid.NONE, null, BoardRoad.Kind.NONE, BoardFireSmoke.NONE, BoardScene.Biome.NONE, false, false,
              0, false, cover == null ? BoardSurfaceBlend.solid(surface) : cover);
    }

    /** A fortified hex's strongpoint, as its terrain-owned layout row places it. */
    private static BoardScene.Feature fortification() {
        return new BoardScene.Feature(BoardFeatures.FORTIFICATION, 0, 0, 0, 1, 1, 0, BoardScene.FeatureKind.SCENERY, 0, true);
    }

    /**
     * A grass board with the earthwork in the middle: a dug-in platoon's holes, or a fortified hex's strongpoint; north
     * of it a hex {@code cliff} levels up.
     */
    private static BoardScene scene(int cliff, boolean fortified) {
        return scene(cliff, fortified, BoardScene.Surface.GRASS);
    }

    private static BoardScene scene(int cliff, boolean fortified, BoardScene.Surface ground) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                Coords coords = new Coords(x, y);
                int level = coords.equals(TRENCH.translated(0)) ? cliff : 0;
                var tile = BoardScenes.newTile(coords, level, -1, false, 0, ground,
                      BoardFeatures.capture(new Hex(level), coords, Map.of()), List.of(), BoardLiquid.NONE, null);
                if (coords.equals(TRENCH)) {
                    if (fortified) {
                        List<BoardScene.Feature> features = new ArrayList<>(tile.features());
                        features.add(fortification());
                        tile = tile.withFeatures(features);
                    } else {
                        tile = BoardFeatures.withTrench(tile, 0);
                    }
                }
                tiles.add(tile);
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** How far a point lies inside each of a hex's edges, toward its centre. */
    private record HexEdges(Coords coords) {
        float inset(int e, float x, float y) {
            var a = BoardGeometry.corner(coords, 0, e);
            var b = BoardGeometry.corner(coords, 0, e + 1);
            float dx = b.x - a.x, dy = b.y - a.y;
            return (dx * (y - a.y) - dy * (x - a.x)) / (float) Math.hypot(dx, dy);
        }
    }
}
