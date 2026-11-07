/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.utils.Align;
import jakarta.annotation.Nullable;
import megamek.client.lobby.BalancedArmy;
import megamek.client.lobby.BattleValueArmy;
import megamek.client.lobby.FactionChoice;
import megamek.client.lobby.FormationForm;
import megamek.client.lobby.RatGeneratorForm;
import megamek.client.lobby.RequisitionAdvancedSearch;
import megamek.client.ratgenerator.FactionRecord;
import megamek.client.ratgenerator.FormationType;
import megamek.client.ratgenerator.MissionRole;
import megamek.client.ratgenerator.UnitTable;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiFilterChip;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiLevelTiles;
import megamek.client.ui.gdx.UiPickList;
import megamek.client.ui.gdx.UiRangeInput;
import megamek.client.ui.gdx.UiSegmented;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiStepper;
import megamek.client.ui.gdx.UiTextField;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.loaders.MekSummary;
import megamek.common.units.UnitType;

/**
 * The random army's generators' choices, a pane each beside the hand (gap analysis L17): the Battle Value army's counts,
 * budget and design years; the balanced lance's measure, target and tolerance; the faction table's faction, year,
 * rating and the RAT Generator's unit type, weight classes, roles, network and motives, with the table it builds; the
 * formation's faction, unit type, formation and additional units. Every choice goes straight to its generator; a
 * choice that changes what else is offered has the pane built anew ({@link Owner#changed}).
 * <p>
 * GPU thread.
 */
final class LobbyArmyGeneratorPanes {
    /** The tiles of the role strictness, Low to High, lit in rising colours. */
    private static final String[] STRICTNESS_ROLES = { "status.success", "status.warning", "status.danger" };
    /** The most rating choices shown as a segmented control rather than a select. */
    private static final int SEGMENTED_RATINGS = 5;
    /** The longest rating shown in a segmented control: letter grades are, named lines (Front Line) are not. */
    private static final int SEGMENTED_RATING_LENGTH = 2;

    /** What the panes ask of the random army. */
    interface Owner {
        /** A choice changed: the hand's caption and Roll follow; with {@code rebuild}, the pane is built anew. */
        void changed(boolean rebuild);

        /** Runs {@code work} on the Swing thread, then {@code then} back on the GPU thread. */
        void edt(Runnable work, Runnable then);

        /** Puts a table's unit in the hand. */
        void take(MekSummary unit);

        /** Opens {@code unit}'s readout over the random army. */
        void readout(MekSummary unit);

        /** The unit filter the Battle Value army and the balanced lance deal from. */
        RequisitionAdvancedSearch criteria();

        /** Shows the unit filter in the random army's place. */
        void openFilter();

        /** Clears the unit filter. */
        void clearFilter();
    }

    /** A row of a built table, read on the Swing thread: its text, its share in percent and its unit, if a unit. */
    record Row(String text, int share, int bv, @Nullable MekSummary unit) { }

    private final UiKit kit;
    private final Owner owner;
    private boolean showTable;
    private @Nullable List<Row> rows;
    private boolean reading;
    private int asked;
    private boolean focusYear;

    LobbyArmyGeneratorPanes(UiKit kit, Owner owner) {
        this.kit = kit;
        this.owner = owner;
    }

    // ---- Battle Value ----

