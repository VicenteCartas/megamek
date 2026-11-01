/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.withSettings;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.common.MMRandom;
import megamek.common.Player;
import megamek.common.Report;
import megamek.common.ResolvedAttack;
import megamek.common.actions.ChargeAttackAction;
import megamek.common.actions.DfaAttackAction;
import megamek.common.actions.PunchAttackAction;
import megamek.common.actions.RamAttackAction;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.net.packets.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.Targetable;
import megamek.server.Server;
import megamek.server.totalWarfare.TWGameManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Answers;

/**
 * The physical attacks that move or crash into units, as a real game plays them: MegaMek's TWGameManager resolves the
 * physical phase, every packet for the local player reaches a Client serialized, as over the network, and a
 * GpuBoardSource captures its game. Each attack's event arrives before what it does, so the playback shows that from
 * its contact, and its log card replays it.
 */
class GpuPhysicalDisplacementTest {
    private static final int LOCAL = 0, OPPONENT = 1;
    private static final int ATTACKER = 1, TARGET = 2;

    /** The user's game: the King Crab punches a Commando dead, then the Commando's charge pushes it off the board. */
    @Test
    void aChargePushesItsTargetOffTheBoardAtItsOwnContactAfterThePunchesBeforeIt() throws Exception {
        var frames = play(ResolvedAttack.Kind.CHARGE, true);
        var playback = new UnitPlayback();
        playback.accept(frames.initial().timeline(), frames.initial().scene(), ignored -> false);
        playback.accept(frames.physical().timeline(), frames.physical().scene(), ignored -> false);
        var kinds = new ArrayList<ResolvedAttack.Kind>();
        UnitAttack previous = null;
        boolean pushedOff = false;
        for (int step = 0; step < 1000 && playback.busy(); step++) {
            playback.advance(.05, UnitMotion.Speed.NORMAL);
            var attack = playback.attack();
            boolean shown = playback.present(frames.physical().scene()).units().stream().anyMatch(unit -> unit.id() == TARGET);
            if (attack != null && attack != previous) { kinds.add(attack.event.result().kind()); }
            previous = attack == null ? previous : attack;
            pushedOff |= attack != null && attack.pushedOff();
            if (attack != null && attack.seconds < attack.duration) {
                assertTrue(shown, "The King Crab shows until the charge has pushed it off and its fall has ended, at "
                      + attack.event.result().kind() + " " + attack.seconds);
            }
        }
        assertEquals(List.of(ResolvedAttack.Kind.PUNCH, ResolvedAttack.Kind.PUNCH, ResolvedAttack.Kind.CHARGE), kinds,
              "The first punch's action includes the Commando's death");
        assertTrue(pushedOff, "The charge pushed the King Crab off the board");
        assertFalse(playback.busy());
        assertFalse(playback.present(frames.physical().scene()).units().stream().anyMatch(unit -> unit.id() == TARGET));
    }

    @ParameterizedTest
    @EnumSource(value = ResolvedAttack.Kind.class, names = { "CHARGE", "DFA", "RAM" })
    void theLogCardOfAnAttackThatMovesOrCrashesReplaysIt(ResolvedAttack.Kind kind) throws Exception {
        var frames = play(kind, false);
        var playback = new UnitPlayback();
        var history = new GpuPlaybackHistory(playback);
        history.accept(frames.initial().timeline(), frames.initial().scene(), frames.initial().reports(), ignored -> false);
        history.accept(frames.physical().timeline(), frames.physical().scene(), frames.physical().reports(), ignored -> false);
        for (int step = 0; step < 1000 && history.running(); step++) { history.advance(.05, ignored -> true); }
        var entry = frames.physical().reports().entries().stream()
              .filter(candidate -> candidate.kind() == GpuReportLog.Kind.PHYSICAL && candidate.attackerId() == ATTACKER)
              .findFirst().orElseThrow();
        assertEquals(TARGET, entry.targetId());
        var step = history.steps(entry).stream().filter(candidate -> candidate.attack() != null).findFirst().orElseThrow();
        assertTrue(history.played(step), "The card offers Replay (GpuLogPanel) once its attack has played");
        history.replayStep(step);
        assertTrue(playback.reviewing());
        assertEquals(kind, playback.attack().event.result().kind());
    }

    private record Frames(GpuBoardSource.Frame initial, GpuBoardSource.Frame physical) { }

