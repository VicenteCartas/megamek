/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The stepper's even gaps and the filter chip's centred text, clicked through the stage. */
@Tag("on-demand")
class UiStepperChipSmokeTest {
    private static final float EPSILON = 0.5f;

    @Test
    void aCaptionedStepperHasOneGapThroughoutAndSteps() {
        UiTestStage.run(ui -> {
            UiStepper stepper = ui.kit.stepper("G", 4, 0, 8);
            place(ui, stepper);
            Label caption = stepper.caption();
            assertNotNull(caption);
            float gap = UiStepper.gap();
            assertEquals(gap, stepper.down().getX() - (caption.getX() + caption.getWidth()), EPSILON,
                  "caption to −");
            assertEquals(gap, stepper.shown().getX() - (stepper.down().getX() + stepper.down().getWidth()), EPSILON,
                  "− to the value");
            assertEquals(gap, stepper.up().getX() - (stepper.shown().getX() + stepper.shown().getWidth()), EPSILON,
                  "the value to +");

            AtomicInteger changes = count(stepper);
            click(ui, stepper.up());
            assertEquals(5, stepper.value());
            click(ui, stepper.down());
            click(ui, stepper.down());
            assertEquals(3, stepper.value());
            assertEquals(3, changes.get(), "one change per step");
        });
    }

    @Test
    void theValueCellFitsTheWidestValueSoTheButtonsStay() {
        UiTestStage.run(ui -> {
            UiStepper stepper = ui.kit.stepper(1, 1, 12);
            place(ui, stepper);
            float upAt = stepper.up().getX();
            stepper.value(12);
            stepper.invalidateHierarchy();
            stepper.validate();
            assertEquals(upAt, stepper.up().getX(), EPSILON);
            assertTrue(stepper.shown().getPrefWidth() <= stepper.shown().getWidth() + EPSILON);
        });
    }

    @Test
    void aClickOnTheValueTypesItAndEnterSetsItWithinTheBounds() {
        UiTestStage.run(ui -> {
            UiStepper stepper = ui.kit.stepper("W", 16, 5, 200);
            place(ui, stepper);
            float upAt = stepper.up().getX();
            float downAt = stepper.down().getX();
            float width = stepper.getPrefWidth();
            AtomicInteger changes = count(stepper);
            click(ui, stepper.shown());
            assertTrue(stepper.typing());
            assertEquals(stepper.field(), ui.stage.getKeyboardFocus(), "the field takes the keys");
            ui.draw();
            assertEquals(width, stepper.getPrefWidth(), EPSILON, "typing keeps the stepper's width");
            assertEquals(downAt, stepper.down().getX(), EPSILON, "the − stays put while typing");
            assertEquals(upAt, stepper.up().getX(), EPSILON, "the + stays put while typing");
            assertEquals(stepper.down().getX() + stepper.down().getWidth(), stepper.field().getX(), EPSILON,
                  "the field fills the gap after the −");
            ui.capture("stepper-typing").dispose();
            type(ui, "150");
            assertEquals(16, stepper.value(), "keys alone do not set the value");
            assertEquals(0, changes.get());
            key(ui, Input.Keys.ENTER);
            assertFalse(stepper.typing());
            assertEquals(150, stepper.value());
            assertEquals(1, changes.get(), "one change for the typed value");
            assertEquals(upAt, stepper.up().getX(), EPSILON, "the buttons are back where they were");

            click(ui, stepper.shown());
            type(ui, "999");
            key(ui, Input.Keys.ENTER);
            assertEquals(200, stepper.value(), "brought within the bounds");
            assertEquals(2, changes.get());
        });
    }

    @Test
    void aTypedValueAsWideAsTheWidestShowsWhole() {
        UiTestStage.run(ui -> {
            // The requisition's quantity: its widest value, 12, is typed
            UiStepper stepper = ui.kit.stepper("Qty", 1, 1, 12);
            place(ui, stepper);
            float upAt = stepper.up().getX();
            click(ui, stepper.shown());
            type(ui, "12");
            assertEquals(0, visible(stepper.field(), "visibleTextStart"), "the field shows the first digit");
            assertEquals(2, visible(stepper.field(), "visibleTextEnd"), "and the last");
            assertEquals(upAt, stepper.up().getX(), EPSILON, "a value that fits the cell moves nothing");
        });
    }

