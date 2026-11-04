/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import org.junit.jupiter.api.Test;

class GpuFlagTest {
    private static float[] cloth(Matrix4 transform, float phase, Vector3 wind, boolean vacuum, List<BoundingBox> boxes) {
        float[] vertices = GpuFlag.data().meshes.first().vertices;
        GpuFlag.deform(vertices, transform, phase, wind, vacuum, boxes, GpuFlag.NO_GROUND);
        return vertices;
    }

    private static Vector3 point(float[] vertices, int row, int column, Matrix4 transform) {
        int at = (row * (GpuFlag.COLUMNS + 1) + column) * 8;
        return new Vector3(vertices[at], vertices[at + 1], vertices[at + 2]).mul(transform);
    }

    @Test void gravityAndWindRemainWorldAlignedThroughRotationsMirrorsAndUnevenScale() {
        for (float rotation : new float[] { 0, 90, 180, 270 }) {
            for (float tilt : new float[] { -90, 0, 90 }) {
                for (float mirror : new float[] { -1, 1 }) {
                    Matrix4 transform = new Matrix4().translate(11, 17, 40).rotate(Vector3.Z, rotation)
                          .rotate(Vector3.Y, tilt).scale(mirror * 2, .4f, 1.5f);
                    var reach = GpuFlag.bounds(transform);
                    for (float strength : new float[] { 0, .1f, 1 }) {
                        float[] mesh = cloth(transform, 2, new Vector3(0, -1, strength), false, List.of());
                        for (int row = 0; row <= GpuFlag.ROWS; row++) {
                            Vector3 root = new Vector3(0, 0, (1 - (float) row / GpuFlag.ROWS) * GpuFlag.HEIGHT).mul(transform);
                            assertTrue(root.epsilonEquals(point(mesh, row, 0, transform), .0001f), "The seam must stay rigid");
                            Vector3 fly = point(mesh, row, GpuFlag.COLUMNS, transform).sub(root);
                            assertTrue(fly.z < 0, "Gravity always points down");
                            if (strength == 1) { assertTrue(fly.y < -30, "North-to-south wind must stay south after any transform"); }
                            else { assertTrue(fly.z < -28, "Calm cloth still hangs down while retaining a usable spread"); }
                            for (int column = 1; column <= GpuFlag.COLUMNS; column++) {
                                Vector3 current = point(mesh, row, column, transform);
                                assertTrue(reach.contains(current));
                                assertEquals(2 * GpuFlag.WIDTH / GpuFlag.COLUMNS,
                                      current.dst(point(mesh, row, column - 1, transform)), .0001f);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test void calmUsesASmallBreezeWhileVacuumHasNoFlutter() {
        Matrix4 transform = new Matrix4();
        float[] calm = cloth(transform, 0, new Vector3(0, 1, 0), false, List.of());
        assertArrayEquals(calm, cloth(transform, 0, new Vector3(0, 1, .15f), false, List.of()));
        assertFalse(java.util.Arrays.equals(calm, cloth(transform, 1, new Vector3(0, 1, 0), false, List.of())));
        assertArrayEquals(cloth(transform, 0, new Vector3(0, 1, 1), true, List.of()),
              cloth(transform, 3, new Vector3(0, -1, 1), true, List.of()));
    }

    @Test void sweptContactStopsThinObstaclesAndSlidesAlongBuildingFaces() {
        var wall = new BoundingBox(new Vector3(-100, -3.05f, -100), new Vector3(100, -3, 100));
        var transform = new Matrix4().translate(0, 0, 30).rotate(Vector3.Y, -90);
        float[] mesh = cloth(transform, 0, new Vector3(0, -1, 1), false, List.of(wall));
        for (int row = 0; row <= GpuFlag.ROWS; row++) {
            for (int column = 0; column <= GpuFlag.COLUMNS; column++) {
                Vector3 point = point(mesh, row, column, transform);
                assertTrue(point.y > wall.max.y, "No vertex may tunnel through a thin obstacle");
            }
            assertTrue(point(mesh, row, GpuFlag.COLUMNS, transform).z < 28, "Contact still allows downward sliding");
        }
        var endpoint = new Vector3(10, 2, 2);
        GpuFlag.slide(new Vector3(-10, 0, 0), endpoint,
              new BoundingBox(new Vector3(0, -5, -5), new Vector3(.01f, 5, 5)));
        assertTrue(endpoint.x < 0, "Sweeps catch an obstacle even when both endpoints are outside it");
        assertTrue(endpoint.z >= 2, "Blocked motion slides along the face rather than compressing the cloth row");
        assertTrue(endpoint.dst(new Vector3(-10, 0, 0)) <= new Vector3(20, 2, 2).len());
    }

    @Test void clothRestsAboveFlatAndSlopedGroundInCalmAndWindAfterTransforming() {
        for (boolean slope : new boolean[] { false, true }) {
            java.util.function.ToDoubleFunction<Vector3> ground = p -> slope ? 1 + .04 * p.x - .03 * p.y : 1;
            for (float tilt : new float[] { 0, -90, 90 }) {
                for (float mirror : new float[] { -1, 1 }) {
                    Matrix4 transform = new Matrix4().translate(3, -5, 4).rotate(Vector3.Z, 37)
                          .rotate(Vector3.Y, tilt).scale(2 * mirror, .7f, 1.3f);
                    for (float strength : new float[] { 0, 1 }) {
                        var data = GpuFlag.data();
                        float[] vertices = data.meshes.first().vertices;
                        GpuFlag.deform(vertices, transform, 2, new Vector3(0, -1, strength), false, List.of(), ground);
                        boolean touching = false;
                        for (int row = 0; row <= GpuFlag.ROWS; row++) {
                            Vector3 root = new Vector3(0, 0, (1 - (float) row / GpuFlag.ROWS) * GpuFlag.HEIGHT).mul(transform);
                            assertTrue(root.epsilonEquals(point(vertices, row, 0, transform), .0001f));
                            for (int column = 0; column <= GpuFlag.COLUMNS; column++) {
                                Vector3 current = point(vertices, row, column, transform);
                                assertTrue(current.z >= ground.applyAsDouble(current) - .0001);
                                touching |= current.z < ground.applyAsDouble(current) + .1;
                            }
                        }
                        assertTrue(touching, "The cloth must reach and rest on the ground");
                        // Planar ground must not cut through triangle interiors either.
                        short[] indices = data.meshes.first().parts[0].indices;
                        for (int at = 0; at < indices.length; at += 3) {
                            Vector3 center = new Vector3();
                            for (int corner = 0; corner < 3; corner++) {
                                int offset = indices[at + corner] * 8;
                                center.add(new Vector3(vertices[offset], vertices[offset + 1], vertices[offset + 2]).mul(transform));
                            }
                            center.scl(1f / 3);
                            assertTrue(center.z >= ground.applyAsDouble(center) - .0001);
                        }
                    }
                }
            }
        }
    }

    @Test void groundContactChecksBetweenEndpointsAndLeavesOffBoardClothFree() {
        Vector3 start = new Vector3(0, 0, 2), end = new Vector3(4, 0, 1);
        GpuFlag.ground(start, end, p -> p.x > 1.9 && p.x < 2.1 ? 6 : 0, new Vector3());
        assertEquals(start, end, "A raised step between endpoints must stop the cloth, not let it tunnel through");
        end.set(20, 0, 1);
        GpuFlag.ground(start, end, p -> p.x > 2.4 && p.x < 2.6 ? 6 : 0, new Vector3());
        assertEquals(start, end, "Enlarging a flag must also increase terrain sampling along its longer segments");
        end.set(4, 0, -3);
        GpuFlag.ground(start, end, GpuFlag.NO_GROUND, new Vector3());
        assertEquals(new Vector3(4, 0, -3), end, "There is no invented ground outside the board");
    }

    @Test void calmClothKeepsWidthFoldsAndAPickableSurface() {
        for (float mirror : new float[] { -1, 1 }) {
            for (float phase : new float[] { 0, 1, 3 }) {
                Matrix4 transform = new Matrix4().scale(mirror, 1, 1);
                float[] vertices = cloth(transform, phase, new Vector3(1, 0, 0), false, List.of());
                var bounds = new BoundingBox().inf();
                float area = 0;
                for (int row = 0; row <= GpuFlag.ROWS; row++) {
                    for (int column = 0; column <= GpuFlag.COLUMNS; column++) { bounds.ext(point(vertices, row, column, transform)); }
                }
                short[] indices = GpuFlag.data().meshes.first().parts[0].indices;
                for (int i = 0; i < indices.length / 2; i += 3) {
                    Vector3 a = vertex(vertices, indices[i], transform), b = vertex(vertices, indices[i + 1], transform);
                    Vector3 c = vertex(vertices, indices[i + 2], transform);
                    area += b.sub(a).crs(c.sub(a)).len() / 2;
                }
                assertTrue(bounds.getWidth() > GpuFlag.WIDTH * .35f, "A calm flag must retain usable downwind width");
                assertTrue(bounds.getHeight() > GpuFlag.WIDTH * .025f, "Static folds must give the surface depth");
                assertTrue(area > GpuFlag.WIDTH * GpuFlag.HEIGHT * .35f, "The sheet must not collapse onto a line");
            }
        }
    }

    private static Vector3 vertex(float[] vertices, int index, Matrix4 transform) {
        return new Vector3(vertices[index * 8], vertices[index * 8 + 1], vertices[index * 8 + 2]).mul(transform);
    }

    @Test void contactAtGroundLevelDoesNotCollapseSmallFlagsOrPushThemIntoOverhangs() {
        Vector3 end = new Vector3(.001f, 0, -.01f);
        GpuFlag.ground(new Vector3(), end, p -> 0, new Vector3());
        assertTrue(end.z > 0 && end.x > 0, "Clearance must scale down and start from the attachment");
        var roof = new BoundingBox(new Vector3(-10, -10, .03f), new Vector3(10, 10, 1));
        Matrix4 transform = new Matrix4().translate(0, 0, .01f).rotate(Vector3.Y, 90).scale(.01f, .01f, .01f);
        float[] vertices = GpuFlag.data().meshes.first().vertices;
        GpuFlag.deform(vertices, transform, 0, new Vector3(1, 0, 0), false, List.of(roof), p -> 0);
        for (int i = 0; i < vertices.length / 8; i++) {
            Vector3 point = vertex(vertices, i, transform);
            assertTrue(point.z >= -.00001f);
            assertFalse(roof.contains(point), "Terrain correction must not put cloth inside an overhang");
        }
    }

    @Test void terrainBetweenClothRowsCannotPokeThroughTheSheet() {
        Matrix4 transform = new Matrix4().translate(0, 0, 2).rotate(Vector3.Y, 90);
        java.util.function.ToDoubleFunction<Vector3> ground = p -> Math.max(0, .6 - 2 * Math.abs(p.x - .375));
        float[] vertices = GpuFlag.data().meshes.first().vertices;
        GpuFlag.deform(vertices, transform, 0, new Vector3(0, -1, 0), false, List.of(), ground);
        for (int column = 1; column <= GpuFlag.COLUMNS; column++) {
            Vector3 midpoint = point(vertices, GpuFlag.ROWS - 1, column, transform)
                  .lerp(point(vertices, GpuFlag.ROWS, column, transform), .5f);
            assertTrue(midpoint.z >= ground.applyAsDouble(midpoint) - .002, "Cross-row terrain contact was missed");
        }
    }

    @Test void buildingContactPreservesTheConnectedSheetAndDoesNotSplitTheAttachment() {
        for (float mirror : new float[] { -1, 1 }) {
            Matrix4 transform = new Matrix4().translate(0, 0, 5).scale(mirror, 1, 1);
            var building = new BoundingBox(new Vector3(-5, -10, 0), new Vector3(5, .2f, 12));
            BoundingBox collider = GpuFlag.attachmentBounds(building, transform);
            float[] vertices = cloth(transform, 1, new Vector3(0, -1, .65f), false, List.of(building));
            float maximum = 0;
            String worst = "";
            for (int row = 0; row <= GpuFlag.ROWS; row++) {
                assertTrue(point(vertices, row, 0, transform).epsilonEquals(
                      new Vector3(0, 0, 5 + (1 - (float) row / GpuFlag.ROWS) * GpuFlag.HEIGHT), .0001f));
                for (int column = 1; column <= GpuFlag.COLUMNS; column++) {
                    Vector3 current = point(vertices, row, column, transform);
                    assertFalse(collider.contains(current));
                    maximum = Math.max(maximum, current.dst(point(vertices, row, column - 1, transform)) / (GpuFlag.WIDTH / GpuFlag.COLUMNS));
                    if (row > 0) { maximum = Math.max(maximum, current.dst(point(vertices, row - 1, column, transform)) / (GpuFlag.HEIGHT / GpuFlag.ROWS)); }
                    if (maximum > 1.5f && worst.isEmpty()) { worst = " at row " + row + ", column " + column + ": " + current; }
                }
            }
            assertTrue(maximum < 1.5f, "Collision must not create stretched spikes between rows: " + maximum + worst);
        }
    }

    @Test void solidGroundContactIncludesIceWithoutChangingPlacementHeightQueries() {
        var bed = new BoardSurface.Face(new Vector3(0, 0, -4), new Vector3(10, 0, -4), new Vector3(0, 10, -4), BoardSurface.Finish.BED);
        var ice = new BoardSurface.Face(new Vector3(0, 0, 0), new Vector3(10, 0, 0), new Vector3(0, 10, 0), BoardSurface.Finish.ICE);
        assertEquals(-4, BoardSurface.sampleHeight(List.of(bed, ice), 1, 1, -4));
        assertEquals(0, BoardSurface.sampleHeight(List.of(bed, ice), 1, 1, -4, true));
    }

    @Test void aNearbySteelStickMustNotBecomeAnInfiniteWall() {
        var stick = new BoundingBox(new Vector3(-.35f, -.35f, 0), new Vector3(.35f, .35f, 24));
        var contact = GpuFlag.attachmentBounds(stick, new Matrix4().translate(.5f, 0, 4));
        assertEquals(stick.min, contact.min);
        assertEquals(stick.max, contact.max);
    }
}
