/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.io.File;
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
import megamek.client.lobby.GameOptionsForm;
import megamek.client.lobby.LobbyError;
import megamek.client.lobby.LobbyFiles;
import megamek.client.lobby.PlayerSettingsForm;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.common.containers.MunitionTree;
import megamek.common.loaders.MekSummary;
import megamek.common.options.GameOptions;
import megamek.common.options.IBasicOption;
import megamek.logging.MMLogger;


/**
 * The lobby's native views that the battle's menus open over the battle window, instead of their Swing dialogs: the
 * game options to read, the player settings, the Game Master's random reinforcements and the help. They show as the
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

    /** Shows an HTML guide a chapter at a time. */
    void help(String title, File file) {
        tools.helpDocument(title, file);
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
