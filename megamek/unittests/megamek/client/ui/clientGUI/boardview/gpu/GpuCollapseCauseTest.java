/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import megamek.common.actions.WeaponAttackAction;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.EquipmentType;
import megamek.common.enums.MoveStepType;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.moves.MovePath;
import megamek.common.net.packets.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.units.BuildingTarget;
import megamek.common.units.Entity;
import megamek.common.units.ProneCause;
import megamek.common.units.Targetable;
import megamek.server.Server;
import megamek.server.totalWarfare.TWGameManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;

/**
 * What brings a structure down, as a real game plays it: MegaMek's TWGameManager moves a unit or resolves its fire,
 * every packet for its player reaches a Client serialized, as over the network, and a GpuBoardSource captures its game.
 * <p>
 * A unit's weight: a walker's path stops before the step that broke the span (CF 90 under a 100-ton Atlas) and the
 * server sends only where it fell; a jumper's path lands on the roof (a light building, CF 15, under a 55-ton Dervish)
 * and the fall comes after it. Either way the playback puts the unit on the structure, brings the structure down under
 * it and drops it with the structure.
 * <p>
 * An attack: the server names the structures an attack's damage brought down, which the rules bring down at the phase's
 * end. A strong hit pushes the structure only when its collapse follows that hit's animation.
 */
class GpuCollapseCauseTest {
    private static final int LOCAL = 0, OPPONENT = 1;
    /** Milliseconds the server takes after sending a collapse, as a live server's damage, reports and saves do. */
    private static long collapsePause;
    /** How long after the server's work the last play's first collapse was taken, in nanoseconds. */
    private static long collapseTaken;
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

