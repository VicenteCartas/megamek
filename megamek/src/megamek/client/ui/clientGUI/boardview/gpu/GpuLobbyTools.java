/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import jakarta.annotation.Nullable;
import megamek.MegaMek;
import megamek.SuiteConstants;
import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.client.generator.RandomGenderGenerator;
import megamek.client.generator.RandomNameGenerator;
import megamek.client.generator.RandomUnitGenerator;
import megamek.client.lobby.AlphaStrikeStatsForm;
import megamek.client.lobby.AssetForm;
import megamek.client.lobby.AutoResolveRun;
import megamek.client.lobby.AutoResolveRun.Score;
import megamek.client.lobby.BalancedArmy;
import megamek.client.lobby.BattleValueArmy;
import megamek.client.lobby.FactionChoice;
import megamek.client.lobby.ForceGeneratorForm;
import megamek.client.lobby.ForceNameRequest;
import megamek.client.lobby.FormationForm;
import megamek.client.lobby.HelpDocument;
import megamek.client.lobby.LobbyAsks;
import megamek.client.lobby.LobbyBattlefield;
import megamek.client.lobby.LobbyCommands;
import megamek.client.lobby.LobbyError;
import megamek.client.lobby.LobbyFiles;
import megamek.client.lobby.LobbyUnitMenu;
import megamek.client.lobby.MegaMekLabPrint;
import megamek.client.lobby.RandomArmyForm;
import megamek.client.lobby.RandomNamesForm;
import megamek.client.lobby.RandomSkillsForm;
import megamek.client.lobby.RatBrowser;
import megamek.client.lobby.RatGeneratorForm;
import megamek.client.lobby.ReadyCheck;
import megamek.client.lobby.Reinforcements;
import megamek.client.lobby.RequisitionReadout;
import megamek.client.lobby.SbfStatsForm;
import megamek.client.lobby.TrainOrderForm;
import megamek.client.lobby.UnitDamageForm;
import megamek.client.lobby.VictoryConditionsForm;
import megamek.client.ratgenerator.ExistingLift;
import megamek.client.ratgenerator.ForceDescriptor;
import megamek.client.ratgenerator.FormationType;
import megamek.client.ratgenerator.GenerationContext;
import megamek.client.ratgenerator.RATGenerator;
import megamek.client.ratgenerator.UnitTable;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.UnitRecipients;
import megamek.client.ui.entityreadout.EntityReadout;
import megamek.client.ui.panels.phaseDisplay.lobby.LobbyUtility;
import megamek.client.ui.util.FontHandler;
import megamek.codeUtilities.MathUtility;
import megamek.common.Player;
import megamek.common.Team;
import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.alphaStrike.cardDrawer.ASCardPrinter;
import megamek.common.autoResolve.converter.MMSetupForces;
import megamek.common.autoResolve.converter.SetupForces;
import megamek.common.autoResolve.event.AutoResolveConcludedEvent;
import megamek.common.battlefieldSupport.BattlefieldSupportAsset;
import megamek.common.board.Board;
import megamek.common.board.postprocess.TWBoardTransformer;
import megamek.common.enums.Gender;
import megamek.common.force.Force;
import megamek.common.game.Game;
import megamek.common.icons.Camouflage;
import megamek.common.loaders.EntityLoadingException;
import megamek.common.loaders.MekFileParser;
import megamek.common.loaders.MekSummary;
import megamek.common.loaders.MekSummaryCache;
import megamek.common.options.GameOptions;
import megamek.common.options.OptionsConstants;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.PreferenceManager;
import megamek.common.strategicBattleSystems.SBFRecordSheetBook;
import megamek.common.units.Entity;
import megamek.common.units.UnitType;
import megamek.logging.MMLogger;
import megamek.server.victory.VictoryResult;

/**
 * The libGDX lobby's tools and prompts on the Swing thread (L17): the native {@link LobbyAsks} that
 * {@link LobbyCommands} asks through, the victory conditions, the random army, skills and names, Load List with the
 * system's file dialog, the unit readout and the bot's help. Each reads its form here, opens its view on the GPU thread
 * through {@link LobbyNativeDialogs}, and applies what the view hands back here again.
 * <p>
 * Swing thread unless a method says otherwise.
 */
final class GpuLobbyTools {
    private static final MMLogger LOGGER = MMLogger.create(GpuLobbyTools.class);
    /** The most units the readout shows at once, as the Swing readouts refuse more. */
    static final int MAX_READOUTS = 10;
    /** The width of a readout's Alpha Strike card, which fits a dialog.lg dossier. */
    private static final int AS_CARD_WIDTH = 640;
    /** How long the lobby must stay still before an open readout reads its units again. */
    private static final int READOUT_REFRESH_MS = 400;

    /** What the tools show over and ask of: the lobby window, or the battle window for its Game Master tools. */
    interface Surface {
        ClientGUI gui();

        /** Any thread: runs {@code work} on the GPU thread. */
        void post(Runnable work);

        /** Whether the window is closing, so that waits stop and answers are dropped. */
        boolean closing();

        /** Runs {@code work} on the Swing thread unless the window closes, then {@code then} on the GPU thread. */
        void edt(String what, Runnable work, Runnable then);

        LobbyFiles files();

        /** Has the unit sprites of {@code units} drawn for the views that show them. */
        void requisitionSprites(List<MekSummary> units);

        /** GPU thread: has {@code open} open a dialog over what shows. */
        void nativeDialog(Consumer<LobbyNativeDialogs> open);

        /** GPU thread: the native dialogs that show, if any. */
        @Nullable LobbyNativeDialogs nativeDialogs();

        /** GPU thread: the unit sprite drawn for {@code key}, or null while there is none. */
        @Nullable TextureRegionDrawable requisitionSprite(String key);

        void openSettings();

        /** Tells the player something in passing. */
        void chatNotice(String text);

        /** Reads the window again soon. */
        void refreshSoon();
    }

    private final Surface window;
    private final LobbyAsks asks = new NativeAsks();
    private static final int RAT_POLL_MS = 250;
    private @Nullable Timer ratWait;
    private @Nullable Timer ratGeneratorWait;
    private @Nullable Timer namesWait;
    private static final int PROBE_TRIES = 20;
    /** How many battles the developer switch's auto-resolve simulates for its odds. */
    private static final int PROBE_ODDS_RUNS = 20;
    private int probeTries;
    private volatile boolean printing;
    /** The readout of the lobby's units that shows, as the GPU thread made it, and its units' ids; null when none. */
    private volatile @Nullable LobbyReadoutView liveReadout;
    private volatile @Nullable List<Integer> liveIds;
    /** What the open readout of the lobby's units was last read from, so it is read again only once that changes. */
    private volatile @Nullable List<Shown> liveShown;
    private @Nullable Timer readoutRefresh;

