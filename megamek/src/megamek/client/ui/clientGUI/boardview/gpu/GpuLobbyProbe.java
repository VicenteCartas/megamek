/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Vector;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;
import com.badlogic.gdx.utils.Array;
import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.client.bot.AIType;
import megamek.client.bot.BotClient;
import megamek.client.bot.BotFactory;
import megamek.client.bot.ui.swing.BotGUI;
import megamek.client.lobby.LobbyCommands;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.dialogs.buttonDialogs.AbstractNagDialog;
import megamek.client.ui.panels.phaseDisplay.lobby.SwingLobbyServices;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.force.Force;
import megamek.common.loaders.MULParser;
import megamek.common.options.BasicOption;
import megamek.common.options.GameOptions;
import megamek.common.options.IBasicOption;
import megamek.common.options.OptionsConstants;
import megamek.common.preference.PreferenceManager;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;
import org.lwjgl.glfw.GLFW;

/**
 * A developer switch for looking at the native lobby without clicking through to it, read from the environment.
 * {@code MM_START_PAGE=lobby} makes the start screen host a game at once with the Host page's saved name, on the saved
 * port or the next free one ({@link StartScreenProbe}); once the lobby shows, this probe fills it:
 * <ul>
 *     <li>{@code MM_LOBBY_BOTS}: how many Princess bots to add, on team 2 with the player on team 1 (0 to 8).</li>
 *     <li>{@code MM_LOBBY_UNITS}: unit list files ({@code .mul}), separated by the path separator ({@code ;} on
 *     Windows), loaded for the player and for each bot.</li>
 *     <li>{@code lobby+ready}: readies the player alone, without their bots, a second before the shot (or once the
 *     bots have joined without one), so the shot shows the ready state while the table waits for the bots.</li>
 *     <li>{@code lobby+start}: presses Ready as the lobby does, for the player and their bots, once the bots have
 *     joined (and after the shot), to watch the game start; it answers No to any question Ready asks, such as
 *     whether to record the battle's GIF.</li>
 *     <li>{@code lobby+blocked}: turns on the victory condition of killing the commander, so forces without one
 *     raise a red warning that blocks Ready.</li>
 *     <li>{@code lobby+overlap}: turns on double blind with exclusive deployment, so every player left on any edge
 *     overlaps and Ready is blocked.</li>
 *     <li>{@code lobby+blind}: turns on the real blind drop, so the other sides' Battle Value hides.</li>
 *     <li>{@code lobby+gm}: allows the Game Master role and, {@value GpuLobbyScreen#PROBE_CLICK_AT} s in, clicks the
 *     top bar's Game Master seat and its Claim through the stage; alone, the seat is the player's at once.
 *     {@code +step} then steps down. {@code +second} seats a second human player without a window on team 2, so the
 *     claim becomes a vote they answer yes to {@value #SECOND_ACTS_AT} s in; with {@code +asked} the player claims
 *     nothing and the second human asks instead, and the probe answers the card that opens with Allow, or with Deny
 *     under {@code +deny}.</li>
 *     <li>{@code MM_START_SHOT}: a PNG file the lobby saves from its frame buffer, {@code MM_START_SHOT_AT} seconds
 *     after it shows (by default {@value #SHOT_AT}, once the bots and units have arrived).</li>
 *     <li>{@code MM_START_QUIT}: {@code true} to quit MegaMek once the shot is saved.</li>
 *     <li>{@code MM_LOBBY_SIZE}: the window's size as the lobby shows, such as {@code 1600x900}; {@code lobby+narrow}
 *     without it makes the window {@value #NARROW_WIDTH} by {@value #NARROW_HEIGHT}, which folds the battlefield into
 *     its rail. Either keeps the window's saved size as it was.</li>
 *     <li>{@code lobby+rail}: opens the folded battlefield from its rail.</li>
 *     <li>{@code lobby+menu}: opens the first bot's player menu (the first player's without bots) before the shot.</li>
 *     <li>{@code lobby+chat}: has the player and each bot say a line in the chat as they join.</li>
 *     <li>{@code lobby+fold}: clicks the chat's fold button through the stage {@value GpuLobbyScreen#PROBE_CLICK_AT} s
 *     in, keeping the client's fold preference as it was, then has the player and each bot say a line
 *     {@value #FOLD_CHAT_AT} s in, so the folded header counts the bots' lines on its badge.</li>
 *     <li>{@code lobby+formations}: puts the player's first units in a formation and a sub-formation, and shows the
 *     forces as formations.</li>
 *     <li>{@code lobby+compact}: shows the forces as compact rows.</li>
 *     <li>{@code lobby+unitmenu}: opens the menu of the player's first unit before the shot.</li>
 *     <li>{@code lobby+zones}: deploys the player at the west edge and the bots at the east, so the map shows
 *     zones.</li>
 *     <li>{@code lobby+autoresolve}: turns on the client setting that shows auto-resolve, and opens its settings in
 *     the battlefield.</li>
 *     <li>{@code lobby+requisition}: opens the Requisition view; with {@code +atlas} it searches for "Atlas" and with
 *     {@code +search=text} for the text, an underscore for each space; with
 *     {@code +filters} it opens the Filters drawer and with {@code +pick} it picks the first result, showing its
 *     dossier.</li>
 *     <li>{@code lobby+clickcompact}: clicks the forces' compact rows toggle through the stage, as a pointer would,
 *     {@value GpuLobbyScreen#PROBE_CLICK_AT} s in; with {@code +formations} it does so in the formations view.</li>
 *     <li>{@code lobby+clickids}: clicks the forces' unit ids toggle through the stage at the same time, which flips
 *     the client's lobby setting for unit ids; a second run shows whether it held.</li>
 *     <li>{@code lobby+saveprefs}: saves the client settings when {@code MM_START_QUIT} quits, as quitting MegaMek
 *     does; without it the probe leaves them as they were.</li>
 *     <li>{@code lobby+requisition+clickfilters}: clicks the Requisition's Filters button through the stage at the same
 *     time, opening the drawer; {@code +closefilters} clicks it again a second later, shutting it.</li>
 *     <li>{@code lobby+requisition+advanced}: through the stage, opens the Filters drawer, clicks Advanced search,
 *     types 5 in the Total Warfare walk minimum and logs the matching count before and after; {@code +as} then
 *     switches to the Alpha Strike tab and clicks the size 4 chip, and {@code +equip} adds the first weapon found for
 *     "PPC" to the equipment expression instead, each logging the count again.</li>
 *     <li>{@code lobby+requisition+hoverrow}: half a second before the shot, puts the pointer on the second result row
 *     at its right end, beside the scroll bar, and logs where the rows and their values end against the bar;
 *     {@code +hoverbar} puts it on the bar instead.</li>
 *     <li>{@code lobby+requisition+setskills}: clicks the adding strip's Set skills, then the gunnery's +, through the
 *     stage.</li>
 *     <li>{@code lobby+requisition+pick+tab=name}: once the picked unit's dossier shows, clicks its tab through the
 *     stage {@value GpuLobbyScreen#PROBE_CLICK_AT} s in: {@code overview}, {@code readout}, {@code ascard},
 *     {@code factions} or {@code damage}; {@code tro} clicks Readout, then its TRO switch, and {@code summary} its
 *     Summary switch. With {@code +scroll=percent} it scrolls the tab that far down half a second before the shot.</li>
 *     <li>{@code lobby+c3}: opens the C3 network manager {@value GpuLobbyScreen#PROBE_CLICK_AT} s in, as the forces'
 *     menu would; {@code +c3plan} clicks Plan Force through the stage 2.5 s before the shot, or {@code +c3config=N}
 *     the Configuration N template, and {@code +c3build} then clicks Build; the board is logged before the shot (see
 *     {@link LobbyC3Probe}).</li>
 *     <li>{@code lobby+mapsetup}: {@value GpuLobbyScreen#PROBE_CLICK_AT} s in, opens the map setup in the forces'
 *     place, as the battlefield column would, and {@code lobby+randommap} the random map settings; with
 *     {@code +mapclick=name,name} it then clicks their controls, or those of the dialog over them, by name through the
 *     stage, one every {@value LobbyMapSetupProbe#CLICK_GAP} s, logging the setup after each and the server's map
 *     settings once done: such as {@code map-slot-1}, {@code map-board-2} and {@code map-place},
 *     {@code map-width-up} (and {@code nag-dont-show}, {@code nag-ok} of the odd-sized board warning),
 *     {@code map-medium-space}, {@code map-rotate}, {@code map-level-offset-up}, {@code map-generate}; or
 *     {@code random-advanced}, {@code random-tab-water}, {@code random-lakes-high}, {@code random-ok} (see
 *     {@link LobbyMapSetupProbe}).</li> *     <li>{@code lobby+configure}: {@value GpuLobbyScreen#PROBE_CLICK_AT} s in, opens the unit bay for the player's
 *     first unit, as the unit menu's Configure does; with {@code +multi} for all their units at once. With
 *     {@code +bayclick=name,name} it then clicks the bay's controls by name through the stage, one every
 *     {@value LobbyUnitBayProbe#CLICK_GAP} s, logging after each the unit, the first crew member's gunnery, the zone,
 *     the round and whether OK may be pressed: such as {@code bay-gunnery-0-up}, {@code bay-zone-5},
 *     {@code bay-tab-deployment}, {@code bay-round}'s {@code bay-round-up}, {@code bay-next} and {@code bay-ok}.
 *     {@code +bayrules} first turns on the rules that add the bay's Abilities, Quirks and Partial Repairs tabs (pilot
 *     advantages, edge, quirks and partial repairs), so {@code bay-tab-abilities} and the others can be clicked.</li>
 *     <li>{@code lobby+dialog=name}: opens a Swing dialog as the lobby's control would once the shot's time comes, and
 *     saves a photograph of the window and its dialogs, taken from the screen only when the dialog is in front,
 *     instead of the frame buffer, which has no Swing dialogs; the
 *     log tells where the dialog stands against the window, then, as the probe closes it, whether the lobby has its
 *     input and the focus back. The names: {@code gameoptions}, {@code conditions}, {@code victory},
 *     {@code randommap}, {@code mapsetup}, {@code loadmapsetup}, {@code camo}, {@code playersettings},
 *     {@code botconfig}, {@code addbot}, {@code customize}, {@code c3} (now the native C3 network manager, on the
 *     stage), {@code randomarmy}, {@code skills}, {@code names}, {@code loadlist}, {@code alert} and {@code settings} (Client Settings, through a click on the top
 *     bar's gear). The libGDX dialogs ({@link #NATIVE_DIALOGS}: {@code camo},
 *     {@code playersettings}, {@code botconfig}, {@code addbot}, {@code conditions} and {@code gameoptions}) open on
 *     the stage instead, where the probe clicks through them: a colour camo for {@code camo}, the SE start and an
 *     offset of 2 for {@code playersettings}, the COWARDLY preset, the pirate switch and the bravery slider's top for
 *     the first local bot's settings ({@code botconfig}) or a new bot on a new team ({@code addbot}), and with
 *     {@code +targets} a force and a hex on the targets tab; two steps down the light and one down the weather for
 *     {@code conditions}; a search for double blind and its check box for {@code gameoptions}; it saves the frame
 *     buffer, presses OK, and logs what the next snapshot shows, the battlefield's conditions and notable rules
 *     among it. Since L17 {@code victory}, {@code randomarmy}, {@code skills} and {@code names} open their libGDX
 *     views and {@code loadlist} the system's file dialog; the pages below probe those views.</li>
 *     <li>{@code lobby+victory}, {@code +randomarmy}, {@code +skills}, {@code +names}, {@code +readout},
 *     {@code +princesshelp} and {@code +prompt=name}, with {@code +click=name,name}: the L17 tools and prompts, see
 *     {@link LobbyToolsProbe}.</li>
 * </ul>
 */
