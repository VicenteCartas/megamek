/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Arrays;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.Align;
import megamek.client.lobby.AlphaStrikeStatsForm;
import megamek.client.lobby.SbfStatsForm;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSelect;
import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFUnit;

/**
 * The Alpha Strike and Strategic BattleForce stats of the units the lobby's menu picked, as {@link UiDialog} cards: a
 * roster of the units, or of the formations and their units, each a card with its stat tiles and its point value. The
 * footer's tools copy the stats for a spreadsheet, print the cards or record sheets and save them as an MMU file,
 * through the {@link Host}.
 * <p>
 * GPU thread.
 */
final class LobbyStatsView {
    /** What the stats cards need of the lobby. Each call is on the GPU thread. */
    interface Host {
        /** Asks for a file and writes the units' Alpha Strike stats to it. */
        void save(AlphaStrikeStatsForm form);

        /** Prints the units' Alpha Strike cards, in this order. */
        void print(List<AlphaStrikeElement> elements);

        /** Asks for a file and writes the formations to it. */
        void save(SbfStatsForm form);

        /** The fonts a record sheet can print in; the first, blank, is the sheet's own. */
        List<String> fonts();

        /** The record sheet's header and value fonts as last printed, blank for the sheet's own. */
        String[] sheetFonts();

        /** Prints the formations' record sheets in these fonts, blank for the sheet's own, and keeps them. */
        void print(SbfStatsForm form, String headerFont, String valueFont);
    }

