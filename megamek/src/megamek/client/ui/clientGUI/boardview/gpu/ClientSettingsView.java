/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuSettingsView.space;

import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.Align;
import jakarta.annotation.Nullable;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.bot.princess.BehaviorSettingsFactory;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.audio.SoundManager;
import megamek.client.ui.clientGUI.audio.SoundType;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Mode;
import megamek.client.ui.design.DesignTokens.Palette;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiFileDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.settings.ClientSettingsForm;
import megamek.client.ui.settings.SettingsForm;
import megamek.client.ui.settings.SettingsForm.Page;
import megamek.client.ui.settings.SettingsForm.Row;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.PreferenceManager;
import megamek.logging.MMLogger;

/**
 * The Client Settings on the shared settings screen ({@link GpuSettingsView}): the player's own preferences, which
 * take effect as they change, so a sound is heard at its new volume and a board option shows on the board behind the
 * card. Each page has a reset to its shipped values in its header and, where it helps, a preview beside it: the
 * Audio page a sound check that plays each alert. The footer has Defaults, which resets every page, OK, which keeps the
 * changes and stores the preferences, and Cancel, which puts back what the settings were when the card opened; closing
 * the card any other way cancels too. The Display page's preview is a unit's readout as the HUD draws it, in the
 * palette and motion the page sets. A page of key bindings shows a keyboard with the keys it binds, the pointed
 * binding's keys lit and a key bound twice in red.
 * <p>
 * GPU thread. The preferences are written on the Swing thread through the {@link Host}, since their listeners update
 * Swing views.
 */
final class ClientSettingsView {
    /** What the view asks of the window. */
    interface Host {
        /** Runs {@code work} on the Swing thread, then {@code then} back on the GPU thread. */
        void edt(Runnable work, Runnable then);

        /** Swing thread: plays the alert's sound as the preferences have it now, whether it is muted or not. */
        default void play(SoundType sound) {
            Sounds.play(sound);
        }

        /** Swing thread: stores the preferences, as quitting does. */
        default void save() {
            PreferenceManager.getInstance().save();
        }

        /**
         * The host of a window whose GPU thread {@code gpu} posts to: the work runs on the Swing thread whatever the
         * window does meanwhile, since a setting that took effect has to be put back even as the window closes.
         */
        static Host swing(Consumer<Runnable> gpu) {
            return (work, then) -> SwingUtilities.invokeLater(() -> {
                try {
                    work.run();
                } catch (RuntimeException failure) {
                    LOGGER.error(failure, "The Client Settings failed");
                } finally {
                    gpu.accept(then);
                }
            });
        }
    }

    private static final MMLogger LOGGER = MMLogger.create(ClientSettingsView.class);

    /** The Audio page's alerts: the section that sets each, and its sound. */
    private static final Map<String, SoundType> ALERTS = Map.of("chat", SoundType.BING_CHAT, "myTurn",
          SoundType.BING_MY_TURN, "otherTurns", SoundType.BING_OTHERS_TURN);
    private static final Map<String, String> MUTES = Map.of("chat",
          GUIPreferences.SOUND_MUTE_CHAT, "myTurn",
          GUIPreferences.SOUND_MUTE_MY_TURN, "otherTurns",
          GUIPreferences.SOUND_MUTE_OTHERS_TURN);
    private static final Map<String, String> ICONS = Map.of(ClientSettingsForm.AUDIO, "volume",
          ClientSettingsForm.DISPLAY, "display", ClientSettingsForm.CONTROLS, "keyboard", ClientSettingsForm.GAMEPLAY,
          "gameplay");
    /** The scales, which take effect where the slider is let go, as they move the card under the pointer. */
    private static final Set<String> SCALES = Set.of(GUIPreferences.GUI_SCALE, GUIPreferences.BOARD_UI_SCALE);

    private final UiKit kit;
    private final Stage stage;
    private final ClientSettingsForm form;
    private final Host host;
    private final Runnable close;
    private final GpuSettingsView view;
    private final UiButton defaults;
    private final UiButton ok;
    private UiButton reset;
    private Table soundCheck;
    private DisplaySample sample;
    private KeyboardSample keyboard;
    private PromptSample prompts;
    private PrincessSample princess;
    private RosterSample roster;
    private Actor restart;
    private UiFileDialog files;
    private boolean applying;
    private boolean pending;
    private boolean confirmed;
    private boolean closed;

