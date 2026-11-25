/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiTheme;
import megamek.logging.MMLogger;

/**
 * The dock's More as a panel that slides out of the dock's right side, in the dock's own look: its frame, an upper-case
 * title with a close button, the setup choices the dock's option row has no room for as the same setup tiles, and the
 * usable commands as dock buttons in a grid. The HUD sizes the panel to the columns and rows they need, so it does not scroll unless the window has no room for them.
 * Unavailable commands are left out. Choosing a command runs it and closes the panel, as the More popover did; a setup
 * tile flips or opens its choices and leaves the panel open, framed in amber once it differs from the start. The
 * HUD places it beside the dock, at least the dock's height and taller where its columns need more rows, or above
 * the dock where the window leaves no room for a column beside it; above the dock it slides up out of the dock's top.
 */
final class GpuMoreDrawer {
    private static final MMLogger LOGGER = MMLogger.create(GpuMoreDrawer.class);
    /** A grid button's width, the gap between buttons and their height (the dock's plain .b and GAP). */
    private static final float CELL = 150;
    /** The column width while setup tiles show: room for captions such as "ECM Suite (Guardian)". */
    private static final float SETUP_CELL = 196;
    /** The gap between the setup tiles and the commands below them. */
    private static final float SECTION = 12;
    private static final float GAP = 6;
    private static final float BUTTON = 36;
    /** The panel's padding inside its frame: 14 at each side, 11 above and 10 below (#dock padding 9 12 8). */
    private static final float PAD_X = 14;
    private static final float PAD_TOP = 11;
    private static final float PAD_BOTTOM = 10;
    /** The slide's length in seconds. */
    private static final float SLIDE = 0.18f;

    private final UiKit ui;
    private final BiConsumer<String, Actor> chooseSetup;
    private final Slide slide = new Slide();
    private final Table panel;
    private final Label title;
    private final Table head = new Table();
    private final UiMenuList grid;
    private final ScrollPane scroll;
    /** The commands the grid shows, by id, label and shortcut: a frame that changes none of them keeps the buttons. */
    private List<String> shownKeys = List.of();
    private List<BoardScene.Command> commands = List.of();
    private List<GpuDeploySetup.Tile> setup = List.of();
    private final Map<String, GpuSetupTile> setupTiles = new HashMap<>();
    private int columns;
    private boolean open;
    /** Whether the HUD placed the panel above the dock rather than beside it. */
    private boolean above;

    /** {@code chooseSetup} takes a setup tile's click: its tile id and the tile, to open its choices above. */
    GpuMoreDrawer(UiKit ui, BiConsumer<String, Actor> chooseSetup) {
        this.ui = ui;
        this.chooseSetup = chooseSetup;
        grid = new UiMenuList(ui);
        grid.setName("dock-more-commands");
        slide.setName("dock-more-drawer");
        // The dock's frame and padding (#dock padding 9 12 8, inside the rails and side borders).
        panel = ui.panel();
        panel.pad(PAD_TOP, PAD_X, PAD_BOTTOM, PAD_X);
        title = ui.label("", "hud-title", 13, Color.WHITE);
        title.setEllipsis(true);
        UiButton close = ui.closeButton(this::close);
        close.setName("dock-more-close");
        head.pad(0, 2, 0, 2);
        head.add(title).left().minWidth(0);
        head.add().expandX();
        head.add(close).padLeft(8);
        panel.add(head).growX().minHeight(26).padBottom(GAP).row();
        grid.top().left();
        scroll = ui.scrollList(grid);
        scroll.setName("dock-more-scroll");
        panel.add(scroll).grow().minHeight(0);
        slide.addActor(panel);
        slide.setVisible(false);
    }

    Actor actor() {
        return slide;
    }

    boolean isOpen() {
        return open;
    }

    /**
     * The columns the setup tiles and usable commands need at {@code height}: the fewest whose rows it holds. One
     * while it is hidden; the closing slide keeps the open panel's shape.
     */
    int columnsAt(float height) {
        if (!slide.isVisible()) {
            return 1;
        }
        int most = Math.max(1, Math.max(setup.size(), commands.size()));
        for (int columns = 1; columns < most; columns++) {
            if (height(columns) <= height + 0.5f) {
                return columns;
            }
        }
        return most;
    }

    /** The columns {@code width} holds with plain command cells, zero when it is too narrow for one. */
    static int columnsIn(float width) {
        return columnsIn(width, CELL);
    }

