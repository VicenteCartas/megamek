/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.utils.Array;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.Player;
import megamek.common.ResolvedAttack;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import megamek.common.enums.GamePhase;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import megamek.common.units.Targetable;
import megamek.common.units.UnitLocation;
import megamek.logging.MMLogger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * No shader compile holds a frame once the loading stage has gone. A game on Grassland 3, in either quality, shows its overview, a close
 * view, a pan, a volley of a laser, an autocannon and missiles and the Tactical View; then a tank and an infantry
 * platoon that were not on the board deploy, and SMAA, a 75% render resolution and the thermal view are switched on and
 * off. The Tactical View, the thermal (wireframe) view, SMAA and a render resolution below 100% are optional: they
 * link their own programs when they first show; their links are not counted, but no loading stage may come back. The 3D editor on the same board opens every Assets category and reveals each of its sections, searches, and
 * paints lava, water, ice, a car, scorch and the board's first building. It also switches Quality from Normal to High, waits for that lighting change's loading stage, and renders
 * fresh model and junction cards. Each phase reports the longest frame after its loading stage and asserts that no
 * program compiled or started a deferred link afterwards, and that no unexpected loading stage came back. With the
 * Tessellation switch on, a zoom from the fitted board to full-detail banks links nothing either.
 * {@code -Dmegamek.gpu.shaderTiming=true} logs every link: "after-loading" ones held a frame, "after-loading-deferred"
 * ones linked off the render thread while their draws waited.
 */
@Tag("on-demand")
class GpuShaderLoadSmokeTest {
    private static final MMLogger LOGGER = MMLogger.create(GpuShaderLoadSmokeTest.class);
    private static final double WATCH_SECONDS = 50;
    private static final File GRASSLAND = new File("data/boards/AGoAC Maps/16x17 Grassland 3.board");
    private static final File UNITS = new File("testresources/megamek/common/units");

