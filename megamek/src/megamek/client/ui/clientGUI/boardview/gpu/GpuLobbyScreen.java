/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.ui.TooltipManager;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import jakarta.annotation.Nullable;
import megamek.client.lobby.BotSetupForm;
import megamek.client.lobby.C3NetworkBoard;
import megamek.client.lobby.CamoCatalog;
import megamek.client.lobby.CamoChoice;
import megamek.client.lobby.GameOptionsForm;
import megamek.client.lobby.LobbyForces;
import megamek.client.lobby.LobbyMenuEntry;
import megamek.client.lobby.LobbyReadiness;
import megamek.client.lobby.LobbyRequisition;
import megamek.client.lobby.LobbySnapshot;
import megamek.client.lobby.MapSetupForm;
import megamek.client.lobby.PlanetaryConditionsForm;
import megamek.client.lobby.PlayerSettingsForm;
import megamek.client.lobby.PortraitCatalog;
import megamek.client.lobby.RandomMapForm;
import megamek.client.lobby.RequisitionAdvancedSearch;
import megamek.client.lobby.RequisitionFactions;
import megamek.client.lobby.RequisitionLoader;
import megamek.client.lobby.RequisitionRecord;
import megamek.client.lobby.RequisitionSearch;
import megamek.client.lobby.RequisitionSkills;
import megamek.client.lobby.UnitConfigForm;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Mode;
import megamek.client.ui.design.DesignTokens.Presentation;
import megamek.client.ui.gdx.DisplayScale;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiRangeInput;
import megamek.client.ui.gdx.UiVirtualList;
import megamek.client.ui.settings.ClientSettingsForm;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.alphaStrike.cardDrawer.ASCard;
import megamek.common.icons.Camouflage;
import megamek.common.icons.Portrait;
import megamek.common.loaders.MekSummary;
import megamek.common.options.OptionsConstants;
import megamek.common.preference.PreferenceManager;
import megamek.logging.MMLogger;

/**
 * The native lobby's screen ({@link GpuLobbyWindow}), the briefing room in the game presentation on the plain
 * surface.base: the top bar ({@link LobbyTopBar}) over three columns ({@link LobbyColumns}),
 * the players with the chat docked under them on the left, the forces in the centre and the battlefield on the right.
 * In a narrow window the battlefield folds into a rail that opens it in the centre, in the forces' place, as a view with
 * Back ({@link LobbyCentre}). It draws only the {@link LobbySnapshot}s its window sends and hands every action back to
 * the window; it never reads the game.
 */
final class GpuLobbyScreen extends ApplicationAdapter {
    private static final MMLogger LOGGER = MMLogger.create(GpuLobbyScreen.class);
    private static final int FRAMES_PER_SECOND = 30;
    /** The centre view that shows the folded battlefield. */
    static final String BATTLEFIELD = "battlefield";
    /** The centre view that shows the Requisition, the unit selector. */
    static final String REQUISITION = "requisition";
    /** The centre's key for the unit bay. */
    static final String UNIT_BAY = "unitbay";
    /** How many catalogue sprites the Requisition keeps as textures; the least recently shown go first. */
    private static final int REQUISITION_SPRITES = 200;
    /** When the developer switch's clicks run, in seconds after the lobby shows. */
    static final double PROBE_CLICK_AT = 1.5;
    /** The stored behaviour the probe chooses in the bot setup. */
    private static final String PROBE_PRESET = "COWARDLY";
    /** {@code lobby+gm}'s name for a click on the seat itself rather than on its card. */
    private static final String GM_SEAT = "seat";

    private final GpuLobbyWindow window;
    private final GUIPreferences preferences;
    private final DesignTokens tokens = DesignTokens.shipped();
    private final java.awt.Color base = awtColor("surface.base");

    private GpuBoardSkin boardSkin;
    private GpuHudKit hud;
    private UiKit kit;
    private Stage stage;
    private float scale = 1;
    private int bw;
    private int bh;

    private LobbyTopBar topBar;
    private LobbyGameMasterSeat gameMasterSeat;
    private LobbyPlayersPanel players;
    private LobbyForcesPanel forces;
    private LobbyBattlefieldPanel battlefield;
    private LobbyCentre centre;
    private LobbyRequisitionPanel requisition;
    /** The map setup, made when it first opens. */
    private LobbyMapSetupView mapSetupView;
    /** The C3 network manager, made when it first opens. */
    private LobbyC3View c3View;
    /** The unit bay in the forces' place, or null when it is shut. */
    private @Nullable LobbyUnitBayView unitBay;
    private ExecutorService searchThread;
    /** The dossier's full records and availabilities, each read on its own thread where the newest unit wins. */
    private ExecutorService recordThread;
    private RequisitionLoader records;
    private ExecutorService factionThread;
    private RequisitionLoader factionLoader;
    /** The dossier's Alpha Strike card pictures, drawn on their own thread where the newest wins. */
    private ExecutorService cardThread;
    private RequisitionLoader cardLoader;
    private RequisitionSearch search;
    /** The Requisition's sprites by catalogue unit and camouflage, the least recently shown first. */
    private final LinkedHashMap<String, TextureRegionDrawable> requisitionSprites = new LinkedHashMap<>(64, .75f,
          true);
    private LobbyColumns columns;
    private Button rail;
    private boolean folded;
    private boolean railPending;
    private boolean menuPending;
    private boolean unitMenuPending;
    private boolean unitDamagePending;
    /** How many locations the probe steps the damage editor on once it is open: {@code +next}. */
    private int damageStepsPending;
    /** Whether the probe rolls heavy damage on the damage editor once it is open: {@code +heavy}. */
    private boolean damageHeavyPending;
    /** Whether the probe picks the whole unit, with its deployment, once the damage editor is open: {@code +whole}. */
    private boolean damageWholePending;
    /** The developer switch's clicks and pointer, run through the stage once their time comes. */
    private boolean clickCompactPending;
    private boolean clickFoldPending;
    private boolean foldReportPending;
    private int filterClicksPending;
    /** The {@code +setskills} clicks left: Set, then the gunnery's +. */
    private int skillClicksPending;
    /** Whether {@code +pickbot} still has to click a bot's card while the Requisition is open. */
    private boolean pickBotPending;
    private Deque<ProbeClick> gmClicks = new ArrayDeque<>();
    /** The developer switch's run through the C3 network manager, or null. */
    private LobbyC3Probe c3Probe;
    /** The developer switch's run through the unit bay, or null. */
    private LobbyUnitBayProbe bayProbe;
    private LobbyMapSetupProbe mapProbe;
    private LobbyToolsProbe toolsProbe;
    private String hoverPending;
    /** The developer switch's advanced search steps, from the first; -1 when there are none. */
    private int advancedStep = -1;
    private double advancedAt;
    /** The dossier tab the developer switch clicks, and how many of its clicks are done. */
    private String tabPending;
    private int tabStep;
    /** How far down the developer switch keeps the dossier tab scrolled before the shot, 0 to 1; -1 when it doesn't. */
    private float scrollPending = -1;
    private boolean scrollLogged;
    /** The Requisition's kept filters and readout switch while the developer switch runs, kept from the Commander's. */
    private String probeFilters = "";
    private boolean probeReadoutTro;
    /** The units' sprites by their window's key, and each shown unit's. */
    private final Map<String, Texture> spriteTextures = new HashMap<>();
    private final Map<Integer, Drawable> spriteDrawables = new HashMap<>();
    private Texture mapTexture;
    /** The players' camo pictures by {@link LobbySnapshot#camoKey}, for their swatches. */
    private final Map<String, Texture> camoTextures = new HashMap<>();
    private final Map<String, Drawable> camoDrawables = new HashMap<>();
    private LobbyNativeDialogs nativeDialogs;
    /** The developer switch's steps through a libGDX dialog, from 0; -1 when there are none. */
    private int nativeStep = -1;
    private long nativeAt;
    /** The file the next frame is saved to, after it is drawn, for the developer switch's libGDX dialogs. */
    private java.io.File nativeShot;
    /** Whether the probe logs the lobby and quits once {@link #nativeShot} is saved. */
    private boolean nativeDone;
    private final ArrayDeque<Runnable> nativeClicks = new ArrayDeque<>();
    private LobbySnapshot shown;
    private long start;
    private boolean shotSaved;

    GpuLobbyScreen(GpuLobbyWindow window, GUIPreferences preferences) {
        this.window = window;
        this.preferences = preferences;
    }

