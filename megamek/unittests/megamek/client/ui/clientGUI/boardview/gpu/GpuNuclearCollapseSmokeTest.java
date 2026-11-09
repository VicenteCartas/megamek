/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.common.NuclearDetonation;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.event.GameNuclearDetonationEvent;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.interfaces.IEntityRemovalConditions;
import megamek.common.units.Terrains;
import megamek.server.Server;
import megamek.server.totalWarfare.TWGameManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * An Alamo strike on the dense 48x48 city board as its client gets it: the real server resolves it
 * (TWGameManager#resolveScheduledNukes, then the phase's end, applyBuildingDamage) and its packets, the completion naming
 * every structure it took to CF 0, reach the client as they do in a game. Every building it brings down falls where it
 * stood (GpuDestruction), each as the front reaches it (GpuArtilleryBlast#shockArrival), near ones first. Prints the
 * frame that took the city, the frame times through the wave (synchronized with glFinish, so CPU and GPU together) and
 * how closely the falls kept to the front; frames go to build/gpu-board-review/nuclear-collapse.
 */
@Tag("on-demand")
class GpuNuclearCollapseSmokeTest {
    private static final float FRAME = 1 / 60f;
    private static final File BOARD = new File("data/boards/unofficial/VictorMorson/48x48.board");
    /**
     * Frames after the flash the review captures: through the front every .05 playback seconds at 1x up to .2, then at
     * .3, .4, .6, .8, 1.6 and 3.
     */
    private static final int[] CAPTURES = { 6, 12, 18, 24, 36, 48, 72, 96, 192, 360 };
    /** Playback seconds a frame at 1x. */
    private static final float PLAYBACK_FRAME = (float) (FRAME * UnitMotion.Speed.NORMAL.rate);

    /**
     * An Alamo, or a Game Master's orbital bombardment of 1,000,000 damage over 10 hexes; megamek.gpu.strikes
     * (comma-separated: nuclear, orbital) picks them, else both.
     */
    @ParameterizedTest
    @ValueSource(strings = { "nuclear", "orbital" })
    void aStrikeOnACityFallsWithItsFront(String kind) throws Exception {
        String chosen = System.getProperty("megamek.gpu.strikes", "");
        Assumptions.assumeTrue(chosen.isBlank() || List.of(chosen.split(",")).contains(kind));
        boolean orbital = kind.equals("orbital");
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), kind + "-collapse");
        Files.createDirectories(output.toPath());
        Board before = board();
        Coords groundZero = densest(before);
        var server = new ResolutionServer();
        server.getGame().setBoard(board());
        server.getGame().addPlayer(0, new Player(0, "Nuclear review"));
        server.getGame().initializeRulesManager(OptionsConstants.RULES_CORE);
        if (orbital) {
            server.addScheduledOrbitalBombardment(new megamek.server.props.OrbitalBombardment.Builder()
                  .x(groundZero.getX()).y(groundZero.getY()).radius(10).damage(1_000_000).build());
        } else {
            server.addScheduledNuke(new int[] { groundZero.getX(), groundZero.getY(), 2 });
        }
        Map<BoardLocation, megamek.common.Hex> strike, phaseEnd;
        // The rules' packets go through the server's animation capture, which names on the strike's completion what it
        // brought down; only the transport records them.
        var transport = mock(Server.class, invocation -> {
            if ("send".equals(invocation.getMethod().getName()) && invocation.getArguments().length == 1
                  && invocation.getArgument(0) instanceof Packet packet) { server.sent(packet); }
            if ("getGame".equals(invocation.getMethod().getName())) { return server.getGame(); }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        try (var servers = mockStatic(Server.class)) {
            servers.when(Server::getServerInstance).thenReturn(transport);
            call(server, orbital ? "resolveScheduledOrbitalBombardments" : "resolveScheduledNukes");
            strike = server.updates();
            call(server, "applyBuildingDamage");
            phaseEnd = server.updates();
        }
        assertFalse(server.collapsed.isEmpty(), "The strike's blast brings buildings down as the phase ends");
        assertFalse(server.completion.collapses().isEmpty(), "The strike's completion names what it brought down");
        // Every building it brings down, in its crater or as the phase ends: the city's kit buildings.
        Set<Coords> falls = new HashSet<>(server.collapsed);
        strike.forEach((at, hex) -> {
            if (structure(before.getHex(at.coords())) && !structure(hex)) { falls.add(at.coords()); }
        });
        falls.removeIf(at -> !before.getHex(at).containsTerrain(Terrains.BUILDING));
        float radius = GpuArtilleryBlast.nuclearRadius(server.completion.stats());
        var failure = new AtomicReference<Throwable>();
        List<String> report = new ArrayList<>();
        try (var fixture = GpuBoardFixture.create(before)) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.setPhase(GamePhase.FIRING);
                fixture.game.removeEntity(fixture.entity.getId(), IEntityRemovalConditions.REMOVE_NEVER_JOINED);
                fixture.source.refresh();
            });
            var configuration = GpuBoardWindow.configuration(false);
            configuration.setWindowedMode(1280, 800);
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                private final long deadline = System.nanoTime() + 600_000_000_000L;
                /** The frame each building began to give way. */
                private final Map<Coords, Integer> starts = new HashMap<>();
                private final List<Double> waveMs = new ArrayList<>();
                private Graphics fixedGraphics;
                private int stage, frames, contact = -1;
                private long last, triggered, status;
                private double takeMs;
                /** This frame's stages as they began (renderStage), and the slowest wave frame's, in ms a stage. */
                private final List<String> stageNames = new ArrayList<>();
                private final List<Long> stageTimes = new ArrayList<>();
                private String slowest = "", takeStages = "";
                /** At the flash: the falling parts, and how many of them fall in fragments. */
                private int[] fallParts = { 0, 0 };
                private double slowestMs;
                /** Programs linked after loading: at the flash, before this frame, and over the wave. */
                private int linksAtFlash = -1, linksBefore;
                /** The most hexes whose trees were felled at once. */
                private int felledHexes;

                /** This frame's programs linked and each stage over 2 ms: its time to the next, the last to the frame's end. */
                private String stages(long now, int links) {
                    StringBuilder result = new StringBuilder((links - linksBefore) + " programs linked in it:");
                    long from = last;
                    String name = "before the first stage";
                    for (int index = 0; index <= stageNames.size(); index++) {
                        long to = index < stageTimes.size() ? stageTimes.get(index) : now;
                        if (to - from > 2_000_000L) { result.append(' ').append(name).append(' ').append((to - from) / 1_000_000L); }
                        if (index < stageNames.size()) { name = stageNames.get(index); from = to; }
                    }
                    return result.toString();
                }

                @Override
                void renderStage(String name) {
                    stageNames.add(name);
                    stageTimes.add(System.nanoTime());
                }

                @Override
                public void create() {
                    fixedGraphics = spy(Gdx.graphics);
                    doReturn(FRAME).when(fixedGraphics).getDeltaTime();
                    Gdx.graphics = fixedGraphics;
                    super.create();
                    boardCamera.setIsometric(true);
                }

                @Override
                public void render() {
                    try {
                        Gdx.graphics = fixedGraphics;
                        super.render();
                        Gdx.gl.glFinish();
                        assertTrue(System.nanoTime() < deadline, "The strike's collapses must play out");
                        if (GpuBoardTestUi.loading(this)) { return; }
                        step();
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }

                private void step() {
                    var terrain = (GpuTerrain) field(this, "terrain");
                    long now = System.nanoTime();
                    frames++;
                    if (stage == 0) {
                        boardCamera.center(BoardGeometry.center(groundZero, before.getHex(groundZero).getLevel()));
                        frames = 0;
                        stage = 1;
                    } else if (stage == 1 && frames >= 60 && !terrain.fragmentsPending()) {
                        capture(output, "0-before");
                        SwingUtilities.invokeLater(() -> {
                            fixture.game.processGameEvent(new GameNuclearDetonationEvent(fixture.game, server.detonation, false));
                            fixture.game.getBoard().setHexes(strike);
                            fixture.game.processGameEvent(new GameNuclearDetonationEvent(fixture.game, server.completion, true));
                            fixture.game.getBoard().collapseBuilding(new Vector<>(server.collapsed));
                            fixture.game.getBoard().setHexes(phaseEnd);
                            fixture.source.refresh();
                        });
                        frames = 0;
                        stage = 2;
                        triggered = status = now;
                    } else if (stage == 2) {
                        double ms = (now - last) / 1e6;
                        if (now - status > 5_000_000_000L) {
                            // Where a slow strike waits: its blast, the pieces being cut or falling, or the terrain.
                            status = now;
                            long seconds = (now - triggered) / 1_000_000_000L;
                            var effects = (GpuAttackEffects) field(this, "attackEffects");
                            var playback = (UnitPlayback) field(this, "playback");
                            System.out.println("t=" + seconds + "s frames=" + frames + " blasts=" + effects.artilleryBlastCount()
                                  + " taken=" + falls.stream().filter(terrain::fallTaken).count() + " started="
                                  + starts.size() + "/" + falls.size() + " felled=" + terrain.felledHexes()
                                  + " terrainBusy=" + GpuBoardTestUi.busy(this)
                                  + " transition=" + (playback.transition() == null ? "none"
                                        : playback.transition().collapses().size() + " collapses"));
                            if (seconds % 20 < 5) { capture(output, "t" + seconds); }
                        }
                        int links = GpuGlsl.compilesAfterLoading() + GpuGlsl.deferredAfterLoading();
                        if (contact < 0 && falls.stream().anyMatch(terrain::fallTaken)) {
                            // The flash: the frame that took the city from its chunks.
                            contact = frames;
                            takeMs = ms;
                            fallParts = terrain.fallParts();
                            takeStages = stages(now, links);
                            linksAtFlash = links;
                        }
                        for (Coords at : falls) {
                            if (!starts.containsKey(at) && terrain.fallStarted(at)) { starts.put(at, frames); }
                        }
                        felledHexes = Math.max(felledHexes, terrain.felledHexes());
                        if (contact >= 0 && frames > contact && frames - contact <= CAPTURES[CAPTURES.length - 1]) {
                            waveMs.add(ms);
                            if (ms > slowestMs) {
                                slowestMs = ms;
                                slowest = "frame " + (frames - contact) + " after the flash, " + stages(now, links);
                            }
                        }
                        for (int index = 0; contact >= 0 && index < CAPTURES.length; index++) {
                            if (frames - contact == CAPTURES[index]) { capture(output, (index + 1) + "-" + CAPTURES[index]); }
                        }
                        if (contact >= 0 && frames - contact > CAPTURES[CAPTURES.length - 1]
                              && falls.stream().noneMatch(terrain::fallTaken) && !GpuBoardTestUi.busy(this)) {
                            capture(output, "after");
                            summarize();
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                            Gdx.app.exit();
                        }
                    }
                    last = now;
                    linksBefore = GpuGlsl.compilesAfterLoading() + GpuGlsl.deferredAfterLoading();
                    stageNames.clear();
                    stageTimes.clear();
                }

                private void summarize() {
                    assertTrue(starts.size() >= falls.size() * 9 / 10,
                          "The buildings it brings down fall: " + starts.size() + " of " + falls.size());
                    // Each as the front reaches it: its arrival in blast seconds, the playback's half.
                    var centre = BoardGeometry.center(groundZero, 0);
                    List<Double> lateness = new ArrayList<>();
                    float near = 0, far = 0;
                    int nearCount = 0, farCount = 0;
                    for (var entry : starts.entrySet()) {
                        var middle = BoardGeometry.center(entry.getKey(), 0);
                        float distance = com.badlogic.gdx.math.Vector2.dst(middle.x, middle.y, centre.x, centre.y);
                        float arrival = GpuArtilleryBlast.shockArrival(distance, radius) / GpuArtilleryBlast.SECONDS_PER_PLAYBACK;
                        lateness.add((double) Math.abs(entry.getValue() - contact - arrival / PLAYBACK_FRAME));
                        int hexes = groundZero.distance(entry.getKey());
                        if (hexes <= 4) { near += entry.getValue(); nearCount++; }
                        if (hexes >= 12) { far += entry.getValue(); farCount++; }
                    }
                    lateness.sort(null);
                    assertTrue(percentile(lateness, .95) <= 2, "The falls keep to the front within two frames: " + lateness);
                    assertTrue(nearCount == 0 || farCount == 0 || near / nearCount < far / farCount,
                          "Near buildings fall before far ones");
                    var sorted = new ArrayList<>(waveMs);
                    sorted.sort(null);
                    report.add("ground zero " + groundZero.getBoardNum() + "; buildings the crater took: " + taken(before, strike)
                          + ", brought down as the phase ended: " + server.collapsed.size() + "; listed on the completion: "
                          + server.completion.collapses().size() + "; falling: " + starts.size() + " of " + falls.size());
                    report.add("frames off the front: median " + percentile(lateness, .5) + ", p95 " + percentile(lateness, .95)
                          + ", max " + percentile(lateness, 1));
                    report.add("frame ms through the wave: median " + percentile(sorted, .5) + ", p95 " + percentile(sorted, .95)
                          + ", max " + percentile(sorted, 1) + "; the frame that took the city: " + Math.round(takeMs) + " ms, "
                          + takeStages);
                    report.add("the slowest wave frame's stages over 2 ms: " + slowest);
                    report.add("hexes whose trees the strike felled: " + felledHexes);
                    report.add("falling parts: " + fallParts[0] + ", in fragments: " + fallParts[1] + "; fragment meshes "
                          + ((GpuTerrain) field(this, "terrain")).fragmentBytes() / 1024 + " KiB");
                    report.add("programs linked from the flash on: "
                          + (GpuGlsl.compilesAfterLoading() + GpuGlsl.deferredAfterLoading() - linksAtFlash));
                }
            }, configuration);
        }
        report.forEach(System.out::println);
        if (failure.get() != null) { throw new AssertionError("Nuclear collapse review failed", failure.get()); }
    }

    private static long percentile(List<Double> sorted, double share) {
        return Math.round(sorted.get((int) Math.min(sorted.size() - 1, Math.floor(share * sorted.size()))));
    }

    /** The structure hexes of {@code board} that {@code updates} leave without their structure. */
    private static long taken(Board board, Map<BoardLocation, megamek.common.Hex> updates) {
        return updates.entrySet().stream().filter(update -> structure(board.getHex(update.getKey().coords()))
              && !structure(update.getValue())).count();
    }

    private static boolean structure(megamek.common.Hex hex) {
        return hex != null && (hex.containsTerrain(Terrains.BUILDING) || hex.containsTerrain(Terrains.BRIDGE)
              || hex.containsTerrain(Terrains.FUEL_TANK));
    }

    private static void capture(File output, String name) {
        GpuBoardTestUi.capture(new File(output, name + ".png"));
    }

    private static Board board() throws Exception {
        var board = new Board();
        board.load(BOARD);
        return board;
    }

    /** The hex with the most building hexes within four hexes of it. */
    private static Coords densest(Board board) {
        Coords best = null;
        int most = -1;
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                var at = new Coords(x, y);
                int count = 0;
                for (int range = 0; range <= 4; range++) {
                    for (Coords near : at.allAtDistance(range)) {
                        var hex = board.getHex(near);
                        if (hex != null && hex.containsTerrain(Terrains.BUILDING)) { count++; }
                    }
                }
                if (count > most) {
                    most = count;
                    best = at;
                }
            }
        }
        return best;
    }

    private static void call(TWGameManager manager, String method) throws ReflectiveOperationException {
        Method declared = TWGameManager.class.getDeclaredMethod(method);
        declared.setAccessible(true);
        declared.invoke(manager);
    }

    /** The real rules resolution; only its transport is replaced, recording what it sends. */
    private static final class ResolutionServer extends TWGameManager {
        private final Set<Coords> changed = new HashSet<>();
        final List<Coords> collapsed = new ArrayList<>();
        /** The strike as it starts, and as it completes with what it brought down. */
        NuclearDetonation detonation, completion;

        @Override
        public void sendChangedHex(Coords coords, int boardId) { changed.add(coords); }

        @SuppressWarnings("unchecked")
        void sent(Packet packet) {
            if (packet.command() == PacketCommand.NUCLEAR_DETONATION) {
                var strike = (NuclearDetonation) packet.getObject(0);
                if (Boolean.TRUE.equals(packet.getObject(1))) { completion = strike; } else { detonation = strike; }
            }
            if (packet.command() == PacketCommand.BLDG_COLLAPSE) { collapsed.addAll((Vector<Coords>) packet.getObject(0)); }
        }

        /** The hexes it changed since the last call, as the client receives them. */
        Map<BoardLocation, megamek.common.Hex> updates() {
            Map<BoardLocation, megamek.common.Hex> result = new HashMap<>();
            for (Coords coords : changed) {
                result.put(BoardLocation.of(coords, getGame().getBoard().getBoardId()), getGame().getBoard().getHex(coords).duplicate());
            }
            changed.clear();
            return result;
        }
    }
}
