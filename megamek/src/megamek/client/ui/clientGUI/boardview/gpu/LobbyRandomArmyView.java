/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Value;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Scaling;
import jakarta.annotation.Nullable;
import megamek.client.lobby.BalancedArmy;
import megamek.client.lobby.BattleValueArmy;
import megamek.client.lobby.FactionChoice;
import megamek.client.lobby.ForceGeneratorForm;
import megamek.client.lobby.FormationForm;
import megamek.client.lobby.RandomArmyForm;
import megamek.client.lobby.RatBrowser;
import megamek.client.lobby.RatGeneratorForm;
import megamek.client.lobby.RequisitionAdvancedSearch;
import megamek.client.ratgenerator.FactionRecord;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiKit.TokenSearch;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiStepper;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.loaders.MekSummary;
import megamek.common.loaders.MekSummaryCache;
import megamek.common.units.Entity;
import megamek.common.units.UnitType;

/**
 * The lobby's random army, dealt as unit cards. Across the top, the generators the hand is dealt by: the random
 * assignment tables, a Battle Value army, a balanced lance, a faction's table and a formation. On the left, the
 * generator's choices: the tables to roll on, browsed a folder at a time (source, faction, command, unit type) or
 * searched across all of them by words of their names and folders, or the other generators' panes
 * ({@link LobbyArmyGeneratorPanes}); on the right, what the hand is dealt by, the unit count and the player; Roll deals
 * the hand, rerolling every card not kept; each card shows its unit's sprite, Battle Value and tonnage and can be kept,
 * rerolled or discarded, unless the generator deals a whole formation; the army's totals over the hand. Add to game
 * sends the hand. The Battle Value army and the balanced lance deal from units the unit filter finds, the Requisition
 * view's advanced search shown in the random army's place. The force generator rolls a whole command, organised as
 * its faction would, into the hand's place ({@link LobbyForceGeneratorPane}). The rolls are a
 * {@link RandomArmyForm}'s, the browsing a {@link RatBrowser}'s.
 * <p>
 * GPU thread. The rolls and the adding run on the Swing thread through {@link Host#edt}.
 */
final class LobbyRandomArmyView {
    /** The generators that need nothing loaded first. */
    record Generators(BattleValueArmy battleValue, BalancedArmy balanced) { }

    /** The generators built of the RAT Generator's data, which loads a while. */
    record Factions(RatGeneratorForm table, FormationForm formation, ForceGeneratorForm force) { }

    /** What the hand is dealt by, with the Swing random army's tab for it, whose last choice both remember. */
    enum Mode {
        TABLES("die", "tables", 2),
        BATTLE_VALUE("military-tech", "battleValue", 0),
        BALANCED("balance", "balanced", 1),
        FACTION("flag", "faction", 3),
        FORMATION("account-tree", "formation", 4),
        FORCE("layers", "force", 5);

        private final String icon;
        private final String key;
        private final int swingTab;

        Mode(String icon, String key, int swingTab) {
            this.icon = icon;
            this.key = key;
            this.swingTab = swingTab;
        }

        /** The mode the Swing random army's {@code tab} deals by; the tables for none. */
        static Mode ofSwingTab(int tab) {
            for (Mode each : values()) {
                if (each.swingTab == tab) {
                    return each;
                }
            }
            return TABLES;
        }

        int swingTab() {
            return swingTab;
        }

        String title() {
            return Messages.getString("Lobby.randomArmy.mode." + key);
        }

        String about() {
            return Messages.getString("Lobby.randomArmy.about." + key);
        }

        /** Whether the generator is built of the RAT Generator's data. */
        boolean needsFactions() {
            return this == FACTION || this == FORMATION || this == FORCE;
        }
    }

    /** What the view asks of the window. */
    interface Host {
        /** Runs {@code work} on the Swing thread, then {@code then} back on the GPU thread. */
        void edt(Runnable work, Runnable then);

        /** Swing thread: adds the hand to the game for the chosen player. */
        void add(RandomArmyForm form);

        /**
         * Rolls a force into {@code force}'s command: built on the Swing thread, generated off it, folded in on it;
         * {@code done} hears on the GPU thread whether it found units.
         */
        default void generate(ForceGeneratorForm force, Consumer<Boolean> done) {
            done.accept(false);
        }

        /** Swing thread: adds the force generator's command, as far as it is included, for the chosen player. */
        default void addForce(ForceGeneratorForm force, RandomArmyForm form) { }

        /** Writes the force generator's command to a unit list the player chooses. */
        default void exportForce(ForceGeneratorForm force) { }

        /** Asks {@code text}; {@code yes} runs on the GPU thread once the player agreed. */
        default void confirm(String title, String text, Runnable yes) { }

        /** Tells the player {@code text}. */
        default void message(String title, String text) { }

        /** Opens a generated unit's readout over the random army. */
        default void readout(Entity unit) { }

