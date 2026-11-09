/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.Map;
import java.util.concurrent.FutureTask;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.common.MMRandom;
import megamek.common.Player;
import megamek.common.Report;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.enums.GamePhase;
import megamek.common.enums.MoveStepType;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.moves.MovePath;
import megamek.common.net.packets.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;
import megamek.common.units.ProneCause;
import megamek.server.Server;
import megamek.server.totalWarfare.TWGameManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;

/**
 * A unit whose weight brings a structure down, as a real game plays it: MegaMek's TWGameManager moves a unit onto it,
 * every packet for its player reaches a Client serialized, as over the network, and a GpuBoardSource captures its game.
 * A walker's path stops before the step that broke the span (CF 90 under a 100-ton Atlas) and the server sends only
 * where it fell; a jumper's path lands on the roof (a light building, CF 15, under a 55-ton Dervish) and the fall comes
 * after it. Either way the playback puts the unit on the structure, brings the structure down under it and drops it
 * with the structure.
 */
class GpuWeightCollapseTest {
    private static final int LOCAL = 0, OPPONENT = 1;
    private static final int MOVER = 1;
    /** The 16x17 Grassland's span beside the dry ground at 1008, its deck a level up. */
    private static final Coords SPAN = new Coords(8, 7);
    private static final Coords BESIDE = new Coords(9, 7);
    /** A light building two levels tall on level grass at 1209, two hexes west of 1409. */
    private static final Coords ROOF = new Coords(11, 8);

    /** Straight onto the span from beside it, or a hex's walk and a turn first, which the server does send. */
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void theSpanGivesWayUnderTheUnitThatStepsOntoIt(boolean walksFirst) throws Exception {
        var frames = move("Atlas AS7-D.mtf", walksFirst ? new Coords(10, 7) : BESIDE, walksFirst ? 4 : 5, path -> {
            // From 1108 it walks south-west to 1008 and turns north-west to the span; from 1008 it faces the span.
            if (walksFirst) {
                path.addStep(MoveStepType.FORWARDS);
                path.addStep(MoveStepType.TURN_RIGHT);
            }
            path.addStep(MoveStepType.FORWARDS);
            path.addStep(MoveStepType.FORWARDS);
        }, Map.of());
        var events = frames.moved().timeline();
        var step = comesDownUnder(frames, SPAN, 1);
        assertEquals(BESIDE, step.path().getFirst().coords(), "It steps on from where it was last shown");
        var earlier = events.subList(0, events.indexOf(step)).stream()
              .filter(event -> event instanceof BoardScene.Movement move && move.entityId() == MOVER).toList();
        if (walksFirst) {
            assertEquals(1, earlier.size(), "The walk the server sent comes first: " + earlier);
            assertEquals(BESIDE, ((BoardScene.Movement) earlier.getFirst()).path().getLast().coords());
            assertEquals(((BoardScene.Movement) earlier.getFirst()).type(), step.type(), "The step keeps the walk's pace");
        } else {
            assertEquals(List.of(), earlier, "No other move shows the Atlas before the span gives way");
        }
    }

    /** A jump from 1409 over the rise at 1309 down onto the roof at 1209. */
    @Test
    void theRoofGivesWayUnderTheUnitThatJumpsOntoIt() throws Exception {
        var frames = move("Dervish DV-11DK.mtf", new Coords(13, 8), 5, path -> {
            path.addStep(MoveStepType.START_JUMP);
            path.addStep(MoveStepType.FORWARDS);
            path.addStep(MoveStepType.TURN_LEFT);
            path.addStep(MoveStepType.FORWARDS);
        }, Map.of("1209", "bldg_elev:2;building:1:0;bldg_cf:15"));
        var jump = comesDownUnder(frames, ROOF, 2);
        assertEquals(new Coords(13, 8), jump.path().getFirst().coords(), "The jump the server sent ends on the roof");
    }

