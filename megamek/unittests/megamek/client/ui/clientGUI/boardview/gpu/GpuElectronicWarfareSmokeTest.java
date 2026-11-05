/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The ECM pass on the ECM design's scenario (GpuEcmScenario): option C in the 3D view (ticks; the hovered Warwolf's
 * field opens and pulses), option B in the Tactical View (outlines and the hatch, as the Overlays panel's Fields and
 * Jamming rows choose; Fields off leaves the 3D view's ticks). Writes review captures: idle, the pulse at 0.30 / 0.80 /
 * 1.25 / 1.50 s, and the Tactical View with the hatch on and off and with Fields off.
 */
@Tag("on-demand")
class GpuElectronicWarfareSmokeTest {
    /** A mid-edge tick: the south-west side of 1308, the Warwolf field's western tip (1208 lies outside it). */
    private static final Coords EDGE = new Coords(12, 7);
    private static final int EDGE_SIDE = 4;
    /** Inside the Atlas field only: not jammed, no other field, nothing drawn in the 3D view's idle state. */
    private static final Coords MID_FIELD = new Coords(7, 6);
    /** Jammed: open ground in the Warwolf field, outside the Crab's ECCM, with no unit or building on it. */
    private static final Coords JAMMED = new Coords(14, 5);

    @Test
    void drawsTicksAndTheOpenFieldInThreeDAndOutlinesAndTheHatchInTheTacticalView() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean hatch = preferences.getEcmTacticalHatch(), sheets = preferences.getShowMapSheets();
        boolean fields = preferences.getEcmTacticalFields();
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        try (GpuEcmScenario scenario = GpuEcmScenario.create()) {
            SwingUtilities.invokeAndWait(() -> {
                preferences.setShowMapSheets(false);
                if (!preferences.getEcmTacticalHatch()) { preferences.toggleEcmTacticalHatch(); }
                if (!preferences.getEcmTacticalFields()) { preferences.toggleEcmTacticalFields(); }
            });
            var configuration = GpuBoardWindow.configuration(false);
            configuration.setWindowedMode(1920, 1080);
            configuration.setInitialVisible(false);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override public void create() {
                    GpuBattleView view = null;
                    try {
                        view = new GpuBattleView(scenario.fixture.source);
                        view.create();
                        GpuBoardTestUi.present(view);
                        verify(view, scenario, output);
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        if (view != null) { view.dispose(); }
                        Gdx.app.exit();
                    }
                }
            }, configuration);
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                preferences.setShowMapSheets(sheets);
                if (preferences.getEcmTacticalHatch() != hatch) { preferences.toggleEcmTacticalHatch(); }
                if (preferences.getEcmTacticalFields() != fields) { preferences.toggleEcmTacticalFields(); }
            });
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }

    private static void verify(GpuBattleView view, GpuEcmScenario scenario, File output) throws Exception {
        GpuBoardTestUi.<Slider>tuning(view, "Time of day").setValue(13);
        // The captures show the board, as the mock-ups do; the HUD returns only to press the Overlays panel's rows.
        GpuBoardTestUi.stage().getRoot().setVisible(false);
        GpuTactical tactical = (GpuTactical) field(view, "tactical");
        GpuElectronicWarfare pass = tactical.electronicWarfare();
        BoardScene scene = scene(view);
        assertTrue(scene.tactical().fills().isEmpty(), "E(C)CM draws no draped wash or floating ring");
        assertTrue(pass.active(), "The ECM pass draws the scenario's fields");
        assertNull(pass.opened(), "Nothing is open without hover or selection");

        // 3D view, option C: ticks only. Compared with ECM Transparency 0 (no E(C)CM drawing at all).
        view.boardCamera.fit(scene);
        view.boardCamera.setIsometric(true);
        view.boardCamera.camera.zoom *= .62f;
        view.boardCamera.center(center(scene, new Coords(15, 7)));
        settle(view, 12);
        Pixmap idle = capture(output, "ecm-3d-idle");
        onSwing(scenario, () -> GUIPreferences.getInstance().setECMTransparency(0));
        settle(view, 8);
        assertFalse(pass.active(), "ECM Transparency 0 turns every board E(C)CM drawing off");
        Pixmap off = capture(output, "ecm-3d-off");
        onSwing(scenario, () -> GUIPreferences.getInstance().setECMTransparency(128));
        settle(view, 8);
        Vector3 edge = edgeMiddle(scene, EDGE, EDGE_SIDE);
        float tick = difference(view, idle, off, edge), middle = difference(view, idle, off, center(scene, MID_FIELD));
        System.out.printf(Locale.ROOT, "ECM 3D idle: tick %.0f, mid-field %.0f%n", tick, middle);
        assertTrue(tick > 60, "A tick marks the middle of a boundary edge: " + tick);
        assertTrue(middle < 40, "Nothing inside a field in the 3D idle state: " + middle);
        idle.dispose();
        off.dispose();

        // Hovering the enemy Warwolf opens its field: outline, its jammed hatch and the pulse from the unit outward.
        hover(view, scenario.warwolf.getId(), scenario.warwolf.getPosition());
        render(view, 2);
        assertEquals(scenario.warwolf.getId(), pass.opened().source().entityId());
        assertTrue(pass.opened().hovered());
        for (float time : new float[] { .30f, .80f, 1.25f, 1.50f }) {
            capturePulse(view, pass, output, "ecm-3d-hover", time);
        }
        hover(view, megamek.common.units.Entity.NONE, null);

        // Tactical View, option B: every outline and the hatch on every jammed hex, then the Jamming row off.
        view.setTacticalView(true);
        view.boardCamera.fit(scene);
        view.boardCamera.camera.zoom *= .8f;
        view.boardCamera.center(center(scene, new Coords(15, 8)));
        settle(view, 12);
        Pixmap hatched = capture(output, "ecm-tactical-hatch-on");
        pressOverlay(view, "overlay-jamming");
        await(view, scenario, () -> !scenario.fixture.source.uiPreferences().overlays().ecmHatch(),
              "The Jamming row switches the client setting");
        settle(view, 6);
        Pixmap plain = capture(output, "ecm-tactical-hatch-off");
        float stripes = difference(view, hatched, plain, center(scene, JAMMED));
        float unjammed = difference(view, hatched, plain, center(scene, MID_FIELD));
        float outline = difference(view, plain, null, edgeMiddle(scene, EDGE, EDGE_SIDE));
        System.out.printf(Locale.ROOT, "ECM Tactical View: hatch %.0f, unjammed %.0f, outline coral distance %.0f%n",
              stripes, unjammed, outline);
        assertTrue(stripes > 60, "The hatch marks a jammed hex: " + stripes);
        assertTrue(unjammed < 40, "No hatch where no enemy ECM jams: " + unjammed);
        assertTrue(outline < 60, "The enemy outline is coral on its edge: " + outline);
        hatched.dispose();

        // Fields off: the 3D view's calm ticks at the middle of each edge, no line beside them.
        pressOverlay(view, "overlay-fields");
        await(view, scenario, () -> !scenario.fixture.source.uiPreferences().overlays().ecmFields(),
              "The Fields row switches the client setting");
        settle(view, 6);
        Pixmap calm = capture(output, "ecm-tactical-fields-off");
        // Past the tick, which covers the middle 40 % of the side, and short of the corner.
        Vector3 beside = edgePoint(scene, EDGE, EDGE_SIDE, .45f);
        float line = difference(view, plain, null, beside), none = difference(view, calm, null, beside);
        float tickLeft = difference(view, calm, null, edgeMiddle(scene, EDGE, EDGE_SIDE));
        System.out.printf(Locale.ROOT, "ECM Tactical View, Fields off: line %.0f -> %.0f beside the tick, tick %.0f%n",
              line, none, tickLeft);
        assertTrue(line < 60, "With Fields on the outline runs along the whole edge: " + line);
        assertTrue(none > 60, "With Fields off no line runs beside the tick: " + none);
        assertTrue(tickLeft < 70, "The tick stays at the edge's middle: " + tickLeft);
        plain.dispose();
        calm.dispose();
        hover(view, scenario.warwolf.getId(), scenario.warwolf.getPosition());
        render(view, 2);
        assertEquals(scenario.warwolf.getId(), pass.opened().source().entityId(), "Hovering still opens the field");
        capture(output, "ecm-tactical-fields-off-hover").dispose();
        hover(view, megamek.common.units.Entity.NONE, null);
        pressOverlay(view, "overlay-fields");
        await(view, scenario, () -> scenario.fixture.source.uiPreferences().overlays().ecmFields(),
              "The Fields row switches back");

        hover(view, scenario.warwolf.getId(), scenario.warwolf.getPosition());
        render(view, 2);
        for (float time : new float[] { .80f, 1.50f }) {
            capturePulse(view, pass, output, "ecm-tactical-hover", time);
        }
        hover(view, megamek.common.units.Entity.NONE, null);
        pressOverlay(view, "overlay-jamming");
        onSwing(scenario, () -> { });
        render(view, 4);
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
    }

    /** Presses a row of the Tactical View's Overlays panel, which shows only there. */
    private static void pressOverlay(GpuBattleView view, String name) {
        GpuBoardTestUi.stage().getRoot().setVisible(true);
        render(view, 2);
        assertTrue(GpuBoardTestUi.shown(GpuBoardTestUi.stage().getRoot().findActor(name)), name + " is shown");
        GpuBoardTestUi.click(name);
        GpuBoardTestUi.stage().getRoot().setVisible(false);
    }

    /** Publishes frames until the board's preferences show the change, as the source's timer would. */
    private static void await(GpuBattleView view, GpuEcmScenario scenario, BooleanSupplier changed, String message)
          throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!changed.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, message);
            onSwing(scenario, () -> { });
            render(view, 1);
        }
    }

    /** Runs the change on Swing, then publishes the board's next frame, as the source's timer would. */
    private static void onSwing(GpuEcmScenario scenario, Runnable change) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            change.run();
            scenario.fixture.source.refresh();
        });
    }

    private static BoardScene scene(GpuBattleView view) throws Exception {
        return (BoardScene) field(view, "scene");
    }

    private static void hover(GpuBattleView view, int unit, Coords coords) throws Exception {
        var hovered = GpuBattleView.class.getDeclaredField("hovered");
        hovered.setAccessible(true);
        hovered.set(view, coords);
        var hoveredUnit = GpuBattleView.class.getDeclaredField("hoveredUnit");
        hoveredUnit.setAccessible(true);
        hoveredUnit.setInt(view, unit);
    }

    /** Renders until the pulse reaches {@code time} in its first cycle, then writes the frame. */
    private static void capturePulse(GpuBattleView view, GpuElectronicWarfare pass, File output, String name,
          float time) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        float previous = pass.pulseTime();
        // Wait for the next cycle when the wanted time has passed in this one.
        while (pass.pulseTime() >= time - .02f || pass.pulseTime() < 0) {
            assertTrue(System.nanoTime() < deadline, "The hovered field pulses");
            render(view, 1);
            if (pass.pulseTime() < previous) { break; }
            previous = pass.pulseTime();
        }
        while (pass.pulseTime() < time) {
            assertTrue(System.nanoTime() < deadline, "The pulse advances");
            render(view, 1);
        }
        System.out.printf(Locale.ROOT, "ECM pulse %s at %.3f s%n", name, pass.pulseTime());
        capture(output, String.format(Locale.ROOT, "%s-%03d", name, Math.round(time * 100))).dispose();
    }

    /**
     * Renders frames as the application loop does: the window's clock advances first, so the pulse sees real frame
     * times although this test drives the view from inside {@code create()}.
     */
    private static void render(GpuBattleView view, int frames) {
        try {
            var update = Gdx.graphics.getClass().getDeclaredMethod("update");
            update.setAccessible(true);
            for (int i = 0; i < frames; i++) {
                update.invoke(Gdx.graphics);
                view.render();
            }
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    /** Renders until the terrain is ready and the camera has finished its transition, then {@code frames} more. */
    private static void settle(GpuBattleView view, int frames) {
        GpuBoardTestUi.present(view);
        long deadline = System.nanoTime() + 10_000_000_000L;
        float[] last = null;
        for (int same = 0; same < 3; ) {
            assertTrue(System.nanoTime() < deadline, "The camera settles");
            render(view, 1);
            float[] now = view.boardCamera.camera.combined.val.clone();
            same = java.util.Arrays.equals(now, last) ? same + 1 : 0;
            last = now;
        }
        render(view, frames);
    }

    private static Pixmap capture(File output, String name) {
        GpuBoardTestUi.capture(new File(output, name + ".png"));
        return Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    }

    private static Vector3 center(BoardScene scene, Coords coords) {
        Vector3 point = BoardGeometry.center(coords, 0);
        point.z = BoardTacticalGeometry.floatingZ(scene, coords);
        return point;
    }

    /** The middle of the hex's side toward {@code direction}, halfway between the two hex planes. */
    private static Vector3 edgeMiddle(BoardScene scene, Coords coords, int direction) {
        return center(scene, coords).lerp(center(scene, coords.translated(direction)), .5f);
    }

    /** A point on that side, {@code share} of the side's length from its middle toward a corner. */
    private static Vector3 edgePoint(BoardScene scene, Coords coords, int direction, float share) {
        Vector3 across = center(scene, coords.translated(direction)).sub(center(scene, coords));
        Vector3 along = new Vector3(-across.y, across.x, 0).nor().scl(share * BoardGeometry.width() / 2);
        return edgeMiddle(scene, coords, direction).add(along);
    }

    /**
     * The largest colour difference in a 9 x 9 pixel window around the projected point: between two frames, or with
     * {@code other} null the distance to the enemy coral.
     */
    private static float difference(GpuBattleView view, Pixmap frame, Pixmap other, Vector3 world) {
        Vector3 screen = view.boardCamera.camera.project(world.cpy(), 0, 0, frame.getWidth(), frame.getHeight());
        System.out.printf(Locale.ROOT, "ECM probe at %.0f, %.0f%n", screen.x, frame.getHeight() - screen.y);
        float result = other == null ? Float.MAX_VALUE : 0;
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = Math.round(screen.x) + dx, y = Math.round(screen.y) + dy;
                if (x < 0 || y < 0 || x >= frame.getWidth() || y >= frame.getHeight()) { continue; }
                int a = frame.getPixel(x, y), b = other == null ? 0xEC9189FF : other.getPixel(x, y);
                float distance = Math.max(Math.abs((a >>> 24) - (b >>> 24)),
                      Math.max(Math.abs((a >>> 16 & 255) - (b >>> 16 & 255)), Math.abs((a >>> 8 & 255) - (b >>> 8 & 255))));
                result = other == null ? Math.min(result, distance) : Math.max(result, distance);
            }
        }
        return result;
    }
}
