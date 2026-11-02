/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Scaling;
import jakarta.annotation.Nullable;
import megamek.client.lobby.RandomArmyForm;
import megamek.client.lobby.RatBrowser;
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

/**
 * The lobby's random army, dealt as unit cards. On the left, the tables to roll on, browsed a folder at a time (source,
 * faction, command, unit type) or searched across all of them by words of their names and folders; on the right, the
 * chosen table, the unit count and the player; Roll deals the hand, rerolling every card not kept; each card shows its
 * unit's sprite, Battle Value and tonnage and can be kept, rerolled or discarded; the army's totals over the hand. Add
 * to game sends the hand. Advanced opens the Swing generator with its Battle Value, RAT generator and force generator
 * tabs. The rolls are a {@link RandomArmyForm}'s, the browsing a {@link RatBrowser}'s.
 * <p>
 * GPU thread. The rolls and the adding run on the Swing thread through {@link Host#edt}.
 */
final class LobbyRandomArmyView {
    /** What the view asks of the window. */
    interface Host {
        /** Runs {@code work} on the Swing thread, then {@code then} back on the GPU thread. */
        void edt(Runnable work, Runnable then);

        /** Swing thread: adds the hand to the game for the chosen player. */
        void add(RandomArmyForm form);

        /** Opens the Swing random army generator. */
        void advanced();

        /** Asks for the sprites of {@code units}, which arrive through {@link #spriteArrived}. */
        void sprites(List<MekSummary> units);
    }

    private final UiKit kit;
    private final RandomArmyForm form;
    private final RatBrowser browser;
    private final Host host;
    private final Runnable close;
    private final Function<String, Drawable> sprites;
    private final float cardWidth;
    private final int columns;
    private final Table hand = new Table();
    private final Table list = new Table();
    private final ScrollPane listScroll;
    private final Label place;
    private final Label where;
    private final Label found;
    private final UiButton back;
    private final Label backTip;
    private final TokenSearch search;
    private final Label chosen;
    private final Label chosenPath;
    private final float countWidth;
    private final Label totals;
    private final UiButton add;
    private final UiButton roll;
    private final UiButton clear;
    private final List<Image> images = new ArrayList<>();
    /** Every card's keep, reroll and discard buttons, which must not change the hand while a roll runs. */
    private final List<UiButton> cardControls = new ArrayList<>();
    private boolean busy;
    private boolean syncing;

