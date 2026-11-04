/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.FocusListener;
import jakarta.annotation.Nullable;
import megamek.client.lobby.LobbyBattlefield;
import megamek.client.lobby.LobbySnapshot;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.gpu.LobbyBattlefieldController.Command;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiPictureCard;
import megamek.client.ui.gdx.UiTextField;
import megamek.client.ui.gdx.UiTheme;

/**
 * The lobby's right column: the battlefield. The map as the minimap draws it with the deployment zones, which opens the
 * map setup, as Change map does, with the 3D preview beside it; a map rolled at launch adds its dice and random map
 * settings beside the map's line; the map's size and the planetary conditions; the rules that matter most and the victory conditions, each
 * with Change; and auto-resolve, folded. Its body moves into the centre as a view while the column is folded into its
 * rail, and back when it unfolds. Every action goes to the window ({@link Actions}), which runs the Swing lobby's own.
 */
final class LobbyBattlefieldPanel {
    /** The map card's height to its width before the map is drawn. */
    static final float MAP_ASPECT = .6f;
    /** The most notable rules shown as chips; the rest go into a "+n" chip. */
    static final int RULE_CHIPS = 3;

    /** What the battlefield column asks of its window. */
    interface Actions {
        /** Runs one of the column's actions. */
        void command(Command command);

        /** Sets the auto-resolve settings. */
        void autoResolve(boolean chances, int runs, int threads);
    }

    private final UiKit kit;
    private final Actions actions;
    private final Table panel;
    private final Table body = new Table();
    private final UiPictureCard map;
    private final Label mapLine;
    private final Label conditions;
    private final UiButton conditionsChange;
    private final Label rulesLine;
    private final Table ruleChips = new Table();
    private final Label victory;
    private final Table autoResolve = new Table();
    private final Label autoResolveSummary;
    private final Table autoResolveBody = new Table();
    private final UiChoice chances;
    private final UiTextField runs;
    private final UiTextField threads;
    private final UiButton runAutoResolve;
    private final Table rolledTools = new Table();
    private final UiButton generate;
    private final UiButton randomMap;
    private LobbyBattlefield shown = LobbyBattlefield.EMPTY;
    private boolean autoResolveOpen;
    private boolean updating;

