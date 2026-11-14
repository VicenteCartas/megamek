/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Polygon;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.GdxNativesLoader;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import megamek.common.Configuration;
import megamek.common.board.Coords;
import megamek.common.units.EntityMovementMode;
import org.junit.jupiter.api.Test;

class InfantryFootprintTest {
    @Test
    void roughFootingFindsAGapButNeverPushesAFullPlateauOverItsEdge() {
        var bounds = new BoundingBox(new Vector3(-2, -2, 0), new Vector3(2, 2, 8));
        var outline = InfantryFootprint.outline(bounds, 0, true);
        var rock = new Polygon(new float[] { -8, -8, 8, -8, 8, 8, -8, 8 });
        var start = new Vector3(0, 0, 7);
        assertTrue(InfantryFootprint.avoidRough(start, outline, List.of(rock), 1, InfantryFootprint.NO_STEPS));
        assertFalse(Intersector.overlapConvexPolygons(outline, rock));
        assertTrue(inside(start, bounds, 0, 1, InfantryFootprint.NO_STEPS));
        assertEquals(7, start.z);
        var occupied = new Polygon(new float[] { -100, -100, 100, -100, 100, 100, -100, 100 });
        var original = new Vector3(0, 0, 7);
        start.set(original);
        assertFalse(InfantryFootprint.avoidRough(start, outline, List.of(occupied), 1, InfantryFootprint.NO_STEPS));
        assertEquals(original, start, "With no gap, stand on the rock instead of walking off the plateau");
    }

    @Test
    void fitsDifferentHeadingsAtBothBoardScalesWithoutChangingHeightOrMesh() {
        GdxNativesLoader.load();
        var bounds = new BoundingBox(new Vector3(-6, -5, 0), new Vector3(6, 8, 32));
        for (float scale : new float[] { .7f, 1 }) {
            for (int heading = 0; heading < 360; heading += 15) {
                var position = new Vector3(42, 26, 7);
                assertTrue(InfantryFootprint.fit(position, InfantryFootprint.outline(bounds, heading, false), scale,
                      InfantryFootprint.NO_STEPS));
                assertEquals(7, position.z);
                assertTrue(inside(position, bounds, heading, scale, InfantryFootprint.NO_STEPS));
            }
        }
    }

    @Test
    void aTroopWatchingAboutStaysInsideAtEveryHeading() {
        GdxNativesLoader.load();
        // Arm and weapon reach forward: fitted at one heading, a turn to watch outward would carry them past the edge.
        var bounds = new BoundingBox(new Vector3(-3, -2, 0), new Vector3(3, 9, 20));
        var position = new Vector3(40, 20, 0);
        assertTrue(InfantryFootprint.fit(position, InfantryFootprint.outline(bounds, 0, true), 1,
              InfantryFootprint.NO_STEPS));
        for (int heading = 0; heading < 360; heading += 15) {
            assertTrue(inside(position, bounds, heading, 1, InfantryFootprint.NO_STEPS), "Out at heading " + heading);
        }
    }

    @Test
    void crowdedTransportsBleedInsteadOfShrinkingOrOverlapping() {
        var bounds = new BoundingBox(new Vector3(-20, -29, 0), new Vector3(20, 29, 15));
        var positions = List.of(new Vector3(-32, 12, 0), new Vector3(32, -12, 0));
        var outlines = List.of(InfantryFootprint.outline(bounds, -17, false),
              InfantryFootprint.outline(bounds, 15, false));
        float layout = InfantryFootprint.compress(positions, outlines, 1, InfantryFootprint.NO_STEPS);
        assertFalse(overlapping(positions, outlines, layout));
        // Too large for the hex together, they stand as close as they can instead of spreading out.
        assertTrue(overlapping(positions, outlines, layout * .98f));
        assertEquals(40, bounds.getWidth());
        assertEquals(58, bounds.getHeight());
        var impossible = new BoundingBox(new Vector3(-50, -50, 0), new Vector3(50, 50, 15));
        var centred = new Vector3(10, 20, 3);
        assertFalse(InfantryFootprint.fit(centred, InfantryFootprint.outline(impossible, 0, false), 1,
              InfantryFootprint.NO_STEPS));
        assertEquals(0, centred.x, .001f, "A member larger than the hex overflows it evenly");
        assertEquals(0, centred.y, .001f);
        assertEquals(3, centred.z);
    }

