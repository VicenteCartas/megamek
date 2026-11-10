/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.Disposable;

/**
 * What the board view asks of the HUD drawn over the board: the battle HUD ({@link GpuHud}) over a game, the map tools
 * ({@link GpuMapHud}) over the board editor and the map preview. Window coordinates are the board view's input
 * coordinates, y down; everything runs on the GL thread.
 */
interface GpuBoardHud extends Disposable {
    /** Shared outer margin and panel clearance for the editor, preview and game HUD, in stage units. */
    float GAP = 10;

    Stage stage();

    /** The kit its widgets and dialogs (UiMessageDialog) are made with. */
    megamek.client.ui.gdx.UiKit uiKit();

    /** The window's logical size and the display scale. */
    void resize(int width, int height, float displayScale);

    /** One frame's snapshots, before the board view draws the board. */
    void update(BoardSource.Frame frame, GpuHud.HudView view, GpuBoardWindow.DialogRequest dialog,
          BoardSource.UiPreferences preferences);

    void draw();

    /** True where the HUD takes a press at these window coordinates, so the board does not get it. */
    boolean hit(int x, int y);

    /** Cliff direction under the inspector pointer, clockwise from north, or -1. Render-thread UI state only. */
    default int cliffEdgeAt(int x, int y) { return -1; }

    /** True where a drag moves the camera (the minimap), for the pointer shape. */
    boolean dragsCamera(int x, int y);

    /** True while a text field has the keys; the camera keys pause then. */
    boolean isTextEditing();

    /** True while a modal owns input, including camera keys already held when it opened. */
    default boolean isModal() { return false; }

    /** Whether the player pans, orbits or turns the camera this frame, reported before each update. */
    default void cameraGesture(boolean moving) { }

    /** A key press before the board's camera keys; true when the HUD used it. */
    boolean keyDown(int key, int awt, int modifiers);

    boolean keyUp(int key, int awt);

    boolean keyTyped(char character);

    /** The window lost the keyboard: held keys end. */
    void focusLost();

    /** A press on the board, which closes what such a press closes. */
    void boardPress();

    /** Panel clearance in window pixels, consumed only by explicit Fit board and replay framing. */
    float framingLeft();
    float framingWidth();
    default float framingBottom() { return 0; }
    default float framingTop() { return 0; }

}