    GpuLobbyTools(GpuLobbyWindow window) {
        this(new Surface() {
            @Override
            public ClientGUI gui() {
                return window.gui();
            }

            @Override
            public void post(Runnable work) {
                GpuHost.post(work);
            }

            @Override
            public boolean closing() {
                return window.closing();
            }

            @Override
            public void edt(String what, Runnable work, Runnable then) {
                window.edt(what, work, then);
            }

            @Override
            public LobbyFiles files() {
                return window.files();
            }

            @Override
            public void requisitionSprites(List<MekSummary> units) {
                window.requisitionSprites(units);
            }

            @Override
            public void nativeDialog(Consumer<LobbyNativeDialogs> open) {
                GpuLobbyScreen screen = window.screen();
                if (screen != null) {
                    screen.nativeDialog(open);
                }
            }

            @Override
            public @Nullable LobbyNativeDialogs nativeDialogs() {
                GpuLobbyScreen screen = window.screen();
                return (screen == null) ? null : screen.nativeDialogs();
            }

            @Override
            public @Nullable TextureRegionDrawable requisitionSprite(String key) {
                GpuLobbyScreen screen = window.screen();
                return (screen == null) ? null : screen.requisitionSprite(key);
            }

            @Override
            public void openSettings() {
                window.openSettings();
            }

            @Override
            public void chatNotice(String text) {
                window.chatNotice(text);
            }

            @Override
            public void refreshSoon() {
                window.refreshSoon();
            }
        });
    }

    GpuLobbyTools(Surface window) {
        this.window = window;
    }

    /** The prompts {@link LobbyCommands} asks through while the libGDX lobby shows. */
    LobbyAsks asks() {
        return asks;
    }

    private ClientGUI gui() {
        return window.gui();
    }

    private Client client() {
        return gui().getClient();
    }

    /** Any thread: has {@code open} open a dialog on the GPU thread, over what shows. */
    private void open(Consumer<LobbyNativeDialogs> open) {
        window.post(() -> window.nativeDialog(open));
    }

    /** Runs a view's answer on the Swing thread, then {@code then} back on the GPU thread, and reads the lobby. */
    private void edt(Runnable work, Runnable then) {
        window.edt("dialog", work, then);
    }

    /** Shows a message natively. */
    private void message(String title, String text) {
        open(dialogs -> dialogs.message(title, text));
    }

    private void error(LobbyError error, Object... args) {
        String title = error.title();
        message(title == null ? Messages.getString("Lobby.prompt.message") : title, error.message(args));
    }

    // ---- The prompts ----

    /** The native prompts. */
    private final class NativeAsks implements LobbyAsks {
        @Override
        public void error(LobbyError error, Object... args) {
            GpuLobbyTools.this.error(error, args);
        }

        @Override
        public void readyBlocked(ReadyCheck.Blocker blocker) {
            message(blocker.title(), blocker.message());
        }

        @Override
        public void confirm(String title, String question, Runnable yes) {
            open(dialogs -> dialogs.confirm(title, question, () -> edt(yes, () -> { })));
        }

        @Override
        public void chooseForceName(ForceNameRequest request, Consumer<String> chosen) {
            open(dialogs -> dialogs.forceName(request, GpuLobbyTools.this::edt, chosen));
        }

        @Override
        public void chooseSquadronDesignation(Consumer<String> chosen) {
            open(dialogs -> dialogs.squadron(GpuLobbyTools.this::edt, chosen));
        }

        @Override
        public void configureAsset(BattlefieldSupportAsset asset, boolean editable, Runnable applied) {
            AssetForm form = new AssetForm(asset, editable);
            open(dialogs -> dialogs.assetCrew(form, GpuLobbyTools.this::edt, applied));
        }

        @Override
        public void editDamage(Entity unit, UnitDamageForm.Options options, Runnable done) {
            UnitDamageForm form = UnitDamageForm.of(unit, options);
            open(dialogs -> dialogs.damage(form, GpuLobbyTools.this::edt, done));
        }

        @Override
        public void editAssetDamage(BattlefieldSupportAsset asset, Runnable applied) {
            AssetForm form = new AssetForm(asset, true);
            open(dialogs -> dialogs.assetDamage(form, GpuLobbyTools.this::edt, applied));
        }

        @Override
        public void orderTrain(Entity head, List<Entity> trailers, Consumer<List<Entity>> ordered) {
            TrainOrderForm form = new TrainOrderForm(head, trailers);
            open(dialogs -> dialogs.trainOrder(form, GpuLobbyTools.this::edt, ordered));
        }

        @Override
        public void showSbfStats(Collection<Force> forces, Game game) {
            SbfStatsForm form = new SbfStatsForm(forces, game);
            open(dialogs -> dialogs.sbfStats(form, statsHost));
        }

        @Override
        public void showAlphaStrikeStats(Collection<Entity> units) {
            AlphaStrikeStatsForm form = new AlphaStrikeStatsForm(units);
            open(dialogs -> dialogs.alphaStrikeStats(form, statsHost));
        }
    }

    // ---- The stats cards ----

    private final LobbyStatsView.Host statsHost = new StatsHost();
    private final List<String> sheetFonts = new ArrayList<>();

    /** The stats cards' saving and printing, each handed over to the Swing thread. */
    private final class StatsHost implements LobbyStatsView.Host {
        @Override
        public void save(AlphaStrikeStatsForm form) {
            SwingUtilities.invokeLater(() -> window.files().exportStats(form.fileName(), form::save));
        }

        @Override
        public void print(List<AlphaStrikeElement> elements) {
            List<AlphaStrikeElement> cards = List.copyOf(elements);
            SwingUtilities.invokeLater(() -> {
                PrinterJob job = PrinterJob.getPrinterJob();
                job.setPrintable(new ASCardPrinter(cards, gui().getFrame()));
                if (job.printDialog()) {
                    printInBackground(job, "Alpha Strike cards");
                }
            });
        }

        @Override
        public void save(SbfStatsForm form) {
            SwingUtilities.invokeLater(() -> window.files().exportStats(form.fileName(), form::save));
        }

        @Override
        public List<String> fonts() {
            synchronized (sheetFonts) {
                if (sheetFonts.isEmpty()) {
                    sheetFonts.add("");
                    sheetFonts.addAll(FontHandler.getAvailableNonSymbolFonts());
                }
                return List.copyOf(sheetFonts);
            }
        }

