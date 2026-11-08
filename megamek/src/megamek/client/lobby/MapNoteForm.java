/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.common.SpecialHexDisplay;

/**
 * A player's note on a hex as its card edits it: the text and who may read it. Done writes both into the note, which
 * the caller then sends to the server as the Swing note dialog's caller did.
 */
public final class MapNoteForm {
    /** Who may read the note, in the order of {@link SpecialHexDisplay#SHD_VISIBLE_TO_OWNER} and its neighbours. */
    private static final List<String> VISIBILITY_KEYS = List.of("NoteDialog.owner", "NoteDialog.team",
          "NoteDialog.all");

    private final SpecialHexDisplay note;
    private String text;
    private int visibility;

    public MapNoteForm(SpecialHexDisplay note) {
        this.note = note;
        text = note.getInfo() == null ? "" : note.getInfo();
        visibility(note.getObscuredLevel());
    }

    /**
     * The local player's own note among those of a hex, or a new empty one of theirs when they have none there.
     *
     * @param shown the hex's special displays
     * @param local the local player
     */
    public static SpecialHexDisplay noteOf(Collection<SpecialHexDisplay> shown, Player local) {
        for (SpecialHexDisplay display : shown) {
            if (display.getType() == SpecialHexDisplay.Type.PLAYER_NOTE && local.equals(display.getOwner())) {
                return display;
            }
        }
        return new SpecialHexDisplay(SpecialHexDisplay.Type.PLAYER_NOTE, SpecialHexDisplay.NO_ROUND, local, "");
    }

    /** @return the names of the visibility choices, owner first */
    public static List<String> visibilityNames() {
        List<String> names = new ArrayList<>();
        for (String key : VISIBILITY_KEYS) {
            names.add(Messages.getString(key));
        }
        return names;
    }

    public String text() {
        return text;
    }

    public void text(String value) {
        text = value == null ? "" : value;
    }

    /** @return the chosen visibility, an index into {@link #visibilityNames()} */
    public int visibility() {
        return visibility;
    }

    public void visibility(int index) {
        visibility = Math.max(0, Math.min(VISIBILITY_KEYS.size() - 1, index));
    }

    /** Writes the text and visibility into the note and returns it, ready to send. */
    public SpecialHexDisplay apply() {
        note.setInfo(text);
        note.setObscuredLevel(visibility);
        return note;
    }
}