    private java.awt.Color awtColor(String name) {
        return new java.awt.Color(tokens.color(name, Mode.DARK), true);
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    /** GPU thread: ignores the mouse and keyboard while a modal Swing dialog is open, as a disabled window would. */
    void setInputBlocked(boolean blocked) {
        if (stage == null) {
            return;
        }
        if (blocked) {
            focusLost();
            TooltipManager.getInstance().hideAll();
        }
        Gdx.input.setInputProcessor(blocked ? null : stage);
    }

    /** End held preview gestures before a native dialog or another window takes their releases. */
    void focusLost() {
        if (stage != null) {
            stage.cancelTouchFocus();
            stage.setKeyboardFocus(null);
            stage.setScrollFocus(null);
        }
    }

    @Override
    public void pause() { focusLost(); }

    @Override
    public void create() {
        boardSkin = GpuBoardSkin.forScene();
        hud = new GpuHudKit(boardSkin.skin);
        kit = hud.ui;
        kit.presentation(Presentation.GAME);
        stage = new Stage(new ScreenViewport()) {
            @Override
            public boolean scrolled(float amountX, float amountY) {
                UiKit.focusScrollAt(this, Gdx.input.getX(), Gdx.input.getY());
                return super.scrolled(amountX, amountY);
            }
        };
        build();
        map(window.lastMap(), true);
        setInputBlocked(window.modalOpen());
        Gdx.graphics.setForegroundFPS(FRAMES_PER_SECOND);
        show(window.snapshot());
        start = System.nanoTime();
        // Sizing the window draws a frame at once on some platforms
        sizeForProbe();
    }

    /** Gives the window the developer switch's size, keeping the saved size as it was. */
    private void sizeForProbe() {
        GpuLobbyProbe probe = window.probe();
        if (probe == null) {
            return;
        }
        railPending = probe.options().contains("rail");
        menuPending = probe.options().contains("menu");
        unitMenuPending = probe.options().contains("unitmenu");
        unitDamagePending = probe.options().contains("unitdamage");
        damageStepsPending = unitDamagePending && probe.options().contains("next") ? 1 : 0;
        damageHeavyPending = unitDamagePending && probe.options().contains("heavy");
        damageWholePending = unitDamagePending && probe.options().contains("whole");
        clickCompactPending = probe.options().contains("clickcompact");
        clickFoldPending = probe.options().contains("fold");
        foldReportPending = clickFoldPending;
        filterClicksPending = probe.options().contains("closefilters") ? 2 : probe.options().contains("clickfilters")
              ? 1 : 0;
        hoverPending = probe.options().contains("hoverbar") ? "bar" : probe.options().contains("hoverrow") ? "row"
              : null;
        advancedStep = probe.options().contains("advanced") ? 0 : -1;
        skillClicksPending = probe.options().contains("setskills") ? 2 : 0;
        pickBotPending = probe.options().contains("pickbot");
        gmClicks = gmClicks(probe);
        c3Probe = LobbyC3Probe.of(probe);
        bayProbe = LobbyUnitBayProbe.of(probe);
        mapProbe = LobbyMapSetupProbe.of(probe);
        toolsProbe = LobbyToolsProbe.of(probe);
        if (probe.options().contains("compact")) {
            forces.compact(true);
        }
        if (probe.options().contains("formations")) {
            forces.showFormations();
        }
        if (probe.options().contains("autoresolve")) {
            battlefield.openAutoResolve();
        }
        if (probe.options().contains("requisition")) {
            openRequisition();
            if (probe.search() != null) {
                requisition.probeSearch(probe.search());
            } else if (probe.options().contains("atlas")) {
                requisition.probeSearch("Atlas");
            }
            if (probe.options().contains("filters")) {
                requisition.probeFilters();
            }
            if (probe.options().contains("pick")) {
                requisition.probePick();
            }
            tabPending = probe.tab();
        scrollPending = probe.scroll();
        }
        if (!probe.sized()) {
            return;
        }
        GpuHost.keepSavedBounds();
        if (Gdx.graphics instanceof Lwjgl3Graphics graphics && graphics.getWindow() != null) {
            graphics.getWindow().restoreWindow();
        }
        Gdx.graphics.setWindowedMode(probe.width(), probe.height());
    }

    // ---- Layout ----

    private void build() {
        boolean motion = !preferences.getReduceMotion();
        gameMasterSeat = new LobbyGameMasterSeat(kit, new LobbyGameMasterSeat.SeatActions() {
            @Override
            public void claim() {
                window.toggleGameMaster();
            }

            @Override
            public void vote(boolean allow) {
                window.voteGameMaster(allow);
            }

            @Override
            public void withdraw() {
                window.withdrawGameMaster();
            }
        }, motion);
        topBar = new LobbyTopBar(kit, gameMasterSeat,
              () -> window.battlefieldCommand(LobbyBattlefieldController.Command.GAME_OPTIONS), this::openSystemMenu,
              window::ready, this::goTo);
        topBar.shortcut(KeyCommandBind.getDesc(KeyCommandBind.DONE));
        players = new LobbyPlayersPanel(kit, playerActions(), !preferences.getLobbyChatFolded());
        forces = new LobbyForcesPanel(kit, forceActions(), spriteDrawables::get);
        battlefield = new LobbyBattlefieldPanel(kit, battlefieldActions());
        battlefield.map(null, LobbyBattlefieldPanel.MAP_ASPECT, true);
        centre = new LobbyCentre(kit, forces.panel(), motion);
        searchThread = Executors.newSingleThreadExecutor(work -> {
            Thread thread = new Thread(work, "Requisition search");
            thread.setDaemon(true);
            return thread;
        });
        search = new RequisitionSearch(RequisitionSearch::fromCache, searchThread);
        recordThread = daemon("Requisition record");
        records = new RequisitionLoader(recordThread);
        factionThread = daemon("Requisition factions");
        factionLoader = new RequisitionLoader(factionThread);
        cardThread = daemon("Requisition card");
        cardLoader = new RequisitionLoader(cardThread);
        requisition = new LobbyRequisitionPanel(kit, requisitionActions(), requisitionSprites::get, motion);
        rail = kit.rail(Messages.getString("Lobby.battlefield"), "map");
        rail.setProgrammaticChangeEvents(false);
        kit.tip(rail, true).getActor().setText(Messages.getString("Lobby.railTip"));
        UiKit.onChange(rail, () -> {
            if (rail.isChecked()) {
                centre.show(BATTLEFIELD, Messages.getString("Lobby.battlefield"), battlefield.body());
            } else {
                centre.back();
            }
        });
        centre.onChange(this::centreChanged);
        columns = new LobbyColumns(players.panel(), centre, battlefield.panel(), rail, space("lg"));

        Table root = new Table();
        root.setFillParent(true);
        root.top();
        stage.addActor(root);
        root.add(topBar).growX().pad(space("md"), space("lg"), space("md"), space("lg")).row();
        root.add(columns).grow().pad(0, space("lg"), space("lg"), space("lg"));
        players.attach(stage.getRoot());
        forces.attach(stage.getRoot());
        topBar.attach(stage.getRoot());
        nativeDialogs = new LobbyNativeDialogs(kit, stage, motion);
        stage.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                // A dialog over the lobby has the keys: none of them reaches the lobby behind it
                if (event.isHandled() || nativeDialogs.isOpen()) {
                    return false;
                }
                if (keycode != Keys.ESCAPE) {
                    return readyKey(keycode);
                }
                // Escape closes a menu first, then leaves a text field, so typing never reaches the lobby's keys
                if (topBar.back() || players.back() || forces.back()) {
                    return true;
                }
                if (stage.getKeyboardFocus() instanceof TextField) {
                    stage.setKeyboardFocus(null);
                    return true;
                }
                if (REQUISITION.equals(centre.showing()) && requisition.back()) {
                    return true;
                }
                if (centre.showing() != null) {
                    centre.back();
                    return true;
                }
                openSystemMenu();
                return true;
            }
        });
    }

    /** GPU thread: opens the system menu, which Esc opens once nothing else is left to close. */
    void openSystemMenu() {
        nativeDialog(dialogs -> dialogs.systemMenu(systemActions()));
    }

    /**
     * The Ready key (KeyCommandBind.DONE, Ctrl+Enter by default) presses Ready, the Client Settings key
     * (KeyCommandBind.CLIENT_SETTINGS, Alt+C) opens the settings, the save keys (LOCAL_SAVE, Ctrl+S, and
     * QUICK_SAVE, Ctrl+Shift+S) save the game and the load keys (LOCAL_LOAD, Ctrl+L, and QUICK_LOAD, Ctrl+Shift+L)
     * load one where the lobby offers that, unless a text field has the keys.
     */
    private boolean readyKey(int keycode) {
        if (stage.getKeyboardFocus() instanceof TextField) {
            return false;
        }
        int key = GpuBattleView.awtKey(keycode);
        int modifiers = GpuBattleView.modifiers();
        KeyCommandBind settings = KeyCommandBind.CLIENT_SETTINGS;
        if (key == settings.key && modifiers == settings.modifiers) {
            window.openSettings();
            return true;
        }
        KeyCommandBind save = KeyCommandBind.LOCAL_SAVE;
        if (key == save.key && modifiers == save.modifiers) {
            window.saveGame();
            return true;
        }
        KeyCommandBind quickSave = KeyCommandBind.QUICK_SAVE;
        if (key == quickSave.key && modifiers == quickSave.modifiers) {
            window.quickSave();
            return true;
        }
        KeyCommandBind load = KeyCommandBind.LOCAL_LOAD;
        if (GpuLobbyWindow.loadsGames() && key == load.key && modifiers == load.modifiers) {
            window.loadGame();
            return true;
        }
        KeyCommandBind quickLoad = KeyCommandBind.QUICK_LOAD;
        if (GpuLobbyWindow.loadsGames() && key == quickLoad.key && modifiers == quickLoad.modifiers) {
            window.quickLoad();
            return true;
        }
        KeyCommandBind bind = KeyCommandBind.DONE;
        if (key != bind.key || modifiers != bind.modifiers) {
            return false;
        }
        window.ready();
        return true;
    }

    /**
     * Goes to a warning's cause: its first player's card, its first unit's card in the forces, or the map setup; a
     * folded window first brings the forces back to the centre for a unit.
     */
    private void goTo(LobbyReadiness.Warning warning) {
        switch (warning.kind().target()) {
            case PLAYER -> {
                if (!warning.players().isEmpty()) {
                    players.reveal(warning.players().get(0));
                }
            }
            case UNIT -> {
                if (!warning.units().isEmpty()) {
                    if (centre.showing() != null) {
                        centre.back();
                    }
                    forces.reveal(warning.units().get(0));
                }
            }
            case MAP -> window.battlefieldCommand(LobbyBattlefieldController.Command.MAP_SETUP);
        }
    }

    /** GPU thread: lights the red warnings, as Ready was pressed while they stand. */
    void pulseWarnings() {
        if (topBar != null) {
            topBar.pulse();
        }
    }

    /** The system menu's actions, which the window runs on the Swing thread. */
    private LobbyNativeDialogs.SystemActions systemActions() {
        return new LobbyNativeDialogs.SystemActions() {
            @Override
            public void save() {
                window.saveGame();
            }

            @Override
            public void quickSave() {
                window.quickSave();
            }

            @Override
            public void load() {
                window.loadGame();
            }

            @Override
            public void quickLoad() {
                window.quickLoad();
            }

            @Override
            public boolean loads() {
                return GpuLobbyWindow.loadsGames();
            }

            @Override
            public void settings() {
                window.openSettings();
            }

            @Override
            public void leave() {
                window.leave();
            }

            @Override
            public void helpContents() {
                window.runMenuItem(ClientGUI.HELP_CONTENTS);
            }

            @Override
            public void reportBug() {
                window.reportBug();
            }

            @Override
            public void about() {
                nativeDialog(LobbyNativeDialogs::about);
            }
        };
    }

    /** The player column's actions, which the window runs on the Swing thread. */
    private LobbyPlayersPanel.Actions playerActions() {
        return new LobbyPlayersPanel.Actions() {
            @Override
            public void chat(String text) {
                window.chat(text);
            }

            @Override
            public void ready() {
                window.ready();
            }

            @Override
            public void playerSettings(int id) {
                window.playerSettings(id);
            }

            @Override
            public void camouflage(int id) {
                window.camouflage(id);
            }

            @Override
            public void changeTeam(int id, int team) {
                window.changeTeam(id, team);
            }

            @Override
            public void changeDeployment(int id, int position) {
                window.changeDeployment(id, position);
            }

            @Override
            public void botSettings(int id) {
                window.botSettings(id);
            }

            @Override
            public void removeBot(int id) {
                window.removeBot(id);
            }

            @Override
            public void replaceWithBot(int id) {
                window.replaceWithBot(id);
            }

            @Override
            public void ping(int id) {
                window.ping(id);
            }

            @Override
            public void addBot(int team) {
                window.addBot(team);
            }

            @Override
            public void pick(int id) {
                if (REQUISITION.equals(centre.showing())) {
                    requisition.recipient(id);
                }
            }

            @Override
            public void chatOpen(boolean open) {
                preferences.setLobbyChatFolded(!open);
            }

            @Override
            public Drawable camo(String key) {
                return camoDrawables.get(key);
            }

            @Override
            public Drawable sprite(int id) {
                return spriteDrawables.get(id);
            }
        };
    }

    /** The forces column's actions, which the window runs on the Swing thread. */
    private LobbyForcesPanel.Actions forceActions() {
        return new LobbyForcesPanel.Actions() {
            @Override
            public void command(LobbyForcesPanel.Command command) {
                if (command == LobbyForcesPanel.Command.ADD_UNIT) {
                    openRequisition();
                } else {
                    window.forcesCommand(command);
                }
            }

            @Override
            public void sort(int index) {
                window.sortUnits(index);
            }

            @Override
            public boolean printing() {
                return window.tools().printing();
            }

            @Override
            public void configure(int unitId) {
                window.configureUnit(unitId);
            }

            @Override
            public void c3(int unitId) {
                window.c3Unit(unitId);
            }

            @Override
            public void drop(List<Integer> unitIds, List<Integer> formationIds, LobbyForcesController.Target target) {
                window.dropForces(unitIds, formationIds, target);
            }

            @Override
            public void delete(List<Integer> unitIds, List<Integer> formationIds) {
                window.deleteForces(unitIds, formationIds);
            }

            @Override
            public void unitCommand(int unitId, String name) {
                window.unitCommand(unitId, name);
            }

            @Override
            public void menu(List<Integer> unitIds, List<Integer> formationIds, boolean formationsView,
                  Set<String> omit) {
                window.unitMenu(unitIds, formationIds, formationsView, omit);
            }

            @Override
            public void choose(List<Integer> path) {
                window.chooseMenu(path);
            }

        };
    }

    /** The Requisition's actions: searches on their own thread, the rest through the window on the Swing thread. */
    private LobbyRequisitionPanel.Actions requisitionActions() {
        return new LobbyRequisitionPanel.Actions() {
            @Override
            public @Nullable Drawable swatch(int id) {
                return playerSwatch(id);
            }

            @Override
            public void search(RequisitionSearch.Query query, Consumer<RequisitionSearch.Result> done) {
                search.search(query, result -> GpuHost.post(() -> {
                    if (stage != null) {
                        done.accept(result);
                    }
                }));
            }

            @Override
            public void add(MekSummary unit, int quantity, int ownerId, RequisitionSkills skills) {
                window.requisitionAdd(unit, quantity, ownerId, skills);
            }

            @Override
            public void remove(int id) {
                window.requisitionRemove(id);
            }

            @Override
            public void sprites(List<MekSummary> units) {
                window.requisitionSprites(units, requisition.recipient());
            }

            @Override
            public void source(LobbyForcesPanel.Command command) {
                window.forcesCommand(command);
            }

            @Override
            public void load(MekSummary unit, Consumer<RequisitionRecord> done) {
                records.load(() -> RequisitionRecord.read(unit, LobbyRequisitionController.FILES::read),
                      record -> GpuHost.post(() -> {
                          if (stage != null) {
                              done.accept(record);
                          }
                      }));
            }

            @Override
            public void factions(MekSummary unit, Consumer<RequisitionFactions> done) {
                factionLoader.load(() -> RequisitionFactions.of(unit), read -> GpuHost.post(() -> {
                    if (stage != null) {
                        done.accept(read);
                    }
                }));
            }

            @Override
            public void asCardImage(MekSummary unit, int width, Consumer<BufferedImage> done) {
                cardLoader.load(() -> asCardPicture(unit, width), image -> GpuHost.post(() -> {
                    if (stage != null) {
                        done.accept(image);
                    }
                }));
            }

            @Override
            public boolean readoutTro() {
                return (window.probe() != null) ? probeReadoutTro : preferences.getRequisitionReadoutTro();
            }

            @Override
            public void readoutTro(boolean tro) {
                if (window.probe() != null) {
                    probeReadoutTro = tro;
                } else {
                    preferences.setRequisitionReadoutTro(tro);
                }
            }

            @Override
            public String keptFilters() {
                return (window.probe() != null) ? probeFilters : preferences.getRequisitionFilters();
            }

            @Override
            public void keptFilters(String kept) {
                if (window.probe() != null) {
                    probeFilters = kept;
                } else {
                    preferences.setRequisitionFilters(kept);
                }
            }
        };
    }

    /**
     * Any thread: {@code unit}'s Alpha Strike card as MegaMek draws it, {@code width} pixels wide in the card font the
     * Commander chose, or null when it cannot be drawn. The drawing is Java2D into an image, safe without a screen.
     */
    static @Nullable BufferedImage asCardPicture(MekSummary unit, int width) {
        try {
            ASCard card = ASCard.createCard(unit);
            String font = GUIPreferences.getInstance().getAsCardFont();
            if ((font != null) && !font.isBlank()) {
                card.setFont(Font.decode(font));
            }
            return card.getCardImage(width);
        } catch (RuntimeException | LinkageError failed) {
            LOGGER.warn(failed, "The Requisition could not draw the AS card of {}", unit.getName());
            return null;
        }
    }

    private static ExecutorService daemon(String name) {
        return Executors.newSingleThreadExecutor(work -> {
            Thread thread = new Thread(work, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Opens the Requisition in the forces' place, afresh unless it shows already. */
    private void openRequisition() {
        if (REQUISITION.equals(centre.showing())) {
            return;
        }
        centre.show(REQUISITION, Messages.getString("Lobby.requisition.title"), requisition.body());
        requisition.opened(shown == null ? LobbyForces.EMPTY : shown.forces());
    }

    /** GPU thread: caches the sprite under the appearance actually drawn, even after a recipient change. */
    void requisitionSprite(String unit, String camouflage, BufferedImage image) {
        if (stage == null) {
            return;
        }
        Texture texture = GpuStartScreen.texture(image, true);
        texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        String key = LobbyRequisition.spriteKey(unit, camouflage);
        TextureRegionDrawable old = requisitionSprites.put(key, new TextureRegionDrawable(texture));
        if (old != null) {
            old.getRegion().getTexture().dispose();
        }
        Iterator<Map.Entry<String, TextureRegionDrawable>> eldest = requisitionSprites.entrySet().iterator();
        while (requisitionSprites.size() > REQUISITION_SPRITES && eldest.hasNext()) {
            Map.Entry<String, TextureRegionDrawable> entry = eldest.next();
            eldest.remove();
            entry.getValue().getRegion().getTexture().dispose();
            requisition.spriteForgotten(entry.getKey());
            randomArmySprite(entry.getKey().substring(entry.getKey().indexOf('\0') + 1));
        }
        requisition.spriteArrived(key);
        randomArmySprite(unit);
    }

    /** Shows the random army's cards of the unit {@code key} with its sprite as now drawn, or none. */
    private void randomArmySprite(String key) {
        LobbyRandomArmyView army = nativeDialogs == null ? null : nativeDialogs.randomArmyView();
        if (army != null) {
            army.spriteArrived(key);
        }
    }

    /** The local player's catalogue sprite, for the random army dialog. */
    @Nullable TextureRegionDrawable requisitionSprite(String key) {
        if (shown == null) {
            return null;
        }
        LobbyRequisition.Recipient local = shown.requisition().recipients().stream()
              .filter(LobbyRequisition.Recipient::self).findFirst().orElse(null);
        return local == null ? null : requisitionSprites.get(LobbyRequisition.spriteKey(key, local.camouflage()));
    }

    /** The battlefield column's actions, which the window runs on the Swing thread. */
    private LobbyBattlefieldPanel.Actions battlefieldActions() {
        return new LobbyBattlefieldPanel.Actions() {
            @Override
            public void command(LobbyBattlefieldController.Command command) {
                window.battlefieldCommand(command);
            }

            @Override
            public void autoResolve(boolean chances, int runs, int threads) {
                window.autoResolve(chances, runs, threads);
            }
        };
    }

    /**
     * GPU thread: shows the map the window drew, or, without one, that it is being drawn ({@code drawing}) or that
     * there is none.
     */
    void map(@Nullable BufferedImage image, boolean drawing) {
        if (stage == null) {
            return;
        }
        if (mapTexture != null) {
            mapTexture.dispose();
            mapTexture = null;
        }
        if (image == null) {
            battlefield.map(null, LobbyBattlefieldPanel.MAP_ASPECT, drawing);
            return;
        }
        mapTexture = GpuStartScreen.texture(image, true);
        mapTexture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        float aspect = Math.clamp((float) image.getHeight() / image.getWidth(), .4f, 1f);
        battlefield.map(new TextureRegionDrawable(mapTexture), aspect, false);
    }

    /** GPU thread: opens the unit menu the window read for the forces column. */
    void unitMenu(List<LobbyMenuEntry> entries) {
        if (forces != null) {
            forces.showMenu(entries);
            GpuLobbyProbe probe = window.probe();
            if (probe != null && probe.options().contains("unitsub")) {
                forces.openFirstSubmenu();
            }
        }
    }

    /** GPU thread: takes the images of new sprite keys and each shown unit's key, then draws the cards again. */
    void sprites(Map<Integer, String> keys, Map<String, BufferedImage> images) {
        if (stage == null) {
            return;
        }
        images.forEach((key, image) -> {
            // Mipmapped and linear, as the cards draw the sprites smaller than the tiles
            Texture texture = GpuStartScreen.texture(image, true);
            texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            Texture old = spriteTextures.put(key, texture);
            if (old != null) {
                old.dispose();
            }
        });
        spriteDrawables.clear();
        keys.forEach((id, key) -> {
            Texture texture = spriteTextures.get(key);
            if (texture != null) {
                spriteDrawables.put(id, new TextureRegionDrawable(texture));
            }
        });
        forces.redraw();
        players.imagesArrived();
        if (c3View != null && LobbyC3View.KEY.equals(centre.showing())) {
            c3View.redraw();
        }
    }

    /** GPU thread: takes the camo pictures of the players' swatches, by {@link LobbySnapshot#camoKey}. */
    void camos(Map<String, BufferedImage> images) {
        if (stage == null || images.isEmpty()) {
            return;
        }
        images.forEach((key, image) -> {
            Texture texture = GpuStartScreen.texture(image, true);
            texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            Texture old = camoTextures.put(key, texture);
            if (old != null) {
                old.dispose();
            }
            camoDrawables.put(key, new TextureRegionDrawable(texture));
        });
        players.imagesArrived();
    }

    /** Player {@code id}'s camo picture once drawn, else their colour; null when they are not shown. */
    private @Nullable Drawable playerSwatch(int id) {
        if (shown == null) {
            return null;
        }
        for (LobbySnapshot.TeamGroup team : shown.teams()) {
            for (LobbySnapshot.PlayerRow player : team.players()) {
                if (player.id() == id) {
                    Drawable camo = player.camo().isEmpty() ? null : camoDrawables.get(player.camo());
                    return camo != null ? camo : kit.skin.newDrawable("white",
                          new com.badlogic.gdx.graphics.Color(player.colour() << 8 | 0xFF));
                }
            }
        }
        return null;
    }

    /** GPU thread: closes the lobby's open menus, then opens the camo chooser over the lobby. */
    void camoDialog(LobbyCamoView.Source source, CamoCatalog catalog, CamoChoice choice, boolean assets,
          Consumer<Camouflage> chosen) {
        if (stage != null) {
            closeMenus();
            nativeDialogs.camo(source, catalog, choice, assets, chosen);
        }
    }

    /** GPU thread: closes the lobby's open menus, then opens the portrait chooser over the lobby and its unit bay. */
    void portraitDialog(LobbyPortraitView.Source source, PortraitCatalog catalog, @Nullable Portrait initial,
          Consumer<Portrait> chosen) {
        if (stage != null) {
            closeMenus();
            nativeDialogs.portrait(source, catalog, initial, chosen);
        }
    }

    /** GPU thread: closes the lobby's open menus, then opens the player settings over the lobby. */
    void settingsDialog(PlayerSettingsForm form, List<PlayerSettingsForm.Position> positions,
          @Nullable BufferedImage camo, LobbyPlayerSettingsView.Host host) {
        if (stage != null) {
            closeMenus();
            nativeDialogs.playerSettings(form, positions, camo, host);
        }
    }

    /** GPU thread: closes the lobby's open menus, then opens the bot setup over the lobby and any dialog. */
    void botSetupDialog(BotSetupForm form, LobbyBotSetupView.Host host) {
        if (stage != null) {
            closeMenus();
            nativeDialogs.botSetup(form, host);
        }
    }

    /** GPU thread: closes the lobby's open menus, then opens the planetary conditions over the lobby. */
    void conditionsDialog(PlanetaryConditionsForm form, LobbyConditionsView.Host host) {
        if (stage != null) {
            closeMenus();
            nativeDialogs.conditions(form, host);
        }
    }

    /** GPU thread: closes the lobby's open menus, then opens the game options over the lobby. */
    void gameOptionsDialog(GameOptionsForm form, LobbyGameOptionsView.Host host) {
        if (stage != null) {
            closeMenus();
            nativeDialogs.gameOptions(form, host);
        }
    }

    /** GPU thread: closes the lobby's open menus, then opens the Client Settings over the lobby on {@code page}. */
    void clientSettingsDialog(ClientSettingsForm form, @Nullable String page, ClientSettingsView.Host host) {
        if (stage != null) {
            closeMenus();
            nativeDialogs.clientSettings(form, page, host);
        }
    }

    /** GPU thread: shows the game's options anew in the game options, when they show, as the server changed them. */
    void gameOptionsChanged(GameOptionsForm fresh) {
        LobbyGameOptionsView view = nativeDialogs == null ? null : nativeDialogs.optionsView();
        if (view != null) {
            view.reload(fresh);
        }
    }

    /** GPU thread: whether the game options show. */
    boolean gameOptionsOpen() {
        return nativeDialogs != null && nativeDialogs.optionsView() != null;
    }

    /** GPU thread: shows an alert over the lobby and any dialog. */
    void messageDialog(String title, String text) {
        if (stage != null) {
            closeMenus();
            nativeDialogs.message(title, text);
        }
    }

    /** GPU thread: closes the lobby's open menus, then has {@code open} open one of the lobby's libGDX dialogs. */
    void nativeDialog(Consumer<LobbyNativeDialogs> open) {
        if (stage != null) {
            closeMenus();
            open.accept(nativeDialogs);
        }
    }

    private void closeMenus() {
        boolean closed = true;
        while (closed) {
            closed = topBar.back() || players.back() || forces.back();
        }
    }

    /** The lobby's libGDX dialogs; null before the screen is made. */
    @Nullable LobbyNativeDialogs nativeDialogs() {
        return nativeDialogs;
    }

    /** The battlefield's body goes back to its column whenever another view, or the forces, take the centre. */
    private void centreChanged(String showing) {
        columns.expandCentre(LobbyMapSetupView.KEY.equals(showing));
        boolean battlefieldShown = BATTLEFIELD.equals(showing);
        rail.setChecked(battlefieldShown);
        if (!battlefieldShown && battlefield.body().getParent() != battlefield.panel()) {
            battlefield.dock();
        }
        if (c3View != null && c3View.shown() != null && !LobbyC3View.KEY.equals(showing)) {
            c3View.closed();
            window.c3Closed();
        }
        if (unitBay != null && !UNIT_BAY.equals(showing)) {
            unitBay.dispose();
            unitBay = null;
        }
        if (mapSetupView != null && mapSetupView.shown() != null && !LobbyMapSetupView.KEY.equals(showing)) {
            mapSetupView.closed();
            mapSetupView.dispose();
            window.mapSetupClosed();
        }
    }

    /** GPU thread: shows the unit bay for the form's units in the forces' place, instead of any bay shown. */
    void unitBay(UnitConfigForm form, List<BufferedImage> sprites, List<BufferedImage> portraits,
          LobbyUnitBayView.Host host) {
        if (stage == null) {
            return;
        }
        closeMenus();
        LobbyUnitBayView old = unitBay;
        unitBay = new LobbyUnitBayView(kit, form, sprites, portraits, host);
        centre.show(UNIT_BAY, Messages.getString("Lobby.bay.title"), unitBay.body());
        if (old != null) {
            old.dispose();
        }
    }

    /** GPU thread: shuts the unit bay, bringing the forces back. */
    void closeUnitBay() {
        if (UNIT_BAY.equals(centre.showing())) {
            centre.back();
        }
    }

    /** GPU thread: opens the map setup in the forces' place; its setup follows from the window. */
    void openMapSetup() {
        if (stage == null) {
            return;
        }
        if (mapSetupView == null) {
            mapSetupView = new LobbyMapSetupView(kit, window.mapSetupHost());
        }
        closeMenus();
        if (!LobbyMapSetupView.KEY.equals(centre.showing())) {
            mapSetupView.closed();
            centre.show(LobbyMapSetupView.KEY, Messages.getString("Lobby.map.title"), mapSetupView.body());
        }
    }

    /** GPU thread: shows the map setup anew while it shows. */
    void mapSetup(MapSetupForm.Picture picture, List<MapSetupForm.Theme> themes) {
        if (mapSetupView != null && LobbyMapSetupView.KEY.equals(centre.showing())) {
            mapSetupView.show(picture, themes);
        }
    }

    /** GPU thread: a board's picture for the map setup, while it shows. */
    void mapPicture(String key, @Nullable BufferedImage image) {
        if (mapSetupView != null && LobbyMapSetupView.KEY.equals(centre.showing())) {
            mapSetupView.picture(key, image);
        }
    }

    /** GPU thread: the map setup, for the probe and the tests; null before it first opens. */
    @Nullable LobbyMapSetupView mapSetupView() {
        return mapSetupView;
    }

    /** GPU thread: warns that boards of odd width do not tile; {@code dontShowAgain} runs if asked not to again. */
    void oddBoardNag(Runnable dontShowAgain) {
        if (stage != null) {
            nativeDialogs.oddBoardNag(dontShowAgain);
        }
    }

    /** GPU thread: asks {@code text} over the lobby; {@code yes} runs on OK. */
    void confirmDialog(String title, String text, Runnable yes) {
        confirmDialog(title, text, yes, () -> { });
    }

    /** GPU thread: as {@link #confirmDialog(String, String, Runnable)}, running {@code no} when not confirmed. */
    void confirmDialog(String title, String text, Runnable yes, Runnable no) {
        if (stage != null) {
            closeMenus();
            nativeDialogs.confirm(title, text, yes, no);
        } else {
            no.run();
        }
    }

    /** GPU thread: closes the lobby's open menus, then opens the random map settings over the lobby. */
    void randomMapDialog(RandomMapForm form, List<String> themes, LobbyRandomMapView.Host host) {
        if (stage != null) {
            closeMenus();
            nativeDialogs.randomMap(form, themes, host);
        }
    }

    /** GPU thread: opens the C3 network manager in the forces' place; its board follows from the window. */    void openC3() {
        if (stage == null) {
            return;
        }
        if (c3View == null) {
            c3View = new LobbyC3View(kit, new LobbyC3View.Host() {
                @Override
                public void act(Consumer<C3NetworkBoard> action) {
                    window.c3(action);
                }

                @Override
                public void confirm(String title, String text, Runnable yes) {
                    nativeDialogs.confirm(title, text, yes);
                }
            }, spriteDrawables::get);
        }
        closeMenus();
        c3View.closed();
        centre.show(LobbyC3View.KEY, Messages.getString("C3NetworkManagerDialog.title"), c3View.body());
    }

    /** GPU thread: shows the C3 network manager's board anew while it shows. */
    void c3(C3NetworkBoard.Picture picture) {
        if (c3View != null && LobbyC3View.KEY.equals(centre.showing())) {
            c3View.show(picture);
        }
    }

    /** Folds the battlefield into its rail below the fold width, and unfolds it, closing its centre view, above. */
    private void applyFold() {
        boolean fold = LobbyLayout.folds(stage.getWidth());
        if (fold == folded) {
            return;
        }
        folded = fold;
        topBar.narrow(fold);
        if (!fold && BATTLEFIELD.equals(centre.showing())) {
            centre.back();
        }
        columns.fold(fold);
    }

    /** GPU thread: shows {@code snapshot}, the lobby as its window last read it. */
    void show(LobbySnapshot snapshot) {
        if (stage == null || snapshot.equals(shown)) {
            return;
        }
        LobbySnapshot before = shown;
        shown = snapshot;
        topBar.show(snapshot);
        players.show(snapshot, before);
        if (before == null || !before.forces().equals(snapshot.forces())) {
            forces.show(snapshot.forces());
        }
        requisition.show(snapshot.requisition(), snapshot.forces());
        battlefield.show(snapshot);
    }

    // ---- Size and scale ----

    /** Applies a changed GUI scale setting at once, as a resize would. */
    void rescale() {
        if (stage != null) {
            resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        }
    }

    @Override
    public void resize(int width, int height) {
        if (width == 0 || height == 0) {
            return;
        }
        scale = DisplayScale.read(preferences.getGUIScale(), new GpuDisplayScale().contentScale());
        ((ScreenViewport) stage.getViewport()).setUnitsPerPixel(1 / scale);
        stage.getViewport().update(width, height, true);
        bw = Gdx.graphics.getBackBufferWidth();
        bh = Gdx.graphics.getBackBufferHeight();
        applyFold();
    }

    // ---- Each frame ----

    @Override
    public void render() {
        Gdx.gl.glClearColor(base.getRed() / 255f, base.getGreen() / 255f, base.getBlue() / 255f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        if (railPending && folded) {
            railPending = false;
            rail.setChecked(true);
            centre.show(BATTLEFIELD, Messages.getString("Lobby.battlefield"), battlefield.body());
        }
        stage.act(Math.min(0.1f, Gdx.graphics.getDeltaTime()));
        // While units are being added, the roster marks who receives them, however the picker last changed
        players.recipient(REQUISITION.equals(centre.showing()) ? requisition.recipient() : -1);
        // Once the bots have joined: a second before the shot
        if (menuPending && (System.nanoTime() - start) / 1e9 > Math.max(1, window.probe().shotAt() - 1)
              && players.openFirstMenu()) {
            menuPending = false;
        }
        if (unitMenuPending && (System.nanoTime() - start) / 1e9 > Math.max(1, window.probe().shotAt() - 1)
              && forces.openFirstUnitMenu()) {
            unitMenuPending = false;
        }
        probeInput();
        probeNative();
        hud.update(Set.of());
        stage.draw();
        if (nativeShot != null) {
            if (GpuStartScreen.saveFrame(nativeShot, bw, bh)) {
                LOGGER.info("Lobby shot saved to {}", nativeShot.getAbsoluteFile());
            }
            nativeShot = null;
            if (nativeDone) {
                nativeDone = false;
                GpuLobbyProbe probe = window.probe();
                SwingUtilities.invokeLater(() -> {
                    window.logProbePlayer();
                    if (probe != null && probe.quit()) {
                        SwingUtilities.invokeLater(() -> System.exit(0));
                    }
                });
            }
        }
        takeShot();
    }

    /**
     * The developer switch's clicks through a libGDX dialog ({@code lobby+dialog=camo} or {@code playersettings}), a
     * step every second or so once it shows: it picks a colour camo, or the SE start and an offset of 2; saves the
     * frame; presses OK; and saves the frame again with the lobby changed, then logs the snapshot.
     */
    private void probeNative() {
        GpuLobbyProbe probe = window.probe();
        if (nativeStep < 0 || probe == null) {
            return;
        }
        double elapsed = (System.nanoTime() - nativeAt) / 1e9;
        boolean camo = "camo".equals(probe.dialog());
        boolean bot = nativeDialogs.botView() != null;
        boolean conditions = nativeDialogs.conditionsView() != null;
        boolean options = nativeDialogs.optionsView() != null;
        boolean queued = bot || conditions || options;
        UiDialog dialog = nativeDialogs.dialog();
        Group root = stage.getRoot();
        switch (nativeStep) {
            case 0 -> {
                if (dialog != null && elapsed > 2) {
                    if (camo) {
                        probeClick(root.findActor("camo:" + Camouflage.COLOUR_CAMOUFLAGE + "/"));
                    } else if (bot) {
                        probeClick(root.findActor("bot-preset-stored-" + PROBE_PRESET));
                        queueBotClicks(dialog, probe);
                    } else if (conditions) {
                        queueConditionsClicks();
                    } else if (options) {
                        queueOptionsClicks();
                    } else {
                        probeReveal(dialog, root.findActor("settings-start-5"));
                    }
                    nativeStep++;
                } else if (elapsed > 20) {
                    LOGGER.warn("The lobby probe's dialog {} never showed", probe.dialog());
                    nativeStep = -1;
                }
            }
            case 1 -> {
                if (elapsed > 3) {
                    if (camo) {
                        probeClick(root.findActor("camo-item-3"));
                        LobbyCamoView view = nativeDialogs.camoView();
                        LOGGER.info("The lobby probe chose the camo {}", view == null ? null : view.choice().result());
                    } else if (queued) {
                        Runnable click = nativeClicks.poll();
                        if (click != null) {
                            click.run();
                            return;
                        }
                    } else {
                        probeClick(root.findActor("settings-start-5"));
                        Actor offset = root.findActor("settings-offset");
                        probeClick(offset);
                        stage.keyTyped('\b');
                        stage.keyTyped('\b');
                        stage.keyTyped('2');
                        LobbyPlayerSettingsView view = nativeDialogs.settingsView();
                        LOGGER.info("The lobby probe set start {} and offset '{}' ({})",
                              view == null ? null : view.form().startPos(),
                              view == null ? null : view.form().text(PlayerSettingsForm.Field.OFFSET),
                              view == null ? null : view.form().valid() ? "valid" : "invalid");
                    }
                    nativeStep++;
                }
            }
            case 2 -> {
                if (elapsed > 4.5) {
                    nativeShot = probe.shot();
                    nativeStep++;
                }
            }
            case 3 -> {
                if (elapsed > 5.5) {
                    probeClick(root.findActor(camo ? "camo-ok" : bot ? "bot-ok" : conditions ? "conditions-ok"
                          : options ? "options-ok" : "settings-ok"));
                    LOGGER.info("The lobby probe pressed OK: dialog open {}", nativeDialogs.isOpen());
                    nativeStep++;
                }
            }
            case 4 -> {
                if (elapsed > 9) {
                    String path = probe.shot().getPath();
                    nativeShot = new java.io.File(path.replaceFirst("(\\.png)?$", "-after.png"));
                    nativeStep = -1;
                    nativeDone = true;
                }
            }
            default -> nativeStep = -1;
        }
    }

    /**
     * Queues the bot setup's clicks, one a frame so that each scroll shows before its click: after
     * {@link #PROBE_PRESET}, the pirate switch and the bravery slider's right end; with {@code +targets} the targets
     * tab, its first force's first unit and a typed hex.
     */
    private void queueBotClicks(UiDialog dialog, GpuLobbyProbe probe) {
        Group root = stage.getRoot();
        nativeClicks.add(() -> probeReveal(dialog, root.findActor("bot-flag-pirate")));
        nativeClicks.add(() -> probeClick(root.findActor("bot-flag-pirate")));
        nativeClicks.add(() -> probeReveal(dialog, root.findActor("bot-slider-bravery")));
        nativeClicks.add(() -> {
            Actor bravery = root.findActor("bot-slider-bravery");
            int[] end = bravery == null ? null
                  : GpuLobbyProbeInput.hover(stage, bravery, bravery.getWidth() - 2, bravery.getHeight() / 2);
            if (end != null) {
                stage.touchDown(end[0], end[1], 0, Input.Buttons.LEFT);
                stage.touchUp(end[0], end[1], 0, Input.Buttons.LEFT);
            }
        });
        if (probe.options().contains("targets")) {
            nativeClicks.add(() -> probeReveal(dialog, root.findActor("bot-tab-targets")));
            nativeClicks.add(() -> probeClick(root.findActor("bot-tab-targets")));
            nativeClicks.add(() -> probeClick(firstNamed(root, "bot-fold-force-")));
            nativeClicks.add(() -> probeReveal(dialog, firstNamed(root, "bot-tick-unit-")));
            nativeClicks.add(() -> probeClick(firstNamed(root, "bot-tick-unit-")));
            nativeClicks.add(() -> probeReveal(dialog, root.findActor("bot-hex-add")));
            nativeClicks.add(() -> {
                probeClick(root.findActor("bot-hex-coords"));
                for (char typed : "3, 4".toCharArray()) {
                    stage.keyTyped(typed);
                }
            });
            nativeClicks.add(() -> probeClick(root.findActor("bot-hex-add")));
        }
        nativeClicks.add(() -> {
            LobbyBotSetupView view = nativeDialogs.botView();
            if (view != null) {
                BotSetupForm form = view.form();
                LOGGER.info("The lobby probe set the bot {} ({}): preset {}, bravery {}, pirate {}, targets {} and {}",
                      form.name(), form.valid() ? "valid" : "invalid", form.description(),
                      form.value(BotSetupForm.Slider.BRAVERY), form.flag(BotSetupForm.Flag.PIRATE),
                      form.picker().picked(), form.hexTargets().stream().map(BotSetupForm.HexTarget::text).toList());
            }
        });
    }

    /** Queues the planetary conditions' clicks, one a frame: two steps down the light, one down the weather. */
    private void queueConditionsClicks() {
        Group root = stage.getRoot();
        nativeClicks.add(() -> probeClick(root.findActor("conditions-light")));
        nativeClicks.add(() -> stage.keyDown(Input.Keys.DOWN));
        nativeClicks.add(() -> stage.keyDown(Input.Keys.DOWN));
        nativeClicks.add(() -> stage.keyDown(Input.Keys.ENTER));
        nativeClicks.add(() -> probeClick(root.findActor("conditions-weather")));
        nativeClicks.add(() -> stage.keyDown(Input.Keys.DOWN));
        nativeClicks.add(() -> stage.keyDown(Input.Keys.ENTER));
        nativeClicks.add(() -> {
            LobbyConditionsView view = nativeDialogs.conditionsView();
            if (view != null) {
                PlanetaryConditionsForm form = view.form();
                LOGGER.info("The lobby probe set the conditions ({}): light {}, weather {}, wind {}",
                      form.valid() ? "valid" : "invalid", form.light(), form.weather(), form.wind());
            }
        });
    }

    /** Queues the game options' clicks, one a frame: a search for double blind and a click on its check box. */
    private void queueOptionsClicks() {
        Group root = stage.getRoot();
        nativeClicks.add(() -> probeClick(root.findActor("options-search")));
        nativeClicks.add(() -> {
            for (char typed : "double blind".toCharArray()) {
                stage.keyTyped(typed);
            }
        });
        nativeClicks.add(() -> {
            LobbyGameOptionsView view = nativeDialogs.optionsView();
            if (view != null) {
                GameOptionsForm form = view.form();
                form.visiblePages().stream()
                      .filter(page -> form.visibleSections(page).stream()
                            .flatMap(section -> form.visibleRows(page, section).stream())
                            .anyMatch(row -> row.name().equals(OptionsConstants.ADVANCED_DOUBLE_BLIND)))
                      .findFirst()
                      .ifPresent(page -> probeClick(root.findActor("options-page-" + page.id())));
            }
        });
        nativeClicks.add(() -> probeClick(root.findActor("option-" + OptionsConstants.ADVANCED_DOUBLE_BLIND)));
        nativeClicks.add(() -> {
            LobbyGameOptionsView view = nativeDialogs.optionsView();
            if (view != null) {
                LOGGER.info("The lobby probe searched '{}' to page {} and changed {}", view.form().search(),
                      view.pageId(), view.form().changedOptions().stream()
                            .map(option -> option.getName() + "=" + option.getValue()).toList());
            }
        });
    }

    private static @Nullable Actor firstNamed(Group group, String prefix) {
        for (Actor child : group.getChildren()) {
            if (child.getName() != null && child.getName().startsWith(prefix)) {
                return child;
            }
            if (child instanceof Group inner) {
                Actor found = firstNamed(inner, prefix);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void probeClick(@Nullable Actor target) {
        if (target == null) {
            LOGGER.warn("The lobby probe found nothing to click");
            return;
        }
        GpuLobbyProbeInput.click(stage, target);
    }

    /** For the developer switch: clicks the actor called {@code name} through the stage, as a pointer would. */
    void probeClick(String name) {
        if (stage != null) {
            probeClick(stage.getRoot().findActor(name));
        }
    }

    /**
     * A click {@code lobby+gm} makes through the stage: the seat itself or a control on the seat's card by name.
     */
    private record ProbeClick(double at, String name) {}

    /**
     * {@code lobby+gm}'s clicks: the seat and Claim, then
     * with {@code +step} the seat and Step down once the claim has
     * gone through; with {@code +asked} only Allow (or Deny) on the card the second human's ask opens.
     */
    private static Deque<ProbeClick> gmClicks(GpuLobbyProbe probe) {
        Deque<ProbeClick> clicks = new ArrayDeque<>();
        if (!probe.options().contains("gm")) {
            return clicks;
        }
        double answerAt = GpuLobbyProbe.SECOND_ACTS_AT + 1;
        if (probe.options().contains("asked")) {
            clicks.add(new ProbeClick(answerAt, probe.options().contains("deny") ? "lobby-gm-deny" : "lobby-gm-allow"));
            return clicks;
        }
        clicks.add(new ProbeClick(PROBE_CLICK_AT, GM_SEAT));
        clicks.add(new ProbeClick(PROBE_CLICK_AT + .5, "lobby-gm-claim"));
        if (probe.options().contains("step")) {
            clicks.add(new ProbeClick(answerAt, GM_SEAT));
            clicks.add(new ProbeClick(answerAt + .5, "lobby-gm-step-down"));
        }
        return clicks;
    }

    /** The first actor under {@code root} whose name starts with {@code prefix}, depth first. */
    private static @Nullable Actor probeFind(Actor root, String prefix) {
        if (root.getName() != null && root.getName().startsWith(prefix)) {
            return root;
        }
        if (root instanceof Group group) {
            for (Actor child : group.getChildren()) {
                Actor found = probeFind(child, prefix);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Scrolls the dialog's body so that {@code target} shows in the middle. */
    private static void probeReveal(UiDialog dialog, @Nullable Actor target) {
        if (target == null) {
            LOGGER.warn("The lobby probe found nothing to scroll to");
            return;
        }
        ScrollPane scroll = dialog.scroll();
        scroll.layout();
        Vector2 at = target.localToAscendantCoordinates(scroll.getActor(), new Vector2());
        scroll.scrollTo(at.x, at.y, target.getWidth(), target.getHeight(), false, true);
        scroll.updateVisualScroll();
    }
    /**
     * The developer switch's clicks, through the stage as the Commander's would go: the compact rows toggle and the
     * Filters button {@value #PROBE_CLICK_AT} s in, Filters again a second later to shut the drawer, and half a second
     * before the shot the pointer on the second result row beside the scroll bar, or on the bar.
     */
    private void probeInput() {
        GpuLobbyProbe probe = window.probe();
        if (probe == null) {
            return;
        }
        double elapsed = (System.nanoTime() - start) / 1e9;
        if (unitDamagePending && elapsed > PROBE_CLICK_AT) {
            // the units arrive from the server a moment after the lobby shows; looks until a second before the shot
            Actor damage = probeFind(stage.getRoot(), "lobby-unit-damage-");
            if (damage != null) {
                unitDamagePending = false;
                GpuLobbyProbeInput.click(stage, damage);
            } else if (elapsed > probe.shotAt() - 1) {
                unitDamagePending = false;
                LOGGER.warn("The lobby probe found no unit with a damage icon");
            }
        }
        if (!unitDamagePending && damageStepsPending > 0) {
            Actor next = probeFind(stage.getRoot(), "damage-next");
            if (next != null) {
                damageStepsPending--;
                GpuLobbyProbeInput.click(stage, next);
            }
        }
        if (!unitDamagePending && damageHeavyPending) {
            Actor heavy = probeFind(stage.getRoot(), "damage-roll-heavy");
            // the dialog lays out on the frame after it opens; a click before that lands nowhere
            if (heavy != null && heavy.getWidth() > 0) {
                damageHeavyPending = false;
                GpuLobbyProbeInput.click(stage, heavy);
            }
        }
        if (!unitDamagePending && damageWholePending) {
            Actor whole = probeFind(stage.getRoot(), "damage-whole");
            if (whole != null && whole.getWidth() > 0) {
                damageWholePending = false;
                GpuLobbyProbeInput.click(stage, whole);
            }
        }
        if (clickCompactPending && elapsed > PROBE_CLICK_AT) {
            clickCompactPending = false;
            GpuLobbyProbeInput.click(stage, forces.compactToggle());
            LOGGER.info("The lobby probe's compact click: compact rows {}", forces.compact() ? "on" : "off");
        }
        if (clickFoldPending && elapsed > PROBE_CLICK_AT) {
            clickFoldPending = false;
            boolean kept = preferences.getLobbyChatFolded();
            if (players.chatOpen()) {
                GpuLobbyProbeInput.click(stage, players.chatFoldButton());
            }
            preferences.setLobbyChatFolded(kept);
            LOGGER.info("The lobby probe's fold click: the chat is {}", players.chatOpen() ? "open" : "folded");
        }
        if (foldReportPending && elapsed > probe.shotAt() - .5) {
            foldReportPending = false;
            LOGGER.info("The lobby probe's folded chat: {} unread, badge {} shown {}, latest \"{}\"",
                  players.chatUnread(), players.chatBadge().count(), players.chatBadge().isVisible(),
                  players.chatLatest());
        }
        if (filterClicksPending > 0 && REQUISITION.equals(centre.showing())
              && elapsed > PROBE_CLICK_AT + (filterClicksPending == 1 && probe.options().contains("closefilters")
              ? 1 : 0)) {
            filterClicksPending--;
            GpuLobbyProbeInput.click(stage, requisition.filtersToggle());
            LOGGER.info("The lobby probe's Filters click: the drawer is {}", requisition.drawerOpen() ? "open"
                  : "shut");
        }
        if (skillClicksPending > 0 && REQUISITION.equals(centre.showing())
              && elapsed > PROBE_CLICK_AT + (2 - skillClicksPending) * .5) {
            skillClicksPending--;
            GpuLobbyProbeInput.click(stage, skillClicksPending == 1 ? requisition.skillsOption(2)
                  : requisition.gunneryStepper().up());
            if (skillClicksPending == 0) {
                stage.mouseMoved(0, 0);
                stage.setKeyboardFocus(null);
                TooltipManager.getInstance().hideAll();
            }
            LOGGER.info("The lobby probe's skills click: gunnery {} shown {}", requisition.gunneryStepper().value(),
                  requisition.gunneryStepper().getStage() != null);
        }
        if (pickBotPending && REQUISITION.equals(centre.showing()) && elapsed > PROBE_CLICK_AT) {
            Actor card = players.probeBotCard();
            if (card != null) {
                pickBotPending = false;
                GpuLobbyProbeInput.click(stage, card);
                stage.mouseMoved(0, 0);
                LOGGER.info("The lobby probe clicked {}: the units go to player {}, the roster marks player {}",
                      card.getName(), requisition.recipient(), players.recipient());
            }
        }
        if (advancedStep >= 0 && REQUISITION.equals(centre.showing()) && elapsed > PROBE_CLICK_AT
              && elapsed > advancedAt) {
            probeAdvanced(probe, elapsed);
        }
        if (c3Probe != null) {
            c3Probe.step(elapsed, stage, () -> window.forcesCommand(LobbyForcesPanel.Command.C3), c3View);
        }
        if (mapProbe != null) {
            LobbyMapSetupProbe run = mapProbe;
            run.step(elapsed, stage, () -> SwingUtilities.invokeLater(() -> window.probeMapSetup(run.random())),
                  mapSetupView, nativeDialogs.randomMapView(), nativeDialogs.dialog(),
                  what -> SwingUtilities.invokeLater(() -> window.probeMapSettings(what)));
        }
        if (bayProbe != null) {
            LobbyUnitBayProbe run = bayProbe;
            run.step(elapsed, stage, () -> SwingUtilities.invokeLater(() -> window.probeConfigure(run.multi())),
                  unitBay);
        }
        if (toolsProbe != null) {
            LobbyToolsProbe run = toolsProbe;
            run.step(elapsed, stage, () -> SwingUtilities.invokeLater(() -> window.probeTool(run.tool())),
                  nativeDialogs);
        }
        if (!gmClicks.isEmpty() && elapsed > gmClicks.peekFirst().at()) {
            ProbeClick click = gmClicks.removeFirst();
            Actor target = click.name().equals(GM_SEAT) ? gameMasterSeat.button()
                  : gameMasterSeat.card().findActor(click.name());
            probeClick(target);
            LOGGER.info("The lobby probe's Game Master click on {}: {}", click.name(),
                  window.snapshot().gameMaster());
        }
        if (tabPending != null && REQUISITION.equals(centre.showing()) &&         requisition.dossierSettled()
              && elapsed > PROBE_CLICK_AT + tabStep * .5) {
            probeTab();
        }
        if (scrollPending >= 0 && elapsed > probe.shotAt() - .5 && requisition.dossierScroll() != null) {
            ScrollPane pane = requisition.dossierScroll();
            pane.layout();
            pane.setScrollPercentY(scrollPending);
            pane.updateVisualScroll();
            if (!scrollLogged) {
                LOGGER.info("The lobby probe scrolls the dossier tab to {} of {}", scrollPending, pane.getMaxY());
                scrollLogged = true;
            }
        }
        if (hoverPending != null && elapsed > probe.shotAt() - .5 && !requisition.resultsList().items().isEmpty()) {
            ScrollPane pane = requisition.resultsPane();
            UiVirtualList<?> list = requisition.resultsList();
            float rowHeight = list.getPrefHeight() / list.items().size();
            int[] at = "bar".equals(hoverPending)
                  ? GpuLobbyProbeInput.hover(stage, pane, pane.getWidth() - pane.getScrollBarWidth() / 2,
                  pane.getHeight() / 2)
                  : GpuLobbyProbeInput.hover(stage, list, list.getWidth() - 1,
                  list.getHeight() - pane.getScrollY() - rowHeight * 1.5f);
            LOGGER.info("The lobby probe's pointer is on the results' {} at {}", hoverPending,
                  at == null ? "nothing" : at[0] + "," + at[1]);
            hoverPending = null;
            requisition.probeMeasure();
        }
    }

    /**
     * The developer switch's advanced search, a step each half second through the stage: a pause, the strip's
     * Advanced, a walk of at least 5 typed in, then the Alpha Strike size 4 chip or a PPC in the equipment expression;
     * the count of matching units is logged at each step.
     */
    private void probeAdvanced(GpuLobbyProbe probe, double elapsed) {
        int found = requisition.resultsList().items().size();
        advancedAt = elapsed + .6;
        LobbyAdvancedSearchView view = requisition.advancedView();
        switch (advancedStep++) {
            case 0 -> LOGGER.info("The lobby probe's advanced search starts with {} units", found);
            case 1 -> {
                GpuLobbyProbeInput.click(stage, requisition.advancedToggle());
                LOGGER.info("The lobby probe opened the advanced search: {}", requisition.advancedOpen());
            }
            case 2 -> {
                Actor walk = view == null ? null : view.control(RequisitionAdvancedSearch.Field.WALK);
                if (walk instanceof UiRangeInput range) {
                    LOGGER.info("The lobby probe's advanced search before: {} units", found);
                    GpuLobbyProbeInput.click(stage, range.minField());
                    stage.keyTyped('5');
                } else {
                    LOGGER.error("The lobby probe found no walk range");
                    advancedStep = -1;
                }
            }
            case 3 -> {
                LOGGER.info("The lobby probe's advanced search after walk >= 5: {} units, criteria {}", found,
                      requisition.probeCriteria());
                if (probe.options().contains("as") && view != null) {
                    GpuLobbyProbeInput.click(stage, view.tabs().option(1));
                } else if (probe.options().contains("equip") && view != null && view.equipmentEditor() != null) {
                    LobbyAdvancedSearchView.EquipmentEditor editor = view.equipmentEditor();
                    view.reveal(editor.addButton());
                    GpuLobbyProbeInput.click(stage, editor.find().field);
                    for (char each : "PPC".toCharArray()) {
                        stage.keyTyped(each);
                    }
                } else {
                    advancedStep = -1;
                }
            }
            case 4 -> {
                if (probe.options().contains("as") && view != null
                      && view.control(RequisitionAdvancedSearch.Field.AS_SIZE)
                      instanceof com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup sizes) {
                    GpuLobbyProbeInput.click(stage, sizes.getChildren().get(3));
                } else if (view != null && view.equipmentEditor() != null) {
                    LobbyAdvancedSearchView.EquipmentEditor editor = view.equipmentEditor();
                    editor.list().select(0, true);
                    view.reveal(editor.addButton());
                    GpuLobbyProbeInput.click(stage, editor.addButton());
                    LOGGER.info("The lobby probe's equipment expression: {}", editor.expression().getText());
                }
            }
            default -> {
                LOGGER.info("The lobby probe's advanced search after the second criterion: {} units, criteria {}; "
                      + "{} columns {} wide", found, requisition.probeCriteria(), view == null ? 0 : view.columns(),
                      view == null ? 0 : view.actor().getWidth());
                advancedStep = -1;
            }
        }
    }

    /** The developer switch's dossier tab clicks: the tab, then for {@code tro} or {@code summary} the switch. */
    private void probeTab() {
        boolean readoutSwitch = "tro".equals(tabPending) || "summary".equals(tabPending);
        Actor target = (readoutSwitch && tabStep == 0) ? requisition.dossierTab("readout")
              : readoutSwitch ? requisition.readoutOption("tro".equals(tabPending))
              : requisition.dossierTab(tabPending);
        if (target == null) {
            LOGGER.error("The lobby probe has no dossier tab called {}", tabPending);
            tabPending = null;
            return;
        }
        GpuLobbyProbeInput.click(stage, target);
        LOGGER.info("The lobby probe's dossier click on {}: the {} tab shows, readout {}", tabPending,
              requisition.dossierTab().key(), requisition.readoutTro() ? "TRO" : "summary");
        tabStep++;
        if (!readoutSwitch || tabStep > 1) {
            tabPending = null;
        }
    }

    /** Saves the developer switch's PNG once its time has come, then quits when it asks to. */
    private void takeShot() {
        GpuLobbyProbe probe = window.probe();
        if (shotSaved || probe == null || probe.shot() == null || (System.nanoTime() - start) / 1e9 < probe.shotAt()) {
            return;
        }
        shotSaved = true;
        if (probe.dialog() != null && GpuLobbyProbe.NATIVE_DIALOGS.contains(probe.dialog())) {
            nativeStep = 0;
            nativeAt = System.nanoTime();
            SwingUtilities.invokeLater(() -> probe.openNative(window, window.gui()));
            return;
        }
        if (probe.dialog() != null) {
            // The frame buffer has no Swing dialogs, so the screen is photographed instead
            SwingUtilities.invokeLater(window::probeDialog);
            return;
        }
        GpuLobbyProbe.reportWindows();
        if (GpuStartScreen.saveFrame(probe.shot(), bw, bh)) {
            LOGGER.info("Lobby shot saved to {}", probe.shot().getAbsoluteFile());
        }
        if (probe.quit()) {
            boolean save = probe.options().contains("saveprefs");
            // After the window report, which waits its turn on the same thread
            SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> {
                if (save) {
                    PreferenceManager.getInstance().save();
                    LOGGER.info("The lobby probe saved the client settings");
                }
                System.exit(0);
            }));
        }
    }

    @Override
    public void dispose() {
        if (nativeDialogs != null) {
            nativeDialogs.dispose();
        }
        if (mapSetupView != null) {
            mapSetupView.dispose();
            mapSetupView = null;
        }
        if (unitBay != null) {
            unitBay.dispose();
            unitBay = null;
        }
        window.disposing();
        camoTextures.values().forEach(Texture::dispose);
        camoTextures.clear();
        camoDrawables.clear();
        spriteTextures.values().forEach(Texture::dispose);
        spriteTextures.clear();
        requisitionSprites.values().forEach(sprite -> sprite.getRegion().getTexture().dispose());
        requisitionSprites.clear();
        if (requisition != null) {
            requisition.dispose();
        }
        for (ExecutorService thread : new ExecutorService[] { searchThread, recordThread, factionThread, cardThread }) {
            if (thread != null) {
                thread.shutdownNow();
            }
        }
        if (mapTexture != null) {
            mapTexture.dispose();
            mapTexture = null;
        }
        for (Object disposable : new Object[] { stage }) {
            if (disposable instanceof com.badlogic.gdx.utils.Disposable resource) {
                resource.dispose();
            }
        }
        if (hud != null) {
            hud.dispose();
        }
        if (boardSkin != null) {
            boardSkin.release();
        }
    }
}
