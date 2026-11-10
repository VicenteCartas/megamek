/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Assets palette in the real panel: the start page's ten category tiles fit without scrolling and show their
 * rendered icons; a tile opens its category, [<] goes back and the category's name jumps to another; a subcategory's card
 * opens its page, where a lone picker family's section lists its members and the card then shows the armed member; the
 * search covers every category and the designs behind a gallery card, under a "Search results" header; Esc clears the
 * search, then leaves a gallery or subcategory, then a category; a Theme card arms its theme's brush (Radius, an unticked Level and Only overwrite
 * clear hexes; no Theme row) and stays highlighted; Ground & water's Liquids hold Water and Lava; the Water card's Brush
 * offers Depth and Current, and the cards then preview clear water, since Water does not paint the Volcano theme it
 * keeps; the Lava card previews lava and its Brush offers Depth only; "lava" and "magma" find it; a picker family's Type
 * changes the brush's asset;
 * and a new editor window opens on the page the last one showed. Captures under b12-palette at 1366x768 and 1280x800.
 */
@Tag("on-demand")
class GpuEditorPaletteSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "b12-palette");

    /** One step of a run: false while it waits for something (previews, the board) and runs again next frame. */
    interface Step { boolean run(GpuBattleView view, GpuMapSource source, Group root) throws Exception; }

    @Test void paletteNavigatesSearchesAndArmsItsCards() throws Exception {
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        run(1366, 768, List.of(
              (view, source, root) -> {
                  // An editor opened before in this JVM left its page: back to the start page first.
                  if (root.findActor("editor-back") != null) { GpuBoardTestUi.click("editor-back"); return false; }
                  if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-category-scroll"))) { return false; }
                  for (var category : BoardEditorBlueprint.get().categories()) {
                      assertTrue(GpuBoardTestUi.shown(root.findActor("editor-category-" + category.id())), category.id());
                  }
                  assertEquals(0, ((ScrollPane) root.findActor("editor-category-scroll")).getMaxY(), .5f, "Ten tiles without scrolling");
                  assertNull(root.findActor("editor-back"), "The start page has no back button");
                  GpuBoardTestUi.capture(new File(OUTPUT, "start-1366x768.png"));
                  GpuBoardTestUi.click("editor-category-ground-water");
                  return true;
              },
              (view, source, root) -> { assertNotNull(root.findActor("editor-category")); return true; }));
        run(1280, 800, List.of(
              // The new window reopens on Ground & water: Basic, Gameplay and Liquids.
              (view, source, root) -> {
                  for (String section : List.of("basic", "gameplay", "liquids")) {
                      assertNotNull(root.findActor("editor-section-ground-water/" + section), section);
                  }
                  if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-library"))) { return false; }
                  GpuBoardTestUi.capture(new File(OUTPUT, "ground-water.png"));
                  GpuBoardTestUi.reveal("editor-section-ground-water/liquids");
                  return true;
              },
              (view, source, root) -> {
                  assertNotNull(root.findActor("editor-library-water/shallow"));
                  assertNotNull(root.findActor("editor-library-water/magma"), "Lava beside Water");
                  if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-library"))) { return false; }
                  GpuBoardTestUi.capture(new File(OUTPUT, "liquids.png"));
                  GpuBoardTestUi.click("editor-back");
                  return true;
              },
              (view, source, root) -> {
                  assertTrue(GpuBoardTestUi.shown(root.findActor("editor-category-vegetation")), "Back on the start page");
                  if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-category-scroll"))) { return false; }
                  GpuBoardTestUi.capture(new File(OUTPUT, "start-1280x800.png"));
                  GpuBoardTestUi.click("editor-category-vegetation");
                  return true;
              },
              (view, source, root) -> {
                  assertNotNull(root.findActor("editor-subcategory-trees"));
                  assertNull(root.findActor("editor-section-vegetation/broadleaf"), "Behind the Trees card");
                  assertNotNull(root.findActor("editor-library-woods/crystal"), "The crystal cover skin stays in Vegetation");
                  assertNull(root.findActor("editor-subcategory-crystals"), "The crystal props do not");
                  if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-library"))) { return false; }
                  GpuBoardTestUi.capture(new File(OUTPUT, "vegetation.png"));
                  GpuBoardTestUi.click("editor-subcategory-trees");
                  return true;
              },
              (view, source, root) -> {
                  assertNotNull(root.findActor("editor-section-vegetation/broadleaf"));
                  if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-library"))) { return false; }
                  GpuBoardTestUi.capture(new File(OUTPUT, "trees.png"));
                  GpuBoardTestUi.category("props");
                  return true;
              },
              (view, source, root) -> {
                  if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-library"))) { return false; }
                  GpuBoardTestUi.capture(new File(OUTPUT, "props.png"));
                  GpuBoardTestUi.click("editor-subcategory-crystals");
                  return true;
              },
              (view, source, root) -> {
                  assertNull(root.findActor("editor-category"), "A subcategory's header names it");
                  assertEquals(UiTheme.upper(UiKit.text("BoardEditor.palette.props.crystals")),
                        ((Label) root.findActor("editor-library-title")).getText().toString());
                  for (String section : List.of("crystal-clusters", "giant-crystals", "single-crystals")) {
                      assertNotNull(root.findActor("editor-section-props/" + section), section);
                  }
                  assertNotNull(root.findActor("editor-library-crystal/cover-spire"), "A family's member has its own card");
                  if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-library"))) { return false; }
                  GpuBoardTestUi.capture(new File(OUTPUT, "crystals.png"));
                  GpuBoardTestUi.click("editor-library-crystal/cover-spire");
                  return true;
              },
              (view, source, root) -> {
                  assertEquals("crystal/cover-spire", source.editorState().activeBrush().key());
                  assertNotNull(((Group) root.findActor("editor-brush")).findActor("editor-choice-Type"), "The member keeps its family's Type row");
                  assertTrue(root.findActor("editor-category-mark-props").isVisible());
                  escape();
                  return true;
              },
              (view, source, root) -> {
                  assertNotNull(root.findActor("editor-category"), "Esc: from the subcategory back to its category");
                  assertTrue(((UiButton) root.findActor("editor-subcategory-crystals")).isChecked(), "Its card shows the armed crystal");
                  GpuBoardTestUi.click("editor-category");
                  return true;
              },
              (view, source, root) -> { GpuBoardTestUi.click("editor-category-item-vegetation"); return true; },
              (view, source, root) -> {
                  GpuBoardTestUi.click("editor-category");
                  return true;
              },
              (view, source, root) -> { GpuBoardTestUi.click("editor-category-item-buildings"); return true; },
              (view, source, root) -> {
                  assertNotNull(root.findActor("editor-section-buildings/looks"), "The list jumps to Buildings");
                  for (String section : List.of("standard", "fortresses", "hangars", "gun-emplacements", "walls")) {
                      assertNotNull(root.findActor("editor-section-buildings/" + section));
                  }
                  if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-library"))) { return false; }
                  GpuBoardTestUi.capture(new File(OUTPUT, "buildings.png"));
                  search(root, "trees");
                  return true;
              },
              (view, source, root) -> {
                  for (String category : List.of("ground-water", "vegetation", "transportation")) {
                      assertNotNull(root.findActor("editor-section-" + category), "trees in " + category);
                  }
                  assertNull(root.findActor("editor-section-buildings"));
                  assertNotNull(root.findActor("editor-library-rough/felled-trees"));
                  assertEquals(UiTheme.upper(UiKit.text("BoardEditor.searchResults")),
                        ((Label) root.findActor("editor-library-title")).getText().toString(), "The results span every category");
                  if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-library"))) { return false; }
                  GpuBoardTestUi.capture(new File(OUTPUT, "search-tree.png"));
                  escape();
                  return true;
              },
              (view, source, root) -> {
                  assertEquals("", ((TextField) root.findActor("editor-search")).getText(), "Esc clears the search first");
                  assertNotNull(root.findActor("editor-section-buildings/looks"));
                  assertNotNull(root.findActor("editor-category"), "The category's drop-down is back");
                  GpuBoardTestUi.category("vegetation");
                  return true;
              },
              (view, source, root) -> {
                  GpuBoardTestUi.click("editor-gallery-Container yard");
                  return true;
              },
              (view, source, root) -> {
                  assertNull(root.findActor("editor-category"), "A gallery's header names it");
                  if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-library"))) { return false; }
                  GpuBoardTestUi.capture(new File(OUTPUT, "gallery-container-yard.png"));
                  search(root, yardDesign().label());
                  return true;
              },
              (view, source, root) -> {
                  assertNotNull(root.findActor("editor-library-" + yardDesign().key()), "The search reaches inside the gallery");
                  escape();
                  return true;
              },
              (view, source, root) -> {
                  assertNotNull(root.findActor("editor-back"));
                  assertNull(root.findActor("editor-category"), "Esc: from the search back to the gallery");
                  escape();
                  return true;
              },
              (view, source, root) -> {
                  assertNotNull(root.findActor("editor-category"), "Esc: from the gallery back to its category");
                  escape();
                  return true;
              },
              (view, source, root) -> {
                  assertNull(root.findActor("editor-back"), "Esc: from the category back to the start page");
                  escape();
                  return true;
              },
              (view, source, root) -> {
                  assertTrue(GpuBoardTestUi.shown(root.findActor("menu-panel")), "With nothing left, Esc opens the Menu");
                  escape();
                  return true;
              },
              (view, source, root) -> {
                  assertFalse(GpuBoardTestUi.shown(root.findActor("menu-panel")), "and the next Esc closes it");
                  GpuBoardTestUi.category("theme");
                  return true;
              },
              (view, source, root) -> {
                  assertNotNull(root.findActor("editor-section-theme/special"));
                  assertNotNull(root.findActor("editor-library-theme/rock"));
                  if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-library"))) { return false; }
                  GpuBoardTestUi.capture(new File(OUTPUT, "theme.png"));
                  GpuBoardTestUi.click("editor-library-theme/volcano");
                  return true;
              },
              (view, source, root) -> {
                  var brush = source.editorState().activeBrush();
                  assertEquals(List.of("theme/volcano", true, "volcano", false),
                        List.of(brush.key(), brush.themeEnabled(), brush.theme(), brush.elevationEnabled()));
                  assertTrue(((UiButton) root.findActor("editor-library-theme/volcano")).isChecked(), "The Theme card stays highlighted");
                  assertTrue(root.findActor("editor-category-mark-theme").isVisible(), "The Theme tile marks the brush's category");
                  // The card is its theme: Radius, an unticked Level and Only overwrite clear hexes, no Theme row.
                  Group panel = root.findActor("editor-brush");
                  assertNotNull(panel.findActor("editor-brush-"), "Radius");
                  assertNotNull(panel.findActor("editor-brush-elevation"), "Level");
                  assertNotNull(panel.findActor("editor-clear-only"));
                  assertNull(panel.findActor("editor-brush-theme"), "No Theme row");
                  assertEquals(UiKit.text("BoardEditor.hint.theme"), ((Label) panel.findActor("editor-brush-hint")).getText().toString());
                  GpuBoardTestUi.capture(new File(OUTPUT, "theme-brush.png"));
                  GpuBoardTestUi.category("ground-water");
                  return true;
              },
              (view, source, root) -> { GpuBoardTestUi.reveal("editor-section-ground-water/liquids"); return true; },
              (view, source, root) -> { GpuBoardTestUi.click("editor-library-water/shallow"); return true; },
              (view, source, root) -> {
                  Group brush = root.findActor("editor-brush");
                  assertNotNull(brush.findActor("editor-brush-terrain:water"), "Depth");
                  assertNotNull(brush.findActor("editor-brush-terrain:rapids"), "Current");
                  assertTrue(root.findActor("editor-category-mark-ground-water").isVisible());
                  if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-library"))) { return false; }
                  // The Water brush keeps the Volcano card's theme but does not paint it: its card shows clear water.
                  Color water = hexTop(root.findActor("editor-library-water/shallow"));
                  assertTrue(water.b > water.r, "Clear blue water, not the volcano theme's: " + water);
                  GpuBoardTestUi.capture(new File(OUTPUT, "water-brush.png"));
                  GpuBoardTestUi.click("editor-library-water/magma");
                  return true;
              },
              (view, source, root) -> {
                  assertEquals("water/magma", source.editorState().activeBrush().key());
                  Group brush = root.findActor("editor-brush");
                  assertNotNull(brush.findActor("editor-brush-terrain:water"), "Depth");
                  assertNull(brush.findActor("editor-brush-terrain:rapids"), "Lava has no current");
                  if (!GpuEditorMaglevSmokeTest.ready(root.findActor("editor-library"))) { return false; }
                  Color lava = hexTop(root.findActor("editor-library-water/magma"));
                  assertTrue(lava.r > lava.b, "The Lava card shows lava: " + lava);
                  GpuBoardTestUi.capture(new File(OUTPUT, "lava-brush.png"));
                  search(root, "lava");
                  return true;
              },
              (view, source, root) -> {
                  assertNotNull(root.findActor("editor-library-water/magma"), "lava finds the Lava card");
                  search(root, "magma");
                  return true;
              },
              (view, source, root) -> {
                  assertNotNull(root.findActor("editor-library-water/magma"), "magma finds the Lava card");
                  assertNotNull(root.findActor("editor-library-hazards/magma-crust"), "and the Hazards' Magma card");
                  search(root, "landing beacon");
                  return true;
              },
              (view, source, root) -> { GpuBoardTestUi.click("editor-library-scenery/roofs/landing-beacon-hex"); return true; },
              (view, source, root) -> {
                  assertEquals("scenery/roofs/landing-beacon-hex", source.editorState().activeBrush().key());
                  GpuBoardTestUi.click("editor-choice-Type");
                  return true;
              },
              (view, source, root) -> { GpuBoardTestUi.clickText("Round landing beacon"); return true; },
              (view, source, root) -> {
                  assertEquals("scenery/roofs/landing-beacon-round", source.editorState().activeBrush().key(), "Type re-arms the member");
                  assertTrue(((UiButton) root.findActor("editor-library-scenery/roofs/landing-beacon-hex")).isChecked(),
                        "The family's card stays highlighted");
                  return true;
              }));
    }

    /**
     * On a Grassland board (no theme) whose hexes have the Grass look and sand, one a pond and one a car: a Mars Theme
     * stroke gives them Mars and Clear ground and keeps the sand, the pond and the car; then a lava lake painted with the
     * Lava card beside a lake of the Water card. Captures theme-before.png, theme-after.png and lava-beside-water.png.
     */
    @Test void aThemeStrokeKeepsTheHexesAndLavaLiesBesideWater() throws Exception {
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        List<Coords> stroke = List.of(new Coords(3, 4), new Coords(5, 4), new Coords(7, 4));
        Coords pond = new Coords(4, 4), car = new Coords(6, 4), lava = new Coords(3, 9), water = new Coords(6, 9);
        long[] generation = { 0 };
        run(1366, 768, () -> {
            Board board = Board.createEmptyBoard(12, 12);
            var grass = new HexAppearance("ground/blend/grass", null, null, .5);
            for (int x = 0; x < 12; x++) {
                for (int y = 0; y < 12; y++) {
                    Coords at = new Coords(x, y);
                    Hex hex = new Hex(0, at.equals(pond) ? "water:1;sand:1" : y < 7 ? "sand:1" : "", "");
                    hex.setAppearance(Map.of("ground", grass));
                    if (at.equals(car)) {
                        hex.setDecorations(List.of(new BoardDecoration("car", "prop", "scenery/vehicles/car", null, 0, 0, 30, false, 2.5,
                              BoardDecoration.Placement.ground(), 0)));
                    }
                    board.setHex(at, hex);
                }
            }
            return board;
        }, List.of(
              (view, source, root) -> {
                  if (root.findActor("editor-back") != null) { GpuBoardTestUi.click("editor-back"); return false; }
                  view.boardCamera.center(BoardGeometry.center(pond.translated(2), 0));
                  view.boardCamera.zoom(.45f / view.boardCamera.camera.zoom);
                  return true;
              },
              (view, source, root) -> {
                  if (busy(view)) { return false; }
                  GpuBoardTestUi.capture(new File(OUTPUT, "theme-before.png"));
                  GpuBoardTestUi.category("theme");
                  return true;
              },
              (view, source, root) -> { GpuBoardTestUi.click("editor-library-theme/mars"); return true; },
              (view, source, root) -> {
                  assertEquals("theme/mars", source.editorState().activeBrush().key());
                  generation[0] = source.takeFrame().boardGeneration();
                  source.editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.BRUSH, "", "2"), generation[0]);
                  source.editorPointer(stroke.getFirst(), 0, 0, false, generation[0]);
                  stroke.stream().skip(1).forEach(at -> source.editorPointer(at, 0, 0, true, generation[0]));
                  source.endEditorStroke();
                  return true;
              },
              (view, source, root) -> {
                  var scene = source.takeFrame().scene();
                  if (busy(view) || scene.tile(pond).appearance().containsKey("ground")) { return false; }
                  for (Coords at : List.of(pond, car, stroke.getFirst(), stroke.getLast())) {
                      assertTrue(scene.tile(at).appearance().isEmpty(), "Clear ground at " + at);
                  }
                  assertEquals(BoardLiquid.Kind.WATER, scene.tile(pond).liquid().kind(), "The pond stays");
                  assertEquals("mars", scene.tile(pond).liquid().theme(), "as Mars water");
                  assertTrue(scene.tile(car).features().stream().anyMatch(f -> f.asset().equals("scenery/vehicles/car")), "The car stays");
                  assertTrue(source.editorState().canUndo());
                  GpuBoardTestUi.capture(new File(OUTPUT, "theme-after.png"));
                  GpuBoardTestUi.category("ground-water");
                  return true;
              },
              (view, source, root) -> { GpuBoardTestUi.reveal("editor-section-ground-water/liquids"); return true; },
              (view, source, root) -> { GpuBoardTestUi.click("editor-library-water/magma"); return true; },
              (view, source, root) -> {
                  generation[0] = source.takeFrame().boardGeneration();
                  source.editorCommand(new BoardEditorSession.Command(BoardEditorSession.Action.BRUSH_VALUE, "terrain:water", "2"), generation[0]);
                  source.editorPointer(lava, 0, 0, false, generation[0]); source.endEditorStroke();
                  return true;
              },
              (view, source, root) -> { GpuBoardTestUi.click("editor-library-water/shallow"); return true; },
              (view, source, root) -> {
                  generation[0] = source.takeFrame().boardGeneration();
                  source.editorPointer(water, 0, 0, false, generation[0]); source.endEditorStroke();
                  view.boardCamera.center(BoardGeometry.center(new Coords(5, 9), 0));
                  return true;
              },
              (view, source, root) -> {
                  var scene = source.takeFrame().scene();
                  if (busy(view) || !scene.tile(water).liquid().present()) { return false; }
                  assertTrue(scene.tile(lava).liquid().molten() && scene.tile(lava).waterDepth() == 2, "A lava lake two levels deep");
                  assertEquals(BoardLiquid.Kind.WATER, scene.tile(water).liquid().kind());
                  GpuBoardTestUi.capture(new File(OUTPUT, "lava-beside-water.png"));
                  return true;
              }));
    }

    /** A design inside a current gallery. */
    private static BoardEditorBlueprint.Entry yardDesign() {
        return BoardEditorBlueprint.get().categories().stream().flatMap(c -> c.sections().stream()).flatMap(s -> s.cards().stream())
              .filter(card -> card.label().equals("Container yard")).findFirst().orElseThrow().members().get(2);
    }

    private static boolean busy(GpuBattleView view) throws ReflectiveOperationException {
        var field = GpuBattleView.class.getDeclaredField("terrain"); field.setAccessible(true);
        return ((GpuTerrain) field.get(view)).busy();
    }

    /** The mean colour on screen of a card preview's hex top: a small square in the middle of the card's upper part. */
    private static Color hexTop(Actor card) {
        Vector2 middle = GpuBoardTestUi.stage().stageToScreenCoordinates(
              card.localToStageCoordinates(new Vector2(card.getWidth() / 2, card.getHeight() * .65f)));
        float scale = Gdx.graphics.getBackBufferWidth() / (float) Gdx.graphics.getWidth();
        int size = Math.round(card.getWidth() * .1f * scale);
        Pixmap pixels = Pixmap.createFromFrameBuffer(Math.round(middle.x * scale) - size / 2,
              Math.round(Gdx.graphics.getBackBufferHeight() - middle.y * scale) - size / 2, size, size);
        try {
            float r = 0, g = 0, b = 0;
            Color pixel = new Color();
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    Color.rgba8888ToColor(pixel, pixels.getPixel(x, y)); r += pixel.r; g += pixel.g; b += pixel.b;
                }
            }
            float count = size * size;
            return new Color(r / count, g / count, b / count, 1);
        } finally { pixels.dispose(); }
    }

    private static void search(Group root, String text) { ((TextField) root.findActor("editor-search")).setText(text); }

    private static void escape() {
        Gdx.input.getInputProcessor().keyDown(Input.Keys.ESCAPE);
        Gdx.input.getInputProcessor().keyUp(Input.Keys.ESCAPE);
    }

    /** Opens an editor window of {@code width} x {@code height} on a pavement board and runs {@code steps} in order. */
    static void run(int width, int height, List<Step> steps) throws Exception {
        run(width, height, () -> {
            Board board = Board.createEmptyBoard(12, 12);
            for (int x = 0; x < 12; x++) {
                for (int y = 0; y < 12; y++) { board.setHex(new Coords(x, y), new Hex(0, "pavement:1", "")); }
            }
            return board;
        }, steps);
    }

    /** Opens an editor window of {@code width} x {@code height} on the board {@code board} makes (on the EDT) and runs {@code steps}. */
    static void run(int width, int height, Supplier<Board> board, List<Step> steps) throws Exception {
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            editor.game().setBoard(board.get());
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(width, height);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 300_000_000_000L;
                int step;
                long after;
                AtomicBoolean applied = new AtomicBoolean(true);

                @Override public void create() { super.create(); boardCamera.setIsometric(true); }

                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "The palette stalled at step " + step + " of " + width + "x" + height);
                        if (!applied.get()) { after = frames() + 8; return; }
                        if (GpuBoardTestUi.loading(this) || frames() < after) { return; }
                        if (step == steps.size()) { Gdx.app.exit(); return; }
                        if (steps.get(step).run(this, source, GpuBoardTestUi.stage().getRoot())) {
                            // The next step waits for the EDT to apply this one's commands, then for the frames that show them.
                            step++; after = frames() + 8; applied = new AtomicBoolean();
                            var marker = applied; SwingUtilities.invokeLater(() -> marker.set(true));
                        }
                    } catch (Throwable error) {
                        GpuBoardTestUi.capture(new File(OUTPUT, "failure-" + width + "x" + height + ".png"));
                        failure.set(error); Gdx.app.exit();
                    }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("The palette failed at " + width + "x" + height, failure.get()); }
    }
}
