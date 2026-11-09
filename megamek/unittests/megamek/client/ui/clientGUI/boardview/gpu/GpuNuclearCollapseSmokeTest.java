/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
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
import megamek.server.totalWarfare.TWGameManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * An Alamo strike on the dense 48x48 city board as its client gets it: the real server resolves it
 * (TWGameManager#resolveScheduledNukes, then the phase's end, applyBuildingDamage), and its changed hexes and collapses
 * reach the client's board as their packets do. What it brings down breaks into pieces that fly away from ground zero,
 * as many as one scene change breaks (GpuTerrain#MAX_COLLAPSES, those nearest its middle). Prints each collapse's cut
 * time, the pieces' memory and the frame times (synchronized with glFinish, so CPU and GPU together); frames go to
 * build/gpu-board-review/nuclear-collapse.
 */
@Tag("on-demand")
class GpuNuclearCollapseSmokeTest {
    private static final float FRAME = 1 / 60f;
    private static final File BOARD = new File("data/boards/unofficial/VictorMorson/48x48.board");
    /** Frames after the first pieces fall that the review captures, and the one that checks where they went. */
    private static final int[] CAPTURES = { 0, 30, 90, 180 };
    private static final int CHECK = 90;

    /** One structure breaking: when the terrain began reading it, when its cut was ready and its pieces fell. */
    private static final class Break {
        long read, cut = -1, started = -1;
        int frame = -1;
        long bytes;
        Vector3 standing;
    }

    @Test
    void aStrikeOnACityThrowsWhatItBringsDownAwayFromGroundZero() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "nuclear-collapse");
        Files.createDirectories(output.toPath());
        Board before = board();
        Coords groundZero = densest(before);
        var server = new ResolutionServer();
        server.getGame().setBoard(board());
        server.getGame().addPlayer(0, new Player(0, "Nuclear review"));
        server.getGame().initializeRulesManager(OptionsConstants.RULES_CORE);
        server.addScheduledNuke(new int[] { groundZero.getX(), groundZero.getY(), 2 });
        call(server, "resolveScheduledNukes");
        var strike = server.updates();
        call(server, "applyBuildingDamage");
        var phaseEnd = server.updates();
        assertFalse(server.collapsed.isEmpty(), "The strike's blast brings buildings down as the phase ends");
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
                private final long deadline = System.nanoTime() + 300_000_000_000L;
                private final Map<GpuCollapse, Break> breaks = new IdentityHashMap<>();
                private final List<Double> frameMs = new ArrayList<>();
                /** How far each broken hex's pieces moved away from ground zero by CHECK frames after they fell. */
                private final List<Float> pushes = new ArrayList<>();
                private Graphics fixedGraphics;
                private int stage, frames, firstFall = -1;
                private long last, triggered, status;
                private double readFrameMs;

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
                    } else if (stage == 1 && frames == 60) {
                        capture(output, "0-before");
                        SwingUtilities.invokeLater(() -> {
                            fixture.game.processGameEvent(new GameNuclearDetonationEvent(fixture.game, server.detonation, false));
                            fixture.game.getBoard().setHexes(strike);
                            fixture.game.processGameEvent(new GameNuclearDetonationEvent(fixture.game, server.detonation, true));
                            fixture.game.getBoard().collapseBuilding(new Vector<>(server.collapsed));
                            fixture.game.getBoard().setHexes(phaseEnd);
                            fixture.source.refresh();
                        });
                        frames = 0;
                        stage = 2;
                        triggered = status = now;
                    } else if (stage == 2) {
                        double ms = (now - last) / 1e6;
                        frameMs.add(ms);
                        if (now - status > 5_000_000_000L) {
                            // Where a slow strike waits: its blast, the pieces being cut or falling, or the terrain.
                            status = now;
                            long seconds = (now - triggered) / 1_000_000_000L;
                            var effects = (GpuAttackEffects) field(this, "attackEffects");
                            var playback = (UnitPlayback) field(this, "playback");
                            System.out.println("t=" + seconds + "s frames=" + frames + " blasts=" + effects.artilleryBlastCount()
                                  + " collapses=" + terrain.collapses().size() + " ready="
                                  + terrain.collapses().stream().filter(GpuCollapse::ready).count() + " falling="
                                  + terrain.collapses().stream().filter(collapse -> collapse.instance() != null).count()
                                  + " seen=" + breaks.size() + " terrainBusy=" + GpuBoardTestUi.busy(this)
                                  + " transition=" + (playback.transition() == null ? "none"
                                        : playback.transition().collapses().size() + " collapses"));
                            if (seconds % 20 < 5) { capture(output, "t" + seconds); }
                        }
                        boolean read = false;
                        for (GpuCollapse collapse : terrain.collapses()) {
                            var known = breaks.get(collapse);
                            if (known == null) {
                                known = new Break();
                                known.read = now;
                                breaks.put(collapse, known);
                                read = true;
                            }
                            if (known.cut < 0 && collapse.ready()) { known.cut = now; }
                            if (known.started < 0 && collapse.instance() != null) {
                                known.started = now;
                                known.frame = frames;
                                known.standing = centroid(collapse);
                                for (Mesh mesh : collapse.instance().model.meshes) {
                                    known.bytes += (long) mesh.getNumVertices() * mesh.getVertexSize();
                                }
                                if (firstFall < 0) { firstFall = frames; }
                            }
                            if (known.frame >= 0 && frames - known.frame == CHECK) {
                                // Away from ground zero: its pieces' mean position moved out along the line from it.
                                var drift = centroid(collapse).sub(known.standing);
                                var out = new Vector3(known.standing).sub(BoardGeometry.center(groundZero, 0));
                                out.z = 0;
                                out.nor();
                                var fall = fall(terrain, collapse);
                                System.out.println("moved " + drift + " out " + out + " along " + drift.dot(out)
                                      + (fall == null ? "" : " strength " + fall.strength() + " from "
                                            + (fall.from() == null ? "nothing" : fall.from().coords().getBoardNum()) + " at "
                                            + fall.coords().getBoardNum()));
                                pushes.add(drift.dot(out));
                            }
                        }
                        if (read) { readFrameMs = Math.max(readFrameMs, ms); }
                        for (int index = 0; firstFall >= 0 && index < CAPTURES.length; index++) {
                            if (frames - firstFall == CAPTURES[index]) { capture(output, (index + 1) + "-" + CAPTURES[index]); }
                        }
                        if (firstFall >= 0 && frames - firstFall > CHECK && terrain.collapses().isEmpty()
                              && !GpuBoardTestUi.busy(this)) {
                            capture(output, "9-after");
                            summarize(terrain);
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                            Gdx.app.exit();
                        }
                    }
                    last = now;
                }

                private void summarize(GpuTerrain terrain) {
                    assertFalse(breaks.isEmpty(), "The strike's structures break into pieces");
                    assertTrue(pushes.stream().filter(away -> away > 0).count() > pushes.size() / 2,
                          "Most broken structures' pieces move away from ground zero: " + pushes);
                    List<Double> cuts = new ArrayList<>(), waits = new ArrayList<>();
                    long bytes = 0;
                    for (Break broken : breaks.values()) {
                        if (broken.cut >= 0) { cuts.add((broken.cut - broken.read) / 1e6); }
                        if (broken.started >= 0) { waits.add((broken.started - broken.read) / 1e6); }
                        bytes += broken.bytes;
                    }
                    var sorted = new ArrayList<>(frameMs);
                    sorted.sort(null);
                    report.add("ground zero " + groundZero.getBoardNum() + "; structure hexes the crater took: "
                          + taken(before, strike) + ", brought down as the phase ended: " + server.collapsed.size());
                    report.add("broken into pieces: " + breaks.size() + "; piece memory " + bytes / 1024 + " KiB in all");
                    report.add("cut (read to ready) ms: " + spread(cuts) + "; read to falling ms: " + spread(waits));
                    report.add("frame ms: median " + percentile(sorted, .5) + ", p95 " + percentile(sorted, .95) + ", max "
                          + percentile(sorted, 1) + "; the frame that read the structures: " + Math.round(readFrameMs) + " ms");
                }
            }, configuration);
        }
        report.forEach(System.out::println);
        if (failure.get() != null) { throw new AssertionError("Nuclear collapse review failed", failure.get()); }
    }

    private static String spread(List<Double> values) {
        if (values.isEmpty()) { return "none"; }
        var sorted = new ArrayList<>(values);
        sorted.sort(null);
        return "min " + percentile(sorted, 0) + ", median " + percentile(sorted, .5) + ", max " + percentile(sorted, 1);
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

    /** The mean of its pieces' middles, where they are now on the board. */
    private static Vector3 centroid(GpuCollapse collapse) {
        var instance = collapse.instance();
        var sum = new Vector3();
        int count = 0;
        for (var piece : instance.nodes.first().getChildren()) {
            if (piece.parts.isEmpty()) { continue; }
            var placed = new com.badlogic.gdx.math.Matrix4(instance.transform).mul(piece.globalTransform);
            sum.add(new Vector3(piece.parts.first().meshPart.center).mul(placed));
            count++;
        }
        return sum.scl(1f / Math.max(1, count));
    }

    /** The scene change's record of the structure {@code collapse} breaks, as the terrain keeps it. */
    private static BoardScene.TileTransition.Collapse fall(GpuTerrain terrain, GpuCollapse collapse) {
        List<?> collapsing = field(terrain, "collapsing");
        for (Object entry : collapsing) {
            if (field(entry, "collapse") != collapse) { continue; }
            BoardScene.TileTransition transition = field(entry, "transition");
            Coords at = field(entry, "coords");
            return transition.collapses().stream().filter(fall -> fall.coords().equals(at)).findFirst().orElse(null);
        }
        return null;
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
        NuclearDetonation detonation;

        @Override
        public void sendChangedHex(Coords coords, int boardId) { changed.add(coords); }

        @Override
        @SuppressWarnings("unchecked")
        public void send(Packet packet) {
            if (packet.command() == PacketCommand.NUCLEAR_DETONATION) { detonation = (NuclearDetonation) packet.getObject(0); }
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
