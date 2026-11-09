/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.model.MeshPart;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.GdxNativesLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Rubble from MeepleShatter: closed bodies break into closed pieces that keep their volume, wherever their parts are
 * placed, open sheets break without caps, and board-textured parts keep their texture where they broke. GL is mocked:
 * meshes keep their vertices on the CPU.
 */
class MeepleShatterTest {
    private static final VertexAttributes LAYOUT = MeshBuilder.createAttributes(VertexAttributes.Usage.Position
          | VertexAttributes.Usage.Normal | VertexAttributes.Usage.TextureCoordinates | VertexAttributes.Usage.ColorPacked);
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
    void aBuildingSizedBoxCrumblesIntoClosedPiecesThatKeepItsVolume() {
        Model pieces = pieces(List.of(new MeepleShatter.Source(box(new Vector3(-20, -15, 0), new Vector3(20, 15, 54)),
              new Material("wall"), null, 0)), MeepleShatter.Planes.crumble(2, 4), concrete());
        assertTrue(pieces.nodes.first().getChildCount() >= 12, "It breaks into many pieces: " + pieces.nodes.first().getChildCount());
        float total = 0;
        for (Node piece : pieces.nodes.first().getChildren()) {
            var triangles = triangles(piece);
            assertTrue(opening(triangles) < 1e-3f, piece.id + " is closed: its faces' area vectors cancel out, "
                  + opening(triangles));
            float volume = volume(triangles, Vector3.Zero);
            assertTrue(volume > 0, piece.id + " is right side out: " + volume);
            total += volume;
        }
        assertEquals(40 * 30 * 54, total, 40 * 30 * 54 * 1e-3f, "The pieces keep the box's volume");
    }

    @Test
    void placedAndMirroredPartsBreakWhereTheyStandRightSideOut() {
        var placed = new Matrix4().setToTranslation(400, 250, 30).rotate(Vector3.Z, 30).scale(-1, 1, 1);
        Model pieces = pieces(List.of(new MeepleShatter.Source(box(new Vector3(-10, -10, 0), new Vector3(10, 10, 20)),
              new Material("wall"), placed, 0)), MeepleShatter.Planes.crumble(1, 3), concrete());
        var expected = new BoundingBox(new Vector3(-10, -10, 0), new Vector3(10, 10, 20)).mul(placed);
        float total = 0;
        for (Node piece : pieces.nodes.first().getChildren()) {
            var triangles = triangles(piece);
            for (Vector3 point : triangles) {
                assertTrue(expected.contains(new Vector3(point).scl(.999f).mulAdd(expected.getCenter(new Vector3()), .001f)),
                      "Pieces lie where the placed box stands: " + point);
            }
            total += volume(triangles, Vector3.Zero);
        }
        assertEquals(8000, total, 8, "A mirrored placement's pieces are wound right side out and keep its volume");
    }

    @Test
    void aShellOpenBelowBreaksIntoPiecesClosedWhereTheyWereCut() {
        Model pieces = pieces(List.of(new MeepleShatter.Source(box(new Vector3(-20, -15, 0), new Vector3(20, 15, 36), false),
              new Material("wall"), null, 0)), MeepleShatter.Planes.crumble(1, 3), concrete());
        float floor = 0;
        for (Node piece : pieces.nodes.first().getChildren()) {
            var opening = openingVector(triangles(piece));
            // What stays open is the floor the shell never had, facing into it from below; nothing at the cuts.
            assertEquals(0, opening.x, 1e-2f, piece.id + " is closed across its cuts: " + opening);
            assertEquals(0, opening.y, 1e-2f, piece.id + " is closed across its cuts: " + opening);
            assertTrue(opening.z > -1e-2f, piece.id + " opens only below: " + opening);
            floor += opening.z;
        }
        assertEquals(40 * 30, floor, 1, "Only the missing floor stays open");
    }

    @Test
    void anOpenSheetBreaksWithoutCaps() {
        // One flat quad: there is nothing inside it to close.
        Mesh sheet = mesh(new float[][] { { -20, -20, 0 }, { 20, -20, 0 }, { 20, 20, 0 }, { -20, -20, 0 }, { 20, 20, 0 },
              { -20, 20, 0 } }, new Vector3(0, 0, 1));
        Model pieces = pieces(List.of(new MeepleShatter.Source(new MeshPart("sheet", sheet, 0, 6, GL20.GL_TRIANGLES),
              new Material("roof"), null, 0)), MeepleShatter.Planes.crumble(0, 4), concrete());
        assertTrue(pieces.nodes.first().getChildCount() > 1, "The sheet breaks");
        float area = 0;
        for (Node piece : pieces.nodes.first().getChildren()) {
            var triangles = triangles(piece);
            for (Vector3 point : triangles) { assertEquals(0, point.z, 1e-4f, "No cap rises out of the sheet"); }
            for (int at = 0; at < triangles.size(); at += 3) {
                area += new Vector3(triangles.get(at + 1)).sub(triangles.get(at))
                      .crs(new Vector3(triangles.get(at + 2)).sub(triangles.get(at))).len() / 2;
            }
        }
        assertEquals(1600, area, .5f, "The pieces keep the sheet's whole area, once");
    }