    LobbyBattlefieldPanel(UiKit kit, Actions actions) {
        this.kit = kit;
        this.actions = actions;
        panel = kit.panel(true);

        Table content = new Table();
        content.top();
        // As tall as the column is wide at most, so a centre view keeps the summaries in sight
        map = kit.pictureCard(Messages.getString("Lobby.battlefield.drawing"), MAP_ASPECT)
              .maxHeight(LobbyLayout.BATTLEFIELD_WIDTH);
        kit.tip(map, true).getActor().setText(Messages.getString("Lobby.battlefield.mapTooltip"));
        UiKit.onChange(map, () -> actions.command(Command.MAP_SETUP));
        content.add(map).growX().row();

        mapLine = wrapped(kit.typeLabel("", "body.strong", "text.primary"));
        generate = tool("die", "Lobby.battlefield.generate", "lobby-battlefield-generate", Command.GENERATE);
        randomMap = tool("tune", "Lobby.battlefield.randomMap", "lobby-battlefield-random-map", Command.RANDOM_MAP);
        rolledTools.add(generate);
        rolledTools.add(randomMap).padLeft(space("xxs"));
        Table mapRow = new Table();
        mapRow.add(mapLine).growX().minWidth(0);
        mapRow.add(rolledTools).right().top().padLeft(space("xs"));
        content.add(mapRow).growX().padTop(space("sm")).row();
        UiButton editMap = kit.button("button-secondary" + UiTheme.COMPACT, null,
              Messages.getString("Lobby.battlefield.editMap"), null);
        editMap.setName("lobby-battlefield-change-map");
        kit.tip(editMap, true).getActor().setText(Messages.getString("Lobby.battlefield.mapTooltip"));
        UiKit.onChange(editMap, () -> actions.command(Command.MAP_SETUP));
        Table mapTools = new Table();
        mapTools.left();
        mapTools.add(editMap);
        mapTools.add(tool("cube", "Lobby.battlefield.preview3D", "lobby-battlefield-preview-3d", Command.PREVIEW_3D))
              .padLeft(space("xs"));
        content.add(mapTools).left().padTop(space("sm")).row();

        conditionsChange = change("Lobby.battlefield.conditions.tooltip", Command.CONDITIONS);
        content.add(heading(Messages.getString("Lobby.battlefield.conditions"), conditionsChange)).growX()
              .padTop(space("lg")).row();
        conditions = wrapped(kit.typeLabel("", "body", "text.secondary"));
        content.add(conditions).growX().padTop(space("xs")).row();

        content.add(heading(Messages.getString("Lobby.battlefield.rules"),
              change("Lobby.battlefield.rules.tooltip", Command.GAME_OPTIONS))).growX().padTop(space("lg")).row();
        rulesLine = wrapped(kit.typeLabel("", "body", "text.secondary"));
        content.add(rulesLine).growX().padTop(space("xs")).row();
        ruleChips.left();
        content.add(ruleChips).growX().padTop(space("xs")).row();

        content.add(heading(Messages.getString("Lobby.battlefield.victory"),
              change("Lobby.battlefield.victory.tooltip", Command.VICTORY))).growX().padTop(space("lg")).row();
        victory = wrapped(kit.typeLabel("", "body", "text.secondary"));
        content.add(victory).growX().padTop(space("xs")).row();

        autoResolveSummary = kit.typeLabel("", "small", "text.muted");
        chances = kit.toggleSwitch(Messages.getString("ChatLounge.chkAutoResolve"), false);
        UiKit.onChange(chances, () -> {
            if (!updating) {
                sendAutoResolve();
            }
        });
        runs = numberField();
        threads = numberField();
        runAutoResolve = kit.button("button-secondary" + UiTheme.COMPACT, null,
              Messages.getString("Lobby.battlefield.autoResolve.run"), null);
        kit.tip(runAutoResolve, true).getActor().setText(Messages.getString("ChatLounge.autoresolve.tooltip"));
        UiKit.onChange(runAutoResolve, () -> actions.command(Command.RUN_AUTO_RESOLVE));
        content.add(autoResolve).growX().padTop(space("lg")).row();
        content.add().expandY();

        body.top();
        ScrollPane scroll = kit.scrollList(content, true, false);
        body.add(scroll).grow();
        fillAutoResolve();
        dock();
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static Label wrapped(Label label) {
        label.setWrap(true);
        return label;
    }

    /** An icon button named {@code name} that runs {@code command}, with its message as the tooltip. */
    private UiButton tool(String icon, String tipKey, String name, Command command) {
        UiButton button = kit.button("button-icon" + UiTheme.COMPACT, icon, null, null);
        button.setName(name);
        kit.tip(button, true).getActor().setText(Messages.getString(tipKey));
        UiKit.onChange(button, () -> actions.command(command));
        return button;
    }

    /** A quiet Change button that runs {@code command}. */
    private UiButton change(String tooltip, Command command) {
        UiButton button = kit.button("button-quiet" + UiTheme.COMPACT, null,
              Messages.getString("Lobby.battlefield.change"), null);
        kit.tip(button, true).getActor().setText(Messages.getString(tooltip));
        UiKit.onChange(button, () -> actions.command(command));
        return button;
    }

    /** A section's overline with its tool at the right. */
    private Table heading(String title, Actor tool) {
        Table heading = new Table();
        heading.add(kit.roleTypeLabel("overline", title, "text.muted")).left().growX();
        heading.add(tool).right();
        return heading;
    }

    /** A field for a whole number, sent when Enter is pressed or the field loses the focus. */
    private UiTextField numberField() {
        UiTextField field = kit.input("", true);
        field.setTextFieldFilter(new TextField.TextFieldFilter.DigitsOnlyFilter());
        field.setMaxLength(4);
        field.setTextFieldListener((typed, key) -> {
            if (key == '\r' || key == '\n') {
                sendAutoResolve();
            }
        });
        field.addListener(new FocusListener() {
            @Override
            public void keyboardFocusChanged(FocusEvent event, Actor actor, boolean focused) {
                if (!focused && actor == field) {
                    sendAutoResolve();
                }
            }
        });
        return field;
    }

    private void sendAutoResolve() {
        LobbyBattlefield.AutoResolve settings = shown.autoResolve();
        actions.autoResolve(chances.isChecked(), number(runs, settings.runs()), number(threads, settings.threads()));
    }

    private static int number(TextField field, int fallback) {
        try {
            return Integer.parseInt(field.getText().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void fillAutoResolve() {
        autoResolve.clearChildren();
        LobbyBattlefield.AutoResolve settings = shown.autoResolve();
        autoResolve.setVisible(settings.shown());
        if (!settings.shown()) {
            return;
        }
        UiButton autoResolveFold = kit.button("button-icon" + UiTheme.COMPACT,
              autoResolveOpen ? "chevron-down" : "chevron-right", null, null);
        UiKit.onChange(autoResolveFold, () -> {
            autoResolveOpen = !autoResolveOpen;
            fillAutoResolve();
        });
        kit.tip(autoResolveFold, true).getActor().setText(Messages.getString(autoResolveOpen
              ? "Lobby.battlefield.autoResolve.close" : "Lobby.battlefield.autoResolve.open"));
        Table header = new Table();
        header.add(autoResolveFold).padRight(space("xs"));
        header.add(kit.roleTypeLabel("overline", Messages.getString("Lobby.battlefield.autoResolve"), "text.muted"))
              .left().growX();
        header.add(autoResolveSummary).right();
        autoResolve.add(header).growX().row();
        if (!autoResolveOpen) {
            return;
        }
        autoResolveBody.clearChildren();
        autoResolveBody.add(wrapped(kit.typeLabel(Messages.getString("ChatLounge.name.autoResolveSetup.blurb"),
              "small", "text.secondary"))).growX().colspan(2).row();
        autoResolveBody.add(chances).left().colspan(2).padTop(space("sm")).row();
        autoResolveBody.add(kit.formField(Messages.getString("ChatLounge.autoresolve.numOfSimulations"), runs))
              .growX().uniformX().padTop(space("sm")).padRight(space("sm"));
        autoResolveBody.add(kit.formField(Messages.getString("ChatLounge.autoresolve.parallelism"), threads))
              .growX().uniformX().padTop(space("sm")).row();
        autoResolveBody.add(runAutoResolve).left().colspan(2).padTop(space("md"));
        autoResolve.add(autoResolveBody).growX().padTop(space("sm"));
    }

    Table panel() {
        return panel;
    }

    /** The battlefield without its panel, which a centre view takes while the column is folded. */
    Table body() {
        return body;
    }

    /** Puts the body back into the column's panel. */
    void dock() {
        panel.clearChildren();
        panel.add(kit.header(Messages.getString("Lobby.battlefield"), null, true)).growX().row();
        panel.add(body).grow();
    }

    /**
     * Shows the map's picture at {@code aspect} (its height over its width), or, without one, that it is being drawn
     * or that there is none.
     */
    void map(@Nullable Drawable picture, float aspect, boolean drawing) {
        map.picture(picture, picture == null ? MAP_ASPECT : aspect, Messages.getString(drawing
              ? "Lobby.battlefield.drawing" : "Lobby.battlefield.noMap"));
    }

    /** Shows {@code snapshot}'s battlefield. */
    void show(LobbySnapshot snapshot) {
        LobbyBattlefield battlefield = snapshot.battlefield();
        LobbyBattlefield before = shown;
        shown = battlefield;
        mapLine.setText(battlefield.mapLine().isBlank() ? Messages.getString("Lobby.noMap") : battlefield.mapLine());
        conditions.setText(battlefield.conditions());
        conditionsChange.setDisabled(battlefield.space());
        rolledTools.setVisible(battlefield.rolled());
        generate.setDisabled(!battlefield.generable());
        randomMap.setDisabled(battlefield.space());
        rulesLine.setText(battlefield.rulesLine());
        if (!battlefield.notableRules().equals(before.notableRules()) || ruleChips.getCells().isEmpty()) {
            fillRuleChips(battlefield.notableRules());
        }
        victory.setText(battlefield.victory());
        LobbyBattlefield.AutoResolve settings = battlefield.autoResolve();
        autoResolveSummary.setText(settings.chances()
              ? Messages.getString("Lobby.battlefield.autoResolve.on", settings.runs())
              : Messages.getString("Lobby.battlefield.autoResolve.off"));
        updating = true;
        chances.setChecked(settings.chances());
        updating = false;
        Actor focus = runs.getStage() == null ? null : runs.getStage().getKeyboardFocus();
        if (focus != runs) {
            runs.setText(String.valueOf(settings.runs()));
        }
        if (focus != threads) {
            threads.setText(String.valueOf(settings.threads()));
        }
        runs.setDisabled(!settings.chances());
        threads.setDisabled(!settings.chances());
        if (settings.shown() != before.autoResolve().shown() || !autoResolve.hasChildren() && settings.shown()) {
            fillAutoResolve();
        }
    }

    private void fillRuleChips(List<String> rules) {
        ruleChips.clearChildren();
        for (int i = 0; i < Math.min(RULE_CHIPS, rules.size()); i++) {
            ruleChips.add(kit.chip(rules.get(i), UiKit.Tone.NORMAL, true)).padRight(space("xs"));
        }
        if (rules.size() > RULE_CHIPS) {
            Actor rest = kit.chip(Messages.getString("Lobby.battlefield.moreRules", rules.size() - RULE_CHIPS),
                  UiKit.Tone.NORMAL, true);
            kit.tip(rest, true).getActor().setText(String.join(", ", rules.subList(RULE_CHIPS, rules.size())));
            ruleChips.add(rest);
        }
    }

    /** For the developer switch: opens the auto-resolve settings. */
    void openAutoResolve() {
        autoResolveOpen = true;
        fillAutoResolve();
    }
}