    /**
     * @param page the page to open on, null for the first
     */
    ClientSettingsView(UiKit kit, Stage stage, UiDialog dialog, ClientSettingsForm form, @Nullable String page,
          float bodyWidth, float paneHeight, Host host, Runnable close) {
        this.kit = kit;
        this.stage = stage;
        this.form = form;
        this.host = host;
        this.close = close;
        view = new GpuSettingsView(kit, dialog, form, bodyWidth, paneHeight, new Flavor());

        defaults = dialog.tool(Messages.getString("ClientSettings.defaults"), () -> {
            form.defaults();
            view.showAll();
            apply();
        });
        defaults.setName("client-defaults");
        ok = dialog.primary(Messages.getString("Ok.text"), this::confirm);
        ok.setName("client-ok");
        dialog.cancelButton(Messages.getString("Cancel.text")).setName("client-cancel");
        view.open(page);
    }

    /** The Client Settings' parts of the settings screen. */
    private final class Flavor extends GpuSettingsView.Flavor {
        @Override
        String prefix() {
            return "client";
        }

        @Override
        String rowPrefix() {
            return "client-setting-";
        }

        @Override
        GpuSettingsView.Texts texts() {
            return new GpuSettingsView.Texts(Messages.getString("ClientSettings.search"),
                  Messages.getString("ClientSettings.search.tooltip"),
                  Messages.getString("ClientSettings.search.none"), Messages.getString("ClientSettings.search.found"),
                  Messages.getString("ClientSettings.hint"));
        }

        @Override
        String icon(String group) {
            return ICONS.getOrDefault(group, "settings");
        }

        @Override
        void header(Page page, Table header) {
            reset = kit.button("button-quiet" + UiTheme.COMPACT, "restart", Messages.getString("ClientSettings.reset"),
                  null);
            reset.setName("client-reset-" + page.id());
            kit.tip(reset, true).getActor().setText(Messages.getString("ClientSettings.reset.tooltip"));
            UiKit.onChange(reset, () -> {
                form.defaults(page);
                view.showAll();
                apply();
            });
            header.add(reset).padLeft(space("md"));
        }

        @Override
        @Nullable Actor preview(Page page) {
            soundCheck = ClientSettingsForm.AUDIO.equals(page.id()) ? soundCheck() : null;
            sample = ClientSettingsForm.DISPLAY.equals(page.id()) ? new DisplaySample() : null;
            keyboard = binds(page) ? new KeyboardSample(page) : null;
            prompts = ClientSettingsForm.WARNINGS.equals(page.id()) ? new PromptSample(page) : null;
            princess = ClientSettingsForm.BOTS.equals(page.id()) ? new PrincessSample() : null;
            roster = ClientSettingsForm.UNITS.equals(page.id()) ? new RosterSample() : null;
            for (Table preview : new Table[] { soundCheck, sample, keyboard, prompts, princess, roster }) {
                if (preview != null) {
                    return preview;
                }
            }
            return null;
        }

        @Override
        void pointed(@Nullable Row row) {
            if (keyboard != null) {
                keyboard.point(row);
            }
            if (prompts != null) {
                prompts.point(row);
            }
        }

        @Override
        void marks(Row row, Table marks) {
            if (ClientPreferences.LOCALE.equals(row.name())) {
                restart = kit.chip(Messages.getString("ClientSettings.display.restart"), UiKit.Tone.WARN, true);
                restart.setName("client-restart");
                marks.add(restart).padLeft(space("sm"));
            }
        }

        @Override
        void changed(Row row) {
            apply();
        }

        @Override
        boolean live(Row row) {
            return !SCALES.contains(row.name());
        }

        @Override
        void pick(Row row, String current, Consumer<String> then) {
            if (files == null) {
                files = new UiFileDialog(kit);
            }
            Path at = Path.of(current.isBlank() ? "." : current).toAbsolutePath();
            Path directory = Files.isDirectory(at) ? at : at.getParent();
            files.show(stage, new UiFileDialog.Options(row.label(), UiFileDialog.Mode.OPEN, "clientSettings.sounds",
                        directory, "", new UiFileDialog.Filter(Messages.getString("ClientSettings.audio.files"),
                        row.values())),
                  path -> {
                      if (path != null) {
                          then.accept(relative(path));
                      }
                  });
        }

        @Override
        void refreshed() {
            showRest();
        }
    }