        /** Asks for the sprites of {@code units}, which arrive through {@link #spriteArrived}. */
        void sprites(List<MekSummary> units);

        /**
         * Asks for the faction table and formation generators, built once the RAT Generator has its data; they reach
         * {@code ready} on the GPU thread.
         */
        void factions(Consumer<Factions> ready);

        /** Swing thread: remembers the faction and unit type a hand is dealt for, for the next random army. */
        void remember(FactionChoice factions, int unitType);

        /** The Swing random army's tab last dealt by, as its preference keeps it, or -1. */
        default int lastTab() {
            return -1;
        }

        /** Remembers the mode the hand is dealt by as the Swing random army's {@code tab}. */
        default void lastTab(int tab) { }

        /** Opens {@code unit}'s readout over the random army. */
        default void readout(MekSummary unit) { }
    }

    /** Deals nothing while the RAT Generator loads. */
    private static final RandomArmyForm.Dealer LOADING = new RandomArmyForm.Dealer() {
        @Override
        public boolean canDeal() {
            return false;
        }

        @Override
        public List<MekSummary> deal(int count, List<MekSummary> kept) {
            return List.of();
        }

        @Override
        public @Nullable MekSummary replace(MekSummary unit, List<MekSummary> rest) {
            return null;
        }
    };

    private final UiKit kit;
    private final RandomArmyForm form;
    private final RatBrowser browser;
    private final Generators generators;
    private final Host host;
    private final Runnable close;
    private final Function<String, Drawable> sprites;
    private final float cardWidth;
    private final int columns;
    private final Table main = new Table();
    private final Cell<Actor> bodyCell;
    private final Map<Mode, UiButton> tiles = new EnumMap<>(Mode.class);
    private final Table tablesPanel;
    private final Container<Actor> leftPane = new Container<>();
    private final LobbyArmyGeneratorPanes panes;
    private final Table hand = new Table();
    private final Table list = new Table();
    private final ScrollPane listScroll;
    private final Label place;
    private final Label where;
    private final Label found;
    private final UiButton back;
    private final Label backTip;
    private final TokenSearch search;
    private final Label rollingOn;
    private final Label chosen;
    private final Label chosenPath;
    private final float countWidth;
    private final Actor countField;
    private final Cell<Actor> countCell;
    private final Label totals;
    private final UiButton add;
    private final Table deal;
    private final Cell<Actor> dealCell;
    private final float handWidth;
    private @Nullable LobbyForceGeneratorPane forcePane;
    private @Nullable ScrollPane forceScroll;
    private final UiButton roll;
    private final UiButton clear;
    private final List<Image> images = new ArrayList<>();
    /** Every card's keep, reroll and discard buttons, which must not change the hand while a roll runs. */
    private final List<UiButton> cardControls = new ArrayList<>();
    private Mode mode = Mode.TABLES;
    private @Nullable Factions factions;
    private RequisitionAdvancedSearch criteria = RequisitionAdvancedSearch.NONE;
    private @Nullable LobbyAdvancedSearchView filter;
    private @Nullable ScrollPane paneScroll;
    private @Nullable Mode paneMode;
    private boolean busy;
    private boolean syncing;

