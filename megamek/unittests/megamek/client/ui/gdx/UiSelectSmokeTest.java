/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import megamek.client.ui.design.DesignTokens;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("on-demand")
class UiSelectSmokeTest {
    private static final DesignTokens TOKENS = DesignTokens.shipped();
    private static final String[] ITEMS = { "Standard", "Total Warfare", "Experimental", "Unofficial" };

    @Test
    void facesAreControlHeightAndFitTheWidestItem() {
        UiTestStage.run(ui -> {
            assertEquals(TOKENS.size().get("control.md"), ui.kit.tokenSelect(false, ITEMS).getPrefHeight(), .01f);
            assertEquals(TOKENS.size().get("control.sm"), ui.kit.tokenSelect(true, ITEMS).getPrefHeight(), .01f);
            assertEquals(TOKENS.size().get("control.sm"), ui.kit.tokenSelectInline(ITEMS).getPrefHeight(), .01f);
            UiSelect select = ui.kit.tokenSelect(false, ITEMS);
            float width = select.getPrefWidth();
            select.select(1);
            select.invalidate();
            assertEquals(width, select.getPrefWidth(), .01f, "the face keeps its width whatever is current");
            assertTrue(select.getMinWidth() < width / 2,
                  "held narrower, the face ellipsizes its value rather than overflow its padding");
            select.placeholder("Choose a ruleset that is longer than every item");
            assertTrue(select.getPrefWidth() > width, "the face makes room for its placeholder");
            assertEquals(-1, select.getSelectedIndex());
            assertNull(select.getSelected());
        });
    }

    @Test
    void aClickOpensTheListBelowAndAChoiceFiresOneChange() {
        UiTestStage.run(ui -> {
            UiSelect select = place(ui, ui.kit.tokenSelect(false, ITEMS));
            AtomicInteger changes = count(select);
            click(ui, select);
            assertTrue(select.isOpen());
            UiListPopup list = select.popup();
            assertSame(ui.stage.getRoot(), list.getParent(), "the list sits on the stage's root");
            float gap = TOKENS.space().get("xs");
            assertEquals(select.getY() - gap, list.getY() + list.getHeight(), .01f, "the list opens below the face");
            assertTrue(list.getWidth() >= select.getWidth(), "the list is at least as wide as the face");
            assertEquals(0, list.current());
            assertEquals(0, list.active());
            assertSame(list, ui.stage.getKeyboardFocus());

            click(ui, list.label(2));
            assertFalse(select.isOpen());
            assertEquals(2, select.getSelectedIndex());
            assertEquals("Experimental", select.getSelected());
            assertEquals(1, changes.get());

            click(ui, select);
            click(ui, list.label(2));
            assertFalse(select.isOpen());
            assertEquals(1, changes.get(), "choosing the current item fires nothing");
            select.select(0);
            assertEquals(1, changes.get(), "a view's own change fires nothing");
            assertThrows(IndexOutOfBoundsException.class, () -> select.select(4));
        });
    }

    @Test
    void movingQuicklyFromTheFaceThroughTheListDoesNotChooseOrClose() {
        UiTestStage.run(ui -> {
            UiSelect select = place(ui, ui.kit.tokenSelect(false, ITEMS));
            AtomicInteger changes = count(select);
            click(ui, select);
            UiListPopup list = select.popup();
            Vector2 start = select.localToStageCoordinates(new Vector2(select.getWidth() / 2, select.getHeight() / 2));
            Vector2 end = list.label(ITEMS.length - 1).localToStageCoordinates(new Vector2(5, 5));
            ui.stage.stageToScreenCoordinates(start);
            ui.stage.stageToScreenCoordinates(end);
            for (int step : new int[] { 1, 30, 300 }) {
                ui.stage.mouseMoved(Math.round(start.x), Math.round(start.y));
                ui.draw();
                for (int y = Math.round(start.y); y < Math.round(end.y); y += step) {
                    ui.stage.mouseMoved(Math.round(end.x), y);
                    ui.draw();
                    assertTrue(select.isOpen(), "pointer motion across the face, gap and rows must keep the list open");
                }
                ui.stage.mouseMoved(Math.round(end.x), Math.round(end.y));
                ui.draw();
                assertTrue(select.isOpen());
                assertEquals(ITEMS.length - 1, list.active());
                assertEquals(0, select.getSelectedIndex());
                assertEquals(0, changes.get());
            }
        });
    }