        @Override
        public String[] sheetFonts() {
            GUIPreferences preferences = GUIPreferences.getInstance();
            return new String[] { preferences.getSbfSheetHeaderFont(), preferences.getSbfSheetValueFont() };
        }

        @Override
        public void print(SbfStatsForm form, String headerFont, String valueFont) {
            SwingUtilities.invokeLater(() -> {
                GUIPreferences.getInstance().setSbfSheetHeaderFont(headerFont);
                GUIPreferences.getInstance().setSbfSheetValueFont(valueFont);
                PrinterJob job = PrinterJob.getPrinterJob();
                if (job.printDialog()) {
                    job.setPrintable(new SBFRecordSheetBook(form.formations(), font(headerFont), font(valueFont)));
                    printInBackground(job, "SBF record sheets");
                }
            });
        }

        private static @Nullable Font font(String name) {
            return (name == null || name.isBlank()) ? null : Font.decode(name);
        }

        private void printInBackground(PrinterJob job, String what) {
            Thread printer = new Thread(() -> {
                try {
                    job.print();
                } catch (PrinterException failure) {
                    LOGGER.error(failure, "The lobby could not print the {}", what);
                    message(Messages.getString("Lobby.prompt.message"), String.valueOf(failure.getMessage()));
                }
            }, "Lobby stats printer");
            printer.setDaemon(true);
            printer.start();
        }
    }

    // ---- The victory conditions ----

    /** Opens the victory conditions; OK sends what changed and keeps them as the last used options. */
    void victory() {
        VictoryConditionsForm form;
        try {
            form = new VictoryConditionsForm(client().getGame().getOptions());
        } catch (RuntimeException failure) {
            LOGGER.error(failure, "The lobby could not read the victory conditions");
            return;
        }
        LobbyVictoryView.Host host = new LobbyVictoryView.Host() {
            @Override
            public void send(VictoryConditionsForm sent) {
                if (sent.modified()) {
                    client().sendGameOptions(sent.password(), sent.changedOptions());
                }
                GameOptions.saveOptions(sent.allOptions());
                MegaMek.getMMPreferences().saveToFile(SuiteConstants.MM_PREFERENCES_FILE);
            }

            @Override
            public void edt(Runnable work, Runnable then) {
                GpuLobbyTools.this.edt(work, then);
            }
        };
        open(dialogs -> dialogs.victory(form, host));
    }

    // ---- The crew tools ----

    /** The local player's client, then the local bots'. */
    private List<Client> clients() {
        List<Client> clients = new ArrayList<>();
        clients.add(client());
        for (AbstractClient bot : gui().getLocalBots().values()) {
            if (bot instanceof Client botClient) {
                clients.add(botClient);
            }
        }
        return clients;
    }

    /** Opens the random skills; Save keeps them as the client's, Randomize also rolls its units' crews. */
    void skills() {
        RandomSkillsForm form = new RandomSkillsForm(clients());
        LobbyCrewToolsView.Host host = crewHost(() -> {
            form.save();
            client().sendServerChat(Player.PLAYER_NONE,
                  client().getLocalPlayer() + " changed settings for Skill Generation");
        }, () -> form.randomize(client().getGame().getEntitiesVector()));
        open(dialogs -> dialogs.skills(form, host));
    }

    /** Opens the random names; Save keeps the faction and the gender share, Randomize also names the crews. */
    void names() {
        if (RandomNameGenerator.getInstance().getFactions() == null) {
            // The names are read on a thread of their own at start
            if (namesWait == null) {
                namesWait = new Timer(RAT_POLL_MS, event -> {
                    if (window.closing() || (RandomNameGenerator.getInstance().getFactions() != null)) {
                        namesWait.stop();
                        namesWait = null;
                        if (!window.closing()) {
                            names();
                        }
                    }
                });
                namesWait.start();
            }
            return;
        }
        RandomNamesForm form = RandomNamesForm.of(clients());
        LobbyCrewToolsView.Host host = crewHost(form::save,
              () -> form.randomize(client().getGame().getEntitiesVector()));
        open(dialogs -> dialogs.names(form, host));
    }

    private LobbyCrewToolsView.Host crewHost(Runnable save, Runnable randomize) {
        return new LobbyCrewToolsView.Host() {
            @Override
            public void edt(Runnable work, Runnable then) {
                GpuLobbyTools.this.edt(work, then);
            }

            @Override
            public void save() {
                save.run();
            }

            @Override
            public void randomize() {
                randomize.run();
            }
        };
    }

    // ---- The random army ----

    /** Opens the random army once the tables are read, as they are on a thread of their own at start. */
    void randomArmy() {
        randomArmy(null);
    }