    /** The picked file as the preferences keep their sounds: relative to the working directory when it is inside. */
    private static String relative(Path path) {
        Path here = Path.of("").toAbsolutePath();
        Path absolute = path.toAbsolutePath().normalize();
        return (absolute.startsWith(here) ? here.relativize(absolute) : absolute).toString().replace('\\', '/');
    }

    /** The Audio page's preview: a pad per alert that plays it, and whether it plays in the game. */
    private Table soundCheck() {
        Table panel = new Table();
        panel.top();
        panel.setName("client-sound-check");
        panel.defaults().left().growX();
        panel.add(kit.caption(Messages.getString("ClientSettings.soundCheck"), true)).row();
        panel.add(view.wrapped(Messages.getString("ClientSettings.soundCheck.summary"), "small", "text.muted"))
              .padTop(space("xs")).row();
        for (String alert : new String[] { "chat", "myTurn", "otherTurns" }) {
            UiButton pad = kit.button("button-secondary", "play",
                  Messages.getString("ClientSettings.soundCheck." + alert), "");
            pad.setName("client-play-" + alert);
            UiKit.onChange(pad, () -> play(ALERTS.get(alert)));
            panel.add(pad).padTop(space("md")).height(GpuSettingsView.size("control.lg") * 1.6f).row();
        }
        return panel;
    }

    /** Applies what is set, then plays the alert as the player will hear it. */
    private void play(SoundType sound) {
        if (closed) {
            return;
        }
        Runnable writer = form.apply();
        host.edt(() -> {
            writer.run();
            host.play(sound);
        }, () -> { });
    }

    /** Shows what the settings screen leaves to this view: the page's reset, the sound check and the footer. */
    private void showRest() {
        if (ok == null) {
            return;
        }
        Page page = form.page(view.pageId());
        if (reset != null) {
            reset.setVisible(page != null && form.customized(page));
        }
        if (soundCheck != null) {
            for (Map.Entry<String, String> alert : MUTES.entrySet()) {
                UiButton pad = soundCheck.findActor("client-play-" + alert.getKey());
                if (pad != null && !pad.details.isEmpty()) {
                    pad.details.getFirst().setText(Messages.getString(form.bool(alert.getValue())
                          ? "ClientSettings.soundCheck.on" : "ClientSettings.soundCheck.off"));
                }
            }
        }
        if (sample != null) {
            sample.show();
        }
        if (keyboard != null) {
            keyboard.show();
        }
        if (prompts != null) {
            prompts.show();
        }
        if (princess != null) {
            princess.show();
        }
        if (roster != null) {
            roster.show();
        }
        if (restart != null) {
            restart.setVisible(form.changed(ClientPreferences.LOCALE));
        }
        defaults.setDisabled(form.pages().stream().noneMatch(form::customized));
    }

    /**
     * The Display page's preview: a unit's readout as the HUD draws it, its bars in the status colors of the palette
     * the page sets, and its heat rising and falling unless motion is reduced.
     */
    private final class DisplaySample extends Table {
        private final UiKit.Meter armor;
        private final UiKit.Meter heat;
        private final UiKit.Meter structure;
        private final Label motion;
        private Color success;
        private Color warning;
        private Color danger;
        private float clock;

        DisplaySample() {
            top();
            setName("client-display-sample");
            defaults().left().growX();
            add(kit.caption(Messages.getString("ClientSettings.sample"), true)).row();
            add(view.wrapped(Messages.getString("ClientSettings.sample.summary"), "small", "text.muted"))
                  .padTop(space("xs")).row();
            armor = kit.meter(Messages.getString("ClientSettings.sample.armor"), false, true);
            heat = kit.meter(Messages.getString("ClientSettings.sample.heat"), false, true);
            structure = kit.meter(Messages.getString("ClientSettings.sample.structure"), false, true);
            armor.setName("client-sample-armor");
            heat.setName("client-sample-heat");
            structure.setName("client-sample-structure");
            add(armor).padTop(space("md")).row();
            add(heat).padTop(space("md")).row();
            add(structure).padTop(space("md")).row();
            motion = view.wrapped("", "small", "text.secondary");
            motion.setName("client-sample-motion");
            add(motion).padTop(space("lg")).row();
            show();
        }

