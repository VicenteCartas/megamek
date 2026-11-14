/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Independent generic-path pixels gate analytic border masks, including borrowed neighboring terrain. */
@Tag("on-demand")
class GpuHexMasksSmokeTest {
    private static final int WIDTH = 900, HEIGHT = 650;

    @Test
    void regularMasksMatchCapturedDrapedBordersAndReleaseOwnedResources() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(WIDTH, HEIGHT);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { verify(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Hex masks", failure.get()); }
    }

    private static void verify() throws Exception {
        assertNotNull(Gdx.gl30, "The float parameter texture requires the native GL30 path");
        String before = Mesh.getManagedStatus().replace(" 0", "");
        Map<Coords, BoardTacticalGeometry.Surface> surfaces = new HashMap<>();
        GpuTactical actual = new GpuTactical(surfaces::get);
        GpuTactical reference = new GpuTactical(surfaces::get);
        Field maskField = GpuTactical.class.getDeclaredField("hexMasks");
        maskField.setAccessible(true);
        GpuHexMasks masks = (GpuHexMasks) maskField.get(actual);
        List<Executable> failures = new ArrayList<>();
        try {
            // Isolate mask coverage/color math before alpha blending or page ordering can contribute.
            var graphics = new BoardTacticalGraphics();
            BoardTactical opaque;
            try {
                var hex = BoardTacticalGraphics.at(graphics, new Coords(0, 0));
                hex.setColor(new Color(73, 151, 227));
                hex.fillHexBorder(1.5, 2.5);
                hex.dispose();
                opaque = graphics.snapshot();
            } finally { graphics.dispose(); }
            BoardScene single = scene(1, 1, opaque);
            surfaces.put(new Coords(0, 0), surface(single.tiles().getFirst(), 0));
            compare("single-opaque", single, actual, masks, reference, true, true, failures);

            BoardScene scene = scene(9, 3, opaqueBorders(9, 3));
            surfaces.clear();
            scene.tiles().forEach(tile -> surfaces.put(tile.coords(), surface(tile, 0)));
            // A narrow part of the common edge borrows its finished terrain from the neighboring Surface.
            Coords owner = new Coords(7, 1), neighbor = new Coords(8, 1);
            sharedEdge(scene, surfaces, owner, neighbor);
            compare("styles-water-ice-neighbor", scene, actual, masks, reference, true, true, failures);
            long bytes = masks.bytes();
            collect(failures, () -> assertTrue(bytes > 0));
            actual.update(scene);
            collect(failures, () -> assertEquals(bytes, masks.bytes(), "Unchanged commands retain the uploaded carrier"));

            List<BoardTactical.Fill> recolored = new ArrayList<>(scene.tactical().fills());
            var first = recolored.getFirst();
            recolored.set(0, new BoardTactical.Fill(first.contours(), first.winding(), 0xFFD050A0,
                  first.playback(), first.border()));
            scene = scene(9, 3, new BoardTactical(recolored, List.of()));
            compare("one-hex-recolor", scene, actual, masks, reference, false, true, failures);
            collect(failures, () -> assertEquals(bytes, masks.bytes(), "A style change borrows the same finished terrain"));

            // A changed owner tile and neighboring Surface replace the carrier even though commands are unchanged.
            scene = scene(9, 3, scene.tactical(), 2);
            sharedEdge(scene, surfaces, owner, neighbor);
            compare("neighbor-elevation-update", scene, actual, masks, reference, false, true, failures);
            BoardScene fallbackScene = scene;
            collect(failures, () -> verifyFallbacks(fallbackScene, actual, masks));

            scene = scene(9, 3, borders(9, 3, 2));
            surfaces.clear();
            scene.tiles().forEach(tile -> surfaces.put(tile.coords(), surface(tile, 0)));
            compare("overlapping-translucent-borders", scene, actual, masks, reference, true, false, failures);

            // Several original 10,000-triangle pages exercise painter order across different sorting centers.
            scene = scene(17, 17, borders(17, 17, 4));
            surfaces.clear();
            scene.tiles().forEach(tile -> surfaces.put(tile.coords(), surface(tile, 0)));
            compare("multiple-transparent-pages", scene, actual, masks, reference, false, false, failures);
            Field pages = GpuTactical.class.getDeclaredField("pages");
            pages.setAccessible(true);
            int count = ((Map<?, ?>) pages.get(reference)).values().stream().mapToInt(value -> ((List<?>) value).size()).sum();
            collect(failures, () -> assertTrue(count > 1, "The reference must cross its real transparent-page boundary"));
            collect(failures, () -> assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError()));
        } finally {
            reference.dispose();
            actual.dispose();
        }
        collect(failures, () -> assertEquals(before, Mesh.getManagedStatus().replace(" 0", "")));
        assertAll("Analytic border mask comparisons", failures);
    }

