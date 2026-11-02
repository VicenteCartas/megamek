/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static java.awt.event.InputEvent.ALT_DOWN_MASK;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudFixtures.ATLAS;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudFixtures.CONTACT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.NameplateMode;
import megamek.client.ui.clientGUI.boardview.UnitStatusWords;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.UnitStatus;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.rolls.TargetRoll;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The nameplates and pips (G8a) over the board-space harness: the mock roster in 3D and in the Tactical View in the
 * states of the hud-v3 shots 02-09 and 14, each plate beside its crop of the shot; which marker every unit gets for
 * the focus, hover, inspection, physical target, status words, destruction, a large battle and the nameplate key held
 * through the HUD's own key handling; that a crowd's markers keep the prototype's boxes; and no plate for a unit
 * behind the camera.
 */
@Tag("on-demand")
class GpuNameplatesSmokeTest {
    private static final String DOT = String.valueOf((char) 0xB7);
    private static final List<String> PIP = List.of("pip");
    private static final List<String> CONTACT_TAG = List.of("Sensor contact", "");
    private static final int WARHAMMER = 2;
    private static final int MARAUDER = 3;
    private static final int PANTHER = 4;
    private static final int LOCUST = 5;
    private static final int TIMBER_WOLF = 6;
    private static final int KING_CRAB = 7;
    private static final int BATTLEMASTER = 8;
    private static final int ENEMY_LOCUST = 10;
    /** The hud-v3 shots that show plates, with the top-left corner of the plate each comparison crops. */
    private static final String SHOT_02 = "02-movement-fire-preview.jpg";
    private static final String SHOT_03 = "03-waypoint-route.jpg";
    private static final String SHOT_04 = "04-opponent-turn.jpg";
    private static final String SHOT_05 = "05-weapon-declaration.jpg";
    private static final String SHOT_07 = "07-tactical-view.jpg";
    private static final String SHOT_08 = "08-weapon-playback.jpg";
    private static final String SHOT_09 = "09-physical-attacks.jpg";
    private static final String SHOT_14 = "14-large-battle-100v100.jpg";
    /**
     * The prototype's boxes (width, height) of a crowd's markers: proto3.css .plate .pip, and .plate .tag in 600 11px
     * Roboto holding "?", "Sensor contact" and the Atlas's "moving" line, as a browser lays them out with the
     * prototype's font.
     */
    private static final float[] MOCK_PIP = { 10, 7 };
    private static final float[] MOCK_UNKNOWN = { 21.422f, 21 };
    private static final float[] MOCK_CONTACT = { 90.5f, 21 };
    private static final float[] MOCK_ATLAS = { 89.078f, 21 };

    /** The component under test and the HUD state it reads, filling the harness's emulated window. */
    private static final class Plates {
        final GpuHudState state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
        final GpuNameplates nameplates;
        final Table root;
        GpuBoardSource.UiPreferences preferences = GpuHudInputTest.preferences();

        Plates(GpuHudTestStage hud) {
            nameplates = new GpuNameplates(hud.kit, mock(GpuBoardSource.class), state);
            root = (Table) nameplates.actor();
            hud.window.addActor(root);
            root.setBounds(0, 0, hud.width(), hud.height());
        }

        /** One HUD frame: the status through the state's focus rule, then the plates at the given heads. */
        void update(GpuHudTestStage hud, GpuBattleStatus.Snapshot status, GpuHudData panels,
              Map<Integer, Vector2> heads, boolean tactical, int hovered) {
            state.update(status, GpuUnitRecord.Snapshot.EMPTY, false);
            nameplates.update(new GpuHud.Inputs(GpuHudInputTest.frame(status, panels),
                  new GpuHud.HudView(tactical, false, Map.of(), heads, Map.of(), null, hovered, 0), null,
                  preferences, GpuHud.Metrics.of(hud.width(), hud.height()), List.of()));
        }

        Table tag(int id) {
            return root.findActor("nameplate-" + id);
        }

        Actor pip(int id) {
            return root.findActor("pip-" + id);
        }

        Actor marks(int id) {
            return root.findActor("marks-" + id);
        }

        /** The unit's marker: its tag's name and sub-line, {@link #PIP}, or null without one. */
        List<String> marker(int id) {
            return GpuNameplatesSmokeTest.marker(root, id);
        }

        /** Every unit's marker by id, for the ids that have one. */
        Map<Integer, List<String>> markers(List<UnitStatus> units) {
            return GpuNameplatesSmokeTest.markers(root, units);
        }

        /** Each marker sits bottom-centred on its unit's head, as the prototype's translate(-50%, -100%). */
        void assertOnHeads(Map<Integer, Vector2> heads) {
            heads.forEach((id, head) -> {
                Actor marker = tag(id) != null ? tag(id) : pip(id);
                if (marker != null) {
                    Rectangle bounds = GpuHudTestStage.bounds(marker);
                    assertEquals(head.x, bounds.x + bounds.width / 2, 1, "centre of the plate of " + id);
                    assertEquals(head.y, bounds.y, .5f, "bottom of the plate of " + id);
                }
            });
        }

        void dispose() {
            root.remove();
        }
    }

