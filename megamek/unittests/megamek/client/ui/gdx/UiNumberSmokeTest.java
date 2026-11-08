/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("on-demand")
class UiNumberSmokeTest {
    @Test void clickedSliderKeepsFocusForArrowEditsAndCommitsOnReleaseOrBlur() {
        UiTestStage.run(ui -> {
            record Change(String value, boolean finished) { }
            var events = new ArrayList<Change>();
            UiNumber number = new UiNumber(ui.kit, ui.stage, "Height", 2, -10, 30, .1,
                  (value, finished) -> events.add(new Change(value, finished)));
            ui.stage.addActor(number); number.setBounds(100, 350, 320, 36); ui.draw();
            try {
                Vector2 caption = point(ui.stage, number.findActor("editor-scrub-Height"));
                ui.stage.touchDown((int) caption.x, (int) caption.y, 0, Input.Buttons.LEFT);
                ui.stage.touchUp((int) caption.x, (int) caption.y, 0, Input.Buttons.LEFT);
                ui.draw();
                Slider slider = ui.stage.getRoot().findActor("editor-slider-Height");
                Vector2 track = point(ui.stage, slider);
                ui.stage.touchDown((int) track.x, (int) track.y, 0, Input.Buttons.LEFT);
                ui.stage.touchUp((int) track.x, (int) track.y, 0, Input.Buttons.LEFT);
                assertTrue(number.editing(), "The popup stays open for keyboard adjustments after a click");
                assertSame(slider, ui.stage.getKeyboardFocus());
                events.clear();
                float value = slider.getValue();
                assertTrue(ui.stage.keyDown(Input.Keys.RIGHT));
                assertEquals(value + .1f, slider.getValue(), .0001f);
                assertEquals(new Change(UiNumber.format(slider.getValue()), false), events.getLast());
                assertTrue(ui.stage.keyUp(Input.Keys.RIGHT));
                assertEquals(new Change(UiNumber.format(slider.getValue()), true), events.getLast());
                assertEquals(1, events.stream().filter(Change::finished).count());
                assertTrue(number.editing());

                assertTrue(ui.stage.keyDown(Input.Keys.LEFT));
                ui.stage.touchDown(20, 20, 0, Input.Buttons.LEFT);
                ui.stage.touchUp(20, 20, 0, Input.Buttons.LEFT);
                assertNull(ui.stage.getKeyboardFocus());
                assertFalse(number.editing());
                assertEquals(new Change(UiNumber.format(slider.getValue()), true), events.getLast());
                assertEquals(2, events.stream().filter(Change::finished).count());
                ui.stage.keyUp(Input.Keys.LEFT);
                assertFalse(ui.stage.keyDown(Input.Keys.RIGHT));
                assertEquals(value, slider.getValue(), .0001f);
                assertEquals(2, events.stream().filter(Change::finished).count(), "Blur commits only once");
            } finally { number.close(); }
        });
    }

    @Test void captionCaptureAllowsOffscreenDragsAndReleasesOnEveryExit() {
        UiTestStage.run(ui -> {
            record Change(String value, boolean finished) { }
            var events = new ArrayList<Change>();
            UiNumber number = new UiNumber(ui.kit, ui.stage, "Distance", 0, -10000, 10000, 1,
                  (value, finished) -> events.add(new Change(value, finished)));
            ui.stage.addActor(number); number.setBounds(100, 350, 320, 36); ui.draw();
            Vector2 at = point(ui.stage, number.findActor("editor-scrub-Distance"));
            try {
                ui.stage.touchDown((int) at.x, (int) at.y, 0, Input.Buttons.LEFT);
                assertTrue(UiCursorCapture.active());
                ui.stage.touchDragged((int) at.x + 4000, (int) at.y, 0);
                assertEquals(new Change("1000", false), events.getLast());
                ui.stage.touchDragged((int) at.x - 4000, (int) at.y, 0);
                assertEquals(new Change("-1000", false), events.getLast());
                ui.stage.touchUp((int) at.x - 4000, (int) at.y, 0, Input.Buttons.LEFT);
                assertFalse(UiCursorCapture.active());
                assertEquals(new Change("-1000", true), events.getLast());
                assertEquals(1, events.stream().filter(Change::finished).count());

                for (Runnable end : new Runnable[] {ui.stage::cancelTouchFocus, number::dismiss, number::remove,
                      number::close}) {
                    ui.stage.touchDown((int) at.x, (int) at.y, 0, Input.Buttons.LEFT);
                    assertTrue(UiCursorCapture.active());
                    end.run();
                    assertFalse(UiCursorCapture.active(), "Ending the gesture must restore the cursor");
                    assertFalse(number.editing());
                    ui.stage.addActor(number);
                }
            } finally { number.close(); }
        });
    }