    @Test
    void theKeyboardOpensMovesChoosesAndReturnsFocus() {
        UiTestStage.run(ui -> {
            UiSelect select = place(ui, ui.kit.tokenSelect(true, ITEMS));
            AtomicInteger changes = count(select);
            ui.stage.setKeyboardFocus(select);
            ui.stage.keyDown(Input.Keys.DOWN);
            assertTrue(select.isOpen());
            UiListPopup list = select.popup();
            ui.stage.keyDown(Input.Keys.DOWN);
            ui.stage.keyDown(Input.Keys.DOWN);
            assertEquals(2, list.active());
            ui.stage.keyDown(Input.Keys.END);
            assertEquals(3, list.active());
            ui.stage.keyDown(Input.Keys.DOWN);
            assertEquals(3, list.active(), "the last row stays active");
            ui.stage.keyDown(Input.Keys.HOME);
            ui.stage.keyDown(Input.Keys.UP);
            assertEquals(0, list.active(), "the first row stays active");
            ui.stage.keyDown(Input.Keys.DOWN);
            ui.stage.keyDown(Input.Keys.ENTER);
            assertFalse(select.isOpen());
            assertEquals(1, select.getSelectedIndex());
            assertEquals(1, changes.get());
            assertSame(select, ui.stage.getKeyboardFocus(), "the focus comes back to the face");

            ui.stage.keyDown(Input.Keys.SPACE);
            assertTrue(select.isOpen());
            assertEquals(1, list.active(), "the list opens on the current item");
            ui.stage.keyDown(Input.Keys.DOWN);
            ui.stage.keyDown(Input.Keys.ESCAPE);
            assertFalse(select.isOpen());
            assertEquals(1, select.getSelectedIndex(), "Escape closes without a choice");
            assertEquals(1, changes.get());
            assertSame(select, ui.stage.getKeyboardFocus());
        });
    }

    @Test
    void aPressOutsideOrOnTheFaceClosesTheList() {
        UiTestStage.run(ui -> {
            UiSelect select = place(ui, ui.kit.tokenSelect(false, ITEMS));
            click(ui, select);
            assertTrue(select.isOpen());
            ui.stage.touchDown(5, 5, 0, Input.Buttons.LEFT);
            ui.stage.touchUp(5, 5, 0, Input.Buttons.LEFT);
            ui.draw();
            assertFalse(select.isOpen(), "a press outside closes the list");
            click(ui, select);
            assertTrue(select.isOpen());
            click(ui, select);
            assertFalse(select.isOpen(), "a press on the face closes the list and does not reopen it");
            assertEquals(0, select.getSelectedIndex());
        });
    }

    @Test
    void theListOpensAboveWithoutRoomBelowAndScrollsLongLists() {
        UiTestStage.run(ui -> {
            String[] many = new String[UiListPopup.MAX_ROWS + 5];
            for (int i = 0; i < many.length; i++) {
                many[i] = "Item " + i;
            }
            UiSelect select = ui.kit.tokenSelect(false, many);
            ui.stage.addActor(select);
            select.setBounds(100, 20, select.getPrefWidth(), select.getPrefHeight());
            select.validate();
            ui.draw();
            click(ui, select);
            UiListPopup list = select.popup();
            float gap = TOKENS.space().get("xs");
            assertEquals(select.getY() + select.getHeight() + gap, list.getY(), .01f, "the list flips above");
            float row = TOKENS.size().get("control.sm");
            assertTrue(list.getHeight() < UiListPopup.MAX_ROWS * row + row, "the list caps its rows and scrolls");
            ui.stage.keyDown(Input.Keys.END);
            assertEquals(many.length - 1, list.active());
            ui.stage.keyDown(Input.Keys.ENTER);
            assertEquals(many.length - 1, select.getSelectedIndex());
        });
    }

    @Test
    void aDisabledSelectDoesNotOpen() {
        UiTestStage.run(ui -> {
            UiSelect select = place(ui, ui.kit.tokenSelectInline(ITEMS));
            AtomicInteger changes = count(select);
            click(ui, select);
            assertTrue(select.isOpen());
            select.setDisabled(true);
            assertFalse(select.isOpen(), "disabling closes the list");
            click(ui, select);
            ui.stage.setKeyboardFocus(select);
            ui.stage.keyDown(Input.Keys.ENTER);
            assertFalse(select.isOpen());
            assertEquals(0, changes.get());
        });
    }

    private static UiSelect place(UiTestStage ui, UiSelect select) {
        ui.stage.addActor(select);
        select.setBounds(100, 300, select.getPrefWidth(), select.getPrefHeight());
        select.validate();
        ui.draw();
        return select;
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

    private static void click(UiTestStage ui, Actor actor) {
        Vector2 point = actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        point = ui.stage.stageToScreenCoordinates(point);
        ui.stage.touchDown(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
        ui.stage.touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
        ui.draw();
    }
}
