/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.board.BoardBillboard;
import megamek.common.board.BoardDecalArt;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardGenerator;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardEditorBillboardTest {
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
        assertEquals(BoardBillboard.DEFAULT.width(), brush.billboard().width());
        assertEquals(14.0 / 48, brush.billboard().height() / brush.billboard().width(), .001, "A US bulletin's proportions");
        assertEquals(BoardBillboard.DEFAULT_LEVELS, brush.placement().offset());
        var art = BoardDecalArt.billboards();
        var decal = place().billboard().decals().getFirst();
        assertTrue(art.isEmpty() ? decal.isEmpty() : art.contains(decal), decal);
        assertEquals(List.of(BoardBillboard.RANDOM), session.snapshot().activeBrush().object().billboard().decals(),
              "The brush keeps picking anew");
    }

    @Test void settingsSurviveEditsUndoCopiesTransformsAndSaving() throws Exception {
        send(Action.ASSET, "", BoardBillboard.ASSET);
        place();
        var settings = new BoardBillboard(List.of("decal/billboards/test", ""), 30, 8, false, false);
        for (String[] change : new String[][] { { "billboard.panels", "2" }, { "billboard.decal0", "decal/billboards/test" },
              { "billboard.decal1", "" }, { "billboard.width", "30" },
              { "billboard.height", "8" }, { "billboard.legs", "false" }, { "billboard.lights", "false" }, { "offset", "3" } }) {
            send(Action.OBJECT_VALUE, change[0], change[1]);
        }
        var edited = selected();
        assertEquals(settings, edited.billboard());
        assertTrue(BoardGenerator.exists(BoardBillboard.ASSET, BoardGenerator.OBJECT));
        send(Action.OBJECT_VALUE, "billboard.width", "12");
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
        send(Action.BRUSH_VALUE, "object:billboard.width", "20");
        assertEquals(20, session.snapshot().activeBrush().object().billboard().width());
    }

    @Test void choosingRandomOnAPlacedBillboardPicksAtOnce() {
        send(Action.ASSET, "", BoardBillboard.ASSET);
        place();
        send(Action.OBJECT_VALUE, "billboard.decal0", "decal/billboards/test");
        send(Action.OBJECT_VALUE, "billboard.decal0", BoardBillboard.RANDOM);
        assertFalse(selected().billboard().decals().contains(BoardBillboard.RANDOM));
    }

    @Test void panelsComeAndGoAtTheBottomOfTheStackAndANewOnePicksAtOnce() {
        send(Action.ASSET, "", BoardBillboard.ASSET);
        place();
        send(Action.OBJECT_VALUE, "billboard.decal0", "decal/billboards/test");
        send(Action.OBJECT_VALUE, "billboard.panels", "3");
        var stack = selected().billboard();
        assertEquals(3, stack.panels());
        assertEquals("decal/billboards/test", stack.decals().getFirst(), "Existing panels keep their pictures");
        assertFalse(stack.decals().contains(BoardBillboard.RANDOM));
        assertEquals(3 * stack.height() + 2 * BoardBillboard.PADDING, stack.stackHeight(), 1e-9);
        send(Action.OBJECT_VALUE, "billboard.panels", "1");
        assertEquals(List.of("decal/billboards/test"), selected().billboard().decals());
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
        assertThrows(IllegalArgumentException.class, () -> BoardBillboard.DEFAULT.with("decal0", "decal/billboards/../../other"));
        assertThrows(IllegalArgumentException.class, () -> BoardBillboard.DEFAULT.with("decal1", ""));
        assertThrows(IllegalArgumentException.class, () -> BoardBillboard.DEFAULT.with("panels", "0"));
        assertThrows(IllegalArgumentException.class, () -> BoardBillboard.DEFAULT.with("panels", "9"));
        assertThrows(IllegalArgumentException.class, () -> BoardBillboard.DEFAULT.with("width", "0"));
        assertThrows(IllegalArgumentException.class, () -> BoardBillboard.DEFAULT.with("height", "NaN"));
        assertThrows(IllegalArgumentException.class, () -> BoardBillboard.DEFAULT.with("depth", "1"));
        var plain = new BoardDecoration("bench", "prop", "scenery/parks/bench", null, 0, 0, 0, false, 1,
              BoardDecoration.Placement.ground(), 0);
        assertThrows(IllegalArgumentException.class, () -> plain.withBillboard(BoardBillboard.DEFAULT.with("width", "5")));
    }
}
