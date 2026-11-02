/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;

/** The regular and embedded previews share the same captured hex information and its corner card. */
final class GpuMapHexCard {
    static final float WIDTH = 320;
    private final UiKit ui;
    private final Table actor;
    private String shown = "";

    GpuMapHexCard(UiKit ui) {
        this.ui = ui;
        actor = ui.panel();
        actor.setName("map-hex");
    }

    Table actor() { return actor; }

    /** Returns whether the card changed, so its host can lay out the corner only when needed. */
    boolean update(String card) {
        if (card.equals(shown)) { return false; }
        shown = card;
        actor.clearChildren();
        if (!card.isEmpty()) {
            List<String> rows = List.of(card.split("\n"));
            String[] head = cells(rows.getFirst());
            actor.add(ui.header(head[0], head[1].isEmpty() ? null : head[1])).growX().row();
            actor.add(new Image(ui.skin.getDrawable("rule"))).growX().height(1).row();
            Table list = new Table();
            list.pad(6, 14, 10, 14);
            for (String row : rows.subList(1, rows.size())) {
                String[] cells = cells(row);
                Label name = ui.label(cells[0], "hud-small", 12, UiTheme.TEXT);
                name.setWrap(true);
                list.add(name).growX().left();
                list.add(ui.label(cells[1], "hud-small", 11.5f, UiTheme.MUTED)).right().top().padLeft(12).row();
            }
            actor.add(list).growX();
        }
        return true;
    }

    private static String[] cells(String row) {
        int column = row.indexOf(GpuMapSource.COLUMN);
        return column < 0 ? new String[] { row, "" }
              : new String[] { row.substring(0, column), row.substring(column + 1) };
    }
}
