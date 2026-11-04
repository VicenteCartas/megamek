/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.actions.SequenceAction;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;
import megamek.client.lobby.LobbyReadiness;
import megamek.client.lobby.LobbyReadiness.Side;
import megamek.client.lobby.LobbyReadiness.Warning;
import megamek.client.lobby.LobbySnapshot;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiKit.Tone;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiPopover;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.util.KeyCommandBind;

/**
 * The lobby's top bar, over the art without a panel, as the reference's ready bar: the BRIEFING title with where the
 * game runs and its map under it at the start; the ready count, a Battle Value chip per side and the warnings to heed
 * before Ready in the middle, centred on the whole bar, more sides folding into one chip that lists them; at the end
 *  only actions: the Game Master's seat, the Client Settings gear, the game's ⋮ menu (saving), then Leave and Ready, Ready
  * last so it holds the edge. Ready turns green with a check once the player is ready, and
 * a click on it while a red warning stands lights the red warnings instead.
 */
final class LobbyTopBar extends WidgetGroup {
    /** More sides than this fold into one chip. */
    static final int MAX_SIDE_CHIPS = 4;
    /** More warnings than this fold the rest into one chip; a narrow bar folds them all. */
    static final int MAX_WARNING_CHIPS = 3;
    /** How long a pulse keeps the red warnings lit, in seconds: the reference's 700 ms. */
    static final float PULSE_SECONDS = 0.7f;
    /** The pulse lights them this many times. */
    static final int PULSE_FLASHES = 2;

    private final UiKit kit;
    private final LobbyGameMasterSeat seat;
    private final Consumer<Warning> warningAction;
    private final Table heading = new Table();
    private final Table chips = new Table();
    private final Table trailing = new Table();
    private final Table warnings = new Table();
    private final Label subtitle;
    private final UiButton ready;
    private final UiButton done;
    private final TextTooltip readyTip;
    private final TextTooltip doneTip;
    private final UiPopover sidesPopover;
    private final UiPopover warningsPopover;
    private final UiPopover gameMenu;
    private final GameActions gameActions;
    private final float gap = space("lg");
    private final List<Button> blockerChips = new ArrayList<>();
    private LobbySnapshot shown = LobbySnapshot.EMPTY;
    private String shortcut = "";
    private boolean narrow;