    private static void compare(String name, BoardScene scene, GpuTactical actual, GpuHexMasks masks,
          GpuTactical reference, boolean allAngles, boolean maskExpected, List<Executable> failures) {
        actual.update(scene);
        var stripped = scene.tactical().fills().stream().map(fill ->
              new BoardTactical.Fill(fill.contours(), fill.winding(), fill.argb(), fill.playback())).toList();
        reference.update(withCommands(scene, new BoardTactical(stripped, List.of())));
        for (boolean perspective : new boolean[] { false, true }) {
            for (float tilt : allAngles ? new float[] { 0, 54.73561f, 75 } : new float[] { 54.73561f }) {
                BoardCamera view = new BoardCamera();
                view.resize(WIDTH, HEIGHT);
                view.fit(scene);
                view.setPerspective(perspective);
                view.setIsometric(false);
                view.orbit(20, tilt);
                collect(failures, () -> assertEquals(tilt, view.tilt(), .0001f));
                Camera camera = view.camera;
                String label = name + "-" + perspective + "-" + tilt;
                byte[] empty = frame(() -> { });
                frame(() -> reference.render(camera, 0));
                frame(() -> actual.render(camera, 0));
                collect(failures, () -> assertEquals(maskExpected, masks.active(), name + ": expected mask/fallback path"));
                byte[] original = frame(() -> reference.render(camera, 0));
                collect(failures, () -> assertPixels(label + "-reference-repeat", original,
                      frame(() -> reference.render(camera, 0))));
                int borderPixels = changedPixels(empty, original);
                collect(failures, () -> assertTrue(borderPixels > 50, label + ": comparison must contain visible borders"));
                collect(failures, () -> assertPixels(label, original,
                      frame(() -> actual.render(camera, 0)), maskExpected ? borderPixels : -1, empty));
            }
        }
    }

    /** Render immediately while the fixture is installed; defer only assertion failures, never GL/resource errors. */
    private static void collect(List<Executable> failures, Runnable comparison) {
        try { comparison.run(); }
        catch (AssertionError failure) { failures.add(() -> { throw failure; }); }
    }

    private static void verifyFallbacks(BoardScene scene, GpuTactical actual, GpuHexMasks masks) {
        var fill = scene.tactical().fills().getFirst();
        var generic = new BoardTactical.Fill(fill.contours(), fill.winding(), fill.argb(), fill.playback());
        var border = fill.border();
        var floating = new BoardTactical.Fill(fill.contours(), fill.winding(), fill.argb(), fill.playback(),
              border, border.anchor());
        for (BoardTactical unsupported : List.of(new BoardTactical(List.of(fill, generic), List.of()),
              new BoardTactical(List.of(floating), List.of()),
              new BoardTactical(List.of(fill, fill, fill, fill, fill), List.of()),
              new BoardTactical(List.of(fill), List.of(), List.of(), List.of(generic)))) {
            actual.update(withCommands(scene, unsupported));
            assertFalse(masks.active());
            assertEquals(0, masks.bytes(), "A fallback releases the former carrier meshes");
        }
    }

