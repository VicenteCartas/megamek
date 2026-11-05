/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;

/**
 * Holds the pointer still for a relative drag, such as a value scrub or a camera orbit. The desktop backend supplies
 * unbounded virtual coordinates while keeping the cursor hidden and anchored, so the drag never stops at the screen's
 * edge, then restores its visible position on release.
 * <p>
 * On Windows, GLFW's first event poll after the capture can still report the pointer where it was before GLFW centred
 * it, and turns that into one bogus step of about the distance to the window's centre (glfw/glfw#1523, open). The
 * drag's owner therefore reads its pointer through {@link #trusted}, which drops the first frame delivering motion
 * after capture. Frames without input must not expire this guard.
 */
public final class UiCursorCapture {
    /** The window input that hid the cursor; each window has its own. */
    private Input captured;
    private long captureFrame, firstMotionFrame;
    private float lastX, lastY, offsetX, offsetY;
    private final Vector2 point = new Vector2();

    /**
     * Starts a drag with the pointer at (x, y) and hides and anchors the cursor. Does not take over another gesture's
     * capture; the drag's pointer is then read as it comes.
     */
    public void capture(float x, float y) {
        lastX = x;
        lastY = y;
        offsetX = 0;
        offsetY = 0;
        if (captured != null || Gdx.input.isCursorCatched()) { return; }
        captured = Gdx.input;
        captured.setCursorCatched(true);
        captureFrame = Gdx.graphics.getFrameId();
        firstMotionFrame = -1;
    }

    /**
     * The pointer at (x, y), in the caller's coordinates, without the first batch of motion GLFW reports after capture.
     * Events of the capture's own frame were polled before it and are kept. The first later frame with motion is
     * rebased even if several frames passed without input; subsequent frames retain their relative movement.
     */
    public Vector2 trusted(float x, float y) {
        if (captured != null) {
            long frame = Gdx.graphics.getFrameId();
            if (frame > captureFrame && firstMotionFrame < 0) { firstMotionFrame = frame; }
            if (frame == firstMotionFrame) {
                offsetX += x - lastX;
                offsetY += y - lastY;
            }
        }
        lastX = x;
        lastY = y;
        return point.set(x - offsetX, y - offsetY);
    }

    /** Shows the cursor again where the capture began; every exit of the gesture may call it. */
    public void release() {
        if (captured != null) {
            captured.setCursorCatched(false);
            captured = null;
        }
    }
}