    /**
     * Resolves one physical phase on a real TWGameManager, the local player's unit attacking. A charge or a death from
     * above comes from the hex beside the target, on the board's edge when {@code offEdge} with the target's two punches
     * first; a ram is one fighter flying into another's hex. The dice roll 5s (6s for a ram's nerve), so all hit.
     */
    private static Frames play(ResolvedAttack.Kind kind, boolean offEdge) throws Exception {
        boolean ram = kind == ResolvedAttack.Kind.RAM;
        Compute.setRNG(new MMRandom() {
            private final java.util.Random others = new java.util.Random(7);
            @Override public int randomInt(int maxValue) { return maxValue == 6 ? (ram ? 5 : 4) : others.nextInt(maxValue); }
            @Override public float randomFloat() { return .5f; }
        });
        TWGameManager manager = mock(TWGameManager.class, withSettings().useConstructor()
              .defaultAnswer(Answers.CALLS_REAL_METHODS));
        doNothing().when(manager).autoSave();
        Game game = manager.getGame();
        game.getOptions().getOption(OptionsConstants.BASE_MAX_NUMBER_ROUND_SAVES).setValue(0);
        Board board = new Board();
        board.load(new File("data/boards/AGoAC Maps/16x17 Grassland 2.board"));
        game.setBoard(board);
        Client client = new Client("Irregulars", "localhost", 0);
        client.setLocalPlayerNumber(LOCAL);
        for (Player player : List.of(new Player(LOCAL, "Irregulars"), new Player(OPPONENT, "Princess"))) {
            player.setTeam(player.getId() + 1);
            game.addPlayer(player.getId(), player);
            client.getGame().addPlayer(player.getId(), player.copy());
        }
        Coords targetAt = offEdge ? new Coords(5, 16) : new Coords(5, 8);
        Entity attacker = add(game, LOCAL, ram ? "Cheetah F-11.blk" : "Atlas AS7-D.mtf", ATTACKER,
              ram ? targetAt : targetAt.translated(1));
        Entity target = add(game, OPPONENT, ram ? "Chippewa CHP-W7.blk" : "Atlas AS7-D.mtf", TARGET, targetAt);
        attacker.setFacing(4);
        attacker.setSecondaryFacing(4);
        target.setFacing(1);
        target.setSecondaryFacing(1);
        attacker.setDone(true);
        target.setDone(true);
        game.setupTeams();
        game.createVictoryConditions();
        game.setCurrentRound(1);
        // A game in its physical phase has reported before; the log keeps no attack events without any history.
        game.addReports(new ArrayList<>(List.of(new Report(2000))));
        game.setPhase(GamePhase.PHYSICAL);
        switch (kind) {
            case CHARGE -> game.addDisplacementAttack(new ChargeAttackAction(attacker, target));
            case DFA -> {
                attacker.moved = EntityMovementType.MOVE_JUMP;
                game.addDisplacementAttack(new DfaAttackAction(ATTACKER, Targetable.TYPE_ENTITY, TARGET, targetAt));
            }
            default -> {
                attacker.setAltitude(3);
                target.setAltitude(3);
                game.addRam(new RamAttackAction(attacker, target));
            }
        }
        if (offEdge) {
            // The Commando's punches kill it; its charge still resolves, as the physical phase's attacks resolve together.
            game.addAction(new PunchAttackAction(TARGET, Targetable.TYPE_ENTITY, ATTACKER, PunchAttackAction.BOTH));
            for (int location = 0; location < attacker.locations(); location++) {
                attacker.setArmor(0, location);
                if (attacker.hasRearArmor(location)) { attacker.setArmor(0, location, true); }
                attacker.setInternal(1, location);
            }
        }
        Method handle = AbstractClient.class.getDeclaredMethod("handlePacket", Packet.class);
        handle.setAccessible(true);
        Server server = mock(Server.class, invocation -> {
            Object[] arguments = invocation.getArguments();
            if (invocation.getMethod().getName().equals("getGame")) { return game; }
            if (invocation.getMethod().getName().equals("send") && (arguments.length == 1 || arguments[0].equals(LOCAL))) {
                Packet received = received((Packet) arguments[arguments.length - 1]);
                SwingUtilities.invokeAndWait(() -> {
                    try { handle.invoke(client, received); } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException(failure);
                    }
                });
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        GpuBoardSource source = null;
        try (var servers = mockStatic(Server.class)) {
            servers.when(Server::getServerInstance).thenReturn(server);
            manager.sendCurrentInfo(LOCAL);
            FutureTask<GpuBoardSource> created = new FutureTask<>(() -> {
                var view = new BoardClientState(client.getGame(), null, null, 0, null);
                view.setLocalPlayer(LOCAL);
                return new GpuBoardSource(view, JPanel::new);
            });
            SwingUtilities.invokeAndWait(created);
            source = created.get();
            var initial = source.takeFrame();
            Method endPhase = TWGameManager.class.getDeclaredMethod("endCurrentPhase");
            endPhase.setAccessible(true);
            endPhase.invoke(manager);
            SwingUtilities.invokeAndWait(() -> { });
            return new Frames(initial, source.takeFrame());
        } finally {
            Compute.setRNG(MMRandom.R_DEFAULT);
            if (source != null) {
                GpuBoardSource closing = source;
                SwingUtilities.invokeAndWait(closing::close);
            }
        }
    }

    /** The packet as the client receives it: a copy, never the server's own objects. */
    private static Packet received(Packet packet) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ObjectOutputStream(bytes)) { out.writeObject(packet); }
        try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { return (Packet) in.readObject(); }
    }

    private static Entity add(Game game, int owner, String file, int id, Coords position) throws Exception {
        Entity entity = new MekFileParser(new File("testresources/megamek/common/units/" + file)).getEntity();
        entity.setId(id);
        entity.setOwner(game.getPlayer(owner));
        entity.setPosition(position);
        entity.setDeployed(true);
        game.addEntity(entity, false);
        return entity;
    }
}
