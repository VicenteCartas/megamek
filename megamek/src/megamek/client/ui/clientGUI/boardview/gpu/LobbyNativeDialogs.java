/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import jakarta.annotation.Nullable;
import megamek.client.lobby.AssetForm;
import megamek.client.lobby.BotSetupForm;
import megamek.client.lobby.CamoCatalog;
import megamek.client.lobby.CamoChoice;
import megamek.client.lobby.ForceNameRequest;
import megamek.client.lobby.GameOptionsForm;
import megamek.client.lobby.PlanetaryConditionsForm;
import megamek.client.lobby.PlayerSettingsForm;
import megamek.client.lobby.RandomArmyForm;
import megamek.client.lobby.RandomMapForm;
import megamek.client.lobby.RandomNamesForm;
import megamek.client.lobby.RandomSkillsForm;
import megamek.client.lobby.RatBrowser;
import megamek.client.lobby.TrainOrderForm;
import megamek.client.lobby.UnitDamageForm;
import megamek.client.lobby.VictoryConditionsForm;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.entityreadout.EntityReadout;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.icons.Camouflage;
import megamek.common.units.Entity;

/**
 * The single entry point of the lobby's libGDX dialogs, as {@link LobbyDialogs} is of its Swing ones: the camo chooser,
 * the player settings, the bot setup, the planetary conditions, the game options, the random map settings, the
 * odd-sized board warning, the victory conditions, the random army, skills and names, the unit readout and a help
 * document, the small prompts ({@link LobbyPromptViews}), a message and a confirmation. A
 * dialog shows over the whole lobby on a scrim that takes every click and key ({@link UiDialog}). Opening the camo
 * chooser, the player settings, the conditions or the game options replaces whatever shows; the bot setup, a message
 * and a confirmation go over it, as the player settings' Bot settings opens the bot setup or hiding a rule category
 * asks first, and closing one shows the one below again. Every {@code new UiDialog} of the lobby is here, which
 * {@code LobbyDialogEntryPointsTest} enforces.
 * <p>
 * GPU thread. The camo pictures are read on a daemon thread of their own.
 */
final class LobbyNativeDialogs {
    private final UiKit kit;
    private final Stage stage;
    private final boolean motion;
    /** The game options' width (tokens.md): dialog.lg and dialog.sm, the navigation beside a page of options. */
    static final float OPTIONS_WIDTH = DesignTokens.shipped().size().get("dialog.lg")
          + DesignTokens.shipped().size().get("dialog.sm");
    /** What the game options' card takes above and below its page: the title bar and the footer, with their gaps. */
    private static final float OPTIONS_CHROME = 180;
    private static final float OPTIONS_MIN_PANE = 280;
    private static final float OPTIONS_MAX_PANE = 760;
    /** What the camo chooser's card takes besides its tree and grid: title, search, preview, footer and gaps. */
    private static final float CAMO_CHROME = 320;
    private static final float CAMO_MIN_GRID = 240;
    private static final float CAMO_MAX_GRID = 560;
    private ExecutorService pictures;
    /** The dialogs that show, the top one first, each with what to do once it has closed. */
    private final Deque<Open> open = new ArrayDeque<>();
    private LobbyCamoView camoView;
    private LobbyPlayerSettingsView settingsView;
    private LobbyBotSetupView botView;
    private LobbyConditionsView conditionsView;
    private LobbyGameOptionsView optionsView;
    private LobbyRandomMapView randomMapView;
    private LobbyVictoryView victoryView;
    private LobbyRandomArmyView randomArmyView;
    private LobbyCrewToolsView crewView;
    private LobbyReadoutView readoutView;
    /** The paperdoll drawings, read once the damage editor first opens. */
    private GpuPaperdolls paperdolls;

    private record Open(UiDialog dialog, Runnable closed) { }

    LobbyNativeDialogs(UiKit kit, Stage stage, boolean motion) {
        this.kit = kit;
        this.stage = stage;
        this.motion = motion;
    }

    /** Whether a dialog shows; the lobby behind it takes no keys meanwhile. */
    boolean isOpen() {
        return !open.isEmpty() && open.peek().dialog().isOpen();
    }

    /** Closes the top dialog, as Cancel would. */
    void cancel() {
        close();
    }

