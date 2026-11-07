/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardBillboard;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Billboards in the shared board scene: a bulletin, a 2:1 poster, a 2:3 portrait panel, a giant panel, one hung on a
 * building's wall and a plain unlit one, by day and by night; then the editor's art picker. The art is each ratio folder's
 * shipped test card: its grid and circles show a stretch, its border a crop and its text a flip.
 */
@Tag("on-demand")
class GpuBillboardSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "billboards");
    private static final String WIDE = "decal/billboards/3_1/test-card", POSTER = "decal/billboards/2_1/test-card",
          TALL = "decal/billboards/2_3/test-card";

    private static BoardDecoration billboard(String id, double x, double y, double offset, BoardBillboard settings) {
        return new BoardDecoration(id, "prop", BoardBillboard.ASSET, null, x, y, 0, false, 1,
              BoardDecoration.Placement.surface("ground", "top", offset), 0).withBillboard(settings);
    }

    private static BoardScene capture(Board board) throws Exception {
        var scene = new AtomicReference<BoardScene>();
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> { fixture.source.refresh(); scene.set(fixture.source.takeFrame().scene()); });
        }
        return scene.get();
    }

    @Test void billboardsStandHangAndLightUpAfterDusk() throws Exception {
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        Board board = Board.createEmptyBoard(9, 9);
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 9; y++) { board.setHex(x, y, new Hex(0, "", "grass")); }
        }
        Coords bulletin = new Coords(3, 4), poster = new Coords(5, 4), portrait = new Coords(7, 2), giant = new Coords(4, 2),
              hung = new Coords(6, 6), plain = new Coords(2, 6), stack = new Coords(1, 3);
        var defaults = BoardBillboard.DEFAULT;
        board.getHex(bulletin).setDecorations(List.of(billboard("bulletin", 0, 0, 1, defaults.with("decal0", WIDE))));
        board.getHex(poster).setDecorations(List.of(billboard("poster", 0, 0, 1, defaults.with("ratio", "2_1").with("decal0", POSTER))));
        board.getHex(portrait).setDecorations(List.of(billboard("portrait", 0, 0, 1,
              defaults.with("ratio", "2_3").with("decal0", TALL).with("scale", "1.5"))));
        board.getHex(giant).setDecorations(List.of(billboard("giant", 0, 0, 1.5, defaults.with("decal0", WIDE).with("scale", "2.1"))));
        board.setHex(hung.getX(), hung.getY(), new Hex(0, "building:2;bldg_cf:60;bldg_elev:3", "grass"));
        // Its anchor against the building's south wall, its legs off, raised to the wall's middle.
        board.getHex(hung).setDecorations(List.of(billboard("hung", 0, -.5, 1.2, defaults.with("decal0", WIDE).with("legs", "false"))));
        board.getHex(plain).setDecorations(List.of(billboard("plain", 0, 0, 1, defaults.with("lights", "false"))));
        // Three stacked panels with their own pictures, one left blank, on tall legs.
        board.getHex(stack).setDecorations(List.of(billboard("stack", 0, 0, 3, defaults.with("ratio", "2_1").with("panels", "3")
              .with("decal0", POSTER).with("decal1", POSTER).with("decal2", ""))));
        var scene = capture(board);
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1500, 1000); config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                GpuTerrain terrain = new GpuTerrain();
                var day = new BoardAtmosphere.Settings(11, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);
                var night = new BoardAtmosphere.Settings(23, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);
                GpuReviewFrame frame = new GpuReviewFrame(day);
                try {
                    BoardCamera camera = new BoardCamera(); camera.resize(1500, 1000);
                    terrain.update(scene);
                    record View(String name, float yaw, float pitch, Coords at, float zoom, float z) { }
                    Coords middle = new Coords(4, 4);
                    var views = List.of(new View("overview", 0, 0, middle, .55f, 20), new View("front", -45, 25, middle, .55f, 20),
                          new View("bulletin", -45, 25, bulletin, .16f, 24), new View("back", 135, 15, bulletin, .16f, 24),
                          new View("poster", -45, 25, poster, .16f, 24), new View("portrait", -45, 25, portrait, .16f, 30),
                          new View("hung", -45, 15, hung, .2f, 30), new View("giant", -45, 25, giant, .3f, 36),
                          new View("plain", -45, 25, plain, .16f, 24), new View("stack", -45, 25, stack, .2f, 70),
                          new View("stack-above", 0, 0, stack, .2f, 70));
                    for (View view : views) {
                        camera.setIsometric(true);
                        camera.orbit(view.yaw(), view.pitch());
                        camera.camera.zoom = view.zoom();
                        camera.center(BoardGeometry.center(view.at(), 0).add(0, 0, view.z()));
                        frame.configure(day);
                        terrain.animate(1, List.of()); frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(OUTPUT, view.name() + "-day.png"));
                        frame.configure(night);
                        terrain.animate(1, List.of()); frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(OUTPUT, view.name() + "-night.png"));
                        camera.orbit(-view.yaw(), -view.pitch());
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    var lights = new ArrayList<GpuLavaLighting.Source>();
                    for (Object chunk : (List<?>) GpuTestSupport.field(terrain, "chunks")) {
                        for (Object prop : (List<?>) GpuTestSupport.field(chunk, "props")) {
                            @SuppressWarnings("unchecked") var drawn = (List<GpuLavaLighting.Source>) GpuTestSupport.field(prop, "drawnLights");
                            lights.addAll(drawn);
                        }
                    }
                    // One light per billboard with lamps: all but the plain one.
                    assertEquals(6, lights.size());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Billboard review", failure.get()); }
    }

    @Test void theEditorPicksArtByRatioScalesThePanelAndDropsTheLegs() throws Exception {
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        var editor = new AtomicReference<BoardEditorSession>();
        Coords at = new Coords(2, 2);
        var setup = new FutureTask<GpuMapSource>(() -> {
            var session = new BoardEditorSession(); editor.set(session);
            session.game().setBoard(Board.createEmptyBoard(5, 5));
            session.command(new Command(Action.ASSET, "", BoardBillboard.ASSET), null);
            session.pointer(at, 0, 0, false); session.finishStroke();
            session.command(new Command(Action.SELECT_OBJECT, "", session.board().getHex(at).getDecorations().getFirst().id()), null);
            session.command(new Command(Action.TOOL, "", "SELECT"), null);
            return new GpuMapSource(session.game(), null, session);
        });
        SwingUtilities.invokeAndWait(setup);
        var source = setup.get();
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1600, 1000); config.useVsync(false);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 180_000_000_000L;
                int step;
                long after;
                @Override public void create() { super.create(); boardCamera.setIsometric(true); }
                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Billboard editor stalled at " + step);
                        if (GpuBoardTestUi.loading(this) || frames() < after) { return; }
                        var field = GpuBattleView.class.getDeclaredField("terrain"); field.setAccessible(true);
                        if (((GpuTerrain) field.get(this)).busy()) { return; }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        var root = GpuBoardTestUi.stage().getRoot();
                        var object = editor.get().board().getHex(at).getDecorations().getFirst();
                        switch (step) {
                            case 0 -> { boardCamera.center(BoardGeometry.center(at, 1)); boardCamera.zoom(.5f); }
                            case 1 -> {
                                assertTrue(object.billboard().art().containsAll(object.billboard().decals()), "Random picked real art");
                                assertNotNull(root.findActor("editor-billboard-legs"));
                                assertNotNull(root.findActor("editor-choice-Aspect ratio"));
                                GpuBoardTestUi.capture(new File(OUTPUT, "editor-inspector.png"));
                                assertNotNull(root.findActor("editor-billboard-leg-height"));
                                assertNull(root.findActor("editor-billboard-art-1"), "One panel, one picture");
                                GpuBoardTestUi.click("editor-billboard-art-0");
                            }
                            case 2 -> {
                                assertTrue(root.findActor("editor-design-gallery").isVisible());
                                assertNotNull(root.findActor("editor-gallery-Random"));
                                GpuBoardTestUi.capture(new File(OUTPUT, "editor-picker.png"));
                                GpuBoardTestUi.click("editor-gallery-Test card");
                            }
                            case 3 -> {
                                assertFalse(root.findActor("editor-design-gallery").isVisible());
                                assertEquals(List.of(WIDE), object.billboard().decals(), "Only the panel's ratio");
                                GpuBoardTestUi.capture(new File(OUTPUT, "editor-picked.png"));
                                SwingUtilities.invokeAndWait(() -> editor.get().command(new Command(Action.OBJECT_VALUE, "billboard.ratio", "2_3"), null));
                            }
                            case 4 -> {
                                String picked = object.billboard().decals().getFirst();
                                assertTrue(picked.startsWith("decal/billboards/2_3/") && object.billboard().art().contains(picked),
                                      "A new ratio picks from its own folder: " + picked);
                                assertEquals(2.0 / 3, object.billboard().width() / object.billboard().height(), 1e-9);
                                GpuBoardTestUi.capture(new File(OUTPUT, "editor-portrait.png"));
                                SwingUtilities.invokeAndWait(() -> {
                                    editor.get().command(new Command(Action.OBJECT_VALUE, "billboard.decal0", TALL), null);
                                    editor.get().command(new Command(Action.OBJECT_VALUE, "billboard.scale", "2"), null);
                                });
                            }
                            case 5 -> {
                                assertEquals(2 * BoardBillboard.BASE_HEIGHT, object.billboard().height(), 1e-9);
                                GpuBoardTestUi.capture(new File(OUTPUT, "editor-scaled.png"));
                                SwingUtilities.invokeAndWait(() -> editor.get().command(new Command(Action.OBJECT_VALUE, "billboard.legs", "false"), null));
                            }
                            case 6 -> {
                                assertFalse(object.billboard().legs());
                                assertNull(root.findActor("editor-billboard-leg-height"), "No legs, no leg height");
                                GpuBoardTestUi.capture(new File(OUTPUT, "editor-no-legs.png"));
                                SwingUtilities.invokeAndWait(() -> {
                                    editor.get().command(new Command(Action.OBJECT_VALUE, "billboard.legs", "true"), null);
                                    editor.get().command(new Command(Action.OBJECT_VALUE, "billboard.panels", "3"), null);
                                    editor.get().command(new Command(Action.OBJECT_VALUE, "offset", "2.5"), null);
                                });
                            }
                            case 7 -> {
                                assertEquals(3, object.billboard().panels());
                                assertNotNull(root.findActor("editor-billboard-art-2"), "A picture card per panel");
                                GpuBoardTestUi.capture(new File(OUTPUT, "editor-stack.png"));
                                GpuBoardTestUi.click("editor-billboard-art-1");
                            }
                            case 8 -> {
                                assertTrue(root.findActor("editor-design-gallery").isVisible());
                                GpuBoardTestUi.click("editor-gallery-Blank");
                            }
                            case 9 -> {
                                assertEquals("", object.billboard().decals().get(1));
                                assertEquals(TALL, object.billboard().decals().getFirst());
                                GpuBoardTestUi.capture(new File(OUTPUT, "editor-stack-picked.png"));
                                SwingUtilities.invokeAndWait(() -> editor.get().command(new Command(Action.ASSET, "", BoardBillboard.ASSET), null));
                            }
                            case 10 -> {
                                assertNotNull(root.findActor("editor-billboard-legs"), "The brush offers the same settings");
                                GpuBoardTestUi.capture(new File(OUTPUT, "editor-brush.png"));
                                GpuBoardTestUi.category("transportation");
                            }
                            default -> {
                                GpuBoardTestUi.capture(new File(OUTPUT, "editor-palette.png"));
                                Gdx.app.exit(); return;
                            }
                        }
                        step++; after = frames() + 40;
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Billboard editor", failure.get()); }
    }
}
