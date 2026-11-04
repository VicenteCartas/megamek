/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMaterial;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BuildingInteriors;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;
import org.junit.jupiter.api.Test;

class GpuAuthoredPartitionTest {
    @Test void sharesConserveSurfaceAreaAndOwnOnlyTheirVoronoiRegionIncludingDistantOverhangs() {
        GdxNativesLoader.load();
        var mesh = new GeneratedMesh();
        mesh.slab("body", List.of(new double[] {-120, -90}, new double[] {170, -90}, new double[] {170, 90}, new double[] {-120, 90}), 0, 30, true);
        var materials = new ModelData(); var material = new ModelMaterial(); material.id = "body"; materials.materials.add(material);
        var source = mesh.data("partition-fixture-lod0", materials);
        var anchor = new Coords(3, 3);
        var members = List.of(new Coords(2, 2), new Coords(4, 2), anchor, new Coords(2, 4), new Coords(4, 4));
        for (double rotation : List.of(0.0, 37.0, 90.0)) {
            var object = new BoardDecoration("fixture", "prop", "buildings/test-partition", null, 0, 0, rotation, rotation == 90,
                  1, BoardDecoration.Placement.ground(), 0).withStretch(new BoardDecoration.Stretch(.9, 1.1, 1.2));
            double area = 0;
            for (Coords cell : members) {
                var layout = new GpuAuthoredBuilding.Layout(cell, anchor, object, 0, 0, 0, BuildingInteriors.NO, members, 8, 8);
                var prepared = GpuAuthoredBuilding.prepare(layout, List.of(source));
                var share = AuthoredBoardClipTest.mesh(prepared.levels().getFirst());
                for (var part : share.parts.values()) {
                    for (int i = 0; i < part.size; i += RigidGlb.STRIDE) {
                        double x = part.items[i] + HexTile.centerX(cell, HexTile.MODEL_WIDTH);
                        double y = part.items[i + 1] + HexTile.centerY(cell, HexTile.HEIGHT);
                        double own = distance(x, y, cell);
                        for (Coords other : members) { assertTrue(own <= distance(x, y, other) + .5, "Share escaped its region"); }
                    }
                    for (int i = 0; i < part.size; i += 3 * RigidGlb.STRIDE) {
                        var a = point(part.items, i); var b = point(part.items, i + RigidGlb.STRIDE); var c = point(part.items, i + 2 * RigidGlb.STRIDE);
                        area += b.sub(a).crs(c.sub(a)).len() * .5;
                    }
                }
            }
            double width = 290 * .9, depth = 180 * 1.1, height = 30 * 1.2;
            assertEquals(2 * (width * depth + width * height + depth * height), area, 1.5, "No missing or duplicate exterior at " + rotation);
        }
    }

    private static Vector3 point(float[] vertices, int at) { return new Vector3(vertices[at], vertices[at + 1], vertices[at + 2]); }
    private static double distance(double x, double y, Coords cell) {
        double dx = x - HexTile.centerX(cell, HexTile.MODEL_WIDTH), dy = y - HexTile.centerY(cell, HexTile.HEIGHT);
        return dx * dx + dy * dy;
    }
}