    /**
     * Opens the camo chooser.
     *
     * @param source  where the camos and the preview come from
     * @param catalog the camo directory as first read
     * @param choice  the camo being chosen
     * @param assets  whether the previewed units are Battlefield Support Assets, which have a marker overlay
     * @param chosen  takes the confirmed camo, on this thread
     */
    LobbyCamoView camo(LobbyCamoView.Source source, CamoCatalog catalog, CamoChoice choice, boolean assets,
          Consumer<Camouflage> chosen) {
        UiDialog dialog = begin(Messages.getString("CamoChoiceDialog.select_camo_pattern"), "dialog.xl", false,
              () -> {
                  camoView.dispose();
                  camoView = null;
              });
        if (pictures == null) {
            pictures = Executors.newSingleThreadExecutor(work -> {
                Thread thread = new Thread(work, "Lobby camo pictures");
                thread.setDaemon(true);
                return thread;
            });
        }
        camoView = new LobbyCamoView(kit, dialog, source, catalog, choice, assets, pictures, bodyWidth(dialog,
              "dialog.xl"), camoGridHeight(), chosen, this::close);
        show(dialog);
        return camoView;
    }

    /**
     * The height the camo chooser's tree and grid take: the window less its margins and the chooser's other rows, so
     * a tall window shows more camos at a glance.
     */
    private float camoGridHeight() {
        float space = DesignTokens.shipped().space().get("xxxl");
        return Math.clamp(stage.getHeight() - 2 * space - CAMO_CHROME, CAMO_MIN_GRID, CAMO_MAX_GRID);
    }

    /**
     * Opens the player settings.
     *
     * @param positions the start positions, as the form read them on the Swing thread
     * @param camo      the player's camo picture, or null
     */
    LobbyPlayerSettingsView playerSettings(PlayerSettingsForm form, List<PlayerSettingsForm.Position> positions,
          @Nullable BufferedImage camo, LobbyPlayerSettingsView.Host host) {
        UiDialog dialog = begin(Messages.getString("PlayerSettingsDialog.title"), "dialog.md", false, () -> {
            settingsView.dispose();
            settingsView = null;
        });
        settingsView = new LobbyPlayerSettingsView(kit, dialog, form, positions, camo, host, this::close);
        show(dialog);
        return settingsView;
    }

    /**
     * Opens the bot setup over whatever shows.
     *
     * @param host what the view asks of the window
     */
    LobbyBotSetupView botSetup(BotSetupForm form, LobbyBotSetupView.Host host) {
        UiDialog dialog = begin(Messages.getString("BotConfigDialog.title"), "dialog.lg", true, () -> botView = null);
        botView = new LobbyBotSetupView(kit, dialog, form, bodyWidth(dialog, "dialog.lg"), host, this::close);
        show(dialog);
        return botView;
    }

    /** Opens the planetary conditions. */
    LobbyConditionsView conditions(PlanetaryConditionsForm form, LobbyConditionsView.Host host) {
        UiDialog dialog = begin(Messages.getString("PlanetaryConditionsDialog.title"), "dialog.md", false,
              () -> conditionsView = null);
        conditionsView = new LobbyConditionsView(kit, dialog, form, host, this::close);
        show(dialog);
        return conditionsView;
    }

    /**
     * Opens the game options, as wide as a large and a small dialog side by side ({@link #OPTIONS_WIDTH}), its page
     * as high as the window leaves.
     */
    LobbyGameOptionsView gameOptions(GameOptionsForm form, LobbyGameOptionsView.Host host) {
        UiDialog dialog = begin(Messages.getString("GameOptionsDialog.title"), OPTIONS_WIDTH, false,
              () -> optionsView = null);
        float space = DesignTokens.shipped().space().get("xxxl");
        // The window less its margins, the card's title and footer, and the navigation's search above the tree
        float paneHeight = Math.clamp(stage.getHeight() - 2 * space - OPTIONS_CHROME, OPTIONS_MIN_PANE,
              OPTIONS_MAX_PANE);
        optionsView = new LobbyGameOptionsView(kit, dialog, this, form, bodyWidth(dialog, OPTIONS_WIDTH), paneHeight,
              host, this::close);
        show(dialog);
        return optionsView;
    }

    /** Opens the victory conditions as objective cards. */
    LobbyVictoryView victory(VictoryConditionsForm form, LobbyVictoryView.Host host) {
        UiDialog dialog = begin(Messages.getString("VictoryConditionsDialog.title"), "dialog.lg", false,
              () -> victoryView = null);
        victoryView = new LobbyVictoryView(kit, dialog, form, bodyWidth(dialog, "dialog.lg"), host, this::close);
        show(dialog);
        return victoryView;
    }