    LobbyRandomArmyView(UiKit kit, UiDialog dialog, RandomArmyForm form, RatBrowser browser, Generators generators,
          float bodyWidth, Function<String, Drawable> sprites, Host host, Runnable close) {
        this.kit = kit;
        this.form = form;
        this.browser = browser;
        this.generators = generators;
        this.host = host;
        this.close = close;
        this.sprites = sprites;
        float gap = space("md");
        float paneGap = space("xl");
        float browserWidth = size("dialog.sm");
        handWidth = Math.max(size("control.lg") * 5, bodyWidth - browserWidth - paneGap);
        columns = Math.max(1, (int) ((handWidth + gap) / (size("control.lg") * 5 + gap)));
        cardWidth = (handWidth - gap * (columns - 1)) / columns;
        Table body = dialog.body();
        body.top().left();
        main.top().left();

        // The generators, a tile each, as a game's modes are chosen
        Table strip = new Table();
        strip.setName("army-modes");
        strip.left();
        for (Mode each : Mode.values()) {
            UiButton tile = kit.button("button-toggle", each.icon, each.title(), null);
            tile.setName("army-mode-" + each.key);
            tile.setProgrammaticChangeEvents(false);
            kit.tip(tile, true).getActor().setText(each.about());
            UiKit.onChange(tile, () -> mode(each));
            tiles.put(each, tile);
            strip.add(tile).uniformX().fillX().padRight(space("sm"));
        }
        main.add(strip).colspan(2).left().padBottom(space("lg")).row();

        // The tables, a folder at a time: source, faction, command, unit type; or searched across them all
        tablesPanel = kit.plainPanel();
        tablesPanel.setName("army-browser");
        tablesPanel.top().left().defaults().left().growX();
        Table head = new Table();
        head.add(kit.roleTypeLabel("overline", Messages.getString("Lobby.randomArmy.tables"), "text.secondary"))
              .left();
        head.add(kit.typeLabel(Messages.getString("Lobby.randomArmy.tableCount", browser.size()), "small",
              "text.muted")).left().padLeft(space("sm")).growX();
        tablesPanel.add(head).row();
        search = kit.tokenSearch(Messages.getString("Lobby.randomArmy.search"), true);
        search.setName("army-search");
        search.field.setName("army-search-field");
        kit.tip(search.field, true).getActor().setText(Messages.getString("Lobby.randomArmy.searchTip"));
        UiKit.onChange(search.field, () -> {
            if (!syncing) {
                browser.search(search.field.getText());
                fillBrowser();
            }
        });
        tablesPanel.add(search).padTop(space("sm")).row();
        Table crumbs = new Table();
        back = kit.button("button-quiet-compact", "chevron-left", null, null);
        back.setName("army-up");
        backTip = kit.tip(back, true).getActor();
        UiKit.onChange(back, () -> {
            if (browser.searching()) {
                clearSearch();
            } else {
                browser.up();
            }
            fillBrowser();
        });
        crumbs.add(back).padRight(space("xs"));
        Table titles = new Table();
        titles.left().defaults().left().growX().minWidth(0);
        place = kit.typeLabel("", "body.strong", "text.primary");
        place.setName("army-place");
        place.setEllipsis(true);
        titles.add(place).row();
        where = kit.typeLabel("", "small", "text.muted");
        where.setName("army-where");
        where.setEllipsis(true);
        titles.add(where).row();
        crumbs.add(titles).growX().minWidth(0);
        tablesPanel.add(crumbs).padTop(space("sm")).row();
        list.top().left();
        countWidth = kit.typeLabel(String.valueOf(browser.size()), "small", "text.muted").getPrefWidth();
        listScroll = kit.scrollList(list, true, false);
        listScroll.setScrollingDisabled(true, false);
        listScroll.setName("army-table-list");
        tablesPanel.add(listScroll).height(size("dialog.sm")).padTop(space("xs")).row();
        found = kit.typeLabel("", "small", "text.muted");
        found.setName("army-matches");
        found.setWrap(true);
        tablesPanel.add(found).padTop(space("xs")).row();
        // Every generator's pane takes the tables' place and height, so the hand stays where it is
        leftPane.fill().top();
        leftPane.setName("army-pane");
        main.add(leftPane).width(browserWidth).height(tablesPanel.getPrefHeight()).top();

        deal = new Table();
        deal.top().left().defaults().left().growX();
        rollingOn = kit.roleTypeLabel("overline", "", "text.secondary");
        rollingOn.setName("army-rolling-on");
        deal.add(rollingOn).row();
        chosen = kit.typeLabel("", "title", "text.primary");
        chosen.setName("army-chosen");
        chosen.setEllipsis(true);
        deal.add(chosen).minWidth(0).padTop(space("xs")).row();
        chosenPath = kit.typeLabel("", "small", "text.muted");
        chosenPath.setName("army-chosen-path");
        chosenPath.setEllipsis(true);
        deal.add(chosenPath).minWidth(0).row();

        Table controls = new Table();
        controls.bottom().left();
        UiStepper count = kit.stepper(form.count(), 1, RandomArmyForm.MAX_UNITS);
        count.setName("army-count");
        count.up().setName("army-count-up");
        count.down().setName("army-count-down");
        UiKit.onChange(count, () -> form.count(count.value()));
        countField = kit.formField(strip("RandomArmyDialog.Unit"), count);
        countCell = controls.add(countField).padRight(gap);
        if (form.players().size() > 1) {
            UiSelect player = kit.tokenSelect(true, form.players().toArray(String[]::new));
            player.setName("army-player");
            player.select(form.player());
            UiKit.onChange(player, () -> form.player(player.getSelectedIndex()));
            controls.add(kit.formField(strip("RandomArmyDialog.Player"), player)).padRight(gap)
                  .width(size("control.lg") * 5);
        }
        roll = kit.button("button-secondary", "replay", Messages.getString("RandomArmyDialog.Roll"), null);
        roll.setName("army-roll");
        UiKit.onChange(roll, this::roll);
        controls.add(roll).bottom();
        controls.add().growX();
        deal.add(controls).padTop(space("md")).row();

        totals = kit.typeLabel("", "title", "accent.primary");
        totals.setName("army-totals");
        deal.add(totals).padTop(space("lg")).row();
        hand.top().left();
        deal.add(hand).padTop(space("sm")).row();
        dealCell = main.add((Actor) deal).width(handWidth).top().padLeft(paneGap);
        bodyCell = body.add(main);
        body.row();

        panes = new LobbyArmyGeneratorPanes(kit, new LobbyArmyGeneratorPanes.Owner() {
            @Override
            public void changed(boolean rebuild) {
                if (rebuild) {
                    showPane();
                }
                showChosen();
            }

            @Override
            public void edt(Runnable work, Runnable then) {
                host.edt(work, then);
            }

            @Override
            public void take(MekSummary unit) {
                LobbyRandomArmyView.this.take(unit);
            }

            @Override
            public void readout(MekSummary unit) {
                host.readout(unit);
            }

            @Override
            public RequisitionAdvancedSearch criteria() {
                return criteria;
            }

            @Override
            public void openFilter() {
                LobbyRandomArmyView.this.openFilter();
            }

            @Override
            public void clearFilter() {
                LobbyRandomArmyView.this.criteria(RequisitionAdvancedSearch.NONE);
                showPane();
                showChosen();
            }
        });

        clear = dialog.secondary(Messages.getString("RandomArmyDialog.Clear"), () -> {
            if (busy) {
                return;
            }
            if (mode == Mode.FORCE) {
                clearForce();
                return;
            }
            form.clear();
            rebuild();
        });
        clear.setName("army-clear");
        add = dialog.primary(Messages.getString("Lobby.randomArmy.add"), this::confirm);
        add.setName("army-add");
        dialog.cancelButton(Messages.getString("Cancel.text")).setName("army-cancel");
        browser.locate(form.table());
        fillBrowser();
        mode(Mode.ofSwingTab(host.lastTab()));
        host.factions(this::factionsReady);
    }

