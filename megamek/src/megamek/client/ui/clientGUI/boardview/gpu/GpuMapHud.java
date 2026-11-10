/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHudState.Dialog;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiFileDialog;
import megamek.client.ui.gdx.UiHexSides;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiMessageDialog;
import megamek.client.ui.gdx.UiModal;
import megamek.client.ui.gdx.UiPopover;
import megamek.common.board.Coords;

/**
 * Native map workspace: the standalone editor uses UIKit panels; previews use a corner hex card and native LOS.
 * Both presentations share the camera utilities and developer tuning panel.
 */
final class GpuMapHud implements GpuBoardHud {
    /** The hex card's width, in stage units. */
    private static final float CARD_WIDTH = GpuMapHexCard.WIDTH;

    private final Stage stage;
    private final GpuBoardEditor editor;
    private final GpuEditorNorth north;
    /** Below the editor's panels, as the north marker is: a panel over the building covers its handles. */
    private final GpuBuildingDesigner designer;
    private final BoardSource source;
    private final BoardCamera camera;
    private final GpuBoardTuning tuningModel;
    private final GpuHudKit kit;
    private final GpuHudState state;
    private final GpuTuningPanel tuning;
    private final UiFileDialog files;
    private GpuEditorFiles.Prompt filePrompt;
    private UiModal fileQuestion;
    private final Container<Actor> tuningSlot;
    private final Table top = new Table();
    private final Table utilityRow = new Table();
    private final UiButton isometric;
    private final UiButton tactical;
    private final UiButton wireframe;
    private final UiButton menu;
    private final UiModal menuLayer;
    private final Container<Table> menuSlot;
    private final GpuCommandTree tree;
    private final Table bottom = new Table();
    /** The running print's card at the bottom centre, hidden while nothing prints. */
    private final Table printSlot = new Table();
    private final UiKit.Progress printProgress;
    private GpuBoardPrint shownPrint;
    private final GpuLosPanel losPanel;
    private final UiPopover contextMenu;
    private long boardGeneration;
    /** The hovered or inspected hex's card in the bottom-right corner. */
    private final GpuMapHexCard hexCard;
    private GpuHud.Metrics metrics;
    private float scale = 1;
    private BoardScene scene;
    /** The About card from the Menu, once opened; GPU thread. */
    private UiDialog about;
    private static final String ABOUT = "helpAbout";