    @Test
    void mechanizedFormationDrawsInToFitItsHexAndOverflowsOnlyWhenItCannot() {
        GdxNativesLoader.load();
        var formation = Formation.tracked();
        var original = BoardGeometry.tuning();
        float previous = 1;
        try {
            // Exercise both fitting and overcrowding after conversion from the shared Atlas model scale.
            for (float size : new float[] { .6f, 1, 2, 3, 5 }) {
                float scale = formation.scale(original, size);
                float layout = InfantryFootprint.compress(formation.positions, formation.outlines(), scale,
                      InfantryFootprint.NO_STEPS);
                assertTrue(layout <= previous, "A larger formation never spreads wider");
                previous = layout;
                assertFalse(overlapping(formation.positions, formation.outlines(), layout), "Overlap at size " + size);
                boolean inside = formation.inside(layout, scale, InfantryFootprint.NO_STEPS);
                if (size < 1) {
                    assertEquals(1, layout, "The authored layout already fits");
                } else if (size <= 3) {
                    assertTrue(inside, "The whole formation, drawn in, fits at size " + size);
                } else {
                    assertFalse(inside, "Too crowded to fit the hex at size " + size);
                    assertTrue(overlapping(formation.positions, formation.outlines(), layout * .98f), "Squeezed tight");
                }
            }
        } finally {
            BoardGeometry.tune(original);
        }
    }

    @Test
    void formationKeepsOffTheStepsAroundItsHex() {
        GdxNativesLoader.load();
        var formation = Formation.tracked();
        // A plateau with hex transitions: the slopes down take room on this hex's side of all its edges but one.
        float[] room = { 12, 12, 12, 0, 12, 12 };
        var original = BoardGeometry.tuning();
        try {
            float scale = formation.scale(original, 1);
            float level = InfantryFootprint.compress(formation.positions, formation.outlines(), scale,
                  InfantryFootprint.NO_STEPS);
            float stepped = InfantryFootprint.compress(formation.positions, formation.outlines(), scale, room);
            assertTrue(stepped < level, "The steps draw the formation further in");
            assertTrue(formation.inside(stepped, scale, room), "Nobody stands on a slope");
            assertFalse(overlapping(formation.positions, formation.outlines(), stepped));
        } finally {
            BoardGeometry.tune(original);
        }
    }

    @Test
    void formationStaysOnTheRenderedPlateauBesideRivers() {
        GdxNativesLoader.load();
        var formation = Formation.tracked();
        var original = BoardGeometry.tuning();
        try {
            float scale = formation.scale(original, UnitFamilyScale.INFANTRY.unitScale());
            for (int level : new int[] { 0, 1, 2 }) {
                for (int direction = 0; direction < 6; direction++) {
                    BoardScene scene = riverPlateau(level, direction);
                    var tile = scene.tile(new Coords(3, 3));
                    var surface = new BoardSurface(scene, tile);
                    float[] room = new float[6];
                    for (int edge = 0; edge < room.length; edge++) { room[edge] = surface.relief.topInset(edge); }
                    var layout = InfantryFootprint.layout(formation.positions, formation.outlines(), scale, room);
                    assertFalse(overlapping(formation.positions, formation.outlines(), layout.scale()));
                    var top = surface.faces.stream().filter(face -> face.finish() == BoardSurface.Finish.TOP).toList();
                    Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
                    for (int member = 0; member < formation.positions.size(); member++) {
                        var outline = formation.outlines().get(member);
                        Vector3 position = layout.place(formation.positions.get(member));
                        outline.setPosition(position.x, position.y);
                        float[] vertices = outline.getTransformedVertices();
                        for (int vertex = 0; vertex < vertices.length; vertex += 2) {
                            float x = center.x + vertices[vertex] * scale;
                            float y = center.y + vertices[vertex + 1] * scale;
                            assertTrue(Float.isFinite(BoardSurface.sampleHeight(top, x, y, Float.NaN)),
                                  "Unsupported member " + member + " river level " + level + " direction " + direction
                                        + " at " + x + ", " + y + " layout " + layout);
                        }
                    }
                }
            }
        } finally {
            BoardGeometry.tune(original);
        }
    }