    /** The Battle Value army's counts by unit type, budget, design years, canon and padding, and the unit filter. */
    Table battleValue(BattleValueArmy army) {
        Table pane = page("Lobby.randomArmy.mode.battleValue", "Lobby.randomArmy.about.battleValue");
        Table counts = new Table();
        counts.left().defaults().left();
        Map<BattleValueArmy.Kind, Container<Actor>> statuses = new EnumMap<>(BattleValueArmy.Kind.class);
        for (BattleValueArmy.Kind kind : BattleValueArmy.Kind.values()) {
            String id = kind.name().toLowerCase();
            counts.add(kit.typeLabel(strip(kindKey(kind)), "body", "text.primary")).growX().minWidth(0)
                  .padTop(space("xs"));
            UiStepper count = kit.stepper(army.count(kind), 0, BattleValueArmy.MAX_COUNT);
            count.setName("army-bv-" + id);
            count.up().setName("army-bv-" + id + "-up");
            count.down().setName("army-bv-" + id + "-down");
            Container<Actor> status = new Container<>();
            status.left();
            statuses.put(kind, status);
            UiKit.onChange(count, () -> {
                army.count(kind, count.value());
                status(status, army, kind);
                owner.changed(false);
            });
            counts.add(count).padLeft(space("md")).padTop(space("xs"));
            counts.add(status).width(size("control.lg") * 3).padLeft(space("md")).padTop(space("xs")).row();
        }
        Runnable recount = () -> statuses.forEach((kind, status) -> status(status, army, kind));
        recount.run();
        pane.add(counts).growX().padTop(space("md")).row();
        Label split = kit.typeLabel(Messages.getString("Lobby.randomArmy.bvSplit"), "small", "text.muted");
        split.setWrap(true);
        pane.add(split).padTop(space("xs")).row();

        UiRangeInput budget = kit.rangeInput(Messages.getString("Lobby.randomArmy.min"),
              Messages.getString("Lobby.randomArmy.max"));
        budget.setName("army-bv-budget");
        budget.set(army.minBV(), army.maxBV());
        UiKit.onChange(budget, () -> {
            army.bv(budget.min() == null ? 0 : budget.min(),
                  budget.max() == null ? BattleValueArmy.MAX_BV : budget.max());
            owner.changed(false);
        });
        pane.add(kit.formField(Messages.getString("Lobby.randomArmy.bvBudget"), budget)).padTop(space("md")).row();

        UiRangeInput years = kit.rangeInput(String.valueOf(BattleValueArmy.MIN_YEAR),
              String.valueOf(BattleValueArmy.MAX_YEAR));
        years.setName("army-bv-years");
        years.set(army.minYear(), army.maxYear());
        UiKit.onChange(years, () -> {
            army.years(years.min() == null ? BattleValueArmy.MIN_YEAR : years.min(),
                  years.max() == null ? BattleValueArmy.MAX_YEAR : years.max());
            recount.run();
            owner.changed(false);
        });
        pane.add(kit.formField(Messages.getString("Lobby.randomArmy.designYears"), years)).padTop(space("md")).row();

        var canon = kit.check(Messages.getString("RandomArmyDialog.Canon"), army.canon());
        canon.setName("army-bv-canon");
        UiKit.onChange(canon, () -> {
            army.canon(canon.isChecked());
            recount.run();
            owner.changed(false);
        });
        pane.add(canon).fill(0f, 0f).padTop(space("md")).row();
        var pad = kit.check(Messages.getString("RandomArmyDialog.Pad"), army.pad());
        pad.setName("army-bv-pad");
        UiKit.onChange(pad, () -> army.pad(pad.isChecked()));
        pane.add(pad).fill(0f, 0f).padTop(space("xs")).row();
        pane.add(filterRow()).padTop(space("md")).row();
        return pane;
    }

    private static String kindKey(BattleValueArmy.Kind kind) {
        return switch (kind) {
            case MEK -> "RandomArmyDialog.Meks";
            case VEHICLE -> "RandomArmyDialog.Vees";
            case BATTLE_ARMOR -> "RandomArmyDialog.BA";
            case INFANTRY -> "RandomArmyDialog.Infantry";
        };
    }

    /** Shows how many units of {@code kind} the army can be dealt, or a warning when it asks for some of none. */
    private void status(Container<Actor> status, BattleValueArmy army, BattleValueArmy.Kind kind) {
        if (army.shortOf(kind)) {
            status.setActor(kit.chip(Messages.getString("Lobby.randomArmy.noneAvailable"), UiKit.Tone.WARN, true));
        } else {
            status.setActor(kit.typeLabel(Messages.getString("Lobby.randomArmy.available", army.available(kind)),
                  "small", "text.muted"));
        }
    }

    // ---- Balanced ----