    /** {@code tuningModel} is the model the board view reads; {@code history} the view's (no game plays here). */
    GpuMapHud(BoardSource source, Skin skin, Batch batch, BoardCamera camera, GpuBoardTuning tuningModel,
          GpuPlaybackHistory history, java.util.function.Supplier<GpuTerrain> terrain) {
        this.source = source;
        this.camera = camera;
        this.tuningModel = tuningModel;
        kit = new GpuHudKit(skin);
        UiKit ui = kit.ui;
        files = new UiFileDialog(ui);
        state = new GpuHudState(history);
        stage = new Stage(new ScreenViewport(), batch);
        north = source.isEditor() ? new GpuEditorNorth(ui) : null;
        designer = source.isEditor() ? new GpuBuildingDesigner(ui) : null;
        if (north != null) {
            stage.addActor(north);
            stage.addActor(designer);
        }

        isometric = GpuUtilityBar.cameraButtons(ui, utilityRow, camera, () -> {
            if (scene != null) { camera.fit(scene, framingLeft(), framingWidth(), framingBottom(), framingTop()); }
        });
        tactical = utility(GpuUtilityBar.utility(ui, "tactical", "GpuBoard.hud.util.tactical", "utility-tactical"));
        onChange(tactical, () -> camera.setTactical(!camera.tactical(), scene));
        wireframe = utility(GpuUtilityBar.utility(ui, "wireframe", "GpuBoard.hud.util.wireframe",
              "utility-wireframe"));
        onChange(wireframe, () -> tuningModel.setWireframe(!tuningModel.wireframe()));
        tuning = new GpuTuningPanel(kit, source, state, camera, tuningModel);
        utility((UiButton) tuning.button());

        top.setFillParent(true);
        utilityRow.setName("map-utilities");
        top.setTouchable(Touchable.childrenOnly);
        top.top().right().add(utilityRow);
        stage.addActor(top);
        tuningSlot = new Container<>(tuning.actor()).fill();
        tuningSlot.setFillParent(true);
        tuningSlot.setTouchable(Touchable.childrenOnly);
        stage.addActor(tuningSlot);

        hexCard = new GpuMapHexCard(ui);
        bottom.setFillParent(true);
        bottom.setTouchable(Touchable.disabled);
        bottom.bottom().right();
        stage.addActor(bottom);
        printProgress = ui.progress(Messages.getString("GpuBoard.print.preparing"));
        UiButton cancelPrint = ui.button("hud-mini", null, UiKit.text("Cancel"), null);
        cancelPrint.setName("map-print-cancel");
        onChange(cancelPrint, () -> { if (shownPrint != null) { shownPrint.cancel(); } });
        Table printCard = ui.panel();
        printCard.setTouchable(Touchable.enabled);
        printSlot.setName("map-print-progress");
        printCard.add(ui.header(Messages.getString("CommonMenuBar.PrintMenu"), null, cancelPrint)).growX().row();
        printCard.add(printProgress).growX().pad(0, 14, 14, 14);
        printSlot.setFillParent(true);
        printSlot.setTouchable(Touchable.childrenOnly);
        printSlot.bottom().add(printCard).width(CARD_WIDTH);
        printSlot.setVisible(false);
        stage.addActor(printSlot);
        losPanel = new GpuLosPanel(ui, stage, source, camera);
        stage.addActor(losPanel.actor());
        contextMenu = new UiPopover(ui);
        contextMenu.setName("map-context-menu");
        stage.addActor(contextMenu);

        Table dialog = ui.dialog(Messages.getString("GpuBoard.hud.menu.title"), () -> state.dialog = Dialog.NONE);
        dialog.setName("menu-panel");
        dialog.setTouchable(Touchable.enabled);
        tree = new GpuCommandTree(ui, dialog);
        dialog.add(tree.body).grow().minHeight(0);
        menuSlot = new Container<>(dialog).fill();
        menuLayer = new UiModal(ui, () -> state.dialog = Dialog.NONE);
        menuLayer.setName("map-menu-modal");
        menuLayer.addActor(menuSlot);
        stage.addActor(menuLayer);
        metrics = GpuHud.Metrics.of(stage.getWidth(), stage.getHeight());
        editor = source.isEditor() ? new GpuBoardEditor(source, ui, stage, terrain, this::frameHex, camera::azimuth) : null;
        if (editor != null) {
            UiButton settings = utility(GpuUtilityBar.compact(ui, ui.button("hud-utility", "settings",
                  Messages.getString("BoardEditor.settingsButton"), null)));
            settings.setName("editor-settings-button");
            onChange(settings, () -> { state.dialog = Dialog.NONE; editor.openSettings(); });
            onChange(tuning.button(), editor::closeSettings);
        }
        menu = GpuUtilityBar.iconButton(ui, "menu", Messages.getString("GpuBoard.hud.menu.title"), "utility-menu");
        utilityRow.add(menu).size(GpuUtilityBar.HEIGHT).padLeft(GpuUtilityBar.GAP);
        onChange(menu, () -> {
            contextMenu.cancel();
            if (editor != null) { editor.closeSettings(); editor.closeHelp(); }
            state.toggle(Dialog.MENU);
        });
        top.toFront(); tuningSlot.toFront();
    }

    private UiButton utility(UiButton button) {
        float gap = utilityRow.getCells().isEmpty() ? 0 : GpuUtilityBar.GAP;
        utilityRow.add(button).height(GpuUtilityBar.HEIGHT).padLeft(gap);
        return button;
    }

    @Override
    public Stage stage() {
        return stage;
    }

    @Override
    public megamek.client.ui.gdx.UiKit uiKit() { return kit.ui; }

    @Override
    public void resize(int width, int height, float displayScale) {
        // Keep the complete editing workspace usable when a large desktop UI scale meets a smaller window. The Tuning
        // panel's UI scale, already a share of displayScale, is a share of that limit too, so each of its steps shows.
        scale = editor == null ? displayScale
              : Math.min(displayScale, Math.min(width / 1280f, height / 800f) * source.uiPreferences().scale());
        ((ScreenViewport) stage.getViewport()).setUnitsPerPixel(1 / scale);
        stage.getViewport().update(width, height, true);
        metrics = GpuHud.Metrics.of(stage.getWidth(), stage.getHeight());
        top.pad(metrics.gap(), 0, 0, metrics.gap());
        bottom.padBottom(metrics.gap()).padRight(metrics.gap());
        losPanel.actor().setSize(metrics.width(), metrics.height());
        layoutBottom();
        layoutEditor();
    }

    private void layoutEditor() {
        if (editor != null) { editor.layout(metrics.width(), metrics.height(), utilityRow.getPrefWidth(), utilityRow.getPrefHeight()); }
    }

