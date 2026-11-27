/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuBotControls.hint;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBotControls.space;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuBotControls.steady;
import static megamek.client.ui.gdx.UiKit.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.TimeUtils;
import megamek.client.bot.princess.BehaviorChange.Trait;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.bot.princess.CardinalEdge;
import megamek.client.bot.princess.Tactic;
import megamek.client.lobby.BotSetupForm;
import megamek.client.lobby.BotSetupForm.Personality;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBotControls.PersonalityCards;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBotControls.PostureControl;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBotControls.SliderRow;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBots.Behavior;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBots.BotRow;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBots.Enemy;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBots.EnemyPlayer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHudState.Dialog;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiCollapsible;
import megamek.client.ui.gdx.UiFilterChip;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSegmented;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiStepper;
import megamek.client.ui.gdx.UiTabs;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.board.Coords;

/**
 * The bot sheet (bot sheet plan, artboard 3): a side sheet at the window's right that gives a bot the local player may
 * command its behavior and standing orders. Every control applies at once as a typed command ({@link GpuBotOrders})
 * and then shows what the bot reported; until the report arrives the footer says the change is on its way. Its scope
 * is always the whole bot, which its banner says. Strategic hexes are picked on the board with the sheet open: its
 * Targets page shows the pick with Done and Cancel. It leaves the board playable beside it, and Esc or its close
 * button closes it.
 * <p>
 * GL thread.
 */
final class GpuBotSheet implements GpuHud.Component {
    private static final int BEHAVIOR = 0;
    private static final int TARGETS = 1;
    /** The retreat choices the sheet offers: fight on, or leave right away by an edge. */
    private static final String[] RETREATS = { "fight", "leave" };
    /** A player's stance in the Players select. */
    private static final int NORMAL = 0;
    private static final int FEUD = 1;
    private static final int IGNORE = 2;
    /** The tactic chips draw no disabled face of their own, so they dim while the bot waits. */
    private static final float WAITING_ALPHA = .45f;
    /** How long the sheet waits for its board pick to start before it offers Pick on board again. */
    private static final long PICK_START_MILLIS = 2000;

    private final GpuHudKit kit;
    private final GpuBoardSource source;
    private final GpuHudState state;
    private final Table root;
    private final Label title;
    private final Container<UiSelect> switcherSlot = new Container<>();
    private List<Integer> switcherIds = List.of();
    private final Label scope;
    private final UiTabs tabs;
    private final Container<Table> page = new Container<>();
    private final ScrollPane scroll;
    private final Table behaviorPage = new Table();
    private final Table targetsPage = new Table();
    private final Stack status = new Stack();
    private final Label sending;
    private final Label noReply;
    private final Label waiting;

    private final Container<Table> cardsSlot = new Container<>();
    private PersonalityCards cards;
    private List<Personality> cardsFor;
    private final PostureControl posture;
    private final Map<Tactic, UiFilterChip> tactics = new EnumMap<>(Tactic.class);
    private final HorizontalGroup tacticChips = new HorizontalGroup();
    private final UiButton hold;
    private final UiButton resume;
    private final Container<UiButton> holdSlot = new Container<>();
    private final Label holdHint;
    private final UiSegmented retreat;
    private final UiSelect retreatEdge;
    private final Label retreatHint;
    private final UiCollapsible customize;
    private final Map<Trait, SliderRow> sliders = new EnumMap<>(Trait.class);
    /** Sliders the player moved, sent once the drag ends. */
    private final Set<Trait> moved = EnumSet.noneOf(Trait.class);

    private final Table priorityList = new Table();
    private final Table hexList = new Table();
    private final UiButton pickHexes;
    private final Table picking = new Table();
    private final Label pickStatus;
    private final Container<Actor> pickSlot = new Container<>();
    private final Table ignoreList = new Table();
    private final UiButton ignoreTurrets;
    private final UiButton clearIgnored;
    private final Table playersList = new Table();
    private final Table tagContent = new Table();
    private final UiCollapsible tag;

    private boolean open;
    private GpuBots.Snapshot shownSnapshot;
    private BotRow shown;
    /** Whether the shown bot has reported its settings, so its controls may send. */
    private boolean commandable;
    /** What each Targets list last showed, so a list is only rebuilt when its content changes. */
    private List<Object> priorityShown;
    private List<Object> hexesShown;
    private List<Object> ignoreShown;
    private List<Object> playersShown;
    private List<Object> tagShown;
    /** The bot whose strategic hexes the board pick adds, or -1. */
    private int pickingFor = -1;
    /** Whether that pick showed on the board yet, and when the sheet asked for it. */
    private boolean pickSeen;
    private long pickAsked;
    /** What the pick area last showed, so it only changes when the pick does. */
    private List<Object> pickShown;

