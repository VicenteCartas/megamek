/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import megamek.client.ui.Messages;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.HexTile;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/**
 * The session commands the Assets panel's cards use: a card's optional field set to None leaves the brush, Edit's Type
 * swaps an object to another member of its family in place, the DropShip blueprint builds seven hexes at once, a
 * preset's terrain outside its component (Hazardous liquid's water) is painted too, Lava and Water replace each other,
 * and a Theme card changes only the theme and the ground's look.
 */
class BoardEditorPaletteCommandsTest {
    private static final String POND = "scenery/parks/pond-1", POOL = "scenery/pools/rectangular";

    @Test
    void noneRemovesAnOfferedFieldFromTheBrush() {
        var session = new BoardEditorSession();
        send(session, Action.CHOOSE_BRUSH, "water", "shallow");
        send(session, Action.BRUSH_VALUE, "terrain:rapids", "1");
        assertNotNull(session.snapshot().activeBrush().property("rapids"));
        send(session, Action.BRUSH_VALUE, "terrain:rapids", "");
        assertNull(session.snapshot().activeBrush().property("rapids"), "Current: None");
        Coords at = new Coords(3, 3);
        click(session, at);
        Hex hex = session.board().getHex(at);
        assertTrue(hex.containsTerrain(Terrains.WATER) && !hex.containsTerrain(Terrains.RAPIDS));
    }

    @Test
    void hazardousLiquidPaintsItsWaterWhereThereIsNone() {
        var session = new BoardEditorSession();
        Coords dry = new Coords(3, 3), lake = new Coords(6, 6);
        session.board().setHex(lake, new Hex(0, "water:3", ""));
        send(session, Action.CHOOSE_BRUSH, "hazards", "hazard-liquid");
        click(session, dry);
        click(session, lake);
        assertEquals(List.of(1, 1), List.of(session.board().getHex(dry).terrainLevel(Terrains.WATER),
              session.board().getHex(dry).terrainLevel(Terrains.HAZARDOUS_LIQUID)), "The card's pool, as its sample shows it");
        assertEquals(List.of(3, 1), List.of(session.board().getHex(lake).terrainLevel(Terrains.WATER),
              session.board().getHex(lake).terrainLevel(Terrains.HAZARDOUS_LIQUID)), "A lake keeps its depth");
        send(session, Action.TOOL, "", "ERASE");
        click(session, dry);
        assertEquals(1, session.board().getHex(dry).terrainLevel(Terrains.WATER), "Erase takes the hazards only");
        assertFalse(session.board().getHex(dry).containsTerrain(Terrains.HAZARDOUS_LIQUID));
    }

    /** Lava is liquid magma on the water's depth: the Liquids section's Lava card, beside Water. */
    @Test
    void lavaPaintsLiquidMagmaOnItsDepthAndWaterAndLavaReplaceEachOther() {
        var session = new BoardEditorSession();
        Coords dry = new Coords(2, 2), lake = new Coords(5, 5), crust = new Coords(8, 8), crusted = new Coords(11, 11);
        Coords pool = new Coords(14, 2), cooled = new Coords(2, 14);
        session.board().setHex(lake, new Hex(0, "water:3;rapids:1", ""));
        session.board().setHex(crust, new Hex(0, "magma:1", ""));
        session.board().setHex(crusted, new Hex(0, "magma:1", ""));
        session.board().setHex(pool, new Hex(0, "water:1;hazardous_liquid:1", ""));
        session.board().setHex(cooled, new Hex(0, "magma:1", ""));
        send(session, Action.CHOOSE_BRUSH, "water", "magma");
        assertEquals(1, session.snapshot().activeBrush().property("water").value(), "The card's Depth row");
        send(session, Action.BRUSH_VALUE, "terrain:water", "2");
        for (Coords at : List.of(dry, lake, crust, pool)) {
            click(session, at);
            assertEquals(List.of(2, 2, Terrain.LEVEL_NONE), liquid(session, at), "Lava of the brush's depth, without a current: " + at);
            assertTrue(HexTile.liquid(session.board().getHex(at)), "The 3D board draws its lava");
        }
        assertFalse(session.board().getHex(pool).containsTerrain(Terrains.HAZARDOUS_LIQUID), "Lava takes the hazardous liquid it hides");
        send(session, Action.CHOOSE_BRUSH, "water", "shallow");
        for (Coords at : List.of(lake, cooled)) {
            click(session, at);
            assertEquals(List.of(1, Terrain.LEVEL_NONE, Terrain.LEVEL_NONE), liquid(session, at), "Water over lava or crust is water: " + at);
            assertTrue(HexTile.liquid(session.board().getHex(at)), "The 3D board draws its water");
        }
        send(session, Action.CHOOSE_BRUSH, "water", "magma");
        send(session, Action.TOOL, "", "ERASE");
        click(session, dry);
        click(session, crusted);
        assertEquals(List.of(Terrain.LEVEL_NONE, Terrain.LEVEL_NONE, Terrain.LEVEL_NONE), liquid(session, dry),
              "Erase takes the lava with its depth");
        assertEquals(List.of(Terrain.LEVEL_NONE, 1, Terrain.LEVEL_NONE), liquid(session, crusted), "Magma crust, a Hazard, stays");
    }