    // ---- The generators ----

    /** Deals the hand by {@code next}: its tile lit, its pane on the left, its dealer the form's. */
    private void mode(Mode next) {
        if (!busy) {
            if (next != mode) {
                host.lastTab(next.swingTab());
            }
            mode = next;
            form.dealer(dealer());
            countCell.setActor(form.dealer() == null || form.dealer().usesCount() ? countField : null);
            showDeal();
            showPane();
            showChosen();
            rebuild();
        }
        tiles.forEach((each, tile) -> tile.setChecked(each == mode));
    }

    private @Nullable RandomArmyForm.Dealer dealer() {
        return switch (mode) {
            case TABLES -> null;
            case BATTLE_VALUE -> generators.battleValue();
            case BALANCED -> generators.balanced();
            case FACTION -> factions == null ? LOADING : factions.table();
            case FORMATION -> factions == null ? LOADING : factions.formation();
            // The force generator deals no hand: its command shows in the hand's place
            case FORCE -> LOADING;
        };
    }

    /** Shows on the right the hand, or the force generator's command in its place, as tall as the left pane. */
    private void showDeal() {
        if (mode == Mode.FORCE && factions != null) {
            if (forcePane == null) {
                forcePane = forcePane(factions.force());
                forceScroll = kit.scrollList(forcePane.actor(), true, false);
                forceScroll.setScrollingDisabled(true, false);
                forceScroll.setName("army-force-scroll");
            }
            dealCell.setActor(forceScroll).height(tablesPanel.getPrefHeight());
            return;
        }
        dealCell.setActor(deal).minHeight(Value.minHeight).prefHeight(Value.prefHeight).maxHeight(Value.zero);
    }

    private LobbyForceGeneratorPane forcePane(ForceGeneratorForm force) {
        Actor player = null;
        if (form.players().size() > 1) {
            UiSelect select = kit.tokenSelect(true, form.players().toArray(String[]::new));
            select.setName("army-force-player");
            select.select(form.player());
            UiKit.onChange(select, () -> {
                form.player(select.getSelectedIndex());
                refresh();
            });
            player = kit.formField(strip("RandomArmyDialog.Player"), select);
        }
        return new LobbyForceGeneratorPane(kit, force, handWidth - space("lg"), player,
              new LobbyForceGeneratorPane.Owner() {
                  @Override
                  public void edt(Runnable work, Runnable then) {
                      host.edt(work, then);
                  }

                  @Override
                  public void generate(ForceGeneratorForm form, Consumer<Boolean> done) {
                      host.generate(form, done);
                  }

                  @Override
                  public void confirm(String title, String text, Runnable yes) {
                      host.confirm(title, text, yes);
                  }

                  @Override
                  public void message(String title, String text) {
                      host.message(title, text);
                  }

                  @Override
                  public void readout(Entity unit) {
                      host.readout(unit);
                  }

                  @Override
                  public void export(ForceGeneratorForm form) {
                      host.exportForce(form);
                  }

                  @Override
                  public void busy(boolean next) {
                      busy = next;
                      leftPane.setTouchable(next ? Touchable.disabled : Touchable.enabled);
                      refresh();
                  }
              });
    }

