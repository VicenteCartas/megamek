/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.BoardFile;
import megamek.common.board.BridgeSpan;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Batch 13's layer decisions and the saved game data they author; no UI timing or clicks. */
class WayLayerEditorTest {
    @TempDir Path directory;
    private static final Coords CENTRE = new Coords(4, 4);

    @Test void roadBrushControlsFollowGroundAndDeckAndAnExplicitLayerMove() {
        var session = new BoardEditorSession();
        session.board().setHex(CENTRE, new Hex(0, "water:2", ""));
        road(session);
        send(session, Action.BRUSH_VALUE, "trafficControl", "signals");
        stroke(session, CENTRE.translated(0), CENTRE, CENTRE.translated(3));
        assertEquals(HexAppearance.TrafficControl.SIGNALS,
              HexAppearance.trafficControl(session.board().getHex(CENTRE).getAppearance(), "bridgeRoad"));
        assertEquals(HexAppearance.TrafficControl.SIGNALS,
              HexAppearance.trafficControl(session.board().getHex(CENTRE.translated(0)).getAppearance(), "road"));
        inspect(session, CENTRE);
        send(session, Action.WAY_LAYER, "bridge_road", "ground");
        assertEquals(HexAppearance.TrafficControl.SIGNALS,
              HexAppearance.trafficControl(session.board().getHex(CENTRE).getAppearance(), "road"));
        send(session, Action.UNDO, "", "");
        send(session, Action.TRAFFIC_CONTROL, "bridgeRoad", "stops");
        assertEquals(HexAppearance.TrafficControl.STOPS,
              HexAppearance.trafficControl(session.board().getHex(CENTRE).getAppearance(), "bridgeRoad"));
        assertEquals(HexAppearance.TrafficControl.SIGNALS,
              HexAppearance.trafficControl(session.board().getHex(CENTRE.translated(0)).getAppearance(), "road"),
              "Editing a deck's controls does not change the ground road's controls");
    }

    @Test void roadAcrossWaterUsesAnImplicitDeckAndReturnsToDryGround() throws Exception {
        var session = new BoardEditorSession();
        session.board().setHex(CENTRE, new Hex(0, "water:2;ice:1", ""));
        road(session);
        stroke(session, CENTRE.translated(0), CENTRE, CENTRE.translated(3));
        Hex water = session.board().getHex(CENTRE);
        assertEquals(0, BridgeSpan.deck(water));
        assertTrue(HexAppearance.implicit(water.getAppearance()));
        assertEquals(9, water.getTerrain(Terrains.BRIDGE).getExits());
        assertEquals(9, water.getTerrain(Terrains.BRIDGE_ROAD).getExits());
        assertFalse(water.containsTerrain(Terrains.ROAD));
        assertEquals(HexAppearance.TrafficControl.GANTRY, HexAppearance.trafficControl(water.getAppearance(), "bridgeRoad"));
        for (int side : new int[] { 0, 3 }) {
            Hex bank = session.board().getHex(CENTRE.translated(side));
            assertTrue(bank.containsTerrain(Terrains.ROAD));
            assertFalse(bank.hasBridge());
            assertEquals(HexAppearance.TrafficControl.GANTRY, HexAppearance.trafficControl(bank.getAppearance(), "road"));
        }
        Path file = directory.resolve("crossing.board2");
        session.save(file);
        assertEquals(BoardFile.hexNode(water), BoardFile.hexNode(BoardFile.read(file).getHex(CENTRE)));
        send(session, Action.UNDO, "", "");
        assertFalse(session.board().getHex(CENTRE).hasBridge());
        assertTrue(session.board().getHex(CENTRE).containsTerrain(Terrains.ICE));
        send(session, Action.REDO, "", "");
        assertEquals(9, session.board().getHex(CENTRE).getTerrain(Terrains.BRIDGE_ROAD).getExits());
    }

