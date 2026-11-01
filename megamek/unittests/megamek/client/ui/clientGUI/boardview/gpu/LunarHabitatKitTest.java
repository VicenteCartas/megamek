/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.Configuration;
import megamek.common.board.HexTile;
import megamek.common.board.TilesetScenery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The lunar habitat, connected building kits generated from buildings/lunar_habitat/lunar_habitat.json in both
 * footprints: Square, rectangular blocks that join across hexsides, and Angled, walls along the hex's directions.
 */
class LunarHabitatKitTest {
    private static final float LEVEL = GpuBuilding.LEVEL_HEIGHT, EDGE = 36;

    @BeforeAll
    static void natives() {
        GdxNativesLoader.load();
    }

    /**
     * Generated from its JSON spec (HabitatKit); loading checks the kit's contract: names, the four states of every
     * corner piece, one-level modules.
     */
    private static ConnectedKit.Kit kit(boolean square) {
        return HabitatKit.kit(new File(Configuration.dataDir(), "models/buildings/lunar_habitat/lunar_habitat.json"), square,
              null);
    }

    private static ConnectedKit.Kit kit() { return kit(true); }

    /** The near triangles of a hex's modules of {@code type}, two levels high, connecting {@code reach} levels each way. */
    private static List<Vector3> placed(ConnectedKit.Kit kit, int[] reach, String type) {
        List<Vector3> result = new ArrayList<>();
        for (var piece : ConnectedKit.placements(kit, 0, 2, reach, 0, 1)) {
            if (piece.role().startsWith(type)) { result.addAll(ConnectedKit.triangles(kit, 0, piece)); }
        }
        return result;
    }

    /** Whether a facade (a vertical face wider than a base course's end) lies on the north hexside's line. */
    private static boolean northFacade(List<Vector3> triangles) {
        for (int i = 0; i < triangles.size(); i += 3) {
            Vector3 a = triangles.get(i), b = triangles.get(i + 1), c = triangles.get(i + 2);
            if (Math.abs(a.y - EDGE) < .01f && Math.abs(b.y - EDGE) < .01f && Math.abs(c.y - EDGE) < .01f
                  && Math.max(a.z, Math.max(b.z, c.z)) - Math.min(a.z, Math.min(b.z, c.z)) > 1
                  && Math.max(a.x, Math.max(b.x, c.x)) - Math.min(a.x, Math.min(b.x, c.x)) > 4) { return true; }
        }
        return false;
    }

    @Test
    void aLoneHexStandsEnclosed() {
        var walls = placed(kit(), new int[6], "base");
        assertFalse(GpuBuildingInterior.walls(walls, LEVEL / 2).isEmpty(), "Its ground storey's facades close round it");
        assertTrue(northFacade(walls), "A facade stands on its north hexside");
    }

    @Test
    void anAngledHexConnectedNorthAndSouthRunsStraightThrough() {
        var kit = kit(false);
        assertFalse(GpuBuildingInterior.walls(placed(kit, new int[6], "base"), LEVEL / 2).isEmpty(),
              "Alone, its facades close round it");
        var through = placed(kit, new int[] { 2, 0, 0, 2, 0, 0 }, "base");
        // Its facades stand on the two lines a hexside's ends run along, from hexside to hexside, and nowhere else.
        float half = HexTile.cornerOffsetX(1, HexTile.MODEL_WIDTH);
        for (int i = 0; i < through.size(); i += 3) {
            Vector3 a = through.get(i), b = through.get(i + 1), c = through.get(i + 2);
            boolean upright = Math.max(a.z, Math.max(b.z, c.z)) - Math.min(a.z, Math.min(b.z, c.z)) > 8;
            boolean wide = Math.max(a.y, Math.max(b.y, c.y)) - Math.min(a.y, Math.min(b.y, c.y)) > 4
                  || Math.max(a.x, Math.max(b.x, c.x)) - Math.min(a.x, Math.min(b.x, c.x)) > 4;
            if (upright && wide && Math.abs(a.x - b.x) < .01f && Math.abs(a.x - c.x) < .01f) {
                assertEquals(half, Math.abs(a.x), .05f, "A facade along the band's side");
            }
        }
        assertFalse(northFacade(through), "Its north hexside stays open");
    }

    @Test
    void aConnectedHexsideStaysOpenAndTheSlantedOnesCellFillsToTheCorner() {
        var kit = kit();
        assertFalse(northFacade(placed(kit, new int[] { 2, 0, 0, 0, 0, 0 }, "base")),
              "Towards a connected north neighbour no facade stands: the two cores run on");
        float lone = (float) placed(kit, new int[6], "base").stream().mapToDouble(point -> point.x).max().orElseThrow();
        float joined = (float) placed(kit, new int[] { 0, 2, 0, 0, 0, 0 }, "base").stream().mapToDouble(point -> point.x)
              .max().orElseThrow();
        assertTrue(lone < 30, "Alone, the block keeps to the core between the north and south hexsides: " + lone);
        assertEquals(HexTile.cornerOffsetX(0, HexTile.MODEL_WIDTH), joined, .05f,
              "Connected north-east, the cell beside that hexside reaches the hex's east corner");
    }

    @Test
    void aGeneratedKitsVariantRidesOnItsAssetAndAnUnknownOneDrawsTheFirst() {
        String habitat = "buildings/lunar_habitat/lunar_habitat";
        assertEquals(List.of("rounded", "parapet"), TilesetScenery.kitVariants(habitat), "The spec's names, in its order");
        String parapet = TilesetScenery.kitVariant(TilesetScenery.footprint(habitat,
              megamek.common.board.HexAppearance.Footprint.SQUARE), "parapet");
        assertEquals(habitat + "-square@parapet", parapet);
        assertEquals(TilesetScenery.customBuildingFile(habitat), TilesetScenery.customBuildingFile(parapet), "One spec");
        assertEquals(habitat, TilesetScenery.kitVariant(habitat, "rounded"), "The first is the kit's own asset");
        assertEquals(habitat, TilesetScenery.kitVariant(habitat, "gone"), "As is a name the spec lacks");
        String dome = "buildings/multihex/dome";
        assertEquals(dome, TilesetScenery.kitVariant(dome, "parapet"), "Only a generated kit has variants");
        assertTrue(TilesetScenery.kitVariants(dome).isEmpty());
        int first = vertices(GpuBuilding.kit(habitat + "-square"));
        assertNotEquals(first, vertices(GpuBuilding.kit(parapet)), "Its own look");
        assertEquals(first, vertices(GpuBuilding.kit(habitat + "-square@gone")), "A name the spec lacks draws the first");
    }

    private static int vertices(ConnectedKit.Kit kit) {
        return kit.lods().getFirst().meshes.first().vertices.length;
    }
}