    @ParameterizedTest
    @ValueSource(strings = { "NORMAL", "HIGH" })
    void gameCompilesNothingAfterItsLoadingStage(String quality) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<List<BoardScene.Animation>> pending = new AtomicReference<>(List.of());
        Watch watch = new Watch("game " + quality);
        GUIPreferences preferences = GUIPreferences.getInstance();
        String savedQuality = preferences.getBoardQuality();
        float savedResolution = preferences.getBoardRenderResolution();
        var fixture = GpuBoardFixture.create(BoardFile.loadSheet(GRASSLAND, false, true));
        List<Entity> late = new ArrayList<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    fixture.game.setPhase(GamePhase.FIRING);
                    Player enemy = new Player(2, "Opponent");
                    enemy.setTeam(2);
                    fixture.game.addPlayer(enemy.getId(), enemy);
                    GpuFiringCaptureTest.addTarget(fixture, 42, new Coords(11, 9)).setOwner(enemy);
                    // Not deployed yet: a vehicle and a formation, whose materials no Atlas shares.
                    int id = 60;
                    for (String file : List.of("Bulldog Medium Tank.blk", "Foot Platoon (AFFS) (Laser 3067+).blk")) {
                        Entity unit = new MekFileParser(new File(UNITS, file)).getEntity();
                        unit.setId(id++);
                        unit.setOwner(fixture.player);
                        unit.setDeployed(false);
                        fixture.game.addEntity(unit, false);
                        late.add(unit);
                    }
                    fixture.source.refresh();
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            });
            var source = spy(fixture.source);
            doAnswer(ignored -> {
                var frame = fixture.source.takeFrame();
                List<BoardScene.Animation> timeline = new ArrayList<>(frame.timeline());
                timeline.addAll(pending.getAndSet(List.of()));
                return frame.withTimeline(timeline);
            }).when(source).takeFrame();
            // The spy holds a copy of the source's fields; the captures happen on the source.
            doAnswer(ignored -> fixture.source.reserveUnits()).when(source).reserveUnits();
            // The game loads at 100%: the switch to 75% below links FSR at its first use, an optional setting.
            preferences.setBoardQuality(quality);
            preferences.setBoardRenderResolution(1);
            new Lwjgl3Application(new GpuBattleView(source) {
                int step, links, programs;

                @Override
                public void render() {
                    try {
                        watch.before(GpuBoardTestUi.loading(this));
                        super.render();
                        if (!watch.frame(GpuBoardTestUi.loading(this))) { return; }
                        double seconds = watch.seconds();
                        var terrain = (GpuTerrain) GpuMixedUnitBenchmarkSmokeTest.field(this, "terrain");
                        BoardScene scene = step < 4 || step == 9 ? fixture.source.takeFrame().scene() : null;
                        var tuning = (GpuBoardTuning) GpuMixedUnitBenchmarkSmokeTest.field(this, "tuning");
                        if (step == 0) {
                            boardCamera.setIsometric(true);
                            boardCamera.fit(scene);
                            step++;
                        } else if (step == 1 && seconds > 5) {
                            boardCamera.camera.zoom = .5f;
                            boardCamera.center(BoardGeometry.center(new Coords(scene.width() / 2, scene.height() / 2), 0));
                            step++;
                        } else if (step == 2 && seconds > 10) {
                            boardCamera.pan(BoardGeometry.WIDTH * .01f, 0);
                            if (seconds > 15) {
                                boardCamera.fit(scene);
                                var attacker = scene.units().stream().filter(unit -> unit.id() == 1).findFirst().orElseThrow();
                                var target = scene.units().stream().filter(unit -> unit.id() == 42).findFirst().orElseThrow();
                                pending.set(List.of(shot(attacker, target, "ISMediumLaser"), shot(attacker, target, "ISAC20"),
                                      shot(attacker, target, "ISLRM20")));
                                step++;
                            }
                        } else if (step == 3 && seconds > 25) {
                            watch.optionalView(true);
                            setTacticalView(true);
                            step++;
                        } else if (step == 4 && seconds > 28) {
                            watch.optionalView(false);
                            setTacticalView(false);
                            deploy(fixture, late.get(0), new Coords(7, 8));
                            step++;
                        } else if (step == 5 && seconds > 32) {
                            deploy(fixture, late.get(1), new Coords(9, 8));
                            step++;
                        } else if (step == 6 && seconds > 36) {
                            watch.optionalView(true);
                            ((SelectBox<?>) GpuMixedUnitBenchmarkSmokeTest.field(tuning, "antiAliasing")).setSelectedIndex(1);
                            BoardCamera.renderScale = .75f;
                            step++;
                        } else if (step == 7 && seconds > 39) {
                            tuning.setWireframe(true);
                            step++;
                        } else if (step == 8 && seconds > 43) {
                            watch.optionalView(false);
                            tuning.setWireframe(false);
                            ((SelectBox<?>) GpuMixedUnitBenchmarkSmokeTest.field(tuning, "antiAliasing")).setSelectedIndex(0);
                            BoardCamera.renderScale = 1;
                            step++;
                        } else if (step == 9 && seconds > 45) {
                            // Hits on walls: their scar patches draw with the walls' own programs.
                            links = GpuGlsl.compilesAfterLoading() + GpuGlsl.deferredAfterLoading();
                            programs = programs(terrain);
                            hitWalls(terrain, scene);
                            step++;
                        } else if (step == 10 && seconds > WATCH_SECONDS) {
                            assertTrue(terrain.surfaceScars().triangleCount() > 0, "The walls took scars");
                            assertEquals(links, GpuGlsl.compilesAfterLoading() + GpuGlsl.deferredAfterLoading(),
                                  "The scars linked a program");
                            assertEquals(programs, programs(terrain), "The scars added a program");
                            noScarPrograms(terrain);
                            assertTrue(fixture.source.takeFrame().scene().units().stream()
                                  .anyMatch(unit -> unit.id() == late.get(1).getId()), "The platoon deployed");
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }
            }, window());
        } finally {
            preferences.setBoardQuality(savedQuality);
            preferences.setBoardRenderResolution(savedResolution);
            BoardCamera.renderScale = 1;
            // The view closed the spied source, which shares the fixture's state.
            SwingUtilities.invokeAndWait(fixture.view::close);
        }
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
        watch.check();
    }

    /**
     * With the Tuning panel's Tessellation switch on, Grassland 2 loads fitted, where no chunk has full detail, then
     * zooms to its banks, where chunks refine to full detail and their curving relief tessellates: its programs linked
     * behind the loading stage, so the zoom links none. SMAA and a 75% render resolution saved on also link behind it.
     */
    @Test
    void tessellatedGameCompilesNothingWhenZoomingIn() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> available = new AtomicReference<>(true);
        Watch watch = new Watch("tessellated game");
        var fixture = GpuBoardFixture.create(BoardFile.loadSheet(new File("data/boards/AGoAC Maps/16x17 Grassland 2.board"),
              false, true));
        boolean saved = GUIPreferences.getInstance().getBoardTessellation();
        String savedAntiAliasing = GUIPreferences.getInstance().getBoardAntiAliasing();
        float savedResolution = GUIPreferences.getInstance().getBoardRenderResolution();
        String savedQuality = GUIPreferences.getInstance().getBoardQuality();
        GUIPreferences.getInstance().setBoardQuality("HIGH");
        GUIPreferences.getInstance().setBoardTessellation(true);
        GUIPreferences.getInstance().setBoardAntiAliasing("SMAA");
        GUIPreferences.getInstance().setBoardRenderResolution(.75f);
        try {
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                int step;

                @Override
                public void render() {
                    try {
                        if (!GpuTessellation.available()) {
                            available.set(false);
                            Gdx.app.exit();
                            return;
                        }
                        watch.before(GpuBoardTestUi.loading(this));
                        super.render();
                        if (!watch.frame(GpuBoardTestUi.loading(this))) { return; }
                        BoardScene scene = fixture.source.takeFrame().scene();
                        if (step == 0) {
                            boardCamera.setIsometric(true);
                            boardCamera.fit(scene);
                            step++;
                        } else if (step == 1 && watch.seconds() > 3) {
                            Coords bank = new Coords(12, 8);
                            boardCamera.setIsometric(false);
                            boardCamera.orbit(15, 55);
                            boardCamera.camera.zoom = .09f;
                            boardCamera.center(BoardGeometry.center(bank, scene.tile(bank).elevation()));
                            step++;
                        } else if (step == 2 && watch.seconds() > 15) {
                            var terrain = (GpuTerrain) GpuMixedUnitBenchmarkSmokeTest.field(this, "terrain");
                            boolean curved = false, coated = false;
                            for (Object chunk : (List<?>) GpuMixedUnitBenchmarkSmokeTest.field(terrain, "chunks")) {
                                for (Object instance : (List<?>) GpuMixedUnitBenchmarkSmokeTest.field(chunk, "opaque")) {
                                    for (var material : ((ModelInstance) instance).materials) {
                                        curved |= GpuTessellation.curved(material);
                                    }
                                }
                                for (Object instance : (List<?>) GpuMixedUnitBenchmarkSmokeTest.field(chunk, "overlays")) {
                                    for (var material : ((ModelInstance) instance).materials) {
                                        coated |= GpuTessellation.curved(material) && material.has(GpuRoads.Mask.TYPE);
                                    }
                                }
                            }
                            assertTrue(curved, "The close view's full-detail chunks draw curving relief");
                            assertTrue(coated, "and road coats curving with it, whose programs link while loading too");
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }
            }, window());
        } finally {
            GUIPreferences.getInstance().setBoardTessellation(saved);
            GUIPreferences.getInstance().setBoardAntiAliasing(savedAntiAliasing);
            GUIPreferences.getInstance().setBoardRenderResolution(savedResolution);
            GUIPreferences.getInstance().setBoardQuality(savedQuality);
            BoardCamera.renderScale = 1;
            SwingUtilities.invokeAndWait(fixture.view::close);
        }
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
        assumeTrue(available.get(), "No tessellation stages before OpenGL 4.0");
        watch.check();
    }

    /** Deploys a unit on Swing, as a deployment does: it appears on the board with the next capture. */
    private static void deploy(GpuBoardFixture fixture, Entity unit, Coords coords) {
        SwingUtilities.invokeLater(() -> {
            unit.setPosition(coords);
            unit.setDeployed(true);
            fixture.source.refresh();
        });
    }

    @Test
    void editorCompilesNothingAfterItsLoadingStage() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Watch watch = new Watch("editor");
        AtomicReference<Watch> relight = new AtomicReference<>();
        GUIPreferences preferences = GUIPreferences.getInstance();
        String savedQuality = preferences.getBoardQuality();
        preferences.setBoardQuality("NORMAL");
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            editor.game().setBoard(BoardFile.loadSheet(GRASSLAND, false, true));
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        // Every category and its sections, a search, then surfaces and an appearance-only scorch stroke.
        List<Runnable> steps = new ArrayList<>();
        for (var category : BoardEditorBlueprint.get().categories()) {
            steps.add(() -> GpuBoardTestUi.category(category.id()));
            for (var section : category.sections()) {
                steps.add(() -> {
                    if (GpuBoardTestUi.stage().getRoot().findActor("editor-section-" + section.path()) != null) {
                        GpuBoardTestUi.reveal("editor-section-" + section.path());
                    }
                });
            }
        }
        for (String text : List.of("tree", "lava")) {
            steps.add(() -> ((TextField) GpuBoardTestUi.stage().getRoot().findActor("editor-search")).setText(text));
        }
        // The building is the board's first: its shell's program and depth twin come from the palette's warm-up.
        List<Coords> strokes = List.of(new Coords(3, 3), new Coords(7, 3), new Coords(11, 3), new Coords(5, 9),
              new Coords(9, 9), new Coords(13, 12));
        List<BoardEditorSession.Command> brushes = List.of(
              new BoardEditorSession.Command(BoardEditorSession.Action.CHOOSE_BRUSH, "water", "magma"),
              new BoardEditorSession.Command(BoardEditorSession.Action.CHOOSE_BRUSH, "water", "shallow"),
              new BoardEditorSession.Command(BoardEditorSession.Action.CHOOSE_BRUSH, "weather", "ice"),
              new BoardEditorSession.Command(BoardEditorSession.Action.ASSET, "", "scenery/vehicles/car"),
              new BoardEditorSession.Command(BoardEditorSession.Action.CHOOSE_BRUSH, "scorch", "scorch/charred"),
              new BoardEditorSession.Command(BoardEditorSession.Action.CHOOSE_BRUSH, "building", "default"));
        for (int i = 0; i < strokes.size(); i++) {
            int stroke = i;
            steps.add(() -> {
                long generation = source.takeFrame().boardGeneration();
                source.editorCommand(brushes.get(stroke), generation);
                source.editorPointer(strokes.get(stroke), 0, 0, false, generation);
                source.endEditorStroke();
            });
        }
        steps.add(() -> ((TextField) GpuBoardTestUi.stage().getRoot().findActor("editor-search")).setText(""));
        steps.add(() -> GpuBoardTestUi.category("hazards"));
        steps.add(() -> GpuBoardTestUi.reveal("editor-section-hazards/scorched"));
        steps.add(() -> GpuBoardTestUi.click("editor-gallery-"
              + BoardEditorBlueprint.get().card(BoardEditorBlueprint.get().entry("scorch/singed")).label()));
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                int step = -1;
                double stepAt;
                long relightStarted;
                boolean changedLighting, sawRelighting;
                Image modelCard, railCard;

                @Override
                public void render() {
                    try {
                        boolean wasLoading = GpuBoardTestUi.loading(this);
                        int compilesBefore = GpuGlsl.compilesAfterLoading(), deferredBefore = GpuGlsl.deferredAfterLoading();
                        Watch active = changedLighting ? relight.get() : watch;
                        if (active != null) { active.before(wasLoading); }
                        super.render();
                        boolean loading = GpuBoardTestUi.loading(this);
                        if (changedLighting) {
                            sawRelighting |= loading;
                            assertTrue(System.nanoTime() - relightStarted < 600_000_000_000L,
                                  "The lighting change and its cards must finish");
                            if (active == null) {
                                if (loading) { return; }
                                // Exclude only the intentional reload. Counters from before this frame still catch
                                // programs first requested by the frame that dismisses its loading stage.
                                active = new Watch("editor-quality", compilesBefore, deferredBefore);
                                active.before(wasLoading);
                                relight.set(active);
                            }
                        }
                        if (!active.frame(loading)) { return; }
                        if (changedLighting) {
                            if (loading) { return; }
                            assertTrue(sawRelighting, "A changed shader flag gets a loading stage");
                            if (modelCard == null) {
                                var hud = GpuMixedUnitBenchmarkSmokeTest.field(this, "ui");
                                var editor = GpuMixedUnitBenchmarkSmokeTest.field(hud, "editor");
                                var models = (GpuEditorModelPreviews) GpuMixedUnitBenchmarkSmokeTest.field(editor, "models");
                                var junctions = (GpuEditorModelPreviews) GpuMixedUnitBenchmarkSmokeTest.field(editor, "junctions");
                                // Fresh image targets cannot reuse cards rendered before the lighting switch.
                                modelCard = new Image(); railCard = new Image();
                                modelCard.setBounds(10, 10, 128, 96); railCard.setBounds(148, 10, 128, 96);
                                GpuBoardTestUi.stage().addActor(modelCard); GpuBoardTestUi.stage().addActor(railCard);
                                models.add("scenery/vehicles/car", modelCard);
                                junctions.add(RailRoute.Kind.RAIL, RailRoute.side(0) | RailRoute.side(3), 0, railCard);
                            } else if ("Ready".equals(modelCard.getUserObject()) && "Ready".equals(railCard.getUserObject())) {
                                Gdx.app.exit();
                            } else {
                                assertTrue(active.seconds() < 60, "Fresh model and rail previews must finish after loading");
                            }
                            return;
                        }
                        var root = GpuBoardTestUi.stage().getRoot();
                        double seconds = watch.seconds();
                        if (step == -1) {
                            noScarPrograms(GpuMixedUnitBenchmarkSmokeTest.field(this, "terrain"));
                            // An editor opened before in this JVM left its page: the start page first.
                            if (root.findActor("editor-back") != null) {
                                GpuBoardTestUi.click("editor-back");
                            } else if (seconds > 2) {
                                step = 0;
                                stepAt = seconds;
                            }
                            return;
                        }
                        // Completed card renders and an idle board permit the next step without a long idle dwell.
                        boolean settled = GpuEditorMaglevSmokeTest.ready(root.findActor("editor-library"))
                              && !((GpuTerrain) GpuMixedUnitBenchmarkSmokeTest.field(this, "terrain")).busy();
                        if (step < steps.size() && seconds - stepAt > (settled ? .25 : 8)) {
                            steps.get(step++).run();
                            stepAt = seconds;
                        } else if (step == steps.size() && settled && seconds - stepAt > 4) {
                            var scene = source.takeFrame().scene();
                            assertTrue(scene.tile(strokes.get(0)).liquid().molten(), "The lava stroke shows");
                            assertTrue(scene.tile(strokes.get(1)).liquid().present(), "The water stroke shows");
                            assertFalse(scene.tile(strokes.get(3)).features().isEmpty(), "The car stands");
                            assertTrue(megamek.common.board.HexAppearance.scorch(scene.tile(strokes.get(4)).appearance()) > 0,
                                  "The authored scorch stroke shows");
                            assertTrue(scene.tile(strokes.get(5)).features().stream().anyMatch(
                                  feature -> feature.kind() == BoardScene.FeatureKind.BUILDING), "The building stands");
                            for (var severity : megamek.common.board.HexAppearance.Scorch.values()) {
                                assertTrue(root.findActor("editor-library-" + severity.variant()) != null,
                                      "The gallery offers " + severity);
                            }
                            File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                            assertTrue(output.isDirectory() || output.mkdirs());
                            GpuBoardTestUi.capture(new File(output, "editor-scorch-gallery.png"));
                            watch.check();
                            SelectBox<BoardQuality> quality = GpuBoardTestUi.tuning(this, "tuning-quality");
                            quality.setSelected(BoardQuality.HIGH);
                            changedLighting = true;
                            relightStarted = System.nanoTime();
                        } else if (seconds - stepAt > 60) {
                            throw new AssertionError("The board did not settle after the last stroke");
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }
            }, window());
        } finally {
            // On the Swing thread, after the panel's own queued saves.
            SwingUtilities.invokeAndWait(() -> {
                source.close();
                preferences.setBoardQuality(savedQuality);
            });
        }
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
        assertTrue(relight.get() != null, "The changed lighting must reach its presented phase");
        relight.get().check();
    }

    /** No program is prepared for scars: they draw with their receivers' programs. */
    private static void noScarPrograms(Object terrain) throws Exception {
        for (Object sample : (List<?>) GpuMixedUnitBenchmarkSmokeTest.field(terrain, "terrainMaterialSamples")) {
            assertFalse(((Renderable) sample).material.has(GpuSurfaceScars.Marking.TYPE), "A scar program");
        }
    }

    /** The programs the shader provider of a renderer's {@code batch} holds: the terrain's, or an unlit overlay's. */
    static int programs(Object renderer) throws Exception {
        var provider = ((ModelBatch) GpuMixedUnitBenchmarkSmokeTest.field(renderer, "batch")).getShaderProvider();
        if (provider instanceof GpuShaderProvider edited) { provider = edited.active(); }
        return ((Array<?>) GpuMixedUnitBenchmarkSmokeTest.field(provider, "shaders")).size;
    }

    /** An autocannon hit on each of the board's three steepest walls, through the game's contact path. */
    private static void hitWalls(GpuTerrain terrain, BoardScene scene) {
        List<BoardSurface.Face> walls = new ArrayList<>();
        for (var tile : scene.tiles()) {
            var surface = terrain.tacticalSurface(tile.coords());
            if (surface != null) { walls.addAll(surface.walls()); }
        }
        walls.sort(Comparator.comparingDouble(face -> Math.abs(normal(face).z)));
        int hits = 0;
        for (var face : walls) {
            if (hits == 3) { break; }
            var point = face.a().cpy().add(face.b()).add(face.c()).scl(1f / 3);
            var incoming = normal(face).scl(-1);
            var origin = point.cpy().mulAdd(incoming, -BoardRelief.metres(20));
            var aim = point.cpy().mulAdd(incoming, BoardRelief.metres(.1f));
            var mark = GroundDamagePlaybackTest.impact("ISAC20", null);
            mark.attack().landscape = ray -> terrain.hit(scene, ray);
            mark.attack().landscapeSegment = (ray, length) -> terrain.hit(scene, ray, length);
            var contact = mark.attack().landscapeContact(t -> origin.cpy().lerp(aim, (float) t), 1);
            if (contact == null) { continue; }
            terrain.impact(scene, new GpuGroundDamage.Impact(mark.attack(), "wall-" + hits, origin, contact.point(),
                  mark.effect(), mark.shot()));
            hits++;
        }
        assertEquals(3, hits, "The board has walls to hit");
    }

    private static Vector3 normal(BoardSurface.Face face) {
        return face.b().cpy().sub(face.a()).crs(face.c().cpy().sub(face.a())).nor();
    }

    private static Lwjgl3ApplicationConfiguration window() {
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 900);
        return config;
    }

    private static BoardScene.Combat shot(BoardScene.Unit attacker, BoardScene.Unit target, String weapon) {
        var result = new ResolvedAttack(UUID.randomUUID(), ResolvedAttack.Kind.SHOT,
              new UnitLocation(attacker.id(), attacker.location().coords(), 0, 0, 0),
              new UnitLocation(target.id(), target.location().coords(), 0, 0, 0), Targetable.TYPE_ENTITY, 0, weapon, 4, true);
        return new BoardScene.Combat(result, attacker, target, target.location());
    }

    /**
     * The frames from the one that dismissed the first loading stage, which already draws the board. The longest frame
     * leaves out that first one; a loading stage that comes back is counted, and its frames are left out.
     */
    private static final class Watch {
        /** Frames longer than this are logged with their start, to find what held them in the timing log. */
        private static final long LONG_FRAME = 100_000_000;
        private final String name;
        private final int compiles, deferred;
        private LocalDateTime frameStart;
        private boolean wasLoading = true;
        private long loaded, previous, longest;
        private double longestAt;
        private int frames, loadingAgain;
        // Links while an optional view shows (the Tactical View, the thermal view), which may link on first use.
        private int optionalCompiles, optionalDeferred, optionalStart = -1, optionalDeferredStart;

        Watch(String name) { this(name, GpuGlsl.compilesAfterLoading(), GpuGlsl.deferredAfterLoading()); }

        /** An optional view's programs link in the background when it first shows; those links are not counted. */
        void optionalView(boolean shows) {
            if (shows) {
                optionalStart = GpuGlsl.compilesAfterLoading();
                optionalDeferredStart = GpuGlsl.deferredAfterLoading();
            } else if (optionalStart >= 0) {
                optionalCompiles += GpuGlsl.compilesAfterLoading() - optionalStart;
                optionalDeferred += GpuGlsl.deferredAfterLoading() - optionalDeferredStart;
                optionalStart = -1;
            }
        }

        Watch(String name, int compiles, int deferred) {
            this.name = name; this.compiles = compiles; this.deferred = deferred;
        }

        void before(boolean loading) {
            wasLoading = loading;
            frameStart = LocalDateTime.now();
        }

        /** Records one frame; true once the loading stage has gone, also while it is back. */
        boolean frame(boolean loading) {
            long now = System.nanoTime();
            if (loading) {
                if (loaded != 0 && !wasLoading) { loadingAgain++; }
                return loaded != 0;
            }
            if (loaded == 0) {
                loaded = now;
                LOGGER.info("L1 {} loading stage gone in the frame started at {}", name, frameStart);
            } else if (!wasLoading) {
                if (now - previous > longest) {
                    longest = now - previous;
                    longestAt = seconds();
                }
                if (now - previous > LONG_FRAME) {
                    LOGGER.info(String.format(Locale.ROOT, "L1 %s long frame %.1f ms started at %s", name,
                          (now - previous) / 1e6, frameStart));
                }
            }
            previous = now;
            frames++;
            return true;
        }

        double seconds() { return (System.nanoTime() - loaded) / 1e9; }

        void check() {
            assertTrue(loaded != 0, name + ": the loading stage never went");
            int after = GpuGlsl.compilesAfterLoading() - compiles - optionalCompiles;
            int deferredAfter = GpuGlsl.deferredAfterLoading() - deferred - optionalDeferred;
            String report = String.format(Locale.ROOT,
                  "L1 %s frames=%d longest=%.1f ms at %.1f s compilesAfterLoading=%d deferred=%d loadingAgain=%d"
                        + " optionalViews=%d+%d", name, frames, longest / 1e6, longestAt, after, deferredAfter, loadingAgain,
                  optionalCompiles, optionalDeferred);
            LOGGER.info(report);
            System.out.println(report);
            assertEquals(0, after, name + ": shaders compiled on the render thread after the loading stage (see the timing log)");
            assertEquals(0, deferredAfter,
                  name + ": shader links were deferred until after the loading stage (see the timing log)");
            assertEquals(0, loadingAgain, name + ": a loading stage came back");
        }
    }
}