    @Override
    public void update(BoardSource.Frame frame, GpuHud.HudView view, GpuBoardWindow.DialogRequest dialog,
          BoardSource.UiPreferences preferences) {
        scene = frame.scene();
        if (boardGeneration != frame.boardGeneration()) { contextMenu.cancel(); }
        boardGeneration = frame.boardGeneration();
        // Only an open menu pins the hex card; every dismissal path returns it to the latest hover.
        if (!contextMenu.isVisible() && frame.context() != null) { source.inspect(null); }
        isometric.setDisabled(camera.tactical());
        tactical.pressed(camera.tactical());
        wireframe.pressed(tuningModel.wireframe()).setDisabled(!GpuWireframe.supported());
        menu.pressed(state.dialog == Dialog.MENU);
        tuning.update(new GpuHud.Inputs(frame, view, dialog, preferences, metrics, List.of()));
        if (state.dialog == Dialog.TUNING && UiModal.active(stage) == null) { tuningSlot.toFront(); }
        showMenu(frame.globalCommands());
        List<com.badlogic.gdx.math.Rectangle> occupied = new ArrayList<>();
        if (editor == null) {
            showCard(frame.tooltip());
            bottom.validate();
            top.validate();
            var bounds = hexCard.actor().localToStageCoordinates(new Vector2());
            if (hexCard.actor().hasChildren()) {
                occupied.add(new com.badlogic.gdx.math.Rectangle(bounds.x, bounds.y,
                      hexCard.actor().getWidth(), hexCard.actor().getHeight()));
            }
        } else {
            editor.update(frame.boardGeneration());
            north.update(scene, source.editorState() == null ? null : source.editorState().selected(), camera);
            long generation = frame.boardGeneration();
            designer.update(scene, source.editorState(), camera, command -> source.editorCommand(command, generation));
            // Keep the shared ruler in the editor's board area, clear of its columns, toolbar and corner panels.
            float left = GpuBoardEditor.LIBRARY_WIDTH + 2 * GAP;
            float right = metrics.width() - GpuBoardEditor.INSPECTOR_WIDTH - 2 * GAP;
            occupied.add(new com.badlogic.gdx.math.Rectangle(0, 0, left, metrics.height()));
            occupied.add(new com.badlogic.gdx.math.Rectangle(right, 0, metrics.width() - right, metrics.height()));
            occupied.add(new com.badlogic.gdx.math.Rectangle(left, 0, right - left, editor.bottomInset()));
            occupied.add(new com.badlogic.gdx.math.Rectangle(left, metrics.height() - editor.topInset(),
                  right - left, editor.topInset()));
        }
        top.validate();
        var bounds = utilityRow.localToStageCoordinates(new Vector2());
        occupied.add(new com.badlogic.gdx.math.Rectangle(bounds.x, bounds.y, utilityRow.getWidth(), utilityRow.getHeight()));
        losPanel.update(new GpuHud.Inputs(frame, view, dialog, preferences, metrics, occupied));
    }

    /** Shows the running print's step and share done, and Cancel for it; {@code null} hides the card. */
    void print(GpuBoardPrint print) {
        shownPrint = print;
        printSlot.setVisible(print != null);
        if (print == null) { return; }
        printProgress.caption(print.progress().step()).set(print.progress().done());
        // Centred in the board area the editor's panels leave free, above its corner panels, as Fit board frames it.
        float left = framingLeft() / scale;
        printSlot.pad(0, left, framingBottom() / scale + metrics.gap(), metrics.width() - left - framingWidth() / scale);
    }

    boolean measuring() { return losPanel.pending(); }

    /** Centres a hex in the board area between the editor's panels, as Fit board fits the board into it. */
    private void frameHex(Coords coords) {
        if (scene != null && scene.tile(coords) != null) {
            // Above the editor's corner panels and below its toolbar, between its side columns.
            camera.viewableArea(framingLeft(), framingWidth(), framingBottom(), framingTop());
            camera.frameLocation(BoardGeometry.center(coords, scene.tile(coords).elevation()), framingWidth());
        }
    }

    @Override public int cliffEdgeAt(int x, int y) {
        if (editor == null) { return -1; }
        Vector2 point = stage.screenToStageCoordinates(new Vector2(x, y));
        Actor hit = stage.hit(point.x, point.y, true);
        if (!(hit instanceof UiHexSides sides) || !"editor-edges-cliff_top".equals(sides.getName())) { return -1; }
        sides.stageToLocalCoordinates(point);
        return sides.sideAt(point.x, point.y);
    }