    /**
     * Opens the random army, as wide as the game options, with {@code browser} over its tables; {@code sprites} has
     * the units' sprites drawn so far.
     */
    LobbyRandomArmyView randomArmy(RandomArmyForm form, RatBrowser browser, Function<String, Drawable> sprites,
          LobbyRandomArmyView.Host host) {
        UiDialog dialog = begin(Messages.getString("Lobby.randomArmy.title"), OPTIONS_WIDTH, false,
              () -> randomArmyView = null);
        randomArmyView = new LobbyRandomArmyView(kit, dialog, form, browser, bodyWidth(dialog, OPTIONS_WIDTH),
              sprites, host, this::close);
        show(dialog);
        return randomArmyView;
    }

    /** Opens the random skills. */
    LobbyCrewToolsView skills(RandomSkillsForm form, LobbyCrewToolsView.Host host) {
        UiDialog dialog = begin(Messages.getString("Lobby.skills.title"), "dialog.sm", false, () -> crewView = null);
        crewView = LobbyCrewToolsView.skills(kit, dialog, form, host, this::close);
        show(dialog);
        return crewView;
    }

    /** Opens the random names. */
    LobbyCrewToolsView names(RandomNamesForm form, LobbyCrewToolsView.Host host) {
        UiDialog dialog = begin(Messages.getString("RandomNameDialog.title"), "dialog.sm", false,
              () -> crewView = null);
        crewView = LobbyCrewToolsView.names(kit, dialog, form, host, this::close);
        show(dialog);
        return crewView;
    }

    /** Opens the dossiers of one or more units. */
    LobbyReadoutView readout(List<LobbyReadoutView.Dossier> dossiers) {
        String title = dossiers.size() == 1 ? dossiers.get(0).name() : Messages.getString("Lobby.readout.title");
        UiDialog dialog = begin(title, "dialog.lg", false, () -> {
            readoutView.dispose();
            readoutView = null;
        });
        readoutView = new LobbyReadoutView(kit, dialog, dossiers, motion, this::close);
        show(dialog);
        return readoutView;
    }

    /** Shows a help document's rows over whatever shows, as the bot setup's help. */
    LobbyReadoutView help(String title, List<EntityReadout.Row> rows) {
        UiDialog dialog = begin(title, "dialog.lg", true, () -> {
            readoutView.dispose();
            readoutView = null;
        });
        readoutView = new LobbyReadoutView(kit, dialog,
              List.of(new LobbyReadoutView.Dossier(title, "", null, List.of(rows))), motion, this::close);
        show(dialog);
        return readoutView;
    }

    /** Asks for a force designation over whatever shows. */
    UiDialog forceName(ForceNameRequest request, LobbyPromptViews.Edt edt, Consumer<String> chosen) {
        UiDialog dialog = begin(Messages.getString("ChatLounge.forceName.title"), "dialog.sm", true, () -> { });
        LobbyPromptViews.forceName(kit, dialog, request, edt, chosen, this::close);
        show(dialog);
        return dialog;
    }

    /** Asks for a squadron designation over whatever shows. */
    UiDialog squadron(LobbyPromptViews.Edt edt, Consumer<String> chosen) {
        UiDialog dialog = begin(Messages.getString("Lobby.prompt.squadronTitle"), "dialog.sm", true, () -> { });
        LobbyPromptViews.squadron(kit, dialog, edt, chosen, this::close);
        show(dialog);
        return dialog;
    }

    /** Shows a Battlefield Support Asset's crew over whatever shows. */
    UiDialog assetCrew(AssetForm form, LobbyPromptViews.Edt edt, Runnable applied) {
        UiDialog dialog = begin(Messages.getString("BattlefieldSupportAssetConfigDialog.title"), "dialog.sm", true,
              () -> { });
        LobbyPromptViews.assetCrew(kit, dialog, form, edt, applied, this::close);
        show(dialog);
        return dialog;
    }

    /** Shows a Battlefield Support Asset's damage over whatever shows. */
    UiDialog assetDamage(AssetForm form, LobbyPromptViews.Edt edt, Runnable applied) {
        UiDialog dialog = begin(Messages.getString("BattlefieldSupportAssetDamageDialog.title"), "dialog.sm", true,
              () -> { });
        LobbyPromptViews.assetDamage(kit, dialog, form, edt, applied, this::close);
        show(dialog);
        return dialog;
    }

