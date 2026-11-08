/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.field;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.Messages;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.clientGUI.BoardGraphics;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.planetaryConditions.Fog;
import megamek.common.planetaryConditions.Weather;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The preview and editor print through their live renderer without changing its camera or label preferences; a print
 * builds the live view's 3D Graphics (Classic columns or the Enhanced terrain).
 */
@Tag("on-demand")
class GpuBoardPrintSmokeTest {
    @Test
    void previewAndEditorExportWholeBoardWithOutlinedLabelsFromAnAngledCloseView() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires an OpenGL window");
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean coordinates = preferences.getCoordsEnabled();
        boolean types = preferences.getHexTypesEnabled();
        boolean outlined = preferences.getHexLabelOutline();
        BoardGraphics graphics = preferences.getBoardGraphics();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            preferences.setCoordsEnabled(false);
            preferences.setHexTypesEnabled(false);
            preferences.setHexLabelOutline(false);
        });
        try {
            var configuration = GpuBoardWindow.configuration(false);
            configuration.setWindowedMode(960, 720);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    try {
                        for (boolean editor : List.of(false, true)) { checkPrint(editor, BoardGraphics.ENHANCED); }
                        checkPrint(false, BoardGraphics.CLASSIC);
                        for (var graphics : BoardGraphics.values()) { checkSpacePrint(graphics); }
                        checkCancel();
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        Gdx.app.exit();
                    }
                }
            }, configuration);
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                preferences.setCoordsEnabled(coordinates);
                preferences.setHexTypesEnabled(types);
                preferences.setHexLabelOutline(outlined);
                preferences.setBoardGraphics(graphics);
            });
        }
        if (failure.get() != null) { throw new AssertionError("Native 3D board print", failure.get()); }
    }

    private static void checkPrint(boolean editing, BoardGraphics graphics) throws Exception {
        AtomicReference<GpuMapSource> captured = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            GUIPreferences.getInstance().setBoardGraphics(graphics);
            BoardEditorSession editor = editing ? new BoardEditorSession() : null;
            Game game = editor == null ? new Game() : editor.game();
            Hex[] hexes = new Hex[12 * 9];
            for (int y = 0; y < 9; y++) {
                for (int x = 0; x < 12; x++) {
                    // The asymmetric raised copse makes the saved north-up board visually distinguishable from
                    // the live isometric close-up and exposes any image-readback inversion.
                    boolean woods = x == 3 && y == 2;
                    hexes[y * 12 + x] = new Hex(woods ? 2 : 0,
                          woods ? "woods:1;foliage_elev:2" : "pavement:1", "grass", new Coords(x, y));
                }
            }
            game.setBoard(new Board(12, 9, hexes));
            BoardSceneryLayouts.convert(game.getBoard());
            game.getPlanetaryConditions().setWeather(Weather.HEAVY_RAIN);
            game.getPlanetaryConditions().setFog(Fog.FOG_HEAVY);
            captured.set(new GpuMapSource(game, null, editor));
        });
        GpuMapSource source = captured.get();
        GpuBattleView view = new GpuBattleView(source);
        try {
            view.create();
            GpuBoardTestUi.present(view);
            GpuBoardTestUi.click("utility-menu");
            view.render();
            assertTrue(GpuBoardTestUi.texts(GpuBoardTestUi.stage().getRoot().findActor("menu-panel"))
                  .contains(Messages.getString("CommonMenuBar.PrintMenu")), "The native Menu offers Print");
            Gdx.input.getInputProcessor().keyDown(Input.Keys.ESCAPE);
            Gdx.input.getInputProcessor().keyUp(Input.Keys.ESCAPE);
            view.render();
            BoardCamera camera = view.boardCamera;
            camera.advance(BoardCamera.ENTRANCE_SECONDS);
            camera.setIsometric(true);
            camera.center(BoardGeometry.center(new Coords(3, 2), 2));
            camera.zoom(.3f);
            view.render();
            float[] projection = camera.camera.combined.val.clone();
            Vector3 focus = camera.focus.cpy();
            assertFalse(camera.isTopDown(), "The live camera starts angled and zoomed into part of the board");
            assertTrue(source.takeFrame().scene().tile(new Coords(0, 0)).text().isEmpty(),
                  "The live scene starts with coordinates and terrain types disabled");
            BoardAtmosphere.Settings weather = source.takeFrame().scenarioAtmosphere();
            assertTrue(weather.effects().rain() > 0 && weather.fog() > 0,
                  "The live board supplies rain and fog that must be excluded from printing");

            boolean[] printedColumns = { false };
            String name = (editing ? "board-print-editor" : "board-print-preview")
                  + (graphics == BoardGraphics.CLASSIC ? "-classic" : "");
            BufferedImage image = print(source, view, name, () -> {
                Object print = field(view, "boardPrint");
                if (print != null && field(print, "terrain") instanceof GpuTerrain printTerrain) {
                    printedColumns[0] |= printTerrain.columns();
                }
            });
            assertEquals(graphics == BoardGraphics.CLASSIC, printedColumns[0],
                  "The print builds the live view's 3D Graphics: " + graphics);
            assertEquals(2775, image.getWidth(), "Twelve columns at 1.125-inch pitch plus the edge, at 200 DPI");
            assertEquals(2470, image.getHeight(), "Nine 1.3-inch rows plus the staggered half hex, at 200 DPI");
            for (int y : List.of(100, 1100, 2100)) {
                assertTrue(brightness(image.getRGB(0, y)) > 250,
                      "The empty background is the paper's white across framebuffer tile and JPEG band boundaries");
            }
            assertTrue(brightness(image.getRGB(150, 130)) < 220, "The board itself covers the paper");
            assertNoPaper(image, 110, 245, 80, 30); // The horizontal grid edge between the first two rows.
            // Every framebuffer tile is one part of one picture: no vignette darkens its corners (1.06 with one).
            double vignette = tileVignette(image);
            assertTrue(Math.abs(vignette - 1) < .02, "Tile corners print as bright as tile centres: " + vignette);
            assertTrue(image.getWidth() > Gdx.graphics.getWidth(), "Print resolution is independent of window width");
            assertTrue(image.getHeight() > Gdx.graphics.getHeight(), "Print resolution is independent of window height");
            // Hex 0403: three 225-pixel column steps, two 260-pixel rows and the odd column's half-row offset.
            assertTrue(greenPixels(image, 675, 650, 300, 260) > 1_000,
                  "The raised woods must be at its north-up mapsheet position with the printed hex pitch");
            assertEquals(0, greenPixels(image, 675, image.getHeight() - 910, 300, 260),
                  "Framebuffer readback must not flip the northern woods into the southern pavement");
            for (int row = 0; row < 2; row++) {
                for (int column = 0; column < 2; column++) {
                    assertTrue(outlinedInk(image, column, row) > 40,
                          "Outlined labels must reach every corner of the full board: " + column + ", " + row);
                }
            }
            assertArrayEquals(projection, camera.camera.combined.val, .0001f, "Printing preserves the live camera");
            assertEquals(focus, camera.focus, "Printing preserves the live focus");
            assertFalse(camera.isTopDown());
            assertEquals(weather, source.takeFrame().scenarioAtmosphere(), "Printing preserves the live board's weather");
            SwingUtilities.invokeAndWait(() -> {
                GUIPreferences preferences = GUIPreferences.getInstance();
                assertFalse(preferences.getCoordsEnabled());
                assertFalse(preferences.getHexTypesEnabled());
                assertFalse(preferences.getHexLabelOutline());
                assertTrue(source.takeFrame().scene().tile(new Coords(0, 0)).text().isEmpty(),
                      "The print-only labels must not leak into the live scene");
            });
            // The offscreen job borrows assets; a normal frame after it finishes must still use valid resources.
            view.render();
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            GpuBoardTestUi.capture(new File(reviewDirectory(), name + "-live.png"));
        } finally {
            view.dispose();
            SwingUtilities.invokeAndWait(source::close);
        }
    }

    /**
     * Both graphics draw SPACE hexes transparent: they print the space sky behind them, one sky across every framebuffer
     * tile.
     */
    private static void checkSpacePrint(BoardGraphics graphics) throws Exception {
        AtomicReference<GpuMapSource> captured = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            GUIPreferences.getInstance().setBoardGraphics(graphics);
            Hex[] hexes = new Hex[12 * 9];
            for (int index = 0; index < hexes.length; index++) {
                int x = index % 12, y = index / 12;
                // A raised platform in the corner: its walls end at the board's floor beside the transparent SPACE.
                boolean platform = x >= 9 && y >= 6;
                hexes[index] = new Hex(platform ? 2 : 0, platform ? "pavement:1" : "space:1", "", new Coords(x, y));
            }
            Game game = new Game();
            game.setBoard(new Board(12, 9, hexes));
            captured.set(new GpuMapSource(game, null, null));
        });
        GpuMapSource source = captured.get();
        GpuBattleView view = new GpuBattleView(source);
        try {
            view.create();
            GpuBoardTestUi.present(view);
            String name = "board-print-space" + (graphics == BoardGraphics.CLASSIC ? "-classic" : "");
            BufferedImage image = print(source, view, name, () -> { });
            view.render();
            GpuBoardTestUi.capture(new File(reviewDirectory(), name + "-live.png"));
            long total = 0;
            int stars = 0, repeated = 0;
            for (int y = 400; y < 700; y++) {
                for (int x = 400; x < 700; x++) {
                    int pixel = image.getRGB(x, y);
                    total += brightness(pixel);
                    if (brightness(pixel) > 60) {
                        stars++;
                        // The neighbouring framebuffer tile's same pixel: a per-tile sky would repeat each star there.
                        if (pixel == image.getRGB(x + 1024, y)) { repeated++; }
                    }
                }
            }
            assertTrue(total / (300 * 300) < 100, "Space prints the dark sky, not the paper: " + total / (300 * 300));
            assertTrue(stars > 0, "The space sky has its stars");
            assertTrue(repeated < stars / 2, "The sky spans the whole print instead of repeating in each tile: "
                  + repeated + " of " + stars + " star pixels repeat");
        } finally {
            view.dispose();
            SwingUtilities.invokeAndWait(source::close);
        }
    }

    /** The print card's Cancel ends a running print: no file stays, the card goes, and Print is offered again. */
    private static void checkCancel() throws Exception {
        AtomicReference<GpuMapSource> captured = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            GUIPreferences.getInstance().setBoardGraphics(BoardGraphics.ENHANCED);
            Game game = new Game();
            game.setBoard(Board.createEmptyBoard(12, 9));
            captured.set(new GpuMapSource(game, null, null));
        });
        GpuMapSource source = captured.get();
        GpuBattleView view = new GpuBattleView(source);
        File target = new File(reviewDirectory(), "board-print-cancelled.jpg");
        try {
            view.create();
            GpuBoardTestUi.present(view);
            java.nio.file.Files.deleteIfExists(target.toPath());
            AtomicBoolean printed = new AtomicBoolean();
            AtomicReference<Exception> failed = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> source.requestPrint(target.toPath(), () -> printed.set(true), failed::set));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (!(field(view, "boardPrint") instanceof GpuBoardPrint print)
                  || !print.progress().step().equals(Messages.getString("GpuBoard.print.rendering"))) {
                assertTrue(System.nanoTime() < deadline, "The print reaches its rendering");
                view.render();
            }
            assertTrue(target.exists(), "The print writes its file as it renders");
            GpuBoardTestUi.click("map-print-cancel");
            view.render();
            assertNull(field(view, "boardPrint"), "Cancel stops the print at its next frame");
            assertFalse(GpuBoardTestUi.stage().getRoot().findActor("map-print-progress").isVisible(),
                  "The progress card leaves with the cancelled print");
            SwingUtilities.invokeAndWait(() -> { });
            assertTrue(failed.get() instanceof java.util.concurrent.CancellationException, "The print ends as cancelled");
            assertFalse(printed.get());
            // The encoder thread closes the file after any band it still encodes, then deletes it.
            long removal = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (target.exists()) {
                assertTrue(System.nanoTime() < removal, "A cancelled print leaves no file");
                Thread.sleep(10);
            }
            assertTrue(source.takeFrame().globalCommands().stream()
                  .anyMatch(command -> command.id().equals("fileBoardPrint") && command.enabled()), "Print is offered again");
        } finally {
            view.dispose();
            SwingUtilities.invokeAndWait(source::close);
        }
    }

    /**
     * Prints through the map's EDT request while the view keeps rendering, as the live window does, into the review
     * directory's {@code name}.jpg, and reads that file back.
     */
    private static BufferedImage print(GpuMapSource source, GpuBattleView view, String name, ThrowingRunnable eachFrame)
          throws Exception {
        File target = new File(reviewDirectory(), name + ".jpg");
        AtomicBoolean printed = new AtomicBoolean();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> source.requestPrint(target.toPath(), () -> {
            if (!SwingUtilities.isEventDispatchThread()) {
                failed.set(new AssertionError("The completion must return to the map's EDT owner"));
            }
            printed.set(true);
        }, failed::set));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        var card = GpuBoardTestUi.stage().getRoot().findActor("map-print-progress");
        java.util.Set<String> steps = new java.util.HashSet<>();
        boolean captured = false;
        while (!printed.get() && failed.get() == null) {
            assertTrue(System.nanoTime() < deadline, "Printing must complete while the view continues to render");
            view.render();
            eachFrame.run();
            if (field(view, "boardPrint") instanceof GpuBoardPrint print) {
                assertTrue(card.isVisible(), "The progress card shows while the board prints");
                steps.add(print.progress().step());
                if (!captured && print.progress().done() > 0 && print.progress().done() < 1) {
                    GpuBoardTestUi.capture(new File(reviewDirectory(), name + "-progress.png"));
                    captured = true;
                }
            }
            SwingUtilities.invokeAndWait(() -> { });
        }
        if (failed.get() != null) { throw new AssertionError("Print rendering failed", failed.get()); }
        assertTrue(steps.contains(Messages.getString("GpuBoard.print.rendering")), "The card names its steps: " + steps);
        view.render();
        assertFalse(card.isVisible(), "The progress card leaves with the finished print");
        BufferedImage image = ImageIO.read(target);
        assertNotNull(image, "The print wrote a readable JPEG");
        return image;
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    private static File reviewDirectory() {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        return output;
    }

    private static int brightness(int pixel) {
        return ((pixel >> 16 & 255) + (pixel >> 8 & 255) + (pixel & 255)) / 3;
    }

    /** No paper shows through: the board's coverage survives the grid and label drawing over it. */
    private static void assertNoPaper(BufferedImage image, int left, int top, int width, int height) {
        for (int y = top; y < top + height; y++) {
            for (int x = left; x < left + width; x++) {
                assertTrue(brightness(image.getRGB(x, y)) < 240,
                      "Grid antialiasing must preserve the covered board at " + x + ", " + y);
            }
        }
    }

    /** The grey board's mean brightness near each framebuffer tile's centre over that near its corners. */
    private static double tileVignette(BufferedImage image) {
        double[] sum = new double[2];
        int[] count = new int[2];
        for (int y = 0; y < image.getHeight(); y += 2) {
            int top = y / 1024 * 1024, height = Math.min(1024, image.getHeight() - top);
            for (int x = 0; x < image.getWidth(); x += 2) {
                int left = x / 1024 * 1024, width = Math.min(1024, image.getWidth() - left);
                float u = (x - left + .5f) / width * 2 - 1, v = (y - top + .5f) / height * 2 - 1;
                int part = u * u + v * v < .25f ? 0 : u * u + v * v > 1.2f ? 1 : -1;
                int pixel = image.getRGB(x, y), red = pixel >> 16 & 255, green = pixel >> 8 & 255, blue = pixel & 255;
                if (part < 0 || Math.abs(red - green) > 12 || Math.abs(green - blue) > 12 || green < 60 || green > 230) {
                    continue;
                }
                sum[part] += green;
                count[part]++;
            }
        }
        return sum[0] / count[0] / (sum[1] / count[1]);
    }

    private static int greenPixels(BufferedImage image, int left, int top, int width, int height) {
        int count = 0;
        for (int y = top; y < top + height; y++) {
            for (int x = left; x < left + width; x++) {
                int pixel = image.getRGB(x, y), red = pixel >> 16 & 255, green = pixel >> 8 & 255, blue = pixel & 255;
                if (green > red + 8 && green > blue + 8) { count++; }
            }
        }
        return count;
    }

    /** Opaque black glyph ink immediately beside its white outline. */
    private static int outlinedInk(BufferedImage image, int column, int row) {
        int left = column == 0 ? image.getWidth() / 30 : image.getWidth() * 3 / 4;
        int right = column == 0 ? image.getWidth() / 4 : image.getWidth() * 29 / 30;
        int top = row == 0 ? image.getHeight() / 30 : image.getHeight() * 3 / 4;
        int bottom = row == 0 ? image.getHeight() / 4 : image.getHeight() * 29 / 30;
        int ink = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int pixel = image.getRGB(x, y);
                if ((pixel >>> 24) != 255 || (pixel >> 16 & 255) > 20 || (pixel >> 8 & 255) > 20
                      || (pixel & 255) > 20) { continue; }
                boolean outline = false;
                for (int dy = -2; dy <= 2 && !outline; dy++) {
                    for (int dx = -2; dx <= 2 && !outline; dx++) {
                        int neighbour = image.getRGB(x + dx, y + dy);
                        outline = (neighbour >> 16 & 255) >= 240 && (neighbour >> 8 & 255) >= 240
                              && (neighbour & 255) >= 240;
                    }
                }
                if (outline) { ink++; }
            }
        }
        return ink;
    }
}
