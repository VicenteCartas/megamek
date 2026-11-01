/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.lobby.RequisitionReadout;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.entityreadout.EntityReadout;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiReveal;

/** A unit readout's rows as the kit lays them out, shared by the Requisition's dossier and the unit readout. GPU thread. */
final class LobbyReadoutRows {
    private final UiKit kit;
    private final boolean motion;

    private LobbyReadoutRows(UiKit kit, boolean motion) {
        this.kit = kit;
        this.motion = motion;
    }

    /** The rows of a readout, block by block; {@code motion} fades the blocks in. */
    static Table of(UiKit kit, boolean motion, List<EntityReadout.Row> rows) {
        return new LobbyReadoutRows(kit, motion).rows(rows);
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static float size(String name) {
        return DesignTokens.shipped().size().get(name);
    }

        /**
     * The readout's rows in the kit's type, block by block, each fading in after the one before the first time the
     * readout shows (gap analysis, Review fixes): the unit's name, headings in overline, labelled lines, text sections
     * as a heading over their text at full width, and tables whose columns line up across every table of the readout.
     */
    private Table rows(List<EntityReadout.Row> rows) {
        float[] columns = readoutColumns(rows);
        UiReveal content = new UiReveal(motion);
        boolean first = true;
        for (List<EntityReadout.Row> rowsOfBlock : RequisitionReadout.blocks(rows)) {
            Table block = new Table();
            block.top().left();
            Table run = null;
            EntityReadout.Row previous = null;
            for (EntityReadout.Row row : rowsOfBlock) {
                if (!RequisitionReadout.isTable(row) && run != null) {
                    block.add(run).growX().minWidth(0).left().row();
                    run = null;
                }
                String text = String.join(" ", row.cells());
                switch (row.kind()) {
                    case TITLE -> {
                        if (first) {
                            block.add(wrapped(text, "body.strong", "text.primary")).growX().minWidth(0).row();
                        } else {
                            block.add(heading(text)).growX().minWidth(0).padTop(space("md")).row();
                        }
                    }
                    case SPACE -> {
                        if (previous == null || previous.kind() != EntityReadout.Row.Kind.TITLE || first) {
                            block.add().height(space("sm")).row();
                        }
                    }
                    case LABELED -> {
                        String label = row.cells().isEmpty() ? "" : row.cells().get(0);
                        String value = String.join(" ", row.cells().subList(Math.min(1, row.cells().size()),
                              row.cells().size()));
                        if (RequisitionReadout.isSection(row)) {
                            block.add(heading(label)).growX().minWidth(0).padTop(space("md")).row();
                            block.add(wrapped(value, "body", "text.primary")).growX().minWidth(0)
                                  .padTop(space("xs")).row();
                        } else {
                            Table line = new Table();
                            line.left().top();
                            line.add(kit.typeLabel(label, "body", "text.secondary")).top().left()
                                  .padRight(space("md"));
                            line.add(wrapped(value, "body", "text.primary")).growX().minWidth(0).top();
                            block.add(line).growX().minWidth(0).padTop(space("xxs")).row();
                        }
                    }
                    case TABLE_HEADER, TABLE_ROW -> {
                        if (run == null) {
                            run = new Table();
                            run.top().left();
                        }
                        tableRow(run, row, columns);
                    }
                    case TEXT -> block.add(wrapped(text, "body", "text.primary")).growX().minWidth(0)
                          .padTop(space("xxs")).row();
                }
                first = false;
                previous = row;
            }
            if (run != null) {
                block.add(run).growX().minWidth(0).left().row();
            }
            content.band(block);
        }
        return content;
    }

    /** A readout heading: a title over a table or a text section, in overline. */
    private Label heading(String text) {
        Label heading = kit.roleTypeLabel("overline", text, "text.muted");
        heading.setWrap(true);
        return heading;
    }

    /**
     * One row of a readout table: its cells up to the last that holds anything, each but the last as wide as its
     * column is across the readout, so every table's columns line up; the last runs to the edge.
     */
    private void tableRow(Table run, EntityReadout.Row row, float[] columns) {
        boolean header = row.kind() == EntityReadout.Row.Kind.TABLE_HEADER;
        int count = RequisitionReadout.filledCells(row.cells());
        for (int i = 0; i < count; i++) {
            Label cell = wrapped(row.cells().get(i), header ? "small" : "body", header ? "text.muted" : "text.primary");
            Cell<Label> placed = run.add(cell).left().top().padTop(space("xxs"));
            if (i < count - 1) {
                placed.width(columns[i]).padRight(space("md"));
            } else {
                placed.growX().minWidth(0);
            }
        }
        run.row();
    }

    /**
     * The widths of the readout tables' columns, shared by every table: each column as wide as its widest cell that
     * is not the last of its row, the first at least as wide as "Center Torso (rear)" so a location never wraps, and
     * none wider than a quarter of a small dialog (the first, nearly half), past which a cell wraps.
     */
    private float[] readoutColumns(List<EntityReadout.Row> rows) {
        float location = textWidth(Messages.getString("Lobby.requisition.readout.widestLocation"), "body");
        List<Float> widths = new ArrayList<>();
        widths.add(location);
        for (EntityReadout.Row row : rows) {
            if (!RequisitionReadout.isTable(row)) {
                continue;
            }
            int count = RequisitionReadout.filledCells(row.cells());
            String type = row.kind() == EntityReadout.Row.Kind.TABLE_HEADER ? "small" : "body";
            for (int i = 0; i < count - 1; i++) {
                float width = textWidth(row.cells().get(i), type);
                if (i < widths.size()) {
                    widths.set(i, Math.max(widths.get(i), width));
                } else {
                    widths.add(width);
                }
            }
        }
        float[] columns = new float[widths.size()];
        for (int i = 0; i < columns.length; i++) {
            float cap = (i == 0) ? Math.max(location, size("dialog.sm") * 0.45f) : size("dialog.sm") / 4;
            columns[i] = (float) Math.ceil(Math.min(widths.get(i), cap));
        }
        return columns;
    }

    private float textWidth(String text, String type) {
        return kit.typeLabel(text, type, "text.primary").getPrefWidth();
    }

    private Label wrapped(String text, String type, String role) {
        Label label = kit.typeLabel(text, type, role);
        label.setWrap(true);
        return label;
    }
}