    /**
     * Opens the random army as above, its units going to {@code recipient} when they may: the Game Master's player
     * setup hands over the player just set up, who may not be on a team yet.
     */
    void randomArmy(@Nullable Player recipient) {
        RandomUnitGenerator generator = RandomUnitGenerator.getInstance();
        if (!generator.isInitialized()) {
            message(Messages.getString("Lobby.randomArmy.title"),
                  Messages.getString("RandomArmyDialog.ratStatusLoading"));
            if (ratWait == null) {
                // Polled: the generator's listener lock is held while it reads, and a listener added late never runs
                ratWait = new Timer(RAT_POLL_MS, event -> {
                    if (window.closing() || generator.isInitialized()) {
                        ratWait.stop();
                        ratWait = null;
                        if (!window.closing()) {
                            randomArmy(recipient);
                        }
                    }
                });
                ratWait.start();
            }
            return;
        }
        Player local = client().getLocalPlayer();
        Set<String> bots = gui().getLocalBots().keySet();
        List<String> players = UnitRecipients.availableTo(local, client().getGame().getPlayersList(), bots,
              !client().getGame().getPhase().isLounge(), recipient).stream().map(Player::getName).toList();
        List<String> tables = new ArrayList<>();
        generator.getRatList().forEachRemaining(tables::add);
        RandomArmyForm form = new RandomArmyForm(tables, generator.getChosenRAT(), players,
              (count, table) -> {
                  generator.setChosenRAT(table);
                  return generator.generate(count, table);
              });
        if (recipient != null) {
            form.player(players.indexOf(recipient.getName()));
        }
        RatBrowser browser = new RatBrowser(RatBrowser.of(generator.getRatTree()));
        int year = client().getGame().getOptions().intOption(OptionsConstants.ALLOWED_YEAR);
        int lastTab = MathUtility.parseInt(
              GUIPreferences.getInstance().getRandomArmySetting(GUIPreferences.RND_ARMY_LAST_TAB), -1);
        LobbyRandomArmyView.Generators generators = new LobbyRandomArmyView.Generators(
              new BattleValueArmy(MekSummaryCache.getInstance()::getAllMeks, year),
              new BalancedArmy(MekSummaryCache.getInstance()::getAllMeks));
        LobbyRandomArmyView.Host host = new LobbyRandomArmyView.Host() {
            @Override
            public void edt(Runnable work, Runnable then) {
                GpuLobbyTools.this.edt(work, then);
            }

            @Override
            public void add(RandomArmyForm army) {
                addArmy(army);
            }

            @Override
            public void generate(ForceGeneratorForm force, Consumer<Boolean> done) {
                generateForce(force, form.playerName(), done);
            }

            @Override
            public void addForce(ForceGeneratorForm force, RandomArmyForm army) {
                GpuLobbyTools.this.addForce(force, army);
            }

            @Override
            public void exportForce(ForceGeneratorForm force) {
                SwingUtilities.invokeLater(() -> GpuLobbyTools.this.exportForce(force));
            }

            @Override
            public void confirm(String title, String text, Runnable yes) {
                open(dialogs -> dialogs.confirm(title, text, yes));
            }

            @Override
            public void message(String title, String text) {
                GpuLobbyTools.this.message(title, text);
            }

            @Override
            public void readout(Entity unit) {
                SwingUtilities.invokeLater(() -> {
                    LobbyReadoutView.Dossier dossier = dossier(unit, client().getLocalPlayer().getCamouflage());
                    open(dialogs -> dialogs.readoutOver(List.of(dossier)));
                });
            }

            @Override
            public void sprites(List<MekSummary> units) {
                window.requisitionSprites(units);
            }

            @Override
            public void factions(Consumer<LobbyRandomArmyView.Factions> ready) {
                SwingUtilities.invokeLater(() -> whenRatGeneratorReady(() -> {
                    LobbyRandomArmyView.Factions[] built = new LobbyRandomArmyView.Factions[1];
                    edt(() -> built[0] = factionGenerators(year), () -> ready.accept(built[0]));
                }));
            }

            @Override
            public void remember(FactionChoice factions, int unitType) {
                rememberFaction(factions, unitType);
            }

            @Override
            public int lastTab() {
                return lastTab;
            }

            @Override
            public void lastTab(int tab) {
                SwingUtilities.invokeLater(() -> GUIPreferences.getInstance()
                      .setRandomArmySetting(GUIPreferences.RND_ARMY_LAST_TAB, String.valueOf(tab)));
            }

            @Override
            public void readout(MekSummary unit) {
                SwingUtilities.invokeLater(() -> armyReadout(unit));
            }
        };
        open(dialogs -> dialogs.randomArmy(form, browser, generators, window::requisitionSprite, host));
    }

    /** Runs {@code then} once the RAT Generator has read its data, started here if it is not yet. */
    private void whenRatGeneratorReady(Runnable then) {
        RATGenerator generator = RATGenerator.getInstance();
        if (generator.isInitialized()) {
            then.run();
            return;
        }
        if (ratGeneratorWait != null) {
            ratGeneratorWait.stop();
        }
        ratGeneratorWait = new Timer(RAT_POLL_MS, event -> {
            if (window.closing() || generator.isInitialized()) {
                ratGeneratorWait.stop();
                ratGeneratorWait = null;
                if (!window.closing()) {
                    then.run();
                }
            }
        });
        ratGeneratorWait.start();
    }

    /** The faction table and formation generators for {@code year}, on the choices the Swing generator remembers. */
    private static LobbyRandomArmyView.Factions factionGenerators(int year) {
        RATGenerator generator = RATGenerator.getInstance();
        generator.loadYear(year);
        GUIPreferences preferences = GUIPreferences.getInstance();
        FactionChoice choice = new FactionChoice(new ArrayList<>(generator.getFactionList()), year);
        String faction = preferences.getRandomArmySetting(GUIPreferences.RND_ARMY_LAST_FACTION);
        if (!faction.isBlank()) {
            choice.faction(faction);
        }
        String command = preferences.getRandomArmySetting(GUIPreferences.RND_ARMY_LAST_SUB_FACTION);
        if (!command.isBlank()) {
            choice.command(command);
        }
        String rating = preferences.getRandomArmySetting(GUIPreferences.RND_ARMY_LAST_RATING);
        if (!rating.isBlank()) {
            choice.rating(rating);
        }
        RatGeneratorForm table = new RatGeneratorForm(choice,
              (record, unitType, at, level, weights, network, motives, roles, strictness) -> {
                  RATGenerator.getInstance().loadYear(at);
                  return UnitTable.findTable(record, unitType, at, level, weights, network, motives, roles,
                        strictness);
              });
        String unitType = preferences.getRandomArmySetting(GUIPreferences.RND_ARMY_LAST_UNIT_TYPE);
        for (int type : RatGeneratorForm.UNIT_TYPES) {
            if (UnitType.getTypeName(type).equals(unitType)) {
                table.unitType(type);
            }
        }
        FormationForm formation = new FormationForm(choice, new ArrayList<>(FormationType.getAllFormations()),
              RATGenerator.getInstance()::loadYear);
        formation.unitType(table.unitType());
        ForceGeneratorForm force = new ForceGeneratorForm(year);
        if (!faction.isBlank()) {
            force.faction(faction);
        }
        return new LobbyRandomArmyView.Factions(table, formation, force);
    }

    /** Remembers the faction, command, rating and unit type for the next time, as the Swing generator does. */
    private static void rememberFaction(FactionChoice factions, int unitType) {
        GUIPreferences preferences = GUIPreferences.getInstance();
        preferences.setRandomArmySetting(GUIPreferences.RND_ARMY_LAST_FACTION,
              factions.faction() == null ? "" : factions.faction().getKey());
        preferences.setRandomArmySetting(GUIPreferences.RND_ARMY_LAST_SUB_FACTION,
              factions.command() == null ? "" : factions.command().getKey());
        preferences.setRandomArmySetting(GUIPreferences.RND_ARMY_LAST_RATING,
              factions.rating() == null ? "" : factions.rating());
        preferences.setRandomArmySetting(GUIPreferences.RND_ARMY_LAST_UNIT_TYPE, UnitType.getTypeName(unitType));
    }

