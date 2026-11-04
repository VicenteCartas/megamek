/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.DragAndDrop;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.UIUtils;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Scaling;
import jakarta.annotation.Nullable;
import megamek.client.lobby.LobbyForces;
import megamek.client.lobby.LobbyForces.Formation;
import megamek.client.lobby.LobbyForces.Owner;
import megamek.client.lobby.LobbyForces.Row;
import megamek.client.lobby.LobbyMenuEntry;
import megamek.client.lobby.LobbyUnit;
import megamek.client.lobby.LobbyUnitMenu;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.gpu.LobbyForcesController.Target;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiKit.Tone;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiPopover;
import megamek.client.ui.gdx.UiSegmented;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiTheme;

/**
 * The lobby's centre column at rest: the forces. Its header holds Random army, Add unit and a ⋮ menu of the force
 * commands; a view row switches between the units by owner and the formations (MegaMek's forces) as a tree, makes the
 * rows compact, collapses or expands every formation, and, for the units, picks their order from the Swing unit table's
 * orders. Each unit's card shows its sprite, name and problems, tonnage, weight class, role and start, the pilot and
 * skills, up to three tags and its Battle Value; a compact row puts the same on one line. A blind drop leaves an enemy
 * unit only its rough description, as the Swing unit table does.
 *
 * <p>A click selects a card, Ctrl adds or removes one and Shift a run of them; a right click or a card's ⋮ opens the
 * unit menu for the selection, which the window builds ({@link megamek.client.lobby.LobbyUnitMenu}); a submenu opens in
 * the menu's place with a Back row. A wide card of the local player's unit also shows Configure, C3 (for a C3 unit) and
 * Delete beside its ⋮, and a double click configures it. In the formations, units and formations are dragged onto a
 * formation, onto a unit in one, or onto the strip that shows at the end to leave their formation, as in the Swing force
 * tree; the card that would take the drop rises under an accent edge.</p>
 */
final class LobbyForcesPanel {
    /** The views of the view row's switch. */
    static final int UNITS = 0;
    static final int FORMATIONS = 1;

    /** The forces column's commands that the window runs, most through a Swing dialog. */
    enum Command {
        ADD_UNIT, RANDOM_ARMY, LOAD_LIST, SAVE_LIST, PRINT_LIST, NAMES, SKILLS, C3, NEW_FORMATION
    }

    /** What the panel's controls do; each takes effect when the window next reads the lobby. */
    interface Actions {
        void command(Command command);

        void sort(int index);

        void configure(int unitId);

        /** Opens the C3 network manager on this unit. */
        void c3(int unitId);

        void drop(List<Integer> unitIds, List<Integer> formationIds, Target target);

        /** Runs the unit menu's command {@code name}, such as {@link LobbyUnitMenu#LMP_VIEW}, on one unit. */
        void unitCommand(int unitId, String name);

        /** Deletes the selection through the same command and confirmation as the unit menu. */
        void delete(List<Integer> unitIds, List<Integer> formationIds);

        /**
         * Asks for the unit menu of these units and formations, which comes back through {@link #showMenu}, without
         * the items whose commands are in {@code omit}.
         */
        void menu(List<Integer> unitIds, List<Integer> formationIds, boolean formationsView, Set<String> omit);

        /** Chooses the item at {@code path} of the unit menu that last came back. */
        void choose(List<Integer> path);

    }

    /** What a drag carries: units or formations. */
    private record Drag(List<Integer> unitIds, List<Integer> formationIds) { }

    /** Where the next unit menu opens, and what it is about. */
    private record MenuAnchor(float x, float y, @Nullable Actor face, String title, String detail) { }

    private final UiKit kit;
    private final Actions actions;
    private final IntFunction<Drawable> sprites;
    private final Table panel;
    private final Label unitCount;
    private final UiSegmented views;
    private final UiButton compactToggle;

    private final UiButton collapseAll;
    private final UiButton expandAll;
    private final Table sortRow = new Table();
    private final Table list = new Table();
    private final ScrollPane scroll;
    private final Table dropStrip;
    private final DragAndDrop dragAndDrop = new DragAndDrop();
    /** The unit menu and the forces column's ⋮ menu; a submenu opens in its place, not beside it. */
    private final UiPopover menu;
    /** The open unit menu's entries and header, and the submenu it shows as indices into the entries. */
    private List<LobbyMenuEntry> menuEntries = List.of();
    private String menuTitle = "";
    private String menuDetail = "";
    private final List<Integer> menuPath = new ArrayList<>();
    private UiSelect sortSelect;
    private List<String> sortNames = List.of();

    private LobbyForces shown = LobbyForces.EMPTY;
    private boolean compact;

    private final Set<Integer> collapsed = new HashSet<>();
    private final Set<Integer> selectedUnits = new LinkedHashSet<>();
    private final Set<Integer> selectedFormations = new LinkedHashSet<>();
    /** The shown cards and formation rows, in list order, with the drawables they rest on. */
    private final Map<Integer, Table> unitCards = new HashMap<>();
    private final Map<Integer, Table> formationRows = new HashMap<>();
    private final Map<Integer, Actor> unitMenuButtons = new HashMap<>();
    /** The unit menu commands each wide card offers as icons, which its ⋮ menu leaves out. */
    private final Map<Integer, Set<String>> cardCommands = new HashMap<>();
    private final List<Object[]> order = new ArrayList<>();
    /** The card a Shift click runs from: a unit id, or a formation id as its negation less one. */
    private Integer anchor;
    private MenuAnchor pendingMenu;