    @Test void aCleanBridgeAndItsBanksHaveNoImplicitRoad() {
        var session = new BoardEditorSession();
        session.board().setHex(CENTRE, new Hex(0, "water:2", ""));
        send(session, Action.CHOOSE_BRUSH, "bridge", "bridge/built");
        stroke(session, CENTRE.translated(0), CENTRE, CENTRE.translated(3));
        Hex bridge = session.board().getHex(CENTRE);
        assertTrue(bridge.hasBridge());
        assertFalse(HexAppearance.implicit(bridge.getAppearance()));
        for (Coords at : List.of(CENTRE, CENTRE.translated(0), CENTRE.translated(3))) {
            Hex hex = session.board().getHex(at);
            assertFalse(hex.containsAnyTerrainOf(Terrains.ROAD, Terrains.BRIDGE_ROAD, Terrains.RAIL, Terrains.BRIDGE_RAIL));
        }
        road(session); stroke(session, CENTRE.translated(0));
        assertTrue(session.board().getHex(CENTRE.translated(0)).containsTerrainExit(Terrains.ROAD, 3));
        assertFalse(session.board().getHex(CENTRE).containsTerrain(Terrains.BRIDGE_ROAD));
    }

    @Test void aBridgeDrawnFromARoadJoinsIt() {
        var session = new BoardEditorSession();
        session.board().setHex(CENTRE, new Hex(0, "water:2", ""));
        road(session); stroke(session, CENTRE.translated(0));
        send(session, Action.CHOOSE_BRUSH, "bridge", "bridge/built");
        stroke(session, CENTRE.translated(0), CENTRE, CENTRE.translated(3));
        assertTrue(session.board().getHex(CENTRE.translated(0)).containsTerrainExit(Terrains.ROAD, 3));
    }

    @Test void aWayPaintedHalfwayAcrossAnExplicitBridgeDoesNotFillItsSpan() {
        var session = new BoardEditorSession();
        for (int y = 2; y <= 6; y++) { session.board().setHex(new Coords(4, y), bridge(0, 1, 9)); }
        road(session);
        point(session, new Coords(4, 2), false, "bridge");
        point(session, new Coords(4, 3), true, "bridge");
        session.finishStroke();
        assertTrue(session.board().getHex(new Coords(4, 2)).containsTerrain(Terrains.BRIDGE_ROAD));
        assertTrue(session.board().getHex(new Coords(4, 3)).containsTerrain(Terrains.BRIDGE_ROAD));
        assertFalse(session.board().getHex(new Coords(4, 4)).containsTerrain(Terrains.BRIDGE_ROAD));
        assertEquals(9, session.board().getHex(new Coords(4, 3)).getTerrain(Terrains.BRIDGE).getExits());
    }

    @Test void aNaturalBridgeKeepsItsKindWhileTakingADeckRoad() {
        var session = new BoardEditorSession();
        Hex hex = bridge(0, 0, 9);
        hex.setAppearance(Map.of("bridge", HexAppearance.NATURAL_BRIDGE));
        session.board().setHex(CENTRE, hex);
        road(session); send(session, Action.BRUSH_VALUE, "terrain:road", "3");
        point(session, CENTRE, false, "bridge"); session.finishStroke();
        assertEquals(3, session.board().getHex(CENTRE).terrainLevel(Terrains.BRIDGE_ROAD));
        assertEquals(HexAppearance.NATURAL_BRIDGE, session.board().getHex(CENTRE).getAppearance().get("bridge"));
        assertTrue(session.board().getHex(CENTRE).containsTerrain(Terrains.BRIDGE_ROAD));
    }