    /** The balanced lance's measure, target and tolerance, with the units it can be dealt and the unit filter. */
    Table balanced(BalancedArmy army) {
        Table pane = page("Lobby.randomArmy.mode.balanced", "Lobby.randomArmy.about.balanced");
        BalancedArmy.BalanceBy[] measures = BalancedArmy.BalanceBy.values();
        List<String> names = new ArrayList<>();
        for (BalancedArmy.BalanceBy measure : measures) {
            names.add(Messages.getString(measure.key()));
        }
        UiStepper balanceBy = kit.selector(names, army.balanceBy().ordinal());
        balanceBy.setName("army-balance-by");
        UiKit.onChange(balanceBy, () -> {
            army.balanceBy(measures[balanceBy.value()]);
            owner.changed(true);
        });
        pane.add(kit.formField(Messages.getString("Lobby.randomArmy.balanceBy"), balanceBy)).padTop(space("md"))
              .row();

        int[] targets = army.balanceBy().targets();
        List<String> targetNames = new ArrayList<>();
        int chosen = 0;
        for (int index = 0; index < targets.length; index++) {
            targetNames.add(NumberFormat.getIntegerInstance().format(targets[index]));
            if (targets[index] == army.target()) {
                chosen = index;
            }
        }
        UiStepper target = kit.selector(targetNames, chosen);
        target.setName("army-balance-target");
        UiKit.onChange(target, () -> {
            army.target(targets[target.value()]);
            owner.changed(false);
        });
        pane.add(kit.formField(Messages.getString("Lobby.randomArmy.target"), target)).padTop(space("md")).row();

        List<String> tolerances = new ArrayList<>();
        for (int percent : BalancedArmy.TOLERANCES) {
            tolerances.add(Messages.getString("Lobby.randomArmy.tolerancePercent", percent));
        }
        UiStepper tolerance = kit.selector(tolerances,
              Math.max(0, BalancedArmy.TOLERANCES.indexOf(army.tolerance())));
        tolerance.setName("army-balance-tolerance");
        UiKit.onChange(tolerance, () -> {
            army.tolerance(BalancedArmy.TOLERANCES.get(tolerance.value()));
            owner.changed(false);
        });
        pane.add(kit.formField(Messages.getString("Lobby.randomArmy.tolerance"), tolerance)).padTop(space("md"))
              .row();
        pane.add(kit.typeLabel(Messages.getString("Lobby.randomArmy.available", army.available()), "small",
              "text.muted")).padTop(space("md")).row();
        pane.add(filterRow()).padTop(space("sm")).row();
        return pane;
    }

    /** The unit filter's button, what it asks for, and a button to clear it. */
    private Table filterRow() {
        Table row = new Table();
        row.left();
        UiButton open = kit.button("button-secondary" + UiTheme.COMPACT, "filter",
              Messages.getString("Lobby.randomArmy.filter"), null);
        open.setName("army-filter");
        UiKit.onChange(open, owner::openFilter);
        row.add(open);
        RequisitionAdvancedSearch criteria = owner.criteria();
        Label shown = kit.typeLabel(criteria.isEmpty() ? Messages.getString("Lobby.randomArmy.filterAny")
              : Messages.getString("Lobby.randomArmy.filterOn", criteria.count()), "small",
              criteria.isEmpty() ? "text.muted" : "accent.primary");
        shown.setName("army-filter-shown");
        row.add(shown).padLeft(space("sm")).growX().minWidth(0);
        if (!criteria.isEmpty()) {
            UiButton clear = kit.button("button-quiet" + UiTheme.COMPACT, "close", null, null);
            clear.setName("army-filter-clear");
            kit.tip(clear, true).getActor().setText(Messages.getString("Lobby.randomArmy.filterClear"));
            UiKit.onChange(clear, owner::clearFilter);
            row.add(clear).padLeft(space("xs"));
        }
        return row;
    }

    // ---- Faction ----

