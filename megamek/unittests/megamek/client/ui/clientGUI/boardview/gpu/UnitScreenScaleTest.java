/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Coords;
import megamek.common.units.EntityMovementType;
import megamek.common.units.ProneCause;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Zoom-out unit scaling: a unit keeps its size until a hex is narrower on screen than the threshold,
 * then grows to keep the size it had on screen at the threshold, up to the maximum, on every axis and on top of its
 * family scale, and its strides grow with it.
 */
class UnitScreenScaleTest {
    private static final float NEAR = .00001f;
    /** The walk's sampling: 256 frames a hex, as UnitLegBendTest walks its fixture. */
    private static final int FRAMES_PER_HEX = 256;

    @BeforeAll
    static void loadMathNatives() {
        GdxNativesLoader.load();
    }

    @Test
    void unitsKeepTheirSizeUntilTheThresholdThenKeepTheirSizeOnScreenUpToTheMaximum() {
        assertEquals(1, UnitScreenScale.factor(200, true, 80, 2), "Zoomed in");
        assertEquals(1, UnitScreenScale.factor(80, true, 80, 2), "At the threshold");
        for (float hexPixels : new float[] { 79, 64, 53.3f, 41 }) {
            assertEquals(80, UnitScreenScale.factor(hexPixels, true, 80, 2) * hexPixels, .001f,
                  "A hex " + hexPixels + " pixels wide: the unit is as large on screen as at the threshold");
        }
        assertEquals(2, UnitScreenScale.factor(40, true, 80, 2), NEAR, "The maximum");
        assertEquals(2, UnitScreenScale.factor(10, true, 80, 2), NEAR, "Further out, no more");
        assertEquals(2, UnitScreenScale.factor(0, true, 80, 2), NEAR);
        assertEquals(1, UnitScreenScale.factor(10, false, 80, 2), "Switched off");
        assertEquals(1, UnitScreenScale.factor(10, true, 80, 1), "A maximum of 1 never grows");
    }

    @Test
    void aHexKeepsItsHudPixelsWhenTheDisplayScaleChanges() {
        BoardCamera camera = new BoardCamera();
        camera.resize(1920, 1080, null, 1);
        camera.zoom(BoardGeometry.WIDTH / 60 / camera.camera.zoom);
        assertEquals(60, UnitScreenScale.hexPixels(camera.camera.zoom, 1), .001f);
        // On a monitor of twice the density the window has twice the pixels; the hex keeps its HUD pixels.
        camera.resize(3840, 2160, null, 2);
        assertEquals(60, UnitScreenScale.hexPixels(camera.camera.zoom, 2), .001f);
    }

    @Test
    void growthIsUniformOnTopOfTheFamilyScaleAndKeepsTheUnitOnItsFeet() {
        var family = UnitFamilyScale.INFANTRY;
        float size = family.UNIT_SCALE;
        float height = family.HEIGHT_SCALE;
        var model = new GpuUnitModel(new Model(), null, true, List.of(), new Vector3(100, 100, 54), List.of(), family);
        var camera = new OrthographicCamera();
        var coords = new Coords(2, 2);
        try {
            // A family's own height knob changes its proportions; the growth must not.
            family.UNIT_SCALE = size * 1.25f;
            family.HEIGHT_SCALE = height * 1.4f;
            var unit = unit(coords, -1, false, List.of(coords));
            // An empty model's anchor is its origin; a real body's top is above it.
            Vector3 anchor = place(model, unit, camera).add(0, 0, 10);
            Vector3 feet = model.instance.transform.getTranslation(new Vector3());
            Vector3 placed = model.instance.transform.getScale(new Vector3());
            Vector3 head = UnitScreenScale.grow(unit, model.instance, anchor, 1.5f);
            assertEquals(15, head.z - feet.z, .001f, "An anchor 10 above the feet rises with the unit");
            assertEquals(feet.x, head.x, .001f);
            assertEquals(feet.y, head.y, .001f);
            Vector3 grown = model.instance.transform.getScale(new Vector3());
            assertEquals(1.5f * placed.x, grown.x, NEAR);
            assertEquals(1.5f * placed.y, grown.y, NEAR);
            assertEquals(1.5f * placed.z, grown.z, NEAR);
            assertEquals(placed.z / placed.x, grown.z / grown.x, NEAR, "Height keeps its ratio to width");
            assertEquals(feet, model.instance.transform.getTranslation(new Vector3()), "The unit stays on its feet");

            // Neither a sensor contact, nor one part of a large unit, nor a unit across several hexes grows.
            for (var kept : List.of(unit(coords, -1, true, List.of(coords)), unit(coords, 0, false, List.of(coords)),
                  unit(coords, -1, false, List.of(coords, coords.translated(0))))) {
                Vector3 own = place(model, kept, camera);
                Vector3 keptSize = model.instance.transform.getScale(new Vector3());
                assertSame(own, UnitScreenScale.grow(kept, model.instance, own, 1.5f));
                assertEquals(keptSize, model.instance.transform.getScale(new Vector3()));
            }
        } finally {
            family.UNIT_SCALE = size;
            family.HEIGHT_SCALE = height;
            model.dispose();
        }
    }