    @Test void followAndLevelChooseDifferentHeightsButShareTheSameDeckRules() {
        var follow = new BoardEditorSession();
        follow.board().getHex(CENTRE).setLevel(3);
        follow.board().getHex(CENTRE).addTerrain(new Terrain(Terrains.RAIL, 1, true, 18));
        follow.board().getHex(CENTRE.translated(3)).setLevel(5);
        road(follow); height(follow, false, 2);
        stroke(follow, CENTRE, CENTRE.translated(3));
        assertEquals(5, BridgeSpan.deck(follow.board().getHex(CENTRE)));
        assertEquals(18, follow.board().getHex(CENTRE).getTerrain(Terrains.RAIL).getExits());
        assertEquals(7, BridgeSpan.deck(follow.board().getHex(CENTRE.translated(3))));
        var level = new BoardEditorSession();
        level.board().getHex(CENTRE).setLevel(3);
        level.board().getHex(CENTRE.translated(3)).setLevel(4);
        road(level); height(level, true, 2);
        stroke(level, CENTRE, CENTRE.translated(3));
        assertEquals(5, BridgeSpan.deck(level.board().getHex(CENTRE)));
        assertEquals(5, BridgeSpan.deck(level.board().getHex(CENTRE.translated(3))));
    }

    @Test void ordinaryGroundFollowingClimbsHillsWithoutInventingDecks() {
        var session = new BoardEditorSession();
        session.board().getHex(CENTRE).setLevel(5);
        road(session);
        stroke(session, CENTRE.translated(0), CENTRE, CENTRE.translated(3));
        assertTrue(session.board().getHex(CENTRE).containsTerrain(Terrains.ROAD));
        assertFalse(session.board().getHex(CENTRE).hasBridge());
    }

    @Test void implicitYDeckGrowsButAnExplicitDeckRefusesAMissingBranch() {
        for (boolean implicit : new boolean[] { true, false }) {
            var session = new BoardEditorSession();
            Hex hex = bridge(0, 1, 9);
            if (implicit) {
                hex.addTerrain(new Terrain(Terrains.BRIDGE_ROAD, 1, true, 9));
                hex.setAppearance(Map.of("bridge", HexAppearance.IMPLICIT_BRIDGE));
            }
            session.board().setHex(CENTRE, hex);
            road(session); height(session, false, 1);
            stroke(session, CENTRE, CENTRE.translated(2));
            Hex changed = session.board().getHex(CENTRE);
            assertEquals(implicit ? 13 : 9, changed.getTerrain(Terrains.BRIDGE).getExits());
            if (implicit) { assertEquals(13, changed.getTerrain(Terrains.BRIDGE_ROAD).getExits()); }
            else {
                assertFalse(changed.containsTerrain(Terrains.BRIDGE_ROAD));
                assertTrue(session.snapshot().message().contains("exit"));
            }
        }
    }

    @Test void aHighDeckDoesNotStealAGroundWayAndASecondDeckIsRefused() {
        var session = new BoardEditorSession();
        session.board().setHex(CENTRE, bridge(0, 5, 9));
        road(session);
        stroke(session, CENTRE.translated(4), CENTRE, CENTRE.translated(1));
        assertTrue(session.board().getHex(CENTRE).containsTerrain(Terrains.ROAD));
        assertFalse(session.board().getHex(CENTRE).containsTerrain(Terrains.BRIDGE_ROAD));
        height(session, true, 2);
        var before = BoardFile.hexNode(session.board().getHex(CENTRE));
        var preview = session.preview(CENTRE, 0, 0, "ground");
        assertTrue(preview.layers().get(CENTRE).refused());
        stroke(session, CENTRE);
        assertEquals(before, BoardFile.hexNode(session.board().getHex(CENTRE)));
    }

    @Test void erasingTheLastWayRemovesOnlyAnImplicitDeckAndCutsNeighbourSides() {
        var session = new BoardEditorSession();
        road(session); height(session, false, 2);
        stroke(session, CENTRE.translated(0), CENTRE, CENTRE.translated(3));
        send(session, Action.TOOL, "", "ERASE");
        stroke(session, CENTRE);
        assertFalse(session.board().getHex(CENTRE).hasBridge());
        assertFalse(session.board().getHex(CENTRE.translated(0)).containsTerrainExit(Terrains.BRIDGE_ROAD, 3));
        send(session, Action.UNDO, "", "");
        assertTrue(session.board().getHex(CENTRE).hasBridge());
        inspect(session, CENTRE);
        send(session, Action.BRIDGE_TYPE, "", "built");
        assertFalse(HexAppearance.implicit(session.board().getHex(CENTRE).getAppearance()));
        road(session); height(session, false, 2);
        send(session, Action.TOOL, "", "ERASE"); stroke(session, CENTRE);
        assertTrue(session.board().getHex(CENTRE).hasBridge());
        assertFalse(session.board().getHex(CENTRE).containsTerrain(Terrains.BRIDGE_ROAD));
    }

