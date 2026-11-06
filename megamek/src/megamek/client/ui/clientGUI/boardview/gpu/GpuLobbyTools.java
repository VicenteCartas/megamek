/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import jakarta.annotation.Nullable;
import megamek.MegaMek;
import megamek.SuiteConstants;
import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.client.generator.RandomGenderGenerator;
import megamek.client.generator.RandomNameGenerator;
import megamek.client.generator.RandomUnitGenerator;
import megamek.client.lobby.AssetForm;
import megamek.client.lobby.ForceNameRequest;
import megamek.client.lobby.LobbyAsks;
import megamek.client.lobby.LobbyCommands;
import megamek.client.lobby.LobbyError;
import megamek.client.lobby.LobbyFiles;
import megamek.client.lobby.LobbyUnitMenu;
import megamek.client.lobby.MegaMekLabPrint;
import megamek.client.lobby.RandomArmyForm;
import megamek.client.lobby.RandomNamesForm;
import megamek.client.lobby.RandomSkillsForm;
import megamek.client.lobby.RatBrowser;
import megamek.client.lobby.ReadyCheck;
import megamek.client.lobby.RequisitionReadout;
import megamek.client.lobby.TrainOrderForm;
import megamek.client.lobby.UnitDamageForm;
import megamek.client.lobby.VictoryConditionsForm;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.UnitRecipients;
import megamek.client.ui.entityreadout.EntityReadout;
import megamek.client.ui.panels.phaseDisplay.lobby.LobbyUtility;
import megamek.client.ui.panels.phaseDisplay.lobby.SwingLobbyPrompts;
import megamek.client.ui.panels.phaseDisplay.lobby.SwingLobbyServices;
import megamek.common.Player;
import megamek.common.battlefieldSupport.BattlefieldSupportAsset;
import megamek.common.enums.Gender;
import megamek.common.force.Force;
import megamek.common.game.Game;
import megamek.common.loaders.EntityLoadingException;
import megamek.common.loaders.MekFileParser;
import megamek.common.loaders.MekSummary;
import megamek.common.options.GameOptions;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.PreferenceManager;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;

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

    private final GpuLobbyWindow window;
    private final LobbyAsks asks = new NativeAsks();
    private static final int RAT_POLL_MS = 250;
    private static final Pattern LAYOUT_TABLE = Pattern.compile("<table[^>]*class=\"config-table\"[^>]*>(.*?)</table>",
          Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private @Nullable Timer ratWait;
    private @Nullable Timer namesWait;
    private static final int PROBE_TRIES = 20;
    private int probeTries;
    private volatile boolean printing;

    GpuLobbyTools(GpuLobbyWindow window) {
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

    /** Any thread: has {@code open} open a dialog on the GPU thread, over the lobby. */
    private void open(Consumer<LobbyNativeDialogs> open) {
        GpuHost.post(() -> {
            GpuLobbyScreen screen = window.screen();
            if (screen != null) {
                screen.nativeDialog(open);
            }
        });
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

    /** The native prompts; Damage and the stats stay Swing's. */
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
            new SwingLobbyPrompts(gui()).showSbfStats(forces, game);
        }

        @Override
        public void showAlphaStrikeStats(Collection<Entity> units) {
            new SwingLobbyPrompts(gui()).showAlphaStrikeStats(units);
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
                            randomArmy();
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
              !client().getGame().getPhase().isLounge(), null).stream().map(Player::getName).toList();
        List<String> tables = new ArrayList<>();
        generator.getRatList().forEachRemaining(tables::add);
        RandomArmyForm form = new RandomArmyForm(tables, generator.getChosenRAT(), players,
              (count, table) -> {
                  generator.setChosenRAT(table);
                  return generator.generate(count, table);
              });
        RatBrowser browser = new RatBrowser(RatBrowser.of(generator.getRatTree()));
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
            public void advanced() {
                SwingUtilities.invokeLater(() -> {
                    if (!window.closing()) {
                        SwingLobbyServices.of(gui()).forcesCommand(SwingLobbyServices.ForcesCommand.RANDOM_ARMY,
                              null);
                    }
                });
            }

            @Override
            public void sprites(List<MekSummary> units) {
                window.requisitionSprites(units);
            }
        };
        open(dialogs -> dialogs.randomArmy(form, browser, key -> {
            GpuLobbyScreen screen = window.screen();
            return screen == null ? null : screen.requisitionSprite(key);
        }, host));
    }

    /**
     * Adds the dealt units for the chosen player, as the Swing random army's Okay does: each read from its file, its
     * crew given the chosen client's skills and a random name where the preferences say so.
     */
    private void addArmy(RandomArmyForm army) {
        Client client = client();
        Player local = client.getLocalPlayer();
        String chosenName = army.playerName();
        Player owner = client.getGame().getPlayersList().stream()
              .filter(player -> player.getName().equals(chosenName)).findFirst().orElse(local);
        if (!UnitRecipients.mayAddUnitsTo(local, owner, gui().getLocalBots().keySet())) {
            owner = local;
        }
        Client skillsClient = (chosenName != null && gui().getLocalBots().get(chosenName) instanceof Client bot)
              ? bot : client;
        ArrayList<Entity> entities = new ArrayList<>();
        for (MekSummary summary : army.army()) {
            try {
                Entity entity = new MekFileParser(summary.getSourceFile(), summary.getEntryName()).getEntity();
                skillsAndName(entity, skillsClient, chosenName);
                entity.setOwner(owner);
                if (!client.getGame().getPhase().isLounge()) {
                    entity.setDeployRound(client.getGame().getRoundCount() + 1);
                    entity.setGame(client.getGame());
                    entity.setDone(true);
                    entity.setUnloaded(true);
                }
                entities.add(entity);
            } catch (EntityLoadingException failure) {
                LOGGER.error(failure, "Unable to load the unit {}: {}", summary.getSourceFile(),
                      summary.getEntryName());
                message(Messages.getString("Lobby.randomArmy.title"), Messages.getString("ClientGUI.errorLoadingFile"));
                return;
            }
        }
        client.sendAddEntity(entities);
        client.sendServerChat(Player.PLAYER_NONE, Messages.getString("RandomArmyDialog.loadedUnitsChat",
              local, owner.getName(), entities.size()));
        army.clear();
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
        open(dialogs -> dialogs.readout(dossiers).page(page));
    }

    private LobbyReadoutView.Dossier dossier(Entity unit) {
        BufferedImage sprite = GpuLobbyWindow.sprite(unit, unit.getCamouflageOrElseOwners());
        Player owner = unit.getOwner();
        String line = Messages.getString("Lobby.readout.line", unit.calculateBattleValue(),
              format(unit.getWeight()), owner == null ? "" : owner.getName());
        List<List<EntityReadout.Row>> pages = List.of(RequisitionReadout.summary(unit), RequisitionReadout.tro(unit),
              rows(() -> ReadoutCalculationReport.battleValue(unit)), rows(() -> ReadoutCalculationReport.cost(unit)));
        return new LobbyReadoutView.Dossier(unit.getShortName(), line, sprite, pages);
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

    /** Any thread: shows an HTML help document in the native help dialog; an unreadable one shows empty. */
    private void helpDocument(String title, File file) {
        List<EntityReadout.Row> rows;
        try {
            rows = RequisitionReadout.fromHtml(unwrapLayoutTables(Files.readString(file.toPath())));
        } catch (Exception failure) {
            LOGGER.error(failure, "The lobby could not read the help {}", file);
            rows = List.of();
        }
        List<EntityReadout.Row> shown = rows;
        open(dialogs -> dialogs.help(title, shown));
    }

    /**
     * The help's tables that only lay a picture out beside its text, without the picture and the table, as the rows
     * would put the text in one cell.
     */
    static String unwrapLayoutTables(String html) {
        Matcher table = LAYOUT_TABLE.matcher(html);
        StringBuilder unwrapped = new StringBuilder();
        while (table.find()) {
            String inner = table.group(1).replaceAll("(?is)<img[^>]*>", "")
                  .replaceAll("(?is)</?(tbody|tr|td)[^>]*>", "");
            table.appendReplacement(unwrapped, Matcher.quoteReplacement(inner));
        }
        table.appendTail(unwrapped);
        return unwrapped.toString();
    }

    // ---- The developer switch ----

    /** Opens {@code tool} as its control would, with the player's units where it needs some; see {@link LobbyToolsProbe}. */
    void probe(String tool) {
        Player local = client().getLocalPlayer();
        List<Entity> units = client().getGame().getEntitiesVector().stream()
              .filter(unit -> local != null && unit.getOwnerId() == local.getId()).toList();
        if ((tool.startsWith("readout") || tool.equals("prompt=train")) && units.isEmpty()
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