    /**
     * Shows the unit's damage editor over whatever shows.
     *
     * @param applied runs on the Swing thread once OK wrote the edits to the unit
     */
    UiDialog damage(UnitDamageForm form, LobbyPromptViews.Edt edt, Runnable applied) {
        if (paperdolls == null) {
            paperdolls = new GpuPaperdolls();
        }
        // a Mek's front, rear and structure dolls stand side by side, as on the record sheet, so it takes more width
        UiDialog dialog = begin(Messages.getString("Lobby.damage.title", form.entity().getDisplayName()),
              LobbyDamageView.sheet(paperdolls, form) ? "dialog.xl" : "dialog.lg", true, () -> { });
        new LobbyDamageView(kit, dialog, form, paperdolls, edt, applied, this::close);
        show(dialog);
        return dialog;
    }

    /** Asks for a train's order over whatever shows. */
    UiDialog trainOrder(TrainOrderForm form, LobbyPromptViews.Edt edt, Consumer<List<Entity>> ordered) {
        UiDialog dialog = begin(Messages.getString("TrainOrderDialog.title"), "dialog.sm", true, () -> { });
        LobbyPromptViews.trainOrder(kit, dialog, form, edt, ordered, this::close);
        show(dialog);
        return dialog;
    }

    /** Asks {@code text} over whatever shows; {@code yes} runs once OK closed it, and Cancel only closes it. */
    UiDialog confirm(String title, String text, Runnable yes) {
        return confirm(title, text, yes, () -> { });
    }

    /** As {@link #confirm(String, String, Runnable)}, running {@code no} when the dialog closes otherwise. */
    UiDialog confirm(String title, String text, Runnable yes, Runnable no) {
        boolean[] confirmed = new boolean[1];
        UiDialog dialog = begin(title, "dialog.sm", true, () -> {
            if (!confirmed[0]) {
                no.run();
            }
        });
        var label = kit.typeLabel(text, "body", "text.primary");
        label.setWrap(true);
        dialog.body().add(label).growX().left();
        dialog.primary(Messages.getString("Ok.text"), () -> {
            confirmed[0] = true;
            close();
            yes.run();
        }).setName("confirm-ok");
        dialog.cancelButton(Messages.getString("Cancel.text")).setName("confirm-cancel");
        show(dialog);
        return dialog;
    }

    /** What a {@link #yesNo} question was answered: Yes or No, and whether its box was checked. */
    interface YesNoAnswer {
        void answered(boolean yes, boolean checked);
    }

    /**
     * Asks a Yes or No question with a check box under its paragraphs, over whatever shows. Closing it any other way,
     * such as with Esc, answers nothing.
     */
    UiDialog yesNo(String title, List<String> paragraphs, String check, YesNoAnswer answer) {
        UiDialog dialog = begin(title, "dialog.md", true, () -> { });
        float gap = DesignTokens.shipped().space().get("md");
        for (String paragraph : paragraphs) {
            var label = kit.typeLabel(paragraph, "body", "text.primary");
            label.setWrap(true);
            dialog.body().add(label).growX().left().padBottom(gap).row();
        }
        UiChoice box = kit.check(check, false);
        box.setName("yes-no-check");
        dialog.body().add(box).left().padTop(gap);
        dialog.primary(Messages.getString("Yes"), () -> {
            close();
            answer.answered(true, box.isChecked());
        }).setName("yes-no-yes");
        dialog.secondary(Messages.getString("No"), () -> {
            close();
            answer.answered(false, box.isChecked());
        }).setName("yes-no-no");
        show(dialog);
        return dialog;
    }

    /** What the save question before leaving was answered; closing it any other way stays in the game. */
    interface SaveFirstAnswer {
        /**
         * @param save whether to save the game before leaving
         */
        void answered(boolean save);
    }

