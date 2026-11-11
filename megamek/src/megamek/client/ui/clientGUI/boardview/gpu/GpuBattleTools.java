/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Graphics;
import java.awt.Image;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Vector;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import jakarta.annotation.Nullable;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.bot.princess.PrincessException;
import megamek.client.event.BoardViewEvent;
import megamek.client.event.BoardViewListenerAdapter;
import megamek.client.lobby.BotSetupForm;
import megamek.client.lobby.BuildingEditForm;
import megamek.client.lobby.ClientCommandForm;
import megamek.client.lobby.EditBotsForm;
import megamek.client.lobby.GameMasterPlayerSetup;
import megamek.client.lobby.GameOptionsForm;
import megamek.client.lobby.HexBrush;
import megamek.client.lobby.LobbyCommands;
import megamek.client.lobby.LobbyError;
import megamek.client.lobby.LobbyFiles;
import megamek.client.lobby.LobbyGameMaster;
import megamek.client.lobby.MapNoteForm;
import megamek.client.lobby.NetworkInformationForm;
import megamek.client.lobby.NovaNetworkForm;
import megamek.client.lobby.PlayerSettingsForm;
import megamek.client.ui.Messages;
import megamek.client.ui.PackageBugReportAction;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.TacticalMark;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.client.ui.tileset.HexTileset;
import megamek.client.ui.tileset.TilesetManager;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.RangeType;
import megamek.common.SpecialHexDisplay;
import megamek.common.board.Board;
import megamek.common.board.BuildingEditSpec;
import megamek.common.board.Coords;
import megamek.common.board.HexEditSpec;
import megamek.common.containers.MunitionTree;
import megamek.common.event.GameListenerAdapter;
import megamek.common.event.GamePhaseChangeEvent;
import megamek.common.loaders.MekSummary;
import megamek.common.options.GameOptions;
import megamek.common.options.IBasicOption;
import megamek.common.preference.PreferenceManager;
import megamek.common.units.Terrains;
import megamek.common.util.Distractable;
import megamek.logging.MMLogger;
import megamek.server.commands.ClientServerCommand;


/**
 * The lobby's native views that the battle's menus open over the battle window, instead of their Swing dialogs: the
 * game options to read, the player settings, the Game Master's random reinforcements and the Requisition, and the
 * help. They show as the
 * battle's own cards ({@link GpuBattleView#cards}) on its HUD, which takes no board input meanwhile.
 * <p>
 * Swing thread unless a method says otherwise.
 */
final class GpuBattleTools implements GpuLobbyTools.Surface {
    private static final MMLogger LOGGER = MMLogger.create(GpuBattleTools.class);
    private static final int CAMO_PICTURE = 72;

    private final ClientGUI gui;
    private final Consumer<Runnable> gpu;
    private final BooleanSupplier closing;
    private final Supplier<LobbyNativeDialogs> cards;
    private final LobbyFiles files;
    private final GpuLobbyTools tools;
    private @Nullable LobbyGameMasterViews.VoteCard voteCard;
    private @Nullable LobbyGameMaster shownVote;
    private @Nullable GpuHexBrushPanel brushPanel;
    /** The Requisition while it shows over the battle. */
    private @Nullable GpuBattleRequisition requisition;

    /**
     * @param gpu     runs work on the GPU thread
     * @param closing whether the battle window closes, read on any thread
     * @param cards   the battle's cards, read on the GPU thread; null while it has none
     */
    GpuBattleTools(ClientGUI gui, Consumer<Runnable> gpu, BooleanSupplier closing,
          Supplier<LobbyNativeDialogs> cards) {
        this.gui = gui;
        this.gpu = gpu;
        this.closing = closing;
        this.cards = cards;
        files = new LobbyFiles((ask, chosen) -> {
            Optional<File> file = GpuBoardWindow.chooseFile(gui, SystemFileDialog.Request.of(ask));
            chosen.accept(file == null ? null : file.orElse(null));
        }, new LobbyFiles.Report() {
            @Override
            public void error(LobbyError error, Object... args) {
                String title = error.title();
                message(title == null ? Messages.getString("Lobby.prompt.message") : title, error.message(args));
            }

            @Override
            public void message(String title, String text) {
                open(dialogs -> dialogs.message(title, text));
            }
        }, GpuLobbyWindow.FOLDERS);
        tools = new GpuLobbyTools(this);
    }