    GpuBotSheet(GpuHudKit kit, GpuBoardSource source, GpuHudState state) {
        this.kit = kit;
        this.source = source;
        this.state = state;
        UiKit ui = kit.ui;
        root = ui.panel(true);
        root.setName("bot-sheet");
        root.top().pad(space("xl"));

        Table header = new Table();
        header.add(ui.icon("bot", 20, UiTheme.tokenColor("accent.primary"))).padRight(space("sm"));
        title = ui.typeLabel("", "headline", "text.primary");
        title.setName("bot-sheet-title");
        title.setEllipsis(true);
        header.add(title).growX().minWidth(0).left();
        UiButton close = ui.closeButton(this::close, true);
        close.setName("bot-sheet-close");
        GpuBotControls.tip(ui, close, text("GpuBoard.hud.bots.close"));
        header.add(close).padLeft(space("xs"));
        root.add(header).growX().row();
        switcherSlot.fill();
        root.add(switcherSlot).growX().row();

        Table banner = new Table();
        banner.left();
        banner.add(ui.chip(text("GpuBoard.hud.bots.chip"), UiKit.Tone.NORMAL, true)).top().left();
        scope = ui.typeLabel("", "small", "text.secondary");
        scope.setName("bot-sheet-scope");
        scope.setWrap(true);
        banner.add(scope).growX().minWidth(0).padLeft(space("sm"));
        root.add(banner).growX().padTop(space("md")).row();

        tabs = ui.tokenTabs(true, text("GpuBoard.hud.bots.tab.behavior"), text("GpuBoard.hud.bots.tab.targets"));
        tabs.setName("bot-sheet-tabs");
        String[] tabNames = { "behavior", "targets" };
        for (int index = 0; index < tabNames.length; index++) {
            tabs.option(index).setName("bot-sheet-tab-" + tabNames[index]);
        }
        UiKit.onChange(tabs, this::showPage);
        root.add(tabs).left().padTop(space("md")).row();

        page.top().fillX();
        scroll = ui.scrollList(page, true, false);
        scroll.setName("bot-sheet-scroll");
        scroll.setFadeScrollBars(false);
        scroll.setScrollingDisabled(true, false);
        root.add(scroll).grow().minHeight(0).padTop(space("md")).row();

        Table foot = ui.footer(root, true);
        sending = ui.typeLabel(text("GpuBoard.hud.bots.sending"), "small", "text.secondary");
        noReply = ui.typeLabel(text("GpuBoard.hud.bots.noReply"), "small", "status.warning");
        waiting = ui.typeLabel(text("GpuBoard.hud.bots.waiting"), "small", "text.muted");
        sending.setName("bot-sheet-sending");
        noReply.setName("bot-sheet-no-reply");
        waiting.setName("bot-sheet-waiting");
        status.add(sending);
        status.add(noReply);
        status.add(waiting);
        foot.add(status).left().growX().minWidth(0);
        foot.add(ui.typeLabel(text("GpuBoard.hud.bots.applyAtOnce"), "small", "text.muted")).right()
              .padLeft(space("sm"));

        // ---- Behavior ----
        behaviorPage.top().left().defaults().left().growX();
        behaviorPage.add(ui.caption(Messages.getString("Lobby.bot.personality"), true)).row();
        cardsSlot.fillX();
        behaviorPage.add(cardsSlot).padTop(space("sm")).row();
        posture = new PostureControl(ui, chosen -> send(() -> orders().posture(id(), chosen)));
        behaviorPage.add(posture.block()).padTop(space("lg")).row();

        behaviorPage.add(ui.caption(text("GpuBoard.hud.bots.tactic"), true)).padTop(space("lg")).row();
        HorizontalGroup chips = tacticChips;
        chips.wrap().rowLeft().left().space(space("xs")).wrapSpace(space("xs"));
        for (Tactic tactic : Tactic.values()) {
            UiFilterChip chip = ui.filterChip(Messages.getString("BotCommandPanel." + tactic.key() + ".title"));
            chip.setName("bot-tactic-" + tactic.name().toLowerCase(Locale.ROOT));
            GpuBotControls.tip(ui, chip, Messages.getString("BotCommandPanel." + tactic.key() + ".tooltip"));
            UiKit.onChange(chip, () -> send(() -> orders().tactic(id(), tactic)));
            tactics.put(tactic, chip);
            chips.addActor(chip);
        }
        behaviorPage.add(chips).minWidth(0).padTop(space("sm")).row();
        behaviorPage.add(hint(ui, text("GpuBoard.hud.bots.tactic.hint"))).minWidth(0).padTop(space("xs")).row();

        hold = ui.button("button-secondary-compact", "pause", text("GpuBoard.hud.bots.hold"), null);
        hold.setName("bot-hold");
        UiKit.onChange(hold, () -> send(() -> orders().hold(id(), true)));
        resume = ui.button("button-secondary-compact", "play", text("GpuBoard.hud.bots.resume"), null);
        resume.setName("bot-resume");
        UiKit.onChange(resume, () -> send(() -> orders().hold(id(), false)));
        float holdWidth = Math.max(hold.getPrefWidth(), resume.getPrefWidth());
        holdSlot.width(holdWidth).left();
        holdHint = hint(ui, "");
        holdHint.setName("bot-hold-hint");
        Table holdRow = new Table();
        holdRow.left();
        holdRow.add(holdSlot).left().top();
        holdRow.add(steady(ui, List.of(text("GpuBoard.hud.bots.hold.hint"), text("GpuBoard.hud.bots.resume.hint")),
              holdHint)).growX().minWidth(0).padLeft(space("md"));
        behaviorPage.add(holdRow).padTop(space("lg")).row();

        String[] retreatLabels = new String[RETREATS.length];
        for (int index = 0; index < RETREATS.length; index++) {
            retreatLabels[index] = Messages.getString("Lobby.bot.retreat." + RETREATS[index]);
        }
        retreat = ui.tokenSegmented(true, retreatLabels);
        retreat.setName("bot-retreat");
        for (int index = 0; index < RETREATS.length; index++) {
            retreat.option(index).setName("bot-retreat-" + RETREATS[index]);
        }
        List<CardinalEdge> edges = BotSetupForm.edges();
        String[] edgeNames = new String[edges.size()];
        for (int index = 0; index < edges.size(); index++) {
            edgeNames[index] = Messages.getString("Lobby.bot.edge." + edges.get(index).name().toLowerCase(Locale.ROOT));
        }
        retreatEdge = ui.tokenSelect(true, edgeNames);
        retreatEdge.setName("bot-retreat-edge");
        retreatEdge.select(Math.max(0, edges.indexOf(CardinalEdge.NEAREST)));
        UiKit.onChange(retreat, () -> {
            CardinalEdge edge = retreat.getSelectedIndex() == 0 ? CardinalEdge.NONE
                  : edges.get(retreatEdge.getSelectedIndex());
            send(() -> orders().retreat(id(), edge));
        });
        UiKit.onChange(retreatEdge, () -> {
            if (retreat.getSelectedIndex() == 1) {
                CardinalEdge edge = edges.get(retreatEdge.getSelectedIndex());
                send(() -> orders().retreat(id(), edge));
            }
        });
        Table retreatRow = new Table();
        retreatRow.left().bottom();
        retreatRow.add(ui.formField(Messages.getString("Lobby.bot.retreat"), retreat)).bottom().left();
        retreatRow.add(ui.typeLabel(Messages.getString("Lobby.bot.toward"), "small", "text.secondary"))
              .bottom().padLeft(space("lg")).padBottom(space("xs"));
        retreatRow.add(retreatEdge).bottom().padLeft(space("sm"));
        retreatRow.add().growX();
        behaviorPage.add(retreatRow).padTop(space("lg")).row();
        retreatHint = hint(ui, "");
        retreatHint.setName("bot-retreat-hint");
        List<String> retreatHints = new ArrayList<>();
        for (String each : RETREATS) {
            retreatHints.add(Messages.getString("Lobby.bot.retreat." + each + ".hint"));
        }
        behaviorPage.add(steady(ui, retreatHints, retreatHint)).minWidth(0).padTop(space("xs")).row();

        Table sliderList = new Table();
        sliderList.top().left().defaults().left().growX();
        for (Trait trait : Trait.values()) {
            BotSetupForm.Slider kind = BotSetupForm.Slider.valueOf(trait.name());
            SliderRow row = new SliderRow(ui, kind, kind.min(), value -> moved.add(trait));
            sliders.put(trait, row);
            sliderList.add(row.row()).padTop(sliders.size() == 1 ? 0 : space("md")).row();
        }
        customize = ui.collapsible(Messages.getString("Lobby.bot.customize"), sliderList, false);
        customize.setName("bot-customize");
        behaviorPage.add(customize).padTop(space("lg")).row();

        // ---- Targets ----
        targetsPage.top().left().defaults().left().growX();
        targetsPage.add(ui.caption(text("GpuBoard.hud.bots.priority"), true)).row();
        priorityList.top().left().defaults().left().growX();
        targetsPage.add(priorityList).padTop(space("sm")).row();

        targetsPage.add(ui.caption(text("GpuBoard.hud.bots.hexes"), true)).padTop(space("xl")).row();
        hexList.top().left().defaults().left().growX();
        targetsPage.add(hexList).padTop(space("sm")).row();
        pickHexes = ui.button("button-secondary-compact", "hex", text("GpuBoard.hud.bots.hexes.pick"), null);
        pickHexes.setName("bot-hex-pick");
        UiKit.onChange(pickHexes, this::pickHexes);
        pickStatus = hint(ui, "");
        pickStatus.setName("bot-hex-pick-status");
        UiButton pickDone = ui.button("button-primary" + UiTheme.COMPACT, "check",
              text("BotCommandPanel.HexPicker.done"), null);
        pickDone.setName("bot-hex-pick-done");
        UiKit.onChange(pickDone, () -> source.players().endPick(true));
        UiButton pickCancel = ui.button("button-quiet-compact", null, text("BotCommandPanel.HexPicker.cancel"), null);
        pickCancel.setName("bot-hex-pick-cancel");
        UiKit.onChange(pickCancel, () -> source.players().endPick(false));
        picking.setName("bot-hex-picking");
        picking.left();
        picking.add(pickStatus).colspan(3).growX().minWidth(0).left().row();
        picking.add(pickDone).left().padTop(space("sm"));
        picking.add(pickCancel).left().padTop(space("sm")).padLeft(space("sm"));
        picking.add().growX();
        pickSlot.left().fillX();
        pickSlot.setActor(pickHexes);
        targetsPage.add(pickSlot).padTop(space("sm")).row();

        targetsPage.add(ui.caption(text("GpuBoard.hud.bots.ignore"), true)).padTop(space("xl")).row();
        ignoreList.top().left().defaults().left().growX();
        targetsPage.add(ignoreList).padTop(space("sm")).row();
        ignoreTurrets = ui.button("button-secondary-compact", null,
              Messages.getString("BotCommandPanel.IgnoreTurrets.title"), null);
        ignoreTurrets.setName("bot-ignore-turrets");
        GpuBotControls.tip(ui, ignoreTurrets, Messages.getString("BotCommandPanel.IgnoreTurrets.tooltip"));
        UiKit.onChange(ignoreTurrets, () -> send(() -> orders().ignoreTurrets(id())));
        clearIgnored = ui.button("button-quiet-compact", null, text("GpuBoard.hud.bots.ignore.clear"), null);
        clearIgnored.setName("bot-ignore-clear");
        GpuBotControls.tip(ui, clearIgnored, Messages.getString("BotCommandPanel.ClearIgnoredTargets.tooltip"));
        UiKit.onChange(clearIgnored, () -> send(() -> orders().clearIgnored(id())));
        Table ignoreTools = new Table();
        ignoreTools.left();
        ignoreTools.add(ignoreTurrets);
        ignoreTools.add(clearIgnored).padLeft(space("sm"));
        ignoreTools.add().growX();
        targetsPage.add(ignoreTools).padTop(space("sm")).row();

        targetsPage.add(ui.caption(text("GpuBoard.hud.bots.players"), true)).padTop(space("xl")).row();
        playersList.top().left().defaults().left().growX();
        targetsPage.add(playersList).padTop(space("sm")).row();
        targetsPage.add(hint(ui, text("GpuBoard.hud.bots.players.hint"))).minWidth(0).padTop(space("xs")).row();

        tagContent.top().left().defaults().left().growX();
        tag = ui.collapsible(text("GpuBoard.hud.bots.tag"), tagContent, false);
        tag.setName("bot-tag");
        targetsPage.add(tag).padTop(space("xl")).row();

        page.setActor(behaviorPage);
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        GpuPlayers.Pick pick = inputs.frame().panels().players().pick();
        followPick(pick);
        boolean shownNow = state.dialog == Dialog.BOTS;
        if (shownNow != open) {
            open = shownNow;
            source.bots().setSheetOpen(open);
            if (!open) {
                Stage stage = root.getStage();
                if (stage != null && stage.getKeyboardFocus() != null
                      && stage.getKeyboardFocus().isDescendantOf(root)) {
                    stage.setKeyboardFocus(null);
                }
                moved.clear();
                shown = null;
            }
        }
        if (!open) {
            return;
        }
        GpuBots.Snapshot snapshot = inputs.frame().panels().bots();
        BotRow row = state.botSheetId < 0 && !snapshot.bots().isEmpty() ? snapshot.bots().getFirst()
              : snapshot.bot(state.botSheetId);
        if (row == null) {
            // The bot left the game, or the player may no longer command it
            state.dialog = Dialog.NONE;
            return;
        }
        if (state.botSheetId != row.id()) {
            state.botSheetId = row.id();
        }
        commandable = row.behavior() != null;
        sendMovedSliders(row);
        if (snapshot != shownSnapshot || row != shown) {
            show(snapshot, row);
        }
        showPick(pick);
    }

