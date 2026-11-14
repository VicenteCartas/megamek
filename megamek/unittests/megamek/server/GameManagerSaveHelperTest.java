/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.server;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.units.BipedMek;
import megamek.common.util.SerializationHelper;
import megamek.server.scriptedEvents.GameEndTriggeredEvent;
import megamek.server.trigger.RoundTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class GameManagerSaveHelperTest {
    @TempDir
    Path directory;
    private Game game;
    private AbstractGameManager manager;

    @BeforeEach
    void createGame() {
        game = new Game();
        Board board = Board.createEmptyBoard(3, 3);
        board.getHex(1, 1).setAppearance(Map.of("ground",
              new HexAppearance("ground/grass", null, null, null)));
        game.setBoard(board);
        Player player = new Player(0, "Árvíz & <Roof>");
        game.addPlayer(0, player);
        BipedMek unit = new BipedMek();
        unit.setOwner(player);
        unit.setId(1);
        unit.setPosition(new Coords(1, 1));
        unit.setElevation(2);
        game.addEntity(unit, false);
        game.setPhase(GamePhase.DEPLOYMENT);
        manager = mock(AbstractGameManager.class);
        when(manager.getGame()).thenReturn(game);
    }

    @Test
    void consecutiveSavesKeepCurrentStateReferencesAndBoardAppearance() throws Exception {
        try (var saves = helper(Executors.newSingleThreadExecutor())) {
            for (int elevation : new int[] { 2, 0 }) {
                game.setPhase(elevation == 2 ? GamePhase.DEPLOYMENT : GamePhase.INITIATIVE_REPORT);
                game.getEntity(1).setElevation(elevation);
                game.getEntity(1).setDeployed(elevation == 0);
                Game loaded = load(saves.saveGame("roundtrip.sav.gz", false).get(10, SECONDS));
                assertEquals(game.getPhase(), loaded.getPhase());
                assertEquals(game.getPlayer(0).getName(), loaded.getPlayer(0).getName());
                assertSame(loaded.getPlayer(0), loaded.getEntity(1).getOwner());
                assertSame(loaded, loaded.getEntity(1).getGame());
                assertEquals(game.getEntity(1).getPosition(), loaded.getEntity(1).getPosition());
                assertEquals(elevation, loaded.getEntity(1).getElevation());
                assertEquals(game.getEntity(1).isDeployed(), loaded.getEntity(1).isDeployed());
                assertEquals(game.getBoard().getHex(1, 1).getAppearance(), loaded.getBoard().getHex(1, 1).getAppearance());
            }
        }
    }

    @Test
    void snapshotReturnsWhileWriterIsBlockedAndKeepsTheRequestedGameState() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (var saves = helper(blockedWriter(release))) {
            try {
                var roof = saves.saveGame("roof", false);
                int savedRound = game.getCurrentRound();
                game.setCurrentRound(savedRound + 1);
                game.getEntity(1).setElevation(0);
                game.setPhase(GamePhase.INITIATIVE_REPORT);
                game.getPlayer(0).setName("After snapshot");
                game.getBoard().getHex(1, 1).setAppearance(Map.of());
                var fallen = saves.saveGame("fallen", false);
                assertFalse(roof.isDone());
                assertFalse(Files.exists(directory.resolve("roof.sav.gz")));
                release.countDown();
                Game before = load(roof.get(10, SECONDS));
                assertEquals(savedRound, before.getCurrentRound());
                assertEquals(2, before.getEntity(1).getElevation());
                assertEquals(GamePhase.DEPLOYMENT, before.getPhase());
                assertEquals("Árvíz & <Roof>", before.getPlayer(0).getName());
                assertFalse(before.getBoard().getHex(1, 1).getAppearance().isEmpty());
                Game after = load(fallen.get(10, SECONDS));
                assertEquals(savedRound + 1, after.getCurrentRound());
                assertEquals(0, after.getEntity(1).getElevation());
                assertEquals(GamePhase.INITIATIVE_REPORT, after.getPhase());
                assertTrue(after.getBoard().getHex(1, 1).getAppearance().isEmpty());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void xmlOnlyScenarioStateFallsBackWithoutLosingTheRequestedSnapshot() throws Exception {
        game.addScriptedEvent(new GameEndTriggeredEvent(new RoundTrigger(4)));
        game.setCurrentRound(3);
        CountDownLatch release = new CountDownLatch(1);
        try (var saves = helper(blockedWriter(release))) {
            try {
                var saved = saves.saveGame("scenario", false);
                game.clearScriptedEvents();
                game.setCurrentRound(4);
                release.countDown();
                Game loaded = load(saved.get(10, SECONDS));
                assertEquals(3, loaded.getCurrentRound());
                assertEquals(List.of(new GameEndTriggeredEvent(new RoundTrigger(4))), loaded.scriptedEvents());
                assertEquals(1, loaded.getNoOfPlayers());
                assertEquals(1, loaded.getNoOfEntities());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void otherGameTypesKeepTheirExistingXmlSavePath() throws Exception {
        var strategic = new SBFGame();
        strategic.setBoard(0, Board.createEmptyBoard(3, 2));
        strategic.setPhase(GamePhase.MOVEMENT);
        strategic.setCurrentRound(5);
        when(manager.getGame()).thenReturn(strategic);
        try (var saves = helper(Executors.newSingleThreadExecutor())) {
            Path file = saves.saveGame("strategic", false).get(10, SECONDS);
            try (var input = new GZIPInputStream(Files.newInputStream(file))) {
                var loaded = (SBFGame) SerializationHelper.getLoadSaveGameXStream().fromXML(input);
                assertEquals(GamePhase.MOVEMENT, loaded.getPhase());
                assertEquals(5, loaded.getCurrentRound());
                assertEquals(3, loaded.getBoard().getWidth());
                assertEquals(2, loaded.getBoard().getHeight());
            }
        }
    }

    @Test
    void queuedOverwriteDeliversItsOwnSnapshotBeforeTheNextSaveReplacesIt() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Path file = directory.resolve("shared.sav.gz");
        Files.writeString(file, "previous save remains until commit");
        try (var saves = helper(blockedWriter(release))) {
            try {
                var sent = saves.sendSaveGame(7, "shared.sav", "client|folder");
                game.getEntity(1).setElevation(0);
                var next = saves.saveGame("shared", false);
                assertEquals("previous save remains until commit", Files.readString(file));
                release.countDown();
                sent.get(10, SECONDS);
                next.get(10, SECONDS);
                ArgumentCaptor<Packet> packet = ArgumentCaptor.forClass(Packet.class);
                verify(manager).send(eq(7), packet.capture());
                assertEquals(PacketCommand.SEND_SAVEGAME, packet.getValue().command());
                assertEquals("shared.sav.gz", packet.getValue().getObject(0));
                assertEquals("client folder", packet.getValue().getObject(2));
                @SuppressWarnings("unchecked")
                List<Integer> encoded = (List<Integer>) packet.getValue().getObject(1);
                byte[] payload = new byte[encoded.size()];
                for (int i = 0; i < payload.length; i++) { payload[i] = encoded.get(i).byteValue(); }
                try (var input = new GZIPInputStream(new ByteArrayInputStream(payload))) {
                    Game delivered = (Game) SerializationHelper.getLoadSaveGameXStream().fromXML(input);
                    assertEquals(2, delivered.getEntity(1).getElevation());
                }
                assertEquals(0, load(file).getEntity(1).getElevation());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void rollingSavesResolveQueuedTimestampCollisionsAndPruneOnlyAfterCommit() throws Exception {
        Path old = directory.resolve("Round-old.sav.gz");
        Files.writeString(old, "previous recovery point");
        Path manual = directory.resolve("manual.sav.gz");
        Files.writeString(manual, "keep manual saves");
        CountDownLatch release = new CountDownLatch(1);
        try (var saves = helper(blockedWriter(release))) {
            try {
                var first = saves.saveRollingGame("Round-0-autosave_stamp.sav.gz", 2, false);
                game.getEntity(1).setElevation(0);
                var second = saves.saveRollingGame("Round-0-autosave_stamp.sav.gz", 2, false);
                assertTrue(Files.exists(old));
                release.countDown();
                Path a = first.get(10, SECONDS), b = second.get(10, SECONDS);
                assertNotEquals(a, b);
                assertEquals(2, load(a).getEntity(1).getElevation());
                assertEquals(0, load(b).getEntity(1).getElevation());
                assertFalse(Files.exists(old));
                assertTrue(Files.exists(manual));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void failedWritePreservesRecoveryFilesReportsFailureAndAllowsTheNextSave() throws Exception {
        Files.writeString(directory.resolve("blocked"), "not a directory");
        Path previous = directory.resolve("Round-old.sav.gz");
        Files.writeString(previous, "keep recovery point");
        try (var saves = helper(Executors.newSingleThreadExecutor())) {
            var failed = saves.saveRollingGame("blocked/Round-new", 1, true);
            assertThrows(ExecutionException.class, () -> failed.get(10, SECONDS));
            assertEquals("keep recovery point", Files.readString(previous));
            verify(manager).sendChat(eq("MegaMek"), startsWith("Could not save"));
            verify(manager, never()).sendChat(eq("MegaMek"), startsWith("Game saved"));
            assertEquals(2, load(saves.saveGame("working", false).get(10, SECONDS)).getEntity(1).getElevation());
        }
    }

    @Test
    void shutdownDrainsAcceptedSavesAndRejectsNewRequests() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService writer = blockedWriter(release);
        try (var saves = helper(writer)) {
            try {
                var saved = saves.saveGame("last", false);
                var closed = CompletableFuture.runAsync(saves::close);
                release.countDown();
                closed.get(10, SECONDS);
                assertTrue(saved.isDone());
                assertEquals(2, load(saved.get(10, SECONDS)).getEntity(1).getElevation());
                assertTrue(writer.isTerminated());
                assertThrows(ExecutionException.class, () -> saves.saveGame("too-late", false).get(10, SECONDS));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void failedLocalSaveSendsAnExplicitFailureInsteadOfAnOldFile() throws Exception {
        Files.writeString(directory.resolve("blocked"), "not a directory");
        try (var saves = helper(Executors.newSingleThreadExecutor())) {
            assertThrows(ExecutionException.class,
                  () -> saves.sendSaveGame(7, "blocked/failure", "client|folder").get(10, SECONDS));
            ArgumentCaptor<Packet> packet = ArgumentCaptor.forClass(Packet.class);
            verify(manager).send(eq(7), packet.capture());
            assertEquals(PacketCommand.SEND_SAVEGAME, packet.getValue().command());
            assertNull(packet.getValue().getObject(1));
            assertEquals("client folder", packet.getValue().getObject(2));
            verify(manager).sendChat(7, "***Server", "Could not send the saved game.");
        }
    }

    private GameManagerSaveHelper helper(ExecutorService writer) {
        return new GameManagerSaveHelper(manager, directory, writer);
    }

    private static ExecutorService blockedWriter(CountDownLatch release) throws Exception {
        ExecutorService writer = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        writer.execute(() -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(10, SECONDS));
        return writer;
    }

    private static Game load(Path file) throws Exception {
        try (var input = new GZIPInputStream(Files.newInputStream(file))) {
            Game loaded = (Game) SerializationHelper.getLoadSaveGameXStream().fromXML(input);
            loaded.initializeAfterLoad();
            loaded.getEntitiesVector().forEach(entity -> entity.setGame(loaded));
            return loaded;
        }
    }
}
