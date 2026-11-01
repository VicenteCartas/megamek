/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.client.ui.boardeditor.BoardEditorSession.Level;
import megamek.common.Hex;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/** The side view's height handles (the HEIGHT command) and its placement by click (PLACE), through the session. */
class BoardEditorSectionTest {
    private final BoardEditorSession session = new BoardEditorSession();

    private void send(Action action, String target, String value) { session.command(new Command(action, target, value), null); }
    private void select(Coords at) { send(Action.SELECT_AT, at.getX() + "," + at.getY(), ""); }
    private Hex hex(Coords at) { return session.board().getHex(at); }
    private Level level(String key) {
        return session.snapshot().levels().stream().filter(level -> level.key().equals(key)).findFirst().orElse(null);
    }

    /** A live drag: several values, then the release, which is one undo step. */
    private void drag(String key, double... values) {
        for (double value : values) { session.command(new Command(Action.HEIGHT, key, Double.toString(value)), null, true); }
        session.finishStroke();
    }

    @Test void groundAndWaterBedMoveTheHexLevelAndTheDepth() {
        Coords at = new Coords(2, 2);
        session.board().setHex(at, new Hex(2, "water:2", ""));
        select(at);
        assertEquals(2, level("ground").level(), "On water the hex level is the surface");
        assertEquals(0, level("water").level(), "The bed lies the depth below it");
        drag("ground", 3, 4);
        assertEquals(4, hex(at).getLevel());
        assertEquals(2, hex(at).terrainLevel(Terrains.WATER), "Moving the surface keeps the depth");
        send(Action.UNDO, "", "");
        assertEquals(2, hex(at).getLevel());
        assertFalse(session.snapshot().canUndo(), "A drag is one undo step");
        drag("water", -1);
        assertEquals(3, hex(at).terrainLevel(Terrains.WATER));
        drag("water", 5);
        assertEquals(0, hex(at).terrainLevel(Terrains.WATER), "The bed cannot rise above the surface");
    }

    @Test void deckMovesTheWholeSpanAndRoofTheWholeStructure() {
        // A bridge over three hexes of uneven ground, joined north to south, and an unrelated bridge.
        Coords north = new Coords(1, 1), middle = new Coords(1, 2), south = new Coords(1, 3), other = new Coords(5, 5);
        session.board().setHex(north, new Hex(0, "bridge:1:9;bridge_cf:40;bridge_elev:4", ""));
        session.board().setHex(middle, new Hex(1, "bridge:1:9;bridge_cf:40;bridge_elev:3", ""));
        session.board().setHex(south, new Hex(0, "bridge:1:9;bridge_cf:40;bridge_elev:4", ""));
        session.board().setHex(other, new Hex(0, "bridge:1:9;bridge_cf:40;bridge_elev:4", ""));
        select(middle);
        assertEquals(4, level("bridge").level());
        assertEquals("bridge", level("bridge").receiver());
        drag("bridge", 5, 6);
        assertEquals(List.of(6, 5, 6), List.of(hex(north).terrainLevel(Terrains.BRIDGE_ELEV),
              hex(middle).terrainLevel(Terrains.BRIDGE_ELEV), hex(south).terrainLevel(Terrains.BRIDGE_ELEV)), "One deck level");
        assertEquals(4, hex(other).terrainLevel(Terrains.BRIDGE_ELEV), "Another bridge keeps its deck");
        send(Action.UNDO, "", "");
        assertEquals(3, hex(middle).terrainLevel(Terrains.BRIDGE_ELEV));
        assertEquals(4, hex(north).terrainLevel(Terrains.BRIDGE_ELEV));

        // Buildings join only through the exits they state: here north to south.
        Coords a = new Coords(3, 2), b = new Coords(3, 3);
        session.board().setHex(a, new Hex(1, "building:1:8;bldg_cf:15;bldg_elev:3", ""));
        session.board().setHex(b, new Hex(2, "building:1:1;bldg_cf:15;bldg_elev:2", ""));
        select(b);
        assertEquals(4, level("building").level());
        drag("building", 6);
        assertEquals(5, hex(a).terrainLevel(Terrains.BLDG_ELEV));
        assertEquals(4, hex(b).terrainLevel(Terrains.BLDG_ELEV));
        drag("building", -3);
        assertEquals(1, hex(b).terrainLevel(Terrains.BLDG_ELEV), "A building keeps at least one level");
    }