    private GpuBotOrders orders() {
        return source.botOrders();
    }

    private int id() {
        return state.botSheetId;
    }

    private void close() {
        state.dialog = Dialog.NONE;
    }

    /** Runs a command and shows the bot's values again at the next update, over a control the click changed. */
    private void send(Runnable command) {
        if (commandable) {
            command.run();
        }
        shown = null;
    }

    private void showPage() {
        page.setActor(tabs.getSelectedIndex() == TARGETS ? targetsPage : behaviorPage);
        scroll.setScrollY(0);
    }

    /** Sends each moved slider's value once its drag ends, or right away when a key moved it. */
    private void sendMovedSliders(BotRow row) {
        if (moved.isEmpty() || row.behavior() == null) {
            return;
        }
        for (Trait trait : List.copyOf(moved)) {
            SliderRow slider = sliders.get(trait);
            if (!slider.slider().isDragging()) {
                moved.remove(trait);
                int value = slider.value();
                if (!Objects.equals(row.behavior().traits().get(trait), value)) {
                    orders().trait(row.id(), trait, value);
                }
            }
        }
    }

    private void show(GpuBots.Snapshot snapshot, BotRow row) {
        shownSnapshot = snapshot;
        shown = row;
        Behavior behavior = row.behavior();
        boolean ready = behavior != null;
        title.setText(row.name());
        showSwitcher(snapshot, row);
        scope.setText(text("GpuBoard.hud.bots.scope", row.units()));
        sending.setVisible(row.isSending());
        noReply.setVisible(!row.isSending() && row.noReply());
        waiting.setVisible(!ready && !row.isSending());

        if (!snapshot.personalities().equals(cardsFor)) {
            cardsFor = snapshot.personalities();
            Map<Personality, BehaviorSettings> presets = snapshot.presets();
            cards = new PersonalityCards(kit.ui, cardsFor, presets::get, 2,
                  personality -> send(() -> orders().personality(id(), personality)));
            cardsSlot.setActor(cards.grid());
        }
        int[] now = new int[GpuBotControls.TRAITS.size()];
        if (ready) {
            for (int index = 0; index < now.length; index++) {
                now[index] = behavior.traits().get(Trait.valueOf(GpuBotControls.TRAITS.get(index).name()));
            }
        }
        cards.show(ready ? behavior.personality() : null, false, now, true);
        for (Personality personality : cardsFor) {
            if (cards.card(personality) != null) {
                cards.card(personality).setDisabled(!ready);
            }
        }
        tacticChips.getColor().a = ready ? 1 : WAITING_ALPHA;
        posture.setDisabled(!ready);
        if (ready) {
            posture.select(behavior.posture());
        }
        for (Map.Entry<Tactic, UiFilterChip> entry : tactics.entrySet()) {
            entry.getValue().setDisabled(!ready);
            entry.getValue().setChecked(ready && matches(entry.getKey(), behavior));
        }
        boolean holding = row.status().holdingPosition();
        holdSlot.setActor(holding ? resume : hold);
        hold.setDisabled(!ready);
        resume.setDisabled(!ready);
        holdHint.setText(text(holding ? "GpuBoard.hud.bots.resume.hint" : "GpuBoard.hud.bots.hold.hint"));
        retreat.setDisabled(!ready);
        retreatEdge.setDisabled(!ready);
        boolean leaving = ready && behavior.retreat() != CardinalEdge.NONE;
        retreat.select(leaving ? 1 : 0);
        if (leaving) {
            retreatEdge.select(Math.max(0, BotSetupForm.edges().indexOf(behavior.retreat())));
        }
        retreatHint.setText(Messages.getString("Lobby.bot.retreat." + RETREATS[leaving ? 1 : 0] + ".hint"));
        for (Map.Entry<Trait, SliderRow> entry : sliders.entrySet()) {
            SliderRow slider = entry.getValue();
            slider.slider().setDisabled(!ready);
            if (ready && !moved.contains(entry.getKey()) && !slider.slider().isDragging()) {
                slider.value(behavior.traits().get(entry.getKey()));
            }
        }
        customize.detail(ready && behavior.personality() == null ? text("GpuBoard.hud.bots.custom") : "");

        showPriorities(row);
        showHexes(row);
        showIgnored(row);
        showPlayers(row);
        showTag(row);
    }

