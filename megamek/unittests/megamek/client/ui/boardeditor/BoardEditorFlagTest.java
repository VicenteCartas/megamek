/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardFlag;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardEditorFlagTest {
    @TempDir Path directory;

    @Test void artworkSurvivesEditsUndoCopiesTransformsAndSaving() throws Exception {
        var session = new BoardEditorSession();
        var at = new Coords(1, 1);
        session.pointer(at, 0, 0, false);
        var object = new BoardDecoration("flag", "prop", BoardFlag.ASSET, null, 0, 0, 0, false, 1,
              BoardDecoration.Placement.ground(), 0);
        session.board().getHex(at).setDecorations(List.of(object));
        send(session, BoardEditorSession.Action.SELECT_OBJECT, "", "flag");
        for (String[] change : new String[][] { { "flag.pattern", "vertical-3" },
              { "flag.emblem", "decal/emblems/star-league" }, { "flag.x", ".15" }, { "flag.y", "-.2" },
              { "flag.scale", ".7" }, { "flag.rotation", "90" }, { "colour1", "#12ab34" },
              { "rotationY", "90" }, { "stretchX", "2" }, { "name", "Banner" } }) {
            send(session, BoardEditorSession.Action.OBJECT_VALUE, change[0], change[1]);
        }
        var flag = new BoardFlag("vertical-3", "decal/emblems/star-league", .15, -.2, .7, 90);
        var edited = session.board().getHex(at).getDecorations().getFirst();
        assertTrue(megamek.common.board.BoardGenerator.exists(BoardFlag.ASSET, megamek.common.board.BoardGenerator.OBJECT));
        assertEquals(flag, edited.flag());
        assertEquals("#12ab34", edited.colours().slot(1));
        send(session, BoardEditorSession.Action.OBJECT_VALUE, "flag.pattern", "quarters");
        send(session, BoardEditorSession.Action.UNDO, "", "");
        assertEquals(edited, session.board().getHex(at).getDecorations().getFirst());
        assertEquals(flag, edited.duplicate().flag());
        assertEquals(flag, edited.withGroup("group").flip(true, false).withColours(BoardDecoration.Colours.NONE).flag());
        assertEquals(flag, edited.transform(1, 1, 45, true, 2, BoardDecoration.Placement.absolute(2)).flag());
        assertFalse(edited.twin(edited.withFlag(BoardFlag.DEFAULT)));
        Path file = directory.resolve("flags.board2");
        session.save(file);
        assertEquals(edited, BoardFile.read(file).getHex(at).getDecorations().getFirst());
        send(session, BoardEditorSession.Action.SAMPLE, "", "");
        assertEquals(flag, session.snapshot().activeBrush().object().flag());
        send(session, BoardEditorSession.Action.BRUSH_VALUE, "object:flag.pattern", "quarters");
        assertEquals("quarters", session.snapshot().activeBrush().object().flag().pattern());
    }

    @Test void patternsAndEmblemInputAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> BoardFlag.DEFAULT.with("emblem", "decal/emblems/../../other"));
        assertThrows(IllegalArgumentException.class, () -> BoardFlag.DEFAULT.with("pattern", "missing"));
        assertThrows(IllegalArgumentException.class, () -> BoardFlag.DEFAULT.with("scale", "0"));
        assertThrows(IllegalArgumentException.class, () -> BoardFlag.DEFAULT.with("x", "NaN"));
        for (String pattern : BoardFlag.PATTERNS) {
            BoardFlag flag = BoardFlag.DEFAULT.with("pattern", pattern);
            for (int y = 0; y <= 30; y++) {
                for (int x = 0; x <= 30; x++) { assertTrue(flag.slot(x / 30., y / 30.) >= 0 && flag.slot(x / 30., y / 30.) < 4); }
            }
        }
        var striped = BoardFlag.DEFAULT.with("pattern", "horizontal-3");
        assertEquals(0, striped.slot(.5, .1)); assertEquals(1, striped.slot(.5, .5)); assertEquals(2, striped.slot(.5, .9));
    }

    private static void send(BoardEditorSession session, BoardEditorSession.Action action, String target, String value) {
        session.command(new BoardEditorSession.Command(action, target, value), null);
    }
}