    /** Shows the game options to read, as the Swing dialog shows them once the game has started; Save stays. */
    void gameOptions() {
        GameOptionsForm form;
        try {
            form = new GameOptionsForm(gui.getClient().getGame().getOptions(), Set.of(), false);
        } catch (RuntimeException failure) {
            LOGGER.error(failure, "The battle could not read the game options");
            return;
        }
        LobbyGameOptionsView.Host host = new LobbyGameOptionsView.Host() {
            @Override
            public void edt(Runnable work, Runnable then) {
                GpuBattleTools.this.edt("game options", work, then);
            }

            @Override
            public void send(String password, Vector<IBasicOption> changed, Vector<IBasicOption> all) {
                LOGGER.warn("The battle's game options are read only; nothing was sent");
            }

            @Override
            public void save(Vector<IBasicOption> all, Runnable then) {
                SwingUtilities.invokeLater(() -> files.saveGameOptions(all, () -> gpu.accept(then)));
            }

            @Override
            public void load(GameOptions into, Consumer<Boolean> then) {
                gpu.accept(() -> then.accept(false));
            }

            @Override
            public void grabBars() {
            }
        };
        open(dialogs -> dialogs.gameOptions(form, host));
    }

    /** Shows the local player's settings, which the Swing dialog lets the player change in the battle as well. */
    void playerSettings() {
        PlayerSettingsForm form;
        List<PlayerSettingsForm.Position> positions;
        try {
            form = PlayerSettingsForm.of(gui.getClient(), gui.lobbyCommands());
            positions = form.positions();
        } catch (RuntimeException failure) {
            LOGGER.error(failure, "The battle could not read the player settings");
            return;
        }
        BufferedImage camo = GpuLobbyWindow.camoPicture(form.player().getCamouflage(), CAMO_PICTURE);
        LobbyPlayerSettingsView.Host host = new LobbyPlayerSettingsView.Host() {
            @Override
            public void edt(Runnable work, Runnable then) {
                GpuBattleTools.this.edt("player settings", work, then);
            }

            @Override
            public void saveLoadout(MunitionTree tree, Runnable then) {
                SwingUtilities.invokeLater(() -> files.saveLoadout(tree, () -> gpu.accept(then)));
            }

            @Override
            public void loadLoadout(Consumer<MunitionTree> then) {
                SwingUtilities.invokeLater(() -> files.loadLoadout(tree -> gpu.accept(() -> then.accept(tree))));
            }

            @Override
            public void botSettings() {
                // Only a bot's settings offer it, and the battle's menu opens the local player's
                LOGGER.warn("The battle's player settings do not open a bot's settings");
            }
        };
        open(dialogs -> dialogs.playerSettings(form, positions, camo, host));
    }

    /** Opens the random army and force generator, whose units join the battle as reinforcements next round. */
    void reinforce() {
        tools.randomArmy();
    }

    /**
     * Shows the game's bots and dropped-out players: a ghost may be handed to a bot, a local bot's settings edited
     * and a bot kicked, as the Swing Edit Bots dialog allowed. Each seat's bot settings open the bot setup over the
     * card; Apply has the client carry out what was chosen.
     */
    void editBots() {
        EditBotsForm form;
        try {
            form = EditBotsForm.of(gui.getClient(), PreferenceManager.getClientPreferences().getUseCASPAR());
        } catch (RuntimeException failure) {
            LOGGER.error(failure, "The battle could not read the bots");
            return;
        }
        LobbyEditBotsView.Host host = new LobbyEditBotsView.Host() {
            @Override
            public void settings(EditBotsForm.Row row, Consumer<BehaviorSettings> configured) {
                SwingUtilities.invokeLater(() -> botSettings(row, configured));
            }

            @Override
            public void apply(EditBotsForm.Result result) {
                SwingUtilities.invokeLater(() -> {
                    if (!closing() && !result.isEmpty()) {
                        gui.applyBotEdits(result.newBots(), result.newBotTypes(), result.changedBots(),
                              result.kickBots());
                    }
                });
            }
        };
        open(dialogs -> dialogs.editBots(form, host));
    }