    /** With more than one commandable bot, a select above the banner picks the bot the sheet shows. */
    private void showSwitcher(GpuBots.Snapshot snapshot, BotRow row) {
        List<Integer> ids = snapshot.bots().stream().map(BotRow::id).toList();
        if (ids.size() < 2) {
            switcherIds = ids;
            switcherSlot.setActor(null);
            return;
        }
        UiSelect switcher = switcherSlot.getActor();
        if (switcher == null || !ids.equals(switcherIds)) {
            switcherIds = ids;
            switcher = kit.ui.tokenSelect(true, snapshot.bots().stream().map(BotRow::name).toArray(String[]::new));
            switcher.setName("bot-sheet-switch");
            UiSelect chosen = switcher;
            UiKit.onChange(switcher, () -> {
                state.botSheetId = switcherIds.get(chosen.getSelectedIndex());
                moved.clear();
                shown = null;
            });
            switcherSlot.setActor(switcher);
            switcherSlot.padTop(space("sm"));
        }
        switcher.select(ids.indexOf(row.id()));
    }

    /** Whether the bot's indices are all the tactic's; No Prisoners has none, so it never shows as chosen. */
    private static boolean matches(Tactic tactic, Behavior behavior) {
        return !tactic.indices().isEmpty() && tactic.indices().stream()
              .allMatch(index -> Objects.equals(behavior.traits().get(index.trait()), index.value()));
    }