    /**
     * Asks whether to save the game before leaving it: Save…, Don't save, or Cancel to stay. Settings → Advanced
     * turns the question off.
     *
     * @param stayed runs when it closes unanswered, as with Cancel or Esc
     */
    UiDialog saveFirst(SaveFirstAnswer answer, Runnable stayed) {
        boolean[] answered = new boolean[1];
        UiDialog dialog = begin(Messages.getString("Lobby.saveFirst.title"), "dialog.md", true, () -> {
            if (!answered[0]) {
                stayed.run();
            }
        });
        var label = kit.typeLabel(Messages.getString("Lobby.saveFirst.message"), "body", "text.primary");
        label.setWrap(true);
        dialog.body().add(label).growX().left();
        dialog.primary(Messages.getString("Lobby.saveFirst.save"), () -> {
            answered[0] = true;
            close();
            answer.answered(true);
        }).setName("save-first-save");
        dialog.secondary(Messages.getString("Lobby.saveFirst.leave"), () -> {
            answered[0] = true;
            close();
            answer.answered(false);
        }).setName("save-first-leave");
        dialog.cancelButton(Messages.getString("Cancel.text")).setName("save-first-cancel");
        show(dialog);
        return dialog;
    }

    /** What the lobby's system menu does; the screen runs each on the Swing thread. */
    interface SystemActions {
        void save();

        void quickSave();

        /** Asks for a saved game and leaves the lobby to load it. */
        void load();

        /** Leaves the lobby to load the quick save. */
        void quickLoad();

        /** Whether this lobby offers loading at all: a MekHQ lobby loads its own campaigns. */
        boolean loads();

        void settings();

        void leave();
    }

    /**
     * The lobby's system menu, as a game's pause menu: Resume, then saving and loading, Settings and Leave lobby, one
     * column of buttons in groups. Esc or Resume closes it; every other button closes it before it acts.
     */
    UiDialog systemMenu(SystemActions actions) {
        UiDialog dialog = begin(Messages.getString("Lobby.menu.title"), "dialog.sm", false, () -> { });
        Table column = dialog.body();
        float gap = DesignTokens.shipped().space().get("sm");
        float group = DesignTokens.shipped().space().get("lg");
        menuButton(column, "button-primary", "Lobby.menu.resume", null, 0, null);
        menuButton(column, "button-secondary", "Lobby.gameMenu.save", KeyCommandBind.LOCAL_SAVE, group,
              actions::save);
        menuButton(column, "button-secondary", "Lobby.gameMenu.quickSave", KeyCommandBind.QUICK_SAVE, gap,
              actions::quickSave);
        if (actions.loads()) {
            menuButton(column, "button-secondary", "Lobby.gameMenu.load", KeyCommandBind.LOCAL_LOAD, gap,
                  actions::load);
            menuButton(column, "button-secondary", "Lobby.gameMenu.quickLoad", KeyCommandBind.QUICK_LOAD, gap,
                  actions::quickLoad);
        }
        menuButton(column, "button-secondary", "Lobby.menu.settings", KeyCommandBind.CLIENT_SETTINGS, group,
              actions::settings);
        menuButton(column, "button-secondary", "Lobby.menu.leave", null, group, actions::leave);
        show(dialog);
        return dialog;
    }

    private void menuButton(Table column, String style, String key, KeyCommandBind binding, float above,
          Runnable action) {
        UiButton button = kit.button(style, null, Messages.getString(key), null);
        button.setName(key);
        if (binding != null) {
            kit.tip(button, true).getActor().setText(KeyCommandBind.getDesc(binding));
        }
        UiKit.onChange(button, () -> {
            close();
            if (action != null) {
                action.run();
            }
        });
        column.add(button).growX().padTop(above).row();
    }

    /**
     * Opens the random map settings.
     *
     * @param themes the tileset's themes
     */
    LobbyRandomMapView randomMap(RandomMapForm form, List<String> themes, LobbyRandomMapView.Host host) {
        UiDialog dialog = begin(Messages.getString("RandomMapDialog.title"), "dialog.lg", false,
              () -> randomMapView = null);
        randomMapView = new LobbyRandomMapView(kit, dialog, form, themes, host, this::close);
        show(dialog);
        return randomMapView;
    }

    /**
     * Warns that boards of odd width do not tile, over whatever shows, as the Swing map setup's bother dialog.
     *
     * @param dontShowAgain runs once OK closed it with "Don't show again" checked
     */
    UiDialog oddBoardNag(Runnable dontShowAgain) {
        UiDialog dialog = begin(Messages.getString("ChatLounge.board.warning.title"), "dialog.md", true, () -> { });
        var label = kit.typeLabel(Messages.getString("ChatLounge.board.warning.message"), "body", "text.primary");
        label.setWrap(true);
        dialog.body().add(label).growX().left().row();
        UiChoice again = kit.check(Messages.getString("SimpleNagNotice.dontShowAgain"), false);
        again.setName("nag-dont-show");
        dialog.body().add(again).left().padTop(DesignTokens.shipped().space().get("lg"));
        dialog.primary(Messages.getString("Ok.text"), () -> {
            close();
            if (again.isChecked()) {
                dontShowAgain.run();
            }
        }).setName("nag-ok");
        show(dialog);
        return dialog;
    }

