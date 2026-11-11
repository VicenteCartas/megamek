/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import jakarta.annotation.Nullable;
import megamek.client.lobby.AlphaStrikeStatsForm;
import megamek.client.lobby.AssetForm;
import megamek.client.lobby.AutoResolveRun;
import megamek.client.lobby.BotFacingForm;
import megamek.client.lobby.BotSetupForm;
import megamek.client.lobby.BuildingEditForm;
import megamek.client.lobby.CamoCatalog;
import megamek.client.lobby.CamoChoice;
import megamek.client.lobby.ClientCommandForm;
import megamek.client.lobby.EditBotsForm;
import megamek.client.lobby.ForceNameRequest;
import megamek.client.lobby.GameMasterPlayerSetup;
import megamek.client.lobby.GameMasterUnitTools;
import megamek.client.lobby.GameOptionsForm;
import megamek.client.lobby.HelpDocument;
import megamek.client.lobby.LanceRoleForm;
import megamek.client.lobby.LobbyForces;
import megamek.client.lobby.LobbyGameMaster;
import megamek.client.lobby.LobbyRequisition;
import megamek.client.lobby.MapNoteForm;
import megamek.client.lobby.NetworkInformationForm;
import megamek.client.lobby.NovaNetworkForm;
import megamek.client.lobby.PlanetaryConditionsForm;
import megamek.client.lobby.PlayerSettingsForm;
import megamek.client.lobby.PortraitCatalog;
import megamek.client.lobby.RandomArmyForm;
import megamek.client.lobby.RandomMapForm;
import megamek.client.lobby.RandomNamesForm;
import megamek.client.lobby.RandomSkillsForm;
import megamek.client.lobby.RatBrowser;
import megamek.client.lobby.SbfStatsForm;
import megamek.client.lobby.TrainOrderForm;
import megamek.client.lobby.UnitDamageForm;
import megamek.client.lobby.VictoryConditionsForm;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.Player;
import megamek.common.SpecialHexDisplay;
import megamek.common.icons.Camouflage;
import megamek.common.icons.Portrait;
import megamek.common.units.Entity;

