/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.event.InputEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import megamek.client.ui.util.KeyCommandBind;

/** Shared native UI input and artwork assertions; all calls run on the GL thread. */
final class GpuBoardTestUi {
    /**
     * How long a board may show its loading screen. Its terrain is ready only once every shader program it draws with
     * has linked, and a program the program cache does not hold yet (a changed shader, another driver) compiles behind
     * the loading screen first.
     */
    static final int LOADING_SECONDS = 120;

    private GpuBoardTestUi() { }

    /**
     * Whether the battle view still shows its loading screen. The board appears only once its terrain is ready, so
     * scripted frames count from the first presented frame; a test's own tick counter must skip the loading frames.
     */
    static boolean loading(GpuBattleView view) {
        return field(view, "loadingStage") != null;
    }

    /**
     * Render until the board is presented and its terrain has settled: the loading screen has gone and no build or
     * detail job is pending, so a manually driven view inspects the outcome of its edits, not the frame before it.
     */
    static void present(GpuBattleView view) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(LOADING_SECONDS);
        // At least one frame: the loading screen appears only once a frame finds the terrain unbuilt.
        do {
            assertTrue(System.nanoTime() < deadline, () -> "Terrain loading must finish: " + loadingState(view));
            view.render();
        } while (loading(view) || busy(view));
    }

    /** Whether the view's terrain still has work: a rebuild, a requested build, detail or materials. */
    static boolean busy(GpuBattleView view) {
        GpuTerrain terrain = field(view, "terrain");
        return terrain != null && terrain.busy();
    }

    /**
     * The view's loading state for a timeout's message, as far as its terrain shows it, so a load that never finishes
     * names the stage it stopped in: the loading screen and the frames drawn, the terrain's readiness for the view's
     * board and its pending work, its build progress (-1 when nothing is building and its materials are ready; 100
     * while it waits for its materials, such as shader programs), its shader programs linked of those it needs, its
     * sections by state and each worker's step. GL thread.
     */
    static String loadingState(GpuBattleView view) {
        GpuTerrain terrain = field(view, "terrain");
        BoardScene scene = field(view, "scene");
        String state = "loading screen " + loading(view) + ", frames " + view.frames();
        if (terrain == null) {
            return state + ", no terrain yet";
        }
        var shaders = terrain.shaderProgress();
        return state + ", terrain ready " + (scene != null && terrain.ready(scene)) + ", busy " + terrain.busy()
              + ", build progress " + terrain.buildProgress() + ", shaders " + shaders.linked() + "/" + shaders.total()
              + ", sections "
              + terrain.buildSections().states().stream().collect(java.util.stream.Collectors.groupingBy(
                    section -> section, () -> new java.util.EnumMap<>(TerrainLoadProgress.SectionState.class),
                    java.util.stream.Collectors.counting()))
              + ", steps " + terrain.buildDetails().stream().map(TerrainLoadProgress.Status::text).toList();
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(GpuBattleView view, String name) {
        try {
            var field = GpuBattleView.class.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(view);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    /**
     * The window's stage: the HUD's, or while the window loads its loading screen's, which follows the fullscreen key
     * ({@code GpuBattleView.FullscreenKey}) among the input processors.
     */
    static Stage stage() {
        for (InputProcessor processor : ((InputMultiplexer) GpuHost.unguarded(Gdx.input.getInputProcessor()))
              .getProcessors()) {
            if (processor instanceof Stage stage) {
                return stage;
            }
        }
        throw new IllegalStateException("The window's input has no stage");
    }

    static void press(KeyCommandBind bind) {
        int key = java.util.stream.IntStream.rangeClosed(1, Input.Keys.MAX_KEYCODE)
              .filter(candidate -> GpuBattleView.awtKey(candidate) == bind.key).findFirst().orElseThrow();
        withModifiers(bind.modifiers, () -> {
            Gdx.input.getInputProcessor().keyDown(key);
            Gdx.input.getInputProcessor().keyUp(key);
        });
    }

    /** Runs {@code action} while the input reports the modifier keys of {@code modifiers} (InputEvent masks) held. */
    static void withModifiers(int modifiers, Runnable action) {
        Input original = Gdx.input;
        // Read first: the input may be a test's mock, which must not be called while another stub is being made.
        com.badlogic.gdx.InputProcessor processor = original.getInputProcessor();
        Input keyboard = mock(Input.class);
        when(keyboard.getInputProcessor()).thenReturn(processor);
        when(keyboard.isKeyPressed(Input.Keys.CONTROL_LEFT)).thenReturn((modifiers & InputEvent.CTRL_DOWN_MASK) != 0);
        when(keyboard.isKeyPressed(Input.Keys.SHIFT_LEFT)).thenReturn((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0);
        when(keyboard.isKeyPressed(Input.Keys.ALT_LEFT)).thenReturn((modifiers & InputEvent.ALT_DOWN_MASK) != 0);
        when(keyboard.isKeyPressed(Input.Keys.SYM)).thenReturn((modifiers & InputEvent.META_DOWN_MASK) != 0);
        Gdx.input = keyboard;
        try {
            action.run();
        } finally {
            Gdx.input = original;
        }
    }

    static void assertHorizontalBounds(Group group, Actor bounds) {
        for (Actor actor : group.getChildren()) {
            if (!actor.isVisible()) { continue; }
            Vector2 point = actor.localToAscendantCoordinates(bounds, new Vector2());
            assertTrue(point.x >= -1 && point.x + actor.getWidth() <= bounds.getWidth() + 1,
                  actor.getName() + " overflows its panel: x=" + point.x + ", width=" + actor.getWidth()
                        + ", panel=" + bounds.getWidth());
            if (actor instanceof Group child) {
                assertHorizontalBounds(child, bounds);
            }
        }
    }

    static void click(String name) {
        clickActor(stage().getRoot().findActor(name));
    }

    /**
     * Opens an Assets palette category: its tile on the start page (cleared of a search first), else the item of the
     * category name's drop-down on a category page (a gallery page, which the panel may reopen on, goes back to it).
     */
    static void category(String id) {
        Group root = stage().getRoot();
        if (!shown(root.findActor("editor-category-" + id)) && root.findActor("editor-category") == null) {
            ((com.badlogic.gdx.scenes.scene2d.ui.TextField) root.findActor("editor-search")).setText("");
        }
        if (!shown(root.findActor("editor-category-" + id)) && root.findActor("editor-category") == null) {
            click("editor-back");
            stage().draw(); // lays the category page out for the real clicks below
        }
        if (shown(root.findActor("editor-category-" + id))) { click("editor-category-" + id); return; }
        click("editor-category");
        click("editor-category-item-" + id);
    }

    /**
     * Scrolls the scroll pane holding the actor named {@code name}, such as a palette section's header, until the actor
     * stands at its top; the grid creates the cards that come into view on the next frame.
     */
    static void reveal(String name) {
        Actor actor = stage().getRoot().findActor(name);
        assertTrue(actor != null, "Missing " + name);
        for (Actor parent = actor.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof ScrollPane scroll) {
                Vector2 position = actor.localToAscendantCoordinates(scroll.getWidget(), new Vector2());
                scroll.setScrollY(scroll.getWidget().getHeight() - position.y - actor.getHeight());
                scroll.updateVisualScroll();
                return;
            }
        }
    }

    /** Whether the actor is on a stage and shown: it and every group above it are visible. */
    static boolean shown(Actor actor) {
        for (Actor each = actor; each != null; each = each.getParent()) {
            if (!each.isVisible()) {
                return false;
            }
        }
        return actor != null && actor.getStage() != null;
    }

    /** The texts of the visible, non-empty labels at or below {@code actor}, depth first; none for null. */
    static List<String> texts(Actor actor) {
        List<String> texts = new ArrayList<>();
        if (actor != null && actor.isVisible()) {
            if (actor instanceof Label label && label.getText().length() > 0) {
                texts.add(label.getText().toString());
            }
            if (actor instanceof Group group) {
                group.getChildren().forEach(child -> texts.addAll(texts(child)));
            }
        }
        return texts;
    }

    static void clickText(String label) {
        clickActor(buttonWithText(stage().getRoot(), label));
    }

    private static Actor buttonWithText(Group group, String label) {
        for (Actor actor : group.getChildren()) {
            if (actor.isVisible() && actor instanceof TextButton button && (button.getText().toString().equals(label)
                  || button.getText().toString().endsWith(" / " + label))) {
                return button;
            }
            if (actor.isVisible() && actor instanceof Group nested) {
                Actor match = buttonWithText(nested, label);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static void clickActor(Actor actor) {
        assertTrue(actor != null, "Missing UI action");
        // The battle HUD's camera presets, wireframe and tuning utilities hang in the View popover: open it first.
        Actor popover = stage().getRoot().findActor("utility-view-menu");
        if (popover != null && actor.isDescendantOf(popover) && !popover.isVisible()) {
            clickActor(stage().getRoot().findActor("utility-view"));
        }
        // Controls can move below the fold of a scrolling panel. Scroll them into view before real input.
        for (Actor parent = actor.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof ScrollPane scroll) {
                Vector2 position = actor.localToAscendantCoordinates(scroll.getWidget(), new Vector2());
                scroll.scrollTo(position.x, position.y, actor.getWidth(), actor.getHeight(), false, true);
                scroll.updateVisualScroll();
                stage().draw();
            }
        }
        Vector2 point = actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        stage().stageToScreenCoordinates(point);
        Gdx.input.getInputProcessor().touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
        Gdx.input.getInputProcessor().touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
    }

    static long capture(File file) {
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            PixmapIO.writePNG(new FileHandle(file), image, -1, true);
            long hash = 1;
            int changes = 0;
            int first = image.getPixel(10, 10);
            for (int y = 80; y < image.getHeight() - 70; y += 8) {
                for (int x = 20; x < image.getWidth() - 290; x += 8) {
                    int pixel = image.getPixel(x, y);
                    hash = hash * 31 + pixel;
                    if (pixel != first) {
                        changes++;
                    }
                }
            }
            assertTrue(changes > 1000, "The board must contain rendered artwork");
            return hash;
        } finally {
            image.dispose();
        }
    }

    /** The board view's tuning model, which the HUD's tuning panel edits. */
    static GpuBoardTuning tuning(GpuBattleView view) {
        try {
            var field = GpuBattleView.class.getDeclaredField("tuning");
            field.setAccessible(true);
            return (GpuBoardTuning) field.get(view);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    /**
     * The control named {@code name} of the board view's tuning model, such as "Time of day" or "tuning-defaults".
     * Setting it applies the value, as the HUD's tuning panel does through the same control.
     */
    static <T extends Actor> T tuning(GpuBattleView view, String name) {
        return tuning(tuning(view), name);
    }

    /** The control named {@code name} on any page of a tuning model, or one of its footer buttons. */
    @SuppressWarnings("unchecked")
    static <T extends Actor> T tuning(GpuBoardTuning tuning, String name) {
        Actor control = Stream.of(tuning.defaults(), tuning.reloadAssets(), tuning.editShaders())
              .filter(button -> name.equals(button.getName())).findFirst().orElse(null);
        for (Table page : List.of(tuning.cameraRows(), tuning.displayRows(), tuning.boardRows(), tuning.atmosphereRows(),
              tuning.terrainRows())) {
            control = control != null ? control : page.findActor(name);
        }
        assertTrue(control != null, "Missing tuning control " + name);
        return (T) control;
    }

    /**
     * Presses a button of the tuning model, as the tuning panel does: a checkbox switches, a mode of a group is chosen,
     * any other button acts.
     */
    static void pressTuning(TextButton button) {
        if (button instanceof CheckBox box) {
            box.toggle();
        } else if (button.getButtonGroup() != null) {
            button.setChecked(true);
        } else {
            button.fire(new ChangeListener.ChangeEvent());
        }
    }
}
