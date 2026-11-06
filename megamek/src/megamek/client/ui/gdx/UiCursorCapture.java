/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Stage;
import org.lwjgl.glfw.GLFW;

/**
 * Hides the pointer for a relative drag, such as a value scrub or a camera orbit, and wraps it inside the window so the
 * drag never stops at the screen's edge. The cursor keeps moving as the desktop moves it; at the window's border it
 * jumps to the opposite one, a margin inside so a fast move cannot carry it out into view. Release puts it back where
 * the drag began and only then shows it.
 * <p>
 * The drag's owner reads its pointer through {@link #trusted}. For a real press that reads the live cursor and makes any
 * jump in the same step, so a jump never reaches the drag, whenever its event arrives; once the button is up the pointer
 * stops, so nothing after the release moves the drag. A synthetic drag moves no real cursor and is read as it comes.
 */
public final class UiCursorCapture {
    /** The one pointer has one capture at a time, across all windows. */
    private static UiCursorCapture holder;
    /** A far reading that lasts this long is taken as real, so a lost jump cannot freeze the drag. */
    private static final long SETTLE_NANOS = 50_000_000;
    /** The wrapping border's distance inside the window, as a share of its size. */
    private static final double BORDER = .1;

    private final double[] cursorX = new double[1], cursorY = new double[1];
    private final int[] width = new int[1], height = new int[1];
    private final Vector2 point = new Vector2();
    /** The GLFW window that hid the cursor, 0 without one. */
    private long window;
    /** Whether a button was really down at the capture, so the live cursor is the drag's pointer. */
    private boolean live;
    private double startX, startY, lastCursorX, lastCursorY;
    private long settleUntil;
    private float unitsPerPixelX, unitsPerPixelY;

    /** Whether a drag holds the pointer now. */
    public static boolean active() {
        return holder != null;
    }

    /** {@link #capture(float, float, float, float)} for coordinates in the stage's units, y up. */
    public void capture(float x, float y, Stage stage) {
        float unitsPerPixel = stage.getWidth() / Gdx.graphics.getWidth();
        capture(x, y, unitsPerPixel, -unitsPerPixel);
    }

    /**
     * Starts a drag with the pointer at (x, y) and hides the cursor. Does not take over another gesture's capture; the
     * drag's pointer is then read as it comes.
     *
     * @param unitsPerPixelX the caller's x units per window pixel
     * @param unitsPerPixelY the caller's y units per window pixel, negative when its y points up
     */
    public void capture(float x, float y, float unitsPerPixelX, float unitsPerPixelY) {
        point.set(x, y);
        if (holder != null) { return; }
        holder = this;
        this.unitsPerPixelX = unitsPerPixelX;
        this.unitsPerPixelY = unitsPerPixelY;
        if (Gdx.graphics instanceof Lwjgl3Graphics graphics) {
            window = graphics.getWindow().getWindowHandle();
            GLFW.glfwGetCursorPos(window, cursorX, cursorY);
            startX = lastCursorX = cursorX[0];
            startY = lastCursorY = cursorY[0];
            settleUntil = System.nanoTime() + SETTLE_NANOS;
            live = Gdx.input.isTouched();
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
        }
    }

    /** The press that became this drag at (x, y) of its capture: release returns the cursor there, not to the capture. */
    public void beganAt(float x, float y) {
        if (holder != this || window == 0) { return; }
        startX += (x - point.x) / unitsPerPixelX;
        startY += (y - point.y) / unitsPerPixelY;
    }

    /** The pointer in the caller's coordinates, unbounded by the window; (x, y) is the event's own position. */
    public Vector2 trusted(float x, float y) {
        if (holder != this || !live) { return point.set(x, y); }
        // The release event may still be queued behind this one, but the drag is over.
        if (!Gdx.input.isTouched()) { return point; }
        GLFW.glfwGetCursorPos(window, cursorX, cursorY);
        GLFW.glfwGetWindowSize(window, width, height);
        double dx = cursorX[0] - lastCursorX, dy = cursorY[0] - lastCursorY;
        // Windows applies a jump a moment later and can read from before it even after showing it. No real move
        // crosses half the wrap between two reads, so such a reading is the jump's, and leaves the drag.
        long now = System.nanoTime();
        if ((Math.abs(dx) > width[0] * (.5 - BORDER) || Math.abs(dy) > height[0] * (.5 - BORDER)) && now < settleUntil) {
            return point;
        }
        settleUntil = now + SETTLE_NANOS;
        point.add((float) dx * unitsPerPixelX, (float) dy * unitsPerPixelY);
        lastCursorX = wrapped(cursorX[0], width[0]);
        lastCursorY = wrapped(cursorY[0], height[0]);
        if (lastCursorX != cursorX[0] || lastCursorY != cursorY[0]) { GLFW.glfwSetCursorPos(window, lastCursorX, lastCursorY); }
        return point;
    }

    /** Where the cursor goes from {@code at}: past one border it comes in at the other by as much. */
    static double wrapped(double at, int size) {
        double border = size * BORDER, span = size - 2 * border;
        if (at < border) { return at + span; }
        return at > size - border ? at - span : at;
    }

    /** Shows the cursor again where the capture began; every exit of the gesture may call it. */
    public void release() {
        if (holder != this) { return; }
        holder = null;
        if (window != 0) {
            GLFW.glfwSetCursorPos(window, startX, startY);
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
            window = 0;
            live = false;
        }
    }
}