    /**
     * A unit grown when zoomed out strides over that much more ground: walking four hexes, its supporting foot keeps
     * its place on the ground as at its own size, while a gait that ignores the growth slides the foot along.
     */
    @Test
    void aGrownUnitStridesSoItsSupportingFootStaysPlanted() throws Exception {
        float travel = BoardGeometry.HEIGHT / FRAMES_PER_HEX;
        float own = slip(1, 1);
        float grown = slip(2, 2);
        float ignored = slip(1, 2);
        System.out.printf("Planted-foot slip per frame (travel %.4f): own size %.4f, grown x2 %.4f, growth ignored"
              + " %.4f%n", travel, own, grown, ignored);
        assertTrue(own < travel / 4, "At its size a planted foot holds: " + own + " of " + travel + " per frame");
        assertTrue(grown < travel / 4, "Grown twice, a planted foot holds: " + grown);
        assertTrue(ignored > travel / 2, "A gait that ignores the growth slides the foot: " + ignored);
    }

    /**
     * The largest move between two frames of a sole that stands on the ground in both, while the leg fixture walks
     * four hexes north with the gait for {@code gaitGrowth} and its body grown by {@code growth}.
     */
    private static float slip(float gaitGrowth, float growth) throws Exception {
        try (var fixture = new UnitLegBendTest.Fixture(false)) {
            float largest = 0;
            Vector3[] previous = null;
            for (int frame = 0; frame <= 4 * FRAMES_PER_HEX; frame++) {
                float steps = frame / (float) FRAMES_PER_HEX;
                UnitMotion.Sample walk = UnitLegBendTest.sample(EntityMovementType.MOVE_WALK, steps, 1, 0,
                      ProneCause.NONE);
                fixture.animator.apply(fixture.model, fixture.instance, fixture.unit, walk, steps, 0, true, 0,
                      gaitGrowth);
                Vector3 anchor = fixture.model.place(fixture.instance, fixture.camera,
                      new Vector3(0, steps * BoardGeometry.HEIGHT, 0), 0, fixture.unit);
                UnitScreenScale.grow(fixture.unit, fixture.instance, anchor, growth);
                Vector3[] soles = { fixture.sole("L"), fixture.sole("R") };
                for (int leg = 0; previous != null && leg < soles.length; leg++) {
                    if (grounded(soles[leg], growth) && grounded(previous[leg], growth)) {
                        largest = Math.max(largest, soles[leg].dst(previous[leg]));
                    }
                }
                previous = soles;
            }
            return largest;
        }
    }

    /** The sole stands on the ground, half a world unit under the placed feet, as the fixture's floor. */
    private static boolean grounded(Vector3 sole, float growth) {
        return Math.abs(sole.z - .5f) < .001f * growth;
    }

    private static BoardScene.Unit unit(Coords coords, int part, boolean contact, List<Coords> footprint) {
        return new BoardScene.Unit(1, part, "Growth review", new BoardScene.Waypoint(coords, 2, 0), null, contact, null,
              1, false, null, 0, footprint);
    }

    private static Vector3 place(GpuUnitModel model, BoardScene.Unit unit, OrthographicCamera camera) {
        return model.place(model.instance, camera, BoardGeometry.center(unit.location().coords(), 2), 0, unit);
    }
}
