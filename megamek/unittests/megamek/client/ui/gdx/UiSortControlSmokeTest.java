/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Align;
import megamek.client.ui.gdx.TableSort.Direction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("on-demand")
class UiSortControlSmokeTest {

    private enum Key implements TableSort.Key {
        NAME(Direction.ASCENDING), VALUE(Direction.DESCENDING), YEAR(Direction.DESCENDING);

        private final Direction start;

        Key(Direction start) {
            this.start = start;
        }

        @Override
        public Direction defaultDirection() {
            return start;
        }
    }

    private static UiSortControl<Key> control(UiTestStage ui, List<Key> keys) {
        UiSortControl<Key> control = ui.kit.sortControl("Sort", "Ascending", "Descending", keys,
              key -> key.name().toLowerCase(Locale.ROOT), TableSort.of(Key.NAME));
        place(ui, control);
        return control;
    }

    @Test
    void choosingAKeyStartsItInItsDirectionAndFiresOnce() {
        UiTestStage.run(ui -> {
            UiSortControl<Key> control = control(ui, List.of(Key.NAME, Key.VALUE, Key.YEAR));
            List<TableSort<Key>> heard = new ArrayList<>();
            control.onSort(heard::add);
            AtomicInteger changes = count(control);
            assertEquals("Ascending", control.directionTip());

            click(ui, control.select());
            assertTrue(control.select().isOpen());
            click(ui, control.select().popup().label(1));

            assertEquals(new TableSort<>(Key.VALUE, Direction.DESCENDING), control.sort());
            assertEquals(List.of(control.sort()), heard);
            assertEquals(1, changes.get(), "the select's own change stays inside the control");
            assertEquals("Descending", control.directionTip());
        });
    }

    @Test
    void theDirectionButtonFlipsTheOrderAndItsTooltip() {
        UiTestStage.run(ui -> {
            UiSortControl<Key> control = control(ui, List.of(Key.NAME, Key.VALUE));
            List<TableSort<Key>> heard = new ArrayList<>();
            control.onSort(heard::add);

            click(ui, control.directionButton());
            assertEquals(new TableSort<>(Key.NAME, Direction.DESCENDING), control.sort());
            assertEquals("Descending", control.directionTip());
            click(ui, control.directionButton());
            assertEquals(TableSort.of(Key.NAME), control.sort());
            assertEquals("Ascending", control.directionTip());
            assertEquals(2, heard.size());
        });
    }

    @Test
    void aViewsOwnChangeFiresNothing() {
        UiTestStage.run(ui -> {
            UiSortControl<Key> control = control(ui, List.of(Key.NAME, Key.VALUE));
            AtomicInteger changes = count(control);

            control.sort(new TableSort<>(Key.VALUE, Direction.ASCENDING));
            assertEquals(1, control.select().getSelectedIndex());
            assertEquals("Ascending", control.directionTip());
            control.keys(List.of(Key.NAME, Key.YEAR), new TableSort<>(Key.YEAR, Direction.DESCENDING));
            assertEquals(List.of(Key.NAME, Key.YEAR), control.keys());
            assertEquals("year", control.select().getSelected());
            assertEquals(0, changes.get());
            assertThrows(IllegalArgumentException.class, () -> control.sort(TableSort.of(Key.VALUE)),
                  "a key it does not offer");
        });
    }

    @Test
    void aHeaderKeepsItsWidthWithOrWithoutItsArrowAndReportsClicks() {
        UiTestStage.run(ui -> {
            UiSortHeader header = ui.kit.sortHeader("Armor", Align.left);
            UiSortHeader value = ui.kit.sortHeader("BV", Align.right);
            place(ui, header);
            place(ui, value);
            AtomicInteger changes = count(header);
            float width = header.getPrefWidth();
            float valueWidth = value.getPrefWidth();
            assertNull(header.direction());

            header.direction(Direction.DESCENDING);
            value.direction(Direction.ASCENDING);
            header.invalidate();
            value.invalidate();
            assertEquals(width, header.getPrefWidth(), .01f, "the arrow's room is kept while it is hidden");
            assertEquals(valueWidth, value.getPrefWidth(), .01f);
            assertEquals(Direction.DESCENDING, header.direction());
            assertEquals(0, changes.get(), "showing the order fires nothing");
            assertTrue(header.getPrefWidth() > ui.kit.roleTypeLabel("overline", "Armor", "text.muted").getPrefWidth(),
                  "the header has room for the arrow beside the name");

            click(ui, header);
            assertEquals(1, changes.get());
            header.direction(null);
            assertNull(header.direction());
            assertFalse(header.text().isEmpty());
        });
    }

    private static void place(UiTestStage ui, Table actor) {
        ui.stage.addActor(actor);
        actor.setBounds(100, 300 - ui.stage.getRoot().getChildren().size * 40f, actor.getPrefWidth(),
              actor.getPrefHeight());
        actor.validate();
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

    private static void click(UiTestStage ui, Actor actor) {
        Vector2 point = actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        point = ui.stage.stageToScreenCoordinates(point);
        ui.stage.touchDown(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
        ui.stage.touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
        ui.draw();
    }
}