    /**
     * The user's rule: a Theme card gives the hex its theme and Clear ground's look (a Grass look goes) and keeps
     * everything else, sand, water, mud, woods, a road's finish, level and objects; one stroke is one undo step, and the
     * card has no Erase. The Clear ground card still clears the ground terrain.
     */
    @Test
    void aThemeCardChangesOnlyTheThemeAndTheGroundLookInOneUndoStep() {
        var session = new BoardEditorSession();
        Coords sandy = new Coords(3, 3), pond = sandy.translated(2), muddy = pond.translated(2);
        var grass = new HexAppearance("ground/blend/grass", null, null, .5);
        Hex sand = new Hex(0, "sand:1", ""), water = new Hex(0, "water:2;sand:1", ""), mud = new Hex(2, "mud:1;woods:1;foliage_elev:2;road:3", "");
        sand.setAppearance(Map.of("ground", grass));
        water.setAppearance(Map.of("ground", grass));
        mud.setAppearance(Map.of("ground", grass, "road", new HexAppearance(null, null, "dirt", null)));
        mud.setDecorations(List.of(new BoardDecoration("car", "prop", "scenery/vehicles/car", null, 0, 0, 0, false, 1,
              BoardDecoration.Placement.ground(), 0)));
        session.board().setHex(sandy, sand); session.board().setHex(pond, water); session.board().setHex(muddy, mud);
        List<Coords> stroke = List.of(sandy, pond, muddy);
        List<Hex> before = stroke.stream().map(at -> session.board().getHex(at).duplicate()).toList();
        send(session, Action.CHOOSE_BRUSH, "theme", "mars");
        var brush = session.snapshot().activeBrush();
        assertEquals(List.of("theme/mars", "Mars", true, false), List.of(brush.key(), brush.label(), brush.themeEnabled(),
              brush.elevationEnabled()), "The Mars card's brush, Level unticked");
        session.pointer(sandy, 0, 0, false); session.pointer(pond, 0, 0, true); session.pointer(muddy, 0, 0, true);
        session.finishStroke(); session.release();
        for (int i = 0; i < stroke.size(); i++) {
            Hex expected = before.get(i).duplicate();
            expected.setTheme("mars");
            var looks = new java.util.LinkedHashMap<>(expected.getAppearance()); looks.remove("ground"); expected.setAppearance(looks);
            assertEquals(BoardFile.hexNode(expected), BoardFile.hexNode(session.board().getHex(stroke.get(i))), stroke.get(i).toString());
        }
        send(session, Action.UNDO, "", "");
        for (int i = 0; i < stroke.size(); i++) {
            assertEquals(BoardFile.hexNode(before.get(i)), BoardFile.hexNode(session.board().getHex(stroke.get(i))), "One undo step");
        }
        assertFalse(session.snapshot().canUndo());
        send(session, Action.TOOL, "", "ERASE");
        click(session, sandy);
        assertEquals(Messages.getString("BoardEditor.themeNoErase"), session.snapshot().message());
        assertFalse(session.snapshot().canUndo(), "A Theme card erases nothing");
        send(session, Action.CHOOSE_BRUSH, "ground", "clear");
        click(session, sandy);
        assertFalse(session.board().getHex(sandy).containsTerrain(Terrains.SAND), "Clear ground clears the sand");
    }

