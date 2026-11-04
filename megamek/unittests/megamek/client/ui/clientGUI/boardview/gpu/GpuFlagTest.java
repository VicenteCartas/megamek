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
                            else { assertTrue(fly.z < -34, "Calm cloth hangs by almost its full length"); }
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
        assertArrayEquals(calm, cloth(transform, 0, new Vector3(0, 1, .1f), false, List.of()));
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
        assertEquals(2, endpoint.z);
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
}