    @Test void pillarsToggleTheWholeSpanInOneUndoStep() {
        // A bridge over three hexes joined north to south, and an unrelated bridge.
        Coords north = new Coords(1, 1), middle = new Coords(1, 2), south = new Coords(1, 3), other = new Coords(5, 5);
        for (Coords at : List.of(north, middle, south, other)) {
            session.board().setHex(at, new Hex(0, "bridge:1:9;bridge_cf:40;bridge_elev:2", ""));
        }
        select(middle);
        send(Action.PILLARS, "", "true");
        assertEquals(List.of(true, true, true, false), List.of(north, middle, south, other).stream()
              .map(at -> HexAppearance.pillars(hex(at).getAppearance())).toList(), "The whole span, not another bridge");
        assertTrue(HexAppearance.pillars(session.snapshot().appearance()), "The panel shows the selected hex's state");
        send(Action.UNDO, "", "");
        assertTrue(List.of(north, middle, south).stream().noneMatch(at -> HexAppearance.pillars(hex(at).getAppearance())));
        assertFalse(session.snapshot().canUndo(), "One undo step");

        send(Action.PILLARS, "", "true");
        send(Action.REMOVE_COMPONENT, "", "bridge");
        assertFalse(hex(middle).getAppearance().containsKey("bridge"), "Removing the bridge drops its piers");
        select(new Coords(3, 3));
        session.command(new Command(Action.PILLARS, "", "true"), null);
        assertEquals("Select a bridge first.", session.snapshot().message(), "Only a bridge has piers");
    }

    private List<HexAppearance> types(Coords... cells) {
        return java.util.Arrays.stream(cells).map(at -> hex(at).getAppearance().get("bridge")).toList();
    }

    @Test void footprintSwitchesTheWholeConnectedBuildingInOneUndoStep() {
        // A building over three hexes joined north to south, and an unrelated building.
        Coords north = new Coords(1, 1), middle = new Coords(1, 2), south = new Coords(1, 3), other = new Coords(5, 5);
        for (Coords at : List.of(north, middle, south, other)) {
            int exits = at.equals(north) ? 8 : at.equals(middle) ? 9 : at.equals(south) ? 1 : 0;
            session.board().setHex(at, new Hex(0, "building:1:" + exits + ";bldg_cf:40;bldg_elev:2", ""));
        }
        var square = HexAppearance.Footprint.SQUARE;
        var angled = HexAppearance.Footprint.ANGLED;
        select(middle);
        send(Action.FOOTPRINT, "", "square");
        assertEquals(List.of(square, square, square, angled), footprints(north, middle, south, other),
              "The whole connected building, not another one");
        assertEquals(square, HexAppearance.footprint(session.snapshot().appearance()), "The panel shows it");
        send(Action.UNDO, "", "");
        assertEquals(List.of(angled, angled, angled), footprints(north, middle, south));
        assertFalse(session.snapshot().canUndo(), "One undo step");
        send(Action.FOOTPRINT, "", "square");
        // A building hex joined to it, even through its own exit alone, takes its footprint.
        Coords added = south.translated(3);
        session.board().setHex(added, new Hex(0, "building:1:0;bldg_cf:40;bldg_elev:2", ""));
        select(added);
        send(Action.EDGE, "building", "0");
        assertEquals(square, HexAppearance.footprint(hex(added).getAppearance()), "A new hex of the building joins it");
        select(middle);
        send(Action.FOOTPRINT, "", "angled");
        assertFalse(hex(north).getAppearance().containsKey("footprint"), "Angled, the default, is stored as none");
        assertFalse(hex(added).getAppearance().containsKey("footprint"), "The whole building, the new hex too");
        // Two buildings joined become one footprint, the one most of the hexes the edit left alone hold, on every hex.
        Coords above = other.translated(0), beside = other.translated(2), next = beside.translated(2);
        session.board().setHex(above, new Hex(0, "building:1:" + (1 << 3) + ";bldg_cf:40;bldg_elev:2", ""));
        session.board().setHex(beside, new Hex(0, "building:1:" + (1 << 2) + ";bldg_cf:40;bldg_elev:2", ""));
        session.board().setHex(next, new Hex(0, "building:1:0;bldg_cf:40;bldg_elev:2", ""));
        select(other);
        send(Action.FOOTPRINT, "", "square");
        assertEquals(List.of(square, square), footprints(other, above), "Joined through the other hex's exit");
        send(Action.EDGE, "building", "2");
        assertEquals(List.of(angled, angled, angled, angled), footprints(other, above, beside, next), "Joined, one footprint");
        select(new Coords(3, 3));
        send(Action.FOOTPRINT, "", "square");
        assertEquals("Select a building first.", session.snapshot().message(), "Only a building has a footprint");
    }

