/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.client.ui.gdx.UiButton;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Layers rows show their layer's type in the real panel (the table itself is GpuBoardEditor.LAYER_ICONS; its keys are
 * checked against the blueprint in BoardSceneryTest): the Ground row keeps the hexagon, a component, a
 * vehicle, a decal and a group row show their icons, no row shows the unit shield, and a reorderable row keeps its drag
 * handle before its type icon; an additive component (Elevators, Hazards, Scenario features with a deployment zone) has
 * its row while its fields are in the hex, and the zone's Edit field lists its IDs.
 * Captures the hex's Layers at 1920x1080.
 */
@Tag("on-demand")
class GpuEditorLayerIconsSmokeTest {
    private record Case(String name, Coords at, Map<String, String> icons) { }

    private static final List<Case> CASES = List.of(
          new Case("water", new Coords(1, 1), Map.of("editor-component-ground", "hex", "editor-component-water", "water",
                "editor-content-bench", "star", "editor-content-paint", "decal", "editor-group-cars", "stack",
                "editor-content-car-1", "car")),
          // Additive components without terrain of their own get a row while one of their fields is in the hex.
          new Case("additive", new Coords(3, 2), Map.of("editor-component-ground", "hex", "editor-component-elevators", "rules",
                "editor-component-hazards", "rules", "editor-component-scenario", "rules")));

    @Test void layerRowsShowTheirType() throws Exception {
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(7, 5);
            hex(board, CASES.get(0).at(), "water:1;bridge:1:9;bridge_cf:40;bridge_elev:2",
                  object("bench", "prop", "scenery/parks/bench", -.2, null),
                  object("paint", "decal", "decal/damage/rubble-light-path", .2, null),
                  new BoardDecoration("route", "prop", RailRoute.Kind.MAGLEV.asset, null, 0, 0, 0, false, 1,
                        BoardDecoration.Placement.surface("bridge", "deck", 0), 0, false, 0, 0, null, false, 9),
                  object("car-1", "prop", "scenery/vehicles/car", -.3, "cars"),
                  object("car-2", "prop", "scenery/vehicles/car", .3, "cars").withColours(BoardDecoration.Colours.of("#142ba6")));
            hex(board, CASES.get(1).at(), "industrial_elevator:0:10;fire:1;deployment_zone:0:5");
            editor.game().setBoard(board); editor.command(new Command(Action.TOOL, "", "SELECT"), null);
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 120_000_000_000L;
                int step;
                long nextFrame;
                boolean scrolled, elevator, zone;

                @Override public void create() { super.create(); boardCamera.setIsometric(true); }

                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, () -> "Layer icons check stalled at step " + step);
                        if (GpuBoardTestUi.loading(this) || frames() < nextFrame) { return; }
                        int index = step / 2;
                        if (index == CASES.size()) { Gdx.app.exit(); return; }
                        Case shown = CASES.get(index);
                        if (step % 2 == 0) {
                            source.editorCommand(new Command(Action.SELECT_AT, shown.at().getX() + "," + shown.at().getY(), ""),
                                  source.takeFrame().boardGeneration());
                        } else {
                            var root = GpuBoardTestUi.stage().getRoot();
                            shown.icons().forEach((row, icon) -> {
                                UiButton button = root.findActor(row);
                                assertNotNull(button, "A Layers row " + row + " on " + shown.name());
                                var type = (Image) button.icons.getLast();
                                assertSame(button.getSkin().getDrawable("icon-" + icon), type.getDrawable(), row + " shows " + icon);
                                assertTrue(button.icons.stream().noneMatch(i -> ((Image) i).getDrawable() == button.getSkin().getDrawable("icon-unit")),
                                      row + " shows no unit shield");
                            });
                            if (index == 0) {
                                UiButton reorderable = root.findActor("editor-content-bench");
                                assertSame(reorderable.getSkin().getDrawable("icon-grip"), ((Image) reorderable.icons.getFirst()).getDrawable(),
                                      "A reorderable row leads with its drag handle");
                                // A repainted car names its colour; the model's own paint adds nothing.
                                assertTrue(GpuBoardTestUi.texts(root.findActor("editor-content-car-2")).contains("Car · Blue"));
                                assertTrue(GpuBoardTestUi.texts(root.findActor("editor-content-car-1")).contains("Car"));
                                // The rows' tooltips: the raw terrain codes, the whole hex on Ground, its own on a component.
                                assertEquals("water:1", tooltip(root.findActor("editor-component-water")));
                                assertTrue(tooltip(root.findActor("editor-component-ground")).contains("bridge:1:9"),
                                      tooltip(root.findActor("editor-component-ground")));
                            }
                            if (index == 1) {
                                if (!elevator) {
                                    // The industrial elevator's Edit fields with the old editor's labels and help.
                                    source.editorCommand(new Command(Action.COMPONENT, "elevators"), source.takeFrame().boardGeneration());
                                    elevator = true; nextFrame = frames() + 8; return;
                                }
                                var details = GpuBoardTestUi.texts(root.findActor("editor-details"));
                                if (!zone) {
                                    for (String text : List.of("Shaft bottom level", "Shaft top level", "Capacity (tons)",
                                          "All levels are counted from the hex surface")) {
                                        assertTrue(details.stream().anyMatch(t -> t.startsWith(text)), text + " in " + details);
                                    }
                                    // The deployment zone's Edit field: its IDs (mask 5 = zones 1 and 3), not its level.
                                    source.editorCommand(new Command(Action.COMPONENT, "scenario"), source.takeFrame().boardGeneration());
                                    zone = true; nextFrame = frames() + 8; return;
                                }
                                var ids = (com.badlogic.gdx.scenes.scene2d.ui.TextField) root.findActor("editor-zone-ids");
                                assertNotNull(ids, "The Zone IDs field");
                                assertEquals("1, 3", ids.getText());
                                assertTrue(details.stream().anyMatch(t -> t.equals("Zone IDs")), details.toString());
                            }
                            if (scrolled) {
                                GpuBoardTestUi.capture(new File(output, "editor-layer-icons-" + shown.name() + "-scrolled.png"));
                            } else {
                                GpuBoardTestUi.capture(new File(output, "editor-layer-icons-" + shown.name() + ".png"));
                                var layers = (com.badlogic.gdx.scenes.scene2d.ui.ScrollPane) root.findActor("editor-layers-scroll");
                                if (layers.getMaxY() > 0) {
                                    // The rest of a long Layers list, the group's members.
                                    layers.setScrollY(layers.getMaxY()); layers.updateVisualScroll();
                                    scrolled = true; nextFrame = frames() + 3; return;
                                }
                            }
                            scrolled = false;
                        }
                        step++; nextFrame = frames() + 10;
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, configuration());
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Layer icons failed", failure.get()); }
    }

    private static String tooltip(com.badlogic.gdx.scenes.scene2d.Actor actor) {
        for (var listener : actor.getListeners()) {
            if (listener instanceof com.badlogic.gdx.scenes.scene2d.ui.TextTooltip tip) { return tip.getActor().getText().toString(); }
        }
        return "";
    }

    private static void hex(Board board, Coords at, String terrain, BoardDecoration... objects) {
        Hex hex = new Hex(0, terrain, "");
        hex.setDecorations(List.of(objects));
        board.setHex(at, hex);
    }

    private static BoardDecoration object(String id, String kind, String asset, double x, String group) {
        return new BoardDecoration(id, kind, asset, null, x, 0, 0, false, .5, BoardDecoration.Placement.ground(), 0,
              "decal".equals(kind), 0, 0, group);
    }

    private static com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration configuration() {
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1920, 1080); return config;
    }
}
