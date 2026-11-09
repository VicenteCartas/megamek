/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.g3d.model.MeshPart;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMesh;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMeshPart;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNode;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNodePart;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.GdxNativesLoader;
import com.badlogic.gdx.utils.ShortArray;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A kit level broken ahead of a fall: a wall's parts break together across its run, its concrete body into closed
 * fragments that keep its volume, its facade sheet left open; and after the debris batch's own compaction every
 * triangle still lies in the one fragment the shader finds for each of its vertices. Glass, floors and small fittings
 * fall whole. GL is mocked: meshes keep their vertices on the CPU.
 */
class KitFragmentsTest {
    private static final String BODY = GeneratedMesh.BUILDING_BODY;
    private static final int STRIDE = RigidGlb.STRIDE;
    private Application application;
    private GL20 gl, gl20;
    private final List<Mesh> meshes = new ArrayList<>();

    @BeforeAll
    static void loadMathNatives() { GdxNativesLoader.load(); }

    @BeforeEach
    void mockGl() {
        application = Gdx.app;
        gl = Gdx.gl;
        gl20 = Gdx.gl20;
        Gdx.app = mock(Application.class);
        Gdx.gl = mock(GL20.class);
        Gdx.gl20 = Gdx.gl;
    }

    @AfterEach
    void restoreGl() {
        meshes.forEach(Mesh::dispose);
        Gdx.app = application;
        Gdx.gl = gl;
        Gdx.gl20 = gl20;
    }

    @Test
    void aWallBreaksAcrossItsRunIntoClosedFragmentsEachVertexFindsInItsBatch() {
        var kit = new Kit();
        // A wall a storey high, as a kit lays one: six bays of concrete 4 long and 2 thick, its facade a sheet on its
        // face, a window and a floor cap.
        List<Vector3[]> bays = new ArrayList<>();
        for (int bay = 0; bay < 6; bay++) { bays.add(new Vector3[] { new Vector3(-12 + 4 * bay, -1, 0), new Vector3(-8 + 4 * bay, 1, 18) }); }
        kit.node("wall", List.of(kit.boxes("wall/body", BODY, bays),
              kit.sheet("wall/facade", "facade", 1.05f), kit.sheet("wall/glass", "glass", 1.1f),
              kit.box("wall/floor", "rooms#floor", new Vector3(-12, -8, 0), new Vector3(12, -1, .2f))));
        kit.node("light", List.of(kit.box("light/body", BODY, new Vector3(-1, -1, 18), new Vector3(1, 1, 20))));
        var fragments = KitFragments.cut(kit.data(), Set.of("glass", "rooms#floor"));
        fragments.install();

        assertNull(fragments.part(part("wall/glass")), "Glass shatters to nothing");
        assertNull(fragments.part(part("wall/floor")), "A floor cap is the interior's");
        assertNull(fragments.part(part("light/body")), "A small fitting falls whole");
        var body = fragments.part(part("wall/body"));
        var facade = fragments.part(part("wall/facade"));
        assertNotNull(body);
        assertNotNull(facade);
        float[] table = body.fragments().table();
        int count = table.length / 8;
        assertTrue(count >= 3 && count <= GpuDestruction.FRAGMENTS, "It breaks into a few fragments: " + count);
        assertEquals(table.length, facade.fragments().table().length, "Every part of the wall breaks alike");
        for (int fragment = 0; fragment < count; fragment++) {
            for (int value : new int[] { 0, 1, 2, 4, 5, 6 }) {
                assertEquals(table[8 * fragment + value], facade.fragments().table()[8 * fragment + value],
                      "Fragment " + fragment + " moves as one box in every part");
            }
            // Cut across its run, never along it: no fragment is a layer peeled off the wall's face.
            assertTrue(table[8 * fragment + 5] >= .99f, "Fragment " + fragment + " goes through the wall");
        }

        float volume = 0;
        int vertices = 0;
        for (var part : List.of(body, facade)) {
            var copy = GpuTreeInstances.compacted(part.range()).end();
            meshes.add(copy);
            vertices += copy.getNumVertices();
            float[] own = part.fragments().table();
            List<List<Vector3>> byFragment = new ArrayList<>();
            for (int fragment = 0; fragment < count; fragment++) { byFragment.add(new ArrayList<>()); }
            var corners = corners(copy);
            for (int corner = 0; corner < corners.size(); corner += 3) {
                int fragment = fragment(own, corners.get(corner).index());
                for (int at = corner; at < corner + 3; at++) {
                    var vertex = corners.get(at);
                    assertEquals(fragment, fragment(own, vertex.index()), "A triangle's corners lie in one fragment");
                    for (int axis = 0; axis < 3; axis++) {
                        float centre = own[8 * fragment + axis], half = own[8 * fragment + 4 + axis];
                        float along = axis == 0 ? vertex.point().x : axis == 1 ? vertex.point().y : vertex.point().z;
                        assertTrue(Math.abs(along - centre) <= half + 1e-3f,
                              "Vertex " + vertex.index() + " lies in fragment " + fragment + "'s box");
                    }
                    byFragment.get(fragment).add(vertex.point());
                }
            }
            if (part == body) {
                for (int fragment = 0; fragment < count; fragment++) {
                    var triangles = byFragment.get(fragment);
                    assertTrue(openingVector(triangles).len() < 1e-2f, "Fragment " + fragment + " is closed where it broke");
                    volume += volume(triangles);
                }
            } else {
                for (List<Vector3> triangles : byFragment) {
                    for (Vector3 point : triangles) { assertEquals(1.05f, point.y, 1e-4f, "The sheet stays open where it broke"); }
                }
            }
        }
        assertEquals(24 * 2 * 18, volume, 24 * 2 * 18 * 1e-3f, "The body's fragments keep its volume");
        // Its parts as drawn: six boxes of 24 corners and the sheet's 4.
        assertTrue(vertices <= KitFragments.VERTEX_SHARE * (6 * 24 + 4), "The debris' vertices stay bounded: " + vertices);
        fragments.dispose();
    }