    private static byte[] frame(Runnable draw) {
        Gdx.gl.glViewport(0, 0, WIDTH, HEIGHT);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glClearColor(.06f, .08f, .1f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        draw.run();
        return ScreenUtils.getFrameBufferPixels(0, 0, WIDTH, HEIGHT, false);
    }

    private static int changedPixels(byte[] expected, byte[] actual) {
        int changed = 0;
        for (int pixel = 0; pixel < expected.length; pixel += 4) {
            for (int channel = 0; channel < 4; channel++) {
                if (expected[pixel + channel] != actual[pixel + channel]) { changed++; break; }
            }
        }
        return changed;
    }

    private static void assertPixels(String name, byte[] expected, byte[] actual) {
        assertPixels(name, expected, actual, -1, null);
    }

    private static void assertPixels(String name, byte[] expected, byte[] actual, int visible, byte[] background) {
        int changed = changedPixels(expected, actual), maximum = 0, interior = 0, junctions = 0;
        for (int i = 0; i < expected.length; i++) {
            maximum = Math.max(maximum, Math.abs(Byte.toUnsignedInt(expected[i]) - Byte.toUnsignedInt(actual[i])));
        }
        int limit = visible < 0 ? 0 : Math.max(4, (int) (visible * .005));
        for (int pixel = 0; pixel < expected.length; pixel += 4) {
            if (!samePixel(expected, pixel, actual, pixel)
                  && (!neighborContains(expected, pixel, actual) || !neighborContains(actual, pixel, expected))) {
                if (background != null && isolatedCoverageTie(expected, actual, pixel, background)) { junctions++; }
                else { interior++; }
            }
        }
        System.out.printf("HEX-MASK %s changedPixels=%d edgeBudget=%d visiblePixels=%d interiorDifferences=%d"
              + " isolatedJunctions=%d maxChannelDelta=%d%n", name, changed, limit, visible, interior, junctions, maximum);
        if (changed > limit || interior != 0) {
            for (boolean reference : new boolean[] { true, false }) {
                Pixmap image = new Pixmap(WIDTH, HEIGHT, Pixmap.Format.RGBA8888);
                try {
                    image.getPixels().put(reference ? expected : actual).flip();
                    PixmapIO.writePNG(Gdx.files.local("build/gpu-performance/review/hex-mask-" + name
                          + (reference ? "-reference.png" : "-actual.png")), image);
                } finally { image.dispose(); }
            }
        }
        if (visible < 0) {
            assertArrayEquals(expected, actual, name + ": immutable reference pixels");
        } else {
            assertEquals(0, interior, name + ": interior colors must match exactly");
            assertTrue(changed <= limit, name + ": edge coverage changes " + changed + " exceed " + limit);
        }
    }

    /**
     * At a shared hex-edge pixel-center tie, CPU clipping can leave a single clear pixel between existing border
     * colors. Closing that isolated crack removes the last local clear sample, so the bidirectional edge check
     * cannot match it. Clipping can also leave one isolated colored sample at a projected corner, which disappears
     * with analytic coverage. Require eight unchanged neighbors in both cases. No new color or alpha value is allowed;
     * all such samples still count against the overall edge budget.
     */
    private static boolean isolatedCoverageTie(byte[] expected, byte[] actual, int pixel, byte[] background) {
        int x = pixel / 4 % WIDTH, y = pixel / 4 / WIDTH;
        if (x == 0 || y == 0 || x == WIDTH - 1 || y == HEIGHT - 1) { return false; }
        boolean isolatedCorner = samePixel(actual, pixel, background, 0);
        if (!isolatedCorner && (!samePixel(expected, pixel, background, 0)
              || !neighborContains(actual, pixel, expected))) { return false; }
        int first = ((y - 1) * WIDTH + x - 1) * 4;
        boolean distinctColors = false;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) { continue; }
                int neighbor = ((y + dy) * WIDTH + x + dx) * 4;
                if (!samePixel(expected, neighbor, actual, neighbor)
                      || (isolatedCorner ? samePixel(expected, neighbor, expected, pixel)
                            : samePixel(expected, neighbor, background, 0))) { return false; }
                distinctColors |= !samePixel(expected, neighbor, expected, first);
            }
        }
        return isolatedCorner || distinctColors;
    }

    /**
     * Analytic discard and clipped triangle edges can select opposite sides of a pixel-center tie. Each changed
     * color must exist in the other image's adjacent 3x3 neighborhood, in BOTH directions; new interior colors
     * cannot pass. A separate 0.5%-of-visible-pixels cap (minimum four pixels) rejects displaced or missing edges.
     */
    private static boolean neighborContains(byte[] source, int pixel, byte[] other) {
        int x = pixel / 4 % WIDTH, y = pixel / 4 / WIDTH;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx, ny = y + dy;
                if (nx >= 0 && nx < WIDTH && ny >= 0 && ny < HEIGHT
                      && samePixel(source, pixel, other, (ny * WIDTH + nx) * 4)) { return true; }
            }
        }
        return false;
    }

    private static boolean samePixel(byte[] a, int p, byte[] b, int q) {
        return a[p] == b[q] && a[p + 1] == b[q + 1] && a[p + 2] == b[q + 2] && a[p + 3] == b[q + 3];
    }

    private static BoardTactical borders(int width, int height, int repetitions) {
        var graphics = new BoardTacticalGraphics();
        try {
            for (int layer = 0; layer < repetitions; layer++) {
                for (int x = 0; x < width; x++) {
                    for (int y = 0; y < height; y++) {
                        var hex = BoardTacticalGraphics.at(graphics, new Coords(x, y));
                        hex.setColor(new Color(40 + (x * 23 + layer * 67) % 200,
                              35 + (y * 41 + layer * 53) % 210, 220 - layer * 35, layer == 0 && x % 3 == 0 ? 255 : 109));
                        hex.fillHexBorder((x + y) % 3 + layer * .5, 1.5 + (x % 3));
                        hex.dispose();
                    }
                }
            }
            return graphics.snapshot();
        } finally { graphics.dispose(); }
    }

    private static BoardTactical opaqueBorders(int width, int height) {
        return new BoardTactical(borders(width, height, 1).fills().stream().map(fill ->
              new BoardTactical.Fill(fill.contours(), fill.winding(), fill.argb() | 0xFF000000,
                    fill.playback(), fill.border())).toList(), List.of());
    }

    private static BoardScene scene(int width, int height, BoardTactical tactical) {
        return scene(width, height, tactical, 0);
    }

    private static BoardScene scene(int width, int height, BoardTactical tactical, int raised) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                tiles.add(BoardScenes.newTile(new Coords(x, y), x == 7 && y == 1 ? raised : x == 2 ? 2 : 0, x == 4 ? 3 : -1,
                      x == 4 && y == 1, 0, BoardScene.Surface.GRASS, List.of(), List.of()));
            }
        }
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of(), null,
              List.of(), List.of(), List.of(), tactical);
    }

    private static BoardScene withCommands(BoardScene scene, BoardTactical tactical) {
        return new BoardScene(scene.boardId(), scene.width(), scene.height(), scene.tiles(), List.of(), List.of(), -1, "",
              List.of(), null, List.of(), List.of(), List.of(), tactical);
    }

    private static BoardTacticalGeometry.Surface surface(BoardScene.Tile tile, float offset) {
        List<BoardSurface.Face> faces = new ArrayList<>();
        Vector3 center = BoardGeometry.center(tile.coords(), 0);
        center.z = BoardGeometry.surfaceZ(tile) + offset;
        for (int edge = 0; edge < 6; edge++) {
            Vector3 a = BoardGeometry.corner(tile.coords(), 0, edge), b = BoardGeometry.corner(tile.coords(), 0, edge + 1);
            a.z = center.z; b.z = center.z;
            if (tile.coords().getX() == 3) {
                a.z += (a.x - center.x) * .2f;
                b.z += (b.x - center.x) * .2f;
            }
            faces.add(new BoardSurface.Face(new Vector3(center), a, b, BoardSurface.Finish.TOP));
        }
        return tile.coords().getX() == 3 ? surface(List.of(), faces) : surface(faces, List.of());
    }

    private static void sharedEdge(BoardScene scene, Map<Coords, BoardTacticalGeometry.Surface> surfaces,
          Coords owner, Coords neighbor) {
        var owned = new ArrayList<>(surface(scene.tile(owner), 0).top());
        var borrowed = new ArrayList<>(surface(scene.tile(neighbor), 0).top());
        var face = owned.removeFirst(); // The owner (7,1) and neighbor (8,1) share this right-upper edge.
        Vector3 p = new Vector3(face.b()).lerp(face.c(), .2f), q = new Vector3(face.b()).lerp(face.c(), .8f);
        Vector3 innerP = new Vector3(p).lerp(face.a(), .1f), innerQ = new Vector3(q).lerp(face.a(), .1f);
        owned.add(new BoardSurface.Face(face.a(), face.b(), p, BoardSurface.Finish.TOP));
        owned.add(new BoardSurface.Face(face.a(), innerP, innerQ, BoardSurface.Finish.TOP));
        owned.add(new BoardSurface.Face(face.a(), q, face.c(), BoardSurface.Finish.TOP));
        borrowed.add(new BoardSurface.Face(innerP, p, q, BoardSurface.Finish.TOP));
        borrowed.add(new BoardSurface.Face(innerP, q, innerQ, BoardSurface.Finish.TOP));
        surfaces.put(owner, surface(owned, List.of()));
        surfaces.put(neighbor, surface(borrowed, List.of()));
    }

    private static BoardTacticalGeometry.Surface surface(List<BoardSurface.Face> top, List<BoardSurface.Face> slopes) {
        return new BoardTacticalGeometry.Surface(List.copyOf(top), List.copyOf(slopes), List.copyOf(top),
              List.of(), List.of(), List.of());
    }
}
