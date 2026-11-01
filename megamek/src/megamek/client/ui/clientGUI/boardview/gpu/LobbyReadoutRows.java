/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;

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
        Columns columns = readoutColumns(rows);
        UiReveal content = new UiReveal(motion);
        boolean first = true;
        for (List<EntityReadout.Row> rowsOfBlock : RequisitionReadout.blocks(rows)) {
            Table block = new Table();
            block.top().left();
            List<EntityReadout.Row> run = new ArrayList<>();
            Table lines = null;
            EntityReadout.Row previous = null;
            for (EntityReadout.Row row : rowsOfBlock) {
                if (!RequisitionReadout.isTable(row) && !run.isEmpty()) {
                    block.add(table(run, columns)).growX().minWidth(0).left().row();
                    run.clear();
                }
                if (!isLine(row) && lines != null) {
                    block.add(lines).growX().minWidth(0).left().row();
                    lines = null;
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
                            if (lines == null) {
                                lines = new Table();
                                lines.left().top();
                            }
                            // One table for a run of lines, so their values start at the same place
                            lines.add(kit.typeLabel(label, "body", "text.secondary")).top().left()
                                  .padRight(space("md")).padTop(space("xxs"));
                            lines.add(wrapped(value, "body", "text.primary")).growX().minWidth(0).top()
                                  .padTop(space("xxs")).row();
                        }
                    }
                    case TABLE_HEADER, TABLE_ROW -> run.add(row);
                    case TEXT -> block.add(wrapped(text, "body", "text.primary")).growX().minWidth(0)
                          .padTop(space("xxs")).row();
                }
                first = false;
                previous = row;
            }
            if (!run.isEmpty()) {
                block.add(table(run, columns)).growX().minWidth(0).left().row();
            }
            if (lines != null) {
                block.add(lines).growX().minWidth(0).left().row();
            }
            content.band(block);
        }
        return content;
    }

    /** Whether {@code row} is a labelled line, which lines up with the lines around it. */
    private static boolean isLine(EntityReadout.Row row) {
        return row.kind() == EntityReadout.Row.Kind.LABELED && !RequisitionReadout.isSection(row);
    }

    /** A readout heading: a title over a table or a text section, in overline. */
    private Label heading(String text) {
        Label heading = kit.roleTypeLabel("overline", text, "text.muted");
        heading.setWrap(true);
        return heading;
    }

    /** The widths of a readout's table columns: the leading columns by place, and the last column of every table. */
    private record Columns(float[] leading, float last) { }

    /**
     * A run of table rows as one table, as wide as the widest of the readout's tables: its leading cells in the
     * readout's leading columns, then its last column where every table's last column stands, so the last columns
     * of tables of different widths (a TRO's mass and tonnage, or a calculation's results) line up down the readout.
     * A table of a single column runs to the edge.
     */
    private Table table(List<EntityReadout.Row> run, Columns columns) {
        Table table = new Table();
        table.top().left();
        int count = runColumns(run);
        for (EntityReadout.Row row : run) {
            if (RequisitionReadout.filledCells(row.cells()) == 0) {
                continue;
            }
            boolean header = row.kind() == EntityReadout.Row.Kind.TABLE_HEADER;
            String type = header ? "small" : "body";
            String role = header ? "text.muted" : "text.primary";
            if (count <= 1) {
                table.add(wrapped(cell(row, 0), type, role)).growX().minWidth(0).left().top().padTop(space("xxs"));
                table.row();
                continue;
            }
            for (int i = 0; i < count - 1; i++) {
                table.add(wrapped(cell(row, i), type, role)).width(columns.leading()[i]).left().top()
                      .padRight(space("md")).padTop(space("xxs"));
            }
            for (int i = count - 1; i < columns.leading().length; i++) {
                table.add().width(columns.leading()[i]).padRight(space("md"));
            }
            table.add(wrapped(cell(row, count - 1), type, role)).width(columns.last()).left().top()
                  .padTop(space("xxs"));
            table.add().growX();
            table.row();
        }
        return table;
    }

    private static String cell(EntityReadout.Row row, int index) {
        return index < row.cells().size() ? row.cells().get(index) : "";
    }

    /** How many columns a run of table rows has: up to the last that any of its rows fills. */
    private static int runColumns(List<EntityReadout.Row> run) {
        int count = 0;
        for (EntityReadout.Row row : run) {
            count = Math.max(count, RequisitionReadout.filledCells(row.cells()));
        }
        return count;
    }

    /** The readout's runs of table rows, as {@link #rows} lays them out. */
    private static List<List<EntityReadout.Row>> runs(List<EntityReadout.Row> rows) {
        List<List<EntityReadout.Row>> runs = new ArrayList<>();
        for (List<EntityReadout.Row> block : RequisitionReadout.blocks(rows)) {
            List<EntityReadout.Row> run = new ArrayList<>();
            for (EntityReadout.Row row : block) {
                if (RequisitionReadout.isTable(row)) {
                    run.add(row);
                } else if (!run.isEmpty()) {
                    runs.add(run);
                    run = new ArrayList<>();
                }
            }
            if (!run.isEmpty()) {
                runs.add(run);
            }
        }
        return runs;
    }

    /**
     * The widths of the readout tables' columns, shared by every table. Each leading column is as wide as its widest
     * cell, the first at least as wide as "Center Torso (rear)" so a location never wraps; the last column of every
     * table shares one width, its widest cell's. None is wider than half a small dialog, past which a cell wraps.
     */
    private Columns readoutColumns(List<EntityReadout.Row> rows) {
        float location = textWidth(Messages.getString("Lobby.requisition.readout.widestLocation"), "body");
        List<Float> widths = new ArrayList<>();
        widths.add(location);
        float last = 0;
        int widest = 0;
        for (List<EntityReadout.Row> run : runs(rows)) {
            int count = runColumns(run);
            if (count <= 1) {
                continue;
            }
            widest = Math.max(widest, count);
            for (EntityReadout.Row row : run) {
                String type = row.kind() == EntityReadout.Row.Kind.TABLE_HEADER ? "small" : "body";
                for (int i = 0; i < count - 1; i++) {
                    float width = textWidth(cell(row, i), type);
                    if (i < widths.size()) {
                        widths.set(i, Math.max(widths.get(i), width));
                    } else {
                        widths.add(width);
                    }
                }
                last = Math.max(last, textWidth(cell(row, count - 1), type));
            }
        }
        float cap = size("dialog.sm") / 2;
        float[] leading = new float[Math.max(0, widest - 1)];
        for (int i = 0; i < leading.length; i++) {
            float width = i < widths.size() ? widths.get(i) : 0;
            leading[i] = (float) Math.ceil(Math.min(width, (i == 0) ? Math.max(location, cap) : cap));
        }
        return new Columns(leading, (float) Math.ceil(Math.min(last, cap)));
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