    /** The map's menus as the battle HUD's Menu panel lists a game's; choosing an item closes the panel. */
    private void showMenu(List<BoardScene.Command> commands) {
        boolean open = state.dialog == Dialog.MENU && !commands.isEmpty();
        tree.open(open);
        menuLayer.open(open);
        if (!open) {
            return;
        }
        List<Object> rows = new ArrayList<>();
        // The editor adds its own entries, which open its native dialogs (New, Help).
        tree.rows(editor == null ? commands : editor.menu(commands), "", 0, rows);
        tree.show(rows, null, command -> {
            state.dialog = Dialog.NONE;
            if (ABOUT.equals(command.id())) {
                showAbout();
            } else {
                command.action().run();
            }
        });
        float width = Math.min(UiKit.DIALOG_WIDTH, metrics.width() - 2 * metrics.gap());
        float height = Math.min(menuSlot.getActor().getPrefHeight(), metrics.height() - UiKit.DIALOG_MARGIN);
        menuSlot.setBounds((metrics.width() - width) / 2, (metrics.height() - height) / 2, width, height);
        menuLayer.setBounds(0, 0, metrics.width(), metrics.height());
        menuLayer.toFront();
        menuLayer.focus();
    }

    /**
     * Menu → About: the native About card over the map. The source's own About command, the Swing box, is what a
     * window without this HUD runs.
     */
    void showAbout() {
        if (about != null && about.isOpen()) {
            return;
        }
        contextMenu.cancel();
        if (editor != null) { editor.closeSettings(); editor.closeHelp(); }
        UiDialog dialog = new UiDialog(kit.ui, GpuAbout.title(), () -> about.close()).width("dialog.md");
        GpuAbout.fill(kit.ui, dialog, dialog::close);
        about = dialog;
        dialog.show(stage, !GUIPreferences.getInstance().getReduceMotion());
    }

    /** The open About card, or null. */
    @Nullable UiDialog about() {
        return about != null && about.isOpen() ? about : null;
    }

    /**
     * The hex card: the hex with its level and theme as a panel's header, a rule,
     * then each row's name with its detail on the right; laid out again only when its text changes.
     */
    private void showCard(String card) {
        if (hexCard.update(card)) { layoutBottom(); }
    }

    /** Keep the inspected hex in the bottom-right corner, clear of the board centre. */
    private void layoutBottom() {
        bottom.clearChildren();
        if (hexCard.actor().hasChildren()) {
            bottom.add(hexCard.actor()).width(Math.min(CARD_WIDTH, metrics.width() - 2 * metrics.gap())).row();
        }
    }

    @Override
    public void draw() {
        showFilePrompt();
        kit.update(Set.of());
        stage.getViewport().apply();
        stage.act(Math.min(Gdx.graphics.getDeltaTime(), .1f));
        stage.draw();
    }

    @Override
    public boolean hit(int x, int y) {
        Vector2 point = stage.screenToStageCoordinates(new Vector2(x, y));
        return stage.hit(point.x, point.y, true) != null;
    }

    @Override
    public boolean dragsCamera(int x, int y) {
        return false;
    }

    @Override
    public boolean isTextEditing() {
        return stage.getKeyboardFocus() instanceof TextField;
    }

    @Override
    public boolean isModal() {
        return UiModal.active(stage) != null || source.editorFilePrompt() != null || state.dialog == Dialog.MENU;
    }

    private void showFilePrompt() {
        GpuEditorFiles.Prompt next = source.editorFilePrompt();
        if (next == filePrompt) { return; }
        filePrompt = next;
        if (fileQuestion != null) { fileQuestion.open(false); fileQuestion.remove(); fileQuestion = null; }
        if (next == null) { return; }
        state.dialog = Dialog.NONE;
        contextMenu.cancel();
        if (next.file() != null) {
            files.show(stage, next.file(), path -> source.answerEditorFile(next, path, -1));
        } else {
            fileQuestion = UiMessageDialog.show(kit.ui, stage, next.title(), next.message(), next.answers(),
                  answer -> source.answerEditorFile(next, null, answer));
        }
    }