    /** The bot setup for one seat of Edit Bots, over its card; {@code configured} runs on the GPU thread. */
    private void botSettings(EditBotsForm.Row row, Consumer<BehaviorSettings> configured) {
        if (closing()) {
            return;
        }
        BotSetupForm form;
        try {
            form = BotSetupForm.of(gui.getClient(), row.name(), row.settings().getCopy(), row.configuredType());
        } catch (PrincessException | RuntimeException failure) {
            LOGGER.error(failure, "The battle could not read the settings of the bot {}", row.name());
            return;
        }
        LobbyBotSetupView.Host host = new LobbyBotSetupView.Host() {
            @Override
            public void edt(Runnable work, Runnable then) {
                GpuBattleTools.this.edt("bot setup", work, then);
            }

            @Override
            public void help() {
                tools.princessHelp();
            }

            @Override
            public void confirm(LobbyPlayerController.BotSetup setup) {
                gpu.accept(() -> configured.accept(setup.behavior()));
            }
        };
        open(dialogs -> dialogs.botSetup(form, host));
    }

    /** Shows an HTML guide a chapter at a time. */
    void help(String title, File file) {
        tools.helpDocument(title, file);
    }

    /**
     * Shows this computer's addresses and the game's host. The card opens at once and the addresses follow once they
     * have been looked up on a thread of their own, as finding the public address can take a few seconds.
     */
    void networkInformation() {
        NetworkInformationForm form = NetworkInformationForm.of(gui.getClient());
        open(dialogs -> dialogs.networkInformation(form.pending()));
        Thread lookup = new Thread(() -> {
            List<NetworkInformationForm.Row> rows = form.read();
            gpu.accept(() -> {
                LobbyNativeDialogs dialogs = cards.get();
                LobbyNetworkInfoView view = dialogs == null ? null : dialogs.networkInfoView();
                if (view != null) {
                    view.addresses(rows);
                }
            });
        }, "Battle network information");
        lookup.setDaemon(true);
        lookup.start();
    }

    /** Shows the Nova CEWS networks of the local player and their allies, read only. */
    void novaNetworks() {
        NovaNetworkForm form;
        try {
            form = NovaNetworkForm.of(gui.getClient().getGame(), gui.getClient().getLocalPlayer().getId());
        } catch (RuntimeException failure) {
            LOGGER.error(failure, "The battle could not read the Nova CEWS networks");
            return;
        }
        open(dialogs -> dialogs.novaNetworks(form));
    }

    /** Shows About MegaMek. */
    void about() {
        open(LobbyNativeDialogs::about);
    }

    /** Shows Report a Bug; its button packages this client's game and the logs. */
    void bugReport() {
        PackageBugReportAction packaging = GpuBugReport.packaging(gui::getClient, files, this::open);
        open(dialogs -> GpuBugReport.open(dialogs, packaging));
    }

    // ---- The Game Master's cards ----

    private LobbyPromptViews.Edt edtFor(String what) {
        return (work, then) -> edt(what, work, then);
    }

    /**
     * Edits the local player's note on a hex, sent as the Swing dialog sent it once Done is pressed.
     *
     * @param closed runs on the Swing thread once the card has closed, however it closed
     */
    void mapNote(SpecialHexDisplay note, Coords coords, int boardId, Runnable closed) {
        MapNoteForm form = new MapNoteForm(note);
        Runnable onSwing = () -> SwingUtilities.invokeLater(closed);
        gpu.accept(() -> {
            LobbyNativeDialogs dialogs = cards.get();
            if (dialogs == null) {
                onSwing.run();
            } else {
                dialogs.mapNote(form, edtFor("map note"),
                      shown -> gui.getClient().sendSpecialHexDisplayAppend(coords, boardId, shown), onSwing);
            }
        });
    }