    /**
     * Adds the dealt units for the chosen player, as the Swing random army's Okay does: each read from its file, its
     * crew given the chosen client's skills and a random name where the preferences say so.
     */
    private void addArmy(RandomArmyForm army) {
        Client client = client();
        Player local = client.getLocalPlayer();
        String chosenName = army.playerName();
        Player owner = armyOwner(chosenName);
        Client skillsClient = (chosenName != null && gui().getLocalBots().get(chosenName) instanceof Client bot)
              ? bot : client;
        ArrayList<Entity> entities = new ArrayList<>();
        for (MekSummary summary : army.army()) {
            try {
                Entity entity = new MekFileParser(summary.getSourceFile(), summary.getEntryName()).getEntity();
                skillsAndName(entity, skillsClient, chosenName);
                entities.add(entity);
            } catch (EntityLoadingException failure) {
                LOGGER.error(failure, "Unable to load the unit {}: {}", summary.getSourceFile(),
                      summary.getEntryName());
                message(Messages.getString("Lobby.randomArmy.title"), Messages.getString("ClientGUI.errorLoadingFile"));
                return;
            }
        }
        Reinforcements.prepare(entities, owner, client.getGame());
        client.sendAddEntity(entities);
        client.sendServerChat(Player.PLAYER_NONE, Messages.getString("RandomArmyDialog.loadedUnitsChat",
              local, owner.getName(), entities.size()));
        recordContext(army.context(), owner);
        army.clear();
    }

    /** The player a random army is for: the one chosen, if the local player may add units for them, else themselves. */
    private Player armyOwner(@Nullable String chosenName) {
        Player local = client().getLocalPlayer();
        Player owner = local;
        for (Player player : client().getGame().getPlayersList()) {
            if (player.getName().equals(chosenName)) {
                owner = player;
                break;
            }
        }
        return UnitRecipients.mayAddUnitsTo(local, owner, gui().getLocalBots().keySet()) ? owner : local;
    }

    /**
     * Rolls a force into the force generator's command, as the Swing generator does: described on the Swing thread
     * from what the chosen player already fields and the command's ships have spare, generated off it, folded in on
     * it; {@code done} hears on the GPU thread whether it found units.
     */
    private void generateForce(ForceGeneratorForm force, @Nullable String chosenName, Consumer<Boolean> done) {
        ForceDescriptor[] rolled = new ForceDescriptor[1];
        GameOptions[] options = new GameOptions[1];
        edt(() -> {
            Player owner = armyOwner(chosenName);
            List<Entity> owned = new ArrayList<>();
            for (Entity entity : client().getGame().getEntitiesVector()) {
                if (entity.getOwnerId() == owner.getId()) {
                    owned.add(entity);
                }
            }
            options[0] = client().getGame().getOptions();
            rolled[0] = force.build(ExistingLift.of(owned).plus(force.commandLift()));
        }, () -> {
            if (rolled[0] == null) {
                done.accept(false);
                return;
            }
            Thread worker = new Thread(() -> {
                try {
                    ForceGeneratorForm.process(rolled[0], options[0], (progress, message) -> { });
                } catch (RuntimeException failure) {
                    LOGGER.error(failure, "The force generator failed");
                    rolled[0] = null;
                }
                boolean[] found = new boolean[1];
                edt(() -> found[0] = rolled[0] != null && force.take(rolled[0]), () -> done.accept(found[0]));
            }, "Force generator");
            worker.setDaemon(true);
            worker.start();
        });
    }

    /**
     * Adds the force generator's command, as far as it is included, for the chosen player, as the Swing generator's
     * Okay does: its units wired and loaded into their carriers, then sent, then the command cleared.
     */
    private void addForce(ForceGeneratorForm force, RandomArmyForm army) {
        List<Entity> units = force.included();
        if (units.isEmpty()) {
            return;
        }
        Client client = client();
        Player owner = armyOwner(army.playerName());
        force.wire(units);
        Reinforcements.prepare(units, owner, client.getGame());
        force.loadCarriers(units);
        client.sendAddEntity(units);
        client.sendServerChat(Player.PLAYER_NONE, Messages.getString("RandomArmyDialog.loadedUnitsChat",
              client.getLocalPlayer(), owner.getName(), units.size()));
        recordContext(force.context(), owner);
        // Cleared last, the context being read from the command: left, its units could be sent a second time
        force.clear();
    }

    /** Writes the force generator's command to a unit list the player chooses. */
    private void exportForce(ForceGeneratorForm force) {
        String title = Messages.getString("ForceGeneratorDialog.exportMUL.title");
        if (force.included().isEmpty()) {
            message(title, Messages.getString("ForceGeneratorDialog.exportMUL.empty"));
            return;
        }
        window.files().exportForce(force.exportName(), file -> {
            try {
                force.export(file, client().getGame().getOptions());
            } catch (IOException | RuntimeException failure) {
                LOGGER.error(failure, "The force generator's command could not be exported");
                message(title, Messages.getString("ForceGeneratorDialog.exportMUL.error") + "\n"
                      + failure.getMessage());
            }
        });
    }

    /**
     * Records what a generator that asked for a faction dealt the units for, as the Swing random army does: against
     * the owner, as their team's faction, and in the chat.
     */
    private void recordContext(@Nullable GenerationContext context, Player owner) {
        if (context == null || context.source() == GenerationContext.Source.UNSPECIFIED) {
            return;
        }
        gui().setGenerationContext(owner.getId(), context);
        Team team = client().getGame().getTeamForPlayer(owner);
        if (team != null) {
            team.setFaction(context.faction());
        }
        // The year goes in as text: message formatting would group its digits
        String year = String.valueOf(context.year());
        String local = client().getLocalPlayer().getName();
        client().sendServerChat(Player.PLAYER_NONE, context.rating() == null
              ? Messages.getString("RandomArmyDialog.generatedFor", local, owner.getName(),
                    context.factionDisplayName(), year)
              : Messages.getString("RandomArmyDialog.generatedForRated", local, owner.getName(),
                    context.factionDisplayName(), year, context.rating()));
    }

    private static void skillsAndName(Entity entity, Client skillsClient, @Nullable String chosenName) {
        ClientPreferences preferences = PreferenceManager.getClientPreferences();
        Arrays.fill(entity.getCrew().getClanPilots(), entity.isClan());
        if (preferences.useAverageSkills()) {
            skillsClient.getSkillGenerator().setRandomSkills(entity);
        }
        if (preferences.generateNames()) {
            for (int slot = 0; slot < entity.getCrew().getSlotCount(); slot++) {
                Gender gender = RandomGenderGenerator.generate();
                entity.getCrew().setGender(gender, slot);
                entity.getCrew().setName(RandomNameGenerator.getInstance()
                      .generate(gender, entity.getCrew().isClanPilot(slot), chosenName), slot);
            }
        }
    }