    @Test void kitVariantSetsTheWholeConnectedBuildingInOneUndoStep() {
        // A lunar habitat over three hexes joined north to south: a generated kit with variants.
        Coords north = new Coords(1, 1), middle = new Coords(1, 2), south = new Coords(1, 3);
        for (Coords at : List.of(north, middle, south)) {
            int exits = at.equals(north) ? 8 : at.equals(middle) ? 9 : 1;
            Hex hex = new Hex(0, "building:1:" + exits + ";bldg_cf:40;bldg_elev:2", "");
            hex.setAppearance(java.util.Map.of("building", new HexAppearance("building/lunar-habitat", null, null, null)));
            session.board().setHex(at, hex);
        }
        select(middle);
        assertEquals(List.of("rounded", "parapet"), session.snapshot().kitVariants(), "The panel lists the spec's names");
        send(Action.KIT_VARIANT, "", "gone");
        assertEquals("Choose one of the building's variants.", session.snapshot().message(), "Only a listed name");
        send(Action.KIT_VARIANT, "", "parapet");
        assertEquals(List.of("parapet", "parapet", "parapet"), variants(north, middle, south), "The whole connected building");
        send(Action.UNDO, "", "");
        assertEquals(java.util.Arrays.asList(null, null, null), variants(north, middle, south), "None stored, the first drawn");
        assertFalse(session.snapshot().canUndo(), "One undo step");
        send(Action.KIT_VARIANT, "", "parapet");
        Coords added = south.translated(3);
        Hex joining = new Hex(0, "building:1:0;bldg_cf:40;bldg_elev:2", "");
        joining.setAppearance(java.util.Map.of("building", new HexAppearance("building/lunar-habitat", null, null, null)));
        session.board().setHex(added, joining);
        select(added);
        send(Action.EDGE, "building", "0");
        assertEquals("parapet", HexAppearance.kitVariant(hex(added).getAppearance()), "A new hex of the building joins it");
    }

    private List<String> variants(Coords... cells) {
        return java.util.Arrays.stream(cells).map(at -> HexAppearance.kitVariant(hex(at).getAppearance())).toList();
    }

    private List<HexAppearance.Footprint> footprints(Coords... cells) {
        return java.util.Arrays.stream(cells).map(at -> HexAppearance.footprint(hex(at).getAppearance())).toList();
    }