/**
 * The single entry point of the lobby's libGDX dialogs, as {@link LobbyDialogs} is of its Swing ones: the camo chooser,
 * the player settings, the bot setup, the planetary conditions, the game options, the random map settings, the
 * odd-sized board warning, the victory conditions, the random army, skills and names, the unit readout and a help
 * document, the small prompts ({@link LobbyPromptViews}), a message and a confirmation. A
 * dialog shows over the whole lobby on a scrim that takes every click and key ({@link UiDialog}). Opening the camo
 * or portrait chooser, the player settings, the conditions or the game options replaces whatever shows; the bot setup, a message
 * and a confirmation go over it, as the player settings' Bot settings opens the bot setup or hiding a rule category
 * asks first, and closing one shows the one below again. Every {@code new UiDialog} of the lobby is here, which
 * {@code LobbyDialogEntryPointsTest} enforces.
 * <p>
 * GPU thread. The camo and portrait pictures are read on a daemon thread of their own.
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
    /** What the Requisition's card takes besides its view: the title bar and the footer, with their gaps. */
    private static final float REQUISITION_CHROME = 140;
    private static final float REQUISITION_MIN_HEIGHT = 360;
    private ExecutorService pictures;
    /** The dialogs that show, the top one first, each with what to do once it has closed. */
    private final Deque<Open> open = new ArrayDeque<>();
    private LobbyCamoView camoView;
    private LobbyPortraitView portraitView;
    private LobbyPlayerSettingsView settingsView;
    private LobbyBotSetupView botView;
    private LobbyEditBotsView editBotsView;
    private LobbyNetworkInfoView networkInfoView;
    private LobbyNovaNetworkView novaNetworkView;
    private LobbyConditionsView conditionsView;
    private GpuMaterialPalette paletteView;
    private LobbyGameOptionsView optionsView;
    private LobbyRandomMapView randomMapView;
    private LobbyVictoryView victoryView;
    private LobbyRandomArmyView randomArmyView;
    private LobbyRequisitionPanel requisitionView;
    private LobbyCrewToolsView crewView;
    private LobbyReadoutView readoutView;
    private LobbyDocumentView documentView;
    private LobbyAutoResolveView autoResolveView;
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
        camoView = new LobbyCamoView(kit, dialog, source, catalog, choice, assets, pictures(), bodyWidth(dialog,
              "dialog.xl"), camoGridHeight(), chosen, this::close);
        show(dialog);
        return camoView;
    }

    /**
     * Opens the portrait chooser over the lobby and its unit bay, replacing whatever dialog shows.
     *
     * @param source  where the portraits come from
     * @param catalog the portrait directory as first read
     * @param initial the crew member's portrait, selected at first, or null
     * @param chosen  takes the confirmed portrait, on this thread
     */
    LobbyPortraitView portrait(LobbyPortraitView.Source source, PortraitCatalog catalog, @Nullable Portrait initial,
          Consumer<Portrait> chosen) {
        UiDialog dialog = begin(Messages.getString("PortraitChoiceDialog.select_portrait"), "dialog.xl", false,
              () -> {
                  portraitView.dispose();
                  portraitView = null;
              });
        portraitView = new LobbyPortraitView(kit, dialog, source, catalog, initial, pictures(),
              bodyWidth(dialog, "dialog.xl"), camoGridHeight(), chosen, this::close);
        show(dialog);
        return portraitView;
    }

    /** The daemon thread the icon choosers read their pictures on, started at first use. */
    private ExecutorService pictures() {
        if (pictures == null) {
            pictures = Executors.newSingleThreadExecutor(work -> {
                Thread thread = new Thread(work, "Lobby icon pictures");
                thread.setDaemon(true);
                return thread;
            });
        }
        return pictures;
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

    /**
     * Opens the game's bots and dropped-out players as a card over the battle; each seat's bot settings open the bot
     * setup over it.
     */
    LobbyEditBotsView editBots(EditBotsForm form, LobbyEditBotsView.Host host) {
        UiDialog dialog = begin(Messages.getString("EditBotsDialog.native.title"), "dialog.lg", false,
              () -> editBotsView = null);
        editBotsView = new LobbyEditBotsView(kit, dialog, form, host, () -> closeDialog(dialog));
        show(dialog);
        return editBotsView;
    }

    /**
     * Opens this computer's addresses and the game's host as a card over the battle, showing {@code rows} until
     * {@link LobbyNetworkInfoView#addresses} brings the looked-up ones.
     */
    LobbyNetworkInfoView networkInformation(List<NetworkInformationForm.Row> rows) {
        UiDialog dialog = begin(Messages.getString("NetworkInformation.title"), "dialog.sm", false,
              () -> networkInfoView = null);
        networkInfoView = new LobbyNetworkInfoView(kit, dialog, rows, () -> closeDialog(dialog));
        show(dialog);
        return networkInfoView;
    }

    /** Opens the Nova CEWS networks, read only, as a card over the battle. */
    LobbyNovaNetworkView novaNetworks(NovaNetworkForm form) {
        UiDialog dialog = begin(Messages.getString("NovaNetworkViewDialog.title"), "dialog.md", false,
              () -> novaNetworkView = null);
        novaNetworkView = new LobbyNovaNetworkView(kit, dialog, form, () -> closeDialog(dialog));
        show(dialog);
        return novaNetworkView;
    }

    /** Opens the planetary conditions. */
    LobbyConditionsView conditions(PlanetaryConditionsForm form, LobbyConditionsView.Host host) {
        return conditions(form, host, () -> { });
    }

    /** Opens the planetary conditions; {@code closed} runs once they close, confirmed or not. */
    LobbyConditionsView conditions(PlanetaryConditionsForm form, LobbyConditionsView.Host host, Runnable closed) {
        UiDialog dialog = begin(Messages.getString("PlanetaryConditionsDialog.title"), "dialog.md", false,
              () -> {
                  conditionsView = null;
                  closed.run();
              });
        conditionsView = new LobbyConditionsView(kit, dialog, form, host, this::close);
        show(dialog);
        return conditionsView;
    }

    /**
     * Opens the Tuning's material palette, a gallery of every board material drawn as the game draws it.
     *
     * @param reloadAssets the board's Reload assets, on this thread; null without a board
     */
    GpuMaterialPalette materialPalette(Runnable reloadAssets) {
        UiDialog dialog = begin(Messages.getString("GpuBoard.palette.title"), GpuMaterialPalette.WIDTH, false,
              () -> {
                  paletteView.dispose();
                  paletteView = null;
              });
        paletteView = new GpuMaterialPalette(kit, dialog, bodyWidth(dialog, GpuMaterialPalette.WIDTH), reloadAssets,
              this::close, Gdx.app::postRunnable);
        show(dialog);
        return paletteView;
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
     * Opens the random army, as wide as the game options, with {@code browser} over its tables and {@code generators}
     * beside them; {@code sprites} has the units' sprites drawn so far.
     */
    LobbyRandomArmyView randomArmy(RandomArmyForm form, RatBrowser browser, LobbyRandomArmyView.Generators generators,
          Function<String, Drawable> sprites, LobbyRandomArmyView.Host host) {
        UiDialog dialog = begin(Messages.getString("Lobby.randomArmy.title"), OPTIONS_WIDTH, false,
              () -> randomArmyView = null);
        randomArmyView = new LobbyRandomArmyView(kit, dialog, form, browser, generators,
              bodyWidth(dialog, OPTIONS_WIDTH), sprites, host, this::close);
        show(dialog);
        return randomArmyView;
    }

    /**
     * Opens the Requisition as a card over the battle, for a Game Master's reinforcements: as wide as the game options
     * and as tall as the window allows. {@code requisition} and {@code forces} are what it opens on; {@code closed}
     * runs once it has closed, after the view gave back its pictures.
     */
    LobbyRequisitionPanel requisition(LobbyRequisitionPanel.Actions actions, Function<String, Drawable> sprites,
          LobbyRequisition requisition, LobbyForces forces, Runnable closed) {
        LobbyRequisitionPanel view = new LobbyRequisitionPanel(kit, actions, sprites, motion);
        UiDialog dialog = begin(Messages.getString("Lobby.requisition.title"), OPTIONS_WIDTH, false, () -> {
            view.dispose();
            if (requisitionView == view) {
                requisitionView = null;
            }
            closed.run();
        });
        requisitionView = view;
        view.show(requisition, forces);
        float space = DesignTokens.shipped().space().get("xxxl");
        float height = Math.max(REQUISITION_MIN_HEIGHT, stage.getHeight() - 2 * space - REQUISITION_CHROME);
        dialog.body().add(view.body()).width(bodyWidth(dialog, OPTIONS_WIDTH)).height(height);
        dialog.primary(Messages.getString("Close"), this::close);
        show(dialog);
        view.opened(forces);
        return view;
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
        return readout(dossiers, false);
    }

    /** Opens the dossiers of one or more units over the dialog that shows, as a random army's unit. */
    LobbyReadoutView readoutOver(List<LobbyReadoutView.Dossier> dossiers) {
        return readout(dossiers, true);
    }

    private LobbyReadoutView readout(List<LobbyReadoutView.Dossier> dossiers, boolean over) {
        String title = dossiers.size() == 1 ? dossiers.get(0).name() : Messages.getString("Lobby.readout.title");
        UiDialog dialog = begin(title, "dialog.lg", over, () -> {
            readoutView.dispose();
            readoutView = null;
        });
        readoutView = new LobbyReadoutView(kit, dialog, dossiers, motion, this::close);
        show(dialog);
        return readoutView;
    }

    /**
     * Shows a help document or battle report a chapter at a time over whatever shows, as the bot setup's help;
     * {@code grid} sets its topics two by two, as a guide's.
     */
    LobbyDocumentView document(String title, HelpDocument document, boolean grid) {
        UiDialog dialog = begin(title, "dialog.xl", true, () -> documentView = null);
        documentView = new LobbyDocumentView(kit, dialog, document, grid, motion, this::close);
        show(dialog);
        return documentView;
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

    /**
     * Shows the Game Master's damage card of a unit in a running game, read with
     * {@link UnitDamageForm.Options#inPlay}: OK sends the edits to the server through {@code host}, and the card adds
     * the Game Master's tools, whose server acts ask here first.
     *
     * @param closed runs once the card has closed, however it closed
     */
    UiDialog gameMasterDamage(UnitDamageForm form, LobbyPromptViews.Edt edt, GameMasterUnitTools.Host host,
          Runnable closed) {
        if (paperdolls == null) {
            paperdolls = new GpuPaperdolls();
        }
        UiDialog dialog = begin(Messages.getString("Lobby.damage.title", form.entity().getDisplayName()),
              LobbyDamageView.sheet(paperdolls, form) ? "dialog.xl" : "dialog.lg", true, closed);
        new LobbyDamageView(kit, dialog, form, paperdolls, edt, () -> { }, this::close, new LobbyDamageView.Battle() {
            @Override
            public void confirm(String title, String text, Runnable yes, Runnable no) {
                LobbyNativeDialogs.this.confirm(title, text, yes, no);
            }

            @Override
            public void confirm(String title, String text, String check, String checkTip, boolean checkable,
                  Consumer<Boolean> yes) {
                yesNo(title, List.of(text), check, checkTip, checkable, (answer, checked) -> {
                    if (answer) {
                        yes.accept(checked);
                    }
                });
            }

            @Override
            public GameMasterUnitTools.Host host() {
                return host;
            }
        });
        show(dialog);
        return dialog;
    }

    /** A map note's text and who sees it, over whatever shows; Done hands the note to {@code done}. */
    UiDialog mapNote(MapNoteForm form, LobbyPromptViews.Edt edt, Consumer<SpecialHexDisplay> done, Runnable closed) {
        UiDialog dialog = begin(Messages.getString("NoteDialog.title"), "dialog.sm", true, closed);
        LobbyGameMasterViews.mapNote(kit, dialog, form, edt, done, () -> closeDialog(dialog));
        show(dialog);
        return dialog;
    }

    /**
     * The running Game Master vote over whatever shows, filled again in place through the card it returns.
     *
     * @param closed runs once the card has closed, however it closed
     */
    LobbyGameMasterViews.VoteCard gameMasterVote(LobbyGameMaster vote, LobbyGameMasterViews.VoteActions actions,
          Runnable closed) {
        UiDialog dialog = begin(Messages.getString("GameMasterVoteDialog.title"), "dialog.sm", true, closed);
        LobbyGameMasterViews.VoteCard card = new LobbyGameMasterViews.VoteCard(kit, dialog, actions);
        card.fill(vote);
        show(dialog);
        return card;
    }

    /** A command's arguments as a form over whatever shows; Execute asks, then sends it through {@code chat}. */
    UiDialog clientCommand(ClientCommandForm form, LobbyPromptViews.Edt edt, Consumer<String> chat) {
        UiDialog dialog = begin(form.title(), "dialog.md", true, () -> { });
        LobbyGameMasterViews.clientCommand(kit, dialog, form, this::confirm, edt, chat, () -> closeDialog(dialog));
        show(dialog);
        return dialog;
    }

    /** A player's team and deployment zone over whatever shows, with the ways to hand them units. */
    UiDialog playerSetup(GameMasterPlayerSetup setup, LobbyPromptViews.Edt edt, Consumer<String> chat,
          Consumer<Player> fromCatalogue, Consumer<Player> fromFile, Consumer<Player> fromGenerator) {
        UiDialog dialog = begin(Messages.getString("GameMasterPlayerSetupDialog.title"), "dialog.md", true, () -> { });
        LobbyGameMasterViews.playerSetup(kit, dialog, setup, edt, chat, fromCatalogue, fromFile, fromGenerator,
              () -> closeDialog(dialog));
        show(dialog);
        return dialog;
    }

    /** A hex's building or fuel tank over whatever shows. */
    UiDialog building(BuildingEditForm form, LobbyPromptViews.Edt edt, LobbyGameMasterViews.BuildingPicture picture,
          Runnable closed) {
        Runnable[] dispose = new Runnable[1];
        UiDialog dialog = begin(form.title(), "dialog.md", true, () -> {
            if (dispose[0] != null) {
                dispose[0].run();
            }
            closed.run();
        });
        dispose[0] = LobbyGameMasterViews.building(kit, dialog, form, edt, (title, text, ignored) -> message(title,
              text), picture, () -> closeDialog(dialog));
        show(dialog);
        return dialog;
    }

    /** Closes {@code dialog} wherever it stands among those that show, as its own close would. */
    void closeDialog(UiDialog dialog) {
        Iterator<Open> each = open.iterator();
        while (each.hasNext()) {
            Open shown = each.next();
            if (shown.dialog() == dialog) {
                each.remove();
                dialog.close();
                shown.closed().run();
                Open top = open.peek();
                if (top != null) {
                    stage.setScrollFocus(top.dialog().scroll());
                }
                return;
            }
        }
    }

    /** The kit and stage the cards show on, for a panel beside the board that is not a card, such as the brush. */
    UiKit kit() {
        return kit;
    }

    Stage stage() {
        return stage;
    }

    /** Asks for a train's order over whatever shows. */
    UiDialog trainOrder(TrainOrderForm form, LobbyPromptViews.Edt edt, Consumer<List<Entity>> ordered) {
        UiDialog dialog = begin(Messages.getString("TrainOrderDialog.title"), "dialog.sm", true, () -> { });
        LobbyPromptViews.trainOrder(kit, dialog, form, edt, ordered, this::close);
        show(dialog);
        return dialog;
    }

    /** Asks for a bot's unit's facings; {@code confirmed} runs on the Swing thread once OK closed it. */
    UiDialog facing(BotFacingForm form, LobbyPromptViews.Edt edt, Runnable confirmed) {
        UiDialog dialog = begin(Messages.getString("BotCommandPanel.Orders.facing.dialogTitle"), "dialog.sm", true,
              () -> { });
        LobbyBotOrderViews.facing(kit, dialog, form, edt, confirmed, this::close);
        show(dialog);
        return dialog;
    }

    /** Asks for a bot's lance's role; {@code confirmed} runs on the Swing thread once OK closed it. */
    UiDialog lanceRole(LanceRoleForm form, LobbyPromptViews.Edt edt, Runnable confirmed) {
        UiDialog dialog = begin(Messages.getString("BotCommandPanel.Role.dialogTitleFor", form.lanceName()),
              "dialog.md", true, () -> { });
        LobbyBotOrderViews.lanceRole(kit, dialog, form, edt, confirmed, this::close);
        show(dialog);
        return dialog;
    }

    /** Shows the units' Alpha Strike stats as cards. */
    UiDialog alphaStrikeStats(AlphaStrikeStatsForm form, LobbyStatsView.Host host) {
        UiDialog dialog = begin(Messages.getString("AlphaStrikeStatsDialog.title"), "dialog.xl", true, () -> { });
        LobbyStatsView.alphaStrike(kit, dialog, form, host, this::close);
        show(dialog);
        return dialog;
    }

    /** Shows the forces' Strategic BattleForce formations as cards. */
    UiDialog sbfStats(SbfStatsForm form, LobbyStatsView.Host host) {
        UiDialog dialog = begin(Messages.getString("SBFStatsDialog.title"), "dialog.xl", true, () -> { });
        LobbyStatsView.sbf(kit, dialog, form, host, this::close);
        show(dialog);
        return dialog;
    }

    /**
     * Opens the auto-resolve briefing, on the odds when {@code odds} says so, else on the battle being resolved;
     * {@code closed} hears when it closes, as a Cancel that stops the odds.
     */
    LobbyAutoResolveView autoResolve(boolean odds, List<String> chatter, LobbyAutoResolveView.Host host,
          Runnable closed) {
        return autoResolve(odds, chatter, host, closed, false);
    }

    /**
     * Opens the briefing again over whatever shows, as a battle resolved after its briefing was closed ends: a dialog
     * opened in the meantime keeps its place and its work, and shows again once the result is closed.
     */
    LobbyAutoResolveView autoResolveOver(List<String> chatter, LobbyAutoResolveView.Host host) {
        return autoResolve(false, chatter, host, () -> { }, true);
    }

    private LobbyAutoResolveView autoResolve(boolean odds, List<String> chatter, LobbyAutoResolveView.Host host,
          Runnable closed, boolean over) {
        UiDialog dialog = begin(AutoResolveRun.title(), "dialog.sm", over, () -> {
            autoResolveView = null;
            closed.run();
        });
        autoResolveView = new LobbyAutoResolveView(kit, dialog, odds, chatter, host, this::close);
        show(dialog);
        return autoResolveView;
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
        return yesNo(title, paragraphs, check, null, true, answer);
    }

    /**
     * As {@link #yesNo(String, List, String, YesNoAnswer)}, its box explained by {@code checkTip} when not null and
     * only to be checked when {@code checkable}.
     */
    UiDialog yesNo(String title, List<String> paragraphs, String check, @Nullable String checkTip,
          boolean checkable, YesNoAnswer answer) {
        UiDialog dialog = begin(title, "dialog.md", true, () -> { });
        float gap = DesignTokens.shipped().space().get("md");
        for (String paragraph : paragraphs) {
            var label = kit.typeLabel(paragraph, "body", "text.primary");
            label.setWrap(true);
            dialog.body().add(label).growX().left().padBottom(gap).row();
        }
        UiChoice box = kit.check(check, false);
        box.setName("yes-no-check");
        box.setDisabled(!checkable);
        if (checkTip != null) {
            kit.tip(box, true).getActor().setText(checkTip);
        }
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

    /**
     * Asks, before the first bot joins, whether to read Princess's guide: {@code yes} reads it, and the box stops the
     * question coming back. Closing it any other way, such as with Esc, answers nothing.
     */
    UiDialog botReadme(String check, YesNoAnswer answer) {
        UiDialog dialog = begin(Messages.getString("Lobby.botReadme.overline"), "dialog.md", true, () -> { });
        float gap = DesignTokens.shipped().space().get("md");
        var headline = kit.typeLabel(Messages.getString("Lobby.botReadme.headline"), "headline", "text.primary");
        headline.setWrap(true);
        headline.setName("bot-readme-headline");
        Table pitch = new Table();
        pitch.add(kit.solid("accent.primary")).width(3).growY().padRight(gap);
        var text = kit.typeLabel(Messages.getString("Lobby.botReadme.text"), "body", "text.secondary");
        text.setWrap(true);
        pitch.add(text).growX().left();
        dialog.body().add(headline).growX().left().padBottom(gap).row();
        dialog.body().add(pitch).growX().left().padBottom(gap).row();
        UiChoice box = kit.check(check, false);
        box.setName("yes-no-check");
        dialog.body().add(box).left().padTop(gap);
        dialog.primary(Messages.getString("Lobby.botReadme.read"), () -> {
            close();
            answer.answered(true, box.isChecked());
        }).setName("yes-no-yes");
        dialog.secondary(Messages.getString("Lobby.botReadme.later"), () -> {
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

        /** Opens the help contents. */
        void helpContents();

        /** Opens the bug report. */
        void reportBug();

        /** Shows the About box. */
        void about();
    }

    /**
     * The lobby's system menu, as a game's pause menu: Resume, then saving and loading, Settings, Help and Leave lobby,
     * one column of buttons in groups ({@link GpuGameMenu}). Help opens its page in the menu's place; Esc goes back from
     * it, and on the first page Esc or Resume closes the menu. Every other button closes it before it acts.
     */
    UiDialog systemMenu(SystemActions actions) {
        UiDialog dialog = begin(Messages.getString("Lobby.menu.title"), "dialog.sm", false, () -> { });
        GpuGameMenu menu = new GpuGameMenu(kit, this::close);
        dialog.body().add(menu.actor()).growX().top();
        List<List<GpuGameMenu.Entry>> groups = new ArrayList<>();
        groups.add(List.of(GpuGameMenu.Entry.primary("Lobby.menu.resume", Messages.getString("Lobby.menu.resume"),
              () -> { })));
        List<GpuGameMenu.Entry> saves = new ArrayList<>();
        saves.add(menuEntry("Lobby.gameMenu.save", KeyCommandBind.LOCAL_SAVE, actions::save));
        saves.add(menuEntry("Lobby.gameMenu.quickSave", KeyCommandBind.QUICK_SAVE, actions::quickSave));
        if (actions.loads()) {
            saves.add(menuEntry("Lobby.gameMenu.load", KeyCommandBind.LOCAL_LOAD, actions::load));
            saves.add(menuEntry("Lobby.gameMenu.quickLoad", KeyCommandBind.QUICK_LOAD, actions::quickLoad));
        }
        groups.add(saves);
        List<List<GpuGameMenu.Entry>> help = List.of(List.of(
              menuEntry("Lobby.menu.helpContents", null, actions::helpContents),
              menuEntry("Lobby.menu.reportBug", null, actions::reportBug),
              menuEntry("Lobby.menu.about", null, actions::about)));
        groups.add(List.of(menuEntry("Lobby.menu.settings", KeyCommandBind.CLIENT_SETTINGS, actions::settings),
              GpuGameMenu.Entry.page("Lobby.menu.help", Messages.getString("Lobby.menu.help"), "", help)));
        groups.add(List.of(menuEntry("Lobby.menu.leave", null, actions::leave)));
        menu.open(groups);
        show(dialog);
        return dialog;
    }

    private static GpuGameMenu.Entry menuEntry(String key, KeyCommandBind binding, Runnable action) {
        return GpuGameMenu.Entry.button(key, Messages.getString(key),
              binding == null ? "" : KeyCommandBind.getDesc(binding), true, action);
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

    /** About MegaMek: the version and the suite's legal notice, in place of whatever shows. */
    UiDialog about() {
        UiDialog dialog = begin(GpuAbout.title(), "dialog.md", false, () -> { });
        GpuAbout.fill(kit, dialog, this::close);
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

    @Nullable LobbyPortraitView portraitView() {
        return portraitView;
    }

    @Nullable LobbyPlayerSettingsView settingsView() {
        return settingsView;
    }

    @Nullable LobbyBotSetupView botView() {
        return botView;
    }

    @Nullable LobbyEditBotsView editBotsView() {
        return editBotsView;
    }

    @Nullable LobbyNetworkInfoView networkInfoView() {
        return networkInfoView;
    }

    @Nullable LobbyNovaNetworkView novaNetworkView() {
        return novaNetworkView;
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

    @Nullable LobbyRequisitionPanel requisitionView() {
        return requisitionView;
    }

    @Nullable LobbyCrewToolsView crewView() {
        return crewView;
    }

    @Nullable LobbyReadoutView readoutView() {
        return readoutView;
    }

    @Nullable LobbyDocumentView documentView() {
        return documentView;
    }

    @Nullable LobbyAutoResolveView autoResolveView() {
        return autoResolveView;
    }

    /** The top dialog, or null. */
    @Nullable UiDialog dialog() {
        return open.isEmpty() ? null : open.peek().dialog();
    }
}