    LobbyRandomArmyView(UiKit kit, UiDialog dialog, RandomArmyForm form, RatBrowser browser, float bodyWidth,
          Function<String, Drawable> sprites, Host host, Runnable close) {
        this.kit = kit;
        this.form = form;
        this.browser = browser;
        this.host = host;
        this.close = close;
        this.sprites = sprites;
        float gap = space("md");
        float paneGap = space("xl");
        float browserWidth = size("dialog.sm");
        float handWidth = Math.max(size("control.lg") * 5, bodyWidth - browserWidth - paneGap);
        columns = Math.max(1, (int) ((handWidth + gap) / (size("control.lg") * 5 + gap)));
        cardWidth = (handWidth - gap * (columns - 1)) / columns;
        Table body = dialog.body();
        body.top().left();

        // The tables, a folder at a time: source, faction, command, unit type; or searched across them all
        Table tables = kit.plainPanel();
        tables.setName("army-browser");
        tables.top().left().defaults().left().growX();
        Table head = new Table();
        head.add(kit.roleTypeLabel("overline", Messages.getString("Lobby.randomArmy.tables"), "text.secondary"))
              .left();
        head.add(kit.typeLabel(Messages.getString("Lobby.randomArmy.tableCount", browser.size()), "small",
              "text.muted")).left().padLeft(space("sm")).growX();
        tables.add(head).row();
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
        tables.add(search).padTop(space("sm")).row();
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
        tables.add(crumbs).padTop(space("sm")).row();
        list.top().left();
        countWidth = kit.typeLabel(String.valueOf(browser.size()), "small", "text.muted").getPrefWidth();
        listScroll = kit.scrollList(list, true, false);
        listScroll.setScrollingDisabled(true, false);
        listScroll.setName("army-table-list");
        tables.add(listScroll).height(size("dialog.sm")).padTop(space("xs")).row();
        found = kit.typeLabel("", "small", "text.muted");
        found.setName("army-matches");
        found.setWrap(true);
        tables.add(found).padTop(space("xs")).row();
        body.add(tables).width(browserWidth).top().fillY();

        Table deal = new Table();
        deal.top().left().defaults().left().growX();
        Label rollingOn = kit.roleTypeLabel("overline", Messages.getString("Lobby.randomArmy.rollingOn"),
              "text.secondary");
        deal.add(rollingOn).row();
        chosen = kit.typeLabel("", "title", "text.primary");
        chosen.setName("army-chosen");
        chosen.setEllipsis(true);
        deal.add(chosen).minWidth(0).padTop(space("xs")).row();
        chosenPath = kit.typeLabel("", "small", "text.muted");
        chosenPath.setEllipsis(true);
        deal.add(chosenPath).minWidth(0).row();

        Table controls = new Table();
        controls.bottom().left();
        UiStepper count = kit.stepper(form.count(), 1, RandomArmyForm.MAX_UNITS);
        count.setName("army-count");
        count.up().setName("army-count-up");
        count.down().setName("army-count-down");
        UiKit.onChange(count, () -> form.count(count.value()));
        controls.add(kit.formField(strip("RandomArmyDialog.Unit"), count));
        if (form.players().size() > 1) {
            UiSelect player = kit.tokenSelect(true, form.players().toArray(String[]::new));
            player.setName("army-player");
            player.select(form.player());
            UiKit.onChange(player, () -> form.player(player.getSelectedIndex()));
            controls.add(kit.formField(strip("RandomArmyDialog.Player"), player)).padLeft(gap)
                  .width(size("control.lg") * 5);
        }
        roll = kit.button("button-secondary", "replay", Messages.getString("RandomArmyDialog.Roll"), null);
        roll.setName("army-roll");
        UiKit.onChange(roll, () -> run(form::roll));
        controls.add(roll).padLeft(gap).bottom();
        controls.add().growX();
        deal.add(controls).padTop(space("md")).row();

        totals = kit.typeLabel("", "title", "accent.primary");
        totals.setName("army-totals");
        deal.add(totals).padTop(space("lg")).row();
        hand.top().left();
        deal.add(hand).padTop(space("sm")).row();
        body.add(deal).width(handWidth).top().padLeft(paneGap);
        body.row();

        dialog.tool(Messages.getString("RandomArmyDialog.AdvancedSearch").replace("...", "\u2026"), () -> {
            close.run();
            host.advanced();
        }).setName("army-advanced");
        clear = dialog.secondary(Messages.getString("RandomArmyDialog.Clear"), () -> {
            if (busy) {
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
        showChosen();
        rebuild();
    }

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

    /** Shows the table the hand is rolled on, with its folders. */
    private void showChosen() {
        String table = form.table();
        chosen.setText(table == null ? "" : table);
        chosenPath.setText(browser.pathOf(table));
        refresh();
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
            Label empty = kit.typeLabel(Messages.getString(form.canRoll() ? "Lobby.randomArmy.empty"
                  : "RandomArmyDialog.ratStatusLoading"), "body", "text.muted");
            empty.setWrap(true);
            empty.setAlignment(Align.center);
            hand.add(empty).width(cardWidth * columns).pad(space("xl"));
        }
        float gap = space("md");
        for (int index = 0; index < cards.size(); index++) {
            hand.add(card(index, cards.get(index))).width(cardWidth).top().fillY()
                  .padLeft(index % columns == 0 ? 0 : gap).padTop(index < columns ? 0 : gap);
            if ((index + 1) % columns == 0) {
                hand.row();
            }
        }
        host.sprites(cards.stream().map(RandomArmyForm.Card::unit).toList());
        refresh();
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
        UiButton keep = kit.button("button-toggle-compact", "star", Messages.getString("Lobby.randomArmy.keep"), null);
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
        cardControls.add(keep);
        cardControls.add(reroll);
        cardControls.add(discard);
        actions.add(keep).left().expandX();
        actions.add(reroll).padLeft(space("xs"));
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
        add.setDisabled(busy || form.cards().isEmpty() || form.playerName() == null);
        roll.setDisabled(busy || !form.canRoll());
        clear.setDisabled(busy);
        cardControls.forEach(control -> control.setDisabled(busy));
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
        if (busy || form.cards().isEmpty()) {
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

    @Nullable Actor control(String name) {
        return hand.getStage() == null ? null : hand.getStage().getRoot().findActor(name);
    }
}
