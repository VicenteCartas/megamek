/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.event.InputEvent;

import com.badlogic.gdx.Input;
import megamek.client.ui.util.KeyCommandBind;

/** Camera gestures and held movement shared by the game board and embedded previews. */
final class BoardCameraControls {
    static final float PAN_PIXELS_PER_SECOND = 750;
    static final float TILT_DEGREES_PER_SECOND = 60;
    static final float SPEED_BOOST = 4;
    static final float DRAG_THRESHOLD = 6;
    static final float ORBIT_DEGREES = .3f;
    private static final float ROTATE_DEGREES_PER_SECOND = 90;

    private BoardCameraControls() { }

    static boolean cameraButton(int button) {
        return button == Input.Buttons.RIGHT || button == Input.Buttons.MIDDLE;
    }

    /** Shift at the press swaps right-pan and middle-orbit for the entire gesture. */
    static boolean orbits(int button, int modifiers) {
        return cameraButton(button)
              && (button == Input.Buttons.MIDDLE) != ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0);
    }

    /** Deltas are in viewport pixels, y down; scale is viewport pixels per layout unit. */
    static void drag(BoardCamera camera, boolean orbit, float dx, float dy, float scale) {
        if (orbit) {
            camera.orbit(dx * ORBIT_DEGREES / scale, dy * ORBIT_DEGREES / scale);
        } else {
            camera.pan(dx, dy);
        }
    }

    static boolean held(KeyCommandBind command) {
        return switch (command) {
            case SCROLL_NORTH, SCROLL_SOUTH, SCROLL_EAST, SCROLL_WEST,
                 CAMERA_ROTATE_LEFT, CAMERA_ROTATE_RIGHT, CAMERA_TILT_UP, CAMERA_TILT_DOWN -> true;
            default -> false;
        };
    }

    static float speed(int modifiers) {
        return (modifiers & InputEvent.SHIFT_DOWN_MASK) != 0 ? SPEED_BOOST : 1;
    }

    static void move(BoardCamera camera, Iterable<KeyCommandBind> commands, float elapsed) {
        float distance = PAN_PIXELS_PER_SECOND * elapsed;
        float inclination = TILT_DEGREES_PER_SECOND * elapsed;
        float rotation = ROTATE_DEGREES_PER_SECOND * elapsed;
        for (KeyCommandBind command : commands) {
            switch (command) {
                case SCROLL_NORTH -> camera.pan(0, distance);
                case SCROLL_SOUTH -> camera.pan(0, -distance);
                case SCROLL_EAST -> camera.pan(-distance, 0);
                case SCROLL_WEST -> camera.pan(distance, 0);
                case CAMERA_TILT_UP -> camera.tilt(-inclination);
                case CAMERA_TILT_DOWN -> camera.tilt(inclination);
                case CAMERA_ROTATE_LEFT -> camera.orbit(-rotation, 0);
                case CAMERA_ROTATE_RIGHT -> camera.orbit(rotation, 0);
                default -> { }
            }
        }
    }
}
