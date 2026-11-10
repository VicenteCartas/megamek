/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Structures coming down as a game brings them down (Board.collapseBuilding, then the source's capture, the playback's
 * collapse and the terrain's swap): a medium, a heavy, a light kit building and a fuel tank fall where they stood on
 * the GPU (GpuDestruction), a river bridge's span and an authored building in both its hexes break into cut pieces and
 * drop; the heavy one, hit by an AC/20 from the west, falls away east. Heavy woods an attack from the west clears break
 * into their pieces, thrown east (GpuTerrain#breakTrees), as do woods drawn as a container yard and as crystals. Frames go
 * to build/gpu-board-review/building-collapse.
 */
@Tag("on-demand")
class GpuBuildingCollapseSmokeTest {
    private static final float FRAME = 1 / 60f;
    /**
     * Frames after the swap the review captures, at 1x (120 to a playback second): the fall over about .3 to 2 real
     * seconds, then its pile on the rubble risen under it, until just before it sinks into it.
     */
    private static final int[] CAPTURES = { 0, 18, 36, 54, 78, 108, 130, 150, 170, 195, 220 };

    /**
     * A structure brought down at {@code at} and, in the same capture, at {@code more}; with {@code woods}, its woods
     * cleared instead.
     */
    private record Scenario(String name, Coords at, boolean ac20, boolean woods, Coords... more) {
        Scenario(String name, Coords at, boolean ac20, Coords... more) { this(name, at, ac20, false, more); }
    }

    private static final List<Scenario> ALL = List.of(
          new Scenario("medium", new Coords(2, 2), false),
          new Scenario("heavy-ac20", new Coords(11, 3), true),
          new Scenario("light", new Coords(11, 12), false),
          new Scenario("fuel-tank", new Coords(3, 12), false),
          new Scenario("bridge", new Coords(6, 8), false),
          new Scenario("woods", new Coords(13, 8), true, true),
          new Scenario("woods-yard", new Coords(5, 0), true, true),
          new Scenario("woods-crystal", new Coords(13, 0), true, true),
          new Scenario("authored", new Coords(10, 8), false, new Coords(10, 7)));
    /** Those named in megamek.gpu.collapseScenarios (comma-separated), else all of them. */
    private static final List<Scenario> SCENARIOS = ALL.stream().filter(scenario ->
          System.getProperty("megamek.gpu.collapseScenarios", "").isBlank()
                || List.of(System.getProperty("megamek.gpu.collapseScenarios").split(",")).contains(scenario.name())).toList();
    /** The authored building, placed on level grass at the last scenario's hex, standing in its two hexes. */
    private static final String AUTHORED = "buildings/authored/classic-church";

    @Test
    void structuresBreakWhereTheyStoodAndComeDown() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "building-collapse");
        Files.createDirectories(output.toPath());
        var board = board(output);
        var failure = new AtomicReference<Throwable>();
        List<String> report = new ArrayList<>();
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                // A collapse at the end of the firing phase: the movement phase holds one back for its mover's path.
                fixture.game.setPhase(GamePhase.FIRING);
                fixture.entity.setPosition(new Coords(8, 15));
                fixture.source.refresh();
            });
            var configuration = GpuBoardWindow.configuration(false);
            configuration.setWindowedMode(1280, 800);
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                private final long deadline = System.nanoTime() + 600_000_000_000L;
                private Graphics fixedGraphics;
                private int scenario, stage, frames, swapFrame = -1, linksBefore;
                private Vector3 standing;

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
                        assertTrue(System.nanoTime() < deadline, "The collapses must play out");
                        if (GpuBoardTestUi.loading(this)) { return; }
                        if (scenario == SCENARIOS.size()) {
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                            Gdx.app.exit();
                            return;
                        }
                        step(SCENARIOS.get(scenario));
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }

                private void step(Scenario current) {
                    var terrain = (GpuTerrain) field(this, "terrain");
                    float level = fixture.game.getBoard().getHex(current.at()).getLevel();
                    frames++;
                    if (stage == 0) {
                        boardCamera.center(BoardGeometry.center(current.at(), level));
                        // Zooming multiplies: once, so every structure is seen alike.
                        if (scenario == 0) { boardCamera.zoom(.3f); }
                        frames = 0;
                        stage = 1;
                    } else if (stage == 1 && current.woods() && frames == 20) {
                        // As an attack's checkpoint arrives ahead of its hit: the trees' models split meanwhile.
                        var shown = (BoardScene) field(this, "scene");
                        terrain.prepareBreak(List.of(new BoardScene.Detonation.Felled(current.at(), shown.tile(current.at())
                              .features().stream().filter(BoardFeatures::cover).toList())));
                    } else if (stage == 1 && frames >= (current.woods() ? 60 : 20) && !terrain.fragmentsPending()) {
                        capture(output, current.name() + "-0-before");
                        linksBefore = GpuGlsl.compilesAfterLoading() + GpuGlsl.deferredAfterLoading();
                        SwingUtilities.invokeLater(() -> {
                            if (current.woods()) {
                                // As an attack from the west clearing them: its hit, then the woods gone (GpuBoardSource).
                                GpuTestSupport.set(fixture.source, "treeHexes", java.util.Set.of(current.at()));
                                GpuTestSupport.set(fixture.source, "treeHit", new BoardScene.TileTransition.Hit(1,
                                      new BoardScene.Waypoint(new Coords(current.at().getX() - 6, current.at().getY()), level, 0)));
                                GpuTestSupport.set(fixture.source, "treeHitRound", fixture.game.getRoundCount());
                                GpuTestSupport.set(fixture.source, "treeHitPhase", fixture.game.getPhase());
                                var cleared = fixture.game.getBoard().getHex(current.at()).duplicate();
                                cleared.removeTerrain(Terrains.WOODS);
                                cleared.removeTerrain(Terrains.FOLIAGE_ELEV);
                                cleared.addTerrain(new megamek.common.units.Terrain(Terrains.ROUGH, 1));
                                fixture.game.getBoard().setHex(current.at(), cleared);
                                fixture.source.refresh();
                                return;
                            }
                            if (current.ac20()) {
                                @SuppressWarnings("unchecked")
                                var hits = (Map<Coords, BoardScene.TileTransition.Hit>) field(fixture.source, "structureHits");
                                hits.put(current.at(), new BoardScene.TileTransition.Hit(1,
                                      new BoardScene.Waypoint(new Coords(current.at().getX() - 6, current.at().getY()), level, 0)));
                            }
                            fixture.game.getBoard().collapseBuilding(current.at());
                            for (Coords also : current.more()) { fixture.game.getBoard().collapseBuilding(also); }
                            fixture.source.refresh();
                        });
                        frames = 0;
                        stage = 2;
                    } else if (stage == 2) {
                        var falling = terrain.collapses().stream().filter(collapse -> collapse.instance() != null).findFirst();
                        if (current.woods() ? terrain.fallParts()[0] == 0 : falling.isEmpty() && !terrain.fallStarted(current.at())) {
                            assertTrue(frames < 600, current.name() + " gives way to its pieces within ten seconds");
                            return;
                        }
                        swapFrame = frames;
                        standing = falling.map(collapse -> bounds(collapse).getCenter(new Vector3())).orElse(null);
                        int[] parts = terrain.fallParts();
                        report.add(current.name() + (standing == null ? ": falls on the GPU" : ": cut into pieces")
                              + " " + frames + " frames after it was brought down, " + parts[1] + " of its " + parts[0]
                              + " parts in fragments");
                        stage = 3;
                    }
                    if (stage == 3) {
                        int since = frames - swapFrame;
                        for (int index = 0; index < CAPTURES.length; index++) {
                            if (since == CAPTURES[index]) { capture(output, current.name() + "-" + (index + 1) + "-" + since); }
                        }
                        if (since == CAPTURES[CAPTURES.length - 1] && current.woods()) {
                            stage = 4;
                        } else if (since == CAPTURES[CAPTURES.length - 1] && standing == null) {
                            assertTrue(terrain.fallTaken(current.at()), current.name() + "'s pile still lies there " + since + " frames on");
                            stage = 4;
                        } else if (since == CAPTURES[CAPTURES.length - 1]) {
                            var falling = terrain.collapses().stream().filter(collapse -> collapse.instance() != null).findFirst();
                            assertTrue(falling.isPresent(), current.name() + "'s pieces still lie there " + since + " frames on");
                            var box = bounds(falling.get());
                            var drift = box.getCenter(new Vector3()).sub(standing);
                            report.add(current.name() + ": pieces' middle moved " + drift + ", top now " + box.max.z);
                            assertTrue(drift.z < 0, current.name() + " came down: " + drift);
                            if (current.ac20()) { assertTrue(drift.x > 5, "The AC/20 from the west throws it east: " + drift); }
                            stage = 4;
                        }
                    } else if (stage == 4) {
                        if (!terrain.collapses().isEmpty() || terrain.fallTaken(current.at()) || terrain.fallParts()[0] > 0) {
                            assertTrue(frames - swapFrame < 1800, current.name() + "'s pieces sink away");
                            return;
                        }
                        capture(output, current.name() + "-9-after");
                        report.add(current.name() + ": " + (GpuGlsl.compilesAfterLoading() + GpuGlsl.deferredAfterLoading()
                              - linksBefore) + " programs linked from its fall on");
                        assertTrue(!fixture.game.getBoard().getHex(current.at()).containsTerrain(Terrains.BUILDING)
                              && !fixture.game.getBoard().getHex(current.at()).containsTerrain(Terrains.WOODS));
                        scenario++;
                        stage = 0;
                    }
                }
            }, configuration);
        }
        report.forEach(System.out::println);
        if (failure.get() != null) { throw new AssertionError("Building collapse review failed", failure.get()); }
    }

    private static BoundingBox bounds(GpuCollapse collapse) {
        return collapse.instance().calculateBoundingBox(new BoundingBox()).mul(collapse.instance().transform);
    }

    private static void capture(File output, String name) {
        GpuBoardTestUi.capture(new File(output, name + ".png"));
    }

    /**
     * The 16x17 Grassland with buildings and a fuel tank where its grass is level, besides its river bridge, and an
     * authored building placed as the editor places one.
     */
    private static megamek.common.board.Board board(File output) throws Exception {
        var source = new File("data/boards/AGoAC Maps/16x17 Grassland 2.board");
        var lines = Files.readAllLines(source.toPath());
        Map<String, String> structures = Map.of(
              "0303", "bldg_elev:3;building:2:0;bldg_cf:40",
              "1204", "bldg_elev:4;building:3:0;bldg_cf:90",
              "1213", "bldg_elev:2;building:1:0;bldg_cf:15",
              "0413", "fuel_tank_cf:45;fuel_tank_elev:2;fuel_tank_magn:80;fuel_tank:3:0",
              "1409", "woods:2;foliage_elev:2",
              "0601", "woods:2;foliage_elev:2",
              "1401", "woods:2;foliage_elev:2");
        List<String> edited = new ArrayList<>();
        for (String line : lines) {
            String hex = line.startsWith("hex ") ? line.substring(4, 8) : null;
            if (hex != null && structures.containsKey(hex)) {
                String[] parts = line.split(" ", 4);
                edited.add("hex " + hex + " " + parts[2] + " \"" + structures.get(hex) + "\" \"grass\"");
            } else {
                edited.add(line);
            }
        }
        var file = new File(output, "collapse-grassland.board");
        Files.write(file.toPath(), edited);
        var editor = new BoardEditorSession();
        editor.open(file.toPath());
        editor.command(new Command(Action.ASSET, AUTHORED), null);
        editor.pointer(ALL.getLast().at(), 0, 0, false);
        editor.finishStroke();
        // Woods drawn as a container yard and as crystals.
        for (var look : Map.of(new Coords(5, 0), "woods/container-yard/rows", new Coords(13, 0), "woods/crystal").entrySet()) {
            editor.command(new Command(Action.SELECT_AT, look.getKey().getX() + "," + look.getKey().getY(), ""), null);
            editor.command(new Command(Action.VARIANT, "vegetation", look.getValue()), null);
        }
        var saved = output.toPath().resolve("collapse-grassland.board2");
        editor.save(saved);
        return BoardFile.read(saved);
    }
}