    // ---- Targets ----

    private void showPriorities(BotRow row) {
        Behavior behavior = row.behavior();
        Map<Integer, Integer> priorities = behavior == null ? Map.of() : behavior.priorities();
        List<Object> key = Arrays.asList(behavior == null, priorities, row.enemies(), row.enemyPlayers());
        if (key.equals(priorityShown)) {
            return;
        }
        priorityShown = key;
        priorityList.clearChildren();
        boolean ready = behavior != null;
        List<Integer> ids = new ArrayList<>(priorities.keySet());
        ids.sort(Comparator.comparingInt((Integer unit) -> priorities.get(unit))
              .thenComparing(unit -> unitName(row, unit)).thenComparingInt(unit -> unit));
        if (ids.isEmpty()) {
            priorityList.add(hint(kit.ui, text("GpuBoard.hud.bots.priority.none"))).minWidth(0).row();
        }
        List<String> levels = new ArrayList<>();
        for (int level = BehaviorSettings.HIGHEST_TARGET_PRIORITY; level <= BehaviorSettings.LOWEST_TARGET_PRIORITY;
              level++) {
            levels.add(text("GpuBoard.hud.bots.priority." + level));
        }
        for (int unit : ids) {
            Table line = new Table();
            line.left();
            line.add(unitLabel(row, unit)).growX().minWidth(0).left();
            UiStepper level = kit.ui.selector(levels, priorities.get(unit) - BehaviorSettings.HIGHEST_TARGET_PRIORITY);
            level.setName("bot-priority-" + unit);
            GpuBotControls.tip(kit.ui, level, Messages.getString("BotConfigDialog.pickerPriorityTip"));
            level.down().setDisabled(!ready);
            level.up().setDisabled(!ready);
            UiKit.onChange(level, () -> send(() -> orders().priority(id(), unit,
                  level.value() + BehaviorSettings.HIGHEST_TARGET_PRIORITY)));
            line.add(level).padLeft(space("sm"));
            UiButton remove = kit.ui.button("button-icon-compact", "close", null, null);
            remove.setName("bot-priority-remove-" + unit);
            remove.setDisabled(!ready);
            GpuBotControls.tip(kit.ui, remove, text("GpuBoard.hud.bots.priority.remove", unitName(row, unit)));
            UiKit.onChange(remove, () -> send(() -> orders().priority(id(), unit, 0)));
            line.add(remove).padLeft(space("xs"));
            priorityList.add(line).padTop(space("xs")).row();
        }
        List<Enemy> addable = row.enemies().stream().filter(enemy -> !priorities.containsKey(enemy.id())).toList();
        UiSelect add = enemySelect(row, addable, text("GpuBoard.hud.bots.priority.add"), !ready);
        add.setName("bot-priority-add");
        UiKit.onChange(add, () -> {
            int unit = addable.get(add.getSelectedIndex()).id();
            send(() -> orders().priority(id(), unit, BehaviorSettings.DEFAULT_TARGET_PRIORITY));
        });
        priorityList.add(add).left().expand(0, 0).fill(0f, 0f).padTop(space("sm")).row();
    }