    @Test void erasingABridgeRemovesItsWaysButPreservesTheGroundWay() {
        var session = new BoardEditorSession();
        Hex hex = bridge(0, 2, 9);
        hex.addTerrain(new Terrain(Terrains.RAIL, 1, true, 18));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_RAIL, 1, true, 9));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_ROAD, 1, true, 9));
        session.board().setHex(CENTRE, hex);
        send(session, Action.CHOOSE_BRUSH, "bridge", "bridge/built");
        send(session, Action.TOOL, "", "ERASE"); stroke(session, CENTRE);
        Hex result = session.board().getHex(CENTRE);
        assertFalse(result.containsAnyTerrainOf(Terrains.BRIDGE, Terrains.BRIDGE_ROAD, Terrains.BRIDGE_RAIL));
        assertEquals(18, result.getTerrain(Terrains.RAIL).getExits());
    }

    @Test void onDeckAndBeneathMoveOneWayWithoutRetypingItsBridge() {
        var session = new BoardEditorSession();
        Hex hex = bridge(0, 2, 9);
        hex.addTerrain(new Terrain(Terrains.RAIL, 1, true, 9));
        session.board().setHex(CENTRE, hex);
        inspect(session, CENTRE);
        send(session, Action.WAY_LAYER, "rail", "deck");
        assertFalse(session.board().getHex(CENTRE).containsTerrain(Terrains.RAIL));
        assertEquals(9, session.board().getHex(CENTRE).getTerrain(Terrains.BRIDGE_RAIL).getExits());
        send(session, Action.WAY_LAYER, "bridge_rail", "ground");
        assertTrue(session.board().getHex(CENTRE).hasBridge());
        assertFalse(session.board().getHex(CENTRE).containsTerrain(Terrains.BRIDGE_RAIL));
        assertEquals(9, session.board().getHex(CENTRE).getTerrain(Terrains.RAIL).getExits());
    }

    @Test void maglevIsTerrainAndExtendingItInheritsItsPillars() {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", RailRoute.Kind.MAGLEV.asset);
        stroke(session, CENTRE.translated(0), CENTRE);
        Hex hex = session.board().getHex(CENTRE);
        assertEquals(2, hex.terrainLevel(Terrains.BRIDGE_RAIL));
        assertEquals(650, hex.terrainLevel(Terrains.BRIDGE_CF));
        assertTrue(HexAppearance.pillars(hex.getAppearance()));
        assertTrue(hex.getDecorations().isEmpty());
        send(session, Action.BRUSH_VALUE, "wayPillars", "false");
        stroke(session, CENTRE, CENTRE.translated(3));
        assertTrue(HexAppearance.pillars(session.board().getHex(CENTRE.translated(3)).getAppearance()));
        assertTrue(RailRoute.onDeck(RailRoute.find(hex, RailRoute.Kind.MAGLEV)));
    }

    @Test void maglevPillarsToggleWithoutAddingABridgeSlabAndUndoRestoresSupports() {
        var session = new BoardEditorSession();
        Coords next = CENTRE.translated(0);
        send(session, Action.ASSET, "", RailRoute.Kind.MAGLEV.asset);
        height(session, true, 2);
        stroke(session, next, CENTRE);
        inspect(session, CENTRE);
        for (boolean on : new boolean[] { false, true }) {
            send(session, Action.PILLARS, "", Boolean.toString(on));
            for (Coords at : List.of(CENTRE, next)) {
                Hex hex = session.board().getHex(at);
                assertTrue(HexAppearance.implicit(hex.getAppearance()), "Supports must not add a visible ordinary bridge");
                assertEquals(on, HexAppearance.pillars(hex.getAppearance()));
                assertEquals(2, hex.terrainLevel(Terrains.BRIDGE_RAIL));
                assertEquals(2, BridgeSpan.deck(hex));
            }
        }
        send(session, Action.UNDO, "", "");
        for (Coords at : List.of(CENTRE, next)) {
            assertTrue(HexAppearance.implicit(session.board().getHex(at).getAppearance()));
            assertFalse(HexAppearance.pillars(session.board().getHex(at).getAppearance()));
        }
    }

    @Test void roadAndRailYShareOnlyTheirPaintedArmsOnImplicitAndDrawnDecks() {
        for (boolean explicit : new boolean[] { false, true }) {
            var session = new BoardEditorSession();
            Coords a = CENTRE.translated(0), b = CENTRE.translated(2), c = CENTRE.translated(3);
            for (Coords at : List.of(a, b, c, CENTRE)) { session.board().setHex(at, new Hex(0, "water:2", "")); }
            if (explicit) {
                send(session, Action.CHOOSE_BRUSH, "bridge", "bridge/truss");
                stroke(session, a, CENTRE, c); stroke(session, CENTRE, b);
            }
            road(session); stroke(session, a, CENTRE, c);
            send(session, Action.ASSET, "", RailRoute.Kind.RAIL.asset); stroke(session, b, CENTRE, c);
            Hex hub = session.board().getHex(CENTRE);
            assertEquals(13, hub.getTerrain(Terrains.BRIDGE).getExits());
            assertEquals(9, hub.getTerrain(Terrains.BRIDGE_ROAD).getExits());
            assertEquals(12, hub.getTerrain(Terrains.BRIDGE_RAIL).getExits());
            assertEquals(!explicit, HexAppearance.implicit(hub.getAppearance()));
            if (explicit) { assertTrue(HexAppearance.truss(hub.getAppearance())); }
            send(session, Action.UNDO, "", "");
            assertFalse(session.board().getHex(CENTRE).containsTerrain(Terrains.BRIDGE_RAIL));
            assertEquals(explicit ? 13 : 9, session.board().getHex(CENTRE).getTerrain(Terrains.BRIDGE).getExits());
        }
    }

    @Test void roadCanMoveBeneathABridgeIntoWaterAndUndoRestoresItsDeckLayer() {
        var session = new BoardEditorSession();
        Hex hex = bridge(0, 2, 9);
        hex.addTerrain(new Terrain(Terrains.WATER, 2));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_ROAD, 3, true, 9));
        session.board().setHex(CENTRE, hex); inspect(session, CENTRE);
        send(session, Action.WAY_LAYER, "bridge_road", "ground");
        Hex submerged = session.board().getHex(CENTRE);
        assertEquals(3, submerged.terrainLevel(Terrains.ROAD));
        assertEquals(2, submerged.terrainLevel(Terrains.WATER));
        assertFalse(submerged.containsTerrain(Terrains.BRIDGE_ROAD));
        send(session, Action.UNDO, "", "");
        assertFalse(session.board().getHex(CENTRE).containsTerrain(Terrains.ROAD));
        assertEquals(3, session.board().getHex(CENTRE).terrainLevel(Terrains.BRIDGE_ROAD));
    }

    @Test void extendingARoadFromADrawnDeckKeepsHeightOverWaterAndLandsOnTheBank() {
        var session = new BoardEditorSession();
        Hex start = bridge(0, 3, 8);
        start.addTerrain(new Terrain(Terrains.BRIDGE_ROAD, 1, true, 8));
        Coords water = CENTRE.translated(3), bank = water.translated(3);
        session.board().setHex(CENTRE, start);
        session.board().setHex(water, new Hex(0, "water:2", ""));
        session.board().getHex(bank).setLevel(3);
        road(session);
        point(session, CENTRE, false, "bridge");
        point(session, water, true, "ground"); point(session, bank, true, "ground");
        session.finishStroke();
        assertEquals(3, BridgeSpan.deck(session.board().getHex(water)));
        assertTrue(HexAppearance.implicit(session.board().getHex(water).getAppearance()));
        assertTrue(session.board().getHex(bank).containsTerrain(Terrains.ROAD));
        assertFalse(session.board().getHex(bank).hasBridge());
        assertFalse(HexAppearance.implicit(session.board().getHex(CENTRE).getAppearance()));
    }

    @Test void editingADeckRailEdgeDoesNotChangeAGroundRailBeneathItsNeighbour() {
        var session = new BoardEditorSession();
        Coords next = CENTRE.translated(0);
        Hex from = bridge(0, 1, 9), to = bridge(0, 1, 9);
        from.addTerrain(new Terrain(Terrains.BRIDGE_RAIL, 1, true, 8));
        to.addTerrain(new Terrain(Terrains.RAIL, 1, true, 9));
        to.addTerrain(new Terrain(Terrains.BRIDGE_RAIL, 1, true, 1));
        session.board().setHex(CENTRE, from); session.board().setHex(next, to);
        inspect(session, CENTRE);
        send(session, Action.EDGE, "bridge_rail", "0");
        assertEquals(9, session.board().getHex(next).getTerrain(Terrains.BRIDGE_RAIL).getExits());
        assertEquals(9, session.board().getHex(next).getTerrain(Terrains.RAIL).getExits());
        send(session, Action.EDGE, "bridge_rail", "0");
        assertEquals(1, session.board().getHex(next).getTerrain(Terrains.BRIDGE_RAIL).getExits());
        assertEquals(9, session.board().getHex(next).getTerrain(Terrains.RAIL).getExits());
    }

    @Test void floodRemovePreservesWaysOnASurvivingBridge() {
        var session = new BoardEditorSession();
        Hex hex = bridge(0, 4, 9);
        hex.addTerrain(new Terrain(Terrains.RAIL, 1, true, 18));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_RAIL, 1, true, 9));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_ROAD, 1, true, 9));
        session.board().setHex(CENTRE, hex);
        send(session, Action.FLOOD, "remove", "2");
        Hex result = session.board().getHex(CENTRE);
        assertTrue(result.hasBridge());
        assertTrue(result.containsTerrain(Terrains.BRIDGE_ROAD));
        assertTrue(result.containsTerrain(Terrains.BRIDGE_RAIL));
        assertFalse(result.containsTerrain(Terrains.RAIL));
    }

    private static Hex bridge(int ground, int height, int exits) {
        return new Hex(ground, "bridge:1:" + exits + ";bridge_cf:40;bridge_elev:" + height, "");
    }

    private static void road(BoardEditorSession session) { send(session, Action.CHOOSE_BRUSH, "road", "1"); }
    private static void height(BoardEditorSession session, boolean level, int offset) {
        send(session, Action.BRUSH_VALUE, "wayHeight", level ? "level" : "follow");
        send(session, Action.BRUSH_VALUE, "wayOffset", Integer.toString(offset));
    }
    private static void inspect(BoardEditorSession session, Coords at) {
        send(session, Action.TOOL, "", "SELECT"); stroke(session, at); session.release();
    }
    private static void stroke(BoardEditorSession session, Coords... cells) {
        for (int i = 0; i < cells.length; i++) { point(session, cells[i], i > 0, "ground"); }
        session.finishStroke();
    }
    private static void point(BoardEditorSession session, Coords at, boolean drag, String receiver) {
        session.pointer(at, 0, 0, drag, null, false, receiver);
    }
    private static void send(BoardEditorSession session, Action action, String target, String value) {
        session.command(new Command(action, target, value), null);
    }
}