    /**
     * Shows an error over whatever shows: OK closes it, and the report button, when {@code reportLabel} is given, closes
     * it and runs {@code report}.
     */
    UiDialog error(String title, String text, String dismissLabel, @Nullable String reportLabel,
          @Nullable Runnable report) {
        UiDialog dialog = begin(title, "dialog.md", true, () -> { });
        var label = kit.typeLabel(text, "body", "text.primary");
        label.setWrap(true);
        dialog.body().add(label).growX().left();
        dialog.primary(dismissLabel, this::close).setName("error-ok");
        if (reportLabel != null && report != null) {
            dialog.secondary(reportLabel, () -> {
                close();
                report.run();
            }).setName("error-report");
        }
        show(dialog);
        return dialog;
    }

    /** Shows {@code text} with an OK button over whatever shows, as an alert. */    UiDialog message(String title, String text) {
        UiDialog dialog = begin(title, "dialog.sm", true, () -> { });
        var label = kit.typeLabel(text, "body", "text.primary");
        label.setWrap(true);
        dialog.body().add(label).growX().left();
        dialog.primary(Messages.getString("Ok.text"), this::close).setName("message-ok");
        show(dialog);
        return dialog;
    }

    /**
     * @param over   whether it goes over the dialogs that show rather than replacing them
     * @param closed what to do once it has closed, such as giving its view's textures back
     */
    private UiDialog begin(String title, String width, boolean over, Runnable closed) {
        return begin(title, DesignTokens.shipped().size().get(width), over, closed);
    }

    private UiDialog begin(String title, float width, boolean over, Runnable closed) {
        if (!over) {
            while (!open.isEmpty()) {
                close();
            }
        }
        UiDialog dialog = new UiDialog(kit, title, this::close).width(width);
        open.push(new Open(dialog, closed));
        return dialog;
    }

    private void show(UiDialog dialog) {
        dialog.show(stage, motion);
    }

    /** The width the body will have in this window: the card's width less its padding and the scroll bar. */
    private float bodyWidth(UiDialog dialog, String width) {
        return bodyWidth(dialog, DesignTokens.shipped().size().get(width));
    }

    private float bodyWidth(UiDialog dialog, float width) {
        float space = DesignTokens.shipped().space().get("xxxl");
        float card = Math.min(width, Math.max(0, stage.getWidth() - 2 * space));
        return Math.max(0, card - dialog.card().getPadLeft() - dialog.card().getPadRight() - DesignTokens.shipped()
              .space().get("md"));
    }

    /** Closes the top dialog, showing the one below it again. */
    private void close() {
        Open top = open.poll();
        if (top != null) {
            top.dialog().close();
            top.closed().run();
            Open below = open.peek();
            if (below != null) {
                stage.setScrollFocus(below.dialog().scroll());
            }
        }
    }

    /** As the screen goes: closes every dialog and stops the picture thread. */
    void dispose() {
        while (!open.isEmpty()) {
            close();
        }
        if (pictures != null) {
            pictures.shutdownNow();
            pictures = null;
        }
    }

    // ---- For the probe and the tests ----

    @Nullable LobbyCamoView camoView() {
        return camoView;
    }

    @Nullable LobbyPlayerSettingsView settingsView() {
        return settingsView;
    }

    @Nullable LobbyBotSetupView botView() {
        return botView;
    }

    @Nullable LobbyConditionsView conditionsView() {
        return conditionsView;
    }

    @Nullable LobbyGameOptionsView optionsView() {
        return optionsView;
    }

    @Nullable LobbyRandomMapView randomMapView() {
        return randomMapView;
    }

    @Nullable LobbyVictoryView victoryView() {
        return victoryView;
    }

    @Nullable LobbyRandomArmyView randomArmyView() {
        return randomArmyView;
    }

    @Nullable LobbyCrewToolsView crewView() {
        return crewView;
    }

    @Nullable LobbyReadoutView readoutView() {
        return readoutView;
    }

    /** The top dialog, or null. */
    @Nullable UiDialog dialog() {
        return open.isEmpty() ? null : open.peek().dialog();
    }
}