    /** The faction table's faction and the RAT Generator's choices, and under them the table they build. */
    Table faction(RatGeneratorForm form) {
        Table pane = page("Lobby.randomArmy.mode.faction", "Lobby.randomArmy.about.faction");
        factionBlock(pane, form.factions());
        ratOptions(pane, form);

        pane.add(kit.roleTypeLabel("overline", Messages.getString("Lobby.randomArmy.table"), "text.secondary"))
              .padTop(space("lg")).row();
        Table head = new Table();
        head.left();
        Label about = kit.typeLabel(Messages.getString("Lobby.randomArmy.tableAbout"), "small", "text.muted");
        about.setWrap(true);
        head.add(about).growX().minWidth(0);
        UiButton view = kit.button("button-secondary" + UiTheme.COMPACT, showTable ? "chevron-up" : "chevron-down",
              Messages.getString(showTable ? "Lobby.randomArmy.hideTable" : "Lobby.randomArmy.showTable"), null);
        view.setName("army-rat-view");
        UiKit.onChange(view, () -> {
            showTable = !showTable;
            owner.changed(true);
        });
        head.add(view).padLeft(space("sm"));
        pane.add(head).padTop(space("xs")).row();
        if (showTable) {
            tableRows(pane, form);
        }
        return pane;
    }

    private void ratOptions(Table pane, RatGeneratorForm form) {
        pane.add(unitTypeField(RatGeneratorForm.UNIT_TYPES, form.unitType(), type -> {
            form.unitType(type);
            tableChanged(true);
        })).padTop(space("md")).row();

        if (!form.weightClasses().isEmpty()) {
            HorizontalGroup weights = chipGroup();
            weights.setName("army-rat-weights");
            List<UiFilterChip> chips = new ArrayList<>();
            for (int weight : form.weightClasses()) {
                UiFilterChip chip = kit.filterChip(Messages.getString(RatGeneratorForm.weightKey(weight)));
                chip.setName("army-rat-weight-" + weight);
                chip.setProgrammaticChangeEvents(false);
                chip.setChecked(form.weight(weight));
                UiKit.onChange(chip, () -> {
                    form.weight(weight, chip.isChecked());
                    tableChanged(false);
                });
                chips.add(chip);
                weights.addActor(chip);
            }
            UiFilterChip.sameWidth(chips);
            pane.add(kit.formField(Messages.getString("Lobby.randomArmy.weights"), weights)).padTop(space("md"))
                  .row();
        }

        List<MissionRole> offered = form.roles();
        if (!offered.isEmpty()) {
            List<String> names = new ArrayList<>();
            Map<Integer, Boolean> picked = new LinkedHashMap<>();
            for (int index = 0; index < offered.size(); index++) {
                names.add(Messages.getString(RatGeneratorForm.roleKey(offered.get(index))));
                if (form.role(offered.get(index))) {
                    picked.put(index, true);
                }
            }
            UiPickList roles = kit.pickList(Messages.getString("Lobby.randomArmy.addRole"), names, false);
            roles.setName("army-rat-roles");
            roles.set(picked);
            UiKit.onChange(roles, () -> {
                for (int index = 0; index < offered.size(); index++) {
                    form.role(offered.get(index), roles.picks().containsKey(index));
                }
                tableChanged(false);
            });
            pane.add(kit.formField(Messages.getString("Lobby.randomArmy.roles"), roles)).growX().padTop(space("md"))
                  .row();

            String[] levels = new String[RatGeneratorForm.STRICTNESS_KEYS.size()];
            for (int index = 0; index < levels.length; index++) {
                levels[index] = Messages.getString(RatGeneratorForm.STRICTNESS_KEYS.get(index));
            }
            UiLevelTiles strictness = kit.levelTiles(levels, STRICTNESS_ROLES);
            strictness.setName("army-rat-strictness");
            strictness.select(form.strictness() - 1);
            for (int index = 0; index < levels.length; index++) {
                int level = index;
                UiLevelTiles.Tile tile = strictness.tiles.get(index);
                tile.setName("army-rat-strictness-" + (index + 1));
                UiKit.onChange(tile, () -> {
                    form.strictness(level + 1);
                    strictness.select(level);
                    tableChanged(false);
                });
            }
            kit.tip(strictness, true).getActor().setText(Messages.getString("RandomArmyDialog.Strictness.tooltip"));
            pane.add(kit.formField(Messages.getString("Lobby.randomArmy.strictness"), strictness)).growX()
                  .padTop(space("md")).row();
        }

        List<RatGeneratorForm.Choice<Integer>> networks = form.networks();
        if (!networks.isEmpty()) {
            pane.add(networkField(networks, form.network(), mask -> {
                form.network(mask);
                tableChanged(false);
            })).padTop(space("md")).row();
        }

        List<RatGeneratorForm.Choice<String>> motives = form.motives();
        if (!motives.isEmpty()) {
            HorizontalGroup group = chipGroup();
            group.setName("army-rat-motives");
            for (RatGeneratorForm.Choice<String> motive : motives) {
                UiFilterChip chip = kit.filterChip(Messages.getString(motive.key()));
                chip.setName("army-rat-motive-" + motive.value());
                chip.setProgrammaticChangeEvents(false);
                chip.setChecked(form.motive(motive.value()));
                UiKit.onChange(chip, () -> {
                    form.motive(motive.value(), chip.isChecked());
                    tableChanged(false);
                });
                group.addActor(chip);
            }
            pane.add(kit.formField(Messages.getString("Lobby.randomArmy.motives"), group)).padTop(space("md"))
                  .row();
        }
    }