    @Test void bridgeTypeSwitchesTheWholeSpanInOneUndoStep() {
        // A bridge over three hexes joined north to south, and an unrelated built bridge.
        Coords north = new Coords(1, 1), middle = new Coords(1, 2), south = new Coords(1, 3), other = new Coords(5, 5);
        for (Coords at : List.of(north, middle, south, other)) {
            Hex hex = new Hex(0, "bridge:1:9;bridge_cf:40;bridge_elev:2", "");
            hex.setAppearance(java.util.Map.of("bridge", HexAppearance.BUILT_BRIDGE));
            session.board().setHex(at, hex);
        }
        var natural = HexAppearance.NATURAL_BRIDGE;
        var built = HexAppearance.BUILT_BRIDGE;
        var pillars = HexAppearance.PILLARS;
        select(middle);
        send(Action.BRIDGE_TYPE, "", "natural");
        assertEquals(List.of(natural, natural, natural, built), types(north, middle, south, other), "The whole span, not another bridge");
        assertEquals(Boolean.FALSE, HexAppearance.bridgeBuilt(session.snapshot().appearance()), "The panel shows the selected hex's type");
        send(Action.UNDO, "", "");
        assertEquals(List.of(built, built, built), types(north, middle, south));
        assertFalse(session.snapshot().canUndo(), "One undo step");

        // Pillars off leaves a built bridge; Built keeps the piers, Natural drops them.
        send(Action.PILLARS, "", "true");
        assertEquals(List.of(pillars, pillars, pillars), types(north, middle, south));
        send(Action.BRIDGE_TYPE, "", "built");
        assertEquals(List.of(pillars, pillars, pillars), types(north, middle, south), "Built keeps the piers");
        send(Action.PILLARS, "", "false");
        assertEquals(List.of(built, built, built), types(north, middle, south), "Pillars off is a built bridge without piers");
        send(Action.PILLARS, "", "true");
        send(Action.BRIDGE_TYPE, "", "natural");
        assertEquals(List.of(natural, natural, natural), types(north, middle, south), "A rock arch has no piers");

        // A hex added to the span takes its type; a typed card's stroke retypes the span it joins.
        Coords added = south.translated(3);
        select(added);
        send(Action.ADD_COMPONENT, "", "bridge");
        assertEquals(built, hex(added).getAppearance().get("bridge"), "+ Add Bridge is an explicit local bridge");
        send(Action.CHOOSE_BRUSH, "bridge", "bridge/built");
        session.pointer(added.translated(3), 0, 0, false);
        session.finishStroke();
        assertEquals(List.of(natural, natural, natural, built, built), types(north, middle, south, added, added.translated(3)),
              "A Built bridge card changes only the hexes it paints");
        select(new Coords(3, 3));
        send(Action.BRIDGE_TYPE, "", "natural");
        assertEquals("Select a bridge first.", session.snapshot().message(), "Only a bridge has a type");
    }

    @Test void bridgeCardsChangeOnlyTheirPaintedHexesWhileSpanActionsStayExplicit() {
        Coords north = new Coords(1, 1), middle = new Coords(1, 2), south = new Coords(1, 3);
        for (Coords at : List.of(north, middle, south)) {
            Hex hex = new Hex(0, "bridge:1:9;bridge_cf:40;bridge_elev:2", "");
            hex.setAppearance(java.util.Map.of("bridge", HexAppearance.PILLARS));
            session.board().setHex(at, hex);
        }
        send(Action.CHOOSE_BRUSH, "bridge", "bridge/natural");
        session.pointer(middle, 0, 0, false, null, false, "bridge");
        session.finishStroke();
        assertEquals(List.of(HexAppearance.PILLARS, HexAppearance.NATURAL_BRIDGE, HexAppearance.PILLARS),
              types(north, middle, south), "Painting a rock arch does not retype its neighbours");
        select(middle);
        send(Action.PILLARS, "", "true");
        assertEquals(List.of(HexAppearance.PILLARS, HexAppearance.PILLARS, HexAppearance.PILLARS), types(north, middle, south));
        send(Action.UNDO, "", "");
        assertEquals(HexAppearance.NATURAL_BRIDGE, hex(middle).getAppearance().get("bridge"));
    }