    /**
     * A modal, a pending file question, the open menu, a focused text field and the hex menu take the keys first (Esc
     * closes the modal, the menu or the hex menu, or leaves the field); then Esc takes {@link #escape}'s next step.
     */
    @Override
    public boolean keyDown(int key, int awt, int modifiers) {
        UiModal active = UiModal.active(stage);
        if (active != null) { active.focus(); stage.keyDown(key); return true; }
        if (source.editorFilePrompt() != null) { return true; }
        if (state.dialog == Dialog.MENU) {
            if (key == Input.Keys.ESCAPE) { state.dialog = Dialog.NONE; }
            else { stage.keyDown(key); }
            return true;
        }
        if (isTextEditing()) { return stage.keyDown(key); }
        if (contextMenu.isVisible()) {
            if (key == Input.Keys.ESCAPE) { contextMenu.cancel(); return true; }
            return stage.keyDown(key);
        }
        // A tree that holds the keyboard focus (Layers, after a click on a row) takes its navigation keys.
        if (stage.getKeyboardFocus() instanceof megamek.client.ui.gdx.UiTree && stage.keyDown(key)) { return true; }
        if (key == Input.Keys.ESCAPE) { escape(); return true; }
        // Bare tool keys win inside the editor only; B waits for a picker click without ending a stroke.
        if (editor != null && modifiers == 0 && (key == Input.Keys.B || editor.toolKey(key))) { return true; }
        return state.dialog != Dialog.NONE && stage.keyDown(key);
    }

    /**
     * Esc, one step per press: the editor's open dialog or list closes, then the tuning panel, then a pending ruler
     * is dropped, the editor's selection clears and the Assets panel goes back a page; with nothing left, the Menu
     * opens, as a game's Esc does.
     */
    private void escape() {
        if (editor != null && editor.escape()) { return; }
        if (state.dialog != Dialog.NONE) { state.dialog = Dialog.NONE; return; }
        if (losPanel.cancel()) { return; }
        if (editor != null && (editor.clearSelection() || editor.escapeAssets())) { return; }
        state.dialog = Dialog.MENU;
    }

    @Override
    public boolean keyUp(int key, int awt) {
        return (isModal() || isTextEditing() || contextMenu.isVisible() || state.dialog != Dialog.NONE) && stage.keyUp(key);
    }

    @Override
    public boolean keyTyped(char character) {
        return stage.keyTyped(character) || isTextEditing();
    }

    @Override
    public void focusLost() {
        stage.cancelTouchFocus();
        losPanel.dismiss();
        contextMenu.cancel();
        stage.setKeyboardFocus(null);
    }

    @Override
    public void boardPress() {
        stage.setKeyboardFocus(null);
        contextMenu.cancel();
        if (editor != null) { editor.boardPress(); }
    }

    /** Map actions are anchored to the clicked hex; preview and editor share view controls and the native ruler. */
    void boardMenu(Coords coords, float pointedZ, int x, int y) {
        if (scene == null || coords == null || scene.tile(coords) == null) { return; }
        source.inspect(coords);
        UiMenuList list = new UiMenuList(kit.ui);
        if (editor != null) { editor.contextTools(list, contextMenu::cancel); }
        long generation = boardGeneration;
        Integer height = Float.isNaN(pointedZ) ? null : GpuLosResult.pointedHeight(scene.tile(coords).elevation(), pointedZ);
        list.item(Messages.getString("GpuBoard.map.lineOfSight"), Messages.getString("GpuBoard.hud.mouse.altClick"), null, false, true, () -> {
            contextMenu.cancel();
            source.changeRuler(generation, model -> { model.clear(); model.addPoint(coords, height); });
        }).setName("map-line-of-sight");
        if (editor != null) {
            editor.contextActions(list, contextMenu::cancel, () -> { state.dialog = Dialog.NONE; editor.openSettings(); });
        }
        contextMenu.header(Messages.getString("GpuBoard.hud.context.hex", coords.getBoardNum()), null).content(list);
        Vector2 at = stage.screenToStageCoordinates(new Vector2(x, y));
        contextMenu.showAt(at.x, at.y);
    }

    @Override
    public float framingLeft() { return editor == null ? 0 : (GpuBoardEditor.LIBRARY_WIDTH + 2 * GAP) * scale; }

    @Override
    public float framingWidth() {
        return Math.max(1, metrics.width() * scale - framingLeft()
              - (editor == null ? 0 : (GpuBoardEditor.INSPECTOR_WIDTH + 2 * GAP) * scale));
    }

    @Override public float framingBottom() { return editor == null ? 0 : editor.bottomInset() * scale; }
    @Override public float framingTop() { return editor == null ? 0 : editor.topInset() * scale; }

    /** The editor objects hovered in the side view or Layers, which the board outlines. */
    java.util.List<String> editorHoverObjects() { return editor == null ? java.util.List.of() : editor.hoveredObjects(); }
    /** The object under the board pointer in the editor's inspected hex, or "". */
    void editorBoardHover(String object) { if (editor != null) { editor.boardHover(object); } }

    @Override
    public void dispose() {
        if (editor != null) { editor.dispose(); }
        tuning.dispose();
        losPanel.dispose();
        contextMenu.cancel(); contextMenu.remove();
        files.close();
        stage.dispose();
        kit.dispose();
    }
}