    LobbyTopBar(UiKit kit, LobbyGameMasterSeat seat, Runnable settingsAction, GameActions gameActions,
          Runnable leaveAction, Runnable readyAction, Consumer<Warning> warningAction) {
        this.kit = kit;
        this.seat = seat;
        this.warningAction = warningAction;
        this.gameActions = gameActions;
        gameMenu = new UiPopover(kit, () -> { }, UiTheme.TOKEN);
        gameMenu.setName("lobby-game-menu-popover");
        sidesPopover = new UiPopover(kit, () -> { }, UiTheme.TOKEN);
        sidesPopover.setName("lobby-sides");
        warningsPopover = new UiPopover(kit, () -> { }, UiTheme.TOKEN);
        warningsPopover.setName("lobby-warnings");
        heading.left();
        heading.add(kit.roleTypeLabel("headline", Messages.getString("Lobby.title"), "text.primary")).left().row();
        subtitle = kit.typeLabel("", "caption", "text.muted");
        subtitle.setEllipsis(true);
        heading.add(subtitle).left().minWidth(0).padTop(space("xxs"));

        UiButton settings = kit.button("button-icon", "settings", null, null);
        settings.setName("lobby-settings");
        kit.tip(settings, true).getActor().setText(Messages.getString("Lobby.settingsTip",
              KeyCommandBind.getDesc(KeyCommandBind.CLIENT_SETTINGS)));
        UiKit.onChange(settings, settingsAction);
        UiButton more = kit.button("button-icon", "more-vert", null, null);
        more.setName("lobby-game-menu");
        kit.tip(more, true).getActor().setText(Messages.getString("Lobby.gameMenu.tip"));
        UiKit.onChange(more, () -> openGameMenu(more));
        UiButton leave = kit.button("button-secondary", null, Messages.getString("Lobby.leave"), null);
        UiKit.onChange(leave, leaveAction);
        ready = kit.button("button-primary", null, Messages.getString("Lobby.ready"), null);
        ready.setName("lobby-ready");
        UiKit.onChange(ready, readyAction);
        done = kit.button(UiTheme.DONE_BUTTON, "check", Messages.getString("Lobby.ready"), null);
        done.setName("lobby-done");
        UiKit.onChange(done, readyAction);
        done.setVisible(false);
        readyTip = kit.tip(ready, true);
        doneTip = kit.tip(done, true);
        // Leave and both of Ready's faces share the widest one's width, so nothing moves when Ready changes face
        float width = Math.max(leave.getPrefWidth(), Math.max(ready.getPrefWidth(), done.getPrefWidth()));
        Stack readyFaces = new Stack(ready, done);
        trailing.add(seat).padRight(space("sm"));
        trailing.add(settings).padRight(space("xs"));
        trailing.add(more).padRight(space("sm"));
        trailing.add(leave).minWidth(width).padRight(space("sm"));
        trailing.add(readyFaces).minWidth(width);

        addActor(heading);
        addActor(chips);
        addActor(trailing);
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    /** Adds the popovers to {@code layer}, above the columns; once, before the bar shows a snapshot. */
    void attach(Group layer) {
        layer.addActor(sidesPopover);
        layer.addActor(warningsPopover);
        layer.addActor(gameMenu);
        seat.attach(layer);
    }

    /** One Escape step: closes an open popover; true when one was open. */
    boolean back() {
        return sidesPopover.back() || warningsPopover.back() || gameMenu.back() || seat.back();
    }

    /** The ⋮ menu of the game itself: saving it and loading another, as the old menu bar's File menu. */
    private void openGameMenu(Actor trigger) {
        if (gameMenu.isVisible()) {
            gameMenu.cancel();
            return;
        }
        if (gameMenu.getStage() == null) {
            return;
        }
        UiMenuList items = new UiMenuList(kit, UiTheme.TOKEN_MENU_ROW);
        gameItem(items, "Lobby.gameMenu.save", KeyCommandBind.LOCAL_SAVE, gameActions::save);
        gameItem(items, "Lobby.gameMenu.quickSave", KeyCommandBind.QUICK_SAVE, gameActions::quickSave);
        if (gameActions.loads()) {
            items.separator();
            gameItem(items, "Lobby.gameMenu.load", KeyCommandBind.LOCAL_LOAD, gameActions::load);
            gameItem(items, "Lobby.gameMenu.quickLoad", KeyCommandBind.QUICK_LOAD, gameActions::quickLoad);
        }
        gameMenu.header(null, null).content(items);
        gameMenu.openedFrom(trigger);
        Vector2 corner = trigger.localToStageCoordinates(new Vector2(0, 0));
        gameMenu.showAt(corner.x, corner.y);
    }

    private void gameItem(UiMenuList items, String key, KeyCommandBind binding, Runnable action) {
        UiButton item = items.item(Messages.getString(key), KeyCommandBind.getDesc(binding), null, false, true, () -> {
            gameMenu.cancel();
            action.run();
        });
        item.setName(key);
    }

    /** What the ⋮ game menu does. */
    interface GameActions {
        void save();

        void quickSave();

        /** Asks for a saved game and leaves the lobby to load it. */
        void load();

        /** Leaves the lobby to load the quick save. */
        void quickLoad();

        /** Whether this lobby offers loading at all. */
        boolean loads();
    }

    /** The Ready key's name for the tooltips, such as "Ctrl+Enter", or empty for none. */
    void shortcut(String text) {
        shortcut = text;
        refreshTips();
    }

    /** Folds every warning into one chip in a narrow window, and shows them one by one again in a wide one. */
    void narrow(boolean on) {
        if (narrow != on) {
            narrow = on;
            fillWarnings();
        }
    }

    /** Shows the server and map, the ready count, each side's Battle Value, the warnings and Ready's face. */
    void show(LobbySnapshot snapshot) {
        shown = snapshot;
        String line = snapshot.server();
        if (!snapshot.mapSummary().isBlank()) {
            line += "  \u00b7  " + snapshot.mapSummary();
        }
        subtitle.setText(line);
        seat.show(snapshot.gameMaster());
        ready.setVisible(!snapshot.localReady());
        done.setVisible(snapshot.localReady());

        chips.clearChildren();
        int readyPlayers = snapshot.readyCount();
        int total = snapshot.readyTotal();
        boolean allReady = readyPlayers > 0 && readyPlayers == total;
        Actor count = kit.chip(Messages.getString("Lobby.readyCount", readyPlayers, total),
              allReady ? Tone.OK : Tone.NORMAL, true);
        count.setName("lobby-ready-count");
        kit.tip(count, true).getActor().setText(withWaiting(Messages.getString("Lobby.readyCount.tooltip")));
        chips.add(count);
        List<Side> sides = snapshot.sides();
        if (sides.size() > MAX_SIDE_CHIPS) {
            Button fold = kit.actingChip(Messages.getString("Lobby.sides", sides.size()), Tone.NORMAL);
            fold.setName("lobby-sides-chip");
            kit.tip(fold, true).getActor().setText(Messages.getString("Lobby.sides.tooltip"));
            UiKit.onChange(fold, () -> openSides(fold));
            chips.add(fold).padLeft(space("sm"));
        } else {
            for (Side side : sides) {
                chips.add(sideChip(side)).padLeft(space("sm"));
            }
        }
        if (sidesPopover.isVisible()) {
            fillSides();
        }
        fillWarnings();
        // The warnings stand with the status they qualify, so the end of the bar holds only actions
        chips.add(warnings).padLeft(shown.readiness().warnings().isEmpty() ? 0 : space("md"));
        refreshTips();
        invalidateHierarchy();
    }

    /** Lights the red warnings for a moment, as when Ready is pressed while they stand. */
    void pulse() {
        for (Button chip : blockerChips) {
            chip.clearActions();
            chip.setChecked(false);
            float step = PULSE_SECONDS / (2 * PULSE_FLASHES);
            SequenceAction flashes = Actions.sequence();
            for (int flash = 0; flash < PULSE_FLASHES; flash++) {
                flashes.addAction(Actions.run(() -> chip.setChecked(true)));
                flashes.addAction(Actions.delay(step));
                flashes.addAction(Actions.run(() -> chip.setChecked(false)));
                flashes.addAction(Actions.delay(step));
            }
            chip.addAction(flashes);
        }
    }

    /** For tests and the probe: the red warnings' chips. */
    List<Button> blockerChips() {
        return List.copyOf(blockerChips);
    }

    private Actor sideChip(Side side) {
        String text = side.hidden() ? Messages.getString("Lobby.sideHidden", side.name())
              : Messages.getString("Lobby.side", side.name(), side.bv());
        String edge = switch (side.relation()) {
            case OWN -> "accent";
            case ENEMY -> "danger";
            default -> "neutral";
        };
        Table chip = kit.edgeChip(text, edge);
        kit.tip(chip, true).getActor().setText(Messages.getString(side.hidden() ? "Lobby.side.hiddenTooltip"
              : "Lobby.side.tooltip"));
        return chip;
    }

    private void openSides(Actor trigger) {
        if (sidesPopover.isVisible()) {
            sidesPopover.cancel();
            return;
        }
        if (sidesPopover.getStage() == null) {
            return;
        }
        fillSides();
        sidesPopover.openedFrom(trigger);
        Vector2 corner = trigger.localToStageCoordinates(new Vector2(0, 0));
        sidesPopover.showAt(corner.x, corner.y);
    }

    private void fillSides() {
        Table list = new Table();
        list.pad(space("sm"), space("md"), space("md"), space("md"));
        list.defaults().left().padTop(space("xs"));
        for (Side side : shown.sides()) {
            list.add(sideChip(side)).row();
        }
        sidesPopover.header(Messages.getString("Lobby.sides.title"), null).content(list);
    }

    private void fillWarnings() {
        warnings.clearChildren();
        blockerChips.clear();
        List<Warning> all = shown.readiness().warnings();
        int single = narrow ? 0 : (all.size() <= MAX_WARNING_CHIPS ? all.size() : MAX_WARNING_CHIPS - 1);
        for (int index = 0; index < single; index++) {
            Warning warning = all.get(index);
            Button chip = kit.actingChip(warning.text(), warning.blocking() ? Tone.BAD : Tone.WARN);
            chip.setName("lobby-warning-" + warning.kind().name().toLowerCase());
            kit.tip(chip, true).getActor().setText(warning.detail());
            UiKit.onChange(chip, () -> warningAction.accept(warning));
            warnings.add(chip).padLeft(index == 0 ? 0 : space("sm"));
            if (warning.blocking()) {
                blockerChips.add(chip);
            }
        }
        List<Warning> rest = all.subList(single, all.size());
        if (!rest.isEmpty()) {
            boolean blocking = rest.stream().anyMatch(Warning::blocking);
            String text = Messages.getString(single == 0 ? "Lobby.warnings" : "Lobby.warnings.more", rest.size());
            Button fold = kit.actingChip(text, blocking ? Tone.BAD : Tone.WARN);
            fold.setName("lobby-warnings-chip");
            kit.tip(fold, true).getActor().setText(Messages.getString("Lobby.warnings.tooltip"));
            UiKit.onChange(fold, () -> openWarnings(fold, rest));
            warnings.add(fold).padLeft(single == 0 ? 0 : space("sm"));
            if (blocking) {
                blockerChips.add(fold);
            }
        }
        if (warningsPopover.isVisible()) {
            warningsPopover.cancel();
        }
        invalidateHierarchy();
    }

    private void openWarnings(Actor trigger, List<Warning> list) {
        if (warningsPopover.isVisible()) {
            warningsPopover.cancel();
            return;
        }
        if (warningsPopover.getStage() == null) {
            return;
        }
        UiMenuList items = new UiMenuList(kit, UiTheme.TOKEN_MENU_ROW);
        for (Warning warning : list) {
            items.item(warning.text(), null, null, false, true, () -> {
                warningsPopover.cancel();
                warningAction.accept(warning);
            });
        }
        warningsPopover.header(Messages.getString("Lobby.warnings.title"), null).content(items);
        warningsPopover.openedFrom(trigger);
        Vector2 corner = trigger.localToStageCoordinates(new Vector2(0, 0));
        warningsPopover.showAt(corner.x, corner.y);
    }

    /** Ready's tooltips: what it does, why it is blocked, or who the table still waits for. */
    private void refreshTips() {
        String key = shortcut.isEmpty() ? "" : " (" + shortcut + ")";
        List<String> blocking = shown.readiness().warnings().stream().filter(Warning::blocking).map(Warning::text)
              .toList();
        String readyTip = Messages.getString("Lobby.ready.tooltip") + key;
        if (!blocking.isEmpty()) {
            readyTip = Messages.getString("Lobby.ready.blocked", String.join(", ", blocking));
        }
        this.readyTip.getActor().setText(readyTip);
        doneTip.getActor().setText(withWaiting(Messages.getString("Lobby.ready.on.tooltip") + key));
    }

    /** {@code text} and, on a line of its own, whom the server still waits for, if anyone. */
    private String withWaiting(String text) {
        List<String> waiting = shown.waitingFor();
        if (waiting.isEmpty()) {
            return text;
        }
        return text + "\n" + Messages.getString("Lobby.readyCount.waiting", LobbyReadiness.names(waiting));
    }

    @Override
    public float getPrefWidth() {
        return heading.getPrefWidth() + gap + chips.getPrefWidth() + gap + trailing.getPrefWidth();
    }

    @Override
    public float getPrefHeight() {
        return Math.max(heading.getPrefHeight(), Math.max(chips.getPrefHeight(), trailing.getPrefHeight()));
    }

    @Override
    public void layout() {
        float height = getHeight();
        float trailingWidth = trailing.getPrefWidth();
        // The heading gives way first, its subtitle cut short, so the chips and Ready keep their room
        float headingWidth = Math.max(0, Math.min(heading.getPrefWidth(),
              getWidth() - trailingWidth - chips.getPrefWidth() - 2 * gap));
        heading.setBounds(0, Math.round((height - heading.getPrefHeight()) / 2), headingWidth,
              heading.getPrefHeight());
        trailing.setBounds(getWidth() - trailingWidth, Math.round((height - trailing.getPrefHeight()) / 2),
              trailingWidth, trailing.getPrefHeight());
        float chipsWidth = Math.min(chips.getPrefWidth(), Math.max(0, getWidth() - headingWidth - trailingWidth
              - 2 * gap));
        float x = LobbyLayout.chipsX(getWidth(), headingWidth, chips.getPrefWidth(), trailingWidth, gap);
        chips.setBounds(Math.round(x), Math.round((height - chips.getPrefHeight()) / 2), chipsWidth,
              chips.getPrefHeight());
    }
}