    /** Three river banks beside the plateau, including the corner moves that a plain edge inset misses. */
    static BoardScene riverPlateau(int waterLevel, int direction) {
        var center = new Coords(3, 3);
        List<Coords> water = List.of(center.translated(direction), center.translated((direction + 1) % 6),
              center.translated((direction + 2) % 6));
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                var coords = new Coords(x, y);
                boolean wet = water.contains(coords);
                tiles.add(BoardScenes.newTile(coords, wet ? waterLevel : 2, wet ? 1 : -1, false, 0,
                      BoardScene.Surface.SAND, List.of(), List.of(),
                      wet ? BoardLiquid.WATER : BoardLiquid.NONE, null));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    @Test
    void troopsClearParkedVehiclesEvenWhenTheyMustLeaveTheHex() {
        var vehicle = InfantryFootprint.polygon(new BoundingBox(new Vector3(-50, -50, 0), new Vector3(50, 50, 15)), 0);
        var troop = new BoundingBox(new Vector3(-4, -4, 0), new Vector3(4, 4, 23));
        var outline = InfantryFootprint.outline(troop, 0, true);
        var position = new Vector3(35, 0, 3);
        InfantryFootprint.avoid(position, outline, List.of(vehicle), 1, InfantryFootprint.NO_STEPS);
        outline.setPosition(position.x, position.y);
        assertFalse(Intersector.overlapConvexPolygons(outline, vehicle));
        var hex = new Coords(0, 0);
        assertFalse(BoardGeometry.contains(hex, position.x + BoardGeometry.centerX(hex),
              position.y + BoardGeometry.centerY(hex)));
        assertEquals(3, position.z);
        assertEquals(8, troop.getWidth());
    }

    /** Four troops beside two tracked transports: the formation the runtime assembles for six display slots. */
    private record Formation(List<Vector3> positions, List<BoundingBox> footprints, float[] headings,
          boolean[] troops) {
        static Formation tracked() {
            var structure = new UnitModelState.Structure(EntityMovementMode.TRACKED, List.of(), List.of(), 28, false);
            var parts = InfantryVisual.parts(structure, json("units/modular/infantry.json"));
            assertEquals(2, parts.stream().filter(part -> part.id().startsWith("vehicle")).count());
            List<Vector3> positions = new ArrayList<>();
            List<BoundingBox> footprints = new ArrayList<>();
            float[] headings = new float[parts.size()];
            boolean[] troops = new boolean[parts.size()];
            for (var part : parts) {
                headings[positions.size()] = part.heading() * MathUtils.radiansToDegrees;
                troops[positions.size()] = part.id().startsWith("trooper");
                positions.add(new Vector3(part.x(), part.y(), 0));
                var bounds = json(part.asset()).get("bounds");
                footprints.add(new BoundingBox(new Vector3(bounds.get("min").asFloatArray()),
                      new Vector3(bounds.get("max").asFloatArray())).mul(new Matrix4().scl(part.scale())));
            }
            return new Formation(positions, footprints, headings, troops);
        }

        List<Polygon> outlines() {
            List<Polygon> outlines = new ArrayList<>();
            for (int i = 0; i < positions.size(); i++) {
                outlines.add(InfantryFootprint.outline(footprints.get(i), headings[i], troops[i]));
            }
            return outlines;
        }

        /**
         * The placement scale at this size relative to the authored one: the Unit scale times the infantry family's
         * size, whatever that default is, through the scale the runtime itself places a formation with.
         */
        float scale(BoardGeometry.Tuning tuning, float size) {
            var family = UnitFamilyScale.INFANTRY;
            BoardGeometry.tune(new BoardGeometry.Tuning(tuning.hexScale(), size / family.unitScale(),
                  tuning.unitHeightScale(), tuning.levelHeight(), tuning.gridShade()));
            var model = new GpuUnitModel(new Model(), null, true, List.of(), null, List.of(), family);
            var unit = new BoardScene.Unit(1, -1, "Formation fit", new BoardScene.Waypoint(new Coords(2, 2), 0, 0),
                  null, false, null, 1, false, null, 0);
            return model.horizontalScale(unit);
        }

        /** Whether every member stands inside, troops at every heading they may turn to while watching. */
        boolean inside(float layout, float scale, float[] room) {
            for (int i = 0; i < positions.size(); i++) {
                var position = new Vector3(positions.get(i)).scl(layout);
                // A vehicle keeps its heading; a troop may turn to any heading while it watches.
                int turns = troops[i] ? 24 : 1;
                for (int turn = 0; turn < turns; turn++) {
                    float heading = troops[i] ? turn * 15 : headings[i];
                    if (!InfantryFootprintTest.inside(position, footprints.get(i), heading, scale, room)) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    private static JsonValue json(String asset) {
        var file = Configuration.dataDir().toPath().resolve("models/" + asset).toFile();
        return new JsonReader().parse(new FileHandle(file));
    }

    /** Whether a footprint at this heading stands inside the hex, clear of the room each edge's step takes. */
    private static boolean inside(Vector3 position, BoundingBox bounds, float heading, float scale, float[] room) {
        var hex = new Coords(0, 0);
        var center = BoardGeometry.center(hex, 0);
        for (float x : new float[] { bounds.min.x, bounds.max.x }) {
            for (float y : new float[] { bounds.min.y, bounds.max.y }) {
                var point = new Vector3(x, y, 0).rotate(Vector3.Z, -heading).add(position).scl(scale).add(center);
                for (int edge = 0; edge < 6; edge++) {
                    var a = BoardGeometry.corner(hex, 0, edge);
                    var b = BoardGeometry.corner(hex, 0, edge + 1);
                    var outward = new Vector3(b.y - a.y, a.x - b.x, 0).nor();
                    if (outward.dot(new Vector3(a).sub(center)) < 0) { outward.scl(-1); }
                    if (outward.dot(new Vector3(point).sub(a)) > .001f - room[edge]) { return false; }
                }
            }
        }
        return true;
    }

    private static boolean overlapping(List<Vector3> positions, List<Polygon> outlines, float layout) {
        for (int i = 0; i < positions.size(); i++) {
            for (int j = i + 1; j < positions.size(); j++) {
                outlines.get(i).setPosition(positions.get(i).x * layout, positions.get(i).y * layout);
                outlines.get(j).setPosition(positions.get(j).x * layout, positions.get(j).y * layout);
                if (Intersector.overlapConvexPolygons(outlines.get(i), outlines.get(j))) { return true; }
            }
        }
        return false;
    }
}