    // ---- Print List ----

    /** Whether MegaMekLab is open to print a unit list; the forces menu offers no second print meanwhile. */
    boolean printing() {
        return printing;
    }

    /**
     * Swing thread: prints the local player's units through MegaMekLab, in force tree order. The chat says it has
     * started, as MegaMekLab takes a while to open its print dialog; when MegaMekLab cannot be found or run, a native
     * notice says why and offers the settings that hold its path.
     */
    void printList() {
        Player player = client().getLocalPlayer();
        if ((player == null) || printing || client().getGame().getPlayerEntities(player, false).isEmpty()) {
            return;
        }
        String title = Messages.getString("MegaMekLabPrint.title");
        MegaMekLabPrint print = MegaMekLabPrint.of(PreferenceManager.getClientPreferences().getMmlPath());
        MegaMekLabPrint.Problem problem = print.problem();
        if (problem != null) {
            String text = problem.message(print.executable());
            open(dialogs -> dialogs.error(title, text, Messages.getString("Ok.text"),
                  Messages.getString("Lobby.print.openSettings"), window::openSettings));
            return;
        }
        try {
            File unitFile = File.createTempFile("MegaMekPrint", ".mul");
            unitFile.deleteOnExit();
            int count = LobbyFiles.writeUnitList(unitFile, client().getGame(), player);
            ProcessBuilder command = print.command(unitFile);
            LOGGER.info("[LobbyPrint] Running {}", String.join(" ", command.command()));
            Process process = command.start();
            printing = true;
            process.onExit().whenComplete((exited, failure) -> printing = false);
            window.chatNotice(Messages.getString("Lobby.print.started", count));
        } catch (IOException failure) {
            LOGGER.error(failure, "The lobby could not print the unit list");
            message(title, Messages.getString("MegaMekLabPrint.failed", String.valueOf(failure.getMessage())));
        }
    }

    // ---- Load List ----

    /** Opens a unit list with the system's file dialog and adds its units for the local player. */
    void loadList() {
        Player player = client().getLocalPlayer();
        if (player == null) {
            return;
        }
        window.files().loadUnitList(file -> gui().loadListFile(player, file), window::refreshSoon);
    }

    // ---- The readout and the help ----

    /**
     * Runs the unit menu's readout commands natively: View, BV and Cost open the dossiers of their units on that page.
     * Returns whether {@code command} was one of them.
     */
    boolean unitCommand(String command) {
        String[] parts = command.split("\\|", -1);
        LobbyReadoutView.Page page = switch (parts[0]) {
            case LobbyUnitMenu.LMP_VIEW -> LobbyReadoutView.Page.SUMMARY;
            case LobbyUnitMenu.LMP_BV -> LobbyReadoutView.Page.BV;
            case LobbyUnitMenu.LMP_COST -> LobbyReadoutView.Page.COST;
            default -> null;
        };
        if (page == null || parts.length < 3) {
            return false;
        }
        readout(new ArrayList<>(LobbyUtility.getEntities(client().getGame(), parts[2])), page);
        return true;
    }

    /** Opens the dossiers of {@code units} on {@code page}, unless they are too many or hidden from the player. */
    void readout(List<Entity> units, LobbyReadoutView.Page page) {
        if (units.isEmpty()) {
            return;
        }
        if (units.size() > MAX_READOUTS) {
            error(LobbyError.TEN_UNITS);
            return;
        }
        if (!gui().lobbyCommands().canSeeAll(units)) {
            error(LobbyError.CANNOT_VIEW_HIDDEN);
            return;
        }
        List<LobbyReadoutView.Dossier> dossiers = new ArrayList<>();
        for (Entity unit : units) {
            dossiers.add(dossier(unit));
        }
        List<Integer> ids = units.stream().map(Entity::getId).toList();
        GameOptions options = client().getGame().getOptions();
        List<Shown> shown = units.stream().map(unit -> Shown.of(unit, options)).toList();
        open(dialogs -> {
            LobbyReadoutView view = dialogs.readout(dossiers).page(page);
            liveReadout = view;
            liveIds = ids;
            liveShown = shown;
        });
    }

    /**
     * Reads the open readout's units again once the lobby has settled, if a readout of the lobby's units shows, and
     * hands their fresh dossiers to it.
     */
    void refreshReadout() {
        if (liveIds == null) {
            return;
        }
        if (readoutRefresh == null) {
            readoutRefresh = new Timer(READOUT_REFRESH_MS, event -> GpuHost.post(this::checkReadout));
            readoutRefresh.setRepeats(false);
        }
        readoutRefresh.restart();
    }

    /** GPU thread: reads the readout's units again on the Swing thread if it still shows, or forgets it. */
    private void checkReadout() {
        LobbyReadoutView view = liveReadout;
        if (view == null || showingReadout() != view) {
            liveReadout = null;
            liveIds = null;
            liveShown = null;
            return;
        }
        SwingUtilities.invokeLater(() -> updateReadout(view));
    }

    /** GPU thread: the readout that shows, if any. */
    private @Nullable LobbyReadoutView showingReadout() {
        LobbyNativeDialogs dialogs = window.nativeDialogs();
        return (dialogs == null) ? null : dialogs.readoutView();
    }

    private void updateReadout(LobbyReadoutView view) {
        List<Integer> ids = liveIds;
        if (ids == null || window.closing()) {
            return;
        }
        Game game = client().getGame();
        List<Shown> now = new ArrayList<>();
        List<Entity> units = new ArrayList<>();
        for (int id : ids) {
            Entity unit = game.getEntity(id);
            if (unit != null && gui().lobbyCommands().canSeeAll(List.of(unit))) {
                now.add(Shown.of(unit, game.getOptions()));
                units.add(unit);
            }
        }
        // Nothing the readout shows has changed: keep its dossiers rather than read them all again
        if (units.isEmpty() || now.equals(liveShown)) {
            return;
        }
        liveShown = now;
        List<LobbyReadoutView.Dossier> dossiers = new ArrayList<>();
        for (Entity unit : units) {
            dossiers.add(dossier(unit));
        }
        GpuHost.post(() -> {
            if (showingReadout() == view) {
                view.update(dossiers);
            }
        });
    }