    LobbyForcesPanel(UiKit kit, Actions actions, IntFunction<Drawable> sprites) {
        this.kit = kit;
        this.actions = actions;
        this.sprites = sprites;
        menu = new UiPopover(kit, () -> { }, UiTheme.TOKEN);
        menu.setName("lobby-unit-menu");

        panel = kit.panel(true);
        UiButton randomArmy = kit.button("button-secondary" + UiTheme.COMPACT, null,
              Messages.getString("Lobby.forces.randomArmy"), null);
        kit.tip(randomArmy, true).getActor().setText(Messages.getString("Lobby.forces.randomArmyTip"));
        UiKit.onChange(randomArmy, () -> actions.command(Command.RANDOM_ARMY));
        UiButton addUnit = kit.button("button-primary" + UiTheme.COMPACT, "add",
              Messages.getString("Lobby.forces.addUnit"), null);
        kit.tip(addUnit, true).getActor().setText(Messages.getString("Lobby.forces.addUnitTip"));
        UiKit.onChange(addUnit, () -> actions.command(Command.ADD_UNIT));
        UiButton more = kit.button("button-icon" + UiTheme.COMPACT, "more-vert", null, null);
        kit.tip(more, true).getActor().setText(Messages.getString("Lobby.forces.menu"));
        UiKit.onChange(more, () -> openForcesMenu(more));
        Table header = kit.header(Messages.getString("Lobby.forcesTitle"), "", true, randomArmy, addUnit, more);
        unitCount = header.findActor(UiKit.HEADER_COUNT);
        panel.add(header).growX().row();

        views = kit.tokenSegmented(true, Messages.getString("Lobby.forces.units"),
              Messages.getString("Lobby.forces.formations"));
        UiKit.onChange(views, this::viewChanged);
        compactToggle = kit.button("button-toggle" + UiTheme.COMPACT, "list", null, null);
        kit.tip(compactToggle, true).getActor().setText(Messages.getString("Lobby.forces.compact"));
        // A UiButton never toggles itself, so the click flips the panel's own state and shows it on the button
        UiKit.onChange(compactToggle, () -> compact(!compact));

        collapseAll = kit.button("button-icon" + UiTheme.COMPACT, "unfold-less", null, null);
        kit.tip(collapseAll, true).getActor().setText(Messages.getString("Lobby.forces.collapse"));
        UiKit.onChange(collapseAll, () -> {
            shown.formations().forEach(formation -> collapsed.add(formation.id()));
            fill();
        });
        expandAll = kit.button("button-icon" + UiTheme.COMPACT, "unfold-more", null, null);
        kit.tip(expandAll, true).getActor().setText(Messages.getString("Lobby.forces.expand"));
        UiKit.onChange(expandAll, () -> {
            collapsed.clear();
            fill();
        });
        Table viewRow = new Table();
        viewRow.left();
        viewRow.add(views).left();
        viewRow.add(compactToggle).padLeft(space("sm"));

        viewRow.add(collapseAll).padLeft(space("sm"));
        viewRow.add(expandAll).padLeft(space("xs"));
        viewRow.add().growX();
        viewRow.add(sortRow).right().minWidth(0);
        panel.add(viewRow).growX().pad(0, space("md"), space("md"), space("md")).row();

        list.top();
        scroll = kit.scrollList(list, true, false);
        panel.add(scroll).grow().row();

        dropStrip = new Table();
        dropStrip.setBackground(kit.skin.getDrawable("token-row-slot"));
        dropStrip.pad(space("sm"), space("md"), space("sm"), space("md"));
        dropStrip.add(kit.typeLabel(Messages.getString("Lobby.forces.dropTop"), "small", "text.secondary"));
        dropStrip.setVisible(false);
        panel.add(dropStrip).growX().padTop(space("xs"));
        panel.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (event.isHandled() || keycode != Input.Keys.FORWARD_DEL || event.getTarget() instanceof TextField
                      || (selectedUnits.isEmpty() && selectedFormations.isEmpty())) {
                    return false;
                }
                actions.delete(List.copyOf(selectedUnits), List.copyOf(selectedFormations));
                return true;
            }
        });
        refreshViewTools();
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static float size(String name) {
        return DesignTokens.shipped().size().get(name);
    }

    Table panel() {
        return panel;
    }

    /** Adds the unit menu to {@code layer}, above the columns; once, before the panel shows a snapshot. */
    void attach(Group layer) {
        layer.addActor(menu);
    }

    /** One Escape step in the unit menu: back out of a submenu, or close it; true when one was open. */
    boolean back() {
        if (menu.isVisible() && !menuPath.isEmpty()) {
            menuPath.removeLast();
            fillMenu();
            return true;
        }
        return menu.back();
    }

    /** Shows compact rows, or the full cards; the choice holds through later snapshots. */
    void compact(boolean on) {
        compact = on;
        compactToggle.setChecked(on);
        fill();
    }

    boolean compact() {
        return compact;
    }

    /** The compact rows toggle, for the developer switch's clicks. */
    UiButton compactToggle() {
        return compactToggle;
    }


    /** For the developer switch: shows the formations. */
    void showFormations() {
        views.select(FORMATIONS);
        viewChanged();
    }

    /** Shows {@code forces}, keeping the selection of what is still there. */
    void show(LobbyForces forces) {
        shown = forces;
        selectedUnits.removeIf(id -> forces.unit(id) == null);
        selectedFormations.removeIf(id -> forces.formation(id) == null);
        if (unitCount != null) {
            unitCount.setText(String.valueOf(forces.units().size()));
        }
        fillSorts();
        fill();
    }

    /** Draws the cards again, as after their sprites arrive. */
    void redraw() {
        fill();
    }

    /**
     * Selects unit {@code id} alone and scrolls its card into view, showing the units rather than the formations, as
     * when a warning in the top bar points at it; nothing when it is not shown.
     */
    void reveal(int id) {
        if (shown.unit(id) == null) {
            return;
        }
        if (formationsView()) {
            views.select(UNITS);
            viewChanged();
        }
        Table card = unitCards.get(id);
        if (card == null) {
            return;
        }
        select(true, id, false, false);
        list.layout();
        scroll.layout();
        Vector2 at = card.localToAscendantCoordinates(list, new Vector2());
        scroll.scrollTo(at.x, at.y, card.getWidth(), card.getHeight(), false, true);
    }

    private boolean formationsView() {
        return views.getSelectedIndex() == FORMATIONS;
    }

    private void viewChanged() {
        selectedFormations.clear();
        refreshViewTools();
        fill();
    }

    private void refreshViewTools() {
        boolean formations = formationsView();
        collapseAll.setVisible(formations);
        expandAll.setVisible(formations);
        sortRow.setVisible(!formations && !sortNames.isEmpty());
    }

    private void fillSorts() {
        if (!shown.sorts().equals(sortNames)) {
            sortNames = shown.sorts();
            sortRow.clearChildren();
            sortSelect = null;
            if (!sortNames.isEmpty()) {
                sortSelect = kit.tokenSelectInline(sortNames.toArray(String[]::new));
                UiSelect select = sortSelect;
                UiKit.onChange(select, () -> actions.sort(select.getSelectedIndex()));
                sortRow.add(kit.typeLabel(Messages.getString("Lobby.forces.sort"), "small", "text.secondary"))
                      .padRight(space("xs"));
                sortRow.add(sortSelect).minWidth(0);
            }
        }
        if (sortSelect != null && shown.sort() >= 0 && shown.sort() < sortNames.size()) {
            sortSelect.select(shown.sort());
        }
        refreshViewTools();
    }

    // ---- The list ----

    private void fill() {
        list.clearChildren();
        dragAndDrop.clear();
        unitCards.clear();
        formationRows.clear();
        unitMenuButtons.clear();
        cardCommands.clear();
        order.clear();
        if (shown.units().isEmpty() && shown.formations().isEmpty()) {
            list.add(emptyState()).growX().padTop(space("xxxl")).padLeft(space("xl")).padRight(space("xl"));
            return;
        }
        if (formationsView()) {
            fillFormations();
        } else {
            fillUnits();
        }
        paintSelection();
    }

    private Table emptyState() {
        Table empty = new Table();
        empty.add(kit.roleTypeLabel("title", Messages.getString("Lobby.forces.empty.title"), "text.secondary"))
              .row();
        Label body = kit.typeLabel(Messages.getString("Lobby.forces.empty.body"), "body", "text.muted");
        body.setWrap(true);
        body.setAlignment(Align.center);
        empty.add(body).growX().padTop(space("sm")).row();
        Table buttons = new Table();
        UiButton army = kit.button("button-secondary" + UiTheme.COMPACT, null,
              Messages.getString("Lobby.forces.randomArmy"), null);
        UiKit.onChange(army, () -> actions.command(Command.RANDOM_ARMY));
        UiButton add = kit.button("button-primary" + UiTheme.COMPACT, "add", Messages.getString("Lobby.forces.addUnit"),
              null);
        UiKit.onChange(add, () -> actions.command(Command.ADD_UNIT));
        buttons.add(army).padRight(space("sm"));
        buttons.add(add);
        empty.add(buttons).padTop(space("lg"));
        return empty;
    }

    private void fillUnits() {
        boolean first = true;
        for (Owner owner : shown.owners()) {
            list.add(ownerHeader(owner)).growX().padTop(first ? 0 : space("lg")).row();
            first = false;
            for (int id : owner.unitIds()) {
                LobbyUnit unit = shown.unit(id);
                if (unit != null) {
                    Table card = card(unit, owner.self() ? "accent.primary" : owner.enemy() ? "status.danger"
                          : "border.control");
                    float indent = unit.depth() * space("lg");
                    list.add(card).growX().padTop(space(compact ? "xxs" : "sm")).padLeft(indent).row();
                }
            }
        }
    }

    private void fillFormations() {
        int hiddenBelow = Integer.MAX_VALUE;
        for (Row row : shown.tree()) {
            if (row.depth() > hiddenBelow) {
                continue;
            }
            hiddenBelow = Integer.MAX_VALUE;
            float indent = row.depth() * space("lg");
            if (row.formation()) {
                Formation formation = shown.formation(row.formationId());
                if (formation == null) {
                    continue;
                }
                list.add(formationRow(formation)).growX().padTop(space(row.depth() == 0 ? "md" : "xs"))
                      .padLeft(indent).row();
                if (collapsed.contains(formation.id())) {
                    hiddenBelow = row.depth();
                }
            } else {
                LobbyUnit unit = shown.unit(row.unitId());
                if (unit == null) {
                    continue;
                }
                Owner owner = shown.owners().stream().filter(o -> o.id() == unit.ownerId()).findFirst().orElse(null);
                String bar = owner == null ? "border.control" : owner.self() ? "accent.primary"
                      : owner.enemy() ? "status.danger" : "border.control";
                list.add(card(unit, bar)).growX().padTop(space(compact ? "xxs" : "xs")).padLeft(indent).row();
            }
        }
        // Last, so the cards and formation rows take a drop before the list around them does
        dragAndDrop.addTarget(dropTarget(dropStrip, Target.NONE));
        dragAndDrop.addTarget(dropTarget(scroll, Target.NONE));
    }

    /** An owner's header: their colour bar, name, and totals unless a blind drop hides them. */
    private Table ownerHeader(Owner owner) {
        String bar = owner.self() ? "accent.primary" : owner.enemy() ? "status.danger" : "border.control";
        Table header = new Table();
        header.add(kit.solid(bar)).width(size("selection.width")).growY().padRight(space("sm"));
        Table text = new Table();
        text.left().defaults().left();
        Table nameRow = new Table();
        Label name = kit.roleTypeLabel("name", owner.name(), owner.self() ? "accent.primary" : "text.primary");
        name.setEllipsis(true);
        nameRow.add(name).minWidth(0).growX();
        if (owner.self()) {
            nameRow.add(kit.chip(Messages.getString("Lobby.you"), Tone.NORMAL, true)).padLeft(space("xs"));
        }
        text.add(nameRow).growX().minWidth(0).row();
        String totals = owner.hidden() ? Messages.getString("Lobby.hidden")
              : Messages.getString("Lobby.totals", owner.bv(), owner.tons(), owner.unitIds().size());
        Label line = kit.typeLabel(totals, "small", "text.secondary");
        line.setEllipsis(true);
        text.add(line).growX().minWidth(0).padTop(space("xxs"));
        header.add(text).growX().minWidth(0);
        return header;
    }

    /** A formation's row: its fold chevron, name, owner at the top level, totals, and its ⋮. */
    private Table formationRow(Formation formation) {
        Table row = kit.card();
        row.setName("lobby-formation-" + formation.id());
        boolean folded = collapsed.contains(formation.id());
        UiButton fold = kit.button("button-icon" + UiTheme.COMPACT, folded ? "chevron-right" : "chevron-down", null,
              null);
        kit.tip(fold, true).getActor().setText(Messages.getString(folded ? "Lobby.forces.expand"
              : "Lobby.forces.collapse"));
        UiKit.onChange(fold, () -> {
            if (!collapsed.remove(formation.id())) {
                collapsed.add(formation.id());
            }
            fill();
        });
        row.add(fold).size(size("control.sm")).padRight(space("xs"));
        String side = formation.enemy() ? "status.danger" : formation.editable() ? "accent.primary" : "text.secondary";
        row.add(kit.icon("layers", size("icon.md"), UiTheme.tokenColor(side))).size(size("icon.md"))
              .padRight(space("sm"));
        Table text = new Table();
        text.left().defaults().left();
        Table nameRow = new Table();
        Label name = kit.typeLabel(formation.name(), "body.strong", "text.primary");
        name.setEllipsis(true);
        nameRow.add(name).minWidth(0).growX();
        if (formation.depth() == 0 && !formation.ownerName().isEmpty()) {
            Label owner = kit.typeLabel(formation.ownerName(), "small", side);
            owner.setEllipsis(true);
            nameRow.add(owner).minWidth(0).padLeft(space("sm"));
        }
        text.add(nameRow).growX().minWidth(0).row();
        String totals = formation.hidden() ? Messages.getString("Lobby.hidden")
              : Messages.getString("Lobby.totals", formation.bv(), formation.tons(), formation.units());
        Label line = kit.typeLabel(totals, "small", "text.secondary");
        line.setEllipsis(true);
        text.add(line).growX().minWidth(0).padTop(space("xxs"));
        row.add(text).growX().minWidth(0);
        UiButton more = kit.button("button-icon" + UiTheme.COMPACT, "more-vert", null, null);
        kit.tip(more, true).getActor().setText(Messages.getString("Lobby.formation.menu"));
        UiKit.onChange(more, () -> {
            if (!selectedFormations.contains(formation.id())) {
                select(false, formation.id(), false, false);
            }
            openMenuUnder(more);
        });
        row.add(more).size(size("control.sm")).padLeft(space("xs"));

        formationRows.put(formation.id(), row);
        order.add(new Object[] { true, formation.id() });
        listen(row, true, formation.id());
        if (formation.editable()) {
            dragAndDrop.addSource(dragSource(row, true, formation.id(), formation.name()));
        }
        dragAndDrop.addTarget(dropTarget(row, Target.formation(formation.id())));
        return row;
    }

    /** A unit's card, or its compact row, with its owner's colour bar at its side. */
    private Table card(LobbyUnit unit, String bar) {
        Table card = kit.card();
        card.setName("lobby-unit-" + unit.id());
        card.top().left();
        if (compact) {
            card.pad(space("xs"), space("sm"), space("xs"), space("xs"));
        }
        card.add(kit.solid(bar)).width(size("selection.width")).growY().padRight(space("sm"));
        // The reference's 58 px card sprite and 26 px compact one, in tokens
        float spriteSize = compact ? size("control.sm") : size("control.lg") + space("lg");
        Cell<Actor> spriteCell = card.add(sprite(unit, spriteSize)).size(spriteSize).padRight(space("sm"));
        if (!compact) {
            spriteCell.top();
        }

        Table lines = new Table();
        lines.top().left().defaults().left();
        lines.add(nameRow(unit)).growX().minWidth(0);
        if (compact) {
            card.add(new CompactBody(lines, unit)).growX().minWidth(0);
        } else {
            lines.row();
            if (!unit.details().isEmpty()) {
                Label details = kit.typeLabel(unit.details(), "small", "text.secondary");
                details.setEllipsis(true);
                lines.add(details).growX().minWidth(0).padTop(space("xxs")).row();
            }
            if (!unit.pilot().isEmpty() || !unit.skills().isEmpty()) {
                lines.add(pilotRow(unit)).growX().minWidth(0).padTop(space("xxs")).row();
            }
            LobbyUnit.Shown tags = unit.shown(LobbyUnit.MAX_TAGS);
            if (!tags.tags().isEmpty()) {
                Table chips = new Table();
                chips.left();
                for (LobbyUnit.Tag tag : tags.tags()) {
                    chips.add(chip(tag)).padRight(space("xs")).minWidth(0);
                }
                if (tags.folded() > 0) {
                    chips.add(kit.typeLabel(Messages.getString("Lobby.card.more", tags.folded()), "small",
                          "text.muted"));
                }
                lines.add(chips).left().minWidth(0).padTop(space("xs"));
            }
            card.add(new CardBody(lines, (unit.sheet() == null) ? null : sheet(unit.sheet()))).growX().minWidth(0)
                  .top();
        }

        Table side = new Table();
        side.top().right();
        if (!unit.bvText().isEmpty()) {
            side.add(kit.typeLabel(unit.bvText(), "body.strong", "text.primary")).right();
            if (!compact) {
                side.row();
            }
        }
        UiButton more = kit.button("button-icon" + UiTheme.COMPACT, "more-vert", null, null);
        kit.tip(more, true).getActor().setText(Messages.getString("Lobby.card.menu"));
        UiKit.onChange(more, () -> {
            if (!selectedUnits.contains(unit.id())) {
                select(true, unit.id(), false, false);
            }
            openMenuUnder(more);
        });
        unitMenuButtons.put(unit.id(), more);
        if (compact) {
            side.add(more).size(size("control.sm")).right().padLeft(space("sm"));
        } else {
            // A wide card's own actions sit under its BV, four to a row with the ⋮ last; its menu leaves them out
            List<UiButton> icons = new ArrayList<>();
            Set<String> offered = new HashSet<>();
            if (!unit.hidden()) {
                icons.add(commandTool(unit, "show", "Lobby.card.view", "view", LobbyUnitMenu.LMP_VIEW, offered));
            }
            if (unit.editable()) {
                icons.add(cardTool(unit, "settings", "Lobby.card.configure", "configure",
                      () -> actions.configure(unit.id())));
                offered.add(LobbyUnitMenu.LMP_CONFIGURE);
                icons.add(commandTool(unit, "palette", "Lobby.card.camo", "camo", LobbyUnitMenu.LMP_INDI_CAMO,
                      offered));
                if (unit.c3()) {
                    icons.add(cardTool(unit, "hub", "Lobby.card.c3", "c3", () -> actions.c3(unit.id())));
                }
            }
            if (!unit.hidden()) {
                icons.add(commandTool(unit, "stats", "Lobby.card.alphaStrike", "alpha",
                      LobbyUnitMenu.LMP_ALPHA_STRIKE, offered));
            }
            if (unit.editable()) {
                icons.add(cardTool(unit, "delete", "Lobby.card.delete", "delete",
                      () -> actions.delete(List.of(unit.id()), List.of())));
                offered.add(LobbyUnitMenu.LMP_DELETE);
            }
            icons.add(more);
            if (!offered.isEmpty()) {
                cardCommands.put(unit.id(), Set.copyOf(offered));
            }
            Table tools = new Table();
            tools.setName("lobby-unit-tools-" + unit.id());
            tools.right();
            int perRow = 4;
            int first = icons.size() % perRow == 0 ? perRow : icons.size() % perRow;
            for (int index = 0; index < icons.size(); index++) {
                if (index == first || (index > first && (index - first) % perRow == 0)) {
                    tools.row();
                }
                // The first row holds the remainder, so the ⋮ always ends a full last row in the corner
                tools.add(icons.get(index)).size(size("control.sm"));
            }
            side.add(tools).right().padTop(space("xs"));
        }
        card.add(side).top().padLeft(space("sm")).minWidth(sideMin(compact));

        unitCards.put(unit.id(), card);
        order.add(new Object[] { false, unit.id() });
        listen(card, false, unit.id());
        if (unit.editable()) {
            card.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    if (getTapCount() == 2 && !inButton(event.getTarget()) && !UIUtils.ctrl() && !UIUtils.shift()) {
                        actions.configure(unit.id());
                    }
                }
            });
        }
        if (formationsView()) {
            if (unit.editable()) {
                dragAndDrop.addSource(dragSource(card, false, unit.id(), unit.name()));
            }
            dragAndDrop.addTarget(dropTarget(card, Target.unit(unit.id())));
        }
        return card;
    }

    /** One of a wide card's action icons, named {@code lobby-unit-<what>-<id>}. */
    private UiButton cardTool(LobbyUnit unit, String icon, String tipKey, String what, Runnable action) {
        UiButton tool = kit.button("button-icon" + UiTheme.COMPACT, icon, null, null);
        tool.setName("lobby-unit-" + what + "-" + unit.id());
        kit.tip(tool, true).getActor().setText(Messages.getString(tipKey));
        UiKit.onChange(tool, action);
        return tool;
    }

    /** A card icon running the unit menu's command {@code name} on the unit, which its ⋮ menu then leaves out. */
    private UiButton commandTool(LobbyUnit unit, String icon, String tipKey, String what, String name,
          Set<String> offered) {
        offered.add(name);
        return cardTool(unit, icon, tipKey, what, () -> actions.unitCommand(unit.id(), name));
    }

    /** The most weapons a card's sheet names before "+n"; its tooltip lists them all. */
    static final int SHEET_WEAPONS = 3;

    /** A wide card's second block: movement, armor and structure, heat sinks and weapons, each under its name. */
    private Table sheet(LobbyUnit.Sheet sheet) {
        Table block = new Table();
        block.setName("lobby-unit-sheet");
        block.top().left().defaults().left();
        block.columnDefaults(0).minWidth(sheetLabelWidth());
        sheetRow(block, "Lobby.card.move", sheet.move(), null);
        sheetRow(block, "Lobby.card.armor", sheet.protection(), null);
        if (!sheet.heat().isEmpty()) {
            sheetRow(block, "Lobby.card.heat.sinks", sheet.heat(), null);
        }
        if (!sheet.weapons().isEmpty()) {
            sheetRow(block, "Lobby.card.weapons", sheet.weaponsSummary(SHEET_WEAPONS), sheet.weaponsAll());
        }
        return block;
    }

    /** The BV and menu column's least width: wide enough for a five-digit BV, so a shorter BV doesn't shift the sheet. */
    private float sideMin(boolean compact) {
        float bv = kit.typeLabel("00,000", "body.strong", "text.primary").getPrefWidth();
        return compact ? bv + space("sm") + size("control.sm") : Math.max(bv, size("control.sm"));
    }

    /** The widest sheet label, so the values start at one column even on a card without heat sinks or weapons. */
    private float sheetLabelWidth() {
        float widest = 0;
        for (String key : new String[] { "Lobby.card.move", "Lobby.card.armor", "Lobby.card.heat.sinks",
              "Lobby.card.weapons" }) {
            widest = Math.max(widest, kit.roleTypeLabel("overline", Messages.getString(key), "text.muted")
                  .getPrefWidth());
        }
        return widest;
    }

    private void sheetRow(Table block, String key, String value, @Nullable String tip) {
        block.add(kit.roleTypeLabel("overline", Messages.getString(key), "text.muted")).padRight(space("sm"))
              .padTop(space("xxs"));
        Label text = kit.typeLabel(value, "small", "text.secondary");
        text.setEllipsis(true);
        if (tip != null) {
            kit.tip(text, true).getActor().setText(tip);
        }
        block.add(text).growX().minWidth(0).padTop(space("xxs")).row();
    }

    /**
     * A card's text with its sheet beside it while the card has room for both: the text keeps {@link #textMin()} and
     * the sheet at most {@link #sheetMax()}; on a narrower card the sheet steps out of the row.
     */
    private final class CardBody extends Table {
        private final @Nullable Table sheet;
        private final @Nullable Cell<Table> sheetCell;
        private boolean shown = true;

        CardBody(Table lines, @Nullable Table sheet) {
            top().left();
            add(lines).growX().minWidth(0).top();
            this.sheet = sheet;
            sheetCell = (sheet == null) ? null : add(sheet).top().left().minWidth(0);
            showSheet(false);
        }

        /** Every card's sheet takes the same width, so the sheets and the ids before them line up down the list. */
        private float sheetWidth() {
            return (sheet == null) ? 0 : sheetMax();
        }

        private void showSheet(boolean show) {
            if (sheet == null || sheetCell == null || show == shown) {
                return;
            }
            shown = show;
            sheet.setVisible(show);
            sheetCell.width(show ? sheetWidth() : 0).padLeft(show ? space("xl") : 0);
            invalidate();
        }

        @Override
        protected void sizeChanged() {
            showSheet(sheetFits(getWidth(), textMin(), space("xl"), sheetWidth()));
            super.sizeChanged();
        }

        /** Whether the sheet shows, for tests. */
        boolean sheetShown() {
            return shown;
        }
    }

    /** Whether a card's body {@code width} wide holds its text at {@code textMin} and, {@code gap} on, its sheet. */
    static boolean sheetFits(float width, float textMin, float gap, float sheetWidth) {
        return sheetWidth > 0 && width >= textMin + gap + sheetWidth;
    }

    /** The least a card's text keeps before its sheet steps aside. */
    private static float textMin() {
        return size("dialog.sm") * 5 / 8;
    }

    /** The most a card's sheet takes; a longer weapons line ellipsizes. */
    private static float sheetMax() {
        return size("dialog.sm") * 3 / 4;
    }

    private Actor sprite(LobbyUnit unit, float spriteSize) {
        Drawable drawable = unit.hidden() ? null : sprites.apply(unit.id());
        if (drawable == null) {
            return kit.icon(unit.hidden() ? "hidden" : "unit", Math.min(spriteSize, size("icon.md")),
                  UiTheme.tokenColor("text.muted"));
        }
        Image image = new Image(drawable, Scaling.fit);
        return image;
    }

    /** The name in its problem's colour, after the problem's glyph, then the muted id. */
    private Table nameRow(LobbyUnit unit) {
        Table row = new Table();
        row.left();
        if (unit.nameTone() == LobbyUnit.Tone.DANGER || unit.nameTone() == LobbyUnit.Tone.WARNING) {
            String icon = unit.nameTone() == LobbyUnit.Tone.DANGER ? "error" : "warn";
            row.add(kit.icon(icon, size("icon.sm"), UiTheme.tokenColor(unit.nameTone().role()))).size(size("icon.sm"))
                  .padRight(space("xs"));
        }
        if (unit.hidden()) {
            row.add(kit.icon("hidden", size("icon.sm"), UiTheme.tokenColor("text.muted"))).size(size("icon.sm"))
                  .padRight(space("xs"));
        }
        String role = unit.nameTone() == null ? "text.primary" : unit.nameTone().role();
        Label name = kit.roleTypeLabel("name", unit.name(), role);
        name.setEllipsis(true);
        row.add(name).minWidth(0).growX();
        Label id = kit.typeLabel("#" + unit.id(), "small", "text.muted");
        id.setName("lobby-unit-id");
        row.add(id).padLeft(space("xs"));
        return row;
    }

    private Table pilotRow(LobbyUnit unit) {
        Table row = new Table();
        row.left();
        Label pilot = kit.typeLabel(unit.pilot(), "small", "text.primary");
        pilot.setEllipsis(true);
        row.add(pilot).minWidth(0);
        if (!unit.skills().isEmpty()) {
            row.add(kit.typeLabel(unit.skills(), "small", "text.secondary")).padLeft(space("sm"));
        }
        return row;
    }

    /**
     * A compact row's name with as many of its facts as its width holds: the skills, then tonnage and weight class,
     * movement, the pilot and the most important tag. Each fact keeps one width on every row, so they line up down the
     * list like a roster's columns; a narrower list drops the least important first.
     */
    private final class CompactBody extends Table {
        /** The facts in the order they drop out, the last first. */
        private final List<Cell<Label>> facts = new ArrayList<>();
        private final List<Float> widths = new ArrayList<>();
        private int showing = -1;

        CompactBody(Table name, LobbyUnit unit) {
            left();
            add(name).growX().minWidth(0);
            boolean open = !unit.hidden();
            LobbyUnit.Sheet sheet = unit.sheet();
            LobbyUnit.Tag tag = (open && !unit.tags().isEmpty()) ? unit.tags().getFirst() : null;
            Cell<Label> weightCell = fact(unit.weight(), "text.secondary", weightWidth());
            Cell<Label> moveCell = fact((open && sheet != null) ? sheet.move() : "", "text.secondary",
                  textWidth(Messages.getString("Lobby.requisition.moveJump", 12, 18, 12)));
            Cell<Label> pilotCell = fact(open ? unit.pilot() : "", "text.primary", size("dialog.sm") * 3 / 8);
            Cell<Label> skillsCell = fact(open ? unit.skills() : "", "text.secondary", textWidth("10/10"));
            Cell<Label> tagCell = fact((tag == null) ? "" : tag.text(),
                  (tag == null) ? "text.secondary" : tag.tone().role(), size("dialog.sm") / 3);
            for (Cell<Label> cell : List.of(skillsCell, weightCell, moveCell, pilotCell, tagCell)) {
                facts.add(cell);
                widths.add(cell.getPrefWidth());
            }
            showFacts(0);
        }

        private Cell<Label> fact(String text, String role, float width) {
            Label label = kit.typeLabel(text, "small", role);
            label.setName(COMPACT_FACT);
            label.setEllipsis(true);
            return add(label).minWidth(0).prefWidth(width).maxWidth(width).padLeft(space("md")).left();
        }

        private float textWidth(String text) {
            return kit.typeLabel(text, "small", "text.secondary").getPrefWidth();
        }

        /** Room for a hundred tons and the longest weight class a 'Mek has. */
        private float weightWidth() {
            return textWidth(Messages.getString("Lobby.card.weight", Messages.getString("Lobby.card.tons", "100"),
                  "Super Heavy"));
        }

        private void showFacts(int count) {
            if (count == showing) {
                return;
            }
            showing = count;
            for (int i = 0; i < facts.size(); i++) {
                boolean show = i < count;
                Cell<Label> cell = facts.get(i);
                cell.getActor().setVisible(show);
                cell.prefWidth(show ? widths.get(i) : 0).maxWidth(show ? widths.get(i) : 0).padLeft(show ? space("md") : 0);
            }
            invalidate();
        }

        @Override
        protected void sizeChanged() {
            showFacts(compactFacts(getWidth(), compactNameMin(), space("md"), widths));
            super.sizeChanged();
        }

        /** How many facts show, for tests. */
        int showing() {
            return showing;
        }
    }

    /** The name of a compact row's fact labels, for tests. */
    static final String COMPACT_FACT = "lobby-compact-fact";

    /** How many of a compact row's facts, {@code widths} wide in order, fit beside a name of {@code nameMin}. */
    static int compactFacts(float width, float nameMin, float gap, List<Float> widths) {
        float used = nameMin;
        int count = 0;
        for (float factWidth : widths) {
            used += gap + factWidth;
            if (used > width) {
                break;
            }
            count++;
        }
        return count;
    }

    /** The least a compact row's name keeps before its facts step aside. */
    private static float compactNameMin() {
        return size("dialog.sm") * 3 / 8;
    }

    private Actor chip(LobbyUnit.Tag tag) {
        return switch (tag.tone()) {
            case DANGER -> kit.chip(tag.text(), Tone.BAD, true);
            case WARNING -> kit.chip(tag.text(), Tone.WARN, true);
            case SETUP -> kit.edgeChip(tag.text(), "accent");
            case FLAVOUR -> kit.chip(tag.text(), Tone.NORMAL, true);
        };
    }

    private static boolean inButton(Actor target) {
        for (Actor actor = target; actor != null; actor = actor.getParent()) {
            if (actor instanceof Button) {
                return true;
            }
        }
        return false;
    }

    // ---- Selection ----

    /** Selects on a click and opens the unit menu on a right click, selecting the card first unless it is. */
    private void listen(Table row, boolean formation, int id) {
        // A table takes only its children's hits; the whole card takes clicks, not only its labels
        row.setTouchable(Touchable.enabled);
        row.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                if (!inButton(event.getTarget()) && getTapCount() == 1) {
                    select(!formation, id, UIUtils.ctrl(), UIUtils.shift());
                }
            }
        });
        row.addListener(new ClickListener(Input.Buttons.RIGHT) {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                boolean selected = formation ? selectedFormations.contains(id) : selectedUnits.contains(id);
                if (!selected) {
                    select(!formation, id, false, false);
                }
                openMenuAt(event.getStageX(), event.getStageY(), null);
            }
        });
    }

    private void select(boolean unit, int id, boolean toggle, boolean range) {
        if (panel.getStage() != null) {
            // Keep focus on the stable panel so rebuilding cards does not lose the selection's keyboard actions.
            panel.getStage().setKeyboardFocus(panel);
        }
        Integer key = unit ? id : -id - 1;
        if (range && anchor != null) {
            int from = indexOf(anchor);
            int to = indexOf(key);
            if (from >= 0 && to >= 0) {
                if (!toggle) {
                    selectedUnits.clear();
                    selectedFormations.clear();
                }
                for (int index = Math.min(from, to); index <= Math.max(from, to); index++) {
                    Object[] item = order.get(index);
                    ((Boolean) item[0] ? selectedFormations : selectedUnits).add((Integer) item[1]);
                }
                paintSelection();
                return;
            }
        }
        Set<Integer> set = unit ? selectedUnits : selectedFormations;
        if (toggle) {
            if (!set.remove(id)) {
                set.add(id);
            }
        } else {
            selectedUnits.clear();
            selectedFormations.clear();
            set.add(id);
        }
        anchor = key;
        paintSelection();
    }

    private int indexOf(Integer key) {
        for (int index = 0; index < order.size(); index++) {
            Object[] item = order.get(index);
            int itemKey = (Boolean) item[0] ? -(Integer) item[1] - 1 : (Integer) item[1];
            if (itemKey == key) {
                return index;
            }
        }
        return -1;
    }

    private void paintSelection() {
        unitCards.forEach((id, card) -> card.setBackground(kit.skin.getDrawable(
              selectedUnits.contains(id) ? "token-card-selected" : "token-card")));
        formationRows.forEach((id, row) -> row.setBackground(kit.skin.getDrawable(
              selectedFormations.contains(id) ? "token-card-selected" : "token-card")));
    }

    // ---- Drag and drop ----

    private DragAndDrop.Source dragSource(Table row, boolean formation, int id, String name) {
        return new DragAndDrop.Source(row) {
            @Override
            public DragAndDrop.Payload dragStart(InputEvent event, float x, float y, int pointer) {
                Set<Integer> selection = formation ? selectedFormations : selectedUnits;
                List<Integer> ids = selection.contains(id) ? List.copyOf(selection) : List.of(id);
                Drag drag = formation ? new Drag(List.of(), ids) : new Drag(ids, List.of());
                DragAndDrop.Payload payload = new DragAndDrop.Payload();
                payload.setObject(drag);
                Table flying = new Table();
                flying.setBackground(kit.skin.getDrawable("token-row-lifted"));
                flying.pad(space("xs"), space("sm"), space("xs"), space("sm"));
                flying.add(kit.icon(formation ? "layers" : "unit", size("icon.sm"),
                      UiTheme.tokenColor("text.secondary"))).size(size("icon.sm")).padRight(space("xs"));
                flying.add(kit.typeLabel(ids.size() == 1 ? name : String.valueOf(ids.size()), "body.strong",
                      "text.primary"));
                flying.pack();
                payload.setDragActor(flying);
                dragAndDrop.setDragActorPosition(-space("md"), flying.getHeight() / 2);
                dropStrip.setVisible(true);
                return payload;
            }

            @Override
            public void dragStop(InputEvent event, float x, float y, int pointer, DragAndDrop.Payload payload,
                  DragAndDrop.Target target) {
                dropStrip.setVisible(false);
            }
        };
    }

    private DragAndDrop.Target dropTarget(Actor actor, Target target) {
        return new DragAndDrop.Target(actor) {
            @Override
            public boolean drag(DragAndDrop.Source source, DragAndDrop.Payload payload, float x, float y,
                  int pointer) {
                if (!(payload.getObject() instanceof Drag drag)
                      || !LobbyForcesController.accepts(drag.unitIds(), drag.formationIds(), target)
                      || target.kind() == LobbyForcesController.TargetKind.UNIT
                      && drag.unitIds().contains(target.id())) {
                    return false;
                }
                if (actor instanceof Table table && actor != scroll) {
                    table.setBackground(kit.skin.getDrawable("token-card-drop"));
                }
                return true;
            }

            @Override
            public void reset(DragAndDrop.Source source, DragAndDrop.Payload payload) {
                if (actor == dropStrip) {
                    dropStrip.setBackground(kit.skin.getDrawable("token-row-slot"));
                } else if (actor instanceof Table) {
                    paintSelection();
                }
            }

            @Override
            public void drop(DragAndDrop.Source source, DragAndDrop.Payload payload, float x, float y, int pointer) {
                if (payload.getObject() instanceof Drag drag) {
                    actions.drop(drag.unitIds(), drag.formationIds(), target);
                }
            }
        };
    }

    // ---- Menus ----

    /** The forces column's ⋮ menu: the unit list commands, random names and skills, C3 and a new formation. */
    private void openForcesMenu(Actor button) {
        if (menu.isVisible()) {
            menu.cancel();
            return;
        }
        if (menu.getStage() == null) {
            return;
        }
        UiMenuList items = new UiMenuList(kit, UiTheme.TOKEN_MENU_ROW);
        command(items, "Lobby.forces.loadList", true, Command.LOAD_LIST);
        command(items, "Lobby.forces.saveList", !shown.units().isEmpty(), Command.SAVE_LIST);
        command(items, "Lobby.forces.print", !shown.units().isEmpty(), Command.PRINT_LIST);
        items.separator();
        command(items, "Lobby.forces.names", !shown.units().isEmpty(), Command.NAMES);
        command(items, "Lobby.forces.skills", !shown.units().isEmpty(), Command.SKILLS);
        command(items, "Lobby.forces.c3", shown.anyC3(), Command.C3);
        items.separator();
        command(items, "Lobby.forces.newFormation", true, Command.NEW_FORMATION);
        menu.header(null, null).content(items);
        menu.openedFrom(button);
        Vector2 corner = button.localToStageCoordinates(new Vector2(0, 0));
        menu.showAt(corner.x, corner.y);
    }

    private void command(UiMenuList items, String key, boolean enabled, Command command) {
        items.item(Messages.getString(key), null, null, false, enabled, () -> {
            menu.cancel();
            actions.command(command);
        });
    }

    private void openMenuUnder(Actor button) {
        if (menu.isVisible()) {
            menu.cancel();
            return;
        }
        Vector2 corner = button.localToStageCoordinates(new Vector2(0, 0));
        openMenuAt(corner.x, corner.y, button);
    }

    /** Asks the window for the unit menu of the selection, to open at stage point ({@code x}, {@code y}). */
    private void openMenuAt(float x, float y, @Nullable Actor face) {
        menu.cancel();
        List<Integer> units = List.copyOf(selectedUnits);
        List<Integer> formations = formationsView() ? List.copyOf(selectedFormations) : List.of();
        if (units.isEmpty() && formations.isEmpty()) {
            return;
        }
        String title;
        String detail = "";
        if (units.size() == 1 && formations.isEmpty() && shown.unit(units.getFirst()) instanceof LobbyUnit unit) {
            title = unit.name();
            detail = unit.details();
        } else if (formations.size() == 1 && units.isEmpty()
              && shown.formation(formations.getFirst()) instanceof Formation formation) {
            title = formation.name();
            detail = formation.ownerName();
        } else {
            title = Messages.getString("Lobby.totals", units.stream().map(shown::unit)
                        .filter(unit -> unit != null).mapToInt(LobbyUnit::bv).sum(),
                  Math.round(units.stream().map(shown::unit).filter(unit -> unit != null)
                        .mapToDouble(LobbyUnit::tons).sum()), units.size());
        }
        pendingMenu = new MenuAnchor(x, y, face, title, detail);
        // Opened from a card's ⋮ for that unit alone, the menu leaves out what the card's icons already offer
        Set<String> omit = (face != null && units.size() == 1 && formations.isEmpty())
              ? cardCommands.getOrDefault(units.getFirst(), Set.of()) : Set.of();
        actions.menu(units, formations, formationsView(), omit);
    }

    /** For the developer switch: opens the menu of the first of the local player's units; false when none shows. */
    boolean openFirstUnitMenu() {
        for (LobbyUnit unit : shown.units()) {
            Actor button = unitMenuButtons.get(unit.id());
            if (unit.editable() && button != null && button.getStage() != null) {
                select(true, unit.id(), false, false);
                openMenuUnder(button);
                return true;
            }
        }
        return false;
    }

    /** Opens the unit menu the window read, where it was asked for; nothing when it was not or is gone. */
    void showMenu(List<LobbyMenuEntry> entries) {
        MenuAnchor at = pendingMenu;
        pendingMenu = null;
        if (at == null || entries.isEmpty() || menu.getStage() == null) {
            return;
        }
        menuEntries = entries;
        menuTitle = at.title();
        menuDetail = at.detail();
        menuPath.clear();
        fillMenu();
        menu.openedFrom(at.face());
        menu.showAt(at.x(), at.y());
    }

    /**
     * Fills the unit menu with the entries at {@link #menuPath}: its top, or a submenu under the submenu's name with a
     * Back row. A submenu replaces the list in the same popover, so the menu never fans out into a cascade; Left and
     * Escape go back up.
     */
    private void fillMenu() {
        List<LobbyMenuEntry> level = menuEntries;
        String title = menuTitle;
        String detail = menuDetail;
        for (int index : menuPath) {
            LobbyMenuEntry opened = level.get(index);
            title = opened.label();
            detail = menuTitle;
            level = opened.children();
        }
        UiMenuList items = new UiMenuList(kit, UiTheme.TOKEN_MENU_ROW);
        items.setName("lobby-unit-menu-items");
        if (!menuPath.isEmpty()) {
            UiButton back = items.item("\u2039  " + Messages.getString("Lobby.unitMenu.back"), null, null, false, true,
                  this::menuUp);
            back.setName("lobby-unit-menu-back");
            items.separator();
        }
        items.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (keycode != Input.Keys.LEFT || menuPath.isEmpty()) {
                    return false;
                }
                menuUp();
                return true;
            }
        });
        for (int index = 0; index < level.size(); index++) {
            LobbyMenuEntry entry = level.get(index);
            if (entry.separator()) {
                items.separator();
                continue;
            }
            List<Integer> path = new ArrayList<>(menuPath);
            path.add(index);
            UiButton item;
            if (entry.submenu()) {
                item = items.item(entry.label(), null, null, true, entry.enabled(), () -> {
                    menuPath.add(path.getLast());
                    fillMenu();
                });
            } else {
                item = items.item(entry.label(), null, entry.checked(), false, entry.enabled(), () -> {
                    menu.cancel();
                    actions.choose(List.copyOf(path));
                });
            }
            if (!entry.tooltip().isBlank()) {
                kit.tip(item, true).getActor().setText(entry.tooltip());
            }
        }
        menu.header(title, detail.isEmpty() ? null : detail).content(items);
        if (menu.isVisible() && menu.getStage() != null) {
            menu.reposition();
            menu.getStage().setKeyboardFocus(items);
        }
    }

    /** Goes from a submenu back up to the menu it is in. */
    private void menuUp() {
        if (!menuPath.isEmpty()) {
            menuPath.removeLast();
            fillMenu();
        }
    }

    /** For the developer switch: opens the open unit menu's first submenu that may be chosen; false when it has none. */
    boolean openFirstSubmenu() {
        if (!menu.isVisible() || !menuPath.isEmpty()) {
            return false;
        }
        for (int index = 0; index < menuEntries.size(); index++) {
            if (menuEntries.get(index).submenu() && menuEntries.get(index).enabled()) {
                menuPath.add(index);
                fillMenu();
                return true;
            }
        }
        return false;
    }

    /** The open unit menu's submenu, as indices from its top; empty at the top. For tests. */
    List<Integer> menuPath() {
        return List.copyOf(menuPath);
    }

    /** Whether a unit is selected; for tests of the panel's selection. */
    boolean selected(int unitId) {
        return selectedUnits.contains(unitId);
    }
}
