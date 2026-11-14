/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.client.ui.Messages;
import megamek.common.board.Board;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks the worker-to-screen boundary in the real loading pipeline, including a superseded build. */
@Tag("on-demand")
class GpuLoadingSmokeTest {
    @ParameterizedTest(name = "wide map: {0}")
    @ValueSource(booleans = { false, true })
    void loadingShowsConcurrentTasksAndClearsSupersededProgress(boolean wide) throws Exception {
        var failure = new AtomicReference<Throwable>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(900, 600);
        var terrainField = GpuBattleView.class.getDeclaredField("terrain");
        var loadingField = GpuBattleView.class.getDeclaredField("loadingStage");
        terrainField.setAccessible(true);
        loadingField.setAccessible(true);
        try (var fixture = wide ? GpuBoardFixture.create(Board.createEmptyBoard(24, 16)) : GpuBoardFixture.create()) {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                fixture.entity.setPosition(null);
                fixture.entity.setDeployed(false);
                fixture.source.refresh();
            });
            assertFalse(fixture.source.reserveUnits().isEmpty(), "Deployment models must warm up off board");
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                final long deadline = System.nanoTime() + 120_000_000_000L;
                final Map<String, Integer> completions = new HashMap<>();
                final Set<String> tasks = new HashSet<>();
                boolean restarted, concurrent, partial, captured;
                GpuLoadingGrid grid;
                int completedSections;

