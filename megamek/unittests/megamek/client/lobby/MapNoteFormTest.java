/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import megamek.common.Player;
import megamek.common.SpecialHexDisplay;
import org.junit.jupiter.api.Test;

class MapNoteFormTest {

    private final Player local = new Player(1, "Local");
    private final Player other = new Player(2, "Other");

    @Test
    void editsThePlayersOwnNoteOnTheHex() {
        SpecialHexDisplay theirs = new SpecialHexDisplay(SpecialHexDisplay.Type.PLAYER_NOTE,
              SpecialHexDisplay.NO_ROUND, other, "theirs");
        SpecialHexDisplay mine = new SpecialHexDisplay(SpecialHexDisplay.Type.PLAYER_NOTE,
              SpecialHexDisplay.NO_ROUND, local, "mine");

        assertSame(mine, MapNoteForm.noteOf(List.of(theirs, mine), local));
    }

    @Test
    void aHexWithoutOneGivesANewEmptyNoteOfThePlayer() {
        SpecialHexDisplay theirs = new SpecialHexDisplay(SpecialHexDisplay.Type.PLAYER_NOTE,
              SpecialHexDisplay.NO_ROUND, other, "theirs");

        SpecialHexDisplay note = MapNoteForm.noteOf(List.of(theirs), local);

        assertEquals(SpecialHexDisplay.Type.PLAYER_NOTE, note.getType());
        assertEquals(local, note.getOwner());
        assertEquals("", note.getInfo());
    }

    @Test
    void startsFromTheNoteAndWritesTheEditsBackOnDone() {
        SpecialHexDisplay note = new SpecialHexDisplay(SpecialHexDisplay.Type.PLAYER_NOTE,
              SpecialHexDisplay.NO_ROUND, local, "old");
        note.setObscuredLevel(SpecialHexDisplay.SHD_VISIBLE_TO_TEAM);
        MapNoteForm form = new MapNoteForm(note);

        assertEquals("old", form.text());
        assertEquals(SpecialHexDisplay.SHD_VISIBLE_TO_TEAM, form.visibility());
        assertEquals(3, MapNoteForm.visibilityNames().size());

        form.text("Mines here");
        form.visibility(SpecialHexDisplay.SHD_VISIBLE_TO_ALL);
        assertEquals("old", note.getInfo(), "nothing is written before Done");

        assertSame(note, form.apply());
        assertEquals("Mines here", note.getInfo());
        assertEquals(SpecialHexDisplay.SHD_VISIBLE_TO_ALL, note.getObscuredLevel());
    }

    @Test
    void keepsTheVisibilityAmongItsChoices() {
        MapNoteForm form = new MapNoteForm(MapNoteForm.noteOf(List.of(), local));

        form.visibility(9);
        assertEquals(2, form.visibility());
        form.visibility(-1);
        assertEquals(0, form.visibility());
        form.text(null);
        assertEquals("", form.text());
    }
}
