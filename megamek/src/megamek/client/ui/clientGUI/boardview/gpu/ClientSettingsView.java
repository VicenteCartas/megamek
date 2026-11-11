/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuSettingsView.space;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import jakarta.annotation.Nullable;
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
 * palette and motion the page sets.
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
          ClientSettingsForm.DISPLAY, "display");
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
            return soundCheck != null ? soundCheck : sample;
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
        host.edt(form.apply(), () -> {
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
        Runnable writer = form.apply();
        host.edt(() -> {
            writer.run();
            host.save();
        }, close);
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
            host.edt(form.revert(), () -> { });
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
