/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static com.badlogic.gdx.scenes.scene2d.actions.Actions.forever;
import static com.badlogic.gdx.scenes.scene2d.actions.Actions.run;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Value;
import com.badlogic.gdx.utils.Align;
import megamek.client.bot.AIType;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.bot.princess.CardinalEdge;
import megamek.client.bot.princess.CombatPosture;
import megamek.client.lobby.BotSetupForm;
import megamek.client.lobby.BotSetupForm.Flag;
import megamek.client.lobby.BotSetupForm.HexTarget;
import megamek.client.lobby.BotSetupForm.Personality;
import megamek.client.lobby.BotSetupForm.Preset;
import megamek.client.lobby.BotSetupForm.PresetKind;
import megamek.client.lobby.BotSetupForm.Retreat;
import megamek.client.lobby.BotTargetPicker;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiCollapsible;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiFormField;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSegmented;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiSliderInput;
import megamek.client.ui.gdx.UiSummaryCard;
import megamek.client.ui.gdx.UiTextField;
import megamek.client.ui.gdx.UiTraitCard;

/**
 * The lobby's bot setup in a {@link UiDialog}, which leads with good defaults and tucks the tuning away. On top, what
 * most players set: the bot's name (a new bot only), its personality, picked from cards that each show how the bot
 * fights, its combat posture and when it retreats. Below, closed until wanted, Customize holds the behaviour sliders
 * and the saved presets, and Advanced the facing sliders, the preset switches, the AI and the strategic targets, which
 * open on a page of their own with Back as its footer's main button. A personality is a stored preset under a friendly
 * name ({@link Personality}); changing its settings marks its card Modified, with a reset button, and its meters show
 * the change. Nothing moves when a choice changes what shows: hints keep the height of their longest text and buttons
 * that do not apply are disabled, not hidden. The values are held by a {@link BotSetupForm}; OK stays disabled while
 * the name is wrong.
 * <p>
 * GPU thread. The preset file is written on the Swing thread through {@link Host#edt}.
 */
final class LobbyBotSetupView {
    /** What the view asks of the window. */
    interface Host {
        /** Runs {@code work} on the Swing thread, then {@code then} back on the GPU thread. */
        void edt(Runnable work, Runnable then);

        /** Opens the Princess help. */
        void help();

        /** Takes the confirmed setup; the dialog closes. */
        void confirm(LobbyPlayerController.BotSetup setup);
    }

    /** Below this body width the cards go two to a row and the posture goes above the retreat. */
    private static final float STACK_BELOW = 600;
    /** The sliders Customize shows, in the cards' order; the rest are Advanced. */
    private static final List<BotSetupForm.Slider> CORE = List.of(BotSetupForm.Slider.AGGRESSION,
          BotSetupForm.Slider.BRAVERY, BotSetupForm.Slider.SELF_PRESERVATION, BotSetupForm.Slider.MUTUAL_SUPPORT,
          BotSetupForm.Slider.FALL_SHAME);
    /** The sliders the cards' meters show, in their order. */
    private static final List<BotSetupForm.Slider> TRAITS = List.of(BotSetupForm.Slider.AGGRESSION,
          BotSetupForm.Slider.BRAVERY, BotSetupForm.Slider.SELF_PRESERVATION, BotSetupForm.Slider.MUTUAL_SUPPORT);
    private static final String[] TRAIT_KEYS = { "aggression", "bravery", "survival", "teamwork" };
    private static final CombatPosture[] POSTURES = { CombatPosture.AUTO, CombatPosture.ATTACK,
                                                      CombatPosture.DEFEND };

    private final UiKit kit;
    private final UiDialog dialog;
    private final BotSetupForm form;
    private final Host host;
    private final Runnable close;
    private final boolean stacked;
    private final Map<Personality, UiTraitCard> cards = new EnumMap<>(Personality.class);
    private final Map<Personality, int[]> cardTraits = new EnumMap<>(Personality.class);
    private final Map<BotSetupForm.Slider, Slider> sliders = new EnumMap<>(BotSetupForm.Slider.class);
    private final Map<BotSetupForm.Slider, Label> sliderTitles = new EnumMap<>(BotSetupForm.Slider.class);
    private final Map<Flag, UiChoice> flags = new EnumMap<>(Flag.class);
    private final List<Preset> savedPresets = new ArrayList<>();
    private final Container<Table> page = new Container<>();
    private final Container<UiSelect> presetHolder = new Container<>();
    private final Table pickerList = new Table();
    private final Table hexList = new Table();
    private final Table mainPage;
    private final Table targetsPage;
    private UiButton ok;
    private UiFormField nameField;
    private Label status;
    private UiSegmented posture;
    private Label postureHint;
    private UiSegmented retreat;
    private UiSelect retreatEdge;
    private Label retreatHint;
    private Label pirateNote;
    private UiCollapsible customize;
    private UiCollapsible advanced;
    private UiSelect presetSelect;
    private UiButton save;
    private UiButton delete;
    private UiButton saveNew;
    private UiTextField newName;
    private UiFormField newNameField;
    private UiSummaryCard targetsCard;
    private Label pickerCount;
    private UiFormField hexField;
    private UiTextField hexInput;
    private boolean busy;
    private boolean pickerDue;