    /** The table the choices build, read on the Swing thread when it is not yet, each unit with a button to take it. */
    private void tableRows(Table pane, RatGeneratorForm form) {
        Table list = new Table();
        list.setName("army-rat-table");
        list.top().left().defaults().left().growX();
        pane.add(list).growX().padTop(space("sm")).row();
        if (rows == null) {
            kit.empty(list, Messages.getString("Lobby.randomArmy.tableLoading"), true);
            if (!reading) {
                reading = true;
                int request = asked;
                List<List<Row>> read = new ArrayList<>(1);
                owner.edt(() -> read.add(rows(form.table())), () -> {
                    reading = false;
                    if (request == asked && !read.isEmpty()) {
                        rows = read.get(0);
                    }
                    owner.changed(true);
                });
            }
            return;
        }
        if (rows.isEmpty()) {
            kit.empty(list, Messages.getString("Lobby.randomArmy.tableEmpty"), true);
            return;
        }
        int index = 0;
        for (Row row : rows) {
            list.add(tableRow(row, index++)).growX().row();
        }
    }

    private Table tableRow(Row row, int index) {
        Table line = new Table();
        line.setName("army-rat-row-" + index);
        line.left();
        Label name = kit.typeLabel(row.text(), "body", row.unit() == null ? "text.muted" : "text.primary");
        name.setEllipsis(true);
        line.add(name).growX().minWidth(0);
        Label share = kit.typeLabel(Messages.getString("Lobby.randomArmy.share", row.share()), "small",
              "text.secondary");
        share.setAlignment(Align.right);
        line.add(share).width(size("control.lg")).padLeft(space("sm"));
        Label bv = kit.typeLabel(row.unit() == null ? "" : String.valueOf(row.bv()), "small", "text.muted");
        bv.setAlignment(Align.right);
        line.add(bv).width(size("control.lg")).padLeft(space("xs"));
        if (row.unit() != null) {
            UiButton inspect = kit.button("button-quiet" + UiTheme.COMPACT, "info", null, null);
            inspect.setName("army-rat-readout-" + index);
            kit.tip(inspect, true).getActor().setText(Messages.getString("Lobby.randomArmy.readout"));
            UiKit.onChange(inspect, () -> owner.readout(row.unit()));
            line.add(inspect).padLeft(space("xs"));
            UiButton take = kit.button("button-quiet" + UiTheme.COMPACT, "add", null, null);
            take.setName("army-take-" + index);
            kit.tip(take, true).getActor().setText(Messages.getString("Lobby.randomArmy.take"));
            UiKit.onChange(take, () -> owner.take(row.unit()));
            line.add(take).padLeft(space("xs"));
        } else {
            line.add().size(size("control.sm")).padLeft(space("xs"));
            line.add().size(size("control.sm")).padLeft(space("xs"));
        }
        return line;
    }