                @Override
                public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Terrain loading must finish");
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        GpuTerrain terrain = (GpuTerrain) terrainField.get(this);
                        Stage loading = (Stage) loadingField.get(this);
                        if (loading != null) {
                            var statuses = terrain.buildDetails();
                            var sections = terrain.buildSections();
                            assertEquals(sections.columns() * sections.rows(), sections.states().size());
                            var order = IntStream.range(0, sections.states().size()).boxed()
                                  .sorted(Comparator.comparingInt(index -> TerrainLoadProgress.displayIndex(index,
                                        sections.columns(), sections.rows()))).toList();
                            int started = sections.states().size()
                                  - Collections.frequency(sections.states(), TerrainLoadProgress.SectionState.WAITING);
                            for (int position = 0; position < order.size(); position++) {
                                assertEquals(position < started,
                                      sections.states().get(order.get(position)) != TerrainLoadProgress.SectionState.WAITING,
                                      "The loader must queue a continuous prefix of the displayed reading order");
                            }
                            int ready = (int) sections.states().stream()
                                  .filter(state -> state == TerrainLoadProgress.SectionState.READY).count();
                            assertTrue(ready >= completedSections, "Finished sections must stay ready until a new build");
                            completedSections = ready;
                            // A finished build (-1) may still be installing behind the stage, which shows it complete.
                            int built = terrain.buildProgress();
                            assertEquals(built < 0 ? 100 : built, 100 * ready / sections.states().size());
                            concurrent |= statuses.size() > 1;
                            int previousSection = 0;
                            for (var status : statuses) {
                                var step = status.step();
                                assertTrue(step.completed() >= 0 && step.completed() <= step.total());
                                assertTrue(step.percent() >= 0 && step.percent() <= 100);
                                String key = status.section() + ":" + step.task() + ":" + step.started();
                                assertTrue(step.completed() >= completions.getOrDefault(key, 0));
                                completions.put(key, step.completed());
                                tasks.add(step.task());
                                partial |= step.completed() > 0 && step.completed() < step.total();
                                if (status.section() > 0) {
                                    assertTrue(status.section() > previousSection && status.section() <= status.sections());
                                    assertEquals(TerrainLoadProgress.SectionState.LOADING,
                                          sections.states().get(order.get(status.section() - 1)), "A task and its cell must agree");
                                    previousSection = status.section();
                                }
                            }
                            Label details = loading.getRoot().findActor("board-loading-details");
                            Label overall = loading.getRoot().findActor("board-loading-message");
                            grid = loading.getRoot().findActor("board-loading-grid");
                            assertEquals(Messages.getString("GpuBoard.loadingLegend.waiting",
                                        Collections.frequency(sections.states(), TerrainLoadProgress.SectionState.WAITING)),
                                  loading.getRoot().<Label>findActor("board-loading-waiting").getText().toString());
                            assertEquals(Messages.getString("GpuBoard.loadingLegend.active",
                                        Collections.frequency(sections.states(), TerrainLoadProgress.SectionState.LOADING)),
                                  loading.getRoot().<Label>findActor("board-loading-active").getText().toString());
                            assertEquals(Messages.getString("GpuBoard.loadingLegend.ready", ready, sections.states().size()),
                                  loading.getRoot().<Label>findActor("board-loading-ready").getText().toString());
                            var shaders = terrain.shaderProgress();
                            assertTrue(shaders.linked() >= 0 && shaders.linked() <= shaders.total(), shaders::toString);
                            // The update plan and the first sections start before the first build's links, which can
                            // hold a frame for seconds: those sections prepare on the workers meanwhile.
                            assertTrue(restarted || started > 0 || shaders.total() == 0,
                                  "Terrain programs must not link before the first sections start: " + shaders);
                            // Programs link in parallel: one may finish after the frame set the bar, never the reverse.
                            assertTrue(loading.getRoot().findActor("board-loading-shaders").isVisible()
                                  || shaders.linked() == shaders.total(),
                                  "The shader bar shows while programs are left to link");
                            assertTrue(overall.getText().toString().contains("%"));
                            assertFalse(details.getText().toString().contains("GpuBoard.loading"));
                            Group content = (Group) loading.getRoot().getChildren().first();
                            assertEquals(loading.getWidth(), content.getWidth(), .1f);
                            GpuBoardTestUi.assertHorizontalBounds(content, content);
                            assertTrue(details.getY() >= 0 && details.getTop() <= loading.getHeight());
                            assertEquals(.06f * loading.getHeight(), details.getY(), .5f,
                                  "The progress must keep the bottom of the screen");
                            assertTrue(grid.getY() >= 0 && grid.getTop() <= loading.getHeight());
                            boolean advancing = sections.states().contains(TerrainLoadProgress.SectionState.LOADING);
                            if (ready > 0 && advancing && !details.getText().isEmpty() && !captured) {
                                assertTrue(details.getText().toString().contains("%"));
                                File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                                assertTrue(output.isDirectory() || output.mkdirs());
                                GpuReviewFrame.save(new File(output, "loading-honeycomb.png"));
                                checkLoadingLayout(loading);
                                checkShortWindow(loading, output);
                                capturePreview(loading, output, 25, 25, "loading-honeycomb-625-preview.png");
                                capturePreview(loading, output, 7, 88, "loading-honeycomb-tall-map-preview.png");
                                capturePreview(loading, output, 88, 7, "loading-honeycomb-wide-map-preview.png");
                                captured = true;
                            }
                            if (ready > 0 && advancing && !restarted) {
                                terrain.boardChanged();
                                assertTrue(terrain.buildDetails().isEmpty(), "Cancelled workers cannot remain on screen");
                                assertTrue(terrain.buildSections().states().isEmpty(), "Old ready cells must be cleared too");
                                completedSections = 0;
                                restarted = true;
                            }
                        } else if (frames() > 0 && !terrain.busy()) {
                            assertTrue(restarted && concurrent && partial && captured);
                            var shaders = terrain.shaderProgress();
                            assertEquals(shaders.total(), shaders.linked(), "The shader bar must finish before the board shows");
                            assertTrue(tasks.contains("surfaces") || tasks.contains("topography"), tasks.toString());
                            assertTrue(terrain.buildDetails().isEmpty());
                            assertEquals(-1, terrain.buildProgress());
                            assertTrue(terrain.buildSections().states().stream()
                                  .allMatch(state -> state == TerrainLoadProgress.SectionState.READY));
                            checkGridBounds(grid);
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }
            }, configuration);
        }
        if (failure.get() != null) { throw new AssertionError("Loading progress review failed", failure.get()); }
    }

    /** Changes in task count and wrapping must never recenter the loading screen. */
    private static void checkLoadingLayout(Stage loading) {
        Label overall = loading.getRoot().findActor("board-loading-message");
        Label details = loading.getRoot().findActor("board-loading-details");
        Label legend = loading.getRoot().findActor("board-loading-ready");
        GpuLoadingGrid grid = loading.getRoot().findActor("board-loading-grid");
        Actor shaders = loading.getRoot().findActor("board-loading-shaders");
        boolean compiling = shaders.isVisible();
        float gridY = grid.getY(), gridHeight = grid.getHeight(), legendY = legend.getY();
        float detailsTop = details.getTop();
        String single = new TerrainLoadProgress.Status(1, 625,
              new TerrainLoadProgress.Step("roads", 36, 64, System.nanoTime())).text();
        for (String text : List.of("", single, String.join("\n", Collections.nCopies(4, single)),
              "Preparing terrain ".repeat(12), single)) {
            details.setText(text);
            for (String status : List.of("Preparing", "Preparing\nLoading terrain")) {
                overall.setText(status);
                // The shader bar appears and hides as programs link.
                shaders.setVisible(!shaders.isVisible());
                loading.draw();
                assertEquals(gridY, grid.getY(), .1f, "Task changes must not move the grid");
                assertEquals(gridHeight, grid.getHeight(), .1f, "Task changes must not resize the grid");
                assertEquals(legendY, legend.getY(), .1f, "Task changes must not move the legend");
                assertEquals(detailsTop, details.getTop(), .1f, "Tasks must grow downward from a fixed position");
                assertTrue(details.getY() >= 0 && details.getTop() <= loading.getHeight());
            }
        }
        shaders.setVisible(compiling);
    }

    /** A short screen keeps the header and every task line on it; the honeycomb shrinks into the space between. */
    private static void checkShortWindow(Stage loading, File output) {
        var viewport = (ScreenViewport) loading.getViewport();
        float unitsPerPixel = viewport.getUnitsPerPixel();
        viewport.setUnitsPerPixel(400f / Gdx.graphics.getHeight());
        viewport.update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        ScreenUtils.clear(.045f, .065f, .075f, 1);
        loading.draw();
        GpuLoadingGrid grid = loading.getRoot().findActor("board-loading-grid");
        Label overall = loading.getRoot().findActor("board-loading-message");
        Label legend = loading.getRoot().findActor("board-loading-ready");
        Label details = loading.getRoot().findActor("board-loading-details");
        assertTrue(grid.getHeight() > 0 && grid.getTop() <= overall.getY(), "The honeycomb must stay below the header");
        assertTrue(legend.localToStageCoordinates(new Vector2(0, legend.getHeight())).y <= grid.getY(),
              "The legend must stay below the honeycomb");
        assertTrue(details.getY() >= 0 && overall.getTop() <= loading.getHeight(), "Every line must stay on screen");
        GpuReviewFrame.save(new File(output, "loading-honeycomb-short.png"));
        viewport.setUnitsPerPixel(unitsPerPixel);
        viewport.update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
    }

    /** UI fixtures rendered through the actual loading layout; no large terrain build is simulated. */
    private static void capturePreview(Stage loading, File output, int columns, int rows, String filename) {
        int count = columns * rows, ready = count * 64 / 100;
        GpuLoadingGrid grid = loading.getRoot().findActor("board-loading-grid");
        grid.update(new TerrainLoadProgress.Sections(columns, rows, IntStream.range(0, count).mapToObj(index -> {
            int order = TerrainLoadProgress.displayIndex(index, columns, rows);
            return order < ready ? TerrainLoadProgress.SectionState.READY
                  : order < ready + 4 ? TerrainLoadProgress.SectionState.LOADING : TerrainLoadProgress.SectionState.WAITING;
        }).toList()));
        loading.getRoot().<Label>findActor("board-loading-message").setText(Messages.getString("GpuBoard.loadingOverall", 100 * ready / count));
        loading.getRoot().<Label>findActor("board-loading-waiting").setText(Messages.getString("GpuBoard.loadingLegend.waiting", count - ready - 4));
        loading.getRoot().<Label>findActor("board-loading-active").setText(Messages.getString("GpuBoard.loadingLegend.active", 4));
        loading.getRoot().<Label>findActor("board-loading-ready").setText(Messages.getString("GpuBoard.loadingLegend.ready", ready, count));
        GpuBattleView.showShaders(loading.getRoot().findActor("board-loading-shaders"), new TerrainLoadProgress.Shaders(12, 40));
        loading.getRoot().<Label>findActor("board-loading-details").setText(String.join("\n",
              new TerrainLoadProgress.Status(ready + 1, count, new TerrainLoadProgress.Step("upload", 4, 7,
                    System.nanoTime() - 2_000_000_000L)).text(),
              new TerrainLoadProgress.Status(ready + 2, count, new TerrainLoadProgress.Step("surfaces", 48, 64,
                    System.nanoTime() - 5_000_000_000L)).text(),
              new TerrainLoadProgress.Status(ready + 3, count, new TerrainLoadProgress.Step("masks", 1, 3,
                    System.nanoTime() - 1_000_000_000L)).text(),
              new TerrainLoadProgress.Status(ready + 4, count, new TerrainLoadProgress.Step("roads", 36, 64,
                    System.nanoTime() - 3_000_000_000L)).text()));
        ScreenUtils.clear(.045f, .065f, .075f, 1);
        loading.act(1f / 60);
        loading.draw();
        Group content = (Group) loading.getRoot().getChildren().first();
        GpuBoardTestUi.assertHorizontalBounds(content, content);
        Label details = loading.getRoot().findActor("board-loading-details");
        assertTrue(details.getY() >= 0 && details.getTop() <= loading.getHeight());
        GpuReviewFrame.save(new File(output, filename));
    }

    /** Check horizontal progress and bounds for dense, wide, tall and single-row/column maps in the real batch. */
    private static void checkGridBounds(GpuLoadingGrid grid) {
        Stage stage = new Stage(new ScreenViewport());
        try {
            stage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
            stage.addActor(grid);
            grid.setBounds(100, 100, 700, 200);
            for (int[] size : List.of(new int[] { 20, 20 }, new int[] { 25, 25 }, new int[] { 40, 10 },
                  new int[] { 10, 40 }, new int[] { 7, 88 }, new int[] { 88, 7 }, new int[] { 1, 20 },
                  new int[] { 20, 1 }, new int[] { 1, 1 })) {
                // The third section must be to the right of the first, still in the first displayed row.
                grid.update(new TerrainLoadProgress.Sections(size[0], size[1], IntStream.range(0, size[0] * size[1])
                      .mapToObj(index -> index == 0 ? TerrainLoadProgress.SectionState.READY
                            : TerrainLoadProgress.displayIndex(index, size[0], size[1]) == 2 ? TerrainLoadProgress.SectionState.LOADING
                            : TerrainLoadProgress.SectionState.WAITING).toList()));
                ScreenUtils.clear(0, 0, 0, 1);
                stage.getBatch().setColor(.6f, .7f, .8f, 1);
                float tint = stage.getBatch().getPackedColor();
                stage.draw();
                assertEquals(tint, stage.getBatch().getPackedColor(), "The grid must restore the stage's batch color");
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
                float scaleX = (float) width / Gdx.graphics.getWidth(), scaleY = (float) height / Gdx.graphics.getHeight();
                Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, width, height);
                try {
                    int painted = 0;
                    int left = width, right = 0, bottom = height, top = 0;
                    int readyX = 0, readyY = 0, readyPixels = 0;
                    int loadingX = 0, loadingY = 0, loadingPixels = 0;
                    for (int x = 0; x < width; x++) {
                        for (int y = 0; y < height; y++) {
                            int pixel = pixels.getPixel(x, y);
                            if ((pixel & 0xffffff00) == 0) { continue; }
                            painted++;
                            left = Math.min(left, x);
                            right = Math.max(right, x);
                            bottom = Math.min(bottom, y);
                            top = Math.max(top, y);
                            int red = pixel >>> 24, green = (pixel >>> 16) & 0xff, blue = (pixel >>> 8) & 0xff;
                            if (green > red && green > blue) {
                                readyX += x;
                                readyY += y;
                                readyPixels++;
                            } else if (red > green && green > blue) {
                                loadingX += x;
                                loadingY += y;
                                loadingPixels++;
                            }
                            assertTrue(x >= 100 * scaleX && x < 800 * scaleX && y >= 100 * scaleY && y < 300 * scaleY,
                                  "Every honeycomb pixel must stay inside its widget");
                        }
                    }
                    assertTrue(painted > size[0] * size[1], "Every map shape must produce visible cells");
                    assertTrue(readyPixels > 0, "The ready section must be visible");
                    if (size[0] * size[1] > 1) {
                        assertTrue(loadingPixels > 0, "The loading section must be visible");
                    }
                    boolean turned = size[1] > size[0];
                    if ((turned ? size[1] : size[0]) > 1) {
                        assertTrue((float) readyX / readyPixels < left + (right - left) * .15f,
                              "The first section must stay at the left edge after orientation");
                        assertTrue((float) loadingX / loadingPixels > (float) readyX / readyPixels,
                              "The next sections must advance to the right, not down the first column");
                        assertEquals((float) readyY / readyPixels, (float) loadingY / loadingPixels, 2 * scaleY,
                              "The first and third sections must stay in the same displayed row");
                    }
                    if ((turned ? size[0] : size[1]) > 1) {
                        float readyHeight = ((float) readyY / readyPixels - bottom) / (top - bottom);
                        assertTrue(readyHeight > .85f, "The first section must stay at the top after orientation");
                    }
                    if (size[0] != size[1]) {
                        assertTrue((right - left) / scaleX > (top - bottom) / scaleY,
                              "A long map must use the horizontal space");
                    }
                } finally { pixels.dispose(); }
            }
        } finally { stage.dispose(); }
    }
}
