/*
 * Copyright (C) 2024-2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */

package megamek.server;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.CompactWriter;
import megamek.MMConstants;
import megamek.common.game.Game;
import megamek.common.game.IGame;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.util.SerializationHelper;
import megamek.logging.MMLogger;

public final class GameManagerSaveHelper implements AutoCloseable {
    private static final MMLogger LOGGER = MMLogger.create(GameManagerSaveHelper.class);
    private final AbstractGameManager gameManager;
    private final Path directory;
    private final ExecutorService writer;
    // Bound retained snapshots if storage cannot keep up. Reserve space before capturing another game state.
    private final Semaphore pending = new Semaphore(4);
    private boolean closed;
    // Owned by the writer: keep reflection/type metadata between autosaves, with fresh references for each save.
    private final XStream saveGameXStream = SerializationHelper.getSaveGameXStream();
    // Only the caller uses this fallback for game types or scenario state without a native serialization contract.
    private XStream fallbackXStream;

    private record Snapshot(byte[] bytes, boolean xml) { }

    public GameManagerSaveHelper(AbstractGameManager gameManager) {
        this(gameManager, Path.of(MMConstants.SAVEGAME_DIR), Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "Game-save-writer");
            thread.setDaemon(true);
            return thread;
        }));
    }

    /** The helper owns its writer; shutdown drains accepted saves before returning. */
    GameManagerSaveHelper(AbstractGameManager gameManager, Path directory, ExecutorService writer) {
        this.gameManager = gameManager;
        this.directory = directory;
        this.writer = writer;
    }

    /**
     * Captures the game server-side and queues its save. The future and optional chat announcement complete after
     * the file is written, or report a failure.
     *
     * @param fileName The filename to use
     * @param sendChat When true, the saving (or error) is announced in chat
     */
    CompletableFuture<Path> saveGame(String fileName, boolean sendChat) {
        return queueSave(fileName, sendChat, 0, saved -> {});
    }

    CompletableFuture<Path> saveRollingGame(String fileName, int maximumSaves, boolean sendChat) {
        return queueSave(fileName, sendChat, maximumSaves, saved -> {});
    }

    /**
     * Called while the server owns the game state. Only owned snapshot bytes cross into the writer, never live game
     * objects. The worker reconstructs that exact state and uses the existing XML save format.
     */
    private synchronized CompletableFuture<Path> queueSave(String fileName, boolean sendChat, int maximumSaves,
          Consumer<Path> afterSave) {
        if (fileName.endsWith(".gz")) { fileName = fileName.substring(0, fileName.length() - 3); }
        if (!fileName.endsWith(MMConstants.SAVE_FILE_EXT)) { fileName += MMConstants.SAVE_FILE_EXT; }
        CompletableFuture<Path> result = new CompletableFuture<>();
        boolean reserved = false;
        try {
            Path requested = directory.resolve(fileName + ".gz");
            if (closed) { throw new IllegalStateException("Save writer is closed"); }
            pending.acquire();
            reserved = true;
            Snapshot snapshot = snapshot(gameManager.getGame());
            writer.execute(() -> {
                try {
                    Files.createDirectories(directory);
                    Path target = maximumSaves > 0 ? AutosaveService.uniquePath(requested) : requested;
                    writeSnapshot(target, snapshot);
                    if (maximumSaves > 0) { AutosaveService.prune(target, maximumSaves); }
                    if (sendChat) { gameManager.sendChat("MegaMek", "Game saved to " + target); }
                    afterSave.accept(target);
                    result.complete(target);
                } catch (Exception failure) {
                    failed(requested.toString(), sendChat, result, failure);
                } finally {
                    pending.release();
                }
            });
        } catch (Exception failure) {
            if (reserved) { pending.release(); }
            if (failure instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            failed(fileName, sendChat, result, failure);
        }
        return result;
    }

    private Snapshot snapshot(IGame game) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (game instanceof Game) {
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
                output.writeObject(game);
                output.flush();
                return new Snapshot(bytes.toByteArray(), false);
            } catch (NotSerializableException unsupported) {
                // Scripted events and extension state were historically XML-only. Preserve those saves without
                // making every scenario/game type adopt the fast snapshot format.
                bytes.reset();
            }
        }
        if (fallbackXStream == null) { fallbackXStream = SerializationHelper.getSaveGameXStream(); }
        try (Writer xml = new OutputStreamWriter(bytes, StandardCharsets.UTF_8)) {
            fallbackXStream.marshal(game, new CompactWriter(xml));
        }
        return new Snapshot(bytes.toByteArray(), true);
    }

    private void failed(String target, boolean sendChat, CompletableFuture<Path> result, Exception failure) {
        LOGGER.error(failure, "Unable to save file: {}", target);
        try {
            if (sendChat) { gameManager.sendChat("MegaMek", "Could not save the game to " + target); }
        } finally {
            result.completeExceptionally(failure);
        }
    }

    private void writeSnapshot(Path target, Snapshot snapshot) throws Exception {
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), ".megamek-save-", ".tmp");
        try {
            try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(temporary));
                  GZIPOutputStream gzip = new GZIPOutputStream(output)) {
                if (snapshot.xml()) {
                    gzip.write(snapshot.bytes());
                } else {
                    // This stream contains only bytes captured above, not an external file or a network payload.
                    try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(snapshot.bytes()));
                          Writer xml = new OutputStreamWriter(gzip, StandardCharsets.UTF_8)) {
                        saveGameXStream.marshal(input.readObject(), new CompactWriter(xml));
                    }
                }
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Stop accepting work and finish the saves already accepted, including a save requested just before quitting. */
    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            writer.shutdown();
        }
        boolean interrupted = false;
        while (!writer.isTerminated()) {
            try {
                writer.awaitTermination(1, TimeUnit.DAYS);
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        if (interrupted) { Thread.currentThread().interrupt(); }
    }

    /**
     * Saves the game and sends it to the specified connection
     *
     * @param connId    The <code>int</code> connection id to send to
     * @param fileName  The <code>String</code> filename to use
     * @param localPath The <code>String</code> path to the file to be used on the client
     */
    public CompletableFuture<Path> sendSaveGame(int connId, String fileName, String localPath) {
        // Logged for a player's own save only; autosaves come through saveGame and stay quiet
        gameManager.getGame().getForces().logTopLevelOrder("Saving game to " + fileName);
        String destination = localPath.replace("|", " ");
        return queueSave(fileName, false, 0, saved -> sendSavedGame(connId, saved, destination))
              .whenComplete((saved, failure) -> {
                  if (failure != null) {
                      // An explicit failure releases a client waiting to save before quitting, without writing a file.
                      gameManager.send(connId, new Packet(PacketCommand.SEND_SAVEGAME, fileName, null, destination));
                      gameManager.sendChat(connId, "***Server", "Could not send the saved game.");
                  }
              });
    }

    /** Runs on the same writer as file replacement, so another request cannot overwrite this save before delivery. */
    private void sendSavedGame(int connId, Path saved, String localPath) {
        try {
            List<Integer> data = new ArrayList<>();
            for (byte value : Files.readAllBytes(saved)) { data.add(value & 0xff); }
            gameManager.send(connId, new Packet(PacketCommand.SEND_SAVEGAME, saved.getFileName().toString(), data, localPath));
            gameManager.sendChat(connId, "***Server", "Save game has been sent to you.");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