    @Test void trussDesignAndColourChangeTheWholeSpanInOneUndoStep() {
        // A pillared bridge over three hexes joined north to south, and an unrelated built bridge.
        Coords north = new Coords(1, 1), middle = new Coords(1, 2), south = new Coords(1, 3), other = new Coords(5, 5);
        for (Coords at : List.of(north, middle, south, other)) {
            Hex hex = new Hex(0, "bridge:1:9;bridge_cf:40;bridge_elev:2", "");
            hex.setAppearance(java.util.Map.of("bridge", at.equals(other) ? HexAppearance.BUILT_BRIDGE : HexAppearance.PILLARS));
            session.board().setHex(at, hex);
        }
        var green = BoardDecoration.Colours.of("#2e5233");
        var truss = HexAppearance.built(true, true, BoardDecoration.Colours.NONE);
        var greenTruss = HexAppearance.built(true, true, green);
        select(middle);
        send(Action.BRIDGE_COLOUR, "", "#2e5233");
        assertEquals("Choose the truss design first.", session.snapshot().message(), "A standard deck has no colour");
        send(Action.BRIDGE_DESIGN, "", "truss");
        assertEquals(List.of(truss, truss, truss, HexAppearance.BUILT_BRIDGE), types(north, middle, south, other),
              "The whole span keeps its piers, not another bridge");
        send(Action.BRIDGE_COLOUR, "", "#2e5233");
        assertEquals(List.of(greenTruss, greenTruss, greenTruss), types(north, middle, south), "One colour for the span");
        send(Action.UNDO, "", "");
        assertEquals(List.of(truss, truss, truss), types(north, middle, south), "The colour is one undo step");
        send(Action.UNDO, "", "");
        assertEquals(List.of(HexAppearance.PILLARS, HexAppearance.PILLARS, HexAppearance.PILLARS), types(north, middle, south),
              "The design is one undo step");
        assertFalse(session.snapshot().canUndo());

        // Pillars and Built keep the design and colour; a joining hex and a Truss card's stroke take the span's.
        send(Action.BRIDGE_DESIGN, "", "truss");
        send(Action.BRIDGE_COLOUR, "", "#2e5233");
        send(Action.PILLARS, "", "false");
        var greenDeck = HexAppearance.built(true, false, green);
        assertEquals(List.of(greenDeck, greenDeck, greenDeck), types(north, middle, south), "Pillars keep the truss and colour");
        send(Action.BRIDGE_TYPE, "", "built");
        assertEquals(List.of(greenDeck, greenDeck, greenDeck), types(north, middle, south), "Built keeps the truss");
        Coords added = south.translated(3);
        select(added);
        send(Action.ADD_COMPONENT, "", "bridge");
        assertEquals(HexAppearance.BUILT_BRIDGE, hex(added).getAppearance().get("bridge"), "+ Add Bridge is local");
        send(Action.CHOOSE_BRUSH, "bridge", "bridge/truss");
        session.pointer(other.translated(0), 0, 0, false);
        session.pointer(other, 0, 0, false, null, false, "bridge");
        session.finishStroke();
        assertTrue(HexAppearance.truss(hex(other).getAppearance()) && HexAppearance.truss(hex(other.translated(0)).getAppearance()),
              "A Truss card's stroke makes the span it paints a truss");
        assertFalse(HexAppearance.pillars(hex(other).getAppearance()), "and keeps its piers");
        send(Action.CHOOSE_BRUSH, "bridge", "bridge/built");
        session.pointer(added, 0, 0, false);
        session.finishStroke();
        assertEquals(List.of(greenDeck, greenDeck, greenDeck, HexAppearance.BUILT_BRIDGE), types(north, middle, south, added),
              "A Built card leaves the other hexes' design unchanged");
        select(middle);
        send(Action.BRIDGE_DESIGN, "", "truss");
        send(Action.BRIDGE_TYPE, "", "natural");
        assertEquals(List.of(HexAppearance.NATURAL_BRIDGE, HexAppearance.BUILT_BRIDGE), types(north, added),
              "The span action changes the connected bridge, not the separate new bridge");
        send(Action.BRIDGE_DESIGN, "", "truss");
        assertEquals("Make the bridge built first.", session.snapshot().message());
    }

    @Test void structureTopsAndCanopyMoveTheirHex() {
        Coords tank = new Coords(4, 2), industry = new Coords(4, 3), woods = new Coords(3, 3);
        session.board().setHex(tank, new Hex(1, "fuel_tank:1;fuel_tank_cf:15;fuel_tank_elev:2;fuel_tank_magn:100", ""));
        session.board().setHex(industry, new Hex(0, "heavy_industrial:4", ""));
        session.board().setHex(woods, new Hex(1, "woods:2;foliage_elev:2", ""));
        select(tank);
        drag("fuelTank", 5);
        assertEquals(4, hex(tank).terrainLevel(Terrains.FUEL_TANK_ELEV));
        select(industry);
        assertEquals("industrial", level("industry").receiver());
        drag("industry", 6);
        assertEquals(6, hex(industry).terrainLevel(Terrains.INDUSTRIAL));
        select(woods);
        assertEquals(3, level("vegetation").level());
        assertEquals("", level("vegetation").receiver(), "Nothing is placed on a canopy");
        drag("vegetation", 2);
        assertEquals(1, hex(woods).terrainLevel(Terrains.FOLIAGE_ELEV));
    }

