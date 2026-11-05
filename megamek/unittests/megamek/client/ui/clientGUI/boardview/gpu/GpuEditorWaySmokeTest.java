/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Actual editor-authored layers: clean bridges, locally painted deck ways and implicit road/maglev decks. */
@Tag("on-demand")
class GpuEditorWaySmokeTest {
    @Test void editorLayersAndPreview() throws Exception {
        var setup = new FutureTask<GpuMapSource>(() -> {
            var session = new BoardEditorSession();
            Board board = Board.createEmptyBoard(9, 9);
            for (int x = 3; x <= 5; x++) {
                for (int y = 2; y <= 6; y++) { board.setHex(x, y, new Hex(0, "water:2", "desert")); }
            }
            board.setHex(4, 4, new Hex(0, "bridge:1:9;bridge_cf:40;bridge_elev:2;rail:1:18;bridge_rail:1:9", "desert"));
            session.game().setBoard(board);
            send(session, Action.SELECT_AT, "4,4", "");
            send(session, Action.COMPONENT, "", "bridge");
            return new GpuMapSource(session.game(), null, session);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "editor-ways");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1600, 1000);
        configuration.setInitialVisible(false);
        try {
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override public void create() {
                    var view = new GpuBattleView(source);
                    try {
                        view.create(); GpuBoardTestUi.present(view);
                        view.boardCamera.setIsometric(true);
                        view.boardCamera.fit(source.takeFrame().scene());
                        frame(view, source, null);
                        GpuReviewFrame.save(new File(output, "inspector-deck.png"));
                        command(source, Action.COMPONENT, "", "ground");
                        frame(view, source, null);
                        GpuReviewFrame.save(new File(output, "inspector-ground.png"));

                        command(source, Action.CHOOSE_BRUSH, "road", "1");
                        Coords river = new Coords(3, 4);
                        frame(view, source, river);
                        assertTrue(source.editorPreview().decks().stream().anyMatch(deck -> deck.at().equals(river) && !deck.removed()));
                        GpuReviewFrame.save(new File(output, "preview-new-deck.png"));

                        command(source, Action.BRUSH_VALUE, "wayOffset", "5");
                        Coords occupied = new Coords(4, 4);
                        frame(view, source, occupied);
                        assertTrue(source.editorPreview().stubs().contains(occupied));
                        assertTrue(source.editorHint().contains("Refused"));
                        GpuReviewFrame.save(new File(output, "preview-refused-deck.png"));
                    } catch (Throwable error) { failure.set(error); }
                    finally { view.dispose(); Gdx.app.exit(); }
                }
            }, configuration);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Editor layer preview", failure.get()); }
    }

    @Test void authoredWays() throws Exception {
        Map<String, GpuRoadRailSmokeTest.View> views = new LinkedHashMap<>();
        var session = new BoardEditorSession();
        Board board = Board.createEmptyBoard(12, 12);
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                board.setHex(x, y, y >= 3 && y <= 7 ? new Hex(0, "water:2", "desert") : new Hex(2, "", "desert"));
            }
        }
        session.game().setBoard(board);
        send(session, Action.CHOOSE_BRUSH, "bridge", "bridge/truss");
        stroke(session, new Coords(4, 2), new Coords(4, 8), "ground");
        capture(views, "clean-truss", session);

        send(session, Action.CHOOSE_BRUSH, "road", "1");
        stroke(session, new Coords(4, 3), new Coords(4, 5), "bridge");
        send(session, Action.ASSET, "", RailRoute.Kind.RAIL.asset);
        stroke(session, new Coords(4, 5), new Coords(4, 7), "bridge");
        capture(views, "partly-painted-truss", session);

        send(session, Action.CHOOSE_BRUSH, "road", "1");
        stroke(session, new Coords(7, 2), new Coords(7, 8), "ground");
        send(session, Action.ASSET, "", RailRoute.Kind.MAGLEV.asset);
        stroke(session, new Coords(2, 2), new Coords(2, 8), "ground");
        capture(views, "implicit-road-and-maglev", session);

        Coords arch = new Coords(10, 5);
        send(session, Action.CHOOSE_BRUSH, "bridge", "bridge/natural");
        stroke(session, new Coords(10, 2), new Coords(10, 8), "ground");
        capture(views, "natural-clean", session, arch);
        for (int y = 3; y <= 7; y++) {
            send(session, Action.SELECT_AT, "10," + y, "");
            send(session, Action.TERRAIN, "bridge_mud", "1");
        }
        capture(views, "natural-mud", session, arch);
        for (int y : new int[] { 1, 2, 8, 9 }) {
            send(session, Action.SELECT_AT, "10," + y, "");
            send(session, Action.TERRAIN, "mud", "1");
        }
        assertTrue(session.board().getHex(10, 2).containsTerrain(Terrains.MUD));
        assertTrue(session.board().getHex(10, 3).containsTerrain(Terrains.BRIDGE_MUD));
        capture(views, "natural-mud-bank", session, new Coords(10, 3));
        for (int height : new int[] { 1, 2 }) {
            for (int y : new int[] { 6, 7 }) {
                var hex = session.board().getHex(10, y).duplicate();
                hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, height));
                session.board().setHex(10, y, hex);
            }
            if (height == 1) { capture(views, "natural-mud-graded", session, new Coords(10, 5)); }
        }
        for (int y : new int[] { 1, 2, 8, 9 }) {
            send(session, Action.SELECT_AT, "10," + y, "");
            send(session, Action.REMOVE_TERRAIN, "", "mud");
        }
        for (int y = 3; y <= 7; y++) {
            send(session, Action.SELECT_AT, "10," + y, "");
            send(session, Action.REMOVE_TERRAIN, "", "bridge_mud");
        }
        send(session, Action.CHOOSE_BRUSH, "road", "1");
        send(session, Action.BRUSH_VALUE, "terrain:road", "3");
        stroke(session, new Coords(10, 3), new Coords(10, 7), "bridge");
        capture(views, "natural-dirt-road", session, arch);
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "editor-ways");
        GpuRoadRailSmokeTest.render(output, views, "");
    }

    private static void capture(Map<String, GpuRoadRailSmokeTest.View> views, String name, BoardEditorSession session) throws Exception {
        capture(views, name, session, new Coords(4, 5));
    }

    private static void capture(Map<String, GpuRoadRailSmokeTest.View> views, String name, BoardEditorSession session,
          Coords focus) throws Exception {
        views.put(name, new GpuRoadRailSmokeTest.View(BoardScenes.scene(session.board()), focus, 2));
    }

    private static void stroke(BoardEditorSession session, Coords from, Coords to, String receiver) {
        session.pointer(from, 0, 0, false, null, false, receiver);
        session.pointer(to, 0, 0, true, null, false, receiver);
        session.finishStroke();
    }

    private static void send(BoardEditorSession session, Action action, String target, String value) {
        session.command(new Command(action, target, value), null);
    }

    private static void command(GpuMapSource source, Action action, String target, String value) {
        source.editorCommand(new Command(action, target, value), source.takeFrame().boardGeneration());
    }

    private static void frame(GpuBattleView view, GpuMapSource source, Coords at) throws Exception {
        for (int frame = 0; frame < 3; frame++) {
            if (at != null) {
                var tile = source.takeFrame().scene().tile(at);
                var bridge = BoardBridge.feature(tile);
                var point = view.boardCamera.camera.project(BoardGeometry.center(at,
                      tile.elevation() + (bridge == null ? 0 : bridge.elevation())));
                Gdx.input.getInputProcessor().mouseMoved(Math.round(point.x), Gdx.graphics.getHeight() - Math.round(point.y));
            }
            SwingUtilities.invokeAndWait(source::refresh);
            view.render();
        }
    }
}