    private void showHexes(BotRow row) {
        boolean ready = row.behavior() != null;
        List<Coords> hexes = new ArrayList<>(row.status().strategicHexes());
        hexes.sort(Comparator.comparingInt(Coords::getX).thenComparingInt(Coords::getY));
        List<Object> key = Arrays.asList(ready, hexes);
        if (key.equals(hexesShown)) {
            return;
        }
        hexesShown = key;
        hexList.clearChildren();
        if (hexes.isEmpty()) {
            hexList.add(hint(kit.ui, Messages.getString("Lobby.bot.hexNone"))).minWidth(0).row();
        }
        for (Coords hex : hexes) {
            String name = Messages.getString("BotConfigDialog.hexListIntro", hex.getX() + 1, hex.getY() + 1);
            Table line = new Table();
            line.left();
            line.add(kit.ui.typeLabel(name, "body", "text.primary")).growX().minWidth(0).left();
            UiButton remove = kit.ui.button("button-icon-compact", "close", null, null);
            remove.setName("bot-hex-remove-" + hex.getX() + "-" + hex.getY());
            remove.setDisabled(!ready);
            GpuBotControls.tip(kit.ui, remove, Messages.getString("Lobby.bot.removeHex", name));
            UiKit.onChange(remove, () -> send(() -> orders().hex(id(), hex, false)));
            line.add(remove).padLeft(space("xs"));
            hexList.add(line).padTop(space("xxs")).row();
        }
    }

    /**
     * Starts a pick of strategic hexes on the board, with the sheet open: board clicks pick and unpick hexes, the
     * pick area's Done (or the Done key) adds them and its Cancel (or Esc) adds none.
     */
    private void pickHexes() {
        if (!commandable) {
            return;
        }
        pickingFor = id();
        pickSeen = false;
        pickAsked = TimeUtils.millis();
        orders().pickHexes(id(), text("GpuBoard.hud.bots.hexes.order", title.getText().toString()));
    }

    /** Ends the sheet's pick once it ends on the board, or when it never started (no board to pick on). */
    private void followPick(GpuPlayers.Pick pick) {
        if (pickingFor < 0) {
            return;
        }
        if (pick != null) {
            pickSeen = true;
        } else if (pickSeen || TimeUtils.timeSinceMillis(pickAsked) >= PICK_START_MILLIS) {
            pickingFor = -1;
            pickSeen = false;
        }
    }

