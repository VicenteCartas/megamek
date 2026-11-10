/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.UnitAnnotations;
import megamek.client.ui.gdx.DisplayScale;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.Player;
import megamek.common.ResolvedAttack;
import megamek.common.actions.WeaponAttackAction;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Mek;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The board-space components in the live battle view (rebuild plan I1b): a real GpuBattleView draws the frames a real
 * source captures from real phase displays, published as the local player's turn (the fixtures' board state has no
 * client to tell), in a 1920 x 1080 window with one HUD unit per window pixel. Movement: after planTo, the overlay's
 * route and ghost, the destination tip and the waypoint number, the nameplates and pips at the units' heads
 * in both views, the fire preview's badge and guides, the Tactical View's north mark, and no overlay rebuild across
 * unchanged recaptures. Firing: the selected weapon's badges, one trace for each attacker and target (none for a
 * read-only draft, no line while an attack plays), the leaders to the placed target cards, the weapon's arc and the
 * front arc. Physical: the ringed neighbours and the target's bold icon. A fourth test times the board-space HUD at
 * 100 v 100.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Tag("on-demand")
class GpuLiveBoardSpaceSmokeTest {
    /** The movement fixture's Sagittaire (walk 3, run 5) pins a waypoint north of it and runs on to the road. */
    private static final Coords WAYPOINT = new Coords(11, 10);
    /** The minimap's planned-route colour. */
    private static final Color ROUTE = Color.valueOf("D4FFF0");
    private static final Coords DESTINATION = new Coords(11, 7);
    private static final int ATLAS = 1;
    private static final int SAGITTAIRE = 2;
    /** The identified enemy of the movement scene, in the open three hexes east, for the fire preview. */
    private static final int ARCHER = 42;
    private static final Coords ARCHER_HEX = new Coords(14, 11);
    /** The firing fixture's Atlas faces north at (5, 5); its enemies: the Archer ahead, the Hachiwara left, more. */
    private static final Coords ATLAS_HEX = new Coords(5, 5);
    private static final int HACHIWARA = 43;
    private static final int CRAB = 44;
    private static final int QUICKDRAW = 45;
    /** The view's render stage that hands the HUD and the board overlay their frame. */
    private static final String STAGE = "board-space HUD";