    /** The panel's width with {@code columns} columns of plain command cells. */
    static float width(int columns) {
        return width(columns, CELL);
    }

    /** The columns {@code width} holds with the cells the panel shows now, wider with setup tiles. */
    int columnsFor(float width) {
        return columnsIn(width, cell());
    }

    /** The panel's width with {@code columns} of the cells it shows now. */
    float widthFor(int columns) {
        return width(columns, cell());
    }

    private static int columnsIn(float width, float cell) {
        return Math.max(0, (int) Math.floor((width - 2 * PAD_X + GAP + 0.5f) / (cell + GAP)));
    }

    private static float width(int columns, float cell) {
        return columns * cell + (columns - 1) * GAP + 2 * PAD_X;
    }

    private float cell() {
        return setup.isEmpty() ? CELL : SETUP_CELL;
    }

    /** The panel's height with the setup tiles' rows over the usable commands' rows in {@code columns} columns. */
    float height(int columns) {
        int setupRows = (setup.size() + columns - 1) / columns;
        int commandRows = (commands.size() + columns - 1) / columns;
        float rows = setupRows * (GpuSetupTile.HEIGHT + GAP) + commandRows * (BUTTON + GAP) - GAP
              + (setupRows > 0 && commandRows > 0 ? SECTION - GAP : 0);
        return chrome() + Math.max(BUTTON, rows);
    }

    /** The height of the panel's padding, head and the gap below the head. */
    private float chrome() {
        return PAD_TOP + PAD_BOTTOM + head.getPrefHeight() + GAP;
    }

    /** Set by the HUD's layout: the panel lies above the dock and slides up, rather than beside it sliding right. */
    void above(boolean value) {
        if (above != value) {
            above = value;
            slide.invalidate();
        }
    }

    /** Opens the panel with {@code more}, or closes it while it is open. */
    void toggle(GpuCommandDock.More more) {
        if (open) {
            close();
            return;
        }
        open = true;
        slide.reveal = 0;
        slide.setVisible(true);
        show(more);
        grid.highlight(null);
        scroll.setScrollY(0);
        if (slide.getStage() != null) {
            slide.getStage().setKeyboardFocus(grid);
        }
        LOGGER.info("More panel opened {} the dock: {}x{} units, {} usable commands in {} columns",
              above ? "above" : "beside", Math.round(slide.getWidth()), Math.round(slide.getHeight()),
              commands.size() + setup.size(), columns);
    }

    /** Closes the panel; true when it was open. */
    boolean close() {
        if (!open) {
            return false;
        }
        open = false;
        Stage stage = slide.getStage();
        if (stage != null) {
            if (stage.getKeyboardFocus() != null && stage.getKeyboardFocus().isDescendantOf(slide)) {
                stage.setKeyboardFocus(null);
            }
            if (stage.getScrollFocus() != null && stage.getScrollFocus().isDescendantOf(slide)) {
                stage.setScrollFocus(null);
            }
        }
        return true;
    }

    /** One frame's More while the panel is open: null closes it, changed commands rebuild the grid. */
    void update(GpuCommandDock.More more) {
        if (!open) {
            return;
        }
        if (more == null) {
            close();
        } else {
            show(more);
        }
    }

    /** The commands of {@code more} the panel shows: the enabled ones without children. */
    static List<BoardScene.Command> usable(GpuCommandDock.More more) {
        List<BoardScene.Command> usable = new ArrayList<>();
        for (List<BoardScene.Command> group : List.of(more.items(), more.commands())) {
            for (BoardScene.Command command : group) {
                if (command.enabled() && command.children().isEmpty()) {
                    usable.add(command);
                }
            }
        }
        return usable;
    }

    private void show(GpuCommandDock.More more) {
        title.setText(UiTheme.upper(more.title()));
        List<BoardScene.Command> usable = usable(more);
        List<String> keys = new ArrayList<>();
        for (GpuDeploySetup.Tile tile : more.setup()) {
            keys.add("setup:" + tile.id());
        }
        for (BoardScene.Command command : usable) {
            keys.add(command.id() + '\n' + command.label() + '\n' + command.shortcut());
        }
        commands = usable;
        setup = more.setup();
        if (!keys.equals(shownKeys)) {
            shownKeys = keys;
            rebuild();
        }
        // Each frame's setup: a flipped switch or a new count shows at once, the panel staying open.
        for (GpuDeploySetup.Tile tile : setup) {
            GpuSetupTile shown = setupTiles.get(tile.id());
            if (shown != null) {
                shown.show(tile);
            }
        }
    }