    /** The hex's water depth, magma level and current, {@link Terrain#LEVEL_NONE} for each it lacks. */
    private static List<Integer> liquid(BoardEditorSession session, Coords at) {
        Hex hex = session.board().getHex(at);
        return List.of(hex.terrainLevel(Terrains.WATER), hex.terrainLevel(Terrains.MAGMA), hex.terrainLevel(Terrains.RAPIDS));
    }

    @Test
    void typeSwapsAPondToAPoolInPlaceInOneUndoStepAndRefusesAnotherFamily() {
        var session = new BoardEditorSession();
        Coords at = new Coords(2, 2);
        send(session, Action.ASSET, "", POND);
        click(session, at);
        send(session, Action.OBJECT_VALUE, "rotation", "30");
        send(session, Action.OBJECT_VALUE, "scale", "1.5");
        BoardDecoration pond = objects(session, at).getFirst();
        send(session, Action.OBJECT_VALUE, "asset", POOL);
        BoardDecoration pool = objects(session, at).getFirst();
        assertEquals(List.of(pond.id(), POOL, pond.x(), pond.y(), 30.0, 1.5),
              List.of(pool.id(), pool.asset(), pool.x(), pool.y(), pool.rotation(), pool.scale()));
        assertEquals(BoardDecoration.Colours.NONE, pool.colours(), "The pool is in its own colours");
        send(session, Action.OBJECT_VALUE, "asset", "scenery/vehicles/car");
        assertEquals(POOL, objects(session, at).getFirst().asset(), "A car is no pool");
        send(session, Action.UNDO, "", "");
        assertEquals(pond, objects(session, at).getFirst(), "One undo step brings the pond back");
    }

    @Test
    void theDropShipBuildsSevenHexesOverTheirPavementInOneUndoStep() {
        var session = new BoardEditorSession();
        Coords centre = new Coords(5, 5);
        List<Coords> around = List.of(centre, centre.translated(0), centre.translated(1), centre.translated(2),
              centre.translated(3), centre.translated(4), centre.translated(5));
        around.forEach(at -> session.board().setHex(at, new Hex(0, "pavement:1", "")));
        send(session, Action.CHOOSE_BRUSH, "building", "dropship");
        click(session, centre);
        // The centre is piece 72; by direction N, NE, SE, S, SW, NW the shipped boards' 70, 69, 71, 73, 74, 75.
        assertEquals(List.of(72, 70, 69, 71, 73, 74, 75), around.stream()
              .map(at -> session.board().getHex(at).getTerrain(Terrains.BUILDING).getExits()).toList());
        assertTrue(around.stream().allMatch(at -> session.board().getHex(at).containsTerrain(Terrains.PAVEMENT)), "Pavement stays");
        send(session, Action.UNDO, "", "");
        assertTrue(around.stream().noneMatch(at -> session.board().getHex(at).containsTerrain(Terrains.BUILDING)), "One undo step");
        assertFalse(session.snapshot().canUndo());
        Coords edge = new Coords(0, 0);
        click(session, edge);
        assertEquals(Messages.getString("BoardEditor.blueprintOffBoard"), session.snapshot().message());
        assertFalse(session.board().getHex(edge).containsTerrain(Terrains.BUILDING), "At the edge nothing is built");
    }

    private static List<BoardDecoration> objects(BoardEditorSession session, Coords coords) {
        return session.board().getHex(coords).getDecorations();
    }

    private static void click(BoardEditorSession session, Coords coords) {
        session.pointer(coords, 0, 0, false);
        session.finishStroke();
        session.release();
    }

    private static void send(BoardEditorSession session, Action action, String target, String value) {
        session.command(new Command(action, target, value), null);
    }
}
