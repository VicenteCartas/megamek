/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.badlogic.gdx.graphics.g3d.model.data.ModelMaterial;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMesh;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMeshPart;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNode;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNodePart;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.FloatArray;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Entity;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class ConnectedKitTest {
    private record Box(String node, float x0, float y0, float z0, float x1, float y1, float z1) { }

    /** A core post, a north arm to the edge midpoint and a north cap off to the side, each in every module. */
    private static List<Box> symmetric() {
        List<Box> result = new ArrayList<>();
        for (String role : List.of("base0", "floor0", "roof0")) {
            float top = role.equals("roof0") ? 2 : 18;
            result.add(new Box("kit-lod0-" + role, -5, -5, 0, 5, 5, top));
            result.add(new Box("kit-lod0-" + role + "-link-n", -2, 5, 0, 2, 36, top));
            result.add(new Box("kit-lod0-" + role + "-end-n", 10, 0, 0, 12, 4, top));
        }
        return result;
    }

    @Test
    void theBoardHexIsRegularSoATurnedNorthPieceMeetsEveryEdge() {
        for (int edge = 0; edge < 6; edge++) {
            Vector3 midpoint = BoardGeometry.modelCorner(edge).add(BoardGeometry.modelCorner(edge + 1)).scl(.5f);
            int direction = BoardGeometry.edgeDirection(edge);
            double angle = Math.toRadians(90 - 60 * direction);
            // The width is the regular one to a 32nd (HexTile.MODEL_WIDTH): within 0.005 of regular.
            assertEquals(36 * Math.cos(angle), midpoint.x, .01, "edge " + edge);
            assertEquals(36 * Math.sin(angle), midpoint.y, .01, "edge " + edge);
        }
    }

    @Test
    void symmetricArmsTurnOntoEveryConnectedEdgeAndNowhereElse() {
        var kit = ConnectedKit.load(List.of(kit(symmetric())));
        assertTrue(kit.symmetric());
        var model = ConnectedKit.model(kit, 0, 1, new int[] { 0, 1, 0, 1, 0, 0 }, 0, 0, "test");
        for (int direction = 0; direction < 6; direction++) {
            float far = farthest(model, direction);
            if (direction == 1 || direction == 3) {
                assertEquals(36, far, 1e-4, "The turned arm ends exactly on edge " + direction);
            } else {
                assertTrue(far < 20, "No arm towards unconnected edge " + direction + ": " + far);
            }
        }
        // The whole kit turns: its north is board south-east here.
        var turned = ConnectedKit.model(kit, 0, 1, new int[] { 0, 0, 1, 0, 0, 0 }, 2, 0, "turned");
        assertEquals(36, farthest(turned, 2), 1e-4);
        assertTrue(farthest(turned, 0) < 20);
    }

    @Test
    void piecesStackThroughTheLevelsWhereTheirTagsHold() {
        var kit = ConnectedKit.load(List.of(kit(symmetric())));
        // Three levels high, connected north for the lowest level only (a lower neighbour).
        var model = ConnectedKit.model(kit, 0, 3, new int[] { 1, 0, 0, 0, 0, 0 }, 0, 0, "stacked");
        float[] arm = heights(model, (x, y) -> Math.abs(x) <= 2.01f && y > 20);
        assertEquals(0, arm[0], 1e-4);
        assertEquals(20, arm[1], 1e-4, "The arm's own roof caps it where the connection stops");
        float[] cap = heights(model, (x, y) -> x >= 9.99f && x <= 12.01f && y >= -.01f && y <= 4.01f);
        assertEquals(18, cap[0], 1e-4, "The closed side starts with a floor above the lower neighbour");
        assertEquals(56, cap[1], 1e-4, "and ends with its roof on top");
        float[] core = heights(model, (x, y) -> Math.abs(x) <= 5.01f && Math.abs(y) <= 5.01f);
        assertEquals(56, core[1], 1e-4);
    }

    @Test
    void groundPiecesStandInTheBaseAloneUnderATallStructure() {
        // A fuel tank's pipes: only the base carries tagged pieces.
        var kit = ConnectedKit.load(List.of(kit(symmetric().stream()
              .filter(box -> box.node().startsWith("kit-lod0-base0") || !box.node().contains("-n")).toList())));
        var model = ConnectedKit.model(kit, 0, 3, new int[] { 3, 0, 0, 0, 0, 0 }, 0, 0, "pipes");
        assertEquals(36, farthest(model, 0), 1e-4);
        float[] arm = heights(model, (x, y) -> Math.abs(x) <= 2.01f && y > 20);
        assertEquals(0, arm[0], 1e-4);
        assertEquals(18, arm[1], 1e-4, "No floor or roof piece follows the arm up the connected side");
    }

    /** {@link #symmetric} with whole-hex pieces: a block alone, and a band towards a single link. */
    private static List<Box> wholes() {
        List<Box> result = new ArrayList<>(symmetric());
        for (String role : List.of("base0", "floor0", "roof0")) {
            float top = role.equals("roof0") ? 2 : 18;
            result.add(new Box("kit-lod0-" + role + LONE, -20, -30, 0, 20, 30, top));
            result.add(new Box("kit-lod0-" + role + BAND, -20, -28, 0, 20, 36, top));
        }
        return result;
    }

    private static final String LONE = "-end-n-end-ne-end-se-end-s-end-sw-end-nw";
    private static final String BAND = "-link-n-end-ne-end-se-end-s-end-sw-end-nw";

    @Test
    void aWholeHexPieceStandsAloneForExactlyItsConnectionsWhileTheyHold() {
        var kit = ConnectedKit.load(List.of(kit(wholes())));
        assertTrue(kit.symmetric());
        assertEquals(Set.of("kit-lod0-base0" + LONE + "@0/base0-at-0", "kit-lod0-roof0" + LONE + "@0/roof0-at-1"),
              prefixes(kit, 1, new int[6]), "Alone: the block once, no core or caps");
        var band = ConnectedKit.model(kit, 0, 1, new int[] { 0, 0, 1, 0, 0, 0 }, 0, 0, "band");
        assertEquals(36, farthest(band, 2), 1e-4, "One link: the band turned onto it");
        assertEquals(Set.of("kit-lod0-base0" + BAND + "@2/base0-at-0", "kit-lod0-roof0" + BAND + "@2/roof0-at-1"),
              prefixes(kit, 1, new int[] { 0, 0, 1, 0, 0, 0 }));
        assertTrue(prefixes(kit, 1, new int[] { 1, 0, 0, 1, 0, 0 }).stream().noneMatch(node -> node.contains("-nw@")),
              "Two links: the corner pieces only");
        // Linked north on the lower level only: the band below, then the core and its caps (the lone block above would
        // overhang the band).
        var stacked = prefixes(kit, 2, new int[] { 1, 0, 0, 0, 0, 0 });
        assertTrue(stacked.contains("kit-lod0-base0" + BAND + "@0/base0-at-0"));
        assertTrue(stacked.contains("kit-lod0-roof0" + BAND + "@0/roof0-at-1"), "The band's roof where it stops");
        assertTrue(stacked.contains("kit-lod0-floor0@0/floor0-at-1"));
        assertTrue(stacked.stream().noneMatch(node -> node.contains(LONE)));
        assertTrue(stacked.stream().noneMatch(node -> node.endsWith("-at-0") && !node.contains(BAND)));
    }

    /** Corner pieces between north and north-east in every state, and the linkless one's bend roof. */
    private static List<Box> corners() {
        List<Box> result = new ArrayList<>();
        for (String role : List.of("base0", "floor0", "roof0")) {
            for (String tags : List.of("-end-n-end-ne", "-link-n-end-ne", "-end-n-link-ne", "-link-n-link-ne")) {
                result.add(new Box("kit-lod0-" + role + tags, 0, 0, 0, 20, 30, role.equals("roof0") ? 2 : 18));
            }
        }
        result.add(new Box("kit-lod0-roof0-end-n-end-ne-bend", 0, 0, 0, 20, 30, 2));
        return result;
    }

    @Test
    void aLinklessCornersBendRoofStandsOnlyBehindASixtyDegreeBend() {
        var kit = ConnectedKit.load(List.of(kit(corners())));
        // Connected south and south-west only: the corner opposite them takes its bend roof over its plain storeys.
        var bend = prefixes(kit, 1, new int[] { 0, 0, 0, 1, 1, 0 });
        assertTrue(bend.contains("kit-lod0-roof0-end-n-end-ne-bend@0/roof0-at-1"));
        assertFalse(bend.contains("kit-lod0-roof0-end-n-end-ne@0/roof0-at-1"));
        assertTrue(bend.contains("kit-lod0-base0-end-n-end-ne@0/base0-at-0"));
        assertTrue(bend.contains("kit-lod0-roof0-end-n-end-ne@1/roof0-at-1"), "The other linkless corners stay plain");
        assertTrue(prefixes(kit, 1, new int[] { 0, 0, 1, 1, 1, 0 }).stream().noneMatch(node -> node.contains("-bend")),
              "A third connection is no bend");
    }

    private static Set<String> prefixes(ConnectedKit.Kit kit, int height, int[] reach) {
        return ConnectedKit.placements(kit, 0, height, reach, 0, 0).stream().map(ConnectedKit.Placement::prefix)
              .map(prefix -> prefix.substring(0, prefix.length() - 1)).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void theHexsExitsConnectAndOnlyAStructureOfItsKindThatConnectsBackLowersThem() {
        Hex[] hexes = new Hex[7 * 7];
        for (int i = 0; i < hexes.length; i++) { hexes[i] = new Hex(0); }
        Coords tall = new Coords(3, 3);
        // Exits north to a lower tank, north-east to open ground, south to a building that connects back.
        structure(hexes, tall, Terrains.FUEL_TANK, (1 << 0) | (1 << 1) | (1 << 3), 3);
        structure(hexes, tall.translated(0), Terrains.FUEL_TANK, 1 << 3, 1);
        structure(hexes, tall.translated(3), Terrains.BUILDING, 1, 1);
        var scene = new BoardScene(0, 7, 7, BoardTiles.captureAll(new Board(7, 7, hexes)), List.of(), List.of(),
              Entity.NONE, "", List.of());
        assertArrayEquals(new int[] { 1, 3, 0, 3, 0, 0 }, ConnectedKit.reach(scene, scene.tile(tall), 3));
    }

    @Test
    void aBuildingStandsOpenWhereItConnectsToNothingAsAfterACollapse() {
        Hex[] hexes = new Hex[7 * 7];
        for (int i = 0; i < hexes.length; i++) { hexes[i] = new Hex(0); }
        // A row of three running south, whose north end still connects north where nothing stands.
        Coords end = new Coords(3, 2), next = end.translated(3), last = next.translated(3);
        structure(hexes, end, Terrains.BUILDING, (1 << 0) | (1 << 3), 2);
        structure(hexes, next, Terrains.BUILDING, (1 << 0) | (1 << 3), 2);
        structure(hexes, last, Terrains.BUILDING, 1, 2);
        var scene = new BoardScene(0, 7, 7, BoardTiles.captureAll(new Board(7, 7, hexes)), List.of(), List.of(),
              Entity.NONE, "", List.of());
        assertTrue(ConnectedKit.open(scene, scene.tile(end), 2), "Its north end is open");
        assertTrue(ConnectedKit.open(scene, scene.tile(next), 2), "The inside of the hex it joins shows through that end");
        assertFalse(ConnectedKit.open(scene, scene.tile(last), 2), "Two hexes in, the inside is out of sight");
        hexes[end.getY() * 7 + end.getX()].getTerrain(Terrains.BUILDING).setExits(1 << 3);
        scene = new BoardScene(0, 7, 7, BoardTiles.captureAll(new Board(7, 7, hexes)), List.of(), List.of(),
              Entity.NONE, "", List.of());
        assertFalse(ConnectedKit.open(scene, scene.tile(end), 2), "Closed at its end, the row is closed");
        assertFalse(ConnectedKit.open(scene, scene.tile(next), 2));
    }

    @Test
    void wallsOfTwoHexesMeetingAtACornerTurnItTogetherAndOnlyThere() {
        // A hex linked north and north-east whose two neighbours do not link each other: a 60-degree notch.
        Hex[] hexes = new Hex[7 * 7];
        for (int i = 0; i < hexes.length; i++) { hexes[i] = new Hex(0); }
        Coords centre = new Coords(3, 3), north = centre.translated(0), northEast = centre.translated(1);
        structure(hexes, centre, Terrains.BUILDING, (1 << 0) | (1 << 1), 2);
        structure(hexes, north, Terrains.BUILDING, 1 << 3, 2);
        structure(hexes, northEast, Terrains.BUILDING, 1 << 4, 2);
        var scene = new BoardScene(0, 7, 7, BoardTiles.captureAll(new Board(7, 7, hexes)), List.of(), List.of(),
              Entity.NONE, "", List.of());
        // Each neighbour's wall, square to its link, ends at the shared corner: its corner 2 (north's), 4 (north-east's).
        assertArrayEquals(new int[] { 0, 0, bit(KitOutline.Junction.S1_NOTCH), 0, 0, 0 }, levelOf(junctions(scene, north, 2), 1));
        assertArrayEquals(new int[] { 0, 0, 0, 0, bit(KitOutline.Junction.S0_NOTCH), 0 }, levelOf(junctions(scene, northEast, 2), 0));
        assertArrayEquals(new int[12], junctions(scene, centre, 2), "Linked both ways, its corner holds no wall");
        // Linked to each other too, the three close the corner inside the building.
        hexes[north.getY() * 7 + north.getX()].getTerrain(Terrains.BUILDING).setExits((1 << 3) | (1 << 2));
        hexes[northEast.getY() * 7 + northEast.getX()].getTerrain(Terrains.BUILDING).setExits((1 << 4) | (1 << 5));
        scene = new BoardScene(0, 7, 7, BoardTiles.captureAll(new Board(7, 7, hexes)), List.of(), List.of(),
              Entity.NONE, "", List.of());
        assertArrayEquals(new int[12], junctions(scene, north, 2));
        assertArrayEquals(new int[12], junctions(scene, northEast, 2));
    }

    @Test
    void wallsClosingLinksAboveAShorterNeighbourMeetTheNextHexsAcrossItsCorner() {
        // A 3-level hex linked north (1 level) and north-west (2), which link each other: above the north one, the
        // walls closing the links to it, the hex's and the north-west one's, meet at their shared corner.
        Hex[] hexes = new Hex[7 * 7];
        for (int i = 0; i < hexes.length; i++) { hexes[i] = new Hex(0); }
        Coords centre = new Coords(3, 3), north = centre.translated(0), northWest = centre.translated(5);
        structure(hexes, centre, Terrains.BUILDING, (1 << 0) | (1 << 5), 3);
        structure(hexes, north, Terrains.BUILDING, (1 << 3) | (1 << 4), 1);
        structure(hexes, northWest, Terrains.BUILDING, (1 << 2) | (1 << 1), 2);
        var scene = new BoardScene(0, 7, 7, BoardTiles.captureAll(new Board(7, 7, hexes)), List.of(), List.of(),
              Entity.NONE, "", List.of());
        int[] centres = junctions(scene, centre, 3), northWests = junctions(scene, northWest, 2);
        assertArrayEquals(new int[6], levelOf(centres, 0), "All three stand and link at the ground");
        assertEquals(bit(KitOutline.Junction.C1_CAP), levelOf(centres, 1)[5]);
        assertEquals(bit(KitOutline.Junction.C0_CAP), levelOf(northWests, 1)[1]);
        // Above the north-west one too, the hex's own two closing walls meet within its corner piece.
        assertArrayEquals(new int[6], levelOf(centres, 2));
    }

    private static int[] junctions(BoardScene scene, Coords at, int height) {
        var tile = scene.tile(at);
        return ConnectedKit.junctions(height, ConnectedKit.reach(scene, tile, height), ConnectedKit.corners(scene, tile), true);
    }

    private static int[] levelOf(int[] junctions, int level) { return java.util.Arrays.copyOfRange(junctions, level * 6, level * 6 + 6); }

    private static int bit(KitOutline.Junction junction) { return 1 << junction.ordinal(); }

    private static void structure(Hex[] hexes, Coords coords, int terrain, int exits, int height) {
        Hex hex = hexes[coords.getY() * 7 + coords.getX()];
        hex.addTerrain(new Terrain(terrain, 1, true, exits));
        boolean tank = terrain == Terrains.FUEL_TANK;
        hex.addTerrain(new Terrain(tank ? Terrains.FUEL_TANK_ELEV : Terrains.BLDG_ELEV, height));
        hex.addTerrain(new Terrain(tank ? Terrains.FUEL_TANK_CF : Terrains.BLDG_CF, 40));
        if (tank) { hex.addTerrain(new Terrain(Terrains.FUEL_TANK_MAGN, 10)); }
    }

    @Test
    void rejectsAmbiguousOrIncompleteKits() {
        List<List<Box>> kits = new ArrayList<>();
        kits.add(renamed(symmetric(), "kit-lod0-base0", "kit-lod0-base0.001"));
        kits.add(renamed(symmetric(), "kit-lod0-base0-link-n", "kit-lod0-base0-link-north"));
        // North and south-east arms only: neither symmetric nor complete.
        kits.add(renamed(renamed(renamed(symmetric(), "kit-lod0-base0-end-n", "kit-lod0-base0-link-se"),
              "kit-lod0-floor0-end-n", "kit-lod0-floor0-link-se"), "kit-lod0-roof0-end-n", "kit-lod0-roof0-link-se"));
        // An arm that stops short of its edge.
        kits.add(symmetric().stream().map(box -> box.node().contains("link") ? new Box(box.node(), -2, 5, 0, 2, 30,
              box.z1()) : box).toList());
        // An arm that makes its module taller than one level.
        kits.add(symmetric().stream().map(box -> box.node().equals("kit-lod0-floor0-link-n")
              ? new Box(box.node(), -2, 5, 0, 2, 36, 20) : box).toList());
        // A variant missing a tagged piece another variant of its type has, or a module missing the core the others have.
        var variants = new ArrayList<>(symmetric());
        variants.add(new Box("kit-lod0-floor1", -5, -5, 0, 5, 5, 18));
        variants.add(new Box("kit-lod0-floor1-link-n", -2, 5, 0, 2, 36, 18));
        kits.add(variants);
        kits.add(symmetric().stream().filter(box -> !box.node().equals("kit-lod0-floor0")).toList());
        // A corner piece without its other three states.
        var corner = new ArrayList<>(symmetric());
        corner.removeIf(box -> box.node().contains("-n"));
        for (String role : List.of("base0", "floor0", "roof0")) {
            corner.add(new Box("kit-lod0-" + role + "-link-n-end-ne", 0, 0, 0, 20, 36, role.equals("roof0") ? 2 : 18));
        }
        kits.add(corner);
        kits.add(wholes().stream().filter(box -> !box.node().equals("kit-lod0-floor0" + LONE)).toList());
        var twice = new ArrayList<>(wholes());
        for (String role : List.of("base0", "floor0", "roof0")) {
            twice.add(new Box("kit-lod0-" + role + "-end-n-link-ne-end-se-end-s-end-sw-end-nw", -20, -28, 0, 20, 36,
                  role.equals("roof0") ? 2 : 18));
        }
        kits.add(twice);
        // A bend piece for a linked corner.
        kits.add(renamed(corners(), "kit-lod0-roof0-end-n-end-ne-bend", "kit-lod0-roof0-link-n-end-ne-bend"));
        for (var boxes : kits) {
            assertThrows(IllegalArgumentException.class, () -> ConnectedKit.load(List.of(kit(boxes))), boxes.toString());
        }
        var far = kit(symmetric().stream().filter(box -> !box.node().contains("end")).toList());
        assertThrows(IllegalArgumentException.class, () -> ConnectedKit.load(List.of(kit(symmetric()), far)),
              "Every level of detail has the same pieces");
    }

    private static List<Box> renamed(List<Box> boxes, String from, String to) {
        return boxes.stream().map(box -> box.node().equals(from)
              ? new Box(to, box.x0(), box.y0(), box.z0(), box.x1(), box.y1(), box.z1()) : box).toList();
    }

    private static RigidGlb.Data kit(List<Box> boxes) {
        var data = new RigidGlb.Data();
        data.id = "kit-lod0";
        var material = new ModelMaterial();
        material.id = "paint";
        data.materials.add(material);
        var mesh = new ModelMesh();
        mesh.id = "mesh";
        var vertices = new FloatArray();
        List<ModelMeshPart> parts = new ArrayList<>();
        for (Box box : boxes) {
            int first = vertices.size / RigidGlb.STRIDE;
            for (int corner = 0; corner < 8; corner++) {
                vertices.addAll((corner & 1) == 0 ? box.x0() : box.x1(), (corner & 2) == 0 ? box.y0() : box.y1(),
                      (corner & 4) == 0 ? box.z0() : box.z1(), 0, 0, 1, 1, 1, 1, 1, 0, 0);
            }
            var part = new ModelMeshPart();
            part.id = box.node() + "/part";
            part.primitiveType = 4;
            part.indices = new short[12];
            int[] corners = { 0, 1, 2, 1, 3, 2, 4, 5, 6, 5, 7, 6 };
            for (int i = 0; i < corners.length; i++) { part.indices[i] = (short) (first + corners[i]); }
            parts.add(part);
            var node = new ModelNode();
            node.id = box.node();
            node.translation = new Vector3();
            node.rotation = new Quaternion();
            node.scale = new Vector3(1, 1, 1);
            var nodePart = new ModelNodePart();
            nodePart.meshPartId = part.id;
            nodePart.materialId = material.id;
            node.parts = new ModelNodePart[] { nodePart };
            data.nodes.add(node);
        }
        mesh.vertices = vertices.toArray();
        mesh.parts = parts.toArray(ModelMeshPart[]::new);
        data.meshes.add(mesh);
        return data;
    }

    /** The model's farthest point along board direction {@code direction}, from the hex centre. */
    private static float farthest(RigidGlb.Data model, int direction) {
        double angle = Math.toRadians(90 - 60 * direction);
        float x = (float) Math.cos(angle), y = (float) Math.sin(angle), result = Float.NEGATIVE_INFINITY;
        for (var mesh : model.meshes) {
            for (int i = 0; i < mesh.vertices.length; i += RigidGlb.STRIDE) {
                result = Math.max(result, mesh.vertices[i] * x + mesh.vertices[i + 1] * y);
            }
        }
        return result;
    }

    private interface Region { boolean contains(float x, float y); }

    /** The lowest and highest point of the model's vertices standing in {@code region}. */
    private static float[] heights(RigidGlb.Data model, Region region) {
        float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
        for (var mesh : model.meshes) {
            for (int i = 0; i < mesh.vertices.length; i += RigidGlb.STRIDE) {
                if (!region.contains(mesh.vertices[i], mesh.vertices[i + 1])) { continue; }
                low = Math.min(low, mesh.vertices[i + 2]);
                high = Math.max(high, mesh.vertices[i + 2]);
            }
        }
        return new float[] { low, high };
    }
}