    /** Swing thread: reads a random army unit's file and opens its dossier over the random army. */
    private void armyReadout(MekSummary unit) {
        Entity entity;
        try {
            entity = new MekFileParser(unit.getSourceFile(), unit.getEntryName()).getEntity();
        } catch (EntityLoadingException failure) {
            LOGGER.error(failure, "Unable to load the unit {}: {}", unit.getSourceFile(), unit.getEntryName());
            message(Messages.getString("Lobby.randomArmy.title"), Messages.getString("ClientGUI.errorLoadingFile"));
            return;
        }
        LobbyReadoutView.Dossier dossier = dossier(entity, client().getLocalPlayer().getCamouflage());
        open(dialogs -> dialogs.readoutOver(List.of(dossier)));
    }

    private LobbyReadoutView.Dossier dossier(Entity unit) {
        return dossier(unit, unit.getCamouflageOrElseOwners());
    }

    private LobbyReadoutView.Dossier dossier(Entity unit, Camouflage camouflage) {
        BufferedImage sprite = GpuLobbyWindow.sprite(unit, camouflage);
        Player owner = unit.getOwner();
        String line = owner == null
              ? Messages.getString("Lobby.readout.lineUnowned", unit.calculateBattleValue(), format(unit.getWeight()))
              : Messages.getString("Lobby.readout.line", unit.calculateBattleValue(), format(unit.getWeight()),
                    owner.getName());
        ReadoutCalculationReport.AlphaStrike alphaStrike;
        try {
            alphaStrike = ReadoutCalculationReport.alphaStrike(unit, AS_CARD_WIDTH);
        } catch (RuntimeException failure) {
            LOGGER.warn(failure, "The Alpha Strike card could not be made");
            alphaStrike = new ReadoutCalculationReport.AlphaStrike(null, List.of());
        }
        List<List<EntityReadout.Row>> pages = List.of(RequisitionReadout.summary(unit), RequisitionReadout.tro(unit),
              rows(() -> ReadoutCalculationReport.battleValue(unit)), rows(() -> ReadoutCalculationReport.cost(unit)),
              alphaStrike.rows());
        return new LobbyReadoutView.Dossier(unit.getId(), unit.getShortName(), line, sprite, pages,
              alphaStrike.card(), ReadoutAvailability.later(unit));
    }

    /**
     * What a readout of {@code unit} shows it from: the server sends a changed unit as a new one, and its owner and
     * camouflage show on its line and sprite. Its Battle Value also moves with things outside the unit, such as its
     * C3 partners, and the game options shape its Battle Value, cost and Alpha Strike pages; the client replaces the
     * options whole when they change.
     */
    private record Shown(Entity unit, @Nullable String owner, Camouflage camouflage, int battleValue,
          GameOptions options) {
        static Shown of(Entity unit, GameOptions options) {
            Player owner = unit.getOwner();
            return new Shown(unit, (owner == null) ? null : owner.getName(), unit.getCamouflageOrElseOwners(),
                  unit.calculateBattleValue(), options);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Shown shown && shown.unit == unit && Objects.equals(shown.owner, owner)
                  && Objects.equals(shown.camouflage, camouflage) && shown.battleValue == battleValue
                  && shown.options == options;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(unit);
        }
    }

    private static List<EntityReadout.Row> rows(java.util.function.Supplier<List<EntityReadout.Row>> report) {
        try {
            return report.get();
        } catch (RuntimeException failure) {
            LOGGER.warn(failure, "A calculation report could not be made");
            return List.of();
        }
    }

    private static String format(double tons) {
        return (tons == Math.rint(tons)) ? String.valueOf((long) tons) : String.valueOf(tons);
    }

    /** Any thread: shows Princess's documentation over the bot setup. */
    void princessHelp() {
        helpDocument(Messages.getString("Lobby.help.princess"),
              new File(Messages.getString("BotConfigDialog.princessHelpPath")));
    }

    /** Any thread: shows the map setup's help over the lobby. */
    void mapSetupHelp() {
        helpDocument(Messages.getString("ChatLounge.map.title.mapAssemblyHelp"),
              new File(Messages.getString("Lobby.help.mapSetupPath")));
    }

    /** Any thread: shows an HTML guide a chapter at a time in the native help dialog; an unreadable one shows empty. */
    void helpDocument(String title, File file) {
        showDocument(title, file, true, HelpDocument::guide);
    }

    /** Any thread: reads {@code file} into a document as {@code read} reads it, and shows it over what shows. */
    private void showDocument(String title, File file, boolean grid,
          java.util.function.Function<String, HelpDocument> read) {
        HelpDocument shown = readDocument(file, read);
        open(dialogs -> dialogs.document(title, shown, grid));
    }

    /** Any thread: {@code file} read into a document as {@code read} reads it; an unreadable one reads empty. */
    static HelpDocument readDocument(File file, java.util.function.Function<String, HelpDocument> read) {
        try {
            return read.apply(Files.readString(file.toPath()));
        } catch (Exception failure) {
            LOGGER.error(failure, "Could not read the document {}", file);
            return new HelpDocument(List.of());
        }
    }

    // ---- Auto-resolve ----

    /**
     * Resolves the battle automatically, natively: the forces, the board and the conditions are read here, the odds
     * worked out first when {@code settings} asks for them, and the battle resolved on threads of its own, while the
     * briefing shows how it goes and then how it ended, with its report.
     */
    void autoResolve(LobbyBattlefield.AutoResolve settings) {
        Game game = client().getGame();
        Player local = client().getLocalPlayer();
        if (local == null) {
            return;
        }
        AutoResolvedBattle run;
        try {
            Board board = TWBoardTransformer.instantiateBoard(client().getMapSettings(), game.getPlanetaryConditions(),
                  game.getOptions());
            Map<Integer, String> names = new HashMap<>();
            for (Player player : game.getPlayersList()) {
                names.put(player.getId(), player.getName());
            }
            run = new AutoResolvedBattle(settings, new MMSetupForces(game), board,
                  new PlanetaryConditions(game.getPlanetaryConditions()), local.getTeam(), local.getId(), names);
        } catch (RuntimeException failure) {
            LOGGER.error(failure, "The lobby could not set up the auto-resolved battle");
            message(AutoResolveRun.failureTitle(), AutoResolveRun.failureText());
            return;
        }
        open(run::start);
    }

    /** One auto-resolved battle, from the odds to the report; GPU thread unless a method says otherwise. */
    private final class AutoResolvedBattle implements LobbyAutoResolveView.Host {
        private final LobbyBattlefield.AutoResolve settings;
        private final SetupForces setup;
        private final Board board;
        private final PlanetaryConditions conditions;
        private final int team;
        private final int playerId;
        private final Map<Integer, String> names;
        private final List<String> chatter = AutoResolveRun.flavor(new Random());
        private volatile boolean cancelled;
        private @Nullable LobbyAutoResolveView view;
        private @Nullable File report;