    /**
     * Shows the running Game Master vote, or fills the card that shows it again: a voter casts or changes their
     * ballot, the requester may withdraw, and closing the card withdraws the requester's request as the Swing dialog's
     * close did. A voter who closes it sees it again on the next ballot.
     */
    void gameMasterVote(LobbyGameMaster vote) {
        gpu.accept(() -> {
            shownVote = vote;
            if (voteCard != null) {
                voteCard.fill(vote);
                return;
            }
            nativeDialog(dialogs -> {
                LobbyGameMasterViews.VoteCard[] card = new LobbyGameMasterViews.VoteCard[1];
                card[0] = dialogs.gameMasterVote(vote, new LobbyGameMasterViews.VoteActions() {
                    @Override
                    public void vote(boolean yes) {
                        chat(LobbyGameMaster.voteCommand(yes));
                    }

                    @Override
                    public void withdraw() {
                        chat(LobbyCommands.GAME_MASTER_WITHDRAW_COMMAND);
                    }
                }, () -> {
                    if (voteCard == card[0]) {
                        voteCard = null;
                        if (shownVote != null && shownVote.requestedBySelf()) {
                            chat(LobbyCommands.GAME_MASTER_WITHDRAW_COMMAND);
                        }
                    }
                });
                voteCard = card[0];
            });
        });
    }

    /** Takes the vote's card away, as the vote has ended; nothing is sent. */
    void closeGameMasterVote() {
        gpu.accept(() -> {
            LobbyGameMasterViews.VoteCard card = voteCard;
            voteCard = null;
            shownVote = null;
            LobbyNativeDialogs dialogs = cards.get();
            if (card != null && dialogs != null) {
                dialogs.closeDialog(card.dialog());
            }
        });
    }

    /** Any thread: sends a chat command on the Swing thread. */
    private void chat(String command) {
        SwingUtilities.invokeLater(() -> {
            if (!closing()) {
                gui.getClient().sendChat(command);
            }
        });
    }

    /** Tells who the vote made Game Master and how the role is given up, as the Swing notice did. */
    void gameMasterAppointed(String name) {
        String text = GpuBoardWindow.plainText(Messages.getString("GameMasterVoteDialog.passed.message", name));
        open(dialogs -> dialogs.error(Messages.getString("GameMasterVoteDialog.passed.title"), text,
              Messages.getString("Okay"), null, null));
    }

    /** Shows a command's arguments as a form, opened on {@code coords} when a hex was clicked. */
    void clientCommand(ClientServerCommand command, @Nullable Coords coords) {
        ClientCommandForm form = new ClientCommandForm(command, gui.getClient().getGame(), coords);
        open(dialogs -> dialogs.clientCommand(form, edtFor("command"), gui.getClient()::sendChat));
    }

    /** Sets a player's team and deployment zone, opened on {@code player} or the first one offered. */
    void playerSetup(@Nullable Player player) {
        GameMasterPlayerSetup setup = new GameMasterPlayerSetup(gui.getClient().getGame(),
              gui.getClient().getLocalPlayerNumber(), player);
        Consumer<Player> fromGenerator = tools::randomArmy;
        open(dialogs -> dialogs.playerSetup(setup, edtFor("player setup"), gui.getClient()::sendChat,
              this::requisition, gui::reinforceFromFile, fromGenerator));
    }

    /**
     * Opens the Requisition over the battle, its units going to {@code player} when they may, or to the first player
     * offered; when it shows already, it turns to {@code player}. Its units arrive as reinforcements next round.
     */
    void requisition(@Nullable Player player) {
        if (requisition != null) {
            requisition.retarget(player);
            return;
        }
        GpuBattleRequisition.Snapshot snapshot = GpuBattleRequisition.snapshot(gui, player);
        GpuBattleRequisition opened = new GpuBattleRequisition(gui, gpu, closing, tools, player, gone -> {
            if (requisition == gone) {
                requisition = null;
            }
        });
        requisition = opened;
        gpu.accept(() -> opened.open(cards.get(), snapshot));
    }

    /** Puts up, changes or takes away the building in a hex. */
    void building(Coords coords) {
        Board board = gui.getClient().getGame().getBoard();
        BuildingEditForm form = new BuildingEditForm(coords, board.getBoardId(), board.getBuildingAt(coords),
              board.getHex(coords), new BuildingEditForm.Host() {
                  @Override
                  public void sendBuildingEdit(BuildingEditSpec spec) {
                      gui.getClient().sendBuildingEdit(spec);
                  }

                  @Override
                  public List<Integer> fluffLevels(Hex building) {
                      return gui.getTilesetManager().definedFluffLevels(building, Terrains.BLDG_FLUFF);
                  }
              });
        open(dialogs -> dialogs.building(form, edtFor("building"),
              (hex, drawn) -> SwingUtilities.invokeLater(() -> {
                  BufferedImage picture = closing() ? null : buildingPicture(hex);
                  gpu.accept(() -> drawn.accept(picture));
              }), () -> {}));
    }