    /** Empties the force generator's command, on the Swing thread its edits run on. */
    private void clearForce() {
        if (factions == null || forcePane == null || forcePane.busy()) {
            return;
        }
        ForceGeneratorForm force = factions.force();
        busy = true;
        refresh();
        host.edt(force::clear, () -> {
            busy = false;
            forcePane.cleared();
            refresh();
        });
    }

    /** The faction table and formation generators have arrived: their modes deal by them now. */
    private void factionsReady(Factions ready) {
        factions = ready;
        if (mode.needsFactions()) {
            mode(mode);
        }
    }

    /** Shows the generator's choices on the left, built anew, where they were scrolled to. */
    private void showPane() {
        if (mode == Mode.TABLES) {
            paneScroll = null;
            paneMode = null;
            leftPane.setActor(tablesPanel);
            return;
        }
        float scrollY = paneScroll != null && paneMode == mode ? paneScroll.getScrollY() : 0;
        Table pane = switch (mode) {
            case BATTLE_VALUE -> panes.battleValue(generators.battleValue());
            case BALANCED -> panes.balanced(generators.balanced());
            case FACTION -> factions == null ? panes.loading("Lobby.randomArmy.mode.faction")
                  : panes.faction(factions.table());
            case FORMATION -> factions == null ? panes.loading("Lobby.randomArmy.mode.formation")
                  : panes.formation(factions.formation());
            case FORCE -> factions == null ? panes.loading("Lobby.randomArmy.mode.force")
                  : panes.force(factions.force());
            case TABLES -> throw new IllegalStateException();
        };
        Table panel = kit.plainPanel();
        panel.setName("army-generator");
        panel.top().left();
        ScrollPane scroll = kit.scrollList(pane, true, false);
        scroll.setScrollingDisabled(true, false);
        scroll.setName("army-generator-scroll");
        panel.add(scroll).grow().minHeight(0);
        leftPane.setActor(panel);
        if (scrollY > 0) {
            // Laid out on the next frame: the new pane scrolls to where the old one was then
            scroll.addAction(Actions.run(() -> {
                scroll.layout();
                scroll.setScrollY(scrollY);
                scroll.updateVisualScroll();
            }));
        }
        paneScroll = scroll;
        paneMode = mode;
    }

    /** Rolls the hand on the Swing thread, remembering a faction's choices for the next time. */
    private void roll() {
        RatGeneratorForm table = mode == Mode.FACTION && factions != null ? factions.table() : null;
        FormationForm formation = mode == Mode.FORMATION && factions != null ? factions.formation() : null;
        run(() -> {
            if (table != null) {
                host.remember(table.factions(), table.unitType());
            } else if (formation != null) {
                host.remember(formation.factions(), formation.unitType());
            }
            form.roll();
        });
    }

    /** Puts a faction table's unit in the hand. */
    private void take(MekSummary unit) {
        if (!busy && form.take(unit)) {
            rebuild();
        }
    }

    // ---- The unit filter ----

    /** Shows the unit filter in the random army's place, as large. */
    private void openFilter() {
        if (busy) {
            return;
        }
        if (filter == null) {
            filter = new LobbyAdvancedSearchView(kit, new LobbyAdvancedSearchView.Host() {
                @Override
                public RequisitionAdvancedSearch criteria() {
                    return criteria;
                }

                @Override
                public void criteria(RequisitionAdvancedSearch next) {
                    LobbyRandomArmyView.this.criteria(next);
                }

                @Override
                public void done() {
                    closeFilter();
                }
            });
            filter.actor().setName("army-filter-view");
        } else {
            filter.sync(criteria);
        }
        filterMatches();
        bodyCell.setActor(filter.actor()).size(main.getWidth(), main.getHeight());
    }

    /** Back to the random army, its pane showing the filter now chosen. */
    private void closeFilter() {
        bodyCell.setActor(main).minWidth(Value.minWidth).prefWidth(Value.prefWidth).maxWidth(Value.zero)
              .minHeight(Value.minHeight).prefHeight(Value.prefHeight).maxHeight(Value.zero);
        showPane();
        showChosen();
    }

    /** Deals the Battle Value army and the balanced lance from the units {@code next} finds. */
    private void criteria(RequisitionAdvancedSearch next) {
        criteria = next;
        Predicate<MekSummary> finds = null;
        if (!next.isEmpty()) {
            MekSummaryCache cache = MekSummaryCache.getInstance();
            finds = unit -> next.matches(unit, unit.isBattlefieldSupportAsset() ? unit : cache.getLinkedAsset(unit));
        }
        generators.battleValue().filter(finds);
        generators.balanced().filter(finds);
        filterMatches();
    }

    /** Shows on the filter how many units the shown generator can be dealt. */
    private void filterMatches() {
        if (filter != null) {
            BattleValueArmy army = generators.battleValue();
            filter.matches(mode == Mode.BALANCED ? generators.balanced().available()
                  : army.available(BattleValueArmy.Kind.MEK) + army.available(BattleValueArmy.Kind.VEHICLE));
        }
    }

