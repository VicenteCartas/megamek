/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.board.BoardBillboard;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardGenerator;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardEditorBillboardTest {
    private static final String WIDE = "decal/billboards/3_1/test", POSTER = "decal/billboards/2_1/test";

    @TempDir Path directory;

    private final BoardEditorSession session = new BoardEditorSession();
    private final Coords at = new Coords(1, 1);

    /** Places the armed brush on the hex and selects the billboard. */
    private BoardDecoration place() {
        session.pointer(at, 0, 0, false);
        session.finishStroke();
        var placed = session.board().getHex(at).getDecorations().getLast();
        send(Action.SELECT_OBJECT, "", placed.id());
        return placed;
    }

    private BoardDecoration selected() { return session.board().getHex(at).getDecorations().getLast(); }

    private void send(Action action, String target, String value) { session.command(new Command(action, target, value), null); }

    @Test void aNewBrushIsAStandingBulletinAndAPlacementStoresARealPick() {
        send(Action.ASSET, "", BoardBillboard.ASSET);
        var brush = session.snapshot().activeBrush().object();
        assertEquals(List.of(BoardBillboard.RANDOM), brush.billboard().decals());
        assertEquals(3, brush.billboard().width() / brush.billboard().height(), 1e-9, "Close to a US bulletin's proportions");
        assertEquals(BoardBillboard.DEFAULT_LEVELS, brush.placement().offset());
        var art = brush.billboard().art();
        var decal = place().billboard().decals().getFirst();
        assertTrue(art.isEmpty() ? decal.isEmpty() : art.contains(decal), decal);
        assertEquals(List.of(BoardBillboard.RANDOM), session.snapshot().activeBrush().object().billboard().decals(),
              "The brush keeps picking anew");
    }

    @Test void settingsSurviveEditsUndoCopiesTransformsAndSaving() throws Exception {
        send(Action.ASSET, "", BoardBillboard.ASSET);
        place();
        var settings = new BoardBillboard(List.of(POSTER, ""), "2_1", 1.5, false, false);
        for (String[] change : new String[][] { { "billboard.panels", "2" }, { "billboard.ratio", "2_1" },
              { "billboard.decal0", POSTER }, { "billboard.decal1", "" }, { "billboard.scale", "1.5" },
              { "billboard.legs", "false" }, { "billboard.lights", "false" }, { "offset", "3" } }) {
            send(Action.OBJECT_VALUE, change[0], change[1]);
        }
        var edited = selected();
        assertEquals(settings, edited.billboard());
        assertTrue(BoardGenerator.exists(BoardBillboard.ASSET, BoardGenerator.OBJECT));
        send(Action.OBJECT_VALUE, "billboard.scale", "3");
        send(Action.UNDO, "", "");
        assertEquals(edited, selected());
        assertEquals(settings, edited.duplicate().billboard());
        assertEquals(settings, edited.transform(1, 1, 45, false, 2, BoardDecoration.Placement.absolute(2)).billboard());
        assertFalse(edited.twin(edited.withBillboard(BoardBillboard.DEFAULT)));
        var file = directory.resolve("billboards.board2");
        session.save(file);
        assertEquals(edited, BoardFile.read(file).getHex(at).getDecorations().getFirst());
        send(Action.SAMPLE, "", "");
        assertEquals(settings, session.snapshot().activeBrush().object().billboard());
        send(Action.BRUSH_VALUE, "object:billboard.scale", "2");
        assertEquals(2, session.snapshot().activeBrush().object().billboard().scale());
    }

    @Test void choosingRandomOnAPlacedBillboardPicksAtOnce() {
        send(Action.ASSET, "", BoardBillboard.ASSET);
        place();
        send(Action.OBJECT_VALUE, "billboard.decal0", WIDE);
        send(Action.OBJECT_VALUE, "billboard.decal0", BoardBillboard.RANDOM);
        assertFalse(selected().billboard().decals().contains(BoardBillboard.RANDOM));
    }

    @Test void aNewRatioPicksItsPicturesFromItsOwnFolderAndKeepsBlankPanels() {
        send(Action.ASSET, "", BoardBillboard.ASSET);
        place();
        send(Action.OBJECT_VALUE, "billboard.panels", "2");
        send(Action.OBJECT_VALUE, "billboard.decal0", WIDE);
        send(Action.OBJECT_VALUE, "billboard.decal1", "");
        send(Action.OBJECT_VALUE, "billboard.ratio", "2_3");
        var portrait = selected().billboard();
        var art = portrait.art();
        String picked = portrait.decals().getFirst();
        assertTrue(art.isEmpty() ? picked.isEmpty() : art.contains(picked), picked);
        assertEquals("", portrait.decals().get(1));
        assertEquals(2.0 / 3, portrait.width() / portrait.height(), 1e-9);
        assertEquals(BoardBillboard.DEFAULT.height(), portrait.height(), 1e-9, "A new ratio keeps the panels' height");
    }

    @Test void panelsComeAndGoAtTheBottomOfTheStackAndANewOnePicksAtOnce() {
        send(Action.ASSET, "", BoardBillboard.ASSET);
        place();
        send(Action.OBJECT_VALUE, "billboard.decal0", WIDE);
        send(Action.OBJECT_VALUE, "billboard.panels", "3");
        var stack = selected().billboard();
        assertEquals(3, stack.panels());
        assertEquals(WIDE, stack.decals().getFirst(), "Existing panels keep their pictures");
        assertFalse(stack.decals().contains(BoardBillboard.RANDOM));
        assertEquals(3 * stack.height() + 2 * BoardBillboard.PADDING, stack.stackHeight(), 1e-9);
        send(Action.OBJECT_VALUE, "billboard.panels", "1");
        assertEquals(List.of(WIDE), selected().billboard().decals());
        send(Action.BRUSH_VALUE, "object:billboard.panels", "2");
        assertEquals(List.of(BoardBillboard.RANDOM, BoardBillboard.RANDOM), session.snapshot().activeBrush().object().billboard().decals());
    }

    @Test void aReflectionKeepsTheArtReadable() {
        var object = new BoardDecoration("board", "prop", BoardBillboard.ASSET, null, .2, 0, 30, false, 1,
              BoardDecoration.Placement.ground(), 0);
        var flipped = object.flip(true, false);
        assertFalse(flipped.mirror());
        assertEquals(-30, flipped.rotation());
        assertEquals(-.2, flipped.x());
    }

    @Test void inputIsValidated() {
        assertThrows(IllegalArgumentException.class, () -> BoardBillboard.DEFAULT.with("decal0", "decal/emblems/star-league"));
        assertThrows(IllegalArgumentException.class, () -> BoardBillboard.DEFAULT.with("decal0", POSTER), "Another ratio's picture");
        assertThrows(IllegalArgumentException.class, () -> BoardBillboard.DEFAULT.with("decal0", "decal/billboards/3_1/../../other"));
        assertThrows(IllegalArgumentException.class, () -> BoardBillboard.DEFAULT.with("decal0", "decal/billboards/3_1/more/other"));
        assertThrows(IllegalArgumentException.class, () -> BoardBillboard.DEFAULT.with("decal1", ""));
        assertThrows(IllegalArgumentException.class, () -> BoardBillboard.DEFAULT.with("panels", "0"));
        assertThrows(IllegalArgumentException.class, () -> BoardBillboard.DEFAULT.with("panels", "9"));
        assertThrows(IllegalArgumentException.class, () -> BoardBillboard.DEFAULT.with("ratio", "4_3"));
        assertThrows(IllegalArgumentException.class, () -> BoardBillboard.DEFAULT.with("scale", "0"));
        assertThrows(IllegalArgumentException.class, () -> BoardBillboard.DEFAULT.with("scale", "NaN"));
        assertThrows(IllegalArgumentException.class, () -> BoardBillboard.DEFAULT.with("width", "5"), "The ratio sets the size");
        var plain = new BoardDecoration("bench", "prop", "scenery/parks/bench", null, 0, 0, 0, false, 1,
              BoardDecoration.Placement.ground(), 0);
        assertThrows(IllegalArgumentException.class, () -> plain.withBillboard(BoardBillboard.DEFAULT.with("scale", "2")));
    }
}