    /**
     * The move right before the structure at {@code at} comes down ends on it, standing {@code top} levels up; the
     * structure carries the unit there as its load; the unit falls with it to where the game has it; and played, it is
     * up on the structure as the structure starts to give way and ends where the game has it.
     */
    private static BoardScene.Movement comesDownUnder(Frames frames, Coords at, float top) {
        List<BoardScene.Animation> events = frames.moved().timeline();
        int collapse = -1;
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index) instanceof BoardScene.TileTransition transition && transition.collapses(at)) { collapse = index; }
        }
        assertTrue(collapse > 0, "The structure comes down after the unit gets onto it: " + events);
        var transition = (BoardScene.TileTransition) events.get(collapse);
        var onto = (BoardScene.Movement) events.get(collapse - 1);
        var on = onto.path().getLast();
        assertEquals(MOVER, onto.entityId());
        assertEquals(at, on.coords());
        assertEquals(top, on.elevation(), .01f, "It stands on the structure");
        assertEquals(ProneCause.NONE, on.proneCause());
        assertEquals(List.of(on), transition.collapses().getFirst().loads(), "Its weight is on it as it gives way");
        var fallen = shown(new UnitPlayback(), frames.moved().scene());
        var fall = (BoardScene.Movement) events.get(collapse + 1);
        assertEquals(MOVER, fall.entityId());
        assertEquals(List.of(on, fallen), fall.path(), "It falls with the structure to where the game has it");
        assertEquals(0, fallen.elevation(), .01f);
        var playback = new UnitPlayback();
        playback.accept(frames.initial().timeline(), frames.initial().scene(), ignored -> false);
        playback.accept(events, frames.moved().scene(), ignored -> false);
        com.badlogic.gdx.math.Vector3 atCollapse = null;
        for (int tick = 0; tick < 2000 && playback.busy(); tick++) {
            playback.advance(.02, UnitMotion.Speed.NORMAL);
            if (atCollapse == null && playback.transition() == transition) {
                atCollapse = new com.badlogic.gdx.math.Vector3(playback.motions.get(MOVER).position());
            }
        }
        assertFalse(playback.busy());
        assertNotNull(atCollapse, "The collapse played");
        var up = BoardGeometry.center(at, top);
        assertEquals(up.x, atCollapse.x, 1, "Over the structure when it starts to give way: " + atCollapse);
        assertTrue(atCollapse.z > (up.z + BoardGeometry.center(at, 0).z) / 2, "Up on it: " + atCollapse + " vs " + up);
        assertEquals(fallen, shown(playback, frames.moved().scene()));
        return onto;
    }

    private static BoardScene.Waypoint shown(UnitPlayback playback, BoardScene scene) {
        return playback.present(scene).units().stream().filter(unit -> unit.id() == MOVER).findFirst().orElseThrow().location();
    }

    private record Frames(GpuBoardSource.Frame initial, GpuBoardSource.Frame moved) { }

    /**
     * Moves {@code unit}, from {@code start} facing {@code facing}, along {@code steps} in a movement phase, as its owner
     * sends it, on the 16x17 Grassland with {@code structures} (hex number to terrain) added.
     */
    private static Frames move(String unit, Coords start, int facing, java.util.function.Consumer<MovePath> steps,
          Map<String, String> structures) throws Exception {
        Compute.setRNG(new MMRandom() {
            private final java.util.Random others = new java.util.Random(7);
            @Override public int randomInt(int maxValue) { return maxValue == 6 ? 4 : others.nextInt(maxValue); }
            @Override public float randomFloat() { return .5f; }
        });
        TWGameManager manager = mock(TWGameManager.class, withSettings().useConstructor()
              .defaultAnswer(Answers.CALLS_REAL_METHODS));
        doNothing().when(manager).autoSave();
        Game game = manager.getGame();
        game.getOptions().getOption(OptionsConstants.BASE_MAX_NUMBER_ROUND_SAVES).setValue(0);
        Board board = new Board();
        var file = java.nio.file.Files.createTempFile("weight-collapse", ".board");
        List<String> lines = new ArrayList<>();
        for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("data/boards/AGoAC Maps/16x17 Grassland 2.board"))) {
            String hex = line.startsWith("hex ") ? line.substring(4, 8) : null;
            lines.add(hex != null && structures.containsKey(hex)
                  ? "hex " + hex + " " + line.split(" ", 4)[2] + " \"" + structures.get(hex) + "\" \"grass\"" : line);
        }
        java.nio.file.Files.write(file, lines);
        try { board.load(file.toFile()); } finally { java.nio.file.Files.delete(file); }
        game.setBoard(board);
        Client client = new Client("Irregulars", "localhost", 0);
        client.setLocalPlayerNumber(LOCAL);
        for (Player player : List.of(new Player(LOCAL, "Irregulars"), new Player(OPPONENT, "Princess"))) {
            player.setTeam(player.getId() + 1);
            game.addPlayer(player.getId(), player);
            client.getGame().addPlayer(player.getId(), player.copy());
        }
        Entity mover = add(game, unit, start);
        mover.setFacing(facing);
        mover.setSecondaryFacing(facing);
        game.setupTeams();
        game.createVictoryConditions();
        game.setCurrentRound(1);
        game.addReports(new ArrayList<>(List.of(new Report(2000))));
        game.setPhase(GamePhase.MOVEMENT);
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
            var path = new MovePath(game, mover);
            steps.accept(path);
            // As TWGameManager.receiveMovement handles the owner's packet, without its turn checks.
            var handler = Class.forName("megamek.server.totalWarfare.MovePathHandler")
                  .getDeclaredConstructor(TWGameManager.class, Entity.class, MovePath.class, Map.class);
            handler.setAccessible(true);
            Object moving = handler.newInstance(manager, mover, path, null);
            Method process = moving.getClass().getDeclaredMethod("processMovement");
            process.setAccessible(true);
            process.invoke(moving);
            Method buildings = TWGameManager.class.getDeclaredMethod("applyAffectedBuildings");
            buildings.setAccessible(true);
            buildings.invoke(manager);
            // A collapse no path follows waits out the source's hold; its timer then captures it.
            List<BoardScene.Animation> timeline = new ArrayList<>();
            GpuBoardSource.Frame moved = null;
            for (long deadline = System.nanoTime() + 5_000_000_000L;
                  timeline.stream().noneMatch(BoardScene.TileTransition.class::isInstance) && System.nanoTime() < deadline; ) {
                Thread.sleep(50);
                SwingUtilities.invokeAndWait(() -> { });
                moved = source.takeFrame();
                timeline.addAll(moved.timeline());
            }
            return new Frames(initial, moved.withTimeline(timeline));
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

    private static Entity add(Game game, String file, Coords position) throws Exception {
        Entity entity = new MekFileParser(new File("testresources/megamek/common/units/" + file)).getEntity();
        entity.setId(MOVER);
        entity.setOwner(game.getPlayer(LOCAL));
        entity.setPosition(position);
        entity.setDeployed(true);
        game.addEntity(entity, false);
        return entity;
    }
}