    /**
     * The pick area under the strategic hexes: Pick on board, or while the shown bot's pick runs, the hexes it has
     * picked with Done and Cancel. Pick on board waits while another pick runs.
     */
    private void showPick(GpuPlayers.Pick pick) {
        boolean ours = pickingFor >= 0 && pickingFor == id();
        List<Object> key = Arrays.asList(ours, ours && pick != null ? pick.status() : null, commandable,
              pick != null);
        if (key.equals(pickShown)) {
            return;
        }
        pickShown = key;
        if (ours) {
            String status = pick == null ? "" : pick.status();
            pickStatus.setText(status.isEmpty() ? text("GpuBoard.hud.bots.hexes.picking") : status);
            pickSlot.setActor(picking);
            pickSlot.fill(1, 0);
        } else {
            pickHexes.setDisabled(!commandable || pick != null);
            pickSlot.setActor(pickHexes);
            pickSlot.fill(0, 0);
        }
    }

    private void showIgnored(BotRow row) {
        Behavior behavior = row.behavior();
        Set<Integer> ignored = behavior == null ? Set.of() : behavior.ignored();
        List<Object> key = Arrays.asList(behavior == null, ignored, row.enemies());
        if (key.equals(ignoreShown)) {
            return;
        }
        ignoreShown = key;
        boolean ready = behavior != null;
        ignoreList.clearChildren();
        List<Integer> ids = new ArrayList<>(ignored);
        ids.sort(Comparator.comparing((Integer unit) -> unitName(row, unit)).thenComparingInt(unit -> unit));
        if (ids.isEmpty()) {
            ignoreList.add(hint(kit.ui, text("GpuBoard.hud.bots.ignore.none"))).minWidth(0).row();
        } else {
            HorizontalGroup chips = new HorizontalGroup();
            chips.wrap().rowLeft().left().space(space("xs")).wrapSpace(space("xs"));
            for (int unit : ids) {
                String name = unitName(row, unit);
                UiButton chip = kit.ui.button("button-secondary-compact", "close", name, null);
                chip.setName("bot-ignored-" + unit);
                chip.setDisabled(!ready);
                GpuBotControls.tip(kit.ui, chip, text("GpuBoard.hud.bots.ignore.remove", name));
                UiKit.onChange(chip, () -> send(() -> orders().ignore(id(), Set.of(unit), false)));
                chips.addActor(chip);
            }
            ignoreList.add(chips).minWidth(0).row();
        }
        List<Enemy> addable = row.enemies().stream().filter(enemy -> !ignored.contains(enemy.id())).toList();
        UiSelect add = enemySelect(row, addable, text("GpuBoard.hud.bots.ignore.add"), !ready);
        add.setName("bot-ignore-add");
        UiKit.onChange(add, () -> {
            int unit = addable.get(add.getSelectedIndex()).id();
            send(() -> orders().ignore(id(), Set.of(unit), true));
        });
        ignoreList.add(add).left().expand(0, 0).fill(0f, 0f).padTop(space("sm")).row();
        ignoreTurrets.setDisabled(!ready || addable.stream().noneMatch(Enemy::turret));
        clearIgnored.setDisabled(!ready || ignored.isEmpty());
    }

    private void showPlayers(BotRow row) {
        Behavior behavior = row.behavior();
        Set<Integer> ignored = behavior == null ? Set.of() : behavior.ignored();
        List<Object> key = Arrays.asList(behavior == null, ignored, row.enemies(), row.enemyPlayers());
        if (key.equals(playersShown)) {
            return;
        }
        playersShown = key;
        boolean ready = behavior != null;
        playersList.clearChildren();
        if (row.enemyPlayers().isEmpty()) {
            playersList.add(hint(kit.ui, text("GpuBoard.hud.bots.players.none"))).minWidth(0).row();
        }
        for (EnemyPlayer player : row.enemyPlayers()) {
            Set<Integer> units = new HashSet<>();
            for (Enemy enemy : row.enemies()) {
                if (enemy.ownerId() == player.id()) {
                    units.add(enemy.id());
                }
            }
            int stance = player.dishonored() ? FEUD
                  : !units.isEmpty() && ignored.containsAll(units) ? IGNORE : NORMAL;
            Table line = new Table();
            line.left();
            Label name = kit.ui.typeLabel(player.name(), "body", "text.primary");
            name.setEllipsis(true);
            line.add(name).growX().minWidth(0).left();
            UiSelect select = kit.ui.tokenSelect(true, text("GpuBoard.hud.bots.stance.normal"),
                  text("GpuBoard.hud.bots.stance.feud"), text("GpuBoard.hud.bots.stance.ignore"));
            select.setName("bot-stance-" + player.id());
            select.select(stance);
            // A blood feud is for good: the bot never forgives the player
            select.setDisabled(!ready || player.dishonored());
            UiKit.onChange(select, () -> {
                int chosen = select.getSelectedIndex();
                if (chosen == FEUD) {
                    send(() -> orders().bloodFeud(id(), player.id()));
                } else if (chosen == IGNORE && !units.isEmpty()) {
                    send(() -> orders().ignore(id(), units, true));
                } else if (chosen == NORMAL && stance == IGNORE) {
                    send(() -> orders().ignore(id(), units, false));
                } else {
                    shown = null;
                }
            });
            line.add(select).padLeft(space("sm"));
            playersList.add(line).padTop(space("xxs")).row();
        }
    }