    /** {@code table}'s rows with their share of its weight; on the Swing thread, which builds tables. */
    static List<Row> rows(@Nullable UnitTable table) {
        if (table == null) {
            return List.of();
        }
        int total = 0;
        for (int index = 0; index < table.getNumEntries(); index++) {
            total += table.getEntryWeight(index);
        }
        List<Row> read = new ArrayList<>();
        for (int index = 0; index < table.getNumEntries(); index++) {
            int share = total == 0 ? 0 : Math.round(table.getEntryWeight(index) * 100f / total);
            read.add(new Row(table.getEntryText(index), share, table.getBV(index), table.getMekSummary(index)));
        }
        return read;
    }

    /** A choice the faction table is built of changed: the table is read again when it shows. */
    private void tableChanged(boolean rebuild) {
        rows = null;
        asked++;
        owner.changed(rebuild);
    }

    // ---- Formation ----

    /** The formation's faction, unit type, formation, additional units and network. */
    Table formation(FormationForm form) {
        Table pane = page("Lobby.randomArmy.mode.formation", "Lobby.randomArmy.about.formation");
        factionBlock(pane, form.factions());
        pane.add(unitTypeField(FormationForm.UNIT_TYPES, form.unitType(), type -> {
            form.unitType(type);
            owner.changed(true);
        })).padTop(space("lg")).row();

        List<FormationType> allowed = new ArrayList<>();
        for (FormationForm.Group group : form.groups()) {
            if (group.head() != null && form.allowed(group.head())) {
                allowed.add(group.head());
            }
            for (FormationType member : group.members()) {
                if (form.allowed(member)) {
                    allowed.add(member);
                }
            }
        }
        if (allowed.isEmpty()) {
            pane.add(kit.typeLabel(Messages.getString("Lobby.randomArmy.noFormation"), "small", "text.muted"))
                  .padTop(space("md")).row();
        } else {
            String[] names = new String[allowed.size()];
            for (int index = 0; index < names.length; index++) {
                names[index] = allowed.get(index).getName();
            }
            UiSelect formation = kit.tokenSelect(true, names);
            formation.setName("army-formation");
            formation.select(Math.max(0, allowed.indexOf(form.formation())));
            UiKit.onChange(formation, () -> {
                form.formation(formation.getSelected());
                owner.changed(false);
            });
            pane.add(kit.formField(Messages.getString("Lobby.randomArmy.formation"), formation)).growX()
                  .padTop(space("md")).row();
        }

        FormationForm.Extra[] extras = FormationForm.Extra.values();
        String[] extraNames = new String[extras.length];
        for (int index = 0; index < extras.length; index++) {
            extraNames[index] = Messages.getString(extras[index].key());
        }
        UiSelect extra = kit.tokenSelect(true, extraNames);
        extra.setName("army-formation-extra");
        extra.select(form.extra().ordinal());
        UiKit.onChange(extra, () -> {
            form.extra(extras[extra.getSelectedIndex()]);
            owner.changed(true);
        });
        pane.add(kit.formField(Messages.getString("Lobby.randomArmy.extras"), extra)).growX().padTop(space("md"))
              .row();
        if (form.extra() != FormationForm.Extra.SIMPLE) {
            UiStepper count = kit.stepper(form.extraCount(), 0, FormationForm.MAX_EXTRA);
            count.setName("army-formation-extra-count");
            UiKit.onChange(count, () -> {
                form.extraCount(count.value());
                owner.changed(false);
            });
            pane.add(kit.formField(Messages.getString("Lobby.randomArmy.extraCount"), count)).padTop(space("md"))
                  .row();
            if (form.extra() == FormationForm.Extra.OTHER) {
                List<Integer> types = FormationForm.OTHER_TYPES;
                String[] names = new String[types.size()];
                for (int index = 0; index < names.length; index++) {
                    names[index] = UnitType.getTypeDisplayableName(types.get(index));
                }
                UiSelect other = kit.tokenSelect(true, names);
                other.setName("army-formation-other");
                other.select(Math.max(0, types.indexOf(form.otherType())));
                UiKit.onChange(other, () -> form.otherType(types.get(other.getSelectedIndex())));
                pane.add(kit.formField(Messages.getString("Lobby.randomArmy.otherType"), other)).growX()
                      .padTop(space("md")).row();
            }
        }

        List<RatGeneratorForm.Choice<Integer>> networks = form.networks();
        if (!networks.isEmpty()) {
            pane.add(networkField(networks, form.network(), mask -> {
                form.network(mask);
                owner.changed(false);
            })).padTop(space("md")).row();
        }
        return pane;
    }