    private static MeshPart part(String id) { return new MeshPart(id, null, 0, 0, GL20.GL_TRIANGLES); }

    /** Which fragment the shader puts a vertex in: the last whose first vertex it has reached (tree-instances.glsl). */
    private static int fragment(float[] table, int vertex) {
        int result = 0;
        for (int fragment = 1; fragment < table.length / 8; fragment++) { result += vertex >= (int) (table[8 * fragment + 3] + .5f) ? 1 : 0; }
        return result;
    }

    private record Corner(int index, Vector3 point) { }

    /** The copy's triangles' corners, three by three, with the index each draws. */
    private static List<Corner> corners(Mesh mesh) {
        int stride = mesh.getVertexSize() / Float.BYTES;
        float[] records = new float[mesh.getNumVertices() * stride];
        mesh.getVertices(records);
        short[] indices = new short[mesh.getNumIndices()];
        mesh.getIndices(indices);
        List<Corner> result = new ArrayList<>();
        for (short index : indices) {
            int vertex = Short.toUnsignedInt(index);
            result.add(new Corner(vertex, new Vector3(records[vertex * stride], records[vertex * stride + 1], records[vertex * stride + 2])));
        }
        return result;
    }

    private static Vector3 openingVector(List<Vector3> triangles) {
        var sum = new Vector3();
        for (int at = 0; at < triangles.size(); at += 3) {
            sum.add(new Vector3(triangles.get(at + 1)).sub(triangles.get(at)).crs(new Vector3(triangles.get(at + 2)).sub(triangles.get(at))).scl(.5f));
        }
        return sum;
    }

    private static float volume(List<Vector3> triangles) {
        double volume = 0;
        for (int at = 0; at < triangles.size(); at += 3) {
            volume += triangles.get(at).dot(new Vector3(triangles.get(at + 1)).crs(triangles.get(at + 2))) / 6.0;
        }
        return (float) volume;
    }