    @Test void objectsMoveTheirOffsetOrFixedLevelAndDecalsStay() {
        Coords at = new Coords(2, 2);
        Hex hex = new Hex(1, "bridge:1:9;bridge_cf:40;bridge_elev:3", "");
        hex.setDecorations(List.of(
              new BoardDecoration("car", "prop", "scenery/vehicles/car", null, 0, 0, 0, false, 1,
                    BoardDecoration.Placement.surface("bridge", "deck", 0), 0),
              new BoardDecoration("sign", "prop", "scenery/vehicles/car", null, 0, 0, 0, false, 1,
                    BoardDecoration.Placement.absolute(3), 0),
              new BoardDecoration("mark", "decal", "decal/damage/rubble-light-path", null, 0, 0, 0, false, 1,
                    BoardDecoration.Placement.ground(), 0)));
        session.board().setHex(at, hex);
        select(at);
        assertEquals(4, level("car").level(), "On the deck");
        drag("car", 5, 5.25);
        assertEquals(1.25, object(at, "car").placement().offset(), 1e-9);
        assertEquals("bridge", object(at, "car").placement().receiver().terrain());
        drag("sign", 4.5);
        assertEquals(4.5, object(at, "sign").placement().level(), 1e-9);
        assertEquals(level("mark").min(), level("mark").max(), "A decal cannot be moved");
        send(Action.HEIGHT, "mark", "3");
        assertEquals(0, object(at, "mark").placement().offset());
        // Raising the deck carries the car with it.
        drag("bridge", 6);
        assertEquals(7.25, level("car").level(), 1e-9);
        // Neither the side view nor the inspector takes an object deeper than a level below the hex's floor.
        drag("car", -20);
        assertEquals(0, level("car").level(), 1e-9);
        send(Action.SELECT_OBJECT, "", "sign");
        send(Action.OBJECT_VALUE, "level", "-7");
        assertEquals(0, object(at, "sign").placement().level(), 1e-9);
    }

    @Test void clickPlacesTheArmedObjectOnASupportOrAtAFixedLevel() {
        Coords at = new Coords(2, 2);
        session.board().setHex(at, new Hex(1, "bridge:1:9;bridge_cf:40;bridge_elev:3", ""));
        select(at);
        send(Action.ASSET, "", "scenery/vehicles/car");
        send(Action.PLACE, "bridge", "0.1,-0.2,4");
        var placed = object(at, session.snapshot().object());
        assertEquals("bridge", placed.placement().receiver().terrain());
        assertEquals(.1, placed.x(), 1e-9);
        assertEquals(-.2, placed.y(), 1e-9);
        assertEquals(List.of(new BoardEditorSession.Selection(at, placed.id())), session.snapshot().selection());
        send(Action.UNDO, "", "");
        assertTrue(hex(at).getDecorations().isEmpty(), "A placement is one undo step");
        send(Action.PLACE, "", "0,0,3.25");
        assertEquals(3.25, object(at, session.snapshot().object()).placement().level(), 1e-9);
        assertEquals("absolute", object(at, session.snapshot().object()).placement().mode());
        send(Action.PLACE, "building", "0,0,0");
        assertEquals(1, hex(at).getDecorations().size(), "No building to stand on");

        Coords ice = new Coords(4, 4);
        session.board().setHex(ice, new Hex(0, "water:1;ice:1", ""));
        select(ice);
        send(Action.ASSET, "", "decal/damage/rubble-light-path");
        send(Action.PLACE, "", "0,0,2");
        assertTrue(hex(ice).getDecorations().isEmpty(), "A decal is never placed in the air");
        send(Action.PLACE, "ground", "0,0,0");
        assertEquals("ice", hex(ice).getDecorations().getFirst().placement().receiver().terrain(), "On ice, as a Paint click");
    }

    @Test void levelsListTheHexBottomToTop() {
        Coords at = new Coords(2, 2);
        session.board().setHex(at, new Hex(1, "water:2;bridge:1:9;bridge_cf:40;bridge_elev:3", ""));
        select(at);
        assertEquals(List.of("water", "ground", "bridge"), session.snapshot().levels().stream().map(Level::key).toList());
        select(new Coords(9, 9));
        assertEquals(List.of("ground"), session.snapshot().levels().stream().map(Level::key).toList());
        assertNull(level("bridge"));
    }

    private BoardDecoration object(Coords at, String id) {
        return hex(at).getDecorations().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }
}