    // ---- Shared ----

    /** Waits for the RAT Generator's data, which the faction and formation generators are built of. */
    Table loading(String titleKey) {
        Table pane = page(titleKey, null);
        Label wait = kit.typeLabel(Messages.getString("Lobby.randomArmy.loadingFactions"), "body", "text.muted");
        wait.setName("army-loading");
        wait.setWrap(true);
        pane.add(wait).growX().padTop(space("lg")).row();
        return pane;
    }

    /** The year, faction, command and rating a faction's units are generated for. */
    private void factionBlock(Table pane, FactionChoice factions) {
        UiTextField year = kit.input(String.valueOf(factions.year()), true);
        year.setName("army-year");
        year.setMaxLength(4);
        year.setTextFieldFilter(new TextField.TextFieldFilter.DigitsOnlyFilter());
        UiKit.onChange(year, () -> {
            Integer typed = UiRangeInput.parse(year.getText());
            if (typed != null && typed >= FactionChoice.EARLIEST_YEAR && typed <= FactionChoice.LATEST_YEAR
                  && typed != factions.year()) {
                factions.year(typed);
                focusYear = true;
                tableChanged(true);
            }
        });
        if (focusYear) {
            // Built anew while the year is typed: the new field takes over the typing
            focusYear = false;
            year.addAction(Actions.run(() -> {
                Stage stage = year.getStage();
                if (stage != null) {
                    stage.setKeyboardFocus(year);
                    year.setCursorPosition(year.getText().length());
                }
            }));
        }
        Table yearRow = new Table();
        yearRow.left();
        yearRow.add(year).width(size("control.lg") * 2);
        yearRow.add(kit.typeLabel(Messages.getString("Lobby.randomArmy.yearRange", FactionChoice.EARLIEST_YEAR,
              FactionChoice.LATEST_YEAR), "small", "text.muted")).padLeft(space("sm"));
        pane.add(kit.formField(Messages.getString("Lobby.randomArmy.year"), yearRow)).padTop(space("md")).row();

        List<FactionRecord> offered = factions.factions();
        if (offered.isEmpty()) {
            pane.add(kit.typeLabel(Messages.getString("Lobby.randomArmy.cannotDeal.faction"), "small",
                  "status.warning")).padTop(space("md")).row();
        } else {
            String[] names = new String[offered.size()];
            for (int index = 0; index < names.length; index++) {
                names[index] = offered.get(index).getName(factions.year());
            }
            UiSelect faction = kit.tokenSelect(true, names);
            faction.setName("army-faction");
            faction.select(Math.max(0, offered.indexOf(factions.faction())));
            UiKit.onChange(faction, () -> {
                factions.faction(offered.get(faction.getSelectedIndex()).getKey());
                tableChanged(true);
            });
            pane.add(kit.formField(Messages.getString("Lobby.randomArmy.faction"), faction)).growX()
                  .padTop(space("md")).row();
        }
        var minor = kit.toggleSwitch(Messages.getString("RandomArmyDialog.ShowMinorFactions"),
              factions.showMinor());
        minor.setName("army-minor");
        UiKit.onChange(minor, () -> {
            factions.showMinor(minor.isChecked());
            tableChanged(true);
        });
        pane.add(minor).fill(0f, 0f).padTop(space("xs")).row();

        List<FactionRecord> commands = factions.commands();
        if (!commands.isEmpty()) {
            String[] names = new String[commands.size() + 1];
            names[0] = Messages.getString("Lobby.randomArmy.generalCommand");
            for (int index = 0; index < commands.size(); index++) {
                names[index + 1] = commands.get(index).getName(factions.year());
            }
            UiSelect command = kit.tokenSelect(true, names);
            command.setName("army-command");
            command.select(factions.command() == null ? 0 : commands.indexOf(factions.command()) + 1);
            UiKit.onChange(command, () -> {
                int index = command.getSelectedIndex();
                factions.command(index <= 0 ? null : commands.get(index - 1).getKey());
                tableChanged(true);
            });
            pane.add(kit.formField(Messages.getString("Lobby.randomArmy.command"), command)).growX()
                  .padTop(space("md")).row();
        }

        List<String> ratings = factions.ratings();
        if (!ratings.isEmpty()) {
            String[] names = ratings.toArray(String[]::new);
            int chosen = Math.max(0, ratings.indexOf(factions.rating()));
            Actor rating;
            if (fitsSegments(names)) {
                UiSegmented segments = kit.tokenSegmented(true, names);
                segments.select(chosen);
                UiKit.onChange(segments, () -> {
                    factions.rating(ratings.get(segments.getSelectedIndex()));
                    tableChanged(false);
                });
                rating = segments;
            } else {
                UiSelect select = kit.tokenSelect(true, names);
                select.select(chosen);
                UiKit.onChange(select, () -> {
                    factions.rating(ratings.get(select.getSelectedIndex()));
                    tableChanged(false);
                });
                rating = select;
            }
            rating.setName("army-rating");
            pane.add(kit.formField(Messages.getString("Lobby.randomArmy.rating"), rating)).growX()
                  .padTop(space("md")).row();
        }
    }