    /** The building drawn as the board's tileset would draw it, or null when it has nothing for it. */
    private @Nullable BufferedImage buildingPicture(Hex hex) {
        try {
            TilesetManager tileset = gui.getTilesetManager();
            Image base = tileset.baseFor(hex);
            List<Image> supers = tileset.supersFor(hex);
            if (base == null && (supers == null || supers.isEmpty())) {
                return null;
            }
            BufferedImage canvas = new BufferedImage(HexTileset.HEX_W, HexTileset.HEX_H, BufferedImage.TYPE_INT_ARGB);
            Graphics graphics = canvas.getGraphics();
            try {
                if (base != null) {
                    graphics.drawImage(base, 0, 0, null);
                }
                if (supers != null) {
                    for (Image layer : supers) {
                        graphics.drawImage(layer, 0, 0, null);
                    }
                }
            } finally {
                graphics.dispose();
            }
            return canvas;
        } catch (RuntimeException failure) {
            // A picture that cannot be drawn must not keep the Game Master from placing a building
            LOGGER.debug("The building's picture could not be drawn: {}", failure.getMessage());
            return null;
        }
    }

    /**
     * Opens the terrain brush on a hex, beside the board so that clicking the board paints; a brush already open
     * closes first. The brush stays on the Swing thread, where the board changes, and the panel draws its views.
     */
    void hexBrush(Coords coords) {
        int boardId = gui.getClient().getGame().getBoard().getBoardId();
        BrushBoard board = new BrushBoard(boardId);
        HexBrush brush = new HexBrush(boardId, coords, board);
        HexBrush.View opening = brush.view();
        GpuHexBrushPanel[] panel = new GpuHexBrushPanel[1];
        board.changed = () -> {
            HexBrush.View view = brush.view();
            gpu.accept(() -> {
                if (panel[0] != null && brushPanel == panel[0]) {
                    panel[0].show(view);
                }
            });
        };
        board.clicked = clicked -> {
            brush.brushHex(clicked);
            board.changed.run();
        };
        gpu.accept(() -> {
            if (brushPanel != null) {
                brushPanel.close();
            }
            LobbyNativeDialogs dialogs = cards.get();
            if (dialogs == null) {
                SwingUtilities.invokeLater(board::release);
                return;
            }
            panel[0] = new GpuHexBrushPanel(dialogs.kit(), dialogs.stage(), opening, new GpuHexBrushPanel.Host() {
                @Override
                public void painting(boolean on) {
                    SwingUtilities.invokeLater(() -> board.painting(on));
                }

                @Override
                public void edit(Consumer<HexBrush> change, Consumer<HexBrush.View> then) {
                    HexBrush.View[] view = new HexBrush.View[1];
                    edt("terrain brush", () -> {
                        change.accept(brush);
                        view[0] = brush.view();
                    }, () -> then.accept(view[0]));
                }

                @Override
                public void closed() {
                    if (brushPanel == panel[0]) {
                        brushPanel = null;
                    }
                    SwingUtilities.invokeLater(board::release);
                }
            });
            brushPanel = panel[0];
        });
    }

    /**
     * How the brush reaches the board, as the Swing dialog reached it: a left click on a hex paints while painting is
     * on, the phase display is kept from reading that click as an order, and the painted hexes are marked. Swing
     * thread, as is the brush that asks it for hexes and units.
     */
    private final class BrushBoard implements HexBrush.Host {
        private static final int ALL_HEX_BORDERS = 63;
        private final int boardId;
        private final List<TacticalMark> marks = new ArrayList<>();
        private Consumer<Coords> clicked = coords -> {};
        /** Sends the panel the brush's view again, as the board or the units on it may have changed. */
        private Runnable changed = () -> {};
        private boolean painting;
        private @Nullable Distractable suppressed;
        private final BoardViewListenerAdapter listener = new BoardViewListenerAdapter() {
            @Override
            public void hexMoused(BoardViewEvent event) {
                if (event.getType() == BoardViewEvent.BOARD_HEX_CLICKED && event.getButton() == MouseEvent.BUTTON1
                      && event.getCoords() != null) {
                    clicked.accept(event.getCoords());
                }
            }
        };
        private final GameListenerAdapter phases = new GameListenerAdapter() {
            @Override
            public void gamePhaseChange(GamePhaseChangeEvent event) {
                SwingUtilities.invokeLater(() -> {
                    if (painting) {
                        painting(true);
                    }
                    changed.run();
                });
            }
        };