        /** Shows the page's palette and motion as the form has them now. */
        void show() {
            DesignTokens tokens = DesignTokens.shipped(form.bool(GUIPreferences.COLOR_BLIND_PALETTE)
                  ? Palette.COLORBLIND : Palette.STANDARD);
            success = color(tokens, "status.success");
            warning = color(tokens, "status.warning");
            danger = color(tokens, "status.danger");
            armor.set("92%", 0.92f, success);
            structure.set("18%", 0.18f, danger);
            if (!moving()) {
                clock = 0;
            }
            heatAt(clock);
            motion.setText(Messages.getString(moving() ? "ClientSettings.sample.moving"
                  : "ClientSettings.sample.still"));
        }

        @Override
        public void act(float delta) {
            super.act(delta);
            if (moving()) {
                clock += delta;
                heatAt(clock);
            }
        }

        private boolean moving() {
            return !form.bool(GUIPreferences.REDUCE_MOTION);
        }

        private void heatAt(float time) {
            float share = 0.55f + 0.25f * MathUtils.sin(time * 1.6f);
            heat.set(Math.round(share * 30) + " / 30", share, warning);
        }

        private static Color color(DesignTokens tokens, String role) {
            Color color = new Color();
            Color.argb8888ToColor(color, tokens.color(role, Mode.DARK));
            return color;
        }
    }

    /**
     * The Warnings page's preview: how many of its warnings ask first, and the prompt the pointed one brings up in a
     * game, faded while it is off.
     */
    private final class PromptSample extends Table {
        private final List<Row> asks;
        private final Label tally;
        private final Table prompt;
        private final Label question;
        private final Label detail;
        private final Label state;
        private Row shown;