    /** One of a text field's protected bounds of the text it draws. */
    private static int visible(TextField field, String bound) {
        try {
            java.lang.reflect.Field shown = TextField.class.getDeclaredField(bound);
            shown.setAccessible(true);
            return shown.getInt(field);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void escapeKeepsTheValueAndLeavingTheFieldSetsIt() {
        UiTestStage.run(ui -> {
            UiStepper stepper = ui.kit.stepper(-1, -3, 5);
            place(ui, stepper);
            AtomicInteger changes = count(stepper);
            click(ui, stepper.shown());
            type(ui, "4");
            key(ui, Input.Keys.ESCAPE);
            assertFalse(stepper.typing());
            assertEquals(-1, stepper.value());
            assertEquals(0, changes.get(), "Escape drops the typed value");

            click(ui, stepper.shown());
            type(ui, "-2");
            ui.stage.setKeyboardFocus(null);
            ui.draw();
            assertFalse(stepper.typing());
            assertEquals(-2, stepper.value(), "a minus leads a value when the bounds go below zero");
            assertEquals(1, changes.get());
        });
    }

    @Test
    void aSelectorTakesNoTyping() {
        UiTestStage.run(ui -> {
            UiStepper selector = ui.kit.selector(List.of("Low", "High"), 0);
            place(ui, selector);
            click(ui, selector.shown());
            assertFalse(selector.typing());
            assertNull(selector.field());
        });
    }

    @Test
    void aChipsTextIsCentredCheckedOrNot() {
        UiTestStage.run(ui -> {
            UiFilterChip light = ui.kit.filterChip("Light");
            UiFilterChip assault = ui.kit.filterChip("Assault");
            UiFilterChip.sameWidth(List.of(light, assault));
            assertEquals(light.getPrefWidth(), assault.getPrefWidth(), EPSILON, "one width for the group");
            Table row = new Table();
            row.add(light);
            row.add(assault);
            place(ui, row);
            for (UiFilterChip chip : List.of(light, assault)) {
                assertCentred(chip);
            }
            assertFalse(light.checkMark().isVisible());
            click(ui, light);
            assertTrue(light.isChecked());
            assertTrue(light.checkMark().isVisible(), "the check shows when on");
            assertCentred(light);
            Actor check = light.checkMark();
            Label text = light.label();
            float textLeft = text.getX() + (text.getWidth() - text.getPrefWidth()) / 2;
            assertTrue(check.getX() + check.getWidth() <= textLeft + EPSILON, "the check stays clear of the text");
            float widest = assault.label().getPrefWidth();
            assertEquals(widest + assault.getPadLeft() + assault.getPadRight(), assault.getWidth(), 1,
                  "the widest text and both reserves set the group's width");
            assertEquals(assault.getPadLeft(), assault.getPadRight(), EPSILON, "equal reserves");
        });
    }

    private static void assertCentred(UiFilterChip chip) {
        Label text = chip.label();
        float centre = text.getX() + text.getWidth() / 2;
        assertEquals(chip.getWidth() / 2, centre, EPSILON, chip.text() + " is centred on its chip");
        assertTrue(text.getWidth() + EPSILON >= text.getPrefWidth(), chip.text() + " fits");
    }

    private static void place(UiTestStage ui, Table table) {
        ui.stage.addActor(table);
        table.setBounds(100, 200, table.getPrefWidth(), table.getPrefHeight());
        table.validate();
        ui.draw();
    }

    private static AtomicInteger count(Actor actor) {
        AtomicInteger changes = new AtomicInteger();
        actor.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor target) {
                changes.incrementAndGet();
            }
        });
        return changes;
    }

    private static void type(UiTestStage ui, String text) {
        for (char character : text.toCharArray()) {
            ui.stage.keyTyped(character);
        }
        ui.draw();
    }

    private static void key(UiTestStage ui, int keycode) {
        ui.stage.keyDown(keycode);
        ui.stage.keyUp(keycode);
        ui.draw();
    }

    private static void click(UiTestStage ui, Actor actor) {
        Vector2 point = actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        point = ui.stage.stageToScreenCoordinates(point);
        ui.stage.touchDown(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
        ui.stage.touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
        ui.draw();
    }
}