    private static boolean fitsSegments(String[] ratings) {
        if (ratings.length > SEGMENTED_RATINGS) {
            return false;
        }
        for (String rating : ratings) {
            if (rating.length() > SEGMENTED_RATING_LENGTH) {
                return false;
            }
        }
        return true;
    }

    private Actor unitTypeField(List<Integer> types, int chosen, IntConsumer choose) {
        String[] names = new String[types.size()];
        for (int index = 0; index < names.length; index++) {
            names[index] = UnitType.getTypeDisplayableName(types.get(index));
        }
        UiSelect select = kit.tokenSelect(true, names);
        select.setName("army-unit-type");
        select.select(Math.max(0, types.indexOf(chosen)));
        UiKit.onChange(select, () -> choose.accept(types.get(select.getSelectedIndex())));
        return kit.formField(Messages.getString("Lobby.randomArmy.unitType"), select);
    }

    private Actor networkField(List<RatGeneratorForm.Choice<Integer>> networks, int chosen,
          IntConsumer choose) {
        String[] names = new String[networks.size()];
        int index = 0;
        int selected = 0;
        for (RatGeneratorForm.Choice<Integer> network : networks) {
            if (network.value() == chosen) {
                selected = index;
            }
            names[index++] = Messages.getString(network.key());
        }
        UiSelect select = kit.tokenSelect(true, names);
        select.setName("army-network");
        select.select(selected);
        UiKit.onChange(select, () -> choose.accept(networks.get(select.getSelectedIndex()).value()));
        return kit.formField(Messages.getString("Lobby.randomArmy.network"), select);
    }

    /** A pane: its title and, when there is one, a line on what the generator does. */
    private Table page(String titleKey, @Nullable String aboutKey) {
        Table pane = new Table();
        pane.top().left().defaults().left().growX();
        pane.add(kit.roleTypeLabel("overline", Messages.getString(titleKey), "text.secondary")).row();
        if (aboutKey != null) {
            Label about = kit.typeLabel(Messages.getString(aboutKey), "small", "text.muted");
            about.setWrap(true);
            pane.add(about).padTop(space("xs")).row();
        }
        return pane;
    }

    private static HorizontalGroup chipGroup() {
        HorizontalGroup group = new HorizontalGroup();
        group.wrap(true).space(space("sm")).wrapSpace(space("xs")).rowLeft();
        return group;
    }

    private static String strip(String key) {
        String text = Messages.getString(key);
        return text.endsWith(":") ? text.substring(0, text.length() - 1) : text;
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static float size(String name) {
        return DesignTokens.shipped().size().get(name);
    }
}