    /**
     * Fills {@code dialog}'s body and footer.
     *
     * @param bodyWidth the width the body will have in this window
     * @param close     closes the dialog
     */
    LobbyBotSetupView(UiKit kit, UiDialog dialog, BotSetupForm form, float bodyWidth, Host host, Runnable close) {
        this.kit = kit;
        this.dialog = dialog;
        this.form = form;
        this.host = host;
        this.close = close;
        stacked = bodyWidth < STACK_BELOW;
        Table body = dialog.body();
        body.defaults().left().growX();

        mainPage = mainPage();
        targetsPage = targetsPage();
        page.fill().align(Align.topLeft);
        page.setActor(mainPage);
        body.add(page).growX().row();

        footer(true);

        showValues();
        refresh();
        // An actor of its own: the layer's actions are its fade, which showing it clears
        Actor pump = new Actor();
        pump.addAction(forever(run(this::drain)));
        dialog.layer().addActor(pump);
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static float size(String name) {
        return DesignTokens.shipped().size().get(name);
    }

    private static String colonless(String key) {
        String text = Messages.getString(key).strip();
        return text.endsWith(":") ? text.substring(0, text.length() - 1) : text;
    }

    private void tip(Actor actor, String text) {
        kit.tip(actor, true).getActor().setText(GpuBoardWindow.plainText(text));
    }

    private Label hint(String text) {
        Label hint = kit.typeLabel(text, "small", "text.muted");
        hint.setWrap(true);
        hint.setAlignment(Align.topLeft);
        return hint;
    }

    /**
     * {@code shown} over hidden copies of each of {@code texts}, so the slot keeps the height of the longest and what
     * is below never moves as the text changes; {@code shown} may be one of several labels in the slot.
     */
    private Stack steady(List<String> texts, Label... shown) {
        Stack slot = new Stack();
        for (String text : texts) {
            Label sizer = hint(text);
            sizer.setVisible(false);
            slot.add(sizer);
        }
        for (Label each : shown) {
            slot.add(each);
        }
        return slot;
    }

    /** The main page's footer (OK, Cancel and Help) or an inner page's (Back as the main button, and Help). */
    private void footer(boolean main) {
        dialog.clearFooter();
        if (main) {
            ok = dialog.primary(Messages.getString("Ok.text"), this::confirm);
            ok.setName("bot-ok");
            dialog.cancelButton(Messages.getString("Cancel.text")).setName("bot-cancel");
            dialog.back(null);
        } else {
            dialog.primary(Messages.getString("Lobby.bot.back"), () -> showPage(mainPage)).setName("bot-targets-back");
            dialog.back(() -> showPage(mainPage));
        }
        dialog.tool(Messages.getString("BotConfigDialog.help"), host::help).setName("bot-help");
    }

    /** Scrolls the body just enough to show {@code section}, or its top when it is higher than the view. */
    private void reveal(Actor section) {
        ScrollPane scroll = dialog.scroll();
        dialog.layer().validate();
        Vector2 spot = section.localToAscendantCoordinates(scroll.getActor(), new Vector2());
        float height = Math.min(section.getHeight(), scroll.getScrollHeight());
        scroll.scrollTo(spot.x, spot.y + section.getHeight() - height, section.getWidth(), height);
    }

    /** Two blocks side by side, top-aligned and sharing the width, or one above the other when stacked. */
    private Table pair(Actor first, Actor second, float gap) {
        Table pair = new Table();
        pair.top().left();
        if (stacked) {
            pair.add(first).growX().minWidth(0).row();
            pair.add(second).growX().minWidth(0).padTop(gap);
        } else {
            pair.add(first).growX().uniformX().minWidth(0).top();
            pair.add(second).growX().uniformX().minWidth(0).top().padLeft(space("xl"));
        }
        return pair;
    }

    // ---- The main page ----

    private Table mainPage() {
        Table content = new Table();
        content.top().left().defaults().left().growX();
        content.add(name()).row();
        Table caption = new Table();
        caption.left();
        caption.add(kit.caption(Messages.getString("Lobby.bot.personality"), true)).left();
        status = kit.typeLabel("", "small", "text.secondary");
        status.setName("bot-status");
        status.setEllipsis(true);
        status.setAlignment(Align.right);
        caption.add(status).growX().minWidth(0).right().padLeft(space("md"));
        content.add(caption).padTop(space(form.newBot() ? "xl" : "lg")).row();
        content.add(personalities()).padTop(space("sm")).row();
        content.add(pair(postureBlock(), retreatBlock(), space("lg"))).padTop(space("lg")).row();

        customize = kit.collapsible(Messages.getString("Lobby.bot.customize"), customizeContent(), false);
        customize.setName("bot-customize");
        customize.header().setName("bot-customize-header");
        content.add(customize).padTop(space("xl")).row();
        advanced = kit.collapsible(Messages.getString("Lobby.bot.advanced"), advancedContent(), false);
        advanced.setName("bot-advanced");
        advanced.header().setName("bot-advanced-header");
        content.add(advanced).padTop(space("sm")).row();
        customize.onToggle(() -> {
            if (customize.isOpen()) {
                reveal(customize);
            }
        });
        advanced.onToggle(() -> {
            if (advanced.isOpen()) {
                reveal(advanced);
            }
        });
        // Settings no personality explains open where they can be read
        if (form.basedOn() == null && form.personality().isEmpty()) {
            customize.open(true);
        }
        return content;
    }

    private Actor name() {
        if (form.newBot()) {
            UiTextField name = kit.input(form.name(), true);
            name.setName("bot-name");
            UiKit.onChange(name, () -> {
                form.name(name.getText());
                refresh();
            });
            nameField = kit.formField(Messages.getString("Lobby.bot.name"), name);
            Table row = new Table();
            row.left();
            row.add(nameField).width(stacked ? size("dialog.sm") / 2 : size("dialog.sm") * 0.6f).left();
            row.add().growX();
            return row;
        }
        Label named = kit.typeLabel(form.name(), "title", "text.primary");
        named.setEllipsis(true);
        return named;
    }

    private Table personalities() {
        Table grid = new Table();
        grid.top().left();
        List<Personality> shown = form.personalities();
        int columns = stacked ? 2 : 4;
        float gap = space("md");
        for (int index = 0; index < shown.size(); index++) {
            Personality personality = shown.get(index);
            UiTraitCard card = kit.traitCard(personality.label(), personality.blurb());
            card.setName("bot-personality-" + personality.key());
            BehaviorSettings preset = form.preset(personality);
            if (preset != null) {
                int[] values = new int[TRAITS.size()];
                for (int trait = 0; trait < TRAITS.size(); trait++) {
                    values[trait] = TRAITS.get(trait).of(preset);
                    card.trait(Messages.getString("Lobby.bot.trait." + TRAIT_KEYS[trait]), values[trait],
                          TRAITS.get(trait).max());
                }
                cardTraits.put(personality, values);
            }
            card.resetButton().setName("bot-reset-" + personality.key());
            tip(card.resetButton(), Messages.getString("Lobby.bot.resetTip", personality.label()));
            // A click on the card, or on its reset, goes back to the personality's own settings
            UiKit.onChange(card, () -> {
                if (!busy) {
                    form.choose(personality);
                    showValues();
                    refresh();
                }
            });
            cards.put(personality, card);
            int column = index % columns;
            grid.add(card).growX().uniform().fill().minWidth(0)
                  .padLeft(column == 0 ? 0 : gap).padTop(index < columns ? 0 : gap);
            if (column == columns - 1) {
                grid.row();
            }
        }
        // A short last row keeps its cards the width of the rest
        for (int index = shown.size(); index % columns != 0; index++) {
            grid.add().growX().uniform().padLeft(gap);
        }
        return grid;
    }

    private Table postureBlock() {
        Table block = new Table();
        block.top().left().defaults().left().growX();
        String[] labels = new String[POSTURES.length];
        for (int index = 0; index < POSTURES.length; index++) {
            labels[index] = POSTURES[index].toString();
        }
        posture = kit.tokenSegmented(true, labels);
        posture.setName("bot-posture");
        for (int index = 0; index < POSTURES.length; index++) {
            posture.option(index).setName("bot-posture-" + POSTURES[index].name().toLowerCase(Locale.ROOT));
        }
        UiKit.onChange(posture, () -> {
            form.posture(POSTURES[posture.getSelectedIndex()]);
            refresh();
        });
        block.add(kit.formField(colonless("BotConfigDialog.postureLabel"), posture)).row();
        postureHint = hint("");
        postureHint.setName("bot-posture-hint");
        List<String> hints = new ArrayList<>();
        for (CombatPosture each : POSTURES) {
            hints.add(Messages.getString("Lobby.bot.posture." + each.name().toLowerCase(Locale.ROOT) + ".hint"));
        }
        block.add(steady(hints, postureHint)).minWidth(0).padTop(space("xs")).row();
        return block;
    }

    private Table retreatBlock() {
        Table block = new Table();
        block.top().left().defaults().left().growX();
        Retreat[] choices = Retreat.values();
        String[] labels = new String[choices.length];
        for (int index = 0; index < choices.length; index++) {
            labels[index] = Messages.getString("Lobby.bot.retreat." + choices[index].name().toLowerCase(Locale.ROOT));
        }
        retreat = kit.tokenSegmented(true, labels);
        retreat.setName("bot-retreat");
        for (int index = 0; index < choices.length; index++) {
            retreat.option(index).setName("bot-retreat-" + choices[index].name().toLowerCase(Locale.ROOT));
        }
        UiKit.onChange(retreat, () -> {
            form.retreat(choices[retreat.getSelectedIndex()]);
            retreatEdge.select(Math.max(0, BotSetupForm.edges().indexOf(form.retreatTo())));
            refresh();
        });
        block.add(kit.formField(Messages.getString("Lobby.bot.retreat"), retreat)).row();

        List<CardinalEdge> edges = BotSetupForm.edges();
        String[] edgeNames = new String[edges.size()];
        for (int index = 0; index < edges.size(); index++) {
            edgeNames[index] = Messages.getString("Lobby.bot.edge." + edges.get(index).name().toLowerCase(Locale.ROOT));
        }
        retreatEdge = kit.tokenSelect(true, edgeNames);
        retreatEdge.setName("bot-retreat-edge");
        tip(retreatEdge, Messages.getString("BotConfigDialog.retreatEdgeTooltip"));
        UiKit.onChange(retreatEdge, () -> {
            form.retreatTo(edges.get(retreatEdge.getSelectedIndex()));
            refresh();
        });
        Table toward = new Table();
        toward.left();
        toward.add(kit.typeLabel(Messages.getString("Lobby.bot.toward"), "small", "text.secondary")).left();
        toward.add(retreatEdge).left().padLeft(space("sm"));
        toward.add().growX();
        block.add(toward).padTop(space("sm")).row();
        retreatHint = hint("");
        retreatHint.setName("bot-retreat-hint");
        pirateNote = kit.typeLabel(Messages.getString("Lobby.bot.retreat.pirate"), "small", "status.warning");
        pirateNote.setWrap(true);
        pirateNote.setName("bot-retreat-pirate");
        List<String> hints = new ArrayList<>();
        for (Retreat each : choices) {
            hints.add(Messages.getString("Lobby.bot.retreat." + each.name().toLowerCase(Locale.ROOT) + ".hint"));
        }
        hints.add(pirateNote.getText().toString());
        block.add(steady(hints, retreatHint, pirateNote)).minWidth(0).padTop(space("xs")).row();
        return block;
    }

    // ---- Customize ----

    private Table customizeContent() {
        Table content = new Table();
        content.top().left().defaults().left().growX();
        content.add(sliderGrid(CORE)).row();

        Table load = new Table();
        load.bottom().left();
        presetHolder.fill().align(Align.left);
        fillPresets();
        save = kit.button("button-secondary-compact", null, Messages.getString("Lobby.bot.saveChanges"), null);
        save.setName("bot-save");
        tip(save, Messages.getString("Lobby.bot.saveChangesTip"));
        UiKit.onChange(save, () -> presetWork(form::savePreset));
        delete = kit.button("button-quiet-compact", null, Messages.getString("Lobby.bot.deletePreset"), null);
        delete.setName("bot-delete");
        tip(delete, Messages.getString("Lobby.bot.deletePresetTip"));
        UiKit.onChange(delete, () -> {
            Preset chosen = form.selected();
            if (chosen != null && chosen.kind() == PresetKind.STORED) {
                presetWork(() -> form.deletePreset(chosen.name()));
            }
        });
        load.add(kit.formField(Messages.getString("Lobby.bot.savedPresets"), presetHolder)).growX().minWidth(0)
              .bottom();
        load.add(save).bottom().padLeft(space("sm"));
        load.add(delete).bottom().padLeft(space("xs"));

        Table saveAs = new Table();
        saveAs.bottom().left();
        newName = kit.input("", true);
        newName.setName("bot-preset-name");
        UiKit.onChange(newName, this::refresh);
        newNameField = kit.formField(Messages.getString("Lobby.bot.presetName"), newName);
        saveNew = kit.button("button-secondary-compact", null, Messages.getString("Lobby.bot.saveNew"), null);
        saveNew.setName("bot-save-new");
        tip(saveNew, Messages.getString("Lobby.bot.saveNewTip"));
        UiKit.onChange(saveNew, () -> {
            String typed = newName.getText();
            if (form.canSaveNew(typed)) {
                presetWork(() -> form.saveNewPreset(typed));
                newName.setText("");
            }
        });
        saveAs.add(newNameField).growX().minWidth(0).bottom();
        saveAs.add(saveNew).bottom().padLeft(space("sm"));
        content.add(pair(load, saveAs, space("md"))).padTop(space("lg")).row();
        content.add(hint(Messages.getString("Lobby.bot.presetsHint"))).minWidth(0).padTop(space("sm")).row();
        return content;
    }

    /** {@code kinds} two to a row (one when stacked), each with its title and value over its ends' words. */
    private Table sliderGrid(List<BotSetupForm.Slider> kinds) {
        Table grid = new Table();
        grid.top().left();
        int columns = stacked ? 1 : 2;
        for (int index = 0; index < kinds.size(); index++) {
            int column = index % columns;
            grid.add(slider(kinds.get(index))).growX().uniformX().minWidth(0).top()
                  .padLeft(column == 0 ? 0 : space("xl")).padTop(index < columns ? 0 : space("md"));
            if (column == columns - 1) {
                grid.row();
            }
        }
        if (kinds.size() % columns != 0) {
            grid.add().growX().uniformX().padLeft(space("xl"));
        }
        return grid;
    }

    private Table slider(BotSetupForm.Slider kind) {
        Slider slider = new Slider(kind.min(), kind.max(), 1, false, kit.tokenSliderStyle());
        slider.setName("bot-slider-" + kind.name().toLowerCase(Locale.ROOT));
        slider.setProgrammaticChangeEvents(false);
        slider.addListener(new UiSliderInput(slider));
        Label title = kit.typeLabel(kind.title(form.value(kind)), "small", "text.secondary");
        UiKit.onChange(slider, () -> {
            form.value(kind, Math.round(slider.getValue()));
            title.setText(kind.title(form.value(kind)));
            refresh();
        });
        tip(slider, kind.tip());
        tip(title, kind.tip());
        sliders.put(kind, slider);
        sliderTitles.put(kind, title);
        Table row = new Table();
        row.top().left();
        row.add(title).left().colspan(2).row();
        row.add(slider).growX().colspan(2).padTop(space("xs")).row();
        Label min = kit.typeLabel(kind.minText(), "small", "text.muted");
        Label max = kit.typeLabel(kind.maxText(), "small", "text.muted");
        min.setEllipsis(true);
        max.setEllipsis(true);
        max.setAlignment(Align.right);
        row.add(min).left().growX().uniformX().minWidth(0);
        row.add(max).right().growX().uniformX().minWidth(0).padLeft(space("sm"));
        return row;
    }

    /**
     * Rebuilds the saved presets select: the opening configuration, the stored presets that are no personality's by
     * name, then the other bots' configurations; the chosen one shows, else the placeholder.
     */
    private void fillPresets() {
        savedPresets.clear();
        List<Preset> stored = new ArrayList<>();
        List<Preset> others = new ArrayList<>();
        for (Preset preset : form.presets()) {
            switch (preset.kind()) {
                case PREVIOUS -> savedPresets.add(preset);
                case STORED -> {
                    if (Personality.of(preset.name()) == null) {
                        stored.add(preset);
                    }
                }
                case BOT -> others.add(preset);
            }
        }
        stored.sort(Comparator.comparing(Preset::label, String.CASE_INSENSITIVE_ORDER));
        savedPresets.addAll(stored);
        savedPresets.addAll(others);
        List<String> labels = new ArrayList<>();
        for (Preset preset : savedPresets) {
            labels.add(preset.kind() == PresetKind.BOT ? Messages.getString("Lobby.bot.presetBot", preset.label())
                  : preset.label());
        }
        presetSelect = kit.tokenSelect(true, labels.toArray(String[]::new));
        presetSelect.setName("bot-presets");
        presetSelect.placeholder(Messages.getString("Lobby.bot.presetsPlaceholder"));
        int chosen = savedPresets.indexOf(form.selected());
        if (chosen >= 0) {
            presetSelect.select(chosen);
        }
        UiKit.onChange(presetSelect, () -> {
            int index = presetSelect.getSelectedIndex();
            if (index >= 0 && !busy) {
                form.select(savedPresets.get(index));
                showValues();
                refresh();
            }
        });
        presetHolder.setActor(presetSelect);
    }

    /** Writes the preset file on the Swing thread, holding the buttons meanwhile, then shows the result. */
    private void presetWork(Runnable work) {
        if (busy) {
            return;
        }
        busy = true;
        refresh();
        host.edt(work, () -> {
            busy = false;
            fillPresets();
            showValues();
            refresh();
        });
    }

    // ---- Advanced ----

    private Table advancedContent() {
        Table content = new Table();
        content.top().left().defaults().left().growX();
        List<BotSetupForm.Slider> rest = new ArrayList<>();
        for (BotSetupForm.Slider kind : form.sliders()) {
            if (!CORE.contains(kind)) {
                rest.add(kind);
            }
        }
        content.add(sliderGrid(rest)).row();

        Table checks = new Table();
        checks.top().left().defaults().left();
        List<Flag> presetFlags = form.presetFlags();
        for (int index = 0; index < presetFlags.size(); index++) {
            checks.add(flag(presetFlags.get(index))).padTop(index == 0 ? 0 : space("xs")).row();
        }
        List<Actor> columns = new ArrayList<>();
        columns.add(kit.formField(Messages.getString("Lobby.bot.options"), checks));

        if (form.types().size() > 1) {
            Table radios = new Table();
            radios.top().left().defaults().left();
            List<UiChoice> choices = new ArrayList<>();
            for (AIType type : form.types()) {
                UiChoice radio = kit.radio(Messages.getString("BotConfigDialog.aiType." + type.name()),
                      type == form.type());
                radio.setName("bot-ai-" + type.name());
                if (form.typeLocked()) {
                    radio.setDisabled(true);
                    tip(radio, Messages.getString("BotConfigDialog.aiTypeLocked.tooltip"));
                } else if (Messages.keyExists("BotConfigDialog.aiType." + type.name() + ".tooltip")) {
                    tip(radio, Messages.getString("BotConfigDialog.aiType." + type.name() + ".tooltip"));
                }
                UiKit.onChange(radio, () -> {
                    if (radio.isChecked()) {
                        form.type(type);
                    }
                });
                radios.add(radio).padTop(choices.isEmpty() ? 0 : space("xs")).row();
                choices.add(radio);
            }
            kit.radioGroup(choices.toArray(UiChoice[]::new));
            columns.add(kit.formField(colonless("BotConfigDialog.aiTypeLabel"), radios));
        }

        targetsCard = kit.summaryCard(Messages.getString("BotConfigDialog.targetsSection"));
        targetsCard.setName("bot-targets-open");
        UiKit.onChange(targetsCard, () -> showPage(targetsPage));
        Table card = new Table();
        card.top().left();
        card.add(targetsCard).growX().minWidth(0).top();
        columns.add(card);

        // Three even columns, the targets card last; one under another when narrow
        if (columns.size() == 2) {
            columns.add(1, null);
        }
        Table row = new Table();
        row.top().left();
        float gap = space("xl");
        for (int index = 0; index < columns.size(); index++) {
            Actor column = columns.get(index);
            if (!stacked) {
                row.add(column).growX().uniformX().minWidth(0).top().left().padLeft(index == 0 ? 0 : gap);
            } else if (column != null) {
                row.add(column).growX().minWidth(0).left().padTop(row.getCells().isEmpty() ? 0 : space("lg")).row();
            }
        }
        content.add(row).padTop(space("lg")).row();
        return content;
    }

    private UiChoice flag(Flag flag) {
        UiChoice check = kit.check(flag.label(), form.flag(flag));
        check.setName("bot-flag-" + flag.name().toLowerCase(Locale.ROOT));
        tip(check, flag.tip());
        UiKit.onChange(check, () -> {
            form.flag(flag, check.isChecked());
            refresh();
        });
        flags.put(flag, check);
        return check;
    }

    // ---- Pages ----

    private Table targetsPage() {
        Table content = new Table();
        content.top().left().defaults().left().growX();
        content.add(kit.typeLabel(Messages.getString("BotConfigDialog.targetsSection"), "title", "text.primary"))
              .row();
        content.add(hint(Messages.getString("Lobby.bot.targetsIntro"))).minWidth(0).padTop(space("xs")).row();
        content.add(targets()).grow().padTop(space("lg")).row();
        return content;
    }

    /**
     * Shows {@code shown}: the targets page from its top, at least the height the main page had, with Back in the
     * footer and the focus in the search; or the main page back at the targets card with the focus on it.
     */
    private void showPage(Table shown) {
        boolean inner = shown == targetsPage;
        ScrollPane scroll = dialog.scroll();
        Table body = dialog.body();
        // The dialog keeps its size: the inner page asks for the view the main page left, its unit list taking up
        // the difference, and grows only when its least is more
        if (inner) {
            page.prefHeight(scroll.getScrollHeight() - body.getPadTop() - body.getPadBottom());
        } else {
            page.prefHeight(Value.prefHeight);
        }
        page.setActor(shown);
        footer(!inner);
        refresh();
        Actor focus = inner ? targetsPage.findActor("bot-target-search") : targetsCard;
        dialog.layer().validate();
        if (inner) {
            scroll.setScrollY(0);
        } else {
            Vector2 spot = focus.localToAscendantCoordinates(scroll.getActor(), new Vector2());
            scroll.scrollTo(spot.x, spot.y, focus.getWidth(), focus.getHeight());
        }
        scroll.updateVisualScroll();
        if (body.getStage() != null) {
            body.getStage().setKeyboardFocus(focus);
        }
    }

    // ---- Strategic targets ----

    private Table targets() {
        Table content = new Table();
        content.top().left().defaults().left().growX();
        content.add(kit.caption(Messages.getString("Lobby.bot.unitTargets"), true)).row();
        UiKit.TokenSearch search = kit.tokenSearch(Messages.getString("BotConfigDialog.pickerSearchHint"), true);
        search.field.setName("bot-target-search");
        tip(search.field, Messages.getString("BotConfigDialog.pickerSearchTip"));
        UiKit.onChange(search.field, () -> {
            form.picker().search(search.field.getText());
            pickerDue = true;
        });
        content.add(search).padTop(space("sm")).row();
        pickerList.top().left();
        ScrollPane scroll = kit.scrollList(pickerList, true);
        scroll.setScrollingDisabled(true, false);
        scroll.setFadeScrollBars(false);
        content.add(scroll).grow().minHeight(size("dialog.sm") * 0.25f).padTop(space("sm")).row();
        pickerCount = kit.typeLabel("", "small", "text.secondary");
        pickerCount.setName("bot-target-count");
        content.add(pickerCount).padTop(space("xs")).row();
        Label hint = kit.typeLabel(Messages.getString("BotConfigDialog.pickerPriorityHint"), "small", "text.muted");
        hint.setWrap(true);
        content.add(hint).padTop(space("xxs")).row();
        fillPicker();

        content.add(kit.caption(colonless("BotConfigDialog.hexTargets"), true)).padTop(space("xl")).row();
        hexList.top().left().defaults().left();
        content.add(hexList).padTop(space("sm")).row();
        fillHexes();

        hexInput = kit.input("", true);
        hexInput.setName("bot-hex-coords");
        hexInput.setTextFieldFilter((text, typed) -> Character.isDigit(typed) || typed == ',' || typed == ' ');
        UiKit.onChange(hexInput, () -> hexField.error(null));
        hexField = kit.formField(Messages.getString("Lobby.bot.hexCoords"), hexInput);
        tip(hexInput, Messages.getString("BotConfigDialog.hexCoordsTip"));
        UiButton addHex = kit.button("button-secondary-compact", "add", Messages.getString("Lobby.bot.add"), null);
        addHex.setName("bot-hex-add");
        UiKit.onChange(addHex, () -> {
            if (form.addHex(hexInput.getText())) {
                hexInput.setText("");
                hexField.error(null);
                fillHexes();
            } else {
                hexField.error(Messages.getString("Lobby.bot.hexUnreadable"));
            }
        });
        Table typed = new Table();
        typed.bottom().left();
        typed.add(hexField).width(size("dialog.sm") / 2).bottom();
        typed.add(addHex).bottom().padLeft(space("sm"));
        typed.add().growX();

        Table chosen = new Table();
        chosen.bottom().left();
        List<HexTarget> buildings = form.buildingHexes();
        UiSelect building = kit.tokenSelect(true, buildings.stream().map(HexTarget::text).toArray(String[]::new));
        building.setName("bot-building");
        UiButton addBuilding = kit.button("button-secondary-compact", "add", Messages.getString("Lobby.bot.add"),
              null);
        addBuilding.setName("bot-building-add");
        if (buildings.isEmpty()) {
            building.placeholder(Messages.getString("Lobby.bot.buildingNone"));
            building.setDisabled(true);
            addBuilding.setDisabled(true);
        }
        UiKit.onChange(addBuilding, () -> {
            int index = building.getSelectedIndex();
            if (index >= 0 && index < buildings.size()) {
                form.addHex(buildings.get(index).coords());
                fillHexes();
            }
        });
        chosen.add(kit.formField(Messages.getString("Lobby.bot.building"), building)).growX().minWidth(0).bottom();
        chosen.add(addBuilding).bottom().padLeft(space("sm"));
        if (stacked) {
            content.add(typed).padTop(space("md")).row();
            content.add(chosen).padTop(space("sm")).row();
        } else {
            Table both = new Table();
            both.bottom().left();
            both.add(typed).growX().uniformX().bottom();
            both.add(chosen).growX().uniformX().bottom().padLeft(space("lg"));
            content.add(both).padTop(space("md")).row();
        }
        return content;
    }

    private void fillPicker() {
        pickerList.clearChildren();
        BotTargetPicker picker = form.picker();
        float indent = space("lg");
        for (BotTargetPicker.Row row : picker.rows()) {
            Table line = new Table();
            line.left();
            float pad = row.depth() * indent;
            switch (row.kind()) {
                case OWNER, NO_FORCE -> {
                    line.add(fold(row)).padLeft(pad);
                    line.add(kit.typeLabel(row.text(), row.kind() == BotTargetPicker.Kind.OWNER ? "body.strong"
                          : "body", "text.primary")).left().padLeft(space("xs"));
                }
                case FORCE -> {
                    line.add(fold(row)).padLeft(pad);
                    line.add(tick(row)).left().padLeft(space("xs"));
                }
                case UNIT -> line.add(tick(row)).left().padLeft(pad + size("control.sm") + space("xs"));
                case OTHER -> line.add(kit.typeLabel(row.text(), "body.strong", "text.primary")).left();
                case EMPTY -> line.add(kit.typeLabel(row.text(), "small", "text.muted")).left();
            }
            if (row.tip() != null && row.kind() == BotTargetPicker.Kind.OTHER) {
                tip(line, row.tip());
            }
            line.add().growX();
            if (row.priority() > 0) {
                UiSelect priority = kit.tokenSelect(true, "1", "2", "3", "4", "5");
                priority.setName("bot-prio-" + row.id());
                priority.select(row.priority() - 1);
                priority.setDisabled(!row.priorityEnabled());
                tip(priority, Messages.getString("BotConfigDialog.pickerPriorityTip"));
                UiKit.onChange(priority, () -> {
                    picker.priority(row.id(), priority.getSelectedIndex() + 1);
                    pickerDue = true;
                });
                line.add(priority).right().padLeft(space("sm"));
            }
            pickerList.add(line).growX().padTop(space("xxs")).row();
        }
        pickerCount.setText(picker.count());
    }

    private UiButton fold(BotTargetPicker.Row row) {
        UiButton fold = kit.button("button-icon-compact", row.open() ? "chevron-down" : "chevron-right", null, null);
        fold.setName("bot-fold-" + row.id());
        UiKit.onChange(fold, () -> {
            form.picker().toggle(row.id());
            pickerDue = true;
        });
        return fold;
    }

    private UiChoice tick(BotTargetPicker.Row row) {
        UiChoice tick = kit.check(row.text(), row.checked());
        tick.setName("bot-tick-" + row.id());
        tick.setDisabled(!row.enabled());
        if (row.tip() != null) {
            tip(tick, row.tip());
        }
        UiKit.onChange(tick, () -> {
            form.picker().tick(row.id(), tick.isChecked());
            pickerDue = true;
        });
        return tick;
    }

    private void fillHexes() {
        hexList.clearChildren();
        List<HexTarget> hexes = form.hexTargets();
        if (hexes.isEmpty()) {
            hexList.add(kit.typeLabel(Messages.getString("Lobby.bot.hexNone"), "small", "text.muted")).left().row();
        }
        for (int index = 0; index < hexes.size(); index++) {
            HexTarget hex = hexes.get(index);
            Label line = kit.typeLabel(hex.text(), "body", hex.valid() ? "text.primary" : "status.warning");
            line.setEllipsis(true);
            UiButton remove = kit.button("button-icon-compact", "close", null, null);
            remove.setName("bot-hex-remove-" + index);
            tip(remove, Messages.getString("Lobby.bot.removeHex", hex.text()));
            UiKit.onChange(remove, () -> {
                form.removeHex(hex.coords());
                fillHexes();
            });
            hexList.add(line).growX().minWidth(0).left();
            hexList.add(remove).padLeft(space("sm")).row();
        }
    }

    // ---- State ----

    /** GPU thread, each frame: rebuilds the unit list after a click changed it, outside that click's event. */
    private void drain() {
        if (pickerDue) {
            pickerDue = false;
            fillPicker();
        }
    }

    /** Puts the form's values into the controls, as after loading a preset. */
    private void showValues() {
        sliders.forEach((kind, slider) -> {
            slider.setValue(form.value(kind));
            sliderTitles.get(kind).setText(kind.title(form.value(kind)));
        });
        flags.forEach((flag, check) -> check.setChecked(form.flag(flag)));
        posture.select(Math.max(0, List.of(POSTURES).indexOf(form.posture())));
        retreat.select(form.retreat().ordinal());
        retreatEdge.select(Math.max(0, BotSetupForm.edges().indexOf(form.retreatTo())));
        int chosen = savedPresets.indexOf(form.selected());
        presetSelect.select(chosen);
        fillHexes();
        pickerDue = true;
    }

    /** Shows what is picked, what each choice means, what may be pressed and what is wrong. */
    private void refresh() {
        if (nameField != null) {
            nameField.error(form.nameError().map(Messages::getString).orElse(null));
        }
        Personality matched = form.personality().orElse(null);
        Personality base = form.basedOn();
        // A modified personality keeps its card chosen, marked Modified with its reset and showing the new values
        Personality shown = matched != null ? matched : base;
        int[] now = new int[TRAITS.size()];
        for (int trait = 0; trait < TRAITS.size(); trait++) {
            now[trait] = form.value(TRAITS.get(trait));
        }
        cards.forEach((personality, card) -> {
            boolean chosen = personality == shown;
            boolean changed = chosen && matched == null;
            card.chosen(chosen);
            card.modified(changed ? Messages.getString("Lobby.bot.modifiedTag") : null);
            int[] own = cardTraits.get(personality);
            if (own != null) {
                card.current(changed ? now : own);
            }
            card.resetButton().setDisabled(busy);
        });
        status.setText(shown == null ? Messages.getString("Lobby.bot.custom") : "");

        String postureKey = POSTURES[Math.max(0, posture.getSelectedIndex())].name().toLowerCase(Locale.ROOT);
        postureHint.setText(Messages.getString("Lobby.bot.posture." + postureKey + ".hint"));
        Retreat chosenRetreat = form.retreat();
        retreatHint.setText(Messages.getString("Lobby.bot.retreat." + chosenRetreat.name().toLowerCase(Locale.ROOT)
              + ".hint"));
        retreatEdge.setDisabled(chosenRetreat == Retreat.FIGHT);
        boolean pirate = chosenRetreat == Retreat.WITHDRAW && form.flag(Flag.PIRATE);
        pirateNote.setVisible(pirate);
        retreatHint.setVisible(!pirate);

        Preset chosen = form.selected();
        boolean own = chosen != null && chosen.kind() == PresetKind.STORED && Personality.of(chosen.name()) == null;
        save.setDisabled(busy || !own || !form.changedPreset());
        delete.setDisabled(busy || !own);
        presetSelect.setDisabled(busy);
        String typed = newName.getText();
        newNameField.error(form.newPresetError(typed).map(Messages::getString).orElse(null));
        saveNew.setDisabled(busy || !form.canSaveNew(typed));

        int units = form.picker().picked().size();
        int hexes = form.hexTargets().size();
        targetsCard.summary(units == 0 && hexes == 0 ? Messages.getString("Lobby.bot.targetsNone")
              : Messages.getString("Lobby.bot.targetsSummary", units, hexes));
        advanced.detail(units + hexes == 0 ? "" : Messages.getString("Lobby.bot.targetsSet", units + hexes));
        ok.setDisabled(busy || !form.valid());
    }

    private void confirm() {
        if (busy || !form.valid()) {
            return;
        }
        host.confirm(new LobbyPlayerController.BotSetup(form.name(), form.type(), form.behavior()));
        close.run();
    }

    // ---- For the probe and the tests ----

    BotSetupForm form() {
        return form;
    }

    UiDialog dialog() {
        return dialog;
    }

    boolean busy() {
        return busy;
    }
}