    /** A kit level's data as GeneratedMesh#kit lays it out: one welded mesh, a part a node and material. */
    private static final class Kit {
        private final FloatArray vertices = new FloatArray();
        private final List<ModelMeshPart> parts = new ArrayList<>();
        private final List<ModelNode> nodes = new ArrayList<>();

        private record Part(ModelMeshPart mesh, String material) { }

        Part box(String id, String material, Vector3 min, Vector3 max) {
            return boxes(id, material, List.<Vector3[]>of(new Vector3[] { min, max }));
        }

        /** Closed boxes, each corner of a face its own vertex, as one part. */
        Part boxes(String id, String material, List<Vector3[]> boxes) {
            var indices = new ShortArray();
            for (Vector3[] box : boxes) { box(box[0], box[1], indices); }
            return part(id, material, indices);
        }

        private void box(Vector3 min, Vector3 max, ShortArray indices) {
            int[][] faces = { { 0, 2, 3, 1 }, { 4, 5, 7, 6 }, { 0, 1, 5, 4 }, { 2, 6, 7, 3 }, { 0, 4, 6, 2 }, { 1, 3, 7, 5 } };
            Vector3[] outward = { new Vector3(0, 0, -1), new Vector3(0, 0, 1), new Vector3(0, -1, 0), new Vector3(0, 1, 0),
                  new Vector3(-1, 0, 0), new Vector3(1, 0, 0) };
            for (int face = 0; face < 6; face++) {
                int first = vertices.size / STRIDE;
                for (int corner : faces[face]) {
                    vertex((corner & 1) == 0 ? min.x : max.x, (corner & 2) == 0 ? min.y : max.y, (corner & 4) == 0 ? min.z : max.z,
                          outward[face]);
                }
                for (int corner : new int[] { 0, 1, 2, 0, 2, 3 }) { indices.add(first + corner); }
            }
        }

        /** A sheet facing +y at {@code y}, as long and high as the wall. */
        Part sheet(String id, String material, float y) {
            int first = vertices.size / STRIDE;
            vertex(-12, y, 0, Vector3.Y);
            vertex(12, y, 0, Vector3.Y);
            vertex(12, y, 18, Vector3.Y);
            vertex(-12, y, 18, Vector3.Y);
            var indices = new ShortArray();
            for (int corner : new int[] { 0, 2, 1, 0, 3, 2 }) { indices.add(first + corner); }
            return part(id, material, indices);
        }

        private Part part(String id, String material, ShortArray indices) {
            var part = new ModelMeshPart();
            part.id = id;
            part.primitiveType = GL20.GL_TRIANGLES;
            part.indices = indices.toArray();
            parts.add(part);
            return new Part(part, material);
        }

        private void vertex(float x, float y, float z, Vector3 normal) {
            vertices.addAll(x, y, z, normal.x, normal.y, normal.z, 1, 1, 1, 1, x / 18, z / 18);
        }

        void node(String id, List<Part> drawn) {
            var node = new ModelNode();
            node.id = id;
            node.children = new ModelNode[0];
            node.translation = new Vector3();
            node.rotation = new Quaternion();
            node.scale = new Vector3(1, 1, 1);
            node.parts = drawn.stream().map(part -> {
                var nodePart = new ModelNodePart();
                nodePart.meshPartId = part.mesh().id;
                nodePart.materialId = part.material();
                return nodePart;
            }).toArray(ModelNodePart[]::new);
            nodes.add(node);
        }

        RigidGlb.Data data() {
            var data = new RigidGlb.Data();
            data.id = "test-kit-lod0";
            var mesh = new ModelMesh();
            mesh.id = "test-kit-mesh0";
            mesh.attributes = new VertexAttribute[] { VertexAttribute.Position(), VertexAttribute.Normal(),
                  VertexAttribute.ColorUnpacked(), VertexAttribute.TexCoords(0) };
            mesh.vertices = vertices.toArray();
            mesh.parts = parts.toArray(ModelMeshPart[]::new);
            data.meshes.add(mesh);
            nodes.forEach(data.nodes::add);
            return data;
        }
    }

}