    @Test
    void boardTexturedPartsKeepTheTextureOfWhereTheyBroke() {
        // Cut near the placement, as a building on the board is: the frame lies at its hex, the UVs follow the board.
        var origin = new Vector3(100, 60, 0);
        var placed = new Matrix4().setToTranslation(120, 80, 0).rotate(Vector3.Z, 90);
        Model pieces = pieces(List.of(new MeepleShatter.Source(box(new Vector3(-10, -10, 0), new Vector3(10, 10, 4)),
              new Material("slab"), new Matrix4().setToTranslation(-100, -60, 0).mul(placed), 8)), origin,
              MeepleShatter.Planes.crumble(0, 2), concrete());
        assertEquals(origin, pieces.nodes.first().translation, "The pieces stand where the frame lies");
        int checked = 0;
        for (Node piece : pieces.nodes.first().getChildren()) {
            for (NodePart part : piece.parts) {
                if (!"slab".equals(part.material.id)) { continue; }
                var mesh = part.meshPart.mesh;
                int stride = mesh.getVertexSize() / Float.BYTES;
                int position = mesh.getVertexAttribute(VertexAttributes.Usage.Position).offset / Float.BYTES;
                int uv = mesh.getVertexAttribute(VertexAttributes.Usage.TextureCoordinates).offset / Float.BYTES;
                float[] vertices = new float[mesh.getNumVertices() * stride];
                mesh.getVertices(vertices);
                for (int vertex = part.meshPart.offset; vertex < part.meshPart.offset + part.meshPart.size; vertex++) {
                    int at = vertex * stride;
                    // As the vertex stage maps a board-textured material: board x and y, north up, 8 units a repeat.
                    assertEquals((vertices[at + position] + origin.x) / 8, vertices[at + uv], 1e-4f);
                    assertEquals(-(vertices[at + position + 1] + origin.y) / 8, vertices[at + uv + 1], 1e-4f);
                    checked++;
                }
            }
        }
        assertTrue(checked > 0);
    }

    @Test
    void aShellOpenBelowIsNotFilledAcrossTheRoomWithoutChords() {
        // A storey's shell of single sheets, roofed and open below, cut upright across its long walls.
        var shell = box(new Vector3(-20, -6, 0), new Vector3(20, 6, 18), false);
        int stride = LAYOUT.vertexSize / Float.BYTES;
        float[] records = new float[shell.size * stride];
        shell.mesh.getVertices(records);
        var walls = new Material("walls");
        for (boolean chords : new boolean[] { true, false }) {
            var cut = MeepleShatter.cut(List.of(walls), List.of(new FloatArray(records)), LAYOUT, Vector3.Z,
                  MeepleShatter.Planes.across(1, 90, false), 11, new MeepleShatter.Caps(new Material("concrete"), LAYOUT, 10, chords));
            assertEquals(2, cut.pieces(), "One break across the walls");
            assertTrue(cut.supports().isEmpty(), "A cut ahead of time works out no support points");
            int caps = 0;
            for (int piece = 0; piece < cut.pieces(); piece++) { caps += cut.triangles(piece, 1).size; }
            // With chords its section, open below, closes across the room; without, an open sheet stays open.
            assertEquals(chords, caps > 0, "Caps with chords " + chords + ": " + caps);
        }
    }

    private Model pieces(List<MeepleShatter.Source> sources, MeepleShatter.Planes planes, MeepleShatter.Caps caps) {
        return pieces(sources, Vector3.Zero, planes, caps);
    }

    private Model pieces(List<MeepleShatter.Source> sources, Vector3 origin, MeepleShatter.Planes planes,
          MeepleShatter.Caps caps) {
        var cut = MeepleShatter.cutter(sources, origin, Vector3.Z, planes, 11, caps).get();
        var model = MeepleShatter.model("rubble", cut);
        meshes.addAll(List.of(model.meshes.toArray(Mesh.class)));
        assertEquals(cut.pieces(), model.nodes.first().getChildCount());
        assertEquals(cut.pieces(), cut.supports().size());
        return model;
    }

    private static MeepleShatter.Caps concrete() {
        return new MeepleShatter.Caps(new Material("concrete"), LAYOUT, 10);
    }

    /** A closed box, its faces wound outward. */
    private MeshPart box(Vector3 min, Vector3 max) {
        return box(min, max, true);
    }

