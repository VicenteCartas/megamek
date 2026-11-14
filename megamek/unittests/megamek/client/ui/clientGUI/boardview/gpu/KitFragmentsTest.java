/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import megamek.common.board.Coords;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A kit node broken for a fall: a wall's parts break together across its run, its concrete body into closed fragments
 * that keep its volume, its facade sheet left open; and after the pools' own compaction every triangle still
 * lies in the one fragment the shader finds for each of its vertices. Glass, floors and small fittings fall whole. A
 * cut is made when its fall is announced, held until its pile has sunk and kept after it only within a budget. GL is
 * mocked: meshes keep their vertices on the CPU.
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

    /** The part's range as GpuIndirectInstances places it in its pools (GpuTreeInstances#compact), as a mesh. */
    private Mesh compacted(MeshPart part) {
        var packed = new FloatArray();
        var indices = new ShortArray();
        GpuTreeInstances.compact(part, packed, indices);
        var mesh = new Mesh(Mesh.VertexDataType.VertexArray, true, packed.size / (part.mesh.getVertexSize() / Float.BYTES),
              indices.size, part.mesh.getVertexAttributes());
        meshes.add(mesh);
        mesh.setVertices(packed.items, 0, packed.size);
        mesh.setIndices(indices.items, 0, indices.size);
        return mesh;
    }

    @AfterEach
    void restoreGl() {
        meshes.forEach(Mesh::dispose);
        Gdx.app = application;
        Gdx.gl = gl;
        Gdx.gl20 = gl20;
    }

    @Test
    void aSharedMeshRangePreservesFirstUseOrderWithOrWithoutRepacking() {
        var source = new Mesh(Mesh.VertexDataType.VertexArray, true, 6, 9, VertexAttribute.Position());
        meshes.add(source);
        source.setVertices(new float[] { 0, 0, 0, 1, 10, 100, 2, 20, 200, 3, 30, 300, 4, 40, 400, 5, 50, 500 });
        for (boolean ordered : new boolean[] { false, true }) {
            source.setIndices(ordered ? new short[] { 0, 5, 0, 2, 3, 2, 4, 3, 4 }
                  : new short[] { 0, 5, 0, 4, 2, 4, 1, 2, 1 });
            var packed = compacted(new MeshPart("range", source, 3, 6, GL20.GL_TRIANGLES));
            assertEquals(3, packed.getNumVertices());
            assertArrayEquals(ordered ? new float[] { 2, 20, 200, 3, 30, 300, 4, 40, 400 }
                  : new float[] { 4, 40, 400, 2, 20, 200, 1, 10, 100 }, packed.getVertices(new float[9]));
            var indices = new short[6];
            packed.getIndices(indices);
            assertArrayEquals(new short[] { 0, 1, 0, 2, 1, 2 }, indices);
        }
    }

    @Test
    void aWallBreaksAcrossItsRunIntoClosedFragmentsEachVertexFindsInItsBatch() {
        var kit = new Kit();
        // A wall a storey high, as a kit lays one: six bays of concrete 4 long and 2 thick, its facade a sheet on its
        // face, a window and a floor cap.
        List<Vector3[]> bays = new ArrayList<>();
        for (int bay = 0; bay < 6; bay++) { bays.add(new Vector3[] { new Vector3(-12 + 4 * bay, -1, 0), new Vector3(-8 + 4 * bay, 1, 18) }); }
        ModelNode wall = kit.node("wall", List.of(kit.boxes("wall/body", BODY, bays),
              kit.sheet("wall/facade", "facade", 1.05f), kit.sheet("wall/glass", "glass", 1.1f),
              kit.box("wall/floor", "rooms#floor", new Vector3(-12, -8, 0), new Vector3(12, -1, .2f))));
        ModelNode light = kit.node("light", List.of(kit.box("light/body", BODY, new Vector3(-1, -1, 18), new Vector3(1, 1, 20))));
        var level = new KitFragments.Level(kit.data(), Set.of("glass", "rooms#floor"));
        var fragments = KitFragments.cut(level, wall);
        fragments.install();

        assertNull(fragments.part(part("wall/glass")), "Glass shatters to nothing");
        assertNull(fragments.part(part("wall/floor")), "A floor cap is the interior's");
        assertNull(fragments.part(part("light/body")), "Another node's part is not in its cut");
        assertNull(KitFragments.cut(level, light).part(part("light/body")), "A small fitting falls whole");
        var body = fragments.part(part("wall/body"));
        var facade = fragments.part(part("wall/facade"));
        assertNotNull(body);
        assertNotNull(facade);
        float[] table = body.fragments().table();
        int count = table.length / 8;
        assertTrue(count >= 3 && count <= KitFragments.KIT_FRAGMENTS, "It breaks into a few fragments: " + count);
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
            var copy = compacted(part.range());
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

    /**
     * A cut is made when a fall is announced, once for every hex that asks, and held by the hex that falls in it. After
     * its pile has sunk it is kept for the next fall while the budget has room: a new cut frees the least recently used
     * one no hex holds, and is dropped where those held fill the budget. One whose fall never came is not kept.
     */
    @Test
    void aCutKeptOnDiskIsReadBackAsItWasMade(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws Exception {
        var kit = new Kit();
        List<Vector3[]> bays = new ArrayList<>();
        for (int bay = 0; bay < 6; bay++) { bays.add(new Vector3[] { new Vector3(-12 + 4 * bay, -1, 0), new Vector3(-8 + 4 * bay, 1, 18) }); }
        ModelNode wall = kit.node("wall", List.of(kit.boxes("wall/body", BODY, bays), kit.sheet("wall/facade", "facade", 1.05f)));
        var level = new KitFragments.Level(kit.data(), Set.of());
        KitFragments.store(folder);
        try {
            var made = KitFragments.cut(level, wall);
            try (var kept = java.nio.file.Files.list(folder)) {
                assertEquals(1, kept.filter(file -> file.toString().endsWith(".cut")).count(), "The node's cut is kept, once");
            }
            var read = KitFragments.cut(level, wall);
            made.install();
            read.install();
            for (String id : List.of("wall/body", "wall/facade")) {
                var first = made.part(part(id));
                var again = read.part(part(id));
                assertNotNull(first, id + " breaks");
                assertNotNull(again, id + " is read back");
                assertArrayEquals(first.fragments().table(), again.fragments().table(), id + ": the same fragments");
                assertEquals(first.range().offset, again.range().offset, id + ": the same range");
                assertEquals(first.range().size, again.range().size, id + ": the same range");
            }
            assertEquals(made.bytes(), read.bytes(), "The same meshes");
            made.dispose();
            read.dispose();
        } finally {
            KitFragments.store(null);
        }
    }

    @Test
    void aCutIsMadeForAnAnnouncedFallAndKeptAfterItOnlyWithinTheBudget() throws Exception {
        var kit = new Kit();
        List<Vector3[]> bays = new ArrayList<>();
        for (int bay = 0; bay < 6; bay++) { bays.add(new Vector3[] { new Vector3(-12 + 4 * bay, -1, 0), new Vector3(-8 + 4 * bay, 1, 18) }); }
        kit.node("wall", List.of(kit.boxes("wall/body", BODY, bays)));
        kit.node("other", List.of(kit.boxes("other/body", BODY, bays)));
        var level = new KitFragments.Level(kit.data(), Set.of());
        // A placement's copies of the nodes, as a building's instance draws them.
        var wall = drawn("wall/body");
        var other = drawn("other/body");
        long bytes = bytes(level, wall), otherBytes = bytes(level, other);
        // Room for either cut, which counts twice (its meshes and their copy in the pools), not for both.
        var cuts = new KitFragments.Cuts(2 * Math.max(bytes, otherBytes) + Math.min(bytes, otherBytes));
        Coords first = new Coords(1, 1), second = new Coords(2, 1), third = new Coords(3, 1);
        assertNull(cuts.take(level.source(wall), first, true), "Nothing is cut before a fall is announced");

        cuts.want(level, List.of(wall), first);
        cuts.want(level, List.of(wall), second);
        assertEquals(1, cuts.size(), "Every hex placing the node shares its cut");
        finish(cuts, first);
        assertEquals(bytes, cuts.bytes(), "Its fragments exist once cut");
        assertNull(cuts.take(level.source(wall), third, true), "A hex that did not ask falls whole");
        assertNotNull(cuts.take(level.source(wall), first, true).part(part("wall/body")), "The hex falls in its fragments");
        cuts.fell(first);
        // The second hex's fall never comes: the playback has played out, and the first one's pile sinks.
        cuts.forget(Set.of(first));
        cuts.retain(Set.of());
        assertEquals(bytes, cuts.bytes(), "No hex holds it: it is kept for the next fall");

        cuts.want(level, List.of(wall), second);
        assertTrue(!cuts.pending(List.of(second)), "The next fall of the node finds its cut made");
        assertNotNull(cuts.take(level.source(wall), second, true), "and falls in it at once");
        cuts.fell(second);
        cuts.want(level, List.of(other), third);
        finish(cuts, third);
        assertNull(cuts.take(level.source(other), third, true), "The held cut fills the budget: the new one is dropped");
        assertEquals(bytes, cuts.bytes());
        cuts.fell(third);

        cuts.retain(Set.of());
        cuts.want(level, List.of(other), third);
        finish(cuts, third);
        assertNotNull(cuts.take(level.source(other), third, true), "With no hex holding the old cut, the new one takes its room");
        assertEquals(otherBytes, cuts.bytes(), "The least recently used cut was freed");
        assertEquals(1, cuts.size());
        cuts.fell(third);

        cuts.want(level, List.of(wall), first);
        cuts.clear();
        assertEquals(1, cuts.size(), "A cut whose fall never came is not kept");
        assertEquals(0, cuts.late(), "No fall found its cut still under way");
        cuts.dispose();
        assertEquals(0, cuts.bytes());
    }

    /** The native bytes of the node's fragments. */
    private static long bytes(KitFragments.Level level, com.badlogic.gdx.graphics.g3d.model.Node drawn) {
        var cut = KitFragments.cut(level.source(drawn));
        cut.install();
        long bytes = cut.bytes();
        cut.dispose();
        assertTrue(bytes > 0);
        return bytes;
    }

    private static com.badlogic.gdx.graphics.g3d.model.Node drawn(String part) {
        var node = new com.badlogic.gdx.graphics.g3d.model.Node();
        node.parts.add(new com.badlogic.gdx.graphics.g3d.model.NodePart(part(part), new com.badlogic.gdx.graphics.g3d.Material(BODY)));
        return node;
    }

    /** Waits for the cuts the hex asked for and installs them, as a frame does. */
    private static void finish(KitFragments.Cuts cuts, Coords hex) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (cuts.pending(List.of(hex))) {
            assertTrue(System.nanoTime() < deadline, "The cut finishes");
            Thread.sleep(2);
            cuts.install();
        }
    }

    /**
     * A tree an attack breaks (KitFragments#splitLater): its bark and leaves split alike into a few fragments, each leaf
     * card whole in one and every vertex in its fragment's box as the shader finds it; its trunk, one connected piece of
     * many triangles, breaks between them.
     */
    @Test
    void aTreeSplitsIntoFragmentsOfWholeLeafCards() {
        var records = new FloatArray();
        var indices = new ShortArray();
        // A trunk 11 high: rings of four corners joined by quads.
        for (int ring = 0; ring < 12; ring++) {
            for (int corner = 0; corner < 4; corner++) {
                double angle = corner * Math.PI / 2;
                records.addAll((float) (.2 * Math.cos(angle)), (float) (.2 * Math.sin(angle)), ring, 1, 0, 0);
            }
        }
        for (int ring = 0; ring < 11; ring++) {
            for (int corner = 0; corner < 4; corner++) {
                short a = (short) (4 * ring + corner), b = (short) (4 * ring + (corner + 1) % 4);
                indices.addAll(a, b, (short) (b + 4), a, (short) (b + 4), (short) (a + 4));
            }
        }
        int barkIndices = indices.size;
        // A crown of 40 leaf cards, each its own four corners.
        List<Vector3> cards = new ArrayList<>();
        for (int card = 0; card < 40; card++) {
            var centre = new Vector3((float) (3 * Math.cos(card * .8)), (float) (3 * Math.sin(card * .8)), 8 + card % 5);
            cards.add(centre);
            short first = (short) (records.size / 6);
            for (float[] offset : new float[][] { { -.3f, -.3f }, { .3f, -.3f }, { .3f, .3f }, { -.3f, .3f } }) {
                records.addAll(centre.x + offset[0], centre.y, centre.z + offset[1], 0, 1, 0);
            }
            indices.addAll(first, (short) (first + 1), (short) (first + 2), first, (short) (first + 2), (short) (first + 3));
        }
        var mesh = new Mesh(Mesh.VertexDataType.VertexArray, true, records.size / 6, indices.size,
              VertexAttribute.Position(), VertexAttribute.Normal());
        meshes.add(mesh);
        mesh.setVertices(records.items, 0, records.size);
        mesh.setIndices(indices.items, 0, indices.size);
        var model = new com.badlogic.gdx.graphics.g3d.Model();
        model.meshes.add(mesh);
        var node = new com.badlogic.gdx.graphics.g3d.model.Node();
        node.id = "tree";
        node.parts.add(new com.badlogic.gdx.graphics.g3d.model.NodePart(
              new MeshPart("tree/bark", mesh, 0, barkIndices, GL20.GL_TRIANGLES), new com.badlogic.gdx.graphics.g3d.Material("bark")));
        node.parts.add(new com.badlogic.gdx.graphics.g3d.model.NodePart(
              new MeshPart("tree/leaves", mesh, barkIndices, indices.size - barkIndices, GL20.GL_TRIANGLES),
              new com.badlogic.gdx.graphics.g3d.Material("leaves")));
        model.nodes.add(node);

        var fragments = KitFragments.splitLater(model).join();
        fragments.install();
        var bark = fragments.part(part("tree/bark"));
        var leaves = fragments.part(part("tree/leaves"));
        assertNotNull(bark);
        assertNotNull(leaves);
        float[] table = leaves.fragments().table();
        int count = table.length / 8;
        assertTrue(count >= 4 && count <= KitFragments.KIT_FRAGMENTS, "It breaks into a few fragments: " + count);
        for (int fragment = 0; fragment < count; fragment++) {
            for (int value : new int[] { 0, 1, 2, 4, 5, 6 }) {
                assertEquals(table[8 * fragment + value], bark.fragments().table()[8 * fragment + value],
                      "Fragment " + fragment + " moves as one box in bark and leaves");
            }
        }
        Set<Integer> trunk = new java.util.HashSet<>();
        for (var part : List.of(bark, leaves)) {
            var copy = compacted(part.range());
            float[] own = part.fragments().table();
            var corners = corners(copy);
            java.util.Map<Vector3, Integer> cardFragment = new java.util.HashMap<>();
            for (int corner = 0; corner < corners.size(); corner += 3) {
                int fragment = fragment(own, corners.get(corner).index());
                var middle = new Vector3();
                for (int at = corner; at < corner + 3; at++) {
                    var vertex = corners.get(at);
                    assertEquals(fragment, fragment(own, vertex.index()), "A triangle's corners lie in one fragment");
                    for (int axis = 0; axis < 3; axis++) {
                        float centre = own[8 * fragment + axis], half = own[8 * fragment + 4 + axis];
                        float along = axis == 0 ? vertex.point().x : axis == 1 ? vertex.point().y : vertex.point().z;
                        assertTrue(Math.abs(along - centre) <= half + 1e-3f,
                              "Vertex " + vertex.index() + " lies in fragment " + fragment + "'s box");
                    }
                    middle.add(vertex.point());
                }
                middle.scl(1 / 3f);
                if (part == bark) {
                    trunk.add(fragment);
                    continue;
                }
                var card = cards.stream().min(java.util.Comparator.comparing(centre -> centre.dst2(middle))).orElseThrow();
                Integer other = cardFragment.putIfAbsent(card, fragment);
                assertTrue(other == null || other == fragment, "The leaf card at " + card + " stays whole");
            }
        }
        assertTrue(trunk.size() >= 2, "The trunk breaks: " + trunk);
        fragments.dispose();
    }

    @Test
    void aStructuresOwnModelBreaksAsAKitNodeDoesItsGlassWithIt() {
        // A wall of six bays 4 by 2 by 18 and a pane in front of it, two parts of one mesh named alike, as a model built
        // at runtime may name them; the pane is glass.
        var records = new FloatArray();
        var indices = new ShortArray();
        for (int bay = 0; bay < 6; bay++) { box(records, indices, new Vector3(-12 + 4 * bay, -1, 0), new Vector3(-8 + 4 * bay, 1, 18)); }
        int body = indices.size;
        box(records, indices, new Vector3(-12, 1.2f, 2), new Vector3(12, 1.4f, 16));
        var mesh = new Mesh(Mesh.VertexDataType.VertexArray, true, records.size / 6, indices.size,
              VertexAttribute.Position(), VertexAttribute.Normal());
        meshes.add(mesh);
        mesh.setVertices(records.items, 0, records.size);
        mesh.setIndices(indices.items, 0, indices.size);
        var model = new com.badlogic.gdx.graphics.g3d.Model();
        model.meshes.add(mesh);
        var node = new com.badlogic.gdx.graphics.g3d.model.Node();
        node.id = "shell";
        var wall = new MeshPart("part", mesh, 0, body, GL20.GL_TRIANGLES);
        var pane = new MeshPart("part", mesh, body, indices.size - body, GL20.GL_TRIANGLES);
        node.parts.add(new com.badlogic.gdx.graphics.g3d.model.NodePart(wall, new com.badlogic.gdx.graphics.g3d.Material("stone")));
        node.parts.add(new com.badlogic.gdx.graphics.g3d.model.NodePart(pane, new com.badlogic.gdx.graphics.g3d.Material("glass")));
        model.nodes.add(node);

        var fragments = KitFragments.shell(model, KitFragments.SIDE, Set.of("glass"), KitFragments.FINE).get();
        fragments.install();
        // Its coarse grade, for a fall seen from farther off, breaks it into fewer, larger pieces (a third of the side
        // here, so that both grades break this small wall).
        var fine = KitFragments.shell(model, KitFragments.SIDE / 3, Set.of("glass"), KitFragments.FINE).get();
        var coarse = KitFragments.shell(model, KitFragments.SIDE / 3, Set.of("glass"), KitFragments.COARSE).get();
        fine.install();
        coarse.install();
        assertNotNull(coarse.part(wall), "The coarse grade breaks it too");
        assertTrue(coarse.part(wall).fragments().table().length < fine.part(wall).fragments().table().length,
              "Fewer pieces in the coarse grade");
        fine.dispose();
        coarse.dispose();
        var stone = fragments.part(wall);
        var glass = fragments.part(pane);
        assertNotNull(stone, "The wall breaks");
        assertNotNull(glass, "Its glass breaks with it, found by its range though both parts have one name");
        float[] table = stone.fragments().table();
        int count = table.length / 8;
        assertTrue(count >= 2 && count <= KitFragments.SHELL_FRAGMENTS, "It breaks: " + count);
        for (int fragment = 0; fragment < count; fragment++) {
            // Cells of a lattice: no fragment a strip much longer than it is wide (its thickness aside).
            float[] sides = { table[8 * fragment + 4], table[8 * fragment + 5], table[8 * fragment + 6] };
            java.util.Arrays.sort(sides);
            assertTrue(sides[2] <= 3 * sides[1], "Fragment " + fragment + " is no strip: " + java.util.Arrays.toString(sides));
        }
        for (int at = 0; at < 8 * count; at++) {
            // Each part's own first vertices aside, a fragment's box is one for wall and glass.
            if (at % 8 != 3) { assertEquals(stone.fragments().table()[at], glass.fragments().table()[at], "Wall and glass break as one"); }
        }
        fragments.dispose();
    }

    /** A closed box from {@code min} to {@code max}: four corners a face, positions and normals. */
    private static void box(FloatArray records, ShortArray indices, Vector3 min, Vector3 max) {
        float[][] normals = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 } };
        for (float[] n : normals) {
            var normal = new Vector3(n[0], n[1], n[2]);
            var u = new Vector3(n[1] != 0 || n[2] != 0 ? 1 : 0, n[0] != 0 ? 1 : 0, 0);
            var v = new Vector3(normal).crs(u);
            short first = (short) (records.size / 6);
            var middle = new Vector3(min).add(max).scl(.5f);
            var half = new Vector3(max).sub(min).scl(.5f);
            for (float[] at : new float[][] { { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } }) {
                var p = new Vector3(normal).add(new Vector3(u).scl(at[0])).add(new Vector3(v).scl(at[1])).scl(half).add(middle);
                records.addAll(p.x, p.y, p.z, normal.x, normal.y, normal.z);
            }
            indices.addAll(first, (short) (first + 1), (short) (first + 2), first, (short) (first + 2), (short) (first + 3));
        }
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

        ModelNode node(String id, List<Part> drawn) {
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
            return node;
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