    @Test void straightDragsTurnAnglesAcrossTheSeamAndCommitOnceOnRelease() {
        UiTestStage.run(ui -> {
            record Change(String value, boolean finished) { }
            var events = new ArrayList<Change>();
            UiNumber number = new UiNumber(ui.kit, ui.stage, "Z", 170, -180, 180, 1,
                  (value, finished) -> events.add(new Change(value, finished))).angle();
            ui.stage.addActor(number); number.setBounds(100, 160, 220, 32); ui.draw();
            try {
                Vector2 caption = point(ui.stage, number.findActor("editor-scrub-Z"));
                int x = (int) caption.x, y = (int) caption.y;
                // From the caption, half a degree per unit: right and up add, left and down subtract, across the seam.
                ui.stage.touchDown(x, y, 0, Input.Buttons.LEFT);
                assertTrue(UiCursorCapture.active());
                ui.stage.touchDragged(x + 30, y, 0);
                assertEquals(new Change("-175", false), events.getLast(), "Right adds");
                ui.stage.touchDragged(x + 30, y - 20, 0);
                assertEquals(new Change("-165", false), events.getLast(), "Up adds");
                ui.stage.touchDragged(x - 30, y + 40, 0);
                assertEquals(new Change("135", false), events.getLast(), "Left and down subtract");
                ui.stage.touchUp(x - 30, y + 40, 0, Input.Buttons.LEFT);
                assertEquals(new Change("135", true), events.getLast());
                assertEquals(1, events.stream().filter(Change::finished).count());
                assertFalse(UiCursorCapture.active());
                assertFalse(number.editing());

                // A caption click opens the dial, which a straight drag turns the same way.
                ui.stage.touchDown(x, y, 0, Input.Buttons.LEFT);
                ui.stage.touchUp(x, y, 0, Input.Buttons.LEFT);
                ui.draw(); ui.capture("editor-angle-dial").dispose();
                Actor dial = ui.stage.getRoot().findActor("editor-dial-Z");
                assertNotNull(dial);
                // Zero points up and values turn clockwise, so 135 degrees lies at -45 from the right.
                Vector2 grip = dialPoint(ui.stage, dial, -45);
                ui.stage.touchDown((int) grip.x, (int) grip.y, 0, Input.Buttons.LEFT);
                assertTrue(UiCursorCapture.active());
                ui.stage.touchDragged((int) grip.x, (int) grip.y - 50, 0);
                assertEquals(new Change("160", false), events.getLast(), "Up on the dial adds");
                ui.stage.touchUp((int) grip.x, (int) grip.y - 50, 0, Input.Buttons.LEFT);
                assertEquals(new Change("160", true), events.getLast());
                assertFalse(UiCursorCapture.active());
                assertFalse(number.editing());

                // A click on the dial points it there, as a compass reads: the right is 90, the left -90, the top 0.
                for (int[] aim : new int[][] {{0, 90}, {180, -90}, {90, 0}}) {
                    ui.stage.touchDown(x, y, 0, Input.Buttons.LEFT);
                    ui.stage.touchUp(x, y, 0, Input.Buttons.LEFT);
                    ui.draw();
                    Vector2 at = dialPoint(ui.stage, dial, aim[0]);
                    ui.stage.touchDown((int) at.x, (int) at.y, 0, Input.Buttons.LEFT);
                    ui.stage.touchUp((int) at.x, (int) at.y, 0, Input.Buttons.LEFT);
                    assertEquals(new Change(String.valueOf(aim[1]), true), events.getLast());
                    assertFalse(UiCursorCapture.active());
                }

                TextField field = number.findActor("editor-Z");
                ui.stage.setKeyboardFocus(field); field.setText("725"); ui.stage.setKeyboardFocus(null);
                assertEquals("5", field.getText());
            } finally { number.close(); }
        });
    }
    private static Vector2 dialPoint(Stage stage, Actor dial, double angle) {
        double radians = Math.toRadians(angle);
        return stage.stageToScreenCoordinates(dial.localToStageCoordinates(new Vector2(
              dial.getWidth() / 2 + 46 * (float) Math.cos(radians), dial.getHeight() / 2 + 46 * (float) Math.sin(radians))));
    }
    @Test void captionClickDragAndBlurHaveDistinctCommitBoundaries() {
        UiTestStage.run(ui -> {
            record Change(String value, boolean finished) { }
            var events = new ArrayList<Change>();
            var enabled = new java.util.concurrent.atomic.AtomicBoolean();
            UiNumber number = new UiNumber(ui.kit, ui.stage, "Height", 2, -10, 30, .1,
                  (value, finished) -> events.add(new Change(value, finished))).withCheckbox(false, enabled::set);
            ui.stage.addActor(number); number.setBounds(100, 350, 320, 36); ui.draw();
            try {
                var checkbox = point(ui.stage, number.findActor("number-enabled"));
                ui.stage.touchDown((int) checkbox.x, (int) checkbox.y, 0, Input.Buttons.LEFT);
                ui.stage.touchUp((int) checkbox.x, (int) checkbox.y, 0, Input.Buttons.LEFT);
                assertTrue(enabled.get());
                assertTrue(events.isEmpty(), "The application checkbox must not change the numeric value");
                assertFalse(number.editing(), "The checkbox must not open the slider");
                Actor caption = number.findActor("editor-scrub-Height");
                Vector2 at = point(ui.stage, caption);
                ui.stage.touchDown((int) at.x, (int) at.y, 0, Input.Buttons.LEFT);
                ui.stage.touchUp((int) at.x, (int) at.y, 0, Input.Buttons.LEFT);
                assertFalse(UiCursorCapture.active(), "A caption click must release the cursor too");
                assertTrue(number.editing());
                assertTrue(ui.stage.getRoot().findActor("editor-number-popup").isVisible());
                assertTrue(events.isEmpty(), "Opening the slider does not edit the value");
                ui.draw(); ui.capture("editor-numeric-slider").dispose();
                Actor popup = ui.stage.getRoot().findActor("editor-number-popup");
                Label readout = ui.stage.getRoot().findActor("editor-slider-value-Height");
                assertEquals("2", readout.getText().toString());
                assertTrue(UiTestStage.bounds(popup).contains(UiTestStage.bounds(readout)), "The value is inside the open slider popup");
                assertTrue(readout.getWidth() >= readout.getPrefWidth(), "The number must not be clipped");
                assertTrue(popup.getHeight() > 60 && popup.getY() > 300, "Popover must be visibly placed above the caption");
                // Outside click closes the slider; the next caption gesture scrubs immediately.
                ui.stage.touchDown(20, 20, 0, Input.Buttons.LEFT); ui.stage.touchUp(20, 20, 0, Input.Buttons.LEFT);
                ui.stage.touchDown((int) at.x, (int) at.y, 0, Input.Buttons.LEFT);
                ui.stage.touchDragged((int) at.x + 40, (int) at.y, 0);
                assertEquals(new Change("3", false), events.getLast());
                assertEquals("3", readout.getText().toString(), "Caption scrubbing updates the popup before release");
                ui.draw(); ui.capture("editor-numeric-caption-drag").dispose();
                ui.stage.touchUp((int) at.x + 40, (int) at.y, 0, Input.Buttons.LEFT);
                assertEquals(new Change("3", true), events.getLast());
                assertEquals(1, events.stream().filter(Change::finished).count());
                assertFalse(number.editing(), "Releasing a caption drag closes its slider");
                TextField field = number.findActor("editor-Height");
                ui.stage.setKeyboardFocus(field); field.setText("4.5"); ui.stage.setKeyboardFocus(null);
                assertEquals(new Change("4.5", true), events.getLast());
                assertEquals("4.5", readout.getText().toString());
                ui.stage.setKeyboardFocus(field); field.setText("99"); ui.stage.keyDown(Input.Keys.ESCAPE);
                assertEquals("4.5", field.getText());
                assertEquals(2, events.stream().filter(Change::finished).count());
                ui.stage.touchDown((int) at.x, (int) at.y, 0, Input.Buttons.LEFT);
                ui.stage.touchDragged((int) at.x + 20, (int) at.y, 0);
                ui.stage.cancelTouchFocus();
                assertFalse(UiCursorCapture.active());
                assertEquals(new Change("5", true), events.getLast(), "Focus loss commits the last preview, not cancelled pointer coordinates");
                assertFalse(number.editing());
                ui.stage.setKeyboardFocus(field); field.setText("NaN"); ui.stage.setKeyboardFocus(null);
                assertEquals("5", field.getText());
                int beforeSync = events.size();
                number.value(-2.5);
                assertEquals(beforeSync, events.size(), "Snapshot refresh is silent");
                ui.stage.touchDown((int) at.x, (int) at.y, 0, Input.Buttons.LEFT);
                ui.stage.touchUp((int) at.x, (int) at.y, 0, Input.Buttons.LEFT);
                assertEquals("-2.5", readout.getText().toString(), "Reopening shows refreshed negative and fractional values");
                ui.draw(); ui.capture("editor-numeric-negative-value").dispose();
                Slider slider = ui.stage.getRoot().findActor("editor-slider-Height");
                Vector2 track = point(ui.stage, slider);
                long committed = events.stream().filter(Change::finished).count();
                ui.stage.touchDown((int) track.x, (int) track.y, 0, Input.Buttons.LEFT);
                ui.stage.touchDragged((int) track.x + 35, (int) track.y, 0);
                assertTrue(popup.isVisible());
                assertTrue(Double.parseDouble(readout.getText().toString()) > 10, "Dragging the track changes the displayed value");
                assertEquals(field.getText(), readout.getText().toString());
                assertEquals(new Change(readout.getText().toString(), false), events.getLast());
                assertEquals(committed, events.stream().filter(Change::finished).count(), "A track drag only previews until release");
                ui.draw(); ui.capture("editor-numeric-slider-drag").dispose();
                ui.stage.touchUp((int) track.x + 35, (int) track.y, 0, Input.Buttons.LEFT);
                assertEquals(new Change(readout.getText().toString(), true), events.getLast());
                assertTrue(popup.isVisible(), "The track stays available for arrow-key adjustments");
                assertSame(slider, ui.stage.getKeyboardFocus());
            } finally { number.close(); }
        });
    }
    @Test void scrubbingStopsAtTheMinimumWhileTypingDoesNot() {
        UiTestStage.run(ui -> {
            record Change(String value, boolean finished) { }
            var events = new ArrayList<Change>();
            UiNumber number = new UiNumber(ui.kit, ui.stage, "Offset", 2, 0, 30, .1,
                  (value, finished) -> events.add(new Change(value, finished)));
            ui.stage.addActor(number); number.setBounds(100, 350, 320, 36); ui.draw();
            Vector2 at = point(ui.stage, number.findActor("editor-scrub-Offset"));
            int x = (int) at.x, y = (int) at.y;
            TextField field = number.findActor("editor-Offset");
            Slider slider = ui.stage.getRoot().findActor("editor-slider-Offset");
            try {
                // A tenth per four units: 400 to the left would reach -8.
                ui.stage.touchDown(x, y, 0, Input.Buttons.LEFT); ui.stage.touchDragged(x - 400, y, 0);
                ui.stage.touchUp(x - 400, y, 0, Input.Buttons.LEFT);
                assertEquals(new Change("0", true), events.getLast(), "Scrubbing stops at the minimum");
                ui.stage.setKeyboardFocus(field); field.setText("-3"); ui.stage.setKeyboardFocus(null);
                assertEquals(new Change("-3", true), events.getLast(), "Typing is not limited");

                int before = events.size();
                ui.stage.touchDown(x, y, 0, Input.Buttons.LEFT); ui.stage.touchDragged(x - 400, y, 0);
                assertEquals(before, events.size(), "Scrubbing never takes a typed value further below the minimum");
                assertEquals(-3, slider.getMinValue(), 1e-4, "The slider reaches down to the typed value only");
                ui.stage.touchDragged(x + 40, y, 0);
                ui.stage.touchUp(x + 40, y, 0, Input.Buttons.LEFT);
                assertEquals(new Change("-2", true), events.getLast(), "Scrubbing upward works");

                ui.stage.setKeyboardFocus(field); field.setText("1"); ui.stage.setKeyboardFocus(null);
                ui.stage.touchDown(x, y, 0, Input.Buttons.LEFT); ui.stage.touchDragged(x - 400, y, 0);
                assertEquals(0, slider.getMinValue(), 1e-4, "An earlier typed value does not widen a later gesture");
                ui.stage.touchUp(x - 400, y, 0, Input.Buttons.LEFT);
                assertEquals(new Change("0", true), events.getLast());
            } finally { number.close(); }
        });
    }
    @Test void captionDoubleClickResetsOnceWithoutScrubbing() {
        UiTestStage.run(ui -> {
            record Change(String value, boolean finished) { }
            var events = new ArrayList<Change>();
            UiNumber offset = new UiNumber(ui.kit, ui.stage, "Offset", 7.5, 0, 30, .1,
                  (value, finished) -> events.add(new Change(value, finished)));
            UiNumber scale = new UiNumber(ui.kit, ui.stage, "Scale", 2, .05, 8, .05,
                  (value, finished) -> events.add(new Change(value, finished)));
            ui.stage.addActor(offset); offset.setBounds(100, 350, 320, 36);
            ui.stage.addActor(scale); scale.setBounds(100, 250, 320, 36); ui.draw();
            try {
                Vector2 at = point(ui.stage, offset.findActor("editor-scrub-Offset"));
                for (int click = 0; click < 2; click++) {
                    ui.stage.touchDown((int) at.x, (int) at.y, 0, Input.Buttons.LEFT);
                    ui.stage.touchUp((int) at.x, (int) at.y, 0, Input.Buttons.LEFT);
                }
                assertEquals(java.util.List.of(new Change("0", true)), events,
                      "One committed reset, as a typed value commits, and no scrub steps");
                assertEquals("0", ((TextField) offset.findActor("editor-Offset")).getText());
                assertFalse(offset.editing(), "The reset closes the slider");
                ui.stage.touchDown((int) at.x, (int) at.y, 0, Input.Buttons.LEFT);
                ui.stage.touchUp((int) at.x, (int) at.y, 0, Input.Buttons.LEFT);
                assertEquals(1, events.size(), "A third click opens the slider instead of resetting again");
                assertTrue(offset.editing());
                offset.dismiss();

                // Zero lies outside this range: the reset takes the value nearest to it.
                Vector2 scaleAt = point(ui.stage, scale.findActor("editor-scrub-Scale"));
                for (int click = 0; click < 2; click++) {
                    ui.stage.touchDown((int) scaleAt.x, (int) scaleAt.y, 0, Input.Buttons.LEFT);
                    ui.stage.touchUp((int) scaleAt.x, (int) scaleAt.y, 0, Input.Buttons.LEFT);
                }
                assertEquals(new Change("0.05", true), events.getLast());
                assertEquals(2, events.size());
            } finally { offset.close(); scale.close(); }
        });
    }
    private static Vector2 point(Stage stage, Actor actor) {
        return stage.stageToScreenCoordinates(actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
    }
}
