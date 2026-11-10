/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import jakarta.annotation.Nullable;
import megamek.client.lobby.LobbyForces;
import megamek.client.lobby.LobbyRequisition;
import megamek.client.lobby.RequisitionFactions;
import megamek.client.lobby.RequisitionLoader;
import megamek.client.lobby.RequisitionRecord;
import megamek.client.lobby.RequisitionSearch;
import megamek.client.lobby.RequisitionSkills;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.gdx.UiKit;
import megamek.common.Player;
import megamek.common.event.GameListener;
import megamek.common.event.GameListenerAdapter;
import megamek.common.event.GameSettingsChangeEvent;
import megamek.common.event.entity.GameEntityChangeEvent;
import megamek.common.event.entity.GameEntityNewEvent;
import megamek.common.event.entity.GameEntityRemoveEvent;
import megamek.common.event.player.GamePlayerChangeEvent;
import megamek.common.game.Game;
import megamek.common.icons.Camouflage;
import megamek.common.loaders.MekSummary;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;

/**
 * The Requisition over the battle: the lobby's unit catalogue as a card, through which a Game Master hands a player
 * units from the catalogue, which arrive as reinforcements next round. While it shows it owns the threads that search
 * the catalogue and read its records, the sprites drawn for it, and a listener that shows the game's changes on it;
 * closing the card, however it closes, gives them all back. Added units cannot be taken back here, as they are already
 * on their way into the game.
 * <p>
 * Swing thread unless a method says otherwise.
 */
final class GpuBattleRequisition {
    private static final MMLogger LOGGER = MMLogger.create(GpuBattleRequisition.class);
    /** How many catalogue sprites are kept on the GPU at most, the eldest given back first. */
    private static final int SPRITES = 200;

    /** What the card shows of the game, read on the Swing thread. */
    record Snapshot(LobbyRequisition requisition, LobbyForces forces, Map<Integer, Integer> colours) { }

    /** A catalogue sprite to draw, in a private copy of the recipient's camouflage. */
    private record Sprite(MekSummary unit, Camouflage camouflage) { }

    private final ClientGUI gui;
    private final Consumer<Runnable> gpu;
    private final BooleanSupplier closing;
    private final GpuLobbyTools tools;
    /** Swing thread: the player last asked for, offered although they may be on no team yet. */
    private int requestedId;
    /** The player the card opens on. */
    private final int firstRequestedId;
    private final Consumer<GpuBattleRequisition> gone;
    private final ExecutorService searchThread = daemon("Battle requisition search");
    private final ExecutorService recordThread = daemon("Battle requisition record");
    private final ExecutorService factionThread = daemon("Battle requisition factions");
    private final ExecutorService cardThread = daemon("Battle requisition card");
    private final RequisitionSearch search = new RequisitionSearch(RequisitionSearch::fromCache, searchThread);
    private final RequisitionLoader records = new RequisitionLoader(recordThread);
    private final RequisitionLoader factions = new RequisitionLoader(factionThread);
    private final RequisitionLoader cards = new RequisitionLoader(cardThread);
    private final LobbySpriteWorker<Sprite> spriteWorker = new LobbySpriteWorker<>("Battle requisition sprites",
          this::drawSprite);
    private final AtomicBoolean refreshDue = new AtomicBoolean();
    private final GameListener listener = new GameListenerAdapter() {
        @Override
        public void gamePlayerChange(GamePlayerChangeEvent event) {
            refreshSoon();
        }

        @Override
        public void gameSettingsChange(GameSettingsChangeEvent event) {
            refreshSoon();
        }

        @Override
        public void gameEntityNew(GameEntityNewEvent event) {
            refreshSoon();
        }

        @Override
        public void gameEntityRemove(GameEntityRemoveEvent event) {
            refreshSoon();
        }

        @Override
        public void gameEntityChange(GameEntityChangeEvent event) {
            refreshSoon();
        }
    };
    private volatile boolean closed;
    /** Swing thread: the game the listener was added to, once it was. */
    private @Nullable Game listened;

    /** GPU thread: the card's view while it shows, its sprites by key, and the players' colours for the swatch. */
    private @Nullable LobbyRequisitionPanel panel;
    private @Nullable UiKit kit;
    private final LinkedHashMap<String, TextureRegionDrawable> sprites = new LinkedHashMap<>();
    private Map<Integer, Integer> colours = Map.of();

    /**
     * @param gpu       runs work on the GPU thread
     * @param closing   whether the battle window closes, read on any thread
     * @param tools     opens the random army, the card's other source of units
     * @param requested the player the units are for at first, or null for the first one offered
     * @param gone      runs on the Swing thread once the card has closed or could not open
     */
    GpuBattleRequisition(ClientGUI gui, Consumer<Runnable> gpu, BooleanSupplier closing, GpuLobbyTools tools,
          @Nullable Player requested, Consumer<GpuBattleRequisition> gone) {
        this.gui = gui;
        this.gpu = gpu;
        this.closing = closing;
        this.tools = tools;
        this.requestedId = requested == null ? Player.PLAYER_NONE : requested.getId();
        this.firstRequestedId = requestedId;
        this.gone = gone;
    }