    // ---- The tables ----

    /** Shows the open folder's rows, or the search's, with where the browser is above them. */
    private void fillBrowser() {
        list.clearChildren();
        List<RatBrowser.Entry> entries = browser.entries();
        boolean searching = browser.searching();
        List<String> path = browser.path();
        String all = Messages.getString("Lobby.randomArmy.allTables");
        String open = path.isEmpty() ? all : path.get(path.size() - 1);
        if (searching) {
            place.setText(Messages.getString("Lobby.randomArmy.matches", browser.matches()));
            where.setText(Messages.getString("Lobby.randomArmy.back", open));
        } else {
            place.setText(open);
            // The line under the open folder says where it is, so the folder's name keeps its place by the arrow
            where.setText(path.isEmpty() ? Messages.getString("Lobby.randomArmy.sources", entries.size())
                  : path.size() == 1 ? all : String.join(" \u203A ", path.subList(0, path.size() - 1)));
        }
        back.setDisabled(!searching && !browser.canGoUp());
        // The back button ends a search, back to the open folder, or goes up a folder
        backTip.setText(Messages.getString("Lobby.randomArmy.back", searching ? open
              : path.size() < 2 ? all : path.get(path.size() - 2)));
        int folders = 0;
        int rats = 0;
        for (RatBrowser.Entry entry : entries) {
            list.add(entry.folder() ? folderRow(entry, folders++) : tableRow(entry, rats++, searching)).growX()
                  .row();
        }
        if (entries.isEmpty()) {
            kit.empty(list, Messages.getString("Lobby.randomArmy.noMatches"), true);
        }
        found.setText(searching && browser.matches() > entries.size()
              ? Messages.getString("Lobby.randomArmy.moreMatches", entries.size(), browser.matches()) : "");
        listScroll.layout();
        listScroll.setScrollY(0);
    }

    private UiButton folderRow(RatBrowser.Entry entry, int index) {
        UiButton row = kit.button(UiTheme.TOKEN_ROW, "folder", entry.title(), null);
        row.setName("army-folder-" + index);
        row.getLabel().setAlignment(Align.left);
        row.getLabel().setEllipsis(true);
        row.getCell(row.getLabel()).growX().minWidth(0);
        if (entry.era() != null) {
            row.add(kit.typeLabel(entry.era(), "small", "text.secondary")).padLeft(space("sm"));
        }
        // The counts take a column of their own, so the years before them end in a line too
        Label count = kit.typeLabel(String.valueOf(entry.tables()), "small", "text.muted");
        count.setAlignment(Align.right);
        row.add(count).width(countWidth).padLeft(space("sm"));
        row.add(kit.icon("chevron-right", size("icon.sm"), UiTheme.tokenColor("text.muted"))).size(size("icon.sm"))
              .padLeft(space("xs"));
        UiKit.onChange(row, () -> {
            browser.open(entry.name());
            fillBrowser();
        });
        return row;
    }

    private UiButton tableRow(RatBrowser.Entry entry, int index, boolean searching) {
        UiButton row = kit.button(UiTheme.TOKEN_ROW, "die", entry.title(), null);
        row.setName("army-rat-" + index);
        row.getLabel().setAlignment(Align.left);
        row.getLabel().setEllipsis(true);
        row.getCell(row.getLabel()).growX().minWidth(0);
        if (searching && !entry.path().isEmpty()) {
            row.row();
            Label path = kit.typeLabel(entry.path(), "small", "text.muted");
            path.setEllipsis(true);
            row.add();
            row.add(path).growX().minWidth(0).left().padLeft(space("sm"));
        }
        row.pressed(entry.name().equals(form.table()));
        UiKit.onChange(row, () -> {
            form.table(entry.name());
            showChosen();
            fillRowsPressed();
        });
        return row;
    }

    /** Marks the chosen table's row. */
    private void fillRowsPressed() {
        int index = 0;
        for (RatBrowser.Entry entry : browser.entries()) {
            if (!entry.folder()) {
                Actor row = list.findActor("army-rat-" + index++);
                if (row instanceof UiButton button) {
                    button.pressed(entry.name().equals(form.table()));
                }
            }
        }
    }

    private void clearSearch() {
        syncing = true;
        search.field.setText("");
        syncing = false;
        browser.search("");
    }