        BrushBoard(int boardId) {
            this.boardId = boardId;
            gui.getClient().getGame().addGameListener(phases);
        }

        /** Turns board clicks into painting or hands them back, catching the phase display that shows now. */
        void painting(boolean on) {
            painting = on;
            for (BoardClientState state : gui.boardStates()) {
                state.removeBoardViewListener(listener);
                if (on) {
                    state.addBoardViewListener(listener);
                }
            }
            if (suppressed != null) {
                suppressed.setIgnoringEvents(false);
                suppressed = null;
            }
            if (on && gui.getCurrentPanel() instanceof Distractable display) {
                suppressed = display;
                suppressed.setIgnoringEvents(true);
            }
        }

        /** Lets go of the board: no more painting, no marks, no phase listening. */
        void release() {
            gui.getClient().getGame().removeGameListener(phases);
            painting(false);
            showMarksNow(List.of());
        }

        @Override
        public @Nullable Hex hex(Coords coords) {
            Board board = gui.getClient().getGame().getBoard(boardId);
            return board != null && board.contains(coords) ? board.getHex(coords) : null;
        }

        @Override
        public boolean occupied(Coords coords) {
            return !gui.getClient().getGame().getEntitiesVector(coords, boardId).isEmpty();
        }

        @Override
        public void sendHexEdit(HexEditSpec spec) {
            if (!closing()) {
                gui.getClient().sendHexEdit(spec);
            }
        }

        @Override
        public void showMarks(Collection<Coords> painted) {
            showMarksNow(List.copyOf(painted));
        }

        private void showMarksNow(List<Coords> painted) {
            List<BoardClientState> states = gui.boardStates();
            if (states.isEmpty()) {
                return;
            }
            BoardClientState state = states.get(0);
            state.removeMarks(marks);
            marks.clear();
            for (Coords coords : painted) {
                marks.add(new TacticalMark.WeaponRange(coords, ALL_HEX_BORDERS, RangeType.RANGE_SHORT));
            }
            state.addMarks(marks);
        }
    }

    /** Any thread: has {@code open} open a card on the GPU thread over the battle, unless the battle has none. */
    private void open(Consumer<LobbyNativeDialogs> open) {
        gpu.accept(() -> nativeDialog(open));
    }

    @Override
    public ClientGUI gui() {
        return gui;
    }

    @Override
    public void post(Runnable work) {
        gpu.accept(work);
    }

    @Override
    public boolean closing() {
        return closing.getAsBoolean();
    }

    @Override
    public void edt(String what, Runnable work, Runnable then) {
        SwingUtilities.invokeLater(() -> {
            try {
                if (!closing()) {
                    work.run();
                }
            } catch (RuntimeException failure) {
                LOGGER.error(failure, "The battle's {} failed", what);
            } finally {
                gpu.accept(then);
            }
        });
    }

    @Override
    public LobbyFiles files() {
        return files;
    }

    @Override
    public void requisitionSprites(List<MekSummary> units) {
        // The battle draws no requisition sprites; the random army lists its units without them
    }

    @Override
    public void nativeDialog(Consumer<LobbyNativeDialogs> open) {
        LobbyNativeDialogs dialogs = cards.get();
        if (dialogs != null) {
            open.accept(dialogs);
        }
    }

    @Override
    public @Nullable LobbyNativeDialogs nativeDialogs() {
        return cards.get();
    }

    @Override
    public @Nullable TextureRegionDrawable requisitionSprite(String key) {
        return null;
    }

    @Override
    public void openSettings() {
        gui.openClientSettings();
    }

    @Override
    public void chatNotice(String text) {
        gui.addToast(ToastLevel.INFO, text);
    }

    @Override
    public void refreshSoon() {
    }
}