    /** Reads what the card shows of the game, offering {@code requested} although they may be on no team yet. */
    static Snapshot snapshot(ClientGUI gui, @Nullable Player requested) {
        Game game = gui.getClient().getGame();
        LobbyRequisition requisition = LobbyRequisition.inGame(game, gui.getClient().getLocalPlayer(),
              gui.getLocalBots().keySet(), LobbyRequisition.knownEras(), requested);
        Map<Integer, Integer> colours = new HashMap<>();
        for (Player player : game.getPlayersList()) {
            var colour = player.getColour();
            colours.put(player.getId(), colour == null ? 0 : colour.getColour().getRGB() & 0xFFFFFF);
        }
        return new Snapshot(requisition, LobbyForces.of(gui::getClient, LobbyForces.Order.NONE), colours);
    }

    /** GPU thread: opens the card with {@code dialogs} over the battle, or gives everything back when it has none. */
    void open(@Nullable LobbyNativeDialogs dialogs, Snapshot snapshot) {
        if (closed) {
            return;
        }
        if (dialogs == null) {
            closed();
            return;
        }
        kit = dialogs.kit();
        colours = snapshot.colours();
        panel = dialogs.requisition(actions(), sprites::get, snapshot.requisition(), snapshot.forces(),
              this::closed);
        if (firstRequestedId != Player.PLAYER_NONE) {
            panel.recipient(firstRequestedId);
        }
        SwingUtilities.invokeLater(() -> {
            if (!closed) {
                listened = gui.getClient().getGame();
                listened.addGameListener(listener);
                // A change between the snapshot and the listener shows all the same
                refreshSoon();
            }
        });
    }

    /**
     * Swing thread: brings the open card to {@code player}, offering them although the team they were just given has
     * not come back from the server yet; null keeps the recipient.
     */
    void retarget(@Nullable Player player) {
        if (player == null || closed) {
            return;
        }
        int id = player.getId();
        requestedId = id;
        Snapshot snapshot = snapshot(gui, player);
        gpu.accept(() -> {
            if (panel != null) {
                colours = snapshot.colours();
                panel.show(snapshot.requisition(), snapshot.forces());
                panel.recipient(id);
            }
        });
    }