    /** The setup tiles' rows, then the command buttons' rows, in as many columns as the panel's width holds. */
    private void rebuild() {
        String highlighted = grid.highlighted() == null ? null : grid.highlighted().getName();
        grid.clearChildren();
        setupTiles.clear();
        columns = columns(slide.getWidth());
        for (int index = 0; index < setup.size(); index++) {
            String id = setup.get(index).id();
            GpuSetupTile tile = new GpuSetupTile(ui, "dock-more-setup-" + id, face -> chooseSetup(id, face));
            setupTiles.put(id, tile);
            tile.show(setup.get(index));
            place(tile.button(), index, GpuSetupTile.HEIGHT, 0);
        }
        fillRow(setup.size());
        for (int index = 0; index < commands.size(); index++) {
            BoardScene.Command command = commands.get(index);
            String id = command.id();
            UiButton button = ui.button("hud", null, command.label(), null);
            button.setName("dock-more-" + command.id());
            button.getLabel().setEllipsis(true);
            button.getCell(button.getLabel()).minWidth(0);
            if (command.shortcut() != null && !command.shortcut().isEmpty()) {
                ui.tip(button).getActor().setText(command.shortcut());
            }
            onChange(button, () -> choose(id));
            place(button, index, BUTTON, setup.isEmpty() ? 0 : SECTION);
        }
        fillRow(commands.size());
        if (highlighted != null) {
            grid.highlight(grid.findActor(highlighted));
        }
    }

    /** A section's {@code index}th button, a new row at each column count; {@code top} above the section's first row. */
    private void place(UiButton button, int index, float height, float top) {
        boolean first = index % columns == 0;
        if (first && grid.getCells().notEmpty()) {
            grid.row();
        }
        grid.item(button).growX().uniformX().minWidth(0).height(height).padLeft(first ? 0 : GAP)
              .padTop(index < columns ? top : GAP);
    }

    /** A section's short last row keeps its buttons at the grid's column width. */
    private void fillRow(int count) {
        for (int index = count % columns; index > 0 && index < columns; index++) {
            grid.add().growX().uniformX().padLeft(GAP);
        }
    }

    /** A setup tile's click, while the panel is open: the dock flips it or opens its choices above it. */
    private void chooseSetup(String id, Actor face) {
        if (open) {
            chooseSetup.accept(id, face);
        }
    }

    /** The grid's columns at {@code width}, at least one; half a unit spare absorbs the layout's rounding. */
    private int columns(float width) {
        return Math.max(1, columnsFor(width));
    }

    /** Buttons may outlive a unit or turn; resolve their action from the latest snapshot when invoked. */
    private void choose(String id) {
        if (!open) {
            return;
        }
        BoardScene.Command command = commands.stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
        if (command != null && command.enabled()) {
            close();
            command.action().run();
        }
    }

    /**
     * The panel's clip: the panel slides in from its left edge beside the dock, or from its bottom edge above it, and
     * out again before the panel hides. A new width with another column count rebuilds the grid.
     */
    private final class Slide extends Table {
        private float reveal;

        Slide() {
            setClip(true);
            // A Table clips only while it draws with its own transform.
            setTransform(true);
        }

        @Override
        public void act(float delta) {
            super.act(delta);
            float target = open ? 1 : 0;
            if (reveal != target) {
                float step = delta / SLIDE;
                reveal = open ? Math.min(1, reveal + step) : Math.max(0, reveal - step);
                invalidate();
            }
            if (!open && reveal == 0) {
                setVisible(false);
            }
        }

        @Override
        public void layout() {
            super.layout();
            if (open && columns(getWidth()) != columns) {
                rebuild();
            }
            float hidden = 1 - Interpolation.pow2Out.apply(reveal);
            if (above) {
                panel.setBounds(0, -hidden * getHeight(), getWidth(), getHeight());
            } else {
                panel.setBounds(-hidden * getWidth(), 0, getWidth(), getHeight());
            }
            if (open && panel.needsLayout()) {
                // Reflow changes the viewport and scroll limits. Restore the selection only after they settle.
                panel.validate();
                grid.highlight(grid.highlighted());
                scroll.updateVisualScroll();
            }
        }

    }
}