    private LobbyStatsView() {
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static Label wrapped(UiKit kit, String text, String type, String role) {
        Label label = kit.typeLabel(text, type, role);
        label.setWrap(true);
        return label;
    }

    /** A stat as a tile: its short label over its value. */
    private static Table tile(UiKit kit, String label, String value) {
        Table tile = new Table();
        tile.add(kit.typeLabel(label, "small", "text.muted")).row();
        tile.add(kit.typeLabel(value, "body.strong", "text.primary"));
        return tile;
    }

    /** The point value, large, as the card's last word. */
    private static Table pointValue(UiKit kit, int value) {
        Table tile = new Table();
        tile.add(kit.typeLabel("PV", "small", "text.muted")).row();
        tile.add(kit.typeLabel(String.valueOf(value), "headline", "text.primary"));
        return tile;
    }

    /** A brief word on the footer that the stats are on the clipboard. */
    private static void copied(Label status, String text) {
        Gdx.app.getClipboard().setContents(text);
        status.setText(Messages.getString("Lobby.stats.copied"));
    }

    private static Label status(UiKit kit) {
        Label status = kit.typeLabel("", "small", "text.secondary");
        status.setName("stats-status");
        return status;
    }

    // ---- Alpha Strike

    /** The units' Alpha Strike stats as a roster, sorted by a stat chosen above it. */
    static void alphaStrike(UiKit kit, UiDialog dialog, AlphaStrikeStatsForm form, Host host, Runnable close) {
        Table body = dialog.body();
        body.top().left().defaults().left().growX();
        List<AlphaStrikeElement> shown = form.elements();
        Table bar = new Table();
        bar.add(kit.typeLabel(Messages.getString("Lobby.stats.units", shown.size(), form.totalPointValue()),
              "body.strong", "text.secondary")).expandX().left();
        bar.add(kit.caption(Messages.getString("Lobby.stats.sortBy"), true)).padRight(space("xs"));
        UiSelect sort = kit.tokenSelect(true, Arrays.stream(AlphaStrikeStatsForm.Sort.values())
              .map(AlphaStrikeStatsForm.Sort::label).toArray(String[]::new));
        sort.setName("stats-sort");
        sort.select(form.sort().ordinal());
        bar.add(sort);
        UiButton order = kit.button("button-quiet-compact", null, direction(form), null);
        order.setName("stats-order");
        kit.tip(order, true).getActor().setText(Messages.getString("Lobby.stats.reverse"));
        bar.add(order).padLeft(space("xs"));
        body.add(bar).row();
        Table roster = new Table();
        roster.top().left().defaults().growX().left();
        body.add(roster).padTop(space("md")).row();
        Runnable fill = () -> {
            roster.clearChildren();
            for (AlphaStrikeElement element : form.elements()) {
                roster.add(elementCard(kit, element)).padBottom(space("sm")).row();
            }
            order.setText(direction(form));
        };
        UiKit.onChange(sort, () -> {
            AlphaStrikeStatsForm.Sort by = AlphaStrikeStatsForm.Sort.values()[sort.getSelectedIndex()];
            if (by != form.sort()) {
                form.sortBy(by);
                fill.run();
            }
        });
        UiKit.onChange(order, () -> {
            form.sortBy(form.sort());
            fill.run();
        });
        fill.run();
        if (shown.isEmpty()) {
            roster.add(wrapped(kit, Messages.getString("Lobby.stats.noUnits"), "body", "text.secondary")).row();
        }

        Label status = status(kit);
        dialog.tool(Messages.getString("CASCardPanel.copyCard"),
              () -> copied(status, form.cardsText())).setName("stats-copy-cards");
        dialog.tool(Messages.getString("CASCardPanel.copyStats"),
              () -> copied(status, form.statsText())).setName("stats-copy-stats");
        dialog.tool(Messages.getString("CASCardPanel.printCard"), () -> host.print(form.elements()))
              .setName("stats-print");
        dialog.tool(Messages.getString("Save.text"), () -> host.save(form)).setName("stats-save");
        dialog.leading().add(status);
        for (var tool : dialog.leading().getChildren()) {
            if (tool instanceof UiButton button) {
                button.setDisabled(shown.isEmpty());
            }
        }
        dialog.primary(Messages.getString("Close"), close).setName("stats-close");
    }

    private static String direction(AlphaStrikeStatsForm form) {
        return Messages.getString(form.descending() ? "Lobby.stats.descending" : "Lobby.stats.ascending");
    }

    private static Table elementCard(UiKit kit, AlphaStrikeElement element) {
        Table card = kit.card();
        card.setName("stats-unit-" + element.getName());
        card.top().left();
        Table head = new Table();
        head.add(kit.typeLabel(element.getName(), "title", "text.primary")).expandX().left().minWidth(0);
        head.add(pointValue(kit, element.getPointValue())).right().top();
        card.add(head).growX().row();
        Table tiles = new Table();
        tiles.left().defaults().padRight(space("lg")).top();
        for (AlphaStrikeStatsForm.Stat stat : AlphaStrikeStatsForm.stats(element)) {
            tiles.add(tile(kit, stat.label(), stat.value()));
        }
        card.add(tiles).left().padTop(space("xs")).row();
        List<String> specials = AlphaStrikeStatsForm.specials(element);
        if (!specials.isEmpty()) {
            card.add(wrapped(kit, String.join("\n", specials), "small", "text.secondary")).growX().left()
                  .padTop(space("sm")).row();
        }
        return card;
    }

    // ---- Strategic BattleForce

    /** The formations as cards, each with its units under it and, when asked, their Alpha Strike elements. */
    static void sbf(UiKit kit, UiDialog dialog, SbfStatsForm form, Host host, Runnable close) {
        Table body = dialog.body();
        body.top().left().defaults().left().growX();
        Table bar = new Table();
        bar.add(kit.typeLabel(Messages.getString("Lobby.stats.formations", form.formations().size(),
              form.totalPointValue()), "body.strong", "text.secondary")).expandX().left();
        UiButton elements = kit.button("button-toggle-compact", null,
              Messages.getString("SBFStatsDialog.showElements"), null);
        elements.setName("stats-elements");
        elements.setProgrammaticChangeEvents(false);
        elements.setChecked(form.showElements());
        bar.add(elements);
        body.add(bar).row();
        Table roster = new Table();
        roster.top().left().defaults().growX().left();
        body.add(roster).padTop(space("md")).row();
        Runnable fill = () -> {
            roster.clearChildren();
            for (SBFFormation formation : form.formations()) {
                roster.add(formationCard(kit, formation, form.showElements())).padBottom(space("md")).row();
            }
            if (form.isEmpty()) {
                roster.add(wrapped(kit, Messages.getString("Lobby.stats.noFormations"), "body", "text.secondary"))
                      .row();
            }
        };
        UiKit.onChange(elements, () -> {
            form.showElements(!form.showElements());
            elements.setChecked(form.showElements());
            fill.run();
        });
        fill.run();

        List<String> fonts = host.fonts();
        String[] sheetFonts = host.sheetFonts();
        Table print = new Table();
        print.left().defaults().left().padRight(space("md"));
        UiSelect header = fontSelect(kit, fonts, sheetFonts[0], "stats-header-font");
        UiSelect value = fontSelect(kit, fonts, sheetFonts[1], "stats-value-font");
        print.add(kit.formField(strip(Messages.getString("SBFStatsDialog.headerFont")), header));
        print.add(kit.formField(strip(Messages.getString("SBFStatsDialog.valueFont")), value));
        body.add(kit.caption(Messages.getString("Lobby.stats.printFonts"), true)).padTop(space("lg")).row();
        body.add(print).padTop(space("xs")).row();

        Label status = status(kit);
        UiButton copy = dialog.tool(Messages.getString("SBFStatsDialog.copy"),
              () -> copied(status, form.clipboardText()));
        copy.setName("stats-copy");
        UiButton printButton = dialog.tool(Messages.getString("SBFStatsDialog.print"),
              () -> host.print(form, fonts.get(header.getSelectedIndex()), fonts.get(value.getSelectedIndex())));
        printButton.setName("stats-print");
        UiButton save = dialog.tool(Messages.getString("Save.text"), () -> host.save(form));
        save.setName("stats-save");
        dialog.leading().add(status);
        copy.setDisabled(form.isEmpty());
        printButton.setDisabled(form.isEmpty());
        save.setDisabled(form.isEmpty());
        dialog.primary(Messages.getString("Close"), close).setName("stats-close");
    }

    private static String strip(String text) {
        String trimmed = text.trim();
        return trimmed.endsWith(":") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static UiSelect fontSelect(UiKit kit, List<String> fonts, String current, String name) {
        UiSelect select = kit.tokenSelect(true, fonts.stream()
              .map(font -> font.isBlank() ? Messages.getString("Lobby.stats.sheetFont") : font)
              .toArray(String[]::new));
        select.setName(name);
        select.select(Math.max(0, fonts.indexOf(current)));
        return select;
    }

    private static Table formationCard(UiKit kit, SBFFormation formation, boolean showElements) {
        Table card = kit.card();
        card.setName("stats-formation-" + formation.getName());
        card.top().left();
        Table head = new Table();
        head.add(kit.typeLabel(formation.getName(), "title", "text.primary")).expandX().left().minWidth(0);
        head.add(pointValue(kit, formation.getPointValue())).right().top();
        card.add(head).growX().row();
        card.add(tiles(kit, SbfStatsForm.stats(formation))).left().padTop(space("xs")).row();
        String specials = SbfStatsForm.specials(formation);
        if (!specials.isBlank()) {
            card.add(wrapped(kit, specials, "small", "text.secondary")).growX().left().padTop(space("xs")).row();
        }
        for (SBFUnit unit : formation.getUnits()) {
            card.add(unitRow(kit, unit, showElements)).growX().padTop(space("md")).row();
        }
        return card;
    }

    private static Table tiles(UiKit kit, List<SbfStatsForm.Stat> stats) {
        Table tiles = new Table();
        tiles.left().defaults().padRight(space("lg")).top();
        for (SbfStatsForm.Stat stat : stats) {
            if (!"PV".equals(stat.label())) {
                tiles.add(tile(kit, stat.label(), stat.value()));
            }
        }
        return tiles;
    }

    /** A unit of a formation, set in under it with an accent bar beside it. */
    private static Table unitRow(UiKit kit, SBFUnit unit, boolean showElements) {
        Table row = new Table();
        row.top().left();
        row.add(kit.solid("accent.primary")).width(space("xxs")).growY().padRight(space("md"));
        Table content = new Table();
        content.top().left().defaults().left();
        Table head = new Table();
        head.add(kit.typeLabel(unit.getName(), "body.strong", "text.primary")).expandX().left().minWidth(0);
        head.add(kit.typeLabel("PV " + unit.getPointValue(), "body.strong", "text.secondary")).right();
        content.add(head).growX().row();
        content.add(tiles(kit, SbfStatsForm.stats(unit))).padTop(space("xxs")).row();
        String specials = SbfStatsForm.specials(unit);
        if (!specials.isBlank()) {
            content.add(wrapped(kit, specials, "small", "text.secondary")).growX().padTop(space("xxs")).row();
        }
        if (showElements) {
            for (AlphaStrikeElement element : unit.getElements()) {
                Table line = new Table();
                Label name = kit.typeLabel(element.getName(), "small", "text.primary");
                name.setAlignment(Align.left);
                line.add(name).expandX().left().minWidth(0);
                StringBuilder stats = new StringBuilder();
                for (AlphaStrikeStatsForm.Stat stat : AlphaStrikeStatsForm.stats(element)) {
                    stats.append(stat.label()).append(' ').append(stat.value()).append("   ");
                }
                stats.append("PV ").append(element.getPointValue());
                line.add(kit.typeLabel(stats.toString(), "small", "text.muted")).right();
                content.add(line).growX().padTop(space("xxs")).row();
            }
        }
        row.add(content).growX();
        return row;
    }
}