    /** A box, its faces wound outward; without its {@code floor}, open below as a model building's shell often is. */
    private MeshPart box(Vector3 min, Vector3 max, boolean floor) {
        List<float[]> corners = new ArrayList<>();
        List<Vector3> normals = new ArrayList<>();
        int[][] faces = { { 0, 2, 3, 1 }, { 4, 5, 7, 6 }, { 0, 1, 5, 4 }, { 2, 6, 7, 3 }, { 0, 4, 6, 2 }, { 1, 3, 7, 5 } };
        Vector3[] outward = { new Vector3(0, 0, -1), new Vector3(0, 0, 1), new Vector3(0, -1, 0), new Vector3(0, 1, 0),
              new Vector3(-1, 0, 0), new Vector3(1, 0, 0) };
        for (int face = floor ? 0 : 1; face < 6; face++) {
            int[] quad = faces[face];
            for (int corner : new int[] { quad[0], quad[1], quad[2], quad[0], quad[2], quad[3] }) {
                corners.add(new float[] { (corner & 1) == 0 ? min.x : max.x, (corner & 2) == 0 ? min.y : max.y,
                      (corner & 4) == 0 ? min.z : max.z });
                normals.add(outward[face]);
            }
        }
        return new MeshPart("box", mesh(corners.toArray(float[][]::new), normals.toArray(Vector3[]::new)), 0,
              corners.size(), GL20.GL_TRIANGLES);
    }

    private Mesh mesh(float[][] corners, Vector3 normal) {
        var normals = new Vector3[corners.length];
        java.util.Arrays.fill(normals, normal);
        return mesh(corners, normals);
    }

    private Mesh mesh(float[][] corners, Vector3[] normals) {
        int stride = LAYOUT.vertexSize / Float.BYTES;
        int position = LAYOUT.findByUsage(VertexAttributes.Usage.Position).offset / Float.BYTES;
        int normal = LAYOUT.findByUsage(VertexAttributes.Usage.Normal).offset / Float.BYTES;
        int colour = LAYOUT.findByUsage(VertexAttributes.Usage.ColorPacked).offset / Float.BYTES;
        float[] records = new float[corners.length * stride];
        for (int index = 0; index < corners.length; index++) {
            System.arraycopy(corners[index], 0, records, index * stride + position, 3);
            records[index * stride + normal] = normals[index].x;
            records[index * stride + normal + 1] = normals[index].y;
            records[index * stride + normal + 2] = normals[index].z;
            records[index * stride + colour] = com.badlogic.gdx.graphics.Color.WHITE_FLOAT_BITS;
        }
        return upload(records);
    }

    private Mesh upload(float[] records) {
        var mesh = new Mesh(true, records.length / (LAYOUT.vertexSize / Float.BYTES), 0, LAYOUT);
        mesh.setVertices(records);
        meshes.add(mesh);
        return mesh;
    }

    /** The piece's triangles' corners, three by three. */
    private static List<Vector3> triangles(Node piece) {
        List<Vector3> result = new ArrayList<>();
        for (NodePart part : piece.parts) {
            var mesh = part.meshPart.mesh;
            int stride = mesh.getVertexSize() / Float.BYTES;
            int position = mesh.getVertexAttribute(VertexAttributes.Usage.Position).offset / Float.BYTES;
            float[] vertices = new float[mesh.getNumVertices() * stride];
            mesh.getVertices(vertices);
            for (int vertex = part.meshPart.offset; vertex < part.meshPart.offset + part.meshPart.size; vertex++) {
                int at = vertex * stride + position;
                result.add(new Vector3(vertices[at], vertices[at + 1], vertices[at + 2]));
            }
        }
        return result;
    }

    /** The length of the sum of the triangles' area vectors: none for a closed surface, an opening's area for an open one. */
    private static float opening(List<Vector3> triangles) {
        return openingVector(triangles).len();
    }

    /** The sum of the triangles' area vectors: none for a closed surface, an opening's area facing into it. */
    private static Vector3 openingVector(List<Vector3> triangles) {
        var sum = new Vector3();
        for (int at = 0; at < triangles.size(); at += 3) {
            sum.add(new Vector3(triangles.get(at + 1)).sub(triangles.get(at))
                  .crs(new Vector3(triangles.get(at + 2)).sub(triangles.get(at))).scl(.5f));
        }
        return sum;
    }

    /** The signed volume the triangles enclose, measured from {@code origin}: the same from anywhere when closed. */
    private static float volume(List<Vector3> triangles, Vector3 origin) {
        double volume = 0;
        for (int at = 0; at < triangles.size(); at += 3) {
            var a = new Vector3(triangles.get(at)).sub(origin);
            var b = new Vector3(triangles.get(at + 1)).sub(origin);
            var c = new Vector3(triangles.get(at + 2)).sub(origin);
            volume += a.dot(new Vector3(b).crs(c)) / 6.0;
        }
        return (float) volume;
    }
}