    /** Shows what the hand is dealt by: the chosen table with its folders, or the generator's choices in brief. */
    private void showChosen() {
        String title = mode.title();
        String headline = "";
        String detail = "";
        switch (mode) {
            case TABLES -> {
                title = Messages.getString("Lobby.randomArmy.rollingOn");
                String table = form.table();
                headline = table == null ? "" : table;
                detail = browser.pathOf(table);
            }
            case BATTLE_VALUE -> {
                BattleValueArmy army = generators.battleValue();
                headline = Messages.getString("Lobby.randomArmy.summary.battleValue", army.size(), army.minBV(),
                      army.maxBV());
                detail = Messages.getString("Lobby.randomArmy.summary.years", army.minYear(), army.maxYear());
            }
            case BALANCED -> {
                BalancedArmy army = generators.balanced();
                headline = Messages.getString("Lobby.randomArmy.summary.balanced",
                      NumberFormat.getIntegerInstance().format(army.target()),
                      Messages.getString(army.balanceBy().key()), army.tolerance());
                detail = Messages.getString("Lobby.randomArmy.available", army.available());
            }
            case FACTION -> {
                if (factions == null) {
                    headline = Messages.getString("Lobby.randomArmy.loadingFactions");
                } else {
                    RatGeneratorForm table = factions.table();
                    FactionChoice choice = table.factions();
                    headline = factionName(choice);
                    FactionRecord command = choice.command();
                    detail = join(String.valueOf(choice.year()),
                          command == null ? null : command.getName(choice.year()), choice.rating(),
                          UnitType.getTypeDisplayableName(table.unitType()));
                }
            }
            case FORMATION -> {
                if (factions == null) {
                    headline = Messages.getString("Lobby.randomArmy.loadingFactions");
                } else {
                    FormationForm formation = factions.formation();
                    FactionChoice choice = formation.factions();
                    headline = formation.formation() == null ? Messages.getString("Lobby.randomArmy.noFormation")
                          : formation.formation().getName();
                    detail = join(factionName(choice), String.valueOf(choice.year()),
                          UnitType.getTypeDisplayableName(formation.unitType()));
                }
            }
            case FORCE -> headline = factions == null ? Messages.getString("Lobby.randomArmy.loadingFactions") : "";
        }
        rollingOn.setText(title);
        chosen.setText(headline);
        chosenPath.setText(detail);
        refresh();
    }

    private static String factionName(FactionChoice choice) {
        FactionRecord faction = choice.faction();
        return faction == null ? "" : faction.getName(choice.year());
    }