    /** Any thread: reads the game again on the Swing thread soon and shows it, once for a burst of changes. */
    private void refreshSoon() {
        if (closed || !refreshDue.compareAndSet(false, true)) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            refreshDue.set(false);
            if (closed || closing.getAsBoolean()) {
                return;
            }
            Snapshot snapshot = snapshot(gui, gui.getClient().getGame().getPlayer(requestedId));
            gpu.accept(() -> {
                if (panel != null) {
                    colours = snapshot.colours();
                    panel.show(snapshot.requisition(), snapshot.forces());
                }
            });
        });
    }

    /** GPU thread: the card has closed; stops the threads, gives the sprites back and lets the battle forget it. */
    private void closed() {
        closed = true;
        panel = null;
        spriteWorker.stop();
        searchThread.shutdownNow();
        recordThread.shutdownNow();
        factionThread.shutdownNow();
        cardThread.shutdownNow();
        for (TextureRegionDrawable sprite : sprites.values()) {
            sprite.getRegion().getTexture().dispose();
        }
        sprites.clear();
        SwingUtilities.invokeLater(() -> {
            if (listened != null) {
                listened.removeGameListener(listener);
                listened = null;
            }
            gone.accept(this);
        });
    }

    /** The view's actions: searches and readings on their own threads, adding units on the Swing thread. */
    private LobbyRequisitionPanel.Actions actions() {
        return new LobbyRequisitionPanel.Actions() {
            @Override
            public void search(RequisitionSearch.Query query, Consumer<RequisitionSearch.Result> done) {
                search.search(query, result -> onGpu(() -> done.accept(result)));
            }

            @Override
            public void add(MekSummary unit, int quantity, int ownerId, RequisitionSkills skills) {
                SwingUtilities.invokeLater(() -> {
                    if (!closed && !closing.getAsBoolean()) {
                        new LobbyRequisitionController(gui::getClient, gui.lobbyCommands(),
                              LobbyRequisitionController.FILES).add(unit, quantity, ownerId, skills);
                    }
                });
            }

            @Override
            public void remove(int id) {
                LOGGER.warn("Units added during the battle cannot be taken back; unit {} stays", id);
            }

            @Override
            public boolean undoable() {
                return false;
            }

            @Override
            public void sprites(List<MekSummary> units) {
                int ownerId = (panel == null) ? Player.PLAYER_NONE : panel.recipient();
                SwingUtilities.invokeLater(() -> {
                    if (closed) {
                        return;
                    }
                    Player owner = gui.getClient().getGame().getPlayer(ownerId);
                    if (owner == null) {
                        owner = gui.getClient().getLocalPlayer();
                    }
                    if (owner != null) {
                        Camouflage camouflage = owner.getCamouflage().clone();
                        List<Sprite> wanted = new ArrayList<>();
                        for (MekSummary unit : units) {
                            wanted.add(new Sprite(unit, camouflage));
                        }
                        spriteWorker.request(wanted);
                    }
                });
            }

            @Override
            public void source(LobbyForcesPanel.Command command) {
                int ownerId = (panel == null) ? Player.PLAYER_NONE : panel.recipient();
                SwingUtilities.invokeLater(() -> {
                    if (closed || closing.getAsBoolean()) {
                        return;
                    }
                    Player owner = gui.getClient().getGame().getPlayer(ownerId);
                    if (owner == null) {
                        owner = gui.getClient().getLocalPlayer();
                    }
                    switch (command) {
                        case LOAD_LIST -> gui.reinforceFromFile(owner);
                        case RANDOM_ARMY -> tools.randomArmy(owner);
                        default -> LOGGER.warn("The battle's Requisition has no source {}", command);
                    }
                });
            }

            @Override
            public void load(MekSummary unit, Consumer<RequisitionRecord> done) {
                records.load(() -> RequisitionRecord.read(unit, LobbyRequisitionController.FILES::read),
                      record -> onGpu(() -> done.accept(record)));
            }

            @Override
            public void factions(MekSummary unit, Consumer<RequisitionFactions> done) {
                factions.load(() -> RequisitionFactions.of(unit), read -> onGpu(() -> done.accept(read)));
            }

            @Override
            public void asCardImage(MekSummary unit, int width, Consumer<BufferedImage> done) {
                cards.load(() -> GpuLobbyScreen.asCardPicture(unit, width), image -> onGpu(() -> done.accept(image)));
            }

            @Override
            public boolean readoutTro() {
                return GUIPreferences.getInstance().getRequisitionReadoutTro();
            }

            @Override
            public void readoutTro(boolean tro) {
                GUIPreferences.getInstance().setRequisitionReadoutTro(tro);
            }

            @Override
            public String keptFilters() {
                return GUIPreferences.getInstance().getRequisitionFilters();
            }

            @Override
            public void keptFilters(String kept) {
                GUIPreferences.getInstance().setRequisitionFilters(kept);
            }

            @Override
            public @Nullable Drawable swatch(int id) {
                Integer colour = colours.get(id);
                return (kit == null || colour == null) ? null
                      : kit.skin.newDrawable("white", new Color(colour << 8 | 0xFF));
            }
        };
    }

    /** Any thread: runs {@code work} on the GPU thread while the card shows. */
    private void onGpu(Runnable work) {
        gpu.accept(() -> {
            if (panel != null) {
                work.run();
            }
        });
    }

    /** The sprites' thread: draws one catalogue sprite on the Swing thread and keeps it on the GPU thread. */
    private void drawSprite(Sprite request) {
        if (closed) {
            return;
        }
        MekSummary unit = request.unit();
        Entity entity = LobbyRequisitionController.FILES.read(unit);
        if (entity == null) {
            return;
        }
        String unitKey = LobbyRequisitionPanel.spriteKey(unit);
        String camouflageKey = LobbyRequisition.camouflageKey(request.camouflage());
        try {
            // The tileset and its images are Swing's
            SwingUtilities.invokeAndWait(() -> {
                BufferedImage image = closed ? null : GpuLobbyWindow.sprite(entity, request.camouflage());
                if (image != null) {
                    onGpu(() -> keepSprite(LobbyRequisition.spriteKey(unitKey, camouflageKey), image));
                }
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException failure) {
            LOGGER.warn(failure, "The battle's Requisition could not draw the sprite of {}", unit.getName());
        }
    }

    /** GPU thread: keeps a drawn sprite, giving back the eldest beyond {@link #SPRITES}, and shows it. */
    private void keepSprite(String key, BufferedImage image) {
        Texture texture = GpuStartScreen.texture(image, true);
        texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        TextureRegionDrawable old = sprites.put(key, new TextureRegionDrawable(texture));
        if (old != null) {
            old.getRegion().getTexture().dispose();
        }
        Iterator<Map.Entry<String, TextureRegionDrawable>> eldest = sprites.entrySet().iterator();
        while (sprites.size() > SPRITES && eldest.hasNext()) {
            Map.Entry<String, TextureRegionDrawable> entry = eldest.next();
            eldest.remove();
            entry.getValue().getRegion().getTexture().dispose();
            if (panel != null) {
                panel.spriteForgotten(entry.getKey());
            }
        }
        if (panel != null) {
            panel.spriteArrived(key);
        }
    }

    private static ExecutorService daemon(String name) {
        return Executors.newSingleThreadExecutor(work -> {
            Thread thread = new Thread(work, name);
            thread.setDaemon(true);
            return thread;
        });
    }
}