    private void showTag(BotRow row) {
        boolean ready = row.behavior() != null;
        List<Integer> tags = row.status().tagTargets().stream().sorted().toList();
        List<Object> key = Arrays.asList(ready, row.tag(), tags, row.enemies());
        if (key.equals(tagShown)) {
            return;
        }
        tagShown = key;
        tag.setDisabled(!row.tag());
        tag.detail(row.tag() ? "" : text("GpuBoard.hud.bots.tag.only"));
        if (!row.tag() && tag.isOpen()) {
            tag.open(false);
        }
        tagContent.clearChildren();
        List<Enemy> enemies = row.enemies();
        UiSelect pick = enemySelect(row, enemies, text("GpuBoard.hud.bots.tag.pick"), !ready || !row.tag());
        pick.setName("bot-tag-pick");
        UiButton designate = kit.ui.button("button-secondary-compact", "tag", text("GpuBoard.hud.bots.tag.designate"),
              null);
        designate.setName("bot-tag-designate");
        GpuBotControls.tip(kit.ui, designate, Messages.getString("BotCommandPanel.TagTargetMenu.tooltip"));
        designate.setDisabled(true);
        UiKit.onChange(pick, () -> designate.setDisabled(pick.getSelectedIndex() < 0));
        UiKit.onChange(designate, () -> {
            int index = pick.getSelectedIndex();
            if (index >= 0) {
                int unit = enemies.get(index).id();
                send(() -> orders().tag(id(), unit));
            }
        });
        Table pickRow = new Table();
        pickRow.left();
        pickRow.add(pick).growX().minWidth(0);
        pickRow.add(designate).padLeft(space("sm"));
        tagContent.add(pickRow).padTop(space("sm")).row();
        String current = tags.isEmpty() ? text("GpuBoard.hud.bots.tag.none") : text("GpuBoard.hud.bots.tag.current",
              String.join(", ", tags.stream().map(unit -> unitName(row, unit)).toList()));
        Label currentLabel = hint(kit.ui, current);
        currentLabel.setName("bot-tag-current");
        UiButton clear = kit.ui.button("button-quiet-compact", null, text("GpuBoard.hud.bots.tag.clear"), null);
        clear.setName("bot-tag-clear");
        clear.setDisabled(!ready || tags.isEmpty());
        UiKit.onChange(clear, () -> send(() -> orders().clearTags(id())));
        Table currentRow = new Table();
        currentRow.left();
        currentRow.add(currentLabel).growX().minWidth(0);
        currentRow.add(clear).padLeft(space("sm"));
        tagContent.add(currentRow).padTop(space("sm")).row();
    }

    /** A select of {@code enemies}, showing {@code placeholder} until one is chosen; disabled when there are none. */
    private UiSelect enemySelect(BotRow row, List<Enemy> enemies, String placeholder, boolean disabled) {
        UiSelect select = kit.ui.tokenSelect(true,
              enemies.stream().map(enemy -> enemy.name() + " \u00b7 " + ownerName(row, enemy.ownerId()))
                    .toArray(String[]::new));
        select.placeholder(placeholder);
        select.select(-1);
        select.setDisabled(disabled || enemies.isEmpty());
        return select;
    }

    /** A unit's name over its owner, for a target row. */
    private Table unitLabel(BotRow row, int unit) {
        Table label = new Table();
        label.left();
        Label name = kit.ui.typeLabel(unitName(row, unit), "body.strong", "text.primary");
        name.setEllipsis(true);
        label.add(name).left().growX().minWidth(0).row();
        Enemy enemy = enemy(row, unit);
        if (enemy != null) {
            Label owner = kit.ui.typeLabel(text("GpuBoard.hud.bots.enemy", ownerName(row, enemy.ownerId())), "small",
                  "text.muted");
            owner.setEllipsis(true);
            label.add(owner).left().growX().minWidth(0);
        }
        return label;
    }

    private static Enemy enemy(BotRow row, int unit) {
        return row.enemies().stream().filter(enemy -> enemy.id() == unit).findFirst().orElse(null);
    }

    /** The unit's name, or its number when the bot's side cannot see it (any more). */
    private static String unitName(BotRow row, int unit) {
        Enemy enemy = enemy(row, unit);
        return enemy == null ? text("GpuBoard.hud.bots.unit", unit) : enemy.name();
    }

    private static String ownerName(BotRow row, int ownerId) {
        return row.enemyPlayers().stream().filter(player -> player.id() == ownerId).map(EnemyPlayer::name)
              .findFirst().orElse("");
    }
}