        AutoResolvedBattle(LobbyBattlefield.AutoResolve settings, SetupForces setup, Board board,
              PlanetaryConditions conditions, int team, int playerId, Map<Integer, String> names) {
            this.settings = settings;
            this.setup = setup;
            this.board = board;
            this.conditions = conditions;
            this.team = team;
            this.playerId = playerId;
            this.names = names;
        }

        void start(LobbyNativeDialogs dialogs) {
            view = dialogs.autoResolve(settings.chances(), chatter, this, this::closed);
            if (settings.chances()) {
                odds();
            } else {
                resolve();
            }
        }

        /** The briefing closed: while the odds run, that stops them; a battle being resolved still ends. */
        private void closed() {
            if (view != null && view.phase() == LobbyAutoResolveView.Phase.ODDS) {
                cancelled = true;
            }
        }

        private void odds() {
            int runs = settings.runs();
            worker("Auto-resolve odds", () -> {
                Score score = AutoResolveRun.odds(runs, settings.threads(), team, playerId, setup, board, conditions,
                      ended -> GpuHost.post(() -> {
                          if (view != null) {
                              view.odds(ended, runs);
                          }
                      }), () -> cancelled);
                GpuHost.post(() -> {
                    if (!cancelled && view != null && view.isOpen()) {
                        view.choice(score);
                    }
                });
            });
        }

        @Override
        public void proceed() {
            resolve();
        }

        private void resolve() {
            if (view != null) {
                view.resolving();
            }
            worker("Auto-resolve", () -> {
                AutoResolveConcludedEvent event = AutoResolveRun.resolve(setup, board, conditions);
                open(dialogs -> ended(dialogs, event));
            });
        }

        /**
         * Shows how the battle ended, opening the briefing again over whatever shows when it was closed while the
         * battle was resolved.
         */
        private void ended(LobbyNativeDialogs dialogs, @Nullable AutoResolveConcludedEvent event) {
            if (view == null || !view.isOpen()) {
                view = dialogs.autoResolveOver(chatter, this);
            }
            if (event == null) {
                view.failed(AutoResolveRun.failureTitle(), AutoResolveRun.failureText());
                return;
            }
            VictoryResult result = event.getVictoryResult();
            report = event.getLogFile();
            view.result(AutoResolveRun.outcome(result, team, playerId),
                  AutoResolveRun.winner(result, id -> names.getOrDefault(id, String.valueOf(id))),
                  report != null && report.isFile());
        }

        @Override
        public void report() {
            File shown = report;
            if (shown != null) {
                worker("Auto-resolve report", () -> showDocument(AutoResolveRun.reportTitle(), shown, false,
                      html -> HelpDocument.battleReport(html, Messages.getString("Lobby.autoResolve.report.summary"),
                            round -> Messages.getString("Lobby.autoResolve.report.round", round))));
            }
        }

        private static void worker(String name, Runnable work) {
            Thread thread = new Thread(work, name);
            thread.setDaemon(true);
            thread.start();
        }
    }

    // ---- The developer switch ----

    /** Opens {@code tool} as its control would, with the player's units where it needs some; see {@link LobbyToolsProbe}. */
    void probe(String tool) {
        Player local = client().getLocalPlayer();
        List<Entity> units = client().getGame().getEntitiesVector().stream()
              .filter(unit -> local != null && unit.getOwnerId() == local.getId()).toList();
        if ((tool.startsWith("readout") || tool.equals("prompt=train") || tool.startsWith("resolve")) && units.isEmpty()
              && probeTries++ < PROBE_TRIES) {
            // The probe's units arrive a moment after the lobby opens
            Timer later = new Timer(RAT_POLL_MS * 2, event -> {
                if (!window.closing()) {
                    probe(tool);
                }
            });
            later.setRepeats(false);
            later.start();
            return;
        }
        switch (tool) {
            case "victory" -> victory();
            case "randomarmy" -> randomArmy();
            case "skills" -> skills();
            case "names" -> names();
            case "princesshelp" -> princessHelp();
            case "maphelp" -> mapSetupHelp();
            case "resolve" -> autoResolve(new LobbyBattlefield.AutoResolve(true, true, PROBE_ODDS_RUNS, 2, 2));
            case "resolve=quick" -> autoResolve(new LobbyBattlefield.AutoResolve(true, false, PROBE_ODDS_RUNS, 2, 2));
            case "readout" -> readout(units.subList(0, Math.min(units.size(), 3)), LobbyReadoutView.Page.SUMMARY);
            case "readout=tro" -> readout(units.subList(0, Math.min(units.size(), 3)), LobbyReadoutView.Page.TRO);
            case "readout=bv" -> readout(units.subList(0, Math.min(units.size(), 3)), LobbyReadoutView.Page.BV);
            case "readout=cost" -> readout(units.subList(0, Math.min(units.size(), 3)), LobbyReadoutView.Page.COST);
            case "prompt=forcename" -> asks.chooseForceName(ForceNameRequest.of(client().getGame(), local, "Alpha"),
                  name -> LOGGER.info("The lobby probe's force name: {}", name));
            case "prompt=squadron" -> asks.chooseSquadronDesignation(
                  name -> LOGGER.info("The lobby probe's squadron: {}", name));
            case "prompt=asset" -> asks.configureAsset(probeAsset(), true,
                  () -> LOGGER.info("The lobby probe's asset crew applied"));
            case "prompt=assetdamage" -> asks.editAssetDamage(probeAsset(),
                  () -> LOGGER.info("The lobby probe's asset damage applied"));
            case "prompt=train" -> {
                if (units.size() > 1) {
                    asks.orderTrain(units.get(0), units.subList(1, units.size()), ordered -> LOGGER.info(
                          "The lobby probe's train: {}", ordered.stream().map(Entity::getShortName).toList()));
                } else {
                    LOGGER.warn("The lobby probe's train needs two of the player's units");
                }
            }
            case "prompt=confirm" -> asks.confirm(Messages.getString("Lobby.prompt.message"),
                  Messages.getString("ChatLounge.AlertBot.message"), () -> LOGGER.info("The lobby probe confirmed"));
            case "prompt=error" -> asks.error(LobbyError.TEN_UNITS);
            default -> LOGGER.error("The lobby probe has no tool called {}", tool);
        }
    }

    private BattlefieldSupportAsset probeAsset() {
        return client().getGame().getEntitiesVector().stream().filter(BattlefieldSupportAsset.class::isInstance)
              .map(BattlefieldSupportAsset.class::cast).findFirst().orElseGet(BattlefieldSupportAsset::new);
    }
}
