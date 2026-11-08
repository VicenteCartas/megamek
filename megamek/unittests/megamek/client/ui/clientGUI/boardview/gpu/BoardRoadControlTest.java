/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardRoadControlTest {
    private static final Coords AT = new Coords(3, 3);

    private static Hex support(Hex hex, Coords at) {
        return at.equals(AT) ? hex : new Hex(0, "", "");
    }

    private static List<BoardScene.Feature> controls(Hex hex) {
        return BoardFeatures.roadControls(hex, AT, at -> support(hex, at), true);
    }

    private static boolean northernControl(Hex hex, Hex north) {
        return BoardFeatures.roadControls(hex, AT, at -> at.equals(AT) ? hex : at.equals(AT.translated(0)) ? north : null, true)
              .stream().anyMatch(feature -> feature.decoration().id().endsWith(":stop:0"));
    }

    private static void stops(Hex hex, boolean deck) {
        var appearance = new java.util.HashMap<>(hex.getAppearance());
        String owner = deck ? "bridgeRoad" : "road";
        appearance.put(owner, HexAppearance.withTrafficControl(appearance.get(owner), HexAppearance.TrafficControl.STOPS));
        hex.setAppearance(appearance);
    }

    @Test
    void roadsAndBridgeDecksDefaultToNativeGantrySignalsWithoutStoredAppearance() {
        for (String terrain : List.of("road:1:27", "bridge:1:27;bridge_cf:40;bridge_elev:2;bridge_road:1:27")) {
            Hex hex = new Hex(0, terrain, "");
            assertTrue(hex.getAppearance().isEmpty());
            var controls = controls(hex);
            assertEquals(4, controls.size());
            for (var control : controls) {
                assertEquals("scenery/road/traffic-signal-gantry", control.asset(), terrain + " " + control.decoration().id());
                assertEquals(megamek.common.board.BoardDecoration.Stretch.NONE, control.stretch(),
                      "The ordinary X crossing uses the authored mast and lamp proportions by default");
            }
            assertTrue(hex.getAppearance().isEmpty(), "Derived defaults do not add stored overrides");
        }
    }

    @Test
    void signsFaceWaitingTrafficAndBelongToTheRoad() {
        Hex hex = new Hex(0, "road:1:27", "");
        stops(hex, false);
        var signs = controls(hex);
        assertEquals(4, signs.size());
        assertTrue(hex.getDecorations().isEmpty(), "Derived roadside furniture is never saved as placed objects");
        var road = BoardRoad.layout(AT, 27, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.NONE);
        for (var sign : signs) {
            assertEquals("scenery/road/stop-sign", sign.asset());
            assertTrue(BoardFeatures.terrainOwned(sign.decoration()), "Controls cannot be selected as loose props");
            assertEquals("ground", sign.decoration().placement().receiver().terrain());
            assertFalse(road.footprint(0).contains(sign.x(), sign.y()), "The pole belongs beside the carriageway");
            double angle = Math.toRadians(sign.rotation());
            double facingX = -Math.sin(angle), facingY = Math.cos(angle);
            int direction = Integer.parseInt(sign.decoration().id().substring(sign.decoration().id().lastIndexOf(':') + 1));
            var approach = road.stopApproaches(27).stream().filter(a -> a.direction() == direction).findFirst().orElseThrow();
            assertEquals(1, facingX * approach.nx() + facingY * approach.ny(), .0001, "Faces point towards waiting traffic");
            assertTrue(facingX * sign.y() - facingY * sign.x() > approach.outer(), "Signs stand outside the incoming lane");
        }
        assertEquals(signs, controls(hex), "Recapture keeps stable ids and transforms");
        hex.addTerrain(new Terrain(Terrains.ROAD, 1, true, 9));
        assertTrue(controls(hex).isEmpty(), "Removing a branch removes its junction controls");
    }

    @Test
    void militaryBaseApronEntrancesDoNotLeaveSignsAfterTheirStopBarsDisappear() {
        var board = new Board();
        board.load(new File("data/boards/buildingsnobasement/militarybase2.board"));
        BoardSceneryLayouts.convert(board);
        var scene = new BoardScene(0, board.getWidth(), board.getHeight(), BoardTiles.captureAll(board),
              List.of(), List.of(), -1, "", List.of());
        for (var at : List.of(new Coords(9, 12), new Coords(6, 16), new Coords(9, 15))) {
            var tile = scene.tile(at);
            assertTrue(BoardRoad.of(scene, tile).stopApproaches(tile.roadExits()).isEmpty(),
                  "The sloped and level apron mouths have no stopped road junction: " + at.getBoardNum());
            assertTrue(tile.features().stream().noneMatch(feature -> feature.decoration() != null
                        && feature.asset().startsWith("scenery/road/")
                        && BoardFeatures.terrainOwned(feature.decoration())),
                  "The captured scene must not retain a physical control without its stop bar: " + at.getBoardNum());
            for (var control : HexAppearance.TrafficControl.values()) {
                var hex = board.getHex(at).duplicate();
                hex.setAppearance(Map.of("road", HexAppearance.withTrafficControl(null, control)));
                assertTrue(BoardFeatures.roadControls(hex, at, board::getHex, true).isEmpty(),
                      control + " must not add furniture to the open apron entrance: " + at.getBoardNum());
            }
        }
    }

    @Test
    void aRealThreeArmJunctionBesideAnApronRetainsItsRoadControl() {
        var road = new Hex(0, "road:1:27", "");
        stops(road, false);
        var apron = AT.translated(1);
        var features = BoardFeatures.roadControls(road, AT,
              at -> at.equals(apron) ? new Hex(0, "pavement:1", "") : support(road, at), true);
        assertEquals(List.of("terrain:3_3:road:stop:4"),
              features.stream().map(feature -> feature.decoration().id()).toList(),
              "The apron is not a fourth road arm; the real T keeps its side-road stop");
    }

    @Test
    void fittedApronClearanceKeepsFurnitureAndPaintTogetherWithoutRemovingOtherFeatures() {
        var board = Board.createEmptyBoard(7, 7);
        var hex = new Hex(0, "road:1:15;bridge:1:27;bridge_cf:40;bridge_elev:2;bridge_road:1:27", "");
        var authored = new BoardDecoration("authored:road:stop:1", "prop", "scenery/road/stop-sign", null,
              0, 0, 0, false, 1, BoardDecoration.Placement.ground(), 0, false);
        hex.setDecorations(List.of(authored));
        board.setHex(AT, hex);
        board.setHex(AT.translated(0), new Hex(0, "pavement:1", ""));
        var scene = new BoardScene(0, board.getWidth(), board.getHeight(), BoardTiles.captureAll(board),
              List.of(), List.of(), -1, "", List.of());
        var tile = scene.tile(AT);
        var removed = tile.features().stream().filter(feature -> feature.decoration() != null
                    && feature.decoration().id().equals("terrain:3_3:road:stop:1"))
              .findFirst().orElseThrow();
        assertEquals(4, tile.features().stream().filter(feature -> feature.decoration() != null
              && feature.decoration().id().contains(":bridgeRoad:stop:")).count(),
              "The fixture also carries controls on a separate elevated deck");
        assertTrue(tile.features().stream().anyMatch(feature -> authored.equals(feature.decoration())));
        assertTrue(tile.features().stream().anyMatch(feature -> feature.asset().equals("bridge")));
        var shown = BoardFeatures.fittedControls(scene, tile);
        assertEquals(tile.features().stream().filter(feature -> !feature.equals(removed)).toList(), shown,
              "Only the generated ground control whose fitted stop bar disappeared is removed");

        var road = BoardRoad.of(scene, tile);
        assertEquals(List.of(2, 3), road.stopApproaches(tile.roadExits()).stream()
              .map(BoardRoad.StopApproach::direction).toList());
        var captured = BoardRoad.layout(AT, 15, BoardRoad.Kind.PAVED, direction -> BoardRoad.Kind.NONE)
              .control(HexAppearance.TrafficControl.GANTRY).aprons(1);
        var paint = GpuRoads.patches(tile, road).stream().filter(patch -> patch.texture().equals("concrete-ground")).toList();
        for (var approach : captured.stopApproaches(15)) {
            float across = (approach.inner() + approach.outer()) / 2;
            float x = approach.x() - approach.ny() * across, y = approach.y() + approach.nx() * across;
            boolean painted = paint.stream().anyMatch(patch -> patch.shape().contains(x, y));
            boolean visible = shown.stream().anyMatch(feature -> feature.decoration() != null
                  && feature.decoration().id().equals("terrain:3_3:road:stop:" + approach.direction()));
            assertEquals(approach.direction() != 1, painted, "The skew entrance loses its bar; the other arms keep theirs");
            assertEquals(painted, visible, "A generated control has the same fitted eligibility as its stop bar");
        }
    }

    @Test
    void adjacentCityJunctionsKeepTheirSignalSupportsOutsideEveryNearbyRoad() {
        var board = new Board();
        board.load(new File("data/boards/Map Set 3/16x17 City Hills Residential 1.board"));
        BoardSceneryLayouts.convert(board);
        var scene = new BoardScene(0, board.getWidth(), board.getHeight(), BoardTiles.captureAll(board),
              List.of(), List.of(), -1, "", List.of());
        Set<String> gantries = new HashSet<>();
        Set<String> islands = Set.of("terrain:10_5:road:stop:3", "terrain:10_5:road:stop:4",
              "terrain:10_6:road:stop:0", "terrain:10_6:road:stop:1");
        for (var at : List.of(new Coords(9, 5), new Coords(10, 5), new Coords(10, 6), new Coords(11, 5))) {
            var features = BoardFeatures.roadControls(board.getHex(at), at, board::getHex, true);
            assertGroundControlsClearNearbyWays(scene, at, features);
            for (var feature : features) {
                if (feature.asset().equals("scenery/road/traffic-signal-gantry")) {
                    gantries.add(feature.decoration().id());
                    if (islands.contains(feature.decoration().id())) {
                        assertEquals(BoardDecoration.Stretch.NONE, feature.stretch(),
                              "Island masts retain their authored proportions");
                    }
                }
            }
            assertTrue(BoardFeatures.fittedControls(scene, scene.tile(at)).containsAll(features),
                  "The restored controls must survive the renderer's fitted entrance policy");
            assertEquals(features, BoardFeatures.roadControls(board.getHex(at), at, board::getHex, true),
                  "Recapturing adjacent junctions keeps stable ids and transforms");
        }
        assertEquals(Set.of("terrain:10_5:road:stop:0", "terrain:10_5:road:stop:2",
              "terrain:10_5:road:stop:3", "terrain:10_5:road:stop:4",
              "terrain:10_6:road:stop:0", "terrain:10_6:road:stop:1",
              "terrain:10_6:road:stop:3", "terrain:10_6:road:stop:5"), gantries,
              "Both four-way junctions need their outer and island-mounted gantries");
    }

    @Test
    void explicitStopsAndSignalsAlsoClearTheAdjoiningJunction() {
        for (var control : List.of(HexAppearance.TrafficControl.STOPS, HexAppearance.TrafficControl.SIGNALS)) {
            var board = Board.createEmptyBoard(7, 7);
            var other = AT.translated(3);
            for (var at : List.of(AT, other)) {
                var hex = new Hex(0, "road:1:" + (at.equals(AT) ? 29 : 43), "");
                hex.setAppearance(Map.of("road", HexAppearance.withTrafficControl(null, control)));
                board.setHex(at, hex);
            }
            var scene = new BoardScene(0, board.getWidth(), board.getHeight(), BoardTiles.captureAll(board),
                  List.of(), List.of(), -1, "", List.of());
            for (var at : List.of(AT, other)) {
                var features = BoardFeatures.roadControls(board.getHex(at), at, board::getHex, true);
                assertEquals(4, features.size(), "The internal islands and external shoulders support all four controls");
                assertTrue(features.stream().allMatch(feature -> feature.asset().equals(
                      control == HexAppearance.TrafficControl.STOPS ? "scenery/road/stop-sign" : "scenery/road/traffic-signal")));
                assertGroundControlsClearNearbyWays(scene, at, features);
            }
        }
    }

    private static void assertGroundControlsClearNearbyWays(BoardScene scene, Coords at,
          List<BoardScene.Feature> features) {
        var roads = BoardRoad.nearbyWays(scene, scene.tile(at));
        var road = BoardRoad.of(scene, scene.tile(at));
        float scale = BoardGeometry.hexScale();
        for (var feature : features) {
            assertEquals("ground", feature.decoration().placement().receiver().terrain());
            float x = BoardGeometry.centerX(at) + feature.x() * scale;
            float y = BoardGeometry.centerY(at) + feature.y() * scale;
            assertTrue(BoardRoad.distance(roads, x, y) >= .5f - .0001f,
                  "A roadside support must clear neighboring pavement too: " + feature.decoration().id());
            String id = feature.decoration().id();
            int direction = id.charAt(id.length() - 1) - '0';
            var approach = road.stopApproaches(scene.tile(at).roadExits()).stream()
                  .filter(arm -> arm.direction() == direction).findFirst().orElseThrow();
            float setback = (feature.x() - approach.x()) * approach.nx() + (feature.y() - approach.y()) * approach.ny();
            assertTrue(setback >= -.001f && setback <= 2.001f,
                  "Island controls stay beside their stop bar, with at most 2px upstream adjustment");
            double angle = Math.toRadians(feature.rotation()), rightX = Math.cos(angle), rightY = Math.sin(angle);
            assertEquals(1, -rightY * approach.nx() + rightX * approach.ny(), .0001,
                  "The recovered control faces its waiting lane");
            if (!feature.asset().equals("scenery/road/traffic-signal-gantry")) { continue; }
            for (float head : new float[] { 5.6f, 8.96f }) {
                double hx = feature.x() + rightX * head * feature.stretch().x();
                double hy = feature.y() + rightY * head * feature.stretch().x();
                double across = -(hx - approach.x()) * approach.ny() + (hy - approach.y()) * approach.nx();
                assertTrue(across - .7 * feature.stretch().x() >= approach.inner() - .001
                      && across + .7 * feature.stretch().x() <= approach.outer() + .001,
                      "Both complete signal heads remain above their incoming lane");
            }
            for (int side : new int[] { -1, 1 }) {
                for (int along : new int[] { -1, 1 }) {
                    float footX = x + (float) (rightX * side * .588 * feature.stretch().x() - rightY * along * .588) * scale;
                    float footY = y + (float) (rightY * side * .588 * feature.stretch().x() + rightX * along * .588) * scale;
                    assertTrue(BoardRoad.distance(roads, footX, footY) >= .02f - .0001f,
                          "The whole gantry footing must clear every nearby carriageway: " + feature.decoration().id());
                }
            }
        }
    }

    @Test
    void controlsStandOnTheCompleteJunctionShoulderAtTheirStopPlane() {
        for (int exits = 0; exits < 64; exits++) {
            if (Integer.bitCount(exits) < 3 || Integer.bitCount(exits) > 4) { continue; }
            for (boolean deck : new boolean[] { false, true }) {
                for (int rails = 0; rails < 3; rails++) {
                    String roadTerrain = deck ? "bridge:1:" + exits + ";bridge_cf:40;bridge_elev:2;bridge_road:1:" : "road:1:";
                    Hex hex = new Hex(0, roadTerrain + exits, "");
                    if (rails != 0) {
                        hex.addTerrain(new Terrain(deck ? Terrains.BRIDGE_RAIL : Terrains.RAIL, 1, true, exits));
                        if (rails == 2) {
                            hex.setAppearance(Map.of(deck ? "bridgeRail" : "rail", HexAppearance.rail(0, 64 | exits)));
                        }
                    }
                    stops(hex, deck);
                    var corridor = RailRoute.corridor(hex, AT, at -> support(hex, at), deck);
                    var road = BoardRoad.layout(AT, exits, BoardRoad.Kind.PAVED, direction -> BoardRoad.Kind.NONE).corridor(corridor);
                    var signs = controls(hex);
                    if (rails != 2) { assertEquals(road.stopApproaches(exits).size(), signs.size()); }
                    for (var sign : signs) {
                        assertFalse(road.footprint(0).contains(sign.x(), sign.y()),
                              "The full turn envelope stays free of poles: exits=" + exits + " deck=" + deck + " rails=" + rails
                                    + " " + sign.decoration().id());
                        assertTrue(road.footprint(BoardRoad.SHOULDER).contains(sign.x(), sign.y()),
                              "The pole remains on the road/deck shoulder");
                        int direction = Integer.parseInt(sign.decoration().id().substring(sign.decoration().id().lastIndexOf(':') + 1));
                        var approach = road.stopApproaches(exits).stream().filter(a -> a.direction() == direction).findFirst().orElseThrow();
                        assertEquals(0, (sign.x() - approach.x()) * approach.nx() + (sign.y() - approach.y()) * approach.ny(),
                              .0001f, "The control remains directly beside its stop bar");
                    }
                }
            }
        }
    }

    @Test
    void defaultStopAtATJunctionControlsOnlyTheSecondaryRoad() {
        for (int axis = 0; axis < 3; axis++) {
            for (int branch = 0; branch < 6; branch++) {
                if (branch == axis || branch == axis + 3) { continue; }
                int exits = (1 << axis) | (1 << (axis + 3)) | (1 << branch);
                Hex hex = new Hex(0, "road:1:" + exits, "");
                var controls = controls(hex);
                assertEquals(1, controls.size(), "The through road remains clear of stop controls");
                assertTrue(controls.getFirst().decoration().id().endsWith(":" + branch));
                assertEquals("scenery/road/stop-sign", controls.getFirst().asset());
                assertTrue(hex.getAppearance().isEmpty(), "Priority is derived without storing an appearance");
            }
        }
    }

    @Test
    void explicitSignalsControlEveryApproachOfATJunctionOnGroundOrDeck() {
        for (boolean deck : new boolean[] { false, true }) {
            for (var mode : List.of(HexAppearance.TrafficControl.SIGNALS, HexAppearance.TrafficControl.GANTRY)) {
                Hex hex = new Hex(0, deck ? "bridge:1:11;bridge_cf:40;bridge_elev:2;bridge_road:1:11" : "road:1:11", "");
                hex.setAppearance(Map.of(deck ? "bridgeRoad" : "road", HexAppearance.withTrafficControl(null, mode)));
                var signals = controls(hex);
                assertEquals(3, signals.size(), "A signal-controlled T controls through traffic as well as its side street");
                assertTrue(signals.stream().allMatch(feature -> feature.asset().startsWith("scenery/road/traffic-signal")));
                var road = BoardRoad.layout(AT, 11, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.NONE).control(mode);
                var paint = road.stopLines(11);
                for (var approach : road.stopApproaches(11)) {
                    float across = (approach.inner() + approach.outer()) / 2;
                    assertTrue(paint.contains(approach.x() - approach.ny() * across, approach.y() + approach.nx() * across),
                          "Every signal-controlled incoming lane has a stop bar");
                }
            }
        }
    }

    @Test
    void railBearingFiveAndSixArmJunctionsRetainControlsInsteadOfUncontrolledRoundabouts() {
        for (int exits : new int[] { 31, 47, 55, 59, 61, 62, 63 }) {
            for (boolean deck : new boolean[] { false, true }) {
                Hex hex = new Hex(0, deck ? "bridge:1:" + exits + ";bridge_cf:40;bridge_elev:2;bridge_road:1:" + exits
                      + ";bridge_rail:1:" + exits : "road:1:" + exits + ";rail:1:" + exits, "");
                var signals = controls(hex);
                assertEquals(Integer.bitCount(exits), signals.size(), "Every rail-hub approach is controlled: " + exits);
                assertTrue(signals.stream().allMatch(feature -> feature.asset().startsWith("scenery/road/traffic-signal")));
            }
        }
    }

    @Test
    void independentRailBranchKeepsFiveArmRoadControlsOnGroundAndDeck() {
        for (boolean deck : new boolean[] { false, true }) {
            Hex hex = new Hex(0, deck ? "bridge:1:63;bridge_cf:40;bridge_elev:2;bridge_road:1:31;bridge_rail:1:32"
                  : "road:1:31;rail:1:32", "");
            assertEquals(0, RailRoute.corridor(hex, AT, at -> support(hex, at), deck).sides(),
                  "The independent rail shares no road approach");
            var signals = controls(hex).stream().filter(feature -> feature.asset().startsWith("scenery/road/")).toList();
            assertEquals(5, signals.size(), "An independent rail must not leave an uncontrolled road hub");
            assertTrue(signals.stream().allMatch(feature -> feature.asset().startsWith("scenery/road/traffic-signal")));
        }
    }

    @Test
    void crowdedBesideRailJunctionKeepsItsStopBarsWhenAShoulderHasNoSupportForAPole() {
        for (boolean deck : new boolean[] { false, true }) {
            Hex hex = new Hex(0, deck ? "bridge:1:39;bridge_cf:40;bridge_elev:2;bridge_road:1:39;bridge_rail:1:39"
                  : "road:1:39;rail:1:39", "");
            hex.setAppearance(Map.of(deck ? "bridgeRail" : "rail", HexAppearance.rail(0, 64 | 39)));
            stops(hex, deck);
            var signs = controls(hex);
            assertEquals(4, signs.size(), "All four shoulders remain supported when their footprint crosses the grid");
            var corridor = RailRoute.corridor(hex, AT, at -> support(hex, at), deck);
            var road = BoardRoad.layout(AT, 39, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.NONE).corridor(corridor);
            var paint = road.stopLines(39);
            assertEquals(4, road.stopApproaches(39).size(), "Limited pole support does not change the junction's stop planes");
            for (var approach : road.stopApproaches(39)) {
                float across = (approach.inner() + approach.outer()) / 2;
                assertTrue(paint.contains(approach.x() - approach.ny() * across, approach.y() + approach.nx() * across),
                      "Every approach retains its stop bar");
            }
        }
    }

    @Test
    void appearanceChangesOnlyTheFurnitureAndDeckControlsStayOnDeck() {
        Hex hex = new Hex(0, "road:1:27;bridge:1:27;bridge_cf:40;bridge_elev:2;bridge_road:1:27", "");
        hex.setAppearance(Map.of("bridgeRoad", HexAppearance.withTrafficControl(null, HexAppearance.TrafficControl.SIGNALS)));
        stops(hex, false);
        var features = controls(hex);
        assertEquals(8, features.size());
        assertEquals(4, features.stream().filter(f -> f.asset().equals("scenery/road/stop-sign")).count());
        var signals = features.stream().filter(f -> f.asset().equals("scenery/road/traffic-signal")).toList();
        assertEquals(4, signals.size());
        assertTrue(signals.stream().allMatch(f -> f.decoration().placement().receiver().terrain().equals("bridge")));
        hex.addTerrain(new Terrain(Terrains.WATER, 1));
        assertEquals(signals, controls(hex), "Submerged ground roads must not plant signals beneath the bridge");
        assertEquals(signals, BoardFeatures.roadControls(hex, AT, at -> null, false));
    }

    @Test
    void gantryHeadsFaceTheirIncomingLaneAndKeepTheirAuthoredHeight() {
        for (int exits : new int[] { 7, 11, 21, 27, 39, 45, 54 }) {
            for (boolean deck : new boolean[] { false, true }) {
                for (int rails = 0; rails < 3; rails++) {
                    Hex hex = new Hex(0, (deck ? "bridge:1:" + exits + ";bridge_cf:40;bridge_elev:2;bridge_road:1:" : "road:1:") + exits, "");
                    var appearance = new java.util.HashMap<String, HexAppearance>();
                    appearance.put(deck ? "bridgeRoad" : "road",
                          HexAppearance.withTrafficControl(null, HexAppearance.TrafficControl.GANTRY));
                    if (rails != 0) {
                        hex.addTerrain(new Terrain(deck ? Terrains.BRIDGE_RAIL : Terrains.RAIL, 1, true, exits));
                        if (rails == 2) { appearance.put(deck ? "bridgeRail" : "rail", HexAppearance.rail(0, 64 | exits)); }
                    }
                    hex.setAppearance(appearance);
                    var corridor = RailRoute.corridor(hex, AT, at -> support(hex, at), deck);
                    var road = BoardRoad.layout(AT, exits, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.NONE).corridor(corridor)
                          .control(HexAppearance.TrafficControl.GANTRY);
                    int gantries = 0;
                    for (var control : controls(hex)) {
                        assertEquals(1, control.stretch().y());
                        assertEquals(1, control.stretch().z(), "Gantry clearance keeps its authored real-world height");
                        if (!control.asset().equals("scenery/road/traffic-signal-gantry")) {
                            assertEquals("scenery/road/traffic-signal", control.asset(), "A cramped shoulder falls back to its roadside signal");
                            continue;
                        }
                        gantries++;
                        int direction = Integer.parseInt(control.decoration().id().substring(control.decoration().id().lastIndexOf(':') + 1));
                        var approach = road.stopApproaches(exits).stream().filter(a -> a.direction() == direction).findFirst().orElseThrow();
                        float setback = (control.x() - approach.x()) * approach.nx() + (control.y() - approach.y()) * approach.ny();
                        assertTrue(setback >= -.001f && setback <= 2.001f,
                              "A mast may step slightly towards waiting traffic to clear an acute corner");
                        double stretch = control.stretch().x();
                        assertTrue(stretch >= .85 && stretch <= 1.25);
                        double angle = Math.toRadians(control.rotation()), rightX = Math.cos(angle), rightY = Math.sin(angle);
                        assertEquals(-1, rightX * -approach.ny() + rightY * approach.nx(), .0001,
                              "The cantilever extends inward from the incoming shoulder");
                        for (float head : new float[] { 5.6f, 8.96f }) {
                            double x = control.x() + rightX * head * stretch, y = control.y() + rightY * head * stretch;
                            double across = -(x - approach.x()) * approach.ny() + (y - approach.y()) * approach.nx();
                            assertTrue(across - .7 * stretch >= approach.inner() - .001
                                  && across + .7 * stretch <= approach.outer() + .001,
                                  "The full lamp stays above its incoming lane: exits=" + exits + " deck=" + deck + " rails=" + rails
                                        + " direction=" + direction + " head=" + head + " across=" + across + " approach=" + approach);
                            assertTrue(road.footprint(0).contains(x, y));
                        }
                        for (int side : new int[] { -1, 1 }) {
                            for (int along : new int[] { -1, 1 }) {
                                double x = control.x() + rightX * side * .588 * stretch - rightY * along * .588;
                                double y = control.y() + rightY * side * .588 * stretch + rightX * along * .588;
                                assertFalse(road.footprint(0).contains(x, y), "The footing does not intrude into asphalt");
                                if (deck) {
                                    assertTrue(road.footprint(BoardBridge.SHOULDER).contains(x, y), "The full footing stands on the bridge shoulder");
                                }
                            }
                        }
                        if (exits == 27 && rails == 0) { assertEquals(1, stretch, "The ordinary X crossing keeps native mast and lamp proportions"); }
                    }
                    if (exits == 27 && rails == 0) { assertEquals(4, gantries, "An ordinary X crossing supports overhead controls on every approach"); }
                }
            }
        }
    }

    @Test
    void unmarkedRoadsRoundaboutsAndUnsupportedGroundDoNotGrowStopFurniture() {
        for (String terrain : List.of("road:2:27", "road:3:27", "road:4:27", "road:1:9", "road:1:63",
              "road:1:27;magma:2")) {
            assertTrue(controls(new Hex(0, terrain, "")).isEmpty(), terrain);
        }
        Hex hex = new Hex(0, "road:1:27", "");
        assertTrue(BoardFeatures.roadControls(hex, AT, at -> null, false).isEmpty());
    }

    @Test
    void aSubmergedRoadKeepsTheFurnitureOfADryOneOnItsBed() {
        var dry = controls(new Hex(0, "road:1:27", "")).stream().map(feature -> feature.decoration().id()).toList();
        assertFalse(dry.isEmpty());
        for (String liquid : List.of("water:0", "water:1", "water:4", "hazardous_liquid:1")) {
            Hex hex = new Hex(0, "road:1:27;" + liquid, "");
            var submerged = BoardFeatures.roadControls(hex, AT, at -> at.equals(AT) ? hex : new Hex(0, liquid, ""), true);
            assertEquals(dry, submerged.stream().map(feature -> feature.decoration().id()).toList(), liquid);
            assertTrue(submerged.stream().allMatch(feature -> feature.decoration().placement().receiver().terrain()
                  .equals("ground")), liquid);
            // A neighbouring street under the same water keeps its poles out of its own lanes.
            assertFalse(BoardFeatures.roadControls(hex, AT, at -> new Hex(0, "road:1:27;" + liquid, ""), true).isEmpty(),
                  liquid);
        }
    }

    @Test
    void iceHidesASubmergedRoadAndClassicLeavesOutItsFurniture() {
        var scene = waterRoad("road:1:27;water:1");
        var tile = scene.tile(AT);
        assertTrue(BoardRoad.rendered(tile));
        var furniture = BoardFeatures.fittedControls(scene, tile).stream()
              .filter(feature -> BoardFeatures.submergedControl(tile, feature)).toList();
        assertFalse(furniture.isEmpty(), "Under open water the road keeps its furniture; Classic filters it by this test");
        var iced = waterRoad("road:1:27;water:1;ice:1");
        assertFalse(BoardRoad.rendered(iced.tile(AT)));
        assertTrue(BoardFeatures.fittedControls(iced, iced.tile(AT)).stream()
              .noneMatch(feature -> BoardFeatures.submergedControl(iced.tile(AT), feature)));
    }

    private static BoardScene waterRoad(String terrain) {
        var board = Board.createEmptyBoard(7, 7);
        board.setHex(AT, new Hex(0, terrain, ""));
        return new BoardScene(0, board.getWidth(), board.getHeight(), BoardTiles.captureAll(board),
              List.of(), List.of(), -1, "", List.of());
    }

    @Test
    void groundControlsFollowTheChosenBridgeRoadInsteadOfTheRoadBelowIt() {
        Hex road = new Hex(2, "road:1:27", "");
        assertFalse(northernControl(road, new Hex(0,
              "bridge:1:8;bridge_cf:40;bridge_elev:2;bridge_road:2:8;road:1:8", "")),
              "An approaching alley deck has neither stop bar nor control despite a paved road below it");
        assertTrue(northernControl(road, new Hex(0,
              "bridge:1:8;bridge_cf:40;bridge_elev:2;bridge_road:1:8;road:2:8", "")),
              "The paved deck wins over an unrelated alley below it");
        assertFalse(northernControl(road, new Hex(2,
              "bridge:1:8;bridge_cf:40;bridge_elev:2;bridge_road:1:8;road:2:8", "")),
              "A level ground continuation wins over a bridge above it");
        assertTrue(northernControl(road, new Hex(1,
              "bridge:1:8;bridge_cf:40;bridge_elev:1;bridge_road:1:8;road:2:8", "")),
              "A level deck wins over a sloped ground continuation");
    }

    @Test
    void aBridgeOverConcreteKeepsItsConnectedGroundJunctionPaintAndControls() {
        var board = Board.createEmptyBoard(7, 7);
        board.setHex(AT, new Hex(0, "road:1:27", ""));
        board.setHex(AT.translated(0), new Hex(0,
              "pavement:1;bridge:1:8;bridge_cf:40;bridge_elev:1;bridge_road:1:8", ""));
        var scene = new BoardScene(0, board.getWidth(), board.getHeight(), BoardTiles.captureAll(board),
              List.of(), List.of(), -1, "", List.of());
        var tile = scene.tile(AT);
        var bridge = scene.tile(AT.translated(0));
        assertEquals(BoardScene.Surface.CONCRETE, bridge.surface());
        assertEquals(BoardRoad.Kind.NONE, bridge.road(), "Only the elevated deck carries a road");
        assertTrue(BoardSurface.connectingBridge(tile, bridge, 0) != null);
        var road = BoardRoad.of(scene, tile);
        assertEquals(4, road.stopApproaches(tile.roadExits()).size(),
              "The connected bridge remains the fourth road arm, not an open concrete apron");
        assertTrue(northernControl(board.getHex(AT), board.getHex(AT.translated(0))),
              "Concrete under the bridge must not remove its approach's physical control");
        var north = road.stopApproaches(tile.roadExits()).stream().filter(arm -> arm.direction() == 0).findFirst().orElseThrow();
        float across = (north.inner() + north.outer()) / 2;
        float x = north.x() - north.ny() * across, y = north.y() + north.nx() * across;
        assertTrue(GpuRoads.patches(tile, road).stream().filter(patch -> patch.texture().equals("concrete-ground"))
                    .anyMatch(patch -> patch.shape().contains(x, y)),
              "The integrated stop-bar paint reaches the same bridge approach");
    }

    @Test
    void deckControlsFollowJoinedDecksAndGroundLandingsOnly() {
        Hex deck = new Hex(0, "bridge:1:27;bridge_cf:40;bridge_elev:2;bridge_road:1:27", "");
        assertFalse(northernControl(deck, new Hex(2, "road:2:8", "")), "The deck's alley landing is unmarked");
        assertTrue(northernControl(deck, new Hex(2, "road:1:8", "")), "A paved landing retains its deck control");
        assertTrue(northernControl(deck, new Hex(0, "road:2:8", "")), "An alley too far below cannot suppress a deck control");
        assertFalse(northernControl(deck, new Hex(0,
              "bridge:1:8;bridge_cf:40;bridge_elev:2;bridge_road:2:8;road:1:8", "")),
              "Joined deck paint follows the neighboring deck's material");
        assertTrue(northernControl(deck, new Hex(0,
              "bridge:1:8;bridge_cf:40;bridge_elev:5;bridge_road:2:8", "")),
              "An unconnected bridge above the junction cannot suppress its control");
    }
}