record GpuLobbyProbe(Set<String> options, List<File> units, int bots, File shot, double shotAt, boolean quit,
      int width, int height) {
    private static final MMLogger LOGGER = MMLogger.create(GpuLobbyProbe.class);

    /** The shot's default time: the bots have joined and every unit has arrived. */
    static final double SHOT_AT = 6;
    static final int MAX_BOTS = 8;
    private static final int POLL_MS = 250;
    /** How long after the shot, or after the bots have joined when there is none, Ready is pressed. */
    private static final double READY_AFTER = 1;
    private static final long ANSWER_NO_NANOS = 30_000_000_000L;
    private static final int PLAYER_TEAM = 1;
    private static final int BOT_TEAM = 2;
    static final int NARROW_WIDTH = 1100;
    static final int NARROW_HEIGHT = 800;
    /** The formation steps of {@code lobby+formations}: a lance, then a sub-formation inside it. */
    private static final int FORMED = 2;
    private static final double FORM_WAIT = 20;
    /** When {@code lobby+fold}'s player and bots chat, in seconds: after the screen has folded the chat. */
    static final double FOLD_CHAT_AT = 3;
    /** {@code lobby+gm+second}'s second human player, and when they act, in seconds. */
    private static final String SECOND_NAME = "Second";
    static final double SECOND_ACTS_AT = 4;
    private static final String DIALOG_OPTION = "dialog=";
    private static final String TAB_OPTION = "tab=";
    private static final String SCROLL_OPTION = "scroll=";
    private static final String SEARCH_OPTION = "search=";
    private static final String BAY_CLICK_OPTION = "bayclick=";
    private static final String MAP_CLICK_OPTION = "mapclick=";
    /** How long a probed dialog has to show, and then to close, before the probe looks. */
    private static final int DIALOG_WAIT_MS = 2500;
    private static final int FRONT_WAIT_MS = 700;

    private static boolean taken;

    static GpuLobbyProbe from(Map<String, String> environment) {
        String page = value(environment, "MM_START_PAGE");
        if (page == null) {
            return null;
        }
        String[] parts = page.toLowerCase(Locale.ROOT).split("\\+");
        if (!parts[0].trim().equals("lobby")) {
            return null;
        }
        Set<String> options = new TreeSet<>();
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isBlank()) {
                options.add(parts[i].trim());
            }
        }
        List<File> units = new ArrayList<>();
        String unitsValue = value(environment, "MM_LOBBY_UNITS");
        if (unitsValue != null) {
            for (String path : unitsValue.split(File.pathSeparator)) {
                if (!path.isBlank()) {
                    units.add(new File(path.trim()));
                }
            }
        }
        int bots = 0;
        String botsValue = value(environment, "MM_LOBBY_BOTS");
        if (botsValue != null) {
            try {
                bots = Math.clamp(Integer.parseInt(botsValue), 0, MAX_BOTS);
            } catch (NumberFormatException ignored) {
                bots = 0;
            }
        }
        String shotValue = value(environment, "MM_START_SHOT");
        double shotAt = SHOT_AT;
        String atValue = value(environment, "MM_START_SHOT_AT");
        if (atValue != null) {
            try {
                shotAt = Math.max(0, Double.parseDouble(atValue));
            } catch (NumberFormatException ignored) {
                shotAt = SHOT_AT;
            }
        }
        boolean quit = shotValue != null && Boolean.parseBoolean(value(environment, "MM_START_QUIT"));
        int[] size = size(value(environment, "MM_LOBBY_SIZE"));
        if (size == null && options.contains("narrow")) {
            size = new int[] { NARROW_WIDTH, NARROW_HEIGHT };
        }
        return new GpuLobbyProbe(Set.copyOf(options), List.copyOf(units), bots,
              shotValue == null ? null : new File(shotValue), shotAt, quit, size == null ? 0 : size[0],
              size == null ? 0 : size[1]);
    }

    /** A {@code WIDTHxHEIGHT} size, or null when {@code value} is missing or not one. */
    private static int[] size(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.toLowerCase(Locale.ROOT).split("x");
        if (parts.length != 2) {
            return null;
        }
        try {
            int width = Integer.parseInt(parts[0].trim());
            int height = Integer.parseInt(parts[1].trim());
            return width > 0 && height > 0 ? new int[] { width, height } : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Whether the probe sizes the window. */
    boolean sized() {
        return width > 0 && height > 0;
    }

    private static String value(Map<String, String> environment, String name) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** EDT: applies the environment's probe to the first lobby shown in this run, if it asks for one. */
    static GpuLobbyProbe applyOnce(ClientGUI gui) {
        if (taken) {
            return null;
        }
        taken = true;
        GpuLobbyProbe probe = from(System.getenv());
        if (probe != null) {
            probe.apply(gui);
        }
        return probe;
    }

    private void apply(ClientGUI gui) {
        long start = System.nanoTime();
        if (options.contains("autoresolve")) {
            PreferenceManager.getClientPreferences().setShowAutoResolvePanel(true);
        }
        Client client = gui.getClient();
        List<BotClient> waiting = new ArrayList<>();
        boolean[] joined = { false };
        boolean starts = options.contains("start");
        double readyAt = shot == null ? READY_AFTER : starts ? shotAt + READY_AFTER : Math.max(0, shotAt - READY_AFTER);
        boolean[] readied = { !starts && !options.contains("ready") };
        int[] formed = { options.contains("formations") ? 0 : FORMED };
        boolean[] foldChatted = { !options.contains("fold") };
        // lobby+gm+second: a second human at the table, who asks for the seat (+asked) or votes yes on the player's ask
        Client[] second = { null };
        boolean[] secondDone = { !options.contains("second") };
        Timer timer = new Timer(POLL_MS, null);
        timer.addActionListener(event -> {
            // The bots join after the player, who gets the first player id as when they are added by hand
            Player local = client.getLocalPlayer();
            if (!joined[0] && local != null) {
                joined[0] = true;
                local.setTeam(PLAYER_TEAM);
                if (options.contains("zones")) {
                    local.setStartingPos(Board.START_W);
                }
                client.sendPlayerInfo();
                sendOptions(client);
                loadUnits(client, local);
                chat(client);
                addBots(gui, waiting);
                if (!secondDone[0]) {
                    second[0] = connectSecond(client);
                    secondDone[0] = second[0] == null;
                }
            }
            waiting.removeIf(bot -> {
                Player player = bot.getLocalPlayer();
                if (player == null) {
                    return false;
                }
                player.setTeam(BOT_TEAM);
                if (options.contains("zones")) {
                    player.setStartingPos(Board.START_E);
                }
                bot.sendPlayerInfo();
                loadUnits(bot, player);
                chat(bot);
                return true;
            });
            double elapsed = (System.nanoTime() - start) / 1e9;
            if (second[0] != null && !secondDone[0]) {
                secondDone[0] = secondStep(second[0], elapsed);
            }
            if (joined[0] && formed[0] < FORMED) {
                formed[0] = elapsed > FORM_WAIT ? FORMED : form(client, local, formed[0]);
            }
            if (joined[0] && !readied[0] && waiting.isEmpty() && elapsed >= readyAt && botsArmed(gui)) {
                readied[0] = true;
                if (starts) {
                    LOGGER.info("The lobby probe presses Ready");
                    answerNo();
                    SwingLobbyServices.of(gui).ready();
                } else {
                    LOGGER.info("The lobby probe readies the player");
                    client.sendDone(true);
                }
            }
            if (joined[0] && waiting.isEmpty() && !foldChatted[0] && elapsed >= FOLD_CHAT_AT) {
                foldChatted[0] = true;
                String line = Messages.getString("Lobby.probeFoldChat");
                client.sendChat(line);
                gui.getLocalBots().values().forEach(bot -> bot.sendChat(line));
                LOGGER.info("The lobby probe's player and {} bots chat while the chat is folded",
                      gui.getLocalBots().size());
            }
            if (joined[0] && waiting.isEmpty() && readied[0] && formed[0] >= FORMED && foldChatted[0]
                  && secondDone[0]) {
                timer.stop();
            }
        });
        timer.start();
    }

    /** Connects {@code lobby+gm+second}'s second human player, a client without a window; null when it fails. */
    private static Client connectSecond(Client client) {
        Client second = new Client(SECOND_NAME, client.getHost(), client.getPort());
        if (second.connect()) {
            LOGGER.info("The lobby probe's second human joins");
            return second;
        }
        LOGGER.error("The lobby probe's second human failed to connect");
        return null;
    }

    /**
     * One step of the second human: on a team once they have joined, then {@value #SECOND_ACTS_AT} s in they ask for
     * the seat ({@code +asked}) or vote yes on the player's ask; true once done.
     */
    private boolean secondStep(Client second, double elapsed) {
        Player player = second.getLocalPlayer();
        if (player == null || elapsed < SECOND_ACTS_AT) {
            if (player != null && player.getTeam() != BOT_TEAM) {
                player.setTeam(BOT_TEAM);
                second.sendPlayerInfo();
            }
            return false;
        }
        boolean asks = options.contains("asked");
        LOGGER.info("The lobby probe's second human {}", asks ? "asks for the Game Master seat" : "votes yes");
        second.sendChat(asks ? LobbyCommands.GAME_MASTER_COMMAND : LobbyCommands.GAME_MASTER_ALLOW_COMMAND);
        return true;
    }

    /** For a while, answers No to the questions the lobby asks and closes the nags, so the game starts unattended. */
    private static void answerNo() {
        long until = System.nanoTime() + ANSWER_NO_NANOS;
        Timer timer = new Timer(POLL_MS, null);
        timer.addActionListener(event -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog dialog && dialog.isShowing()
                      && dialog.getContentPane().getComponentCount() > 0
                      && dialog.getContentPane().getComponent(0) instanceof JOptionPane pane) {
                    LOGGER.info("The lobby probe answers No to {}", dialog.getTitle());
                    pane.setValue(JOptionPane.NO_OPTION);
                } else if (window instanceof AbstractNagDialog nag && nag.isShowing()) {
                    LOGGER.info("The lobby probe closes {}", nag.getTitle());
                    nag.setVisible(false);
                }
            }
            if (System.nanoTime() > until) {
                timer.stop();
            }
        });
        timer.start();
    }

    private void addBots(ClientGUI gui, List<BotClient> waiting) {
        Client client = gui.getClient();
        for (int i = 1; i <= bots; i++) {
            String name = Messages.getString("Lobby.probeBot", i);
            BotClient bot = BotFactory.createBot(AIType.PRINCESS, name, client.getHost(), client.getPort());
            bot.setClientGUI(gui);
            bot.getGame().addGameListener(new BotGUI(gui.getFrame(), bot));
            if (bot.connect()) {
                gui.getLocalBots().put(name, bot);
                waiting.add(bot);
            } else {
                LOGGER.error("The lobby probe's bot {} failed to connect", name);
                bot.die();
            }
        }
    }

    /**
     * Sends the game options {@code lobby+blocked}, {@code lobby+overlap}, {@code lobby+blind}, {@code lobby+gm} and
     * {@code lobby+bayrules} ask for.
     */
    private void sendOptions(Client client) {
        Vector<IBasicOption> changed = new Vector<>();
        if (options.contains("gm")) {
            changed.add(new BasicOption(OptionsConstants.GAME_MASTER_ALLOW, true));
        }
        if (options.contains("blocked")) {
            changed.add(new BasicOption(OptionsConstants.VICTORY_COMMANDER_KILLED, true));
        }
        if (options.contains("overlap")) {
            changed.add(new BasicOption(OptionsConstants.ADVANCED_DOUBLE_BLIND, true));
            changed.add(new BasicOption(OptionsConstants.BASE_EXCLUSIVE_DB_DEPLOYMENT, true));
        }
        if (options.contains("blind")) {
            changed.add(new BasicOption(OptionsConstants.BASE_REAL_BLIND_DROP, true));
        }
        if (options.contains("bayrules")) {
            changed.add(new BasicOption(OptionsConstants.RPG_PILOT_ADVANTAGES, true));
            changed.add(new BasicOption(OptionsConstants.EDGE, true));
            changed.add(new BasicOption(OptionsConstants.ADVANCED_STRATOPS_QUIRKS, true));
            changed.add(new BasicOption(OptionsConstants.ADVANCED_STRATOPS_PARTIAL_REPAIRS, true));
        }
        if (!changed.isEmpty()) {
            client.sendGameOptions("", changed);
        }
    }

    /** With {@code lobby+chat}, has {@code client} say a line, so the chat shows players' lines. */
    private void chat(AbstractClient client) {
        if (options.contains("chat")) {
            client.sendChat(Messages.getString("Lobby.probeChat"));
        }
    }

    /**
     * With {@code lobby+formations}, puts the player's first unit in a formation and, once it exists, up to two more in
     * a sub-formation of it, so the nesting shows near the top even with three units; returns the step reached.
     */
    private static int form(Client client, Player local, int step) {
        List<Entity> mine = client.getGame().getEntitiesVector().stream()
              .filter(entity -> entity.getOwnerId() == local.getId()).toList();
        if (step == 0) {
            if (mine.isEmpty()) {
                return 0;
            }
            client.sendAddForce(Force.createToplevelForce(Messages.getString("Lobby.probeFormation"), local),
                  new ArrayList<>(mine.subList(0, 1)));
            return 1;
        }
        Force top = client.getGame().getForces().getTopLevelForces().stream()
              .filter(force -> force.getOwnerId() == local.getId()).findFirst().orElse(null);
        if (top == null) {
            return 1;
        }
        List<Entity> free = mine.stream().filter(entity -> entity.getForceId() == Force.NO_FORCE).toList();
        if (!free.isEmpty()) {
            client.sendAddForce(Force.createSubforce(Messages.getString("Lobby.probeSubFormation"), top),
                  new ArrayList<>(free.subList(0, Math.min(2, free.size()))));
        }
        return FORMED;
    }

    /** Loads every unit list for {@code player}, sent by its own client. */
    /**
     * Whether every bot's units have reached the game, so readying waits for them: a unit that arrives later un-readies
     * the player.
     */
    private boolean botsArmed(ClientGUI gui) {
        if (units.isEmpty()) {
            return true;
        }
        for (AbstractClient bot : gui.getLocalBots().values()) {
            Player player = bot.getLocalPlayer();
            if (player == null || gui.getClient().getGame().getPlayerEntities(player, false).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void loadUnits(AbstractClient client, Player player) {
        for (File file : units) {
            try {
                Vector<Entity> entities = new MULParser(file, (GameOptions) client.getGame().getOptions())
                      .getEntities();
                for (Entity entity : entities) {
                    entity.setOwner(player);
                }
                if (!entities.isEmpty() && client instanceof Client totalWarfare) {
                    totalWarfare.sendAddEntity(entities);
                }
            } catch (Exception failure) {
                LOGGER.error(failure, "The lobby probe could not load {}", file);
            }
        }
    }

    /** The dossier tab {@code lobby+requisition+pick+tab=name} clicks, or null. */
    String tab() {
        for (String option : options) {
            if (option.startsWith(TAB_OPTION)) {
                return option.substring(TAB_OPTION.length());
            }
        }
        return null;
    }

    /** What {@code lobby+requisition+search=text} searches for, an underscore for each space, or null. */
    String search() {
        for (String option : options) {
            if (option.startsWith(SEARCH_OPTION)) {
                return option.substring(SEARCH_OPTION.length()).replace('_', ' ');
            }
        }
        return null;
    }

    /** How far down {@code lobby+requisition+pick+scroll=percent} scrolls the dossier tab, 0 to 1, or -1. */
    float scroll() {
        for (String option : options) {
            if (option.startsWith(SCROLL_OPTION)) {
                try {
                    return Math.max(0, Math.min(1, Integer.parseInt(option.substring(SCROLL_OPTION.length())) / 100f));
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /** The dialogs {@code lobby+dialog=name} opens on the stage, the lobby's libGDX dialogs. */
    static final Set<String> NATIVE_DIALOGS = Set.of("camo", "playersettings", "botconfig", "addbot", "conditions",
          "gameoptions");

    /** EDT: opens the libGDX dialog {@link #dialog()} names, as the lobby's control would. */
    void openNative(GpuLobbyWindow window, ClientGUI gui) {
        for (Dialog dialog : showingDialogs()) {
            dialog.setVisible(false);
        }
        openDialog(window, gui);
    }

    /** The unit bay's controls {@code lobby+configure+bayclick=name,name} clicks, in order; empty without it. */
    List<String> bayClicks() {
        return clicks(BAY_CLICK_OPTION);
    }

    /** The map setup's controls {@code lobby+mapsetup+mapclick=name,name} clicks, in order; empty without it. */
    List<String> mapClicks() {
        return clicks(MAP_CLICK_OPTION);
    }

    private List<String> clicks(String prefix) {
        List<String> names = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(prefix)) {
                for (String name : option.substring(prefix.length()).split(",")) {
                    if (!name.isBlank()) {
                        names.add(name.trim());
                    }
                }
            }
        }
        return names;
    }

    /** The dialog {@code lobby+dialog=name} opens over the lobby, or null. */
    String dialog() {
        for (String option : options) {
            if (option.startsWith(DIALOG_OPTION)) {
                return option.substring(DIALOG_OPTION.length());
            }
        }
        return null;
    }

    /**
     * EDT: opens {@link #dialog()} as the lobby's own control would, then, once it shows in front, photographs the
     * window and its dialogs from the screen (the frame buffer has no Swing dialogs) and logs where the dialog stands against the window; then
     * closes it and logs whether the lobby takes its input and the focus back, and quits when asked to.
     */
    void dialogShot(GpuLobbyWindow window, ClientGUI gui) {
        // The first run's notices would stand over the dialog
        for (Dialog dialog : showingDialogs()) {
            dialog.setVisible(false);
        }
        later(() -> {
            openDialog(window, gui);
            later(() -> front(window), DIALOG_WAIT_MS);
        }, DIALOG_WAIT_MS);
    }

    private void openDialog(GpuLobbyWindow window, ClientGUI gui) {
        String name = dialog();
        Client client = gui.getClient();
        Player local = client.getLocalPlayer();
        int localId = local == null ? Player.PLAYER_NONE : local.getId();
        switch (name) {
            case "gameoptions" -> window.battlefieldCommand(LobbyBattlefieldController.Command.GAME_OPTIONS);
            case "conditions" -> window.battlefieldCommand(LobbyBattlefieldController.Command.CONDITIONS);
            case "victory" -> window.battlefieldCommand(LobbyBattlefieldController.Command.VICTORY);
            case "randommap" -> window.battlefieldCommand(LobbyBattlefieldController.Command.RANDOM_MAP);
            case "mapsetup" -> window.battlefieldCommand(LobbyBattlefieldController.Command.MAP_SETUP);
            case "loadmapsetup" -> window.battlefieldCommand(LobbyBattlefieldController.Command.LOAD_MAP_SETUP);
            case "camo" -> window.camouflage(localId);
            case "playersettings" -> window.playerSettings(localId);
            case "botconfig" -> window.botSettings(gui.getLocalBots().values().stream()
                  .map(AbstractClient::getLocalPlayer).filter(java.util.Objects::nonNull).mapToInt(Player::getId)
                  .findFirst().orElse(localId));
            case "addbot" -> window.addBot(window.snapshot().freeTeam());
            case "customize" -> client.getGame().getEntitiesVector().stream()
                  .filter(entity -> entity.getOwnerId() == localId).findFirst()
                  .ifPresent(entity -> window.configureUnit(entity.getId()));
            case "c3" -> window.forcesCommand(LobbyForcesPanel.Command.C3);
            case "randomarmy" -> window.forcesCommand(LobbyForcesPanel.Command.RANDOM_ARMY);
            case "skills" -> window.forcesCommand(LobbyForcesPanel.Command.SKILLS);
            case "names" -> window.forcesCommand(LobbyForcesPanel.Command.NAMES);
            case "loadlist" -> window.forcesCommand(LobbyForcesPanel.Command.LOAD_LIST);
            case "alert" -> SwingUtilities.invokeLater(() -> gui.doAlertDialog(
                  Messages.getString("ChatLounge.AlertBot.title"), Messages.getString("ChatLounge.AlertBot.message")));
            case "settings" -> window.probeClick("lobby-settings");
            default -> LOGGER.error("The lobby probe has no dialog called {}", name);
        }
    }

    /**
     * Brings the newest dialog to the front as a click would: a run started in the background may not take the
     * foreground otherwise, and the photograph would show whatever stands in front of the window.
     */
    private void front(GpuLobbyWindow window) {
        List<Dialog> open = showingDialogs();
        if (!open.isEmpty()) {
            try {
                Robot robot = new Robot();
                robot.keyPress(KeyEvent.VK_ALT);
                robot.keyRelease(KeyEvent.VK_ALT);
            } catch (AWTException | RuntimeException failure) {
                LOGGER.warn("The lobby probe could not press a key: {}", failure.toString());
            }
            open.get(open.size() - 1).toFront();
        }
        later(() -> photograph(window), FRONT_WAIT_MS);
    }

    private void photograph(GpuLobbyWindow window) {
        Rectangle area = GpuHost.swingClientBounds();
        List<Dialog> open = showingDialogs();
        Rectangle covered = area == null ? null : new Rectangle(area);
        for (Dialog dialog : open) {
            Rectangle bounds = dialog.getBounds();
            String offset = area == null ? "unknown" : (bounds.x + bounds.width / 2 - area.x - area.width / 2) + ","
                  + (bounds.y + bounds.height / 2 - area.y - area.height / 2);
            LOGGER.info("Lobby probe dialog '{}' {} modal {}, at {}, window {}, centre offset {}, on top {}, active {}",
                  dialog.getTitle(), dialog.getClass().getSimpleName(), dialog.isModal(), bounds, area, offset,
                  dialog.isAlwaysOnTop(), dialog.isActive());
            if (covered != null) {
                covered.add(bounds);
            }
        }
        LOGGER.info("Lobby probe dialog {}: {} showing, lobby input blocked {}", dialog(), open.size(),
              window.modalOpen());
        boolean inFront = !open.isEmpty() && open.get(open.size() - 1).isActive();
        if (shot != null && covered != null && inFront) {
            // Only the window and its dialogs, never the rest of the screen
            Rectangle region = covered.intersection(screenOf(area));
            try {
                MultiResolutionImage capture = new Robot().createMultiResolutionScreenCapture(region);
                List<Image> variants = capture.getResolutionVariants();
                Image image = variants.get(variants.size() - 1);
                BufferedImage saved = new BufferedImage(image.getWidth(null), image.getHeight(null),
                      BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = saved.createGraphics();
                graphics.drawImage(image, 0, 0, null);
                // Blacks out the corners the window and its dialogs leave, where other programs show
                Area hidden = new Area(region);
                hidden.subtract(new Area(area));
                for (Dialog dialog : open) {
                    hidden.subtract(new Area(dialog.getBounds()));
                }
                graphics.scale((double) saved.getWidth() / region.width, (double) saved.getHeight() / region.height);
                graphics.translate(-region.x, -region.y);
                graphics.setColor(Color.BLACK);
                graphics.fill(hidden);
                graphics.dispose();
                ImageIO.write(saved, "png", shot);
                LOGGER.info("Lobby shot saved to {} (the window and its dialogs, at {})", shot.getAbsoluteFile(),
                      region);
            } catch (AWTException | IOException | RuntimeException failure) {
                LOGGER.error(failure, "The lobby probe could not photograph the screen");
            }
        } else if (shot != null) {
            LOGGER.warn("The lobby probe takes no photograph: the dialog isn't in front");
        }
        // The newest first, so a question over a dialog closes before it
        for (int i = open.size() - 1; i >= 0; i--) {
            open.get(i).setVisible(false);
        }
        later(() -> {
            boolean blocked = window.modalOpen();
            GpuHost.post(() -> {
                boolean focused = Gdx.graphics instanceof Lwjgl3Graphics graphics
                      && GLFW.glfwGetWindowAttrib(graphics.getWindow().getWindowHandle(), GLFW.GLFW_FOCUSED)
                      == GLFW.GLFW_TRUE;
                LOGGER.info("Lobby probe dialog {} closed: lobby input blocked {}, window focused {}", dialog(),
                      blocked, focused);
                if (quit) {
                    SwingUtilities.invokeLater(() -> System.exit(0));
                }
            });
        }, DIALOG_WAIT_MS);
    }

    private static List<Dialog> showingDialogs() {
        List<Dialog> open = new ArrayList<>();
        for (Window shown : Window.getWindows()) {
            if (shown.isShowing() && shown instanceof Dialog dialog) {
                open.add(dialog);
            }
        }
        return open;
    }

    /** EDT: runs {@code step} once after {@code delay} ms, on the EDT, even while a modal dialog waits. */
    private static void later(Runnable step, int delay) {
        Timer timer = new Timer(delay, event -> step.run());
        timer.setRepeats(false);
        timer.start();
    }

    /** The bounds of the screen that holds most of {@code area}. */
    private static Rectangle screenOf(Rectangle area) {
        Rectangle best = null;
        long bestOverlap = -1;
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle bounds = device.getDefaultConfiguration().getBounds();
            Rectangle overlap = bounds.intersection(area);
            long size = overlap.isEmpty() ? 0 : (long) overlap.width * overlap.height;
            if (size > bestOverlap) {
                bestOverlap = size;
                best = bounds;
            }
        }
        return best == null ? area : best;
    }

    /**
     * GPU thread: logs the native windows of this run's libGDX application and the showing Swing windows, so a probe
     * run proves the lobby shares the one window with nothing beside it.
     */
    static void reportWindows() {
        String nativeWindows;
        try {
            java.lang.reflect.Field field = Gdx.app.getClass().getDeclaredField("windows");
            field.setAccessible(true);
            nativeWindows = String.valueOf(((Array<?>) field.get(Gdx.app)).size);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            nativeWindows = "unknown (" + failure + ")";
        }
        long gpuThreads = Thread.getAllStackTraces().keySet().stream()
              .filter(thread -> thread.getName().startsWith("MegaMek-GPU")).count();
        String glfw = nativeWindows;
        SwingUtilities.invokeLater(() -> {
            List<String> showing = new ArrayList<>();
            for (Window window : Window.getWindows()) {
                if (window.isShowing()) {
                    String title = window instanceof Frame frame ? frame.getTitle()
                          : window instanceof Dialog dialog ? dialog.getTitle() : "";
                    showing.add(window.getClass().getSimpleName() + " '" + title + "'");
                }
            }
            LOGGER.info("Lobby probe windows: libGDX windows {}, GPU threads {}, showing Swing windows {} {}", glfw,
                  gpuThreads, showing.size(), showing);
        });
    }
}