    /** The parts that are there, between middle dots. */
    private static String join(@Nullable String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (!joined.isEmpty()) {
                    joined.append(" \u00B7 ");
                }
                joined.append(part);
            }
        }
        return joined.toString();
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static float size(String name) {
        return DesignTokens.shipped().size().get(name);
    }

    private static String strip(String key) {
        String text = Messages.getString(key);
        return text.endsWith(":") ? text.substring(0, text.length() - 1) : text;
    }

    // ---- The hand ----

    /** Runs a roll on the Swing thread, then deals the hand again. */
    private void run(Runnable work) {
        if (busy) {
            return;
        }
        busy = true;
        refresh();
        host.edt(work, () -> {
            busy = false;
            rebuild();
        });
    }

    private void rebuild() {
        hand.clearChildren();
        images.clear();
        cardControls.clear();
        List<RandomArmyForm.Card> cards = form.cards();
        if (cards.isEmpty()) {
            Label empty = kit.typeLabel(Messages.getString(emptyKey()), "body", "text.muted");
            empty.setName("army-empty");
            empty.setWrap(true);
            empty.setAlignment(Align.center);
            hand.add(empty).width(cardWidth * columns).pad(space("xl"));
        }
        float gap = space("md");
        List<MekSummary> units = new ArrayList<>(cards.size());
        for (int index = 0; index < cards.size(); index++) {
            hand.add(card(index, cards.get(index))).width(cardWidth).top().fillY()
                  .padLeft(index % columns == 0 ? 0 : gap).padTop(index < columns ? 0 : gap);
            if ((index + 1) % columns == 0) {
                hand.row();
            }
            units.add(cards.get(index).unit());
        }
        host.sprites(units);
        refresh();
    }

    /** What an empty hand says: how to deal one, or why none can be dealt. */
    private String emptyKey() {
        if (mode.needsFactions() && factions == null) {
            return "RandomArmyDialog.ratStatusLoading";
        }
        if (mode == Mode.FORMATION && factions != null && factions.formation().empty()) {
            return "Lobby.randomArmy.noFormationBuilt";
        }
        if (form.canRoll()) {
            return mode == Mode.TABLES ? "Lobby.randomArmy.empty" : "Lobby.randomArmy.emptyGenerator";
        }
        return mode == Mode.TABLES ? "RandomArmyDialog.ratStatusLoading" : "Lobby.randomArmy.cannotDeal." + mode.key;
    }

    private Table card(int index, RandomArmyForm.Card card) {
        MekSummary unit = card.unit();
        Table frame = kit.card();
        frame.setName("army-card-" + index);
        frame.top().left().defaults().left();
        Image sprite = new Image((Drawable) null, Scaling.fit);
        sprite.setName(LobbyRequisitionPanel.spriteKey(unit));
        sprite.setDrawable(sprites.apply(LobbyRequisitionPanel.spriteKey(unit)));
        images.add(sprite);
        frame.add(sprite).size(size("control.lg") * 2).center().row();
        Label name = kit.typeLabel(unit.getName(), "body.strong", "text.primary");
        name.setWrap(true);
        frame.add(name).growX().padTop(space("xs")).row();
        frame.add(kit.typeLabel(Messages.getString("Lobby.randomArmy.stats", unit.getBV(), format(unit.getTons()),
              unit.getUnitType()), "small", "text.secondary")).growX().row();
        Table actions = new Table();
        // A formation's cards hold together: none is kept or rerolled alone
        if (form.cardsApart()) {
            UiButton keep = kit.button("button-toggle-compact", "star", Messages.getString("Lobby.randomArmy.keep"),
                  null);
            keep.setName("army-keep-" + index);
            keep.setProgrammaticChangeEvents(false);
            keep.setChecked(card.kept());
            UiKit.onChange(keep, () -> {
                if (!busy) {
                    form.keep(index, !form.cards().get(index).kept());
                }
                keep.setChecked(form.cards().get(index).kept());
            });
            UiButton reroll = kit.button("button-quiet-compact", "replay", null, null);
            reroll.setName("army-reroll-" + index);
            kit.tip(reroll, true).getActor().setText(Messages.getString("Lobby.randomArmy.reroll"));
            UiKit.onChange(reroll, () -> run(() -> form.reroll(index)));
            cardControls.add(keep);
            cardControls.add(reroll);
            actions.add(keep).left().expandX();
            actions.add(reroll).padLeft(space("xs"));
        } else {
            actions.add().expandX();
        }
        UiButton inspect = kit.button("button-quiet-compact", "info", null, null);
        inspect.setName("army-readout-" + index);
        kit.tip(inspect, true).getActor().setText(Messages.getString("Lobby.randomArmy.readout"));
        UiKit.onChange(inspect, () -> host.readout(unit));
        actions.add(inspect).padLeft(space("xs"));
        UiButton discard = kit.button("button-quiet-compact", "close", null, null);
        discard.setName("army-discard-" + index);
        kit.tip(discard, true).getActor().setText(Messages.getString("Lobby.randomArmy.discard"));
        UiKit.onChange(discard, () -> {
            if (busy) {
                return;
            }
            form.discard(index);
            rebuild();
        });
        cardControls.add(discard);
        actions.add(discard).padLeft(space("xs"));
        frame.add(actions).growX().padTop(space("sm")).expandY().bottom();
        return frame;
    }

    private static String format(double tons) {
        return (tons == Math.rint(tons)) ? String.valueOf((long) tons) : String.valueOf(tons);
    }

    private void refresh() {
        totals.setText(Messages.getString("Lobby.randomArmy.totals", form.cards().size(), form.battleValue(),
              format(form.tonnage())));
        boolean forcing = mode == Mode.FORCE;
        boolean hasHand = forcing ? forcePane != null && forcePane.canAdd() : !form.cards().isEmpty();
        add.setDisabled(busy || !hasHand || form.playerName() == null);
        roll.setDisabled(busy || !form.canRoll());
        clear.setDisabled(busy || (forcing && (forcePane == null || factions == null || factions.force().empty())));
        for (UiButton control : cardControls) {
            control.setDisabled(busy);
        }
        for (UiButton tile : tiles.values()) {
            tile.setDisabled(busy);
        }
    }

    /** A unit's sprite has been drawn: shows it on its cards. */
    void spriteArrived(String key) {
        for (Image image : images) {
            if (key.equals(image.getName())) {
                image.setDrawable(sprites.apply(key));
            }
        }
    }

    private void confirm() {
        if (busy) {
            return;
        }
        if (mode == Mode.FORCE) {
            if (factions == null || forcePane == null || !forcePane.canAdd()) {
                return;
            }
            ForceGeneratorForm force = factions.force();
            busy = true;
            refresh();
            host.edt(() -> host.addForce(force, form), close);
            return;
        }
        if (form.cards().isEmpty()) {
            return;
        }
        busy = true;
        refresh();
        host.edt(() -> host.add(form), close);
    }

    // ---- For the probe and the tests ----

    RandomArmyForm form() {
        return form;
    }

    RatBrowser browser() {
        return browser;
    }

    Mode mode() {
        return mode;
    }

    /** Whether the faction table and formation generators have arrived. */
    boolean factionsReady() {
        return factions != null;
    }

    /** The force generator's command, once it has been shown. */
    @Nullable LobbyForceGeneratorPane forcePane() {
        return forcePane;
    }

    /** Whether the unit filter shows in the random army's place. */
    boolean filtering() {
        return filter != null && bodyCell.getActor() == filter.actor();
    }

    @Nullable Actor control(String name) {
        return hand.getStage() == null ? null : hand.getStage().getRoot().findActor(name);
    }
}