    /**
     * A live server sends the mover's path long after the collapse it caused; the playback still shows the walk onto the
     * span, the span giving way under it and the fall, never the walk again from where it started.
     */
    @Test
    void aSlowServerStillShowsTheWalkOntoTheSpanBeforeItGivesWay() throws Exception {
        collapsePause = 1500;
        try {
            var frames = move("Atlas AS7-D.mtf", BESIDE, 5, path -> {
                path.addStep(MoveStepType.FORWARDS);
                path.addStep(MoveStepType.FORWARDS);
            }, Map.of());
            var step = comesDownUnder(frames, SPAN, 1);
            assertEquals(BESIDE, step.path().getFirst().coords(), "It steps on from where it was last shown");
            assertEquals(1, frames.moved().timeline().stream()
                  .filter(event -> event instanceof BoardScene.Movement move && move.entityId() == MOVER
                        && move.path().getLast().coords().equals(SPAN) && move.path().getLast().elevation() > 0).count(),
                  "It walks onto the span once: " + brief(frames.moved().timeline()));
            // The move's last update ends the wait (GpuBoardSource#endsHeldMove), long before the hold would run out.
            assertTrue(collapseTaken < 1_000_000_000L, "Shown " + collapseTaken / 1_000_000 + " ms after the server's work");
        } finally {
            collapsePause = 0;
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
     * An Atlas two hexes south of a light building (CF 10) brings it down with its AC/20 (20 a hit): the server names
     * the building on that shot. As the phase's last shot it pushes the building away from the Atlas; a medium laser
     * fired after it, whose animation comes between them, leaves the building coming straight down.
     */
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void aStrongHitPushesWhatItBringsDownOnlyRightBeforeItComesDown(boolean laserAfter) throws Exception {
        var atlas = new Coords(11, 10);
        var frames = play("Atlas AS7-D.mtf", atlas, 0, Map.of("1209", "bldg_elev:2;building:1:0;bldg_cf:10"),
              GamePhase.FIRING, (manager, game, attacker) ->
                    fire(manager, game, attacker, laserAfter ? List.of("ISAC20", "ISMediumLaser") : List.of("ISAC20")));
        var events = frames.moved().timeline();
        var collapse = events.stream().filter(event -> event instanceof BoardScene.TileTransition transition
              && transition.collapses(ROOF)).map(BoardScene.TileTransition.class::cast).findFirst().orElseThrow(
              () -> new AssertionError("The building comes down: " + events));
        var shots = events.subList(0, events.indexOf(collapse)).stream()
              .filter(BoardScene.Combat.class::isInstance).map(BoardScene.Combat.class::cast).toList();
        assertEquals(laserAfter ? 2 : 1, shots.size(), "Its shots play before it comes down: " + events);
        assertEquals(List.of(BoardLocation.of(ROOF, 0)), shots.getFirst().result().collapses(),
              "The server names what the AC/20 brought down");
        var fall = collapse.collapses().getFirst();
        if (laserAfter) {
            assertEquals(List.of(), shots.getLast().result().collapses());
            assertEquals(0, fall.strength(), "The laser's animation comes between: it comes straight down");
            assertNull(fall.from());
        } else {
            assertEquals(1, fall.strength(), .01f, "An AC/20 pushes at full strength");
            assertEquals(atlas, fall.from().coords(), "It falls away from the Atlas");
        }
    }

    /**
     * An AC/20 bursts a fuel tank at 1209 (magnitude 30: 30 damage in its hex, 20 a hex out), whose explosion brings down
     * the building beside it: the explosion takes both, each falling away from the tank, and the shot takes neither.
     */
    @Test
    void aFuelTanksExplosionTakesWhatItBringsDown() throws Exception {
        var beside = ROOF.translated(2);
        var frames = play("Atlas AS7-D.mtf", new Coords(11, 10), 0,
              Map.of(ROOF.getBoardNum(), "fuel_tank_cf:10;fuel_tank_elev:2;fuel_tank_magn:30;fuel_tank:1:0",
                    beside.getBoardNum(), "bldg_elev:2;building:1:0;bldg_cf:15"),
              GamePhase.FIRING, (manager, game, attacker) -> fire(manager, game, attacker, List.of("ISAC20")));
        var events = frames.moved().timeline();
        var shot = events.stream().filter(BoardScene.Combat.class::isInstance).map(BoardScene.Combat.class::cast)
              .findFirst().orElseThrow(() -> new AssertionError("The shot plays: " + brief(events)));
        var explosion = events.stream().filter(BoardScene.Detonation.class::isInstance).map(BoardScene.Detonation.class::cast)
              .findFirst().orElseThrow(() -> new AssertionError("The tank's explosion plays: " + brief(events)));
        assertTrue(events.indexOf(shot) < events.indexOf(explosion), "after the shot: " + brief(events));
        assertEquals(List.of(), shot.result().collapses(), "The AC/20 takes neither");
        assertEquals(megamek.common.NuclearDetonation.Kind.EXPLOSION, explosion.result().kind());
        assertEquals(ROOF, explosion.result().coords());
        assertEquals(java.util.Set.of(ROOF, beside), java.util.Set.copyOf(explosion.collapses().stream()
              .map(BoardScene.TileTransition.Collapse::coords).toList()), brief(events));
        for (var fall : explosion.collapses()) {
            assertEquals(ROOF, fall.from().coords(), fall.coords() + " falls away from the tank");
        }
    }

    /**
     * The Atlas clears the light woods at 1209, shot after shot: the attack that takes the woods down names on the
     * checkpoint after it every tree the hex had, to break away from the Atlas as its hit lands (GpuTerrain#breakTrees).
     */
    @Test
    void theTreesAnAttackTakesDownBreakAwayFromIt() throws Exception {
        var atlas = new Coords(11, 10);
        var frames = play("Atlas AS7-D.mtf", atlas, 0, Map.of(ROOF.getBoardNum(), "woods:1;foliage_elev:2"),
              GamePhase.FIRING, (manager, game, attacker) -> fire(manager, game, attacker,
                    List.of("ISAC20", "ISMediumLaser", "ISMediumLaser", "ISLRM20", "ISSRM6"), Targetable.TYPE_HEX_CLEAR,
                    new megamek.common.HexTarget(ROOF, game.getBoard(), Targetable.TYPE_HEX_CLEAR).getId()));
        var trees = frames.initial().scene().tile(ROOF).features().stream()
              .filter(feature -> feature.kind() == BoardScene.FeatureKind.TREE).toList();
        assertFalse(trees.isEmpty(), "The woods stand");
        var events = frames.moved().timeline();
        var broken = events.stream().filter(event -> event instanceof BoardScene.SceneUpdate update
              && !update.broken().isEmpty()).map(BoardScene.SceneUpdate.class::cast).toList();
        assertEquals(1, broken.size(), "One attack takes the woods down: " + brief(events));
        var update = broken.getFirst();
        assertTrue(events.subList(0, events.indexOf(update)).stream().anyMatch(BoardScene.Combat.class::isInstance),
              "After its shot");
        assertEquals(List.of(ROOF), update.broken().stream().map(BoardScene.Detonation.Felled::coords).toList());
        assertEquals(java.util.Set.copyOf(trees), java.util.Set.copyOf(update.broken().getFirst().trees()),
              "Every tree the woods had");
        assertEquals(atlas, update.hit().from().coords(), "Away from the Atlas");
    }

    /**
     * An Alamo strike at 1209: its crater (a hex's ring) takes the building at 1210 and its blast brings down the
     * building at 1211, two hexes out, and the board's bridges. What its own checkpoint shows gone comes down right after
     * its flash, the crater's building among them; the rest as the phase ends (TWGameManager#applyBuildingDamage). All of
     * it falls away from ground zero at full strength.
     */
    @Test
    void aNuclearStrikePushesWhatItBringsDownAwayFromGroundZero() throws Exception {
        var groundZero = ROOF;
        var crater = new Coords(11, 9);
        var blast = new Coords(11, 10);
        var frames = play("Atlas AS7-D.mtf", new Coords(4, 4), 0,
              Map.of("1210", "bldg_elev:2;building:1:0;bldg_cf:40", "1211", "bldg_elev:2;building:1:0;bldg_cf:40"),
              GamePhase.FIRING, (manager, game, unit) -> {
                  manager.addScheduledNuke(new int[] { groundZero.getX(), groundZero.getY(), 2 });
                  call(manager, "resolveScheduledNukes");
                  call(manager, "applyBuildingDamage");
              });
        var events = frames.moved().timeline();
        var strike = events.stream().filter(BoardScene.Detonation.class::isInstance).map(BoardScene.Detonation.class::cast)
              .findFirst().orElseThrow(() -> new AssertionError("The strike plays: " + brief(events)));
        var falls = strike.collapses();
        assertTrue(falls.stream().anyMatch(fall -> fall.coords().equals(crater)),
              "The crater's building comes down with the strike: " + brief(events));
        assertTrue(falls.stream().anyMatch(fall -> fall.coords().equals(blast)),
              "The blast's building, which the rules bring down at the phase's end, falls with the strike's front too: "
                    + brief(events));
        for (var fall : falls) {
            assertEquals(1, fall.strength(), .01f, fall.coords() + " comes down at full strength");
            assertEquals(groundZero, fall.from().coords(), fall.coords() + " falls away from ground zero");
        }
    }

    /** The timeline in short: each event's kind and, for a scene change, the hexes it brings down. */
    private static String brief(List<BoardScene.Animation> events) {
        return events.stream().map(event -> event instanceof BoardScene.TileTransition transition
              ? "TileTransition" + transition.collapses().stream().map(fall -> fall.coords().getBoardNum()).toList()
              : event.getClass().getSimpleName()).toList().toString();
    }

    /** The attacker fires {@code weapons} at the structure at 1209, and the firing phase resolves, to its end. */
    private static void fire(TWGameManager manager, Game game, Entity attacker, List<String> weapons) throws Exception {
        fire(manager, game, attacker, weapons, Targetable.TYPE_BUILDING, new BuildingTarget(ROOF, game.getBoard(), false).getId());
    }

    /** The attacker fires {@code weapons} at the target, and the firing phase resolves, to its end. */
    private static void fire(TWGameManager manager, Game game, Entity attacker, List<String> weapons, int targetType,
          int target) throws Exception {
        for (String weapon : weapons) {
            int mount = attacker.getWeaponList().stream()
                  .filter(gun -> gun.getType() == EquipmentType.get(weapon) && !gun.isRearMounted())
                  .findFirst().map(attacker::getEquipmentNum).orElseThrow();
            game.addAction(new WeaponAttackAction(MOVER, targetType, target, mount));
        }
        call(manager, "resolveOnlyWeaponAttacks");
        manager.assignAMS();
        call(manager, "handleAttacks");
        call(manager, "applyBuildingDamage");
    }

    private static void call(TWGameManager manager, String method) throws ReflectiveOperationException {
        Method declared = TWGameManager.class.getDeclaredMethod(method);
        declared.setAccessible(true);
        declared.invoke(manager);
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
        return play(unit, start, facing, structures, GamePhase.MOVEMENT, (manager, game, mover) -> {
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
            call(manager, "applyAffectedBuildings");
        });
    }

    /** The server's part of a phase, given its manager, its game and the unit. */
    private interface ServerWork {
        void run(TWGameManager manager, Game game, Entity unit) throws Exception;
    }

    /**
     * Plays {@code work}, the server's part of a {@code phase}, with {@code unit} at {@code start} facing {@code facing},
     * on the 16x17 Grassland with {@code structures} (hex number to terrain) added: the frames before it and after it,
     * up to the first scene change it brought.
     */
    private static Frames play(String unit, Coords start, int facing, Map<String, String> structures, GamePhase phase,
          ServerWork work) throws Exception {
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
        mover.loadAllWeapons();
        game.setupTeams();
        game.createVictoryConditions();
        game.setCurrentRound(1);
        game.addReports(new ArrayList<>(List.of(new Report(2000))));
        game.setPhase(phase);
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
                if (received.command() == megamek.common.net.enums.PacketCommand.BLDG_COLLAPSE) { Thread.sleep(collapsePause); }
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
            work.run(manager, game, mover);
            long done = System.nanoTime();
            collapseTaken = Long.MAX_VALUE;
            // A collapse no path follows waits out the source's hold; its timer then captures it. Each added structure
            // that comes down does so in a scene change of its own.
            List<Coords> added = structures.keySet().stream().map(hex ->
                  new Coords(Integer.parseInt(hex.substring(0, 2)) - 1, Integer.parseInt(hex.substring(2)) - 1)).toList();
            List<BoardScene.Animation> timeline = new ArrayList<>();
            GpuBoardSource.Frame moved = null;
            for (long deadline = System.nanoTime() + 5_000_000_000L; System.nanoTime() < deadline
                  && (timeline.stream().noneMatch(BoardScene.TileTransition.class::isInstance)
                        || !added.stream().allMatch(at -> timeline.stream().anyMatch(event ->
                              event instanceof BoardScene.TileTransition transition && transition.collapses(at)))); ) {
                Thread.sleep(50);
                SwingUtilities.invokeAndWait(() -> { });
                moved = source.takeFrame();
                timeline.addAll(moved.timeline());
                if (collapseTaken == Long.MAX_VALUE && moved.timeline().stream().anyMatch(BoardScene.TileTransition.class::isInstance)) {
                    collapseTaken = System.nanoTime() - done;
                }
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