    @Test
    void aPlannedRouteShowsTheOverlayLabelsAndPlatesInBothViews() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            plan(moving);
            moving.board.source.moves().planTo(WAYPOINT, 0, true);
            settle();
            moving.board.source.moves().planTo(DESTINATION, 0, false);
            settle();
            awaitPreview(moving.board.source);
            run(moving.board.source, live -> {
                live.publish();
                live.draw(20, .1f);
                GpuMovePlan.Snapshot move = live.frame.get().panels().move();
                assertEquals(List.of(WAYPOINT), move.pins().stream().map(GpuMovePlan.Step::coords).toList());
                assertEquals(DESTINATION, move.destination());
                assertFalse(move.envelope().isEmpty(), "The plan has its envelope");
                assertSame(move, GpuTestSupport.field(live.overlay, "move"), "The overlay draws the published plan");
                verifyMovement3d(live, move, moving.unit);
                verifyUnchangedRecaptures(live, 60);

                live.view.setTacticalView(true);
                live.draw(5, .1f);
                verifyMovementTactical(live);
                verifyUnchangedRecaptures(live, 20);
                live.view.setTacticalView(false);
                verifyMinimapFollowsPlayback(live);
            });
        }
    }

    /** The 3D view: heads, plates, tip, pin, guides and badge; then the overlay alone on the board. */
    private static void verifyMovement3d(Live live, GpuMovePlan.Snapshot move, Entity planner) throws Exception {
        live.zoom(DESTINATION, 120);
        live.capture("i1b-movement-3d.png");
        // For the review: the overlay without MegaMek's own sprites.
        GpuBoardSource.Frame published = live.frame.get();
        live.show(live.overlayOnly(move));
        live.capture("i1b-movement-3d-overlay-only.png");
        live.show(published);
        GpuHud.HudView view = live.hudView();
        assertEquals(120, view.hexPixels(), .01f, "A hex's width on the screen, in HUD units");
        for (int id : List.of(SAGITTAIRE, ATLAS, ARCHER)) {
            verifyModelHead(live, view, id);
        }
        Actor focus = live.actor("nameplate-" + SAGITTAIRE);
        // A tag names its unit as the client's label style does (GpuNameplates).
        assertEquals(UnitAnnotations.labelName(planner, GUIPreferences.getInstance().getUnitLabelStyle()),
              GpuBoardTestUi.texts(focus).getFirst(), "The planning unit's tag");
        verifyPlate(live, view, "nameplate-" + SAGITTAIRE, SAGITTAIRE);
        verifyPlate(live, view, "pip-" + ATLAS, ATLAS);
        verifyPlate(live, view, "pip-" + ARCHER, ARCHER);
        assertEquals(.45f, live.actor("pip-" + ATLAS).getColor().a, .001f, "The moved Atlas's pip fades");

        Actor tip = live.actor("route-tip");
        assertTrue(GpuBoardTestUi.shown(tip), "The destination tip");
        assertTrue(GpuBoardTestUi.texts(tip).getFirst().contains(move.cost() + " / " + move.budget()),
              () -> "The tip names the route's cost: " + GpuBoardTestUi.texts(tip));
        Vector2 destination = live.hex(DESTINATION);
        Vector2 corner = tip.localToStageCoordinates(new Vector2());
        assertTrue(corner.x > destination.x || corner.x + tip.getWidth() < destination.x,
              "The tip stands beside the destination");
        assertTrue(corner.y > destination.y && corner.y < destination.y + 200, "The tip hangs above it");
        Actor pin = live.actor("pin-1");
        assertTrue(GpuBoardTestUi.shown(pin), "The waypoint's number");
        Vector2 waypoint = live.hex(WAYPOINT);
        Vector2 pinCentre = pin.localToStageCoordinates(new Vector2(pin.getWidth() / 2, pin.getHeight() / 2));
        assertEquals(waypoint.x, pinCentre.x, 1.5f, "The number stands over its waypoint");
        assertEquals(waypoint.y + 22, pinCentre.y, 1.5f, "22 units above its hex");
        assertFalse(GpuBoardTestUi.shown(live.hud.stage.getRoot().findActor("pin-2")), "One waypoint, one number");

        GpuFirePreview.Snapshot preview = live.frame.get().panels().preview();
        assertTrue(preview.active() && preview.unitId() == SAGITTAIRE, "The Sagittaire's fire preview");
        assertTrue(live.strokes() > 0, "The preview's guides lie in the fx layer");
        assertNotNull(live.badgeOn(ARCHER, view), "The Archer's TN badge, 4 over its nameplate");
        // A larger HUD keeps the plates on the units: the heads are in stage units.
        GpuBoardSource.UiPreferences preferences = live.source.uiPreferences;
        live.source.uiPreferences = scaled(preferences, 1.5f);
        live.draw(3, 0);
        assertEquals(1920 / 1.5f, live.hud.stage.getWidth(), 1, "The HUD at 1.5 window pixels per unit");
        verifyModelHead(live, live.hudView(), SAGITTAIRE);
        verifyPlate(live, live.hudView(), "nameplate-" + SAGITTAIRE, SAGITTAIRE);
        live.source.uiPreferences = preferences;
        live.draw(3, 0);

        // The overlay alone: the published plan against the same frame without it, seen from straight above.
        live.camera().orbit(0, -BoardCamera.MAX_TILT);
        live.zoom(new Coords(12, 9), 160);
        live.show(live.overlayOnly(move));
        Pixmap planned = live.board();
        live.show(live.overlayOnly(GpuMovePlan.Snapshot.EMPTY));
        Pixmap idle = live.board();
        try {
            for (Coords dot : List.of(new Coords(11, 9), new Coords(11, 8))) {
                assertTrue(live.difference(planned, idle, dot) > 60, dot + ": the route's dot");
            }
            assertTrue(live.difference(planned, idle, DESTINATION) > 30, "The ghost stands at the destination");
        } finally {
            planned.dispose();
            idle.dispose();
        }
        live.publish();
        live.draw(2, 0);
        assertSame(live.model(SAGITTAIRE), GpuTestSupport.field(live.overlay, "ghostSource"), "The ghost copies the shown model");
        Vector3 at = ((ModelInstance) GpuTestSupport.field(live.overlay, "ghost")).transform.getTranslation(new Vector3());
        Vector3 centre = BoardGeometry.center(DESTINATION, 0);
        assertEquals(centre.x, at.x, .5f, "The ghost stands at the route's end");
        assertEquals(centre.y, at.y, .5f);
        live.camera().setIsometric(true);
        live.camera().fit(live.scene());
        live.draw(3, 0);
    }

    /** The Tactical View: the north mark, the units' bases and their heads, the meeple ghost and the overlay. */
    private static void verifyMovementTactical(Live live) throws Exception {
        live.zoom(new Coords(12, 9), 90);
        live.capture("i1b-movement-tactical.png");
        Actor north = live.actor("tactical-north");
        assertTrue(GpuBoardTestUi.shown(north), "The Tactical View's north mark");
        Vector2 corner = north.localToStageCoordinates(new Vector2());
        assertEquals(live.hud.stage.getWidth() / 2, corner.x + north.getWidth() / 2, 1, "Centred in the window");
        assertEquals(GpuUtilityBar.NORTH_TOP, live.hud.stage.getHeight() - corner.y - north.getHeight(), 1);
        GpuHud.HudView view = live.hudView();
        assertTrue(view.tactical());
        for (int id : List.of(SAGITTAIRE, ATLAS, ARCHER)) {
            ModelInstance base = live.bases().base(live.unit(id));
            Vector2 centre = live.stage(base.transform.getTranslation(new Vector3()));
            float side = live.stage(new Vector3(-GpuUnitBases.RING, 0, 0).mul(base.transform))
                  .dst(live.stage(new Vector3(GpuUnitBases.RING, 0, 0).mul(base.transform)));
            Vector2 head = view.unitHeads().get(id);
            assertEquals(centre.x, head.x, .5f, id + ": the head straight above the base");
            assertEquals(centre.y + side / 2, head.y, .5f, id + ": at the base ring's north point");
            assertEquals(side, view.unitRects().get(id).width, .5f, id + ": its rectangle is the ring's square");
        }
        verifyPlate(live, view, "nameplate-" + SAGITTAIRE, SAGITTAIRE);
        assertSame(live.model(SAGITTAIRE), GpuTestSupport.field(live.overlay, "ghostSource"),
              "The Tactical View's ghost copies the unit's meeple");
        // The terrain's top artwork draws the elevation-drop edges; the overlay adds none (GpuBoardOverlaySmokeTest).
        live.show(live.overlayOnly(live.frame.get().panels().move()));
        Pixmap planned = live.board();
        live.show(live.overlayOnly(GpuMovePlan.Snapshot.EMPTY));
        Pixmap idle = live.board();
        try {
            assertTrue(live.difference(planned, idle, DESTINATION) > 20, "The ghost icon at the destination");
        } finally {
            planned.dispose();
            idle.dispose();
        }
        live.publish();
        live.draw(2, 0);
    }

    /**
     * W4: while a move plays, the minimap draws the unit where the view shows it, the animated board position the
     * HudView carries, not at the hex it left nor the one it moves to. The Archer walks four hexes north.
     */
    private static void verifyMinimapFollowsPlayback(Live live) throws Exception {
        GpuBoardSource.Frame base = live.frame.get();
        BoardScene scene = base.scene();
        BoardScene.Unit archer = scene.units().stream().filter(unit -> unit.id() == ARCHER).findFirst()
              .orElseThrow();
        List<BoardScene.Waypoint> path = new ArrayList<>();
        for (int y = ARCHER_HEX.getY(); y >= ARCHER_HEX.getY() - 4; y--) {
            Coords coords = new Coords(ARCHER_HEX.getX(), y);
            path.add(new BoardScene.Waypoint(coords, scene.tile(coords).elevation(), 0));
        }
        BoardScene.Unit moved = new BoardScene.Unit(ARCHER, archer.part(), archer.name(), path.getLast(),
              archer.image(), false, archer.annotations(), archer.height(), false, archer.model(),
              archer.outlineRgb(), List.of(path.getLast().coords()));
        BoardScene after = scene.withUnits(scene.units().stream().map(unit -> unit.id() == ARCHER ? moved : unit)
              .toList());
        GpuBoardSource.Frame arrived = Live.copy(base, after, base.status(), base.panels());
        live.frame.set(arrived.withTimeline(List.of(new BoardScene.Movement(ARCHER, after.boardId(), path,
              EntityMovementType.MOVE_WALK, 0, 4, moved))));
        live.draw(1, 0);
        live.frame.set(arrived.withTimeline(List.of()));
        Vector3 start = BoardGeometry.center(path.getFirst().coords(), 0);
        Vector3 end = BoardGeometry.center(path.getLast().coords(), 0);
        Vector2 shown = null;
        for (int frame = 0; frame < 300 && shown == null; frame++) {
            live.draw(1, 1 / 30f);
            Vector2 position = live.hudView().unitPositions().get(ARCHER);
            if (position.dst(start.x, start.y) > 1.5f * BoardGeometry.HEIGHT
                  && position.dst(end.x, end.y) > 1.5f * BoardGeometry.HEIGHT) {
                shown = position;
            }
        }
        assertNotNull(shown, "The Archer walks between its hexes");
        Minimap minimap = live.minimap();
        Vector3 from = BoardGeometry.center(live.unit(SAGITTAIRE).location().coords(), 0);
        Vector3 to = BoardGeometry.center(WAYPOINT, 0);
        float routeX = (from.x + to.x) / 2;
        float routeY = (from.y + to.y) / 2;
        Pixmap window = live.window();
        try {
            assertEquals(shown, live.hudView().unitPositions().get(ARCHER), "No time passed");
            int moving = minimap.pixel(window, shown.x, shown.y);
            assertTrue(shows(moving, UiTheme.CORAL, 32), () -> "The enemy's square where the view shows it, was "
                  + Integer.toHexString(moving));
            for (Vector3 hex : List.of(start, end)) {
                int left = minimap.pixel(window, hex.x, hex.y);
                assertFalse(shows(left, UiTheme.CORAL, 32), "No square at its hexes: " + Integer.toHexString(left));
            }
            // While a move plays, the HUD holds the played unit's state and hides the plan (GpuHudState.presented).
            int hidden = minimap.pixel(window, routeX, routeY);
            assertFalse(shows(hidden, ROUTE, 40), () -> "No route while a move plays, was " + Integer.toHexString(hidden));
        } finally {
            window.dispose();
        }
        live.publish();
        live.draw(60, .1f);
        // Once it has played, the planned route's line starts at the planning unit, whose own hex the route leaves out
        // (E2b).
        Pixmap settled = live.window();
        try {
            int route = minimap.pixel(settled, routeX, routeY);
            assertTrue(shows(route, ROUTE, 40), () -> "The route's first leg, was " + Integer.toHexString(route));
        } finally {
            settled.dispose();
        }
    }

    /** {@code preferences} at {@code factor} times their HUD scale. */
    private static GpuBoardSource.UiPreferences scaled(GpuBoardSource.UiPreferences preferences, float factor) {
        return GpuHudFixtures.with(preferences, Map.of("scale", preferences.scale() * factor));
    }

    /** A 3D head is the top of the unit's model above its middle, in stage units, y up (C.4 HudView). */
    private static void verifyModelHead(Live live, GpuHud.HudView view, int id) {
        BoundingBox bounds = UnitBounds.world(live.model(id));
        float top = -Float.MAX_VALUE;
        for (float x : new float[] { bounds.min.x, bounds.max.x }) {
            for (float y : new float[] { bounds.min.y, bounds.max.y }) {
                for (float z : new float[] { bounds.min.z, bounds.max.z }) {
                    top = Math.max(top, live.stage(new Vector3(x, y, z)).y);
                }
            }
        }
        Vector2 middle = live.stage(live.model(id).transform.getTranslation(new Vector3()));
        Vector2 head = view.unitHeads().get(id);
        assertNotNull(head, id + " has a head");
        assertEquals(middle.x, head.x, 1, id + ": the head straight above the model");
        assertEquals(top, head.y, 2, id + ": the head at the model's top");
        com.badlogic.gdx.math.Rectangle rect = view.unitRects().get(id);
        assertEquals(head.y, rect.y + rect.height, .5f, id + ": its rectangle reaches up to the head");
        assertEquals(.55f * view.hexPixels(), rect.width, 1, id + ": and is .55 of a hex wide");
        assertEquals(head.x, rect.x + rect.width / 2, 1, id + ": around the model's middle");
    }

    /**
     * The plate {@code name} stands bottom-centred on unit {@code id}'s head (G8a); while the unit's marks show, they
     * stand on the head and the plate {@code GpuNameplates.MARKS_GAP} above them (GpuNameplates.Plate#place).
     */
    private static void verifyPlate(Live live, GpuHud.HudView view, String name, int id)
          throws ReflectiveOperationException {
        Actor plate = live.actor(name);
        assertTrue(GpuBoardTestUi.shown(plate), name + " is shown");
        Vector2 corner = plate.localToStageCoordinates(new Vector2());
        Vector2 head = view.unitHeads().get(id);
        assertEquals(head.x, corner.x + plate.getWidth() / 2, 1, name + ": centred on the head");
        Actor marks = live.hud.stage.getRoot().findActor("marks-" + id);
        float bottom = head.y;
        if (GpuBoardTestUi.shown(marks)) {
            assertEquals(head.y, marks.localToStageCoordinates(new Vector2()).y, 1, id + ": its marks on the head");
            var gap = GpuNameplates.class.getDeclaredField("MARKS_GAP");
            gap.setAccessible(true);
            bottom += marks.getHeight() + gap.getFloat(null);
        }
        assertEquals(bottom, corner.y, 1, name + ": standing on the head, above the marks");
    }

    /** The overlay keeps its meshes over {@code frames} recaptures that show the same (G7). */
    private static void verifyUnchangedRecaptures(Live live, int frames) throws Exception {
        for (int index = 0; index < 3; index++) {
            // Jobs still running publish their last results first.
            live.publish();
            live.draw(2, 0);
        }
        long builds = live.overlay.builds();
        BoardScene first = live.frame.get().scene();
        for (int index = 0; index < frames; index++) {
            live.publish();
            live.draw(1, 0);
        }
        assertTrue(live.frame.get().scene() != first, "The source captured new scenes");
        assertEquals(builds, live.overlay.builds(), "No overlay rebuild across " + frames + " unchanged frames");
    }

    @Test
    void aDeclarationShowsBadgesOneTraceEachLeadersAndTheArcs() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            GpuBoardFixture board = firing.board;
            GpuFiringFixture.enemy(firing, "Crab CRB-20.mtf", CRAB, GpuFiringFixture.NORTH);
            Entity quickdraw = GpuFiringFixture.enemy(firing, "Quickdraw QKD-8X.mtf", QUICKDRAW,
                  GpuFiringFixture.EAST);
            int cannon = GpuFiringFixture.eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
            int left = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_LEFT_ARM);
            int right = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_RIGHT_ARM);
            GpuFiringFixture.command(firing, fire -> fire.assign(cannon, TargetKey.unit(firing.ahead.getId())));
            GpuFiringFixture.command(firing, fire -> fire.assign(left, TargetKey.unit(CRAB)));
            GpuFiringFixture.command(firing, fire -> fire.selectWeapon(right));
            onSwing(() -> {
                // The Quickdraw's declared attack on the Atlas, then the board's attacks as the client refreshes them.
                board.game.addAction(new WeaponAttackAction(QUICKDRAW, ATLAS,
                      quickdraw.getEquipmentNum(quickdraw.getWeaponList().getFirst())));
                board.view.refreshAttacks();
                board.source.setVisibleArea(new Rectangle(0, 0, 16, 17));
                return null;
            });
            settle();
            run(board.source, live -> {
                live.publish();
                live.draw(20, .1f);
                GpuFireOrders.Snapshot fire = live.frame.get().panels().fire();
                assertEquals(List.of(TargetKey.unit(firing.ahead.getId()), TargetKey.unit(CRAB)),
                      fire.targets().stream().map(GpuFireOrders.Target::key).toList());
                assertEquals(right, fire.selectedWeapon());
                live.zoom(new Coords(6, 4), 130);
                live.capture("i1b-firing-3d.png");
                verifyTraces(live, fire);
                verifyBadgesAndPlates(live, fire);
                verifyLeaders(live);
                verifyUnchangedRecaptures(live, 60);
                verifyReadOnlyDraftAndPlayback(live, fire);
                verifyArcs(live, firing, right);
                live.view.setTacticalView(true);
                live.zoom(new Coords(6, 4), 90);
                live.capture("i1b-firing-tactical.png");
                assertEquals(fire.targets().size(), live.strokes(), "The Tactical View traces each target too");
            });
        }
    }

    /**
     * One trace for each attacker and target (rebuild plan H32): the board labels trace the actor's targets, the fire
     * control draws every other line, in the attacker's side colour.
     */
    private static void verifyTraces(Live live, GpuFireOrders.Snapshot fire) throws Exception {
        Set<String> captured = live.scene().firingLines().stream().map(GpuLiveBoardSpaceSmokeTest::pair)
              .collect(Collectors.toSet());
        assertEquals(Set.of(ATLAS + ">" + fire.targets().getFirst().key().id(), ATLAS + ">" + CRAB,
              QUICKDRAW + ">" + ATLAS), captured, "The board shows the actor's queued attacks and the Quickdraw's");
        List<BoardScene.FiringLine> drawn = live.firingLines();
        assertEquals(List.of(QUICKDRAW + ">" + ATLAS), pairs(drawn),
              "The fire control leaves the actor's lines to the traces");
        assertEquals(Color.rgb888(UiTheme.CORAL), drawn.getFirst().rgb(), "An enemy's line is coral");
        assertEquals(fire.targets().size(), live.strokes(), "One trace for each of the actor's targets");
    }

    /**
     * T2's other cases: a read-only draft (G6b: the focus unit's draft on another player's turn) has no targets, so
     * the labels trace nothing and the fire control draws every line; and every line hides while an attack plays.
     */
    private static void verifyReadOnlyDraftAndPlayback(Live live, GpuFireOrders.Snapshot fire) throws Exception {
        GpuBoardSource.Frame published = live.frame.get();
        GpuHudData p = published.panels();
        GpuFireOrders.Snapshot draft = new GpuFireOrders.Snapshot(true, false, fire.actorId(),
              GpuFireOrders.Focus.NONE, -1, List.of(), List.of(), fire.attacks(), 0, false, false, "", null, null, null,
              List.of(), null, Map.of(), 0, 0, null);
        live.show(Live.copy(published, published.scene(), published.status(), new GpuHudData(p.phase(), p.move(),
              draft, p.physical(), p.record(), p.preview(), p.chat(), p.toasts(), p.los(), p.players())));
        assertEquals(0, live.strokes(), "A read-only draft has no traces");
        assertEquals(pairs(live.scene().firingLines()), pairs(live.firingLines()),
              "Without targets, the fire control draws every line");
        live.publish();
        live.draw(2, 0);
        GpuBoardSource.Frame base = live.frame.get();
        live.frame.set(base.withTimeline(List.of(UnitPlaybackFixture.attack(live.unit(QUICKDRAW), live.unit(ATLAS),
              ResolvedAttack.Kind.SHOT, true))));
        live.draw(1, 0);
        live.frame.set(base.withTimeline(List.of()));
        UnitPlayback playback = (UnitPlayback) GpuTestSupport.field(live.view, "playback");
        assertFalse(playback.attacks().isEmpty(), "The Quickdraw's shot plays");
        assertEquals(List.of(), live.firingLines(), "Every line hides while an attack plays");
        for (int frame = 0; frame < 300 && !playback.attacks().isEmpty(); frame++) {
            live.draw(1, .1f);
        }
        assertTrue(playback.attacks().isEmpty(), "The shot ends");
        assertEquals(List.of(QUICKDRAW + ">" + ATLAS), pairs(live.firingLines()), "The lines return after it");
        live.draw(30, .1f);
    }

    private static List<String> pairs(List<BoardScene.FiringLine> lines) {
        return lines.stream().map(GpuLiveBoardSpaceSmokeTest::pair).toList();
    }

    private static String pair(BoardScene.FiringLine line) {
        return line.attackerId() + ">" + line.targetKey().id();
    }

    /** The selected weapon's badges on the enemies without a card; the carded units have no plate. */
    private static void verifyBadgesAndPlates(Live live, GpuFireOrders.Snapshot fire) throws Exception {
        GpuHud.HudView view = live.hudView();
        assertEquals(Set.of(QUICKDRAW, HACHIWARA), fire.badges().stream().map(GpuFireOrders.Badge::targetId)
              .collect(Collectors.toSet()), "Badges on the other enemies in range");
        for (GpuFireOrders.Badge badge : fire.badges()) {
            assertNotNull(live.badgeOn(badge.targetId(), view), badge.targetId() + "'s badge");
        }
        for (GpuFireOrders.Target target : fire.targets()) {
            Group root = live.hud.stage.getRoot();
            assertFalse(GpuBoardTestUi.shown(root.findActor("pip-" + target.key().id())), "A card replaces the plate");
            assertFalse(GpuBoardTestUi.shown(root.findActor("nameplate-" + target.key().id())));
        }
        verifyPlate(live, view, "pip-" + QUICKDRAW, QUICKDRAW);
        verifyPlate(live, view, "nameplate-" + ATLAS, ATLAS);
    }

    /** The labels draw a leader from each placed card that leaves its target's head free, ending in a diamond. */
    private static void verifyLeaders(Live live) throws Exception {
        GpuTargetCards cards = (GpuTargetCards) GpuTestSupport.field(live.hud, "targetCards");
        Map<TargetKey, com.badlogic.gdx.math.Rectangle> placed = cards.placed();
        assertEquals(2, placed.size(), "Both targets' cards are placed");
        assertEquals(placed, GpuTestSupport.field(GpuTestSupport.field(live.hud, "boardLabels"), "cards"), "The labels have this frame's cards");
        GpuHud.HudView view = live.hudView();
        Pixmap window = live.window();
        try {
            int leaders = 0;
            for (Map.Entry<TargetKey, com.badlogic.gdx.math.Rectangle> card : placed.entrySet()) {
                Vector2 head = view.head(card.getKey());
                if (card.getValue().contains(head)) {
                    continue;
                }
                int rgba = live.pixel(window, head);
                assertTrue(shows(rgba, Color.valueOf("EC6F64"), 12),
                      card.getKey() + ": the leader's diamond at the head, was " + Integer.toHexString(rgba));
                leaders++;
            }
            assertTrue(leaders > 0, "At least one card leaves its target's head free");
        } finally {
            window.dispose();
        }
    }

    /**
     * The overlay's arcs (E3b): the selected right-arm laser's arc as the mint wedge around the Atlas, which faces
     * north, reaching ahead and to the right but not behind; without a weapon, the front arc instead.
     */
    private static void verifyArcs(Live live, GpuFiringFixture firing, int right) throws Exception {
        GpuFireOrders.WeaponArc wedge = live.frame.get().panels().fire().solution().wedge();
        assertEquals(List.of(ATLAS_HEX, 0), List.of(wedge.origin(), wedge.facing()));
        live.camera().orbit(0, -BoardCamera.MAX_TILT);
        live.zoom(ATLAS_HEX, 160);
        Pixmap armed = live.board();
        GpuFireOrders.Snapshot none = GpuFiringFixture.command(firing, fire -> fire.selectWeapon(-1));
        assertNull(none.solution());
        assertNotNull(none.frontArc(), "Without a weapon, the front arc");
        live.publish();
        live.draw(2, 0);
        assertSame(live.frame.get().panels().fire(), GpuTestSupport.field(live.overlay, "fire"));
        Pixmap front = live.board();
        try {
            Vector3 atlas = BoardGeometry.center(ATLAS_HEX, live.scene().tile(ATLAS_HEX).elevation());
            float distance = 1.5f * BoardGeometry.WIDTH / 2;
            // Clockwise from north: the right arm's arc runs from -60 to 120 degrees around the facing.
            for (int degrees : List.of(0, 60)) {
                assertTrue(live.difference(armed, front, atlas, degrees, distance) > 8,
                      degrees + " degrees: inside the wedge");
            }
            for (int degrees : List.of(180, 240)) {
                assertTrue(live.difference(armed, front, atlas, degrees, distance) < 3,
                      degrees + " degrees: outside the wedge");
            }
        } finally {
            armed.dispose();
            front.dispose();
        }
        live.camera().setIsometric(true);
        live.camera().fit(live.scene());
        live.zoom(new Coords(6, 4), 130);
        live.capture("i1b-firing-front-arc.png");
        GpuFiringFixture.command(firing, fire -> fire.selectWeapon(right));
        live.publish();
        live.draw(3, 0);
    }

    @Test
    void aPhysicalAttackRingsTheNeighboursAndFocusesTheTarget() throws Exception {
        ClientGUI gui = mock(ClientGUI.class);
        when(gui.doYesNoDialog(anyString(), anyString())).thenReturn(true);
        try (GpuPhysicalOptionsTest.Scene physical = GpuPhysicalOptionsTest.Scene.create(gui,
              "testresources/megamek/common/units/Atlas AS7-D.mtf", 0)) {
            onSwing(() -> {
                // The fixture's own Atlas stands far off and has acted, so the HUD's focus (C.5) is the attacker.
                physical.fixture.entity.setDone(true);
                physical.source.setVisibleArea(new Rectangle(0, 0, 16, 17));
                return null;
            });
            run(physical.source, live -> {
                live.publish();
                live.draw(20, .1f);
                GpuPhysicalOptions.Snapshot options = live.frame.get().panels().physical();
                int attacker = physical.attacker.getId();
                int enemy = physical.enemy.getId();
                assertTrue(options.active() && options.actorId() == attacker && options.targetId() == enemy);
                assertSame(options, GpuTestSupport.field(live.overlay, "physical"), "The overlay draws the published options");
                assertEquals(enemy, live.overlay.focus(), "The attack's target is the overlay's focus");
                GpuHud.HudView view = live.hudView();
                verifyPlate(live, view, "nameplate-" + attacker, attacker);
                verifyPlate(live, view, "nameplate-" + enemy, enemy);
                Coords hex = physical.attacker.getPosition();
                live.camera().orbit(0, -BoardCamera.MAX_TILT);
                live.zoom(hex, 160);
                live.show(live.overlayOnly(live.frame.get().panels().move()));
                Pixmap attacking = live.board();
                GpuBoardSource.Frame shown = live.frame.get();
                GpuHudData p = shown.panels();
                live.show(Live.copy(shown, shown.scene(), shown.status(), new GpuHudData(p.phase(), p.move(),
                      p.fire(), GpuPhysicalOptions.Snapshot.EMPTY, p.record(), p.preview(), p.chat(), p.toasts(),
                      p.los(), p.players())));
                Pixmap idle = live.board();
                try {
                    for (int direction = 0; direction < 6; direction++) {
                        Coords neighbour = hex.translated(direction);
                        assertTrue(live.changed(attacking, idle, neighbour) > 100, neighbour + " is ringed");
                    }
                    assertTrue(live.changed(attacking, idle, hex.translated(3).translated(3)) < 20,
                          "A hex two away is not");
                } finally {
                    attacking.dispose();
                    idle.dispose();
                }
                live.publish();
                live.camera().setIsometric(true);
                live.zoom(hex, 130);
                live.capture("i1b-physical-3d.png");
                live.view.setTacticalView(true);
                live.zoom(hex, 90);
                live.capture("i1b-physical-tactical.png");
                assertEquals(GpuUnitBases.Emphasis.FOCUS, live.bases().spec(live.unit(enemy)).emphasis(),
                      "The target's base glows as the focused enemy");
            });
        }
    }

    /**
     * The HUD's state comes before the camera in each frame, though the HUD's components come after the units: at a
     * report phase's start the log opens itself, and the unit selected in the same frame is framed in the middle of
     * the narrower board area between the columns (I2), not of the area before the log opened.
     */
    @Test
    void aReportPhaseOpensTheLogBeforeTheCameraFramesTheSelection() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            plan(moving);
            run(moving.board.source, live -> {
                live.publish();
                live.draw(20, .1f);
                live.camera().setIsometric(true);
                live.draw(3, 0);
                assertFalse(live.hud.state.logOpen(), "The log is closed in the movement phase");
                GpuBoardSource.Frame base = live.frame.get();
                BoardScene s = base.scene();
                GpuBattleStatus.Snapshot status = base.status();
                live.frame.set(Live.copy(base, new BoardScene(s.boardId(), s.width(), s.height(), s.tiles(), s.units(),
                      s.plannedPath(), ARCHER, s.phase(), s.commands(), s.light(), s.firingLines(), s.rangeBorders(),
                      s.markers(), s.tactical(), s.rangeLabels(), s.fieldOfView()),
                      new GpuBattleStatus.Snapshot(status.round(), GamePhase.MOVEMENT_REPORT, false,
                            status.localPlayerId(), Entity.NONE, status.turns(), status.turnIndex(), status.units(),
                            status.initiative(), status.turnOrderHidden()), base.panels()));
                live.draw(1, 0);
                for (int frame = 0; frame < 100 && live.camera().isFraming(); frame++) {
                    live.draw(1, .1f);
                }
                assertTrue(live.hud.state.logOpen(), "The report phase opened the log");
                assertFalse(live.camera().isFraming(), "The camera framed the Archer");
                Vector3 archer = live.camera().camera.project(BoardGeometry.center(ARCHER_HEX,
                      live.scene().tile(ARCHER_HEX).elevation()), 0, 0, live.camera().camera.viewportWidth,
                      live.camera().camera.viewportHeight);
                assertEquals(live.camera().camera.viewportWidth / 2, archer.x, 3,
                      "The Archer stands in the full viewport center even with the log open");
            });
        }
    }

    /**
     * The board-space HUD's cost at 100 v 100 (rebuild plan I1b F3): the movement scene with 197 more units, its
     * frames steady (no overlay rebuild) and while the pointer sweeps over units (no rebuild either: the marks
     * follow the pointer every frame). Prints the
     * CPU time of the view's board-space HUD stage, which builds the HudView and updates the HUD and the overlay.
     */
    @Test
    void aHundredAgainstAHundredRebuildsOnlyOnChange() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            plan(moving);
            moving.board.source.moves().planTo(DESTINATION, 0, false);
            settle();
            run(moving.board.source, live -> {
                live.crowd = true;
                live.publish();
                live.draw(20, .1f);
                assertEquals(200, live.scene().units().size());
                System.out.println("GL renderer " + Gdx.gl.glGetString(GL20.GL_RENDERER));
                long builds = live.overlay.builds();
                List<Long> steady = new ArrayList<>();
                for (int index = 0; index < 60; index++) {
                    live.publish();
                    live.draw(1, 0);
                    steady.add(live.stageNanos);
                }
                assertEquals(builds, live.overlay.builds(), "No rebuild while the 200 units stand still");
                List<Long> sweep = new ArrayList<>();
                for (BoardScene.Unit unit : live.scene().units()) {
                    if (sweep.size() < 60 && live.hover(unit.location().coords())) {
                        live.draw(1, 0);
                        sweep.add(live.stageNanos);
                    }
                }
                long rebuilt = live.overlay.builds() - builds;
                assertTrue(rebuilt <= 1, "Hovering one unit after another keeps the meshes; " + rebuilt);
                System.out.printf("Board-space HUD stage at 200 units: steady median %.2f ms (max %.2f); pointer sweep"
                            + " median %.2f ms (max %.2f) with %d rebuilds in %d frames%n", median(steady), max(steady),
                      median(sweep), max(sweep), rebuilt, sweep.size());
            });
        }
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * The movement scene: the Atlas has moved, so the HUD's focus (C.5) is the Sagittaire the display plans for; an
     * identified enemy Archer stands north-east; the display is the source's phase panel, after a capture.
     */
    private static void plan(GpuMovementFixture moving) throws Exception {
        GpuBoardFixture board = moving.board;
        onSwing(() -> {
            board.entity.setDone(true);
            Player enemy = new Player(2, "Enemy");
            enemy.setTeam(2);
            board.game.addPlayer(enemy.getId(), enemy);
            Entity archer = GpuFiringFixture.unit("Archer ARC-2R.mtf", ARCHER, ARCHER_HEX);
            archer.setOwner(enemy);
            board.game.addEntity(archer, false);
            archer.addBeenSeenBy(board.player);
            board.panel = moving.display;
            board.source.setVisibleArea(new Rectangle(0, 0, 16, 17));
            board.source.refresh();
            return null;
        });
    }

    /** Lets posted commands run, then the jobs they asked for and their republish. */
    static void settle() throws Exception {
        for (int pass = 0; pass < 4; pass++) {
            onSwing(() -> null);
        }
    }

    /** Waits until the source's fire preview has the Archer, as its job publishes it. */
    private static void awaitPreview(GpuBoardSource source) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            GpuFirePreview.Snapshot preview = onSwing(() -> {
                source.refresh();
                return source.takeFrame().panels().preview();
            });
            if (preview.complete() && preview.contacts().stream().anyMatch(contact -> contact.id() == ARCHER)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("The fire preview did not complete");
    }

    /** Whether a captured pixel shows {@code colour}, each of its channels within {@code tolerance}. */
    private static boolean shows(int rgba, Color colour, int tolerance) {
        int expected = Color.rgba8888(colour);
        for (int shift : new int[] { 24, 16, 8 }) {
            if (Math.abs((rgba >>> shift & 255) - (expected >>> shift & 255)) > tolerance) {
                return false;
            }
        }
        return true;
    }

    /**
     * Where the minimap draws a world point: its mapping from world to stage units, derived from two presses on its
     * canvas, each of which centres the camera on the point under it (as GpuUtilityMinimapSmokeTest derives it).
     */
    private record Minimap(Vector2 pressed, Vector3 origin, Vector2 scale, float density) {
        int pixel(Pixmap window, float worldX, float worldY) {
            float x = pressed.x + (worldX - origin.x) * scale.x;
            float y = pressed.y + (worldY - origin.y) * scale.y;
            return window.getPixel(Math.round(x * density), Math.round(y * density));
        }
    }

    private static double median(List<Long> times) {
        List<Long> sorted = times.stream().sorted().toList();
        return sorted.get(sorted.size() / 2) / 1e6;
    }

    private static double max(List<Long> times) {
        return times.stream().mapToLong(Long::longValue).max().orElse(0) / 1e6;
    }

    /** A step of a test that may throw, on the GL thread. */
    interface Body {
        void run(Live live) throws Exception;
    }

    /** Opens a hidden 1920 x 1080 window and runs {@code body} with a live view over the real source. */
    static void run(GpuBoardSource real, Body body) {
        run(real, 1920, 1080, body);
    }

    /** Opens a hidden window of the given size and runs {@code body} with a live view over the real source. */
    static void run(GpuBoardSource real, int width, int height, Body body) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Lwjgl3ApplicationConfiguration configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(width, height);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                Live live = null;
                try {
                    live = new Live(real);
                    body.run(live);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    if (live != null) {
                        live.close();
                    }
                    Gdx.app.exit();
                }
            }
        }, configuration);
        if (failure.get() != null) {
            throw new AssertionError("The live board-space check failed", failure.get());
        }
    }

    /**
     * The view under test over a recording source that publishes the real source's captures as the local player's
     * turn; GL thread only. The pointer rests over the phase header, where it hovers no hex, unless a check moves it.
     * GpuLivePlaybackSmokeTest drives it too.
     */
    static final class Live {
        final GpuBoardSource real;
        final GpuBoardSource source = mock(GpuBoardSource.class);
        final AtomicReference<GpuBoardSource.Frame> frame = new AtomicReference<>();
        final GpuBattleView view;
        final GpuHud hud;
        final GpuBoardOverlay overlay;
        /** The CPU time of the last frame's board-space HUD stage, in nanoseconds. */
        long stageNanos;
        /** Publish the movement scene with 197 more units, 100 against 100 (F3). */
        boolean crowd;
        private final Input input;
        private final Input pointer = mock(Input.class);
        private final Graphics graphics;
        private final Graphics timed;
        private float delta;
        private long stageStart;
        private GpuBattleStatus.Snapshot captured;
        private GpuBattleStatus.Snapshot local;
        private List<BoardScene.Unit> extras;

        Live(GpuBoardSource real) throws Exception {
            this.real = real;
            // One HUD unit per window pixel, as in the prototype's 1920 x 1080 captures; envelopes and the minimap on.
            source.uiPreferences = GpuHudFixtures.preferences(.1f
                  / DisplayScale.read(.1f, new GpuDisplayScale().contentScale()));
            // The view reads the preferences through the accessor; tests change them through the field.
            when(source.uiPreferences()).thenAnswer(invocation -> source.uiPreferences);
            when(source.takeFrame()).thenAnswer(invocation -> frame.get());
            when(source.scenarioAtmosphere()).thenReturn(BoardAtmosphere.DEFAULTS);
            when(source.reserveUnits()).thenAnswer(invocation -> real.reserveUnits());
            when(source.moves()).thenReturn(mock(GpuMovePlan.class));
            when(source.fire()).thenReturn(mock(GpuFireOrders.class));
            when(source.physical()).thenReturn(mock(GpuPhysicalOptions.class));
            when(source.record()).thenReturn(mock(GpuUnitRecord.class));
            when(source.toasts()).thenReturn(mock(GpuToasts.class));
            when(source.los()).thenReturn(mock(GpuLosResult.class));
            when(source.chat()).thenReturn(mock(GpuChat.class));
            when(source.players()).thenReturn(mock(GpuPlayers.class));
            publish();
            view = new GpuBattleView(source) {
                @Override
                void renderStage(String stage) {
                    if (STAGE.equals(stage)) {
                        stageStart = System.nanoTime();
                    } else if (stageStart != 0) {
                        stageNanos = System.nanoTime() - stageStart;
                        stageStart = 0;
                    }
                }
            };
            view.create();
            hud = (GpuHud) GpuTestSupport.field(view, "ui");
            // The forces list stays open, as the checks use its rows; the folded rail has its own test.
            hud.state.forcesPinned = true;
            overlay =  (GpuBoardOverlay) GpuTestSupport.field(view, "overlay");
            input = Gdx.input;
            when(pointer.getInputProcessor()).thenReturn(input.getInputProcessor());
            when(pointer.getX()).thenReturn(120);
            when(pointer.getY()).thenReturn(40);
            Gdx.input = pointer;
            graphics = Gdx.graphics;
            timed = spy(graphics);
            doAnswer(invocation -> delta).when(timed).getDeltaTime();
            // The HUD draws once the board is presented, after the terrain is built behind the loading screen; no
            // time passes until then, so every check starts from the board's first shown frame.
            Gdx.graphics = timed;
            try {
                GpuBoardTestUi.present(view);
            } finally {
                Gdx.graphics = graphics;
            }
        }

        /** Publishes the real source's next capture as the local player's turn. */
        void publish() throws Exception {
            GpuBoardSource.Frame next = onSwing(() -> {
                real.refresh();
                return real.takeFrame();
            });
            frame.set(localTurn(next));
        }

        /** Shows {@code next} for two frames in which no time passes. */
        void show(GpuBoardSource.Frame next) {
            frame.set(next);
            draw(2, 0);
        }

        /**
         * The shown frame with the movement plan {@code move} and without MegaMek's own tactical sprites, its route and
         * envelope among them, which reach the board until I3's capture filter (wave 2 checklist, I3 G4).
         */
        GpuBoardSource.Frame overlayOnly(GpuMovePlan.Snapshot move) {
            GpuBoardSource.Frame shown = frame.get();
            BoardScene s = shown.scene();
            GpuHudData panels = shown.panels();
            return copy(shown, new BoardScene(s.boardId(), s.width(), s.height(), s.tiles(), s.units(), s.plannedPath(),
                  s.selectedId(), s.phase(), s.commands(), s.light(), s.firingLines(), s.rangeBorders(), s.markers(),
                  BoardTactical.EMPTY, s.rangeLabels(), s.fieldOfView()), shown.status(),
                  new GpuHudData(panels.phase(), move, panels.fire(), panels.physical(), panels.record(),
                        panels.preview(), panels.chat(), panels.toasts(), panels.los(), panels.players()));
        }

        /**
         * The capture as the local player's client presents it: the fixtures' board state has no client, so its
         * capture cannot tell that the turn is the local player's, which the fixtures' client reports. An unchanged
         * status keeps its snapshot, as the source does. With the crowd, the capture gains its 197 units.
         */
        private GpuBoardSource.Frame localTurn(GpuBoardSource.Frame next) {
            BoardScene scene = next.scene();
            if (crowd) {
                List<BoardScene.Unit> units = new ArrayList<>(scene.units());
                units.addAll(extras(scene));
                scene = scene.withUnits(units);
            }
            GpuBattleStatus.Snapshot status = next.status();
            if (status != captured || crowd && local.units().size() == status.units().size()) {
                captured = status;
                List<GpuBattleStatus.UnitStatus> units = new ArrayList<>(status.units());
                if (crowd) {
                    GpuBattleStatus.UnitStatus template = GpuHudState.unit(status, ARCHER);
                    for (BoardScene.Unit unit : extras) {
                        // 98 more of the player's units and 99 more enemies: 100 against 100 with the scene's three.
                        units.add(GpuBoardOverlaySmokeTest.listing(template, unit.id(), unit.id() < 198
                              ? GpuBattleStatus.Side.OWN : GpuBattleStatus.Side.ENEMY, unit.location().coords()));
                    }
                }
                local = new GpuBattleStatus.Snapshot(status.round(), status.phase(), true, status.localPlayerId(),
                      status.actorId(), status.turns(), status.turnIndex(), units, status.initiative(),
                      status.turnOrderHidden());
            }
            return copy(next, scene, local, next.panels());
        }

        static GpuBoardSource.Frame copy(GpuBoardSource.Frame frame, BoardScene scene,
              GpuBattleStatus.Snapshot status, GpuHudData panels) {
            return new GpuBoardSource.Frame(scene, frame.timeline(), frame.context(), frame.globalCommands(),
                  frame.tooltip(), frame.centerRequest(), frame.boardGeneration(), frame.actorName(),
                  frame.scenarioAtmosphere(), frame.reports(), status, panels);
        }

        /**
         * 197 more units as flat sprites on free hexes, as new records at every capture, as the source makes them;
         * their ids run from 100.
         */
        private List<BoardScene.Unit> extras(BoardScene scene) {
            if (extras == null) {
                BoardScene.Unit template = scene.units().stream().filter(unit -> unit.id() == ARCHER).findFirst()
                      .orElseThrow();
                Set<Coords> taken = scene.units().stream().map(unit -> unit.location().coords())
                      .collect(Collectors.toSet());
                extras = new ArrayList<>();
                for (int x = 0; x < scene.width() && extras.size() < 197; x++) {
                    for (int y = 0; y < scene.height() && extras.size() < 197; y++) {
                        Coords coords = new Coords(x, y);
                        if (!taken.contains(coords) && !coords.equals(DESTINATION)) {
                            extras.add(new BoardScene.Unit(100 + extras.size(), -1, template.name(),
                                  new BoardScene.Waypoint(coords, scene.tile(coords).elevation(), 0),
                                  template.image(), false, null, 2, false));
                        }
                    }
                }
            }
            return extras.stream().map(unit -> new BoardScene.Unit(unit.id(), unit.part(), unit.name(),
                  new BoardScene.Waypoint(unit.location().coords(), unit.location().elevation(), 0), unit.image(),
                  false, null, unit.height(), false)).toList();
        }

        /** Draws {@code frames} frames of {@code seconds} each. */
        void draw(int frames, float seconds) {
            delta = seconds;
            Gdx.graphics = timed;
            try {
                for (int index = 0; index < frames; index++) {
                    view.render();
                }
            } finally {
                Gdx.graphics = graphics;
            }
        }

        /** Moves the pointer over {@code hex} as the window reports a move; false where a HUD panel covers it. */
        boolean hover(Coords hex) {
            Vector3 point = view.screenPosition(hex);
            int x = Math.round(point.x);
            int y = Math.round(point.y);
            if (x < 0 || y < 0 || x >= Gdx.graphics.getWidth() || y >= Gdx.graphics.getHeight() || hud.hit(x, y)) {
                return false;
            }
            when(pointer.getX()).thenReturn(x);
            when(pointer.getY()).thenReturn(y);
            input.getInputProcessor().mouseMoved(x, y);
            return true;
        }

        BoardCamera camera() {
            return view.boardCamera;
        }

        /** Centres {@code hex} with a hex {@code hexPixels} HUD units wide, and draws a frame. */
        void zoom(Coords hex, float hexPixels) throws Exception {
            camera().center(BoardGeometry.center(hex, scene().tile(hex).elevation()));
            float scale = (float) GpuTestSupport.field(view, "layoutScale");
            camera().zoom(BoardGeometry.WIDTH / (hexPixels * scale) / camera().camera.zoom);
            draw(3, 0);
        }

        BoardScene scene() throws Exception {
            return (BoardScene) GpuTestSupport.field(view, "scene");
        }

        BoardScene.Unit unit(int id) throws Exception {
            return scene().units().stream().filter(unit -> unit.id() == id).findFirst().orElseThrow();
        }

        @SuppressWarnings("unchecked")
        ModelInstance model(int id) {
            return ((Map<String, ModelInstance>) GpuTestSupport.field(view, "unitInstances")).get(id + ":-1");
        }

        GpuUnitBases bases() throws Exception {
            return (GpuUnitBases) GpuTestSupport.field(view, "unitBases");
        }

        /** The facts the view handed the HUD in the last frame. */
        GpuHud.HudView hudView() throws Exception {
            return ((GpuHud.Inputs) GpuTestSupport.field(hud, "inputs")).view();
        }

        Actor actor(String name) {
            Actor actor = hud.stage.getRoot().findActor(name);
            assertNotNull(actor, "No actor " + name);
            return actor;
        }

        /** The minimap's mapping (see {@link Minimap}); the presses leave the camera centred elsewhere. */
        Minimap minimap() {
            Actor canvas = actor("minimap-canvas");
            Vector2 first = canvas.localToStageCoordinates(new Vector2(canvas.getWidth() / 2, canvas.getHeight() / 2));
            Vector2 second = first.cpy().add(40, 20);
            Vector3 origin = press(first);
            Vector3 other = press(second);
            return new Minimap(first, origin, new Vector2((second.x - first.x) / (other.x - origin.x),
                  (second.y - first.y) / (other.y - origin.y)), Gdx.graphics.getBackBufferWidth()
                  / hud.stage.getWidth());
        }

        /** A left press and release at a stage point, which it moves to the pixel pressed; the camera's focus after. */
        private Vector3 press(Vector2 point) {
            Vector2 screen = hud.stage.stageToScreenCoordinates(point.cpy());
            int x = Math.round(screen.x);
            int y = Math.round(screen.y);
            point.set(hud.stage.screenToStageCoordinates(new Vector2(x, y)));
            hud.stage.touchDown(x, y, 0, Input.Buttons.LEFT);
            hud.stage.touchUp(x, y, 0, Input.Buttons.LEFT);
            return camera().focus.cpy();
        }

        /** The firing lines the fire control draws this frame. */
        @SuppressWarnings("unchecked")
        List<BoardScene.FiringLine> firingLines() throws Exception {
            return (List<BoardScene.FiringLine>) GpuTestSupport.field(GpuTestSupport.field(view, "fireControl"), "firingLines");
        }

        /** The number of strokes the labels' fx layer draws this frame: guides and traces. */
        int strokes() throws Exception {
            return ((Collection<?>) GpuTestSupport.field(GpuTestSupport.field(GpuTestSupport.field(hud, "boardLabels"), "strokes"), "list")).size();
        }

        /** The unit's shown TN badge, bottom-centred 4 units over its nameplate, or null. */
        Actor badgeOn(int unit, GpuHud.HudView view) throws Exception {
            Vector2 head = view.unitHeads().get(unit);
            GpuNameplates plates = (GpuNameplates) GpuTestSupport.field(GpuTestSupport.field(hud, "boardLabels"), "nameplates");
            return badge(hud.stage.getRoot(), new Vector2(head.x, plates.top(unit, head.y)));
        }

        private static Actor badge(Actor actor, Vector2 base) {
            if ("tn-badge".equals(actor.getName()) && GpuBoardTestUi.shown(actor)) {
                Vector2 corner = actor.localToStageCoordinates(new Vector2());
                if (Math.abs(corner.x + actor.getWidth() / 2 - base.x) <= 1 && Math.abs(corner.y - base.y - 4) <= 1) {
                    return actor;
                }
            }
            if (actor instanceof Group group) {
                for (Actor child : group.getChildren()) {
                    Actor badge = badge(child, base);
                    if (badge != null) {
                        return badge;
                    }
                }
            }
            return null;
        }

        /** A world point in stage units, y up, as the view hands heads to the HUD. */
        Vector2 stage(Vector3 world) {
            Vector3 point = camera().camera.project(new Vector3(world), 0, 0, camera().camera.viewportWidth,
                  camera().camera.viewportHeight);
            float scale = hud.stage.getWidth() / camera().camera.viewportWidth;
            return new Vector2(point.x * scale, point.y * scale);
        }

        /** A hex's surface in stage units, y up. */
        Vector2 hex(Coords coords) throws Exception {
            return stage(surface(coords));
        }

        private Vector3 surface(Coords coords) throws Exception {
            return new Vector3(BoardGeometry.centerX(coords), BoardGeometry.centerY(coords),
                  BoardGeometry.surfaceZ(scene().tile(coords)));
        }

        /** The window as a frame in which no time passes draws it; rows y up. */
        Pixmap window() {
            draw(1, 0);
            return Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(),
                  Gdx.graphics.getBackBufferHeight());
        }

        /** The board alone, the HUD hidden, in a frame in which no time passes. */
        Pixmap board() {
            hud.stage.getRoot().setVisible(false);
            try {
                draw(1, 0);
                return window();
            } finally {
                hud.stage.getRoot().setVisible(true);
            }
        }

        /** The pixel under a stage point. */
        int pixel(Pixmap pixels, Vector2 point) {
            float density = pixels.getWidth() / hud.stage.getWidth();
            return pixels.getPixel(Math.round(point.x * density), Math.round(point.y * density));
        }

        /** The pixels within a hex's inner circle that changed by more than 8 in a channel between two captures. */
        int changed(Pixmap before, Pixmap after, Coords hex) throws Exception {
            Vector2 centre = stage(surface(hex));
            float density = before.getWidth() / hud.stage.getWidth();
            float radius = stage(surface(hex).add(BoardGeometry.HEIGHT / 2, 0, 0)).dst(centre) * density;
            int x = Math.round(centre.x * density);
            int y = Math.round(centre.y * density);
            int changed = 0;
            for (int dx = -Math.round(radius); dx <= radius; dx++) {
                for (int dy = -Math.round(radius); dy <= radius; dy++) {
                    if (dx * dx + dy * dy > radius * radius) {
                        continue;
                    }
                    int a = before.getPixel(x + dx, y + dy);
                    int b = after.getPixel(x + dx, y + dy);
                    for (int shift : new int[] { 24, 16, 8 }) {
                        if (Math.abs((a >>> shift & 255) - (b >>> shift & 255)) > 8) {
                            changed++;
                            break;
                        }
                    }
                }
            }
            return changed;
        }

        /** The largest change of a colour channel between two captures at a hex's centre, mean of 3 x 3 pixels. */
        float difference(Pixmap before, Pixmap after, Coords hex) throws Exception {
            return difference(before, after, surface(hex), 0, 0);
        }

        /**
         * As {@link #difference(Pixmap, Pixmap, Coords)} at the point {@code distance} world units from
         * {@code centre}, {@code degrees} clockwise from north.
         */
        float difference(Pixmap before, Pixmap after, Vector3 centre, int degrees, float distance) {
            float angle = degrees * MathUtils.degreesToRadians;
            Vector2 point = stage(new Vector3(centre).add(MathUtils.sin(angle) * distance,
                  MathUtils.cos(angle) * distance, 0));
            float density = before.getWidth() / hud.stage.getWidth();
            int x = Math.round(point.x * density);
            int y = Math.round(point.y * density);
            float sum = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    int a = before.getPixel(x + dx, y + dy);
                    int b = after.getPixel(x + dx, y + dy);
                    int largest = 0;
                    for (int shift : new int[] { 24, 16, 8 }) {
                        largest = Math.max(largest, Math.abs(((a >>> shift) & 255) - ((b >>> shift) & 255)));
                    }
                    sum += largest;
                }
            }
            return sum / 9;
        }

        /** Saves the window, as the review renders are saved. */
        void capture(String name) {
            draw(1, 0);
            File directory = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
            assertTrue(directory.isDirectory() || directory.mkdirs());
            GpuBoardTestUi.capture(new File(directory, name));
        }

        void close() {
            Gdx.input = input;
            Gdx.graphics = graphics;
            view.dispose();
        }
    }
}