    @Test
    void platesOverTheBoardFollowTheMockStates() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene, hud.theme.skin);
            try {
                GpuBattleStatus.Snapshot moving = GpuHudFixtures.status();
                List<UnitStatus> roster = moving.units();
                GpuHudData none = panels(GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY);

                // Shot 02: the Atlas plans its move; every other unit shows its pip, the contact its tag.
                Plates plates = new Plates(hud);
                Map<Integer, Vector2> heads = draw(hud, board, plates, false, List.of(moving), none,
                      Entity.NONE);
                assertEquals(10, heads.size(), "Every unit of the roster has a head in 3D");
                Map<Integer, List<String>> expected = pips(roster);
                expected.put(ATLAS, List.of("Atlas", ""));
                expected.put(CONTACT, CONTACT_TAG);
                assertEquals(expected, plates.markers(roster));
                plates.assertOnHeads(heads);
                Rectangle atlas = GpuHudTestStage.bounds(plates.tag(ATLAS));
                capture(hud, "nameplates-02", SHOT_02, new Crop("tag", plates.tag(ATLAS), 915, 615),
                      new Crop("pip", plates.pip(LOCUST), 1313, 514));
                plates.dispose();

                // The same at 1280 x 720: the plates keep the prototype's size, their units move.
                board.camera.resize(1280, 720);
                hud.size(1280, 720);
                plates = new Plates(hud);
                heads = draw(hud, board, plates, false, List.of(moving), none, Entity.NONE);
                assertEquals(expected, plates.markers(roster));
                plates.assertOnHeads(heads);
                Rectangle compact = GpuHudTestStage.bounds(plates.tag(ATLAS));
                assertEquals(atlas.width, compact.width, "The Atlas tag keeps its width at 1280 x 720");
                assertEquals(atlas.height, compact.height, "The Atlas tag keeps its height at 1280 x 720");
                opaque();
                hud.capture("nameplates-02-1280x720").dispose();
                plates.dispose();
                board.camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                hud.size(1920, 1080);

                // Shot 03: the player selected the Panther; the Atlas is a pip again.
                plates = new Plates(hud);
                draw(hud, board, plates, false, List.of(moving, status(moving, GamePhase.MOVEMENT, true,
                      PANTHER, roster)), none, Entity.NONE);
                expected = pips(roster);
                expected.put(PANTHER, List.of("Panther", ""));
                expected.put(CONTACT, CONTACT_TAG);
                assertEquals(expected, plates.markers(roster));
                capture(hud, "nameplates-03", SHOT_03, new Crop("tag", plates.tag(PANTHER), 1100, 611));
                plates.dispose();

                // Shot 04: the Atlas has moved and the opponent moves; the Warhammer is up next.
                List<UnitStatus> moved = new ArrayList<>(roster);
                moved.set(0, unit(roster.get(0), false, false, true, false, List.of()));
                plates = new Plates(hud);
                draw(hud, board, plates, false, List.of(moving, status(moving, GamePhase.MOVEMENT, false,
                      TIMBER_WOLF, moved)), none, Entity.NONE);
                expected = pips(roster);
                expected.put(WARHAMMER, List.of("Warhammer", ""));
                expected.put(CONTACT, CONTACT_TAG);
                assertEquals(expected, plates.markers(roster));
                assertEquals(.45f, plates.pip(ATLAS).getColor().a, 1e-6, "A moved unit's pip is dimmed");
                assertEquals(1, plates.pip(MARAUDER).getColor().a, 1e-6);
                capture(hud, "nameplates-04", SHOT_04, new Crop("tag", plates.tag(WARHAMMER), 1255, 501));
                plates.dispose();

                // Shots 05 and 07: the Atlas declares attacks on two targets, whose cards replace their plates.
                GpuBattleStatus.Snapshot firing = status(moving, GamePhase.FIRING, true, ATLAS, roster);
                GpuHudData attacks = panels(fire(null), GpuPhysicalOptions.Snapshot.EMPTY);
                expected = pips(roster);
                expected.put(ATLAS, List.of("Atlas", ""));
                expected.put(CONTACT, CONTACT_TAG);
                expected.remove(TIMBER_WOLF);
                expected.remove(BATTLEMASTER);
                for (boolean tactical : new boolean[] { false, true }) {
                    plates = new Plates(hud);
                    heads = draw(hud, board, plates, tactical, List.of(firing), attacks, Entity.NONE);
                    assertEquals(10, heads.size(), "Every unit has a head");
                    // The Tactical View's bases carry the pips: its plates are the tags alone.
                    Map<Integer, List<String>> shown = new TreeMap<>(expected);
                    if (tactical) { shown.values().removeIf(PIP::equals); }
                    assertEquals(shown, plates.markers(roster));
                    plates.assertOnHeads(heads);
                    if (tactical) {
                        capture(hud, "nameplates-07", SHOT_07, new Crop("tag", plates.tag(ATLAS), 834, 626));
                    } else {
                        capture(hud, "nameplates-05", SHOT_05, new Crop("tag", plates.tag(ATLAS), 885, 731));
                    }
                    plates.dispose();
                }

                // Shot 08: the weapon playback has no focus unit; every unit shows its pip.
                List<UnitStatus> resolved = roster.stream()
                      .map(unit -> unit(unit, false, false, false, false, List.of())).toList();
                plates = new Plates(hud);
                draw(hud, board, plates, false, List.of(status(moving, GamePhase.FIRING_REPORT, false,
                      Entity.NONE, resolved)), none, Entity.NONE);
                expected = pips(roster);
                expected.put(CONTACT, CONTACT_TAG);
                assertEquals(expected, plates.markers(roster));
                capture(hud, "nameplates-08", SHOT_08, new Crop("pip", plates.pip(WARHAMMER), 1032, 685));
                plates.dispose();

                // Shot 09: the Atlas kicks the Timber Wolf, whose full tag shows as the physical target.
                plates = new Plates(hud);
                draw(hud, board, plates, false, List.of(status(moving, GamePhase.PHYSICAL, true, ATLAS,
                      roster)), panels(GpuFireOrders.Snapshot.EMPTY, new GpuPhysicalOptions.Snapshot(true, ATLAS,
                      TIMBER_WOLF, List.of(TIMBER_WOLF), List.of())), Entity.NONE);
                expected = pips(roster);
                expected.put(ATLAS, List.of("Atlas", ""));
                expected.put(TIMBER_WOLF, List.of("Timber Wolf", ""));
                expected.put(CONTACT, CONTACT_TAG);
                assertEquals(expected, plates.markers(roster));
                assertEquals(UiTheme.CORAL, ((Label) plates.tag(TIMBER_WOLF).getChild(0)).getColor(),
                      "An enemy's tag is coral");
                capture(hud, "nameplates-09", SHOT_09, new Crop("target", plates.tag(TIMBER_WOLF), 888, 339),
                      new Crop("tag", plates.tag(ATLAS), 913, 437));
                plates.dispose();
            } finally {
                board.dispose();
            }
        });
    }

    @Test
    void aLargeBattleShowsPipsWithinThePrototypesBoxes() throws Exception {
        BoardScene large = large(GpuBoardSpaceHarness.scene());
        GpuHudTestStage.run(hud -> {
            // Shot 14: 100 against 100; ten enemies are sensor contacts, shown only as "?" unless hovered.
            GpuBoardSpaceHarness battle = new GpuBoardSpaceHarness(large);
            try {
                List<UnitStatus> units = largeStatus();
                GpuBattleStatus.Snapshot moving = status(GpuHudFixtures.status(), GamePhase.MOVEMENT, true, ATLAS,
                      units);
                int hovered = units.stream().filter(UnitStatus::sensorContact).mapToInt(UnitStatus::id).max()
                      .orElseThrow();
                GpuHudData none = panels(GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY);
                Plates plates = new Plates(hud);
                Map<Integer, Vector2> heads = draw(hud, battle, plates, false, List.of(moving), none,
                      hovered);
                assertEquals(200, heads.size());
                for (UnitStatus unit : units) {
                    List<String> marker = plates.marker(unit.id());
                    if (unit.id() == ATLAS) {
                        assertEquals(List.of("Atlas", ""), marker);
                    } else if (unit.sensorContact()) {
                        assertEquals(unit.id() == hovered ? CONTACT_TAG : List.of("?", ""), marker);
                    } else {
                        assertEquals(PIP, marker, "unit " + unit.id());
                    }
                }
                plates.assertOnHeads(heads);

                // Each marker lies within the prototype's box for it at the same head, to the whole unit both round
                // their edges to, so markers overlap only where the prototype's would.
                Map<Integer, Rectangle> ours = new TreeMap<>();
                Map<Integer, Rectangle> prototype = new TreeMap<>();
                for (UnitStatus unit : units) {
                    Vector2 head = heads.get(unit.id());
                    Actor marker = plates.tag(unit.id()) != null ? plates.tag(unit.id()) : plates.pip(unit.id());
                    float[] box = unit.id() == ATLAS ? MOCK_ATLAS : unit.id() == hovered ? MOCK_CONTACT
                          : unit.sensorContact() ? MOCK_UNKNOWN : MOCK_PIP;
                    // The prototype rounds the anchor to whole pixels, then centres the box on it.
                    Rectangle mockBox = new Rectangle(Math.round(head.x) - box[0] / 2, Math.round(head.y), box[0],
                          box[1]);
                    Rectangle area = GpuHudTestStage.bounds(marker);
                    assertTrue(area.x >= mockBox.x - 1 && area.x + area.width <= mockBox.x + mockBox.width + 1
                          && area.y >= mockBox.y && area.y + area.height <= mockBox.y + mockBox.height,
                          "The marker of unit " + unit.id() + " at " + area + " leaves the prototype's " + mockBox);
                    ours.put(unit.id(), area);
                    prototype.put(unit.id(), mockBox);
                }
                System.out.println("Large battle: " + overlaps(ours) + " overlapping marker pairs; the prototype's "
                      + "boxes at the same heads " + overlaps(prototype));
                capture(hud, "nameplates-14", SHOT_14, new Crop("tag", plates.tag(ATLAS), 914, 570));

                // The cost of one frame's update, with the pips and with every tag (the nameplate key held).
                for (boolean all : new boolean[] { false, true }) {
                    plates.state.allNameplates = all;
                    plates.update(hud, moving, none, heads, false, hovered);
                    long start = System.nanoTime();
                    for (int frame = 0; frame < 100; frame++) {
                        plates.update(hud, moving, none, heads, false, hovered);
                    }
                    System.out.printf("Large battle, %s: %.3f ms per update%n", all ? "every tag" : "pips",
                          (System.nanoTime() - start) / 100 / 1e6);
                }
                plates.dispose();
            } finally {
                battle.dispose();
            }
        });
    }

    @Test
    void theNameplateKeyHoverInspectionAndStatusChooseTheTag() {
        GpuHudTestStage.run(hud -> {
            List<UnitStatus> roster = new ArrayList<>(GpuHudFixtures.status().units());
            UnitStatusWords.StatusWord prone = new UnitStatusWords.StatusWord("PRONE", "PRONE",
                  UnitStatusWords.Severity.CAUTION);
            roster.set(KING_CRAB - 1, unit(roster.get(KING_CRAB - 1), false, true, false, false, List.of(prone)));
            roster.set(ENEMY_LOCUST - 1, unit(roster.get(ENEMY_LOCUST - 1), false, false, false, true, List.of()));
            GpuBattleStatus.Snapshot moving = status(GpuHudFixtures.status(), GamePhase.MOVEMENT, true, ATLAS,
                  roster);
            GpuHudData none = panels(GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY);
            Map<Integer, Vector2> heads = grid(roster);
            Plates plates = new Plates(hud);

            // The nameplate key held: every unit's tag, with its model and status words.
            plates.state.allNameplates = true;
            plates.update(hud, moving, none, heads, false, Entity.NONE);
            assertEquals(allTags(), plates.markers(roster));
            plates.assertOnHeads(heads);
            hud.draw();
            opaque();
            hud.capture("nameplates-alt").dispose();

            // Released: the pips return, a destroyed unit's at .3.
            plates.state.allNameplates = false;
            plates.update(hud, moving, none, heads, false, Entity.NONE);
            Map<Integer, List<String>> expected = pips(roster);
            expected.put(ATLAS, List.of("Atlas", ""));
            expected.put(CONTACT, CONTACT_TAG);
            assertEquals(expected, plates.markers(roster));
            assertEquals(.3f, plates.pip(ENEMY_LOCUST).getColor().a, 1e-6, "A destroyed unit's pip is faint");
            assertEquals(UiTheme.CORAL.r, plates.pip(TIMBER_WOLF).getColor().r, 1e-6);
            assertEquals(UiTheme.MINT.g, plates.pip(WARHAMMER).getColor().g, 1e-6);

            // The Always setting shows every tag without the key.
            plates.preferences = GpuHudInputTest.with(GpuHudInputTest.preferences(),
                  Map.of("nameplates", NameplateMode.ALWAYS));
            plates.update(hud, moving, none, heads, false, Entity.NONE);
            assertEquals(allTags(), plates.markers(roster));
            plates.preferences = GpuHudInputTest.preferences();

            // Hovering an enemy in the movement phase gives its distance from the focus unit; a friend, its model.
            plates.update(hud, moving, none, heads, false, TIMBER_WOLF);
            assertEquals(List.of("Timber Wolf", "9 hex"), plates.marker(TIMBER_WOLF));
            plates.update(hud, moving, none, heads, false, WARHAMMER);
            assertEquals(List.of("Warhammer", ""), plates.marker(WARHAMMER));
            assertEquals(PIP, plates.marker(TIMBER_WOLF));

            // An inspected unit keeps its tag without the hover.
            plates.state.inspected = BATTLEMASTER;
            plates.update(hud, moving, none, heads, false, Entity.NONE);
            assertEquals(List.of("BattleMaster", ""), plates.marker(BATTLEMASTER));
            plates.state.inspected = Entity.NONE;

            // Declaring attacks: the hovered enemy shows the actor's best roll after its status words; an automatic
            // success reads 2+, no roll gives MegaMek's reason, and a roll on another unit is not this unit's.
            GpuBattleStatus.Snapshot firing = status(moving, GamePhase.FIRING, true, ATLAS, roster);
            plates.update(hud, firing, panels(fire(new GpuFireOrders.Badge(KING_CRAB, 7, 58.3, "")),
                  GpuPhysicalOptions.Snapshot.EMPTY), heads, false, KING_CRAB);
            assertEquals(List.of("King Crab", "PRONE " + DOT + " best 7+ " + DOT + " 58%"),
                  plates.marker(KING_CRAB));
            roster.set(KING_CRAB - 1, unit(roster.get(KING_CRAB - 1), false, true, false, false, List.of()));
            firing = status(moving, GamePhase.FIRING, true, ATLAS, roster);
            plates.update(hud, firing, panels(fire(new GpuFireOrders.Badge(KING_CRAB, 7, 58.3, "")),
                  GpuPhysicalOptions.Snapshot.EMPTY), heads, false, KING_CRAB);
            assertEquals(List.of("King Crab", "best 7+ " + DOT + " 58%"),
                  plates.marker(KING_CRAB));
            plates.update(hud, firing, panels(fire(new GpuFireOrders.Badge(KING_CRAB, TargetRoll.AUTOMATIC_SUCCESS,
                  100, "")), GpuPhysicalOptions.Snapshot.EMPTY), heads, false, KING_CRAB);
            assertEquals(List.of("King Crab", "best 2+ " + DOT + " 100%"),
                  plates.marker(KING_CRAB));
            plates.update(hud, firing, panels(fire(new GpuFireOrders.Badge(KING_CRAB, TargetRoll.IMPOSSIBLE, 0,
                  "no shot")), GpuPhysicalOptions.Snapshot.EMPTY), heads, false, KING_CRAB);
            assertEquals(List.of("King Crab", "no shot"), plates.marker(KING_CRAB));
            plates.update(hud, firing, panels(fire(new GpuFireOrders.Badge(ENEMY_LOCUST, 9, 27.8, "")),
                  GpuPhysicalOptions.Snapshot.EMPTY), heads, false, KING_CRAB);
            assertEquals(List.of("King Crab", ""), plates.marker(KING_CRAB));

            // The targets' cards replace their plates even under the hover.
            plates.update(hud, firing, panels(fire(null), GpuPhysicalOptions.Snapshot.EMPTY), heads, false,
                  TIMBER_WOLF);
            assertNull(plates.marker(TIMBER_WOLF), "The Timber Wolf's target card replaces its plate");
            assertNull(plates.marker(BATTLEMASTER));
            hud.draw();
            opaque();
            hud.capture("nameplates-hover-firing").dispose();

            plates.dispose();

            // A firing phase that starts with the opponent's turn: the focus unit's tag names it without a phase
            // word, and a hovered enemy gives its distance from the focus unit.
            plates = new Plates(hud);
            plates.update(hud, status(moving, GamePhase.FIRING, false, TIMBER_WOLF, roster), none, heads, false,
                  TIMBER_WOLF);
            assertEquals(List.of("Atlas", ""), plates.marker(ATLAS));
            assertEquals(List.of("Timber Wolf", "9 hex"), plates.marker(TIMBER_WOLF));

            // The tag names the unit as the client's label style does; a style that names none leaves the tag its
            // status words, and a unit without words its pip, even under the hover.
            List<UnitStatus> named = new ArrayList<>(roster);
            named.set(ATLAS - 1, labelled(roster.get(ATLAS - 1), "\"ACE\" (AS7-D)"));
            named.set(WARHAMMER - 1, labelled(roster.get(WARHAMMER - 1), ""));
            named.set(KING_CRAB - 1, labelled(unit(roster.get(KING_CRAB - 1), false, true, false, false,
                  List.of(prone)), ""));
            plates.state.inspected = KING_CRAB;
            plates.update(hud, status(moving, GamePhase.MOVEMENT, true, ATLAS, named), none, heads, false,
                  WARHAMMER);
            assertEquals(List.of("\"ACE\" (AS7-D)", ""), plates.marker(ATLAS));
            assertEquals(PIP, plates.marker(WARHAMMER), "Nothing to show: the hovered unit keeps its pip");
            assertEquals(List.of("", "PRONE"), plates.marker(KING_CRAB));
            plates.state.inspected = Entity.NONE;

            // A unit without a head (behind the camera, or not drawn) has no plate.
            Map<Integer, Vector2> fewer = new HashMap<>(heads);
            fewer.remove(MARAUDER);
            plates.update(hud, moving, none, fewer, false, Entity.NONE);
            assertNull(plates.marker(MARAUDER));
            assertEquals(PIP, plates.marker(PANTHER));
            plates.dispose();
        });
    }

    /**
     * The tag of the unit the card shows lies over every other tag and the hovered
     * unit's over the rest; without either the tags keep the units' order. With the nameplate key held every unit has
     * a tag; the King Crab's overlaps the Timber Wolf's, which comes first.
     */
    @Test
    void theShownUnitsTagLiesOverTheOthersAndTheOrderReturnsWithoutIt() {
        GpuHudTestStage.run(hud -> {
            List<UnitStatus> roster = GpuHudFixtures.status().units();
            GpuBattleStatus.Snapshot moving = status(GpuHudFixtures.status(), GamePhase.MOVEMENT, false, TIMBER_WOLF,
                  roster);
            GpuHudData none = panels(GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY);
            Map<Integer, Vector2> heads = grid(roster);
            heads.put(TIMBER_WOLF, new Vector2(900, 500));
            heads.put(KING_CRAB, new Vector2(930, 506));
            Plates plates = new Plates(hud);
            plates.state.allNameplates = true;
            plates.update(hud, moving, none, heads, false, Entity.NONE);
            Table wolf = plates.tag(TIMBER_WOLF);
            Table crab = plates.tag(KING_CRAB);
            assertTrue(GpuHudTestStage.bounds(wolf).overlaps(GpuHudTestStage.bounds(crab)), "The two tags overlap");
            assertTrue(wolf.getZIndex() < crab.getZIndex(), "In the units' order the King Crab's is drawn last");

            // The Timber Wolf inspected: its tag lies over the King Crab's, also while the King Crab is hovered.
            plates.state.inspected = TIMBER_WOLF;
            plates.update(hud, moving, none, heads, false, Entity.NONE);
            assertTrue(wolf.getZIndex() > crab.getZIndex(), "The shown unit's tag is drawn last");
            hud.draw();
            opaque();
            hud.capture("nameplates-order-shown").dispose();
            plates.update(hud, moving, none, heads, false, KING_CRAB);
            assertTrue(wolf.getZIndex() > crab.getZIndex(), "The shown unit wins over the hovered one");

            // Deselected, the hovered Timber Wolf's tag is drawn last; without the hover the units' order returns.
            plates.state.inspected = Entity.NONE;
            plates.update(hud, moving, none, heads, false, TIMBER_WOLF);
            assertTrue(wolf.getZIndex() > crab.getZIndex(), "The hovered unit's tag is drawn last");
            plates.update(hud, moving, none, heads, false, Entity.NONE);
            assertTrue(wolf.getZIndex() < crab.getZIndex(), "The order returns to the units'");
            plates.dispose();
        });
    }

    @Test
    void theNameplateKeyShowsEveryPlateWhileItIsHeld() {
        GpuHudTestStage.run(harness -> {
            SpriteBatch batch = new SpriteBatch();
            GpuBoardSource source = mock(GpuBoardSource.class);
            when(source.record()).thenReturn(mock(GpuUnitRecord.class));
            GpuHud hud = new GpuHud(source, harness.theme.skin, batch, new BoardCamera(), mock(GpuBoardTuning.class),
                  new GpuPlaybackHistory(new UnitPlayback()));
            try {
                float density = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
                hud.resize(Math.round(harness.width() / density), Math.round(harness.height() / density),
                      1 / density);
                // Shot 04's turn: the Atlas has moved, the opponent moves and the Warhammer is up next.
                List<UnitStatus> roster = new ArrayList<>(GpuHudFixtures.status().units());
                roster.set(ATLAS - 1, unit(roster.get(ATLAS - 1), false, false, true, false, List.of()));
                GpuBoardSource.Frame frame = GpuHudInputTest.frame(status(GpuHudFixtures.status(),
                      GamePhase.MOVEMENT, false, TIMBER_WOLF, roster), panels(GpuFireOrders.Snapshot.EMPTY,
                      GpuPhysicalOptions.Snapshot.EMPTY));
                GpuHud.HudView view = new GpuHud.HudView(false, false, Map.of(), grid(roster), Map.of(), null,
                      Entity.NONE, 0);
                GpuBoardSource.UiPreferences preferences = GpuHudInputTest.preferences();
                Group root = hud.stage.getRoot();
                Map<Integer, List<String>> pips = pips(roster);
                pips.put(WARHAMMER, List.of("Warhammer", ""));
                pips.put(CONTACT, CONTACT_TAG);
                hud.update(frame, view, null, preferences);
                assertEquals(pips, markers(root, roster));
                assertTrue(root.findActor("nameplates").isDescendantOf(root.getChild(0)),
                      "The plates lie in the HUD's lowest layer");

                // SHOW_NAMEPLATES (Alt) held: every unit's tag from the next frame on, until it is released.
                assertTrue(hud.keyDown(Input.Keys.ALT_LEFT, KeyEvent.VK_ALT, ALT_DOWN_MASK));
                hud.update(frame, view, null, preferences);
                Map<Integer, List<String>> all = allTags();
                all.put(ATLAS, List.of("Atlas", ""));
                all.put(WARHAMMER, List.of("Warhammer", ""));
                all.put(KING_CRAB, List.of("King Crab", ""));
                all.put(ENEMY_LOCUST, List.of("Locust", ""));
                assertEquals(all, markers(root, roster));
                assertEquals("A 3/4", line(root.findActor("nameplate-" + ATLAS), 1),
                      "A tag's chip: the weight class letter and the gunnery and piloting skills");
                // The prototype's plates take no pointer events: a press on one reaches the board.
                Rectangle tag = GpuHudTestStage.bounds(root.findActor("nameplate-" + KING_CRAB));
                Vector2 press = hud.stage.stageToScreenCoordinates(new Vector2(tag.x + tag.width / 2,
                      tag.y + tag.height / 2));
                assertFalse(hud.hit(Math.round(press.x), Math.round(press.y)), "A press on a plate reaches the board");
                ScreenUtils.clear(.42f, .5f, .3f, 1, true);
                hud.draw();
                opaque();
                harness.capture("nameplates-key-held").dispose();
                assertTrue(hud.keyUp(Input.Keys.ALT_LEFT, KeyEvent.VK_ALT));
                hud.update(frame, view, null, preferences);
                assertEquals(pips, markers(root, roster), "Released, the pips return");

                // Losing the window's focus ends the key, as no release arrives then.
                hud.keyDown(Input.Keys.ALT_LEFT, KeyEvent.VK_ALT, ALT_DOWN_MASK);
                hud.update(frame, view, null, preferences);
                assertEquals(all, markers(root, roster));
                hud.focusLost();
                hud.update(frame, view, null, preferences);
                assertEquals(pips, markers(root, roster), "The focus loss ends the plates");

                // Alt with a focused grip belongs to its reordering, not to the plates.
                Actor grip = new Actor();
                hud.stage.addActor(grip);
                hud.stage.setKeyboardFocus(grip);
                hud.keyDown(Input.Keys.ALT_LEFT, KeyEvent.VK_ALT, ALT_DOWN_MASK);
                hud.update(frame, view, null, preferences);
                assertEquals(pips, markers(root, roster), "No plates for Alt with a focused grip");
            } finally {
                hud.dispose();
                batch.dispose();
            }
        });
    }

    /**
     * The Legacy 2D board label's marks (the user's request of 2026-10-07): every unit but the sensor contact and the
     * destroyed Locust has its damage tile, if any, and its bars bottom-centred on its head, with its pip or tag 2
     * above them in 3D; in the Tactical View the units' bases show them, so there are neither marks nor pips.
     */
    @Test
    void marksSitOnTheHeadUnderTheMarker() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        GUIPreferences preferences = GUIPreferences.getInstance();
        int green = new java.awt.Color(16, 196, 16).getRGB();
        int caution = preferences.getCautionColor().getRGB();
        int warning = preferences.getWarningColor().getRGB();
        int black = java.awt.Color.BLACK.getRGB();
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene, hud.theme.skin);
            try {
                List<UnitStatus> roster = new ArrayList<>(GpuHudFixtures.status().units());
                Map<Integer, GpuBattleStatus.Marks> marks = Map.of(
                      ATLAS, new GpuBattleStatus.Marks(caution, caution, green),
                      WARHAMMER, new GpuBattleStatus.Marks(0, green, green),
                      MARAUDER, new GpuBattleStatus.Marks(java.awt.Color.GREEN.getRGB(), green, green),
                      PANTHER, new GpuBattleStatus.Marks(warning, warning, caution),
                      LOCUST, new GpuBattleStatus.Marks(black, warning, warning),
                      TIMBER_WOLF, new GpuBattleStatus.Marks(warning, warning, green),
                      KING_CRAB, new GpuBattleStatus.Marks(0, green, green),
                      BATTLEMASTER, new GpuBattleStatus.Marks(0, caution, 0),
                      ENEMY_LOCUST, new GpuBattleStatus.Marks(black, warning, warning));
                double[][] shares = { { .6, .95 }, { 1, 1 }, { .8, 1 }, { .2, .5 }, { .1, .2 }, { .2, .9 },
                      { 1, 1 }, { .7, 1 }, { -1, -1 }, { 0, 0 } };
                for (int index = 0; index < roster.size(); index++) {
                    UnitStatus unit = roster.get(index);
                    roster.set(index, labelled(unit, unit.label(), shares[index][0], shares[index][1],
                          marks.getOrDefault(unit.id(), GpuBattleStatus.Marks.NONE), unit.id() == ENEMY_LOCUST));
                }
                GpuBattleStatus.Snapshot moving = status(GpuHudFixtures.status(), GamePhase.MOVEMENT, true, ATLAS,
                      roster);
                GpuHudData none = panels(GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY);
                for (boolean tactical : new boolean[] { false, true }) {
                    Plates plates = new Plates(hud);
                    Map<Integer, Vector2> heads = draw(hud, board, plates, tactical, List.of(moving), none,
                          Entity.NONE);
                    assertEquals(10, heads.size(), "Every unit has a head");
                    for (UnitStatus unit : roster) {
                        Vector2 head = heads.get(unit.id());
                        Actor shown = plates.marks(unit.id());
                        if (tactical) {
                            assertNull(shown, "The base shows the marks of " + unit.id());
                            assertNull(plates.pip(unit.id()), "and its pip");
                            continue;
                        }
                        Actor marker = plates.tag(unit.id()) != null ? plates.tag(unit.id()) : plates.pip(unit.id());
                        float markerBottom = GpuHudTestStage.bounds(marker).y;
                        if (unit.sensorContact() || unit.destroyed()) {
                            assertNull(shown, "No marks for unit " + unit.id());
                            assertEquals(head.y, markerBottom, .5f, "The marker on the head of " + unit.id());
                            continue;
                        }
                        Rectangle area = GpuHudTestStage.bounds(shown);
                        assertEquals(head.x, area.x + area.width / 2, 1, "The marks centred on " + unit.id());
                        assertEquals(head.y, area.y, .5f, "The marks on the head of " + unit.id());
                        assertEquals(area.y + area.height + 2, markerBottom, .5f, "The marker over " + unit.id());
                        boolean tile = unit.marks().damageArgb() != 0;
                        boolean structure = unit.marks().structureArgb() != 0;
                        assertEquals(tile ? 40 : 28, area.width, "The width of the marks of " + unit.id());
                        assertEquals(tile ? 13 : structure ? 11 : 7, area.height, "The height of " + unit.id());
                    }
                    opaque();
                    hud.capture(tactical ? "nameplates-marks-tactical" : "nameplates-marks").dispose();
                    plates.dispose();
                }
            } finally {
                board.dispose();
            }
        });
    }

    /** A unit with this label; everything else as captured. */
    private static UnitStatus labelled(UnitStatus unit, String label) {
        return labelled(unit, label, unit.armor(), unit.structure(), unit.marks(), unit.destroyed());
    }

    /** A unit with this label, these armor and structure shares, marks and destruction; everything else as captured. */
    private static UnitStatus labelled(UnitStatus unit, String label, double armor, double structure,
          GpuBattleStatus.Marks marks, boolean destroyed) {
        return new UnitStatus(unit.id(), unit.side(), unit.sensorContact(), unit.name(), unit.chassis(), unit.model(),
              unit.tons(), unit.weightClass(), unit.formation(), unit.pilot(), unit.gunnery(), unit.piloting(),
              armor, structure, unit.heat(), unit.heatRgb(), unit.heatCapacity(), unit.walk(), unit.run(),
              unit.jump(), unit.moved(), unit.mpUsed(), unit.hexesMoved(), unit.facing(), unit.tmm(),
              unit.canActNow(), unit.pending(), unit.done(), destroyed, unit.damageLevel(),
              unit.destroyedLocations(), unit.heatEffects(), unit.position(), unit.boardId(), unit.icon(),
              unit.statusWords(), unit.weightClassIndex(), unit.declaredAttacks(), unit.ownerId(),
              unit.statusTiles(), label, marks);
    }

    @Test
    void platesBehindTheCameraAreHidden() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene);
            try {
                GpuBattleStatus.Snapshot moving = GpuHudFixtures.status();
                board.view(false);
                // The orthographic board camera keeps x and y when its eye moves along the view direction: moved
                // to the middle of the board, the own units (south) lie behind it at their usual screen places.
                Camera camera = board.camera.camera;
                Vector3 middle = BoardGeometry.center(new Coords(14, 9), 0);
                camera.position.mulAdd(camera.direction, new Vector3(middle).sub(camera.position)
                      .dot(camera.direction));
                camera.update();
                board.draw(unused -> { });
                Plates plates = new Plates(hud);
                Map<Integer, Vector2> heads = heads(camera, board.anchors);
                plates.update(hud, moving, panels(GpuFireOrders.Snapshot.EMPTY, GpuPhysicalOptions.Snapshot.EMPTY),
                      heads, false, Entity.NONE);
                int behind = 0;
                for (Map.Entry<BoardScene.Unit, Vector3> entry : board.anchors.entrySet()) {
                    int id = entry.getKey().id();
                    Vector3 anchor = entry.getValue();
                    if (new Vector3(anchor).sub(camera.position).dot(camera.direction) < camera.near) {
                        behind++;
                        Vector3 mirror = camera.project(new Vector3(anchor));
                        assertTrue(mirror.x > 0 && mirror.x < camera.viewportWidth && mirror.y > 0
                              && mirror.y < camera.viewportHeight, "Without the rule unit " + id + " would show");
                        assertNull(heads.get(id), "No head behind the camera for " + id);
                        assertNull(plates.marker(id), "No plate behind the camera for " + id);
                    } else {
                        assertNotNull(plates.marker(id), "A plate in front of the camera for " + id);
                    }
                }
                assertTrue(behind >= 3 && behind <= 7, behind + " of 10 units behind the camera");
                hud.drawStage();
                opaque();
                hud.capture("nameplates-behind-camera").dispose();
                plates.dispose();
            } finally {
                board.dispose();
            }
        });
    }

    /**
     * Draws the board in 3D or in the Tactical View (its meeples on their bases), then shows each status in turn to
     * the plates with the heads of the drawn units, and draws them over the board.
     */
    private static Map<Integer, Vector2> draw(GpuHudTestStage hud, GpuBoardSpaceHarness board, Plates plates, boolean tactical, List<GpuBattleStatus.Snapshot> statuses, GpuHudData panels, int hovered) {
        GpuBattleStatus.Snapshot status = statuses.getLast();
        board.view(tactical);
        if (!tactical) {
            // Nearer to the 3D shots' framing: the roster at 1.8x the fitted view, the large battle at 1.25x.
            closeUp(board.camera.camera, board.scene.units().size() > 10 ? 1.25f : 1.8f);
        }
        board.basesStatus = status;
        board.draw(camera -> { });
        Map<Integer, Vector2> heads = heads(board.camera.camera, board.anchors);
        for (GpuBattleStatus.Snapshot shown : statuses) {
            plates.update(hud, shown, panels, heads, tactical, hovered);
        }
        hud.drawStage();
        return heads;
    }

    /** Zooms the fitted view in by {@code factor} around the middle of the board's two sides. */
    private static void closeUp(BoardProjectionCamera camera, float factor) {
        camera.position.set(BoardGeometry.center(new Coords(15, 8), 0)).mulAdd(camera.direction, -10000);
        camera.zoom /= factor;
        camera.update();
    }

    /** The anchors' heads in stage units (back-buffer pixels), as the board view hands them to the HUD. */
    static Map<Integer, Vector2> heads(Camera camera, Map<BoardScene.Unit, Vector3> anchors) {
        float density = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
        Map<Integer, Vector2> heads = new HashMap<>();
        anchors.forEach((unit, anchor) -> {
            Vector2 head = GpuNameplates.project(camera, anchor);
            if (head != null) {
                heads.put(unit.id(), head.scl(density));
            }
        });
        return heads;
    }

    /** Heads apart on a grid of five columns, so that every unit's tag can show without touching another. */
    private static Map<Integer, Vector2> grid(List<UnitStatus> units) {
        Map<Integer, Vector2> heads = new HashMap<>();
        for (UnitStatus unit : units) {
            heads.put(unit.id(), new Vector2(200 + (unit.id() - 1) % 5 * 380, 700 - (unit.id() - 1) / 5 * 300));
        }
        return heads;
    }

    /**
     * Every roster unit's full tag as the nameplate key shows it: its label (the fixtures' chassis) over its status
     * words, the King Crab prone and the enemy Locust destroyed.
     */
    private static Map<Integer, List<String>> allTags() {
        return new TreeMap<>(Map.of(ATLAS, List.of("Atlas", ""), WARHAMMER, List.of("Warhammer", ""),
              MARAUDER, List.of("Marauder", ""), PANTHER, List.of("Panther", ""), LOCUST, List.of("Locust", ""),
              TIMBER_WOLF, List.of("Timber Wolf", ""), KING_CRAB, List.of("King Crab", "PRONE"), BATTLEMASTER,
              List.of("BattleMaster", ""), CONTACT, CONTACT_TAG, ENEMY_LOCUST, List.of("Locust", "destroyed")));
    }

    /** The number of marker pairs whose boxes overlap by more than half a unit each way. */
    private static int overlaps(Map<Integer, Rectangle> boxes) {
        List<Rectangle> list = List.copyOf(boxes.values());
        int pairs = 0;
        Rectangle overlap = new Rectangle();
        for (int first = 0; first < list.size(); first++) {
            for (int second = first + 1; second < list.size(); second++) {
                if (Intersector.intersectRectangles(list.get(first), list.get(second), overlap)
                      && overlap.width > .5f && overlap.height > .5f) {
                    pairs++;
                }
            }
        }
        return pairs;
    }

    /** The unit's marker under {@code root}: its tag's name and sub-line, {@link #PIP}, or null without one. */
    private static List<String> marker(Group root, int id) {
        Table tag = root.findActor("nameplate-" + id);
        if (tag != null) {
            return List.of(line(tag, 0), line(tag, 2));
        }
        return root.findActor("pip-" + id) != null ? PIP : null;
    }

    /** Every unit's marker by id under {@code root}, for the ids that have one. */
    private static Map<Integer, List<String>> markers(Group root, List<UnitStatus> units) {
        Map<Integer, List<String>> markers = new TreeMap<>();
        for (UnitStatus unit : units) {
            List<String> marker = marker(root, unit.id());
            if (marker != null) {
                markers.put(unit.id(), marker);
            }
        }
        return markers;
    }

    /** A plate to compare, and the top-left corner of the plate it matches in the mock shot. */
    private record Crop(String part, Actor plate, int x, int y) { }

    /** Writes {name}.png and each crop beside the same place of the mock shot, {name}-{part}-vs-mock.png. */
    private static void capture(GpuHudTestStage hud, String name, String shot, Crop... crops) {
        opaque();
        Pixmap captured = hud.capture(name);
        try {
            for (Crop crop : crops) {
                hud.compare(name + "-" + crop.part(), captured, crop.plate(), shot, crop.x(), crop.y());
            }
        } finally {
            captured.dispose();
        }
    }

    /**
     * Makes the back buffer opaque without changing its colours. Translucent plates leave its alpha below 1, which
     * the captured PNGs would keep, so image viewers would show them lighter than the window does.
     */
    static void opaque() {
        Gdx.gl.glColorMask(false, false, false, true);
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glColorMask(true, true, true, true);
    }

    /** Every roster unit's pip, to be overridden by the units that show a tag or nothing. */
    private static Map<Integer, List<String>> pips(List<UnitStatus> units) {
        Map<Integer, List<String>> pips = new TreeMap<>();
        units.forEach(unit -> pips.put(unit.id(), PIP));
        return pips;
    }

    /** A tag's name (0), chip (1: weight class and skills) or sub-line (2). */
    private static String line(Table tag, int index) {
        return ((Label) tag.getChild(index)).getText().toString();
    }

    private static GpuHudData panels(GpuFireOrders.Snapshot fire, GpuPhysicalOptions.Snapshot physical) {
        return GpuHudInputTest.panels(GpuMovePlan.Snapshot.EMPTY, fire, physical, GpuUnitRecord.Snapshot.EMPTY);
    }

    /** The Atlas's orders of shots 05-07: the Timber Wolf (A, primary, focused) and the BattleMaster (B). */
    private static GpuFireOrders.Snapshot fire(GpuFireOrders.Badge hoverBest) {
        return new GpuFireOrders.Snapshot(true, true, ATLAS,
              new GpuFireOrders.Focus(TargetKey.unit(TIMBER_WOLF), "Timber Wolf", null), -1, List.of(),
              List.of(new GpuFireOrders.Target(TargetKey.unit(TIMBER_WOLF), 'A', "Timber Wolf", true, 0, true, null),
                    new GpuFireOrders.Target(TargetKey.unit(BATTLEMASTER), 'B', "BattleMaster", false, 1, true,
                          null)), List.of(), 0, false, false, "", null, null, null, List.of(), hoverBest, Map.of(), 0,
              0, null);
    }

    /** The mock status in another phase, turn and actor, with the given units. */
    private static GpuBattleStatus.Snapshot status(GpuBattleStatus.Snapshot mock, GamePhase phase, boolean myTurn,
          int actor, List<UnitStatus> units) {
        return new GpuBattleStatus.Snapshot(mock.round(), phase, myTurn, mock.localPlayerId(), actor, mock.turns(),
              mock.turnIndex(), units, mock.initiative(), false);
    }

    /** A unit with other turn flags, destruction and status words; everything else as captured. */
    static UnitStatus unit(UnitStatus unit, boolean canActNow, boolean pending, boolean done,
          boolean destroyed, List<UnitStatusWords.StatusWord> words) {
        return copy(unit, unit.id(), unit.position(), canActNow, pending, done, destroyed, words);
    }

    private static UnitStatus copy(UnitStatus unit, int id, Coords position, boolean canActNow, boolean pending,
          boolean done, boolean destroyed, List<UnitStatusWords.StatusWord> words) {
        return new UnitStatus(id, unit.side(), unit.sensorContact(), unit.name(), unit.chassis(), unit.model(),
              unit.tons(), unit.weightClass(), unit.formation(), unit.pilot(), unit.gunnery(), unit.piloting(),
              unit.armor(), unit.structure(), unit.heat(), unit.heatRgb(), unit.heatCapacity(), unit.walk(),
              unit.run(), unit.jump(), unit.moved(), unit.mpUsed(), unit.hexesMoved(), unit.facing(), unit.tmm(),
              canActNow, pending, done, destroyed, unit.damageLevel(), unit.destroyedLocations(),
              unit.heatEffects(), position, unit.boardId(), unit.icon(), words, unit.weightClassIndex(),
              unit.declaredAttacks(), unit.ownerId(), unit.statusTiles());
    }

    /** The large battle's hexes: 100 own units in four rows in the south, 100 enemies in four rows in the north. */
    private static Coords place(int index) {
        boolean own = index < 100;
        int slot = index % 100;
        return new Coords(3 + slot % 25, (own ? 9 : 1) + slot / 25 * 2);
    }

    /** The large battle on the harness board: the roster's units repeated, every tenth enemy a sensor contact. */
    private static BoardScene large(BoardScene scene) {
        List<BoardScene.Unit> roster = scene.units();
        List<BoardScene.Unit> units = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            BoardScene.Unit template = roster.get(templateIndex(index));
            Coords coords = place(index);
            units.add(new BoardScene.Unit(index + 1, -1, template.name(), new BoardScene.Waypoint(coords,
                  scene.tile(coords).elevation(), template.location().facing()), template.image(),
                  template.sensorContact(), null, template.height(), false));
        }
        return scene.withUnits(units);
    }

    /** The statuses of the large battle's units, in the same order and places. */
    private static List<UnitStatus> largeStatus() {
        List<UnitStatus> roster = GpuHudFixtures.status().units();
        List<UnitStatus> units = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            units.add(copy(roster.get(templateIndex(index)), index + 1, place(index), index == 0, index < 100,
                  false, false, List.of()));
        }
        return units;
    }

    /** Own units cycle through the five own roster units, enemies through the enemies with every tenth a contact. */
    private static int templateIndex(int index) {
        if (index < 100) {
            return index % 5;
        }
        int enemy = index - 100;
        return enemy % 10 == 9 ? CONTACT - 1 : new int[] { 5, 6, 7, 9 }[enemy % 4];
    }
}
