/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuBoardOverlaySmokeTest.difference;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBoardOverlaySmokeTest.peak;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBoardOverlaySmokeTest.step;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBoardOverlaySmokeTest.unit;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The planned route's pulse (user item 55) over the board-space harness, as GpuBattleView draws it: the head runs from
 * the unit to the destination along the route, the ghost surges as it lands and a ring ripples out, the cycle repeats,
 * keeps its rhythm while the plan stays and starts again for a new one; a jump's head rides its arc; a turn in place
 * has no head but its ghost surges; nothing pulses for a hover preview, a route MegaMek's board clicks plan, a cleared
 * selection or with the pulse switched off; the Tactical View's flat pulse; the real GpuBattleView moving it on once a
 * frame. Time alone never rebuilds the overlay's meshes. It writes the review strip (pulse-*.png) and the layer's
 * measured cost (pulse-cost.txt).
 */
@Tag("on-demand")
class GpuRoutePulseSmokeTest {
    private static final int ATLAS = GpuHudFixtures.ATLAS;
    private static final float FRAME = 1 / 60f;
    /** The hex radius in world units. */
    private static final float RADIUS = BoardGeometry.WIDTH / 2;
    private static final GpuBattleStatus.Snapshot STATUS = GpuHudFixtures.status();

    @Test
    void pulseHeadAndArrivalUseTheConfiguredSegmentColorsInBothViews() throws Exception {
        Coords origin = new Coords(3, 7);
        BoardScene scene;
        try (GpuBoardFixture fixture = GpuBoardFixture.create(Board.createEmptyBoard(7, 9))) {
            scene = onSwing(() -> {
                fixture.entity.setPosition(origin);
                fixture.source.refresh();
                return fixture.source.takeFrame().scene();
            });
        }
        BoardSource.UiPreferences preferences = onSwing(BoardSource.UiPreferences::capture);
        Map<GpuMovePlan.Band, Integer> colors = new HashMap<>(preferences.movementColors());
        colors.put(GpuMovePlan.Band.WALK, java.awt.Color.RED.getRGB());
        colors.put(GpuMovePlan.Band.RUN, java.awt.Color.GREEN.getRGB());
        colors.put(GpuMovePlan.Band.BOOST, java.awt.Color.BLUE.getRGB());
        BoardSource.UiPreferences custom = GpuHudFixtures.with(preferences, Map.of("movementColors", colors));
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene);
            GpuBoardOverlay overlay = new GpuBoardOverlay();
            try {
                // GpuBattleView's warm-up: the route, its marks and its pulse then draw without linking a program.
                overlay.prepare();
                int programs = GpuShaderLoadSmokeTest.programs(overlay);
                board.units = false;
                List<GpuMovePlan.Step> route = List.of(
                      step(scene, origin.translated(0, 1), 0, GpuMovePlan.Band.WALK),
                      step(scene, origin.translated(0, 2), 0, GpuMovePlan.Band.RUN),
                      step(scene, origin.translated(0, 3), 0, GpuMovePlan.Band.BOOST));
                GpuMovePlan.Snapshot plan = GpuBoardOverlaySmokeTest.move(ATLAS, route, List.of(), List.of(),
                      Map.of(), true);
                var frame = GpuBoardOverlaySmokeTest.frame(scene, STATUS,
                      GpuBoardOverlaySmokeTest.panels(plan, GpuFireOrders.Snapshot.EMPTY));
                List<Color> expected = List.of(Color.RED, Color.GREEN, Color.BLUE);
                for (boolean tactical : List.of(false, true)) {
                    GpuHudState state = GpuBoardOverlaySmokeTest.state(STATUS);
                    show(overlay, scene, tactical, GpuMovePlan.Snapshot.EMPTY, state);
                    overlay.update(frame, GpuBoardOverlaySmokeTest.view(tactical, null, Entity.NONE), custom, state);
                    GpuBoardOverlaySmokeTest.frame(board, tactical, route.get(1).coords(), 150, 960, 540);
                    GpuRoutePulse pulse = overlay.pulse();
                    Set<Integer> seen = new HashSet<>();
                    for (int tick = 0; tick < 120 && pulse.head() >= 0; tick++) {
                        float progress = pulse.head() / pulse.length() * route.size();
                        int segment = Math.min(route.size() - 1, (int) progress);
                        // Sample inside each segment, away from its boundary and the initial fade-in.
                        if (progress - segment > .4f && progress - segment < .7f) {
                            Renderable rendered = pulse.renderable(board.camera.camera);
                            assertNotNull(rendered);
                            var mesh = rendered.meshPart.mesh;
                            int stride = mesh.getVertexSize() / Float.BYTES;
                            int color = mesh.getVertexAttribute(VertexAttributes.Usage.ColorPacked).offset / Float.BYTES;
                            float[] vertices = new float[mesh.getNumVertices() * stride];
                            mesh.getVertices(vertices);
                            // The head's innermost quad is last, after the trail and streak.
                            assertHue(expected.get(segment), vertices[vertices.length - stride + color]);
                            if (seen.add(segment)) {
                                board.draw(overlay::render);
                                hud.captureBackBuffer("pulse-color-" + tactical + "-segment-" + segment).dispose();
                            }
                        }
                        pulse.advance(FRAME);
                    }
                    assertEquals(Set.of(0, 1, 2), seen, "The head must visit all three colour bands");
                    assertEquals(Color.BLUE, pulse.arrivalColor(new Color()), "The ghost surge uses the final colour");
                    // Once the trail and flash fade, only the arrival ripples remain.
                    for (int tick = 0; tick < 33; tick++) { pulse.advance(FRAME); }
                    Renderable arrived = pulse.renderable(board.camera.camera);
                    assertNotNull(arrived);
                    var mesh = arrived.meshPart.mesh;
                    int stride = mesh.getVertexSize() / Float.BYTES;
                    int color = mesh.getVertexAttribute(VertexAttributes.Usage.ColorPacked).offset / Float.BYTES;
                    float[] vertices = new float[mesh.getNumVertices() * stride];
                    mesh.getVertices(vertices);
                    for (int index = color; index < vertices.length; index += stride) {
                        assertHue(Color.BLUE, vertices[index]);
                    }
                    board.draw(overlay::render);
                    hud.captureBackBuffer("pulse-color-" + tactical + "-arrival").dispose();
                }
                assertEquals(programs, GpuShaderLoadSmokeTest.programs(overlay), "The route linked a program");
            } finally {
                overlay.dispose();
                board.dispose();
            }
        });
    }

    private static void assertHue(Color expected, float packed) {
        Color actual = new Color();
        Color.abgr8888ToColor(actual, packed);
        float strength = Math.max(actual.r, Math.max(actual.g, actual.b));
        assertTrue(strength > .01f, "The pulse must remain visible");
        assertEquals(expected.r, actual.r / strength, .01f);
        assertEquals(expected.g, actual.g / strength, .01f);
        assertEquals(expected.b, actual.b / strength, .01f);
    }

    /**
     * One pulse at 60 frames a second as the pulse reports it, frame by frame: the head (-1 once it landed) and the
     * surge; the frames where the head landed, the surge ended and the next pulse left the unit.
     */
    private record Cycle(List<Float> heads, List<Float> surges, int landed, int calm, int again) {
        float travel() {
            return landed * FRAME;
        }

        /** Halfway through the rest between the surge and the next pulse, in seconds from the pulse's start. */
        float resting() {
            return (calm + again) / 2f * FRAME;
        }
    }

    @Test
    void theHeadRunsFromTheUnitToTheGhostWhichSurgesAndTheCycleRepeats() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        BoardScene.UnitModel atlasModel = GpuBoardOverlaySmokeTest.atlasModel();
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene);
            GpuBoardOverlay overlay = new GpuBoardOverlay();
            GpuUnitModels models = new GpuUnitModels();
            List<Pixmap> images = new ArrayList<>();
            try {
                assertEquals(0, Gdx.graphics.getDeltaTime(), "Inside the harness only the test moves the pulse on");
                GpuHudState state = GpuBoardOverlaySmokeTest.state(STATUS);
                GpuMovePlan.Snapshot plan = walkAndRun(scene);
                show(overlay, scene, false, plan, state);
                GpuRoutePulse pulse = overlay.pulse();
                Vector3 start = lifted(scene, unit(scene, ATLAS).location().coords());
                Vector3 end = lifted(scene, plan.route().getLast().coords());
                Vector3 head = new Vector3();
                assertTrue(pulse.headAt(head), "The pulse leaves at once");
                assertEquals(0, Vector2.dst(start.x, start.y, head.x, head.y), .05f * RADIUS,
                      "It leaves from the unit's hex");

                Cycle cycle = cycle(overlay, scene, false, plan, state, end);
                float length = pulse.length();
                assertTrue(length > 5 * BoardGeometry.HEIGHT / RADIUS * .99f, "Five hexes of route: " + length);
                assertEquals(0f, (float) cycle.heads().getFirst(), "The head starts at the unit");
                for (int frame = 1; frame < cycle.landed(); frame++) {
                    assertTrue(cycle.heads().get(frame) >= cycle.heads().get(frame - 1),
                          "The head never runs back: frame " + frame);
                    assertEquals(0f, (float) cycle.surges().get(frame), "No surge before it lands");
                }
                assertTrue(cycle.heads().get(cycle.landed() - 1) > .99f * length, "It runs the whole route");
                float surge = (float) cycle.surges().stream().mapToDouble(Float::doubleValue).max().orElse(0);
                assertTrue(surge > .95f * GpuRoutePulse.intensity, "The ghost surges as the head lands: " + surge);
                assertTrue(cycle.calm() > cycle.landed() && cycle.again() > cycle.calm(),
                      "The surge ends, the pulse rests, then the next one leaves the unit");
                assertEquals(0, (float) cycle.heads().get(cycle.again()), .01f,
                      "The next pulse starts at the unit again (within its first frame's way)");

                // The review strip, with the authored Atlas's ghost as GpuBattleView shows it in 3D.
                ModelInstance atlas = GpuBoardOverlaySmokeTest.authored(models, atlasModel, unit(scene, ATLAS), board);
                Function<BoardScene.Unit, ModelInstance> shown = unit -> unit.id() == ATLAS ? atlas : null;
                GpuBoardOverlaySmokeTest.frame(board, false, plan.route().get(2).coords(), 150, 960, 560);
                float travel = cycle.travel();
                Pixmap rest = strip(hud, board, overlay, scene, plan, state, shown, cycle.resting(),
                      "pulse-3d-0-rest");
                images.add(rest);
                images.add(strip(hud, board, overlay, scene, plan, state, shown, .12f, "pulse-3d-1-leaving"));
                Pixmap midway = strip(hud, board, overlay, scene, plan, state, shown, travel / 2, "pulse-3d-2-midway");
                images.add(midway);
                assertTrue(pulse.headAt(head));
                Vector2 middle = board.screen(head);
                images.add(strip(hud, board, overlay, scene, plan, state, shown, travel - 3 * FRAME,
                      "pulse-3d-3-arriving"));
                images.add(strip(hud, board, overlay, scene, plan, state, shown, travel + .08f, "pulse-3d-4-surge"));
                Pixmap ripple = strip(hud, board, overlay, scene, plan, state, shown, travel + .3f,
                      "pulse-3d-5-ripple");
                images.add(ripple);
                assertTrue(peak(rest, midway, middle, 3) > 60, "The head shines where the pulse puts it");
                assertTrue(ripples(board, rest, ripple, end) > 30, "A ring ripples out around the destination");
                // The ghost alone, resting and at the surge's peak: its glow rises.
                Pixmap calm = ghost(hud, board, overlay, scene, plan, state, shown, cycle.resting(),
                      "pulse-3d-ghost-rest");
                Pixmap surging = ghost(hud, board, overlay, scene, plan, state, shown, travel + .08f,
                      "pulse-3d-ghost-surge");
                images.addAll(List.of(calm, surging));
                Vector2 ghost = board.screen(new Vector3(end).add(0, 0, .5f * RADIUS));
                assertTrue(brightness(surging, ghost, 12) > brightness(calm, ghost, 12) + 8,
                      "The ghost brightens at the surge: " + brightness(calm, ghost, 12) + " to "
                            + brightness(surging, ghost, 12));

                measure(board, overlay, scene, plan, state, travel);
            } finally {
                GpuRoutePulse.enabled = GpuRoutePulse.ENABLED;
                images.forEach(Pixmap::dispose);
                overlay.dispose();
                models.dispose();
                board.dispose();
            }
        });
    }

    @Test
    void theRhythmHoldsForTheSamePlanAndAJumpRidesItsArc() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        BoardScene.UnitModel atlasModel = GpuBoardOverlaySmokeTest.atlasModel();
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene);
            GpuBoardOverlay overlay = new GpuBoardOverlay();
            GpuUnitModels models = new GpuUnitModels();
            try {
                GpuHudState state = GpuBoardOverlaySmokeTest.state(STATUS);
                GpuMovePlan.Snapshot plan = walkAndRun(scene);
                GpuRoutePulse pulse = overlay.pulse();
                at(overlay, scene, false, plan, state, .3f);
                float head = pulse.head();
                assertTrue(head > 0, "Under way after .3 s");
                long builds = overlay.builds();
                // A rebuild for something else (here a unit under the pointer, which hides a hover route) keeps the
                // pulse where it was.
                show(overlay, scene, false, plan, state, scene.units().getLast().id());
                assertEquals(builds + 1, overlay.builds(), "Hovering a unit rebuilds the meshes");
                assertEquals(head, pulse.head(), "and the same plan's pulse runs on from where it was");
                pulse.advance(FRAME);
                assertTrue(pulse.head() > head);
                // A new plan starts from the unit, and a shorter route takes less time.
                GpuMovePlan.Snapshot shorter = GpuBoardOverlaySmokeTest.atlasMove(scene);
                show(overlay, scene, false, shorter, state);
                assertEquals(0, pulse.head(), "A new plan's pulse leaves the unit");
                Cycle two = cycle(overlay, scene, false, shorter, state, lifted(scene, shorter.route().getLast()
                      .coords()));
                Cycle five = cycle(overlay, scene, false, plan, state, lifted(scene, plan.route().getLast().coords()));
                assertTrue(two.travel() < five.travel(), "Two hexes take " + two.travel() + " s, five "
                      + five.travel() + " s");
                // A plan that only turns in place has no head to run, but its ghost still surges.
                GpuMovePlan.Snapshot turn = GpuBoardOverlaySmokeTest.move(ATLAS, List.of(step(scene,
                      unit(scene, ATLAS).location().coords(), 2, GpuMovePlan.Band.WALK)), List.of(), List.of(),
                      Map.of(), true);
                at(overlay, scene, false, turn, state, 0);
                assertEquals(0, pulse.length(), "No route to run");
                float turned = 0;
                for (int frame = 0; frame < 90; frame++) {
                    assertEquals(-1, pulse.head(), "No head while turning in place");
                    turned = Math.max(turned, pulse.surge());
                    pulse.advance(FRAME);
                }
                assertTrue(turned > .9f, "The turned ghost surges: " + turned);

                // A jump's head rides its arc, high over the ground halfway.
                GpuMovePlan.Snapshot jump = jump(scene);
                Coords landing = jump.route().getLast().coords();
                Cycle arc = cycle(overlay, scene, false, jump, state, lifted(scene, landing));
                ModelInstance atlas = GpuBoardOverlaySmokeTest.authored(models, atlasModel, unit(scene, ATLAS), board);
                GpuBoardOverlaySmokeTest.frame(board, false, jump.route().get(1).coords(), 150, 960, 600);
                strip(hud, board, overlay, scene, jump, state, unit -> unit.id() == ATLAS ? atlas : null,
                      arc.travel() / 2, "pulse-3d-6-jump").dispose();
                Vector3 position = new Vector3();
                assertTrue(pulse.headAt(position));
                float ground = UnitLandingSupports.surface(scene, position.x, position.y, null);
                assertTrue(position.z > ground + .8f * RADIUS, "Halfway the head is " + (position.z - ground)
                      / RADIUS + " hex radii over the ground");
            } finally {
                overlay.dispose();
                models.dispose();
                board.dispose();
            }
        });
    }

    @Test
    void nothingPulsesForAHoverPreviewABoardClickRouteAClearedSelectionOrWhenSwitchedOff() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene);
            GpuBoardOverlay overlay = new GpuBoardOverlay();
            try {
                board.units = false;
                GpuMovePlan.Snapshot plan = walkAndRun(scene);
                GpuBoardOverlaySmokeTest.frame(board, false, plan.route().get(2).coords(), 150, 960, 560);
                GpuMovePlan.Snapshot hover = GpuBoardOverlaySmokeTest.withRoute(plan, List.of(), plan.route(), true);
                GpuMovePlan.Snapshot clicks = GpuBoardOverlaySmokeTest.withRoute(plan, plan.route(), List.of(), false);
                GpuHudState cleared = GpuBoardOverlaySmokeTest.state(STATUS);
                cleared.clearSelection();
                Map<String, Runnable> cases = new LinkedHashMap<>();
                cases.put("hover", () -> show(overlay, scene, false, hover, GpuBoardOverlaySmokeTest.state(STATUS)));
                cases.put("board-clicks", () -> show(overlay, scene, false, clicks,
                      GpuBoardOverlaySmokeTest.state(STATUS)));
                cases.put("cleared", () -> show(overlay, scene, false, plan, cleared));
                cases.put("off", () -> {
                    GpuRoutePulse.enabled = false;
                    show(overlay, scene, false, plan, GpuBoardOverlaySmokeTest.state(STATUS));
                });
                for (Map.Entry<String, Runnable> entry : cases.entrySet()) {
                    String name = entry.getKey();
                    show(overlay, scene, false, GpuMovePlan.Snapshot.EMPTY, GpuBoardOverlaySmokeTest.state(STATUS));
                    entry.getValue().run();
                    GpuRoutePulse pulse = overlay.pulse();
                    board.draw(overlay::render);
                    Pixmap first = hud.captureBackBuffer("pulse-none-" + name + "-a");
                    for (int frame = 0; frame < 120; frame++) {
                        assertEquals(-1, pulse.head(), name + ": no head");
                        assertEquals(0, pulse.surge(), name + ": no surge");
                        pulse.advance(FRAME);
                    }
                    assertNull(pulse.renderable(board.camera.camera), name + ": no quads");
                    board.draw(overlay::render);
                    Pixmap later = hud.captureBackBuffer("pulse-none-" + name + "-b");
                    try {
                        for (GpuMovePlan.Step step : plan.route()) {
                            Vector2 at = board.screen(lifted(scene, step.coords()));
                            assertTrue(difference(first, later, at, 12) < .01f, name + ": the board stays still at "
                                  + step.coords());
                        }
                    } finally {
                        first.dispose();
                        later.dispose();
                        GpuRoutePulse.enabled = GpuRoutePulse.ENABLED;
                    }
                }
            } finally {
                GpuRoutePulse.enabled = GpuRoutePulse.ENABLED;
                overlay.dispose();
                board.dispose();
            }
        });
    }

    @Test
    void theTacticalViewRunsAFlatGlowAndRipplesTheRingAndTheGhostIconBrightens() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene, hud.theme.skin);
            GpuBoardOverlay overlay = new GpuBoardOverlay();
            List<Pixmap> images = new ArrayList<>();
            try {
                GpuHudState state = GpuBoardOverlaySmokeTest.state(STATUS);
                GpuMovePlan.Snapshot plan = walkAndRun(scene);
                Vector3 end = lifted(scene, plan.route().getLast().coords());
                Cycle cycle = cycle(overlay, scene, true, plan, state, end);
                GpuBoardOverlaySmokeTest.frame(board, true, plan.route().get(2).coords(), 90, 960, 540);
                float travel = cycle.travel();
                Pixmap rest = flat(hud, board, overlay, scene, plan, state, cycle.resting(),
                      "pulse-tactical-0-rest");
                Pixmap midway = flat(hud, board, overlay, scene, plan, state, travel / 2,
                      "pulse-tactical-1-midway");
                Vector3 head = new Vector3();
                assertTrue(overlay.pulse().headAt(head));
                Vector2 middle = board.screen(head);
                Pixmap ripple = flat(hud, board, overlay, scene, plan, state, travel + .25f,
                      "pulse-tactical-2-ripple");
                images.addAll(List.of(rest, midway, ripple));
                assertTrue(peak(rest, midway, middle, 3) > 60, "The flat head shines on the dashed line");
                assertTrue(ripples(board, rest, ripple, end) > 30, "The destination ring ripples out");
                // The icons and the ghost icon alone: the ghost icon brightens at the surge.
                Pixmap calm = ghostIcon(hud, board, overlay, scene, plan, state, cycle.resting(),
                      "pulse-tactical-ghost-rest");
                Pixmap surging = ghostIcon(hud, board, overlay, scene, plan, state, travel + .08f,
                      "pulse-tactical-ghost-surge");
                images.addAll(List.of(calm, surging));
                Vector2 ghost = board.screen(end);
                assertTrue(brightness(surging, ghost, 10) > brightness(calm, ghost, 10) + 8,
                      "The ghost icon brightens at the surge: " + brightness(calm, ghost, 10) + " to "
                            + brightness(surging, ghost, 10));
            } finally {
                images.forEach(Pixmap::dispose);
                overlay.dispose();
                board.dispose();
            }
        });
    }

    /**
     * The real view, through GpuLiveBoardSpaceSmokeTest's live harness: a real MovementDisplay's plan through the real
     * source into GpuBattleView, whose frames take a set time. The view's own frames move the pulse on, once a frame
     * and by the frame's time only, never rebuild the overlay for it, and draw it where the pulse puts its head.
     */
    @Test
    void theRealViewMovesThePulseOnEveryFrameWithoutRebuildingTheOverlay() throws Exception {
        Coords destination = GpuMovementFixture.START.translated(0).translated(0).translated(0);
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            GpuBoardFixture board = moving.board;
            onSwing(() -> {
                // The fixture's Atlas has moved, so the HUD's focus is the Sagittaire the display plans for.
                board.entity.setDone(true);
                board.panel = moving.display;
                board.source.setVisibleArea(new Rectangle(0, 0, 16, 17));
                board.source.refresh();
                return null;
            });
            board.source.moves().planTo(destination, 0, false);
            GpuLiveBoardSpaceSmokeTest.settle();
            GpuLiveBoardSpaceSmokeTest.run(board.source, live -> {
                live.publish();
                live.draw(2, 0);
                live.zoom(GpuMovementFixture.START.translated(0), 120);
                GpuRoutePulse pulse = live.overlay.pulse();
                assertTrue(pulse.length() > 0, "The real plan's route pulses");
                long builds = live.overlay.builds();
                assertEquals(0, pulse.head(), "Frames in which no time passes leave the head at the unit");
                live.draw(1, FRAME);
                float one = pulse.head();
                assertTrue(one > 0, "A frame of the view moves the head on");
                live.draw(1, 0);
                assertEquals(one, pulse.head(), "and a frame without time leaves it");
                live.draw(18, FRAME);
                Vector3 head = new Vector3();
                assertTrue(pulse.headAt(head), "Under way");
                Pixmap travelling = live.board();
                live.capture("pulse-live-3d.png");
                for (int frame = 0; frame < 300 && (pulse.head() >= 0 || pulse.surge() > 0); frame++) {
                    live.draw(1, FRAME);
                }
                Pixmap resting = live.board();
                try {
                    Vector2 at = live.stage(head);
                    // The centre can match an already-saturated path; the surrounding glow must still change.
                    assertTrue(peak(travelling, resting, at, 12) > 60,
                          "The coloured head's glow must remain visible over the resting path");
                } finally {
                    travelling.dispose();
                    resting.dispose();
                }
                assertEquals(builds, live.overlay.builds(), "The view's frames never rebuild the overlay");
            });
        }
    }

    // Plans.

    /**
     * The Atlas walks two hexes north, then runs north-east twice and north once, through a waypoint where it starts
     * running: five hexes.
     */
    private static GpuMovePlan.Snapshot walkAndRun(BoardScene scene) {
        Coords from = unit(scene, ATLAS).location().coords();
        Coords first = from.translated(0);
        Coords second = first.translated(0);
        Coords third = second.translated(1);
        Coords fourth = third.translated(1);
        Coords fifth = fourth.translated(0);
        List<GpuMovePlan.Step> route = List.of(step(scene, first, 0, GpuMovePlan.Band.WALK),
              step(scene, second, 0, GpuMovePlan.Band.WALK), step(scene, third, 1, GpuMovePlan.Band.RUN),
              step(scene, fourth, 1, GpuMovePlan.Band.RUN), step(scene, fifth, 0, GpuMovePlan.Band.RUN));
        return GpuBoardOverlaySmokeTest.move(ATLAS, route, List.of(), List.of(route.get(1)),
              GpuBoardOverlaySmokeTest.envelope(scene, from, 3, 5), true);
    }

    /** The Atlas jumps three hexes north-west, over the hill beside it: one arc. */
    private static GpuMovePlan.Snapshot jump(BoardScene scene) {
        Coords first = unit(scene, ATLAS).location().coords().translated(5);
        Coords second = first.translated(5);
        Coords landing = second.translated(5);
        return GpuBoardOverlaySmokeTest.move(ATLAS, List.of(step(scene, first, 5, GpuMovePlan.Band.JUMP),
              step(scene, second, 5, GpuMovePlan.Band.JUMP), step(scene, landing, 5, GpuMovePlan.Band.JUMP)),
              List.of(), List.of(), Map.of(), true);
    }

    // Time.

    /** Hands the overlay the fixture's movement turn with this plan, as GpuBattleView does once a frame. */
    private static void show(GpuBoardOverlay overlay, BoardScene scene, boolean tactical, GpuMovePlan.Snapshot plan,
          GpuHudState state) {
        show(overlay, scene, tactical, plan, state, Entity.NONE);
    }

    private static void show(GpuBoardOverlay overlay, BoardScene scene, boolean tactical, GpuMovePlan.Snapshot plan,
          GpuHudState state, int hoveredUnit) {
        overlay.update(GpuBoardOverlaySmokeTest.frame(scene, STATUS,
                    GpuBoardOverlaySmokeTest.panels(plan, GpuFireOrders.Snapshot.EMPTY)),
              GpuBoardOverlaySmokeTest.view(tactical, null, hoveredUnit), GpuBoardOverlaySmokeTest.preferences(),
              state);
    }

    /** Shows the plan anew, so that its pulse leaves the unit, and lets {@code seconds} pass in frames. */
    private static void at(GpuBoardOverlay overlay, BoardScene scene, boolean tactical, GpuMovePlan.Snapshot plan,
          GpuHudState state, float seconds) {
        show(overlay, scene, tactical, GpuMovePlan.Snapshot.EMPTY, state);
        show(overlay, scene, tactical, plan, state);
        for (int frame = Math.round(seconds / FRAME); frame > 0; frame--) {
            overlay.pulse().advance(FRAME);
        }
    }

    /**
     * One pulse of the plan from its start, frame by frame, the overlay updated every frame as the view does it. The
     * head lands at the destination {@code end}, and nothing is rebuilt meanwhile.
     */
    private static Cycle cycle(GpuBoardOverlay overlay, BoardScene scene, boolean tactical, GpuMovePlan.Snapshot plan,
          GpuHudState state, Vector3 end) {
        at(overlay, scene, tactical, plan, state, 0);
        GpuRoutePulse pulse = overlay.pulse();
        long builds = overlay.builds();
        Vector3 position = new Vector3();
        List<Float> heads = new ArrayList<>();
        List<Float> surges = new ArrayList<>();
        int landed = -1;
        int calm = -1;
        for (int frame = 0; frame < 600; frame++) {
            float head = pulse.head();
            heads.add(head);
            surges.add(pulse.surge());
            if (landed < 0) {
                if (head >= 0) {
                    assertTrue(pulse.headAt(position));
                } else {
                    landed = frame;
                    assertTrue(Vector2.dst(end.x, end.y, position.x, position.y) < .1f * RADIUS, "The head lands at "
                          + "the destination, was " + Vector2.dst(end.x, end.y, position.x, position.y) / RADIUS
                          + " hex radii off");
                }
            } else if (calm < 0) {
                if (surges.get(frame) == 0 && surges.get(frame - 1) > 0) {
                    calm = frame;
                }
            } else if (head >= 0) {
                assertEquals(builds, overlay.builds(), "Time alone never rebuilds the overlay's meshes");
                return new Cycle(heads, surges, landed, calm, frame);
            }
            pulse.advance(FRAME);
            show(overlay, scene, tactical, plan, state);
        }
        throw new AssertionError("The pulse never came round again: landed " + landed + ", calm " + calm);
    }

    // Pictures.

    /** The board, the ghost and the overlay as GpuBattleView draws them in 3D, {@code seconds} into the pulse. */
    private static Pixmap strip(GpuHudTestStage hud, GpuBoardSpaceHarness board, GpuBoardOverlay overlay,
          BoardScene scene, GpuMovePlan.Snapshot plan, GpuHudState state,
          Function<BoardScene.Unit, ModelInstance> shown, float seconds, String name) {
        at(overlay, scene, false, plan, state, seconds);
        board.draw(camera -> {
            overlay.renderGhost(camera, shown);
            overlay.render(camera);
        });
        return hud.captureBackBuffer(name);
    }

    /** The board and the ghost alone, {@code seconds} into the pulse. */
    private static Pixmap ghost(GpuHudTestStage hud, GpuBoardSpaceHarness board, GpuBoardOverlay overlay,
          BoardScene scene, GpuMovePlan.Snapshot plan, GpuHudState state,
          Function<BoardScene.Unit, ModelInstance> shown, float seconds, String name) {
        at(overlay, scene, false, plan, state, seconds);
        board.draw(camera -> overlay.renderGhost(camera, shown));
        return hud.captureBackBuffer(name);
    }

    /** The Tactical View: the overlay, the meeples on their bases and the ghost, {@code seconds} into the pulse. */
    private static Pixmap flat(GpuHudTestStage hud, GpuBoardSpaceHarness board, GpuBoardOverlay overlay,
          BoardScene scene, GpuMovePlan.Snapshot plan, GpuHudState state, float seconds, String name) {
        at(overlay, scene, true, plan, state, seconds);
        board.basesStatus = STATUS;
        board.draw(camera -> {
            overlay.render(camera);
            overlay.renderGhost(camera, board.instances::get);
        });
        return hud.captureBackBuffer(name);
    }

    /** The Tactical View's meeples and the ghost alone, {@code seconds} into the pulse. */
    private static Pixmap ghostIcon(GpuHudTestStage hud, GpuBoardSpaceHarness board, GpuBoardOverlay overlay,
          BoardScene scene, GpuMovePlan.Snapshot plan, GpuHudState state, float seconds, String name) {
        at(overlay, scene, true, plan, state, seconds);
        board.basesStatus = STATUS;
        board.draw(camera -> overlay.renderGhost(camera, board.instances::get));
        return hud.captureBackBuffer(name);
    }

    /** A hex's centre a little over its level, where the route's marks lie. */
    private static Vector3 lifted(BoardScene scene, Coords coords) {
        return GpuBoardOverlaySmokeTest.ground(scene, coords).add(0, 0, .07f * RADIUS);
    }

    /** The largest change between the two pictures around the destination, 1.2 to 1.9 hex radii out. */
    private static float ripples(GpuBoardSpaceHarness board, Pixmap before, Pixmap after, Vector3 destination) {
        float largest = 0;
        for (int direction = 0; direction < 12; direction++) {
            double angle = Math.PI * 2 * direction / 12;
            for (float reach = 1.2f; reach <= 1.9f; reach += .1f) {
                Vector2 at = board.screen(new Vector3(destination).add((float) Math.cos(angle) * reach * RADIUS,
                      (float) Math.sin(angle) * reach * RADIUS, 0));
                largest = Math.max(largest, peak(before, after, at, 1));
            }
        }
        return largest;
    }

    /** The mean of the red, green and blue channels within {@code half} texels of a point, 0 to 255. */
    private static float brightness(Pixmap image, Vector2 point, int half) {
        float total = 0;
        for (int dy = -half; dy <= half; dy++) {
            for (int dx = -half; dx <= half; dx++) {
                int pixel = image.getPixel(Math.round(point.x) + dx, Math.round(point.y) + dy);
                total += (pixel >>> 24) + (pixel >>> 16 & 0xFF) + (pixel >>> 8 & 0xFF);
            }
        }
        return total / (3 * (2 * half + 1) * (2 * half + 1));
    }

    // Cost.

    /**
     * The pulse's cost on this machine, while the head travels and while the ring ripples: the overlay's draw with and
     * without the pulse on alternate frames, in GPU time (timestamp queries) and submission time, and the filling and
     * upload of the pulse's quads. Written to pulse-cost.txt with the GL renderer. Not a claim beyond this run: JaCoCo
     * instruments the smoke run's CPU work.
     */
    private static void measure(GpuBoardSpaceHarness board, GpuBoardOverlay overlay, BoardScene scene,
          GpuMovePlan.Snapshot plan, GpuHudState state, float travel) throws Exception {
        StringBuilder report = new StringBuilder("GL renderer: " + Gdx.gl.glGetString(GL20.GL_RENDERER) + "\n");
        report.append("The overlay's draw (its static meshes, and the pulse on alternate frames) over the drawn board, ")
              .append(Gdx.graphics.getBackBufferWidth()).append(" x ").append(Gdx.graphics.getBackBufferHeight())
              .append(" back buffer; 30 warm-up frames, 200 measured; glFinish after each frame, outside the span.\n");
        for (Map.Entry<String, Float> moment : List.of(Map.entry("travelling", travel / 2),
              Map.entry("rippling", travel + .2f))) {
            at(overlay, scene, false, plan, state, moment.getValue());
            board.draw(camera -> { });
            Renderable quads = overlay.pulse().renderable(board.camera.camera);
            assertNotNull(quads, moment.getKey());
            report.append(moment.getKey()).append(": ").append(quads.meshPart.size / 6).append(" quads\n");
            try (GpuStageTimings timings = new GpuStageTimings()) {
                for (int frame = 0; frame < 230; frame++) {
                    boolean on = frame % 2 == 0;
                    GpuRoutePulse.enabled = on;
                    if (frame >= 30) {
                        timings.beginFrame();
                        timings.stage(on ? "overlay with the pulse" : "overlay without the pulse");
                    }
                    overlay.render(board.camera.camera);
                    if (frame >= 30) {
                        timings.stage(null);
                    }
                    Gdx.gl.glFinish();
                }
                timings.appendReport(report, moment.getKey());
            } finally {
                GpuRoutePulse.enabled = GpuRoutePulse.ENABLED;
            }
            List<Long> fills = new ArrayList<>();
            for (int frame = 0; frame < 1000; frame++) {
                long begin = System.nanoTime();
                overlay.pulse().renderable(board.camera.camera);
                fills.add(System.nanoTime() - begin);
            }
            fills.sort(Long::compare);
            report.append(String.format(Locale.ROOT, "%s: the pulse's quads filled and uploaded, median %.4f ms,"
                  + " p95 %.4f ms (CPU)%n", moment.getKey(), fills.get(500) / 1e6, fills.get(950) / 1e6));
        }
        System.out.print(report);
        Files.writeString(new File(UiTestStage.OUTPUT, "pulse-cost.txt").toPath(), report.toString(),
              StandardCharsets.UTF_8);
    }
}