        PromptSample(Page page) {
            top();
            setName("client-prompt-sample");
            defaults().left().growX();
            asks = page.sections().stream().flatMap(section -> section.rows().stream())
                  .filter(row -> row.kind() == SettingsForm.Kind.BOOLEAN).toList();
            add(kit.caption(Messages.getString("ClientSettings.prompt"), true)).row();
            add(view.wrapped(Messages.getString("ClientSettings.prompt.summary"), "small", "text.muted"))
                  .padTop(space("xs")).row();
            tally = view.wrapped("", "body", "text.secondary");
            tally.setName("client-prompt-tally");
            add(tally).padTop(space("md")).row();
            prompt = kit.card();
            prompt.setName("client-prompt-card");
            prompt.pad(space("md"));
            prompt.defaults().left().growX();
            Table head = new Table();
            head.add(kit.icon("warn", 18, UiTheme.tokenColor("status.warning"))).padRight(space("sm"));
            head.add(kit.typeLabel(Messages.getString("ClientSettings.prompt.title"), "label", "text.primary"))
                  .left().growX();
            prompt.add(head).row();
            question = view.wrapped("", "body", "text.primary");
            question.setName("client-prompt-question");
            prompt.add(question).padTop(space("sm")).row();
            detail = view.wrapped("", "small", "text.secondary");
            prompt.add(detail).padTop(space("xs")).row();
            Table buttons = new Table();
            buttons.right();
            UiButton cancel = kit.button("button-secondary" + UiTheme.COMPACT, null, Messages.getString("Cancel.text"),
                  null);
            UiButton go = kit.button("button-primary" + UiTheme.COMPACT, null,
                  Messages.getString("ClientSettings.prompt.go"), null);
            cancel.setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.disabled);
            go.setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.disabled);
            buttons.add(cancel);
            buttons.add(go).padLeft(space("sm"));
            prompt.add(buttons).padTop(space("md")).row();
            add(prompt).padTop(space("md")).row();
            state = view.wrapped("", "small", "text.secondary");
            state.setName("client-prompt-state");
            add(state).padTop(space("sm")).row();
            shown = asks.isEmpty() ? null : asks.getFirst();
            show();
        }

        /** The pointed warning shows until another is pointed at. */
        void point(@Nullable Row row) {
            if (row != null && asks.contains(row)) {
                shown = row;
                show();
            }
        }

        void show() {
            long on = asks.stream().filter(row -> form.bool(row.name())).count();
            tally.setText(Messages.getString("ClientSettings.prompt.tally", on, asks.size()));
            prompt.setVisible(shown != null);
            if (shown == null) {
                state.setText("");
                return;
            }
            boolean asking = form.bool(shown.name());
            question.setText(shown.label());
            detail.setText(GpuSettingsView.plain(shown.tooltip()));
            prompt.getColor().a = asking ? 1 : 0.35f;
            state.setText(Messages.getString(asking ? "ClientSettings.prompt.on" : "ClientSettings.prompt.off"));
            state.setColor(UiTheme.tokenColor(asking ? "status.success" : "text.muted"));
        }
    }

    /** The Bots page's preview: the dials of the behavior a new Princess starts with, and her experimental features. */
    private final class PrincessSample extends Table {
        private final Label behavior;
        private final UiKit.Meter bravery;
        private final UiKit.Meter aggression;
        private final UiKit.Meter support;
        private final UiKit.Meter caution;
        private final UiKit.Meter selfPreservation;
        private final Label experimental;

        PrincessSample() {
            top();
            setName("client-princess-sample");
            defaults().left().growX();
            Table head = new Table();
            head.add(kit.icon("bot", 22, UiTheme.tokenColor("accent.primary"))).padRight(space("sm"));
            head.add(kit.caption(Messages.getString("ClientSettings.princess"), true)).left().growX();
            add(head).row();
            add(view.wrapped(Messages.getString("ClientSettings.princess.summary"), "small", "text.muted"))
                  .padTop(space("xs")).row();
            behavior = view.wrapped("", "title", "text.primary");
            behavior.setName("client-princess-behavior");
            add(behavior).padTop(space("md")).row();
            bravery = dial("bravery");
            aggression = dial("aggression");
            support = dial("support");
            caution = dial("caution");
            selfPreservation = dial("selfPreservation");
            experimental = view.wrapped("", "small", "text.secondary");
            experimental.setName("client-princess-experimental");
            add(experimental).padTop(space("lg")).row();
            show();
        }

        private UiKit.Meter dial(String name) {
            UiKit.Meter meter = kit.meter(Messages.getString("ClientSettings.princess." + name), false, true);
            meter.setName("client-princess-" + name);
            add(meter).padTop(space("md")).row();
            return meter;
        }

        void show() {
            BehaviorSettingsFactory factory = BehaviorSettingsFactory.getInstance();
            String name = form.text(ClientPreferences.FAVORITE_PRINCESS_BEHAVIOR_SETTING);
            BehaviorSettings settings = factory.getBehaviorOrDefault(name, factory.DEFAULT_BEHAVIOR);
            behavior.setText(name);
            Color accent = UiTheme.tokenColor("accent.primary");
            set(bravery, settings.getBraveryIndex(), accent);
            set(aggression, settings.getHyperAggressionIndex(), accent);
            set(support, settings.getMutualSupportIndex(), accent);
            set(caution, settings.getFallShameIndex(), accent);
            set(selfPreservation, settings.getSelfPreservationIndex(), accent);
            boolean on = form.bool(ClientPreferences.ENABLE_EXPERIMENTAL_BOT_FEATURES);
            experimental.setText(Messages.getString("ClientSettings.princess.experimental") + ": "
                  + Messages.getString(on ? "ClientSettings.on" : "ClientSettings.off"));
            experimental.setColor(UiTheme.tokenColor(on ? "status.warning" : "text.muted"));
        }

        private static void set(UiKit.Meter meter, int index, Color color) {
            int value = MathUtils.clamp(index, 0, 10);
            meter.set(value + " / 10", value / 10f, color);
        }
    }

    /** The New units page's preview: a unit as it joins the force in the lobby, with its crew, ejection and codes. */
    private final class RosterSample extends Table {
        private final Label unit;
        private final Label pilot;
        private final Label skills;
        private final Label eject;
        private final Label point;

        RosterSample() {
            top();
            setName("client-roster-sample");
            defaults().left().growX();
            add(kit.caption(Messages.getString("ClientSettings.roster"), true)).row();
            add(view.wrapped(Messages.getString("ClientSettings.roster.summary"), "small", "text.muted"))
                  .padTop(space("xs")).row();
            Table card = kit.card();
            card.pad(space("md"));
            card.defaults().left().growX();
            Table head = new Table();
            head.add(kit.icon("unit", 22, UiTheme.tokenColor("accent.primary"))).padRight(space("sm"));
            unit = kit.typeLabel("", "label", "text.primary");
            unit.setName("client-roster-unit");
            head.add(unit).left().growX();
            card.add(head).row();
            pilot = line(card, "client-roster-pilot");
            skills = line(card, "client-roster-skills");
            eject = line(card, "client-roster-eject");
            point = line(card, "client-roster-point");
            add(card).padTop(space("md")).row();
            show();
        }

        private Label line(Table card, String name) {
            Label label = view.wrapped("", "small", "text.secondary");
            label.setName(name);
            card.add(label).padTop(space("xs")).row();
            return label;
        }

        void show() {
            String name = Messages.getString("ClientSettings.roster.unit");
            unit.setText(form.bool(ClientPreferences.SHOW_UNIT_ID)
                  ? name + "  " + Messages.getString("ClientSettings.roster.id", "7") : name);
            pilot.setText(Messages.getString(form.bool(ClientPreferences.GENERATE_NAMES)
                  ? "ClientSettings.roster.named" : "ClientSettings.roster.unnamed"));
            skills.setText(Messages.getString(form.bool(ClientPreferences.USE_AVERAGE_SKILLS)
                  ? "ClientSettings.roster.rolled" : "ClientSettings.roster.standard"));
            boolean ejects = form.bool(ClientPreferences.DEFAULT_AUTO_EJECT_DISABLED);
            eject.setText(Messages.getString("ClientSettings.roster.eject") + ": "
                  + Messages.getString(ejects ? "ClientSettings.on" : "ClientSettings.off"));
            eject.setColor(UiTheme.tokenColor(ejects ? "status.success" : "text.muted"));
            int start = Integer.parseInt(form.text(ClientPreferences.UNIT_START_CHAR).isBlank() ? "65"
                  : form.text(ClientPreferences.UNIT_START_CHAR));
            point.setText(Messages.getString("ClientSettings.roster.point", String.valueOf((char) start)));
        }
    }

    /** Whether the page binds keys: a page of the Controls but their Keyboard. */
    private static boolean binds(Page page) {
        return ClientSettingsForm.CONTROLS.equals(page.categoryId()) && !ClientSettingsForm.KEYBOARD.equals(page.id());
    }

    /**
     * A key binding page's preview: a keyboard whose keys the page binds glow, the pointed binding's key and modifiers
     * lit and a key bound twice in red; the page's keys the keyboard lacks are listed under it.
     */
    private final class KeyboardSample extends Table {
        /** A key's width, in the width of a letter's key. */
        private record Key(int code, String label, float width) { }

        private static final float UNIT = 18;
        private static final float GAP = 2;
        private static final int NONE = KeyEvent.VK_UNDEFINED;
        private static final List<List<Key>> MAIN = List.of(
              keys("Esc:" + KeyEvent.VK_ESCAPE, "|0.5", "F1:" + KeyEvent.VK_F1, "F2:" + KeyEvent.VK_F2,
                    "F3:" + KeyEvent.VK_F3, "F4:" + KeyEvent.VK_F4, "|0.5", "F5:" + KeyEvent.VK_F5,
                    "F6:" + KeyEvent.VK_F6, "F7:" + KeyEvent.VK_F7, "F8:" + KeyEvent.VK_F8, "|0.5",
                    "F9:" + KeyEvent.VK_F9, "F10:" + KeyEvent.VK_F10, "F11:" + KeyEvent.VK_F11,
                    "F12:" + KeyEvent.VK_F12),
              keys("`:" + KeyEvent.VK_BACK_QUOTE, "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
                    "-:" + KeyEvent.VK_MINUS, "=:" + KeyEvent.VK_EQUALS, "\u2190:" + KeyEvent.VK_BACK_SPACE + ":2"),
              keys("Tab:" + KeyEvent.VK_TAB + ":1.5", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P",
                    "[:" + KeyEvent.VK_OPEN_BRACKET, "]:" + KeyEvent.VK_CLOSE_BRACKET,
                    "\\:" + KeyEvent.VK_BACK_SLASH + ":1.5"),
              keys("Caps:" + KeyEvent.VK_CAPS_LOCK + ":1.75", "A", "S", "D", "F", "G", "H", "J", "K", "L",
                    ";:" + KeyEvent.VK_SEMICOLON, "':" + KeyEvent.VK_QUOTE, "Enter:" + KeyEvent.VK_ENTER + ":2.25"),
              keys("Shift:" + KeyEvent.VK_SHIFT + ":2.25", "Z", "X", "C", "V", "B", "N", "M",
                    ",:" + KeyEvent.VK_COMMA, ".:" + KeyEvent.VK_PERIOD, "/:" + KeyEvent.VK_SLASH,
                    "Shift:" + KeyEvent.VK_SHIFT + ":2.75"),
              keys("Ctrl:" + KeyEvent.VK_CONTROL + ":1.5", "Meta:" + KeyEvent.VK_META + ":1.25",
                    "Alt:" + KeyEvent.VK_ALT + ":1.5", " :" + KeyEvent.VK_SPACE + ":6.5",
                    "Alt:" + KeyEvent.VK_ALT + ":1.5", "Meta:" + KeyEvent.VK_META + ":1.25",
                    "Ctrl:" + KeyEvent.VK_CONTROL + ":1.5"));
        /** The keys beside the letters: the editing block, the arrows and the keypad's plus and minus. */
        private static final List<List<Key>> SIDE = List.of(
              keys("Ins:" + KeyEvent.VK_INSERT + ":1.25", "Home:" + KeyEvent.VK_HOME + ":1.25",
                    "PgUp:" + KeyEvent.VK_PAGE_UP + ":1.25", "|0.75", "|1", "\u2191:" + KeyEvent.VK_UP, "|1",
                    "|0.75", "-:" + KeyEvent.VK_SUBTRACT),
              keys("Del:" + KeyEvent.VK_DELETE + ":1.25", "End:" + KeyEvent.VK_END + ":1.25",
                    "PgDn:" + KeyEvent.VK_PAGE_DOWN + ":1.25", "|0.75", "\u2190:" + KeyEvent.VK_LEFT,
                    "\u2193:" + KeyEvent.VK_DOWN, "\u2192:" + KeyEvent.VK_RIGHT, "|0.75", "+:" + KeyEvent.VK_ADD));
        private static final Set<Integer> SHOWN = new HashSet<>();

        static {
            for (List<List<Key>> block : List.of(MAIN, SIDE)) {
                block.forEach(line -> line.forEach(key -> SHOWN.add(key.code())));
            }
        }

        private final Page page;
        /** Each shown key's caps (a modifier has two), by its key code. */
        private final Map<Integer, List<Table>> caps = new HashMap<>();
        private final Label others;
        private @Nullable Row pointed;

        KeyboardSample(Page page) {
            this.page = page;
            top();
            setName("client-keyboard");
            defaults().left();
            add(kit.caption(Messages.getString("ClientSettings.keys"), true)).growX().row();
            add(view.wrapped(Messages.getString("ClientSettings.keys.summary"), "small", "text.muted"))
                  .growX().padTop(space("xs")).row();
            add(block(MAIN)).padTop(space("md")).row();
            add(block(SIDE)).padTop(space("sm")).row();
            others = view.wrapped("", "small", "text.secondary");
            others.setName("client-keyboard-others");
            add(others).growX().padTop(space("md")).row();
            show();
        }

        /**
         * Keys from "label:code:width", a label alone for a letter or a digit, or "|width" for a gap; the code is
         * the AWT key code.
         */
        private static List<Key> keys(String... specs) {
            List<Key> keys = new ArrayList<>();
            for (String spec : specs) {
                if (spec.startsWith("|")) {
                    keys.add(new Key(NONE, "", Float.parseFloat(spec.substring(1))));
                    continue;
                }
                int colon = spec.indexOf(':', 1);
                if (colon < 0) {
                    keys.add(new Key(spec.charAt(0), spec, 1));
                    continue;
                }
                String[] rest = spec.substring(colon + 1).split(":");
                keys.add(new Key(Integer.parseInt(rest[0]), spec.substring(0, colon),
                      rest.length > 1 ? Float.parseFloat(rest[1]) : 1));
            }
            return keys;
        }

        private Table block(List<List<Key>> lines) {
            Table block = new Table();
            block.left();
            for (List<Key> line : lines) {
                Table keys = new Table();
                keys.left();
                for (Key key : line) {
                    float width = key.width() * UNIT - GAP;
                    if (key.code() == NONE) {
                        keys.add().width(width).padRight(GAP);
                        continue;
                    }
                    Table cap = new Table();
                    cap.pad(0);
                    cap.setName("client-key-" + key.code());
                    Label label = kit.typeLabel(key.label(), "caption", "text.secondary");
                    label.setAlignment(Align.center);
                    cap.add(label).grow();
                    caps.computeIfAbsent(key.code(), code -> new ArrayList<>()).add(cap);
                    keys.add(cap).width(width).height(UNIT).padRight(GAP);
                }
                block.add(keys).left().padBottom(GAP).row();
            }
            return block;
        }

        /** Lights the binding under the pointer, or none. */
        void point(@Nullable Row row) {
            pointed = row != null && row.name().startsWith(ClientSettingsForm.BIND) ? row : null;
            show();
        }

        /** Shows the page's bindings as the form has them now. */
        void show() {
            Set<Integer> bound = new HashSet<>();
            Set<Integer> twice = new HashSet<>();
            Set<String> unshown = new TreeSet<>();
            for (SettingsForm.Section section : page.sections()) {
                for (Row row : section.rows()) {
                    int code = SettingsForm.keyCode(form.text(row.name()));
                    if (!row.name().startsWith(ClientSettingsForm.BIND) || code == 0) {
                        continue;
                    }
                    bound.add(code);
                    if (form.error(row.name()) != null) {
                        twice.add(code);
                    }
                    if (!SHOWN.contains(code)) {
                        unshown.add(KeyEvent.getKeyText(code));
                    }
                }
            }
            Set<Integer> lit = new HashSet<>();
            if (pointed != null) {
                String value = form.text(pointed.name());
                int code = SettingsForm.keyCode(value);
                if (code != 0) {
                    lit.add(code);
                    int modifiers = SettingsForm.keyModifiers(value);
                    for (int[] modifier : new int[][] { { KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_CONTROL },
                                                        { KeyEvent.ALT_DOWN_MASK, KeyEvent.VK_ALT },
                                                        { KeyEvent.SHIFT_DOWN_MASK, KeyEvent.VK_SHIFT },
                                                        { KeyEvent.META_DOWN_MASK, KeyEvent.VK_META } }) {
                        if ((modifiers & modifier[0]) != 0) {
                            lit.add(modifier[1]);
                        }
                    }
                }
            }
            for (Map.Entry<Integer, List<Table>> key : caps.entrySet()) {
                int code = key.getKey();
                String tone;
                if (twice.contains(code)) {
                    tone = lit.contains(code) ? "danger-lit" : "danger-over";
                } else if (lit.contains(code)) {
                    tone = "accent-lit";
                } else if (bound.contains(code)) {
                    tone = "accent-over";
                } else {
                    tone = "neutral";
                }
                for (Table cap : key.getValue()) {
                    cap.setBackground(kit.skin.getDrawable(UiTheme.TOKEN_CHIP + tone));
                    cap.pad(0);
                    Label label = (Label) cap.getChildren().first();
                    Label.LabelStyle style = label.getStyle();
                    style.fontColor = UiTheme.tokenColor("neutral".equals(tone) ? "text.muted" : "text.primary");
                    label.setStyle(style);
                }
            }
            others.setText(unshown.isEmpty() ? ""
                  : Messages.getString("ClientSettings.keys.others", String.join(", ", unshown)));
        }
    }

    /**
     * Writes what is set into the preferences, so it takes effect at once; while a write is under way, the next waits
     * for it and writes what is set by then.
     */
    private void apply() {
        if (closed) {
            return;
        }
        if (applying) {
            pending = true;
            return;
        }
        applying = true;
        host.edt(swing(form.apply()), () -> {
            GpuHost.tooltipDelay();
            applying = false;
            if (pending) {
                pending = false;
                apply();
            }
        });
    }

    private void confirm() {
        if (closed) {
            return;
        }
        confirmed = true;
        Runnable writer = swing(form.apply());
        host.edt(() -> {
            writer.run();
            host.save();
        }, () -> {
            GpuHost.tooltipDelay();
            close.run();
        });
    }

    /** The writer, then the Swing tooltips' delay as it wrote it. */
    private static Runnable swing(Runnable writer) {
        return () -> {
            writer.run();
            ToolTipManager.sharedInstance().setInitialDelay(GUIPreferences.getInstance().getTooltipDelay());
        };
    }

    /** As the card closes: unless OK closed it, the settings go back to what they were when it opened. */
    void closed() {
        if (closed) {
            return;
        }
        closed = true;
        if (files != null) {
            files.close();
            files = null;
        }
        if (!confirmed) {
            host.edt(swing(form.revert()), GpuHost::tooltipDelay);
        }
    }

    // ---- For the probe and the tests ----

    ClientSettingsForm form() {
        return form;
    }

    UiDialog dialog() {
        return view.dialog();
    }

    String pageId() {
        return view.pageId();
    }

    /** The shown setting's control, or null when its page does not show. */
    @Nullable Actor control(String name) {
        return view.control(name);
    }

    /** The test sounds, loaded on the Swing thread as they are played. */
    private static final class Sounds {
        private static SoundManager manager;

        private static void play(SoundType sound) {
            if (manager == null) {
                manager = new SoundManager();
            }
            manager.loadSoundFiles();
            manager.playSoundNoMute(sound);
        }
    }
}
