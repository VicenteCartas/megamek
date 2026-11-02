/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static megamek.client.ui.gdx.UiTestStage.bounds;
import static megamek.client.ui.gdx.UiTestStage.place;
import static megamek.client.ui.gdx.UiTestStage.settle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * UiList's drag-to-reorder through the stage's own input, as a window delivers it: a grip pressed and dragged lifts its
 * row, which follows the pointer while a slot opens and the other rows slide; the view hears one move once the rows
 * rest and the list keeps that order until the view answers. The held row stays inside its list; Esc and a drop in
 * place move nothing; a keyboard move flies the same way; in a scroll pane the list scrolls under the held row, which
 * no clip cuts.
 */
@Tag("on-demand")
class UiListSmokeTest {
    /** Each row's height. */
    private static final float ROW = 40;

    @Test
    void aDraggedRowFollowsThePointerTheOthersMakeRoomAndTheDropReportsOneMove() {
        UiTestStage.run(ui -> {
            Rows rows = new Rows(ui.kit, 5, -1);
            place(ui.window, rows.panel(ui.kit), 100, 100);
            ui.draw();
            List<Rectangle> rest = rows.bounds();
            Stage stage = ui.stage;

            // The first row's grip, dragged 90 units down in steps: its centre passes the second and third rows'
            // middles (60 and 100 units below the list's top would be 20 and 60 down), not the fourth's (140).
            Vector2 grip = centre(rows.grips.get(0));
            press(stage, grip);
            drag(stage, grip, new Vector2(grip.x, grip.y - 90), 9);
            Rectangle lifted = bounds(rows.rows.get(0));
            assertEquals(rest.get(0).x, lifted.x, .01f, "the row keeps the list's x");
            assertEquals(rest.get(0).y - 90, lifted.y, 2, "and follows the pointer at its grab");
            stage.act(1 / 30f);
            float sliding = bounds(rows.rows.get(1)).y;
            assertTrue(sliding > rest.get(1).y + 1 && sliding < rest.get(1).y + ROW - 1,
                  "the second row slides up, under way: " + sliding + " from " + rest.get(1).y);
            frames(stage, 1);
            assertEquals(List.of(rest.get(0).y, rest.get(1).y, rest.get(3).y, rest.get(4).y), List.of(
                  bounds(rows.rows.get(1)).y, bounds(rows.rows.get(2)).y, bounds(rows.rows.get(3)).y,
                  bounds(rows.rows.get(4)).y), "the second and third rows rose a row; the slot opened below them");
            ui.draw();
            ui.capture("ui-list-drag").dispose();

            release(stage, new Vector2(grip.x, grip.y - 90));
            assertTrue(rows.moves.isEmpty(), "nothing is reported while the row glides into the slot");
            settle(stage);
            assertEquals(List.of("0>2"), rows.moves, "one move, once the rows rest");
            assertEquals(0, rows.clicks.get(), "a drag is never a click");
            assertEquals(rest.get(2).y, bounds(rows.rows.get(0)).y, .01f, "the row rests in the slot");
            frames(stage, .5f);
            assertEquals(rest.get(2).y, bounds(rows.rows.get(0)).y, .01f, "the list keeps the order meanwhile");
            assertFalse(rows.list.busy(), "a list waiting for its view's answer is at rest");

            // The view answers with its rows in the new order: they show at rest, without a move back.
            rows.reorder(1, 2, 0, 3, 4);
            ui.draw();
            assertEquals(List.of(rest.get(0).y, rest.get(1).y, rest.get(2).y), List.of(bounds(rows.rows.get(1)).y,
                  bounds(rows.rows.get(2)).y, bounds(rows.rows.get(0)).y));
            assertFalse(rows.list.busy());
        });
    }

    /**
     * The held row stays inside its list: dragged far above the list it stops at
     * the top, far below at the bottom, and beside it keeps the list's x; a drop at the bottom takes the last place.
     */
    @Test
    void aDraggedRowStaysInsideItsList() {
        UiTestStage.run(ui -> {
            Rows rows = new Rows(ui.kit, 5, -1);
            place(ui.window, rows.panel(ui.kit), 100, 100);
            ui.draw();
            List<Rectangle> rest = rows.bounds();
            Stage stage = ui.stage;
            Vector2 grip = centre(rows.grips.get(1));
            press(stage, grip);
            Vector2 above = new Vector2(grip.x + 400, grip.y + 300);
            drag(stage, grip, above, 6);
            Rectangle lifted = bounds(rows.rows.get(1));
            assertEquals(rest.get(0).x, lifted.x, .01f, "beside the list it keeps the list's x");
            assertEquals(rest.get(0).y, lifted.y, .01f, "above the list it stops at the top");
            Vector2 below = new Vector2(grip.x - 400, grip.y - 500);
            drag(stage, above, below, 8);
            assertEquals(rest.get(4).y, bounds(rows.rows.get(1)).y, .01f, "below the list it stops at the bottom");
            release(stage, below);
            settle(stage);
            assertEquals(List.of("1>4"), rows.moves, "the drop there takes the last place");
        });
    }

    @Test
    void escAndADropInPlaceGlideHomeAndReportNothing() {
        UiTestStage.run(ui -> {
            Rows rows = new Rows(ui.kit, 5, -1);
            place(ui.window, rows.panel(ui.kit), 100, 100);
            ui.draw();
            List<Rectangle> rest = rows.bounds();
            Stage stage = ui.stage;

            // Esc (the view's cancel): the row glides home; the rest of the gesture does nothing.
            Vector2 grip = centre(rows.grips.get(1));
            press(stage, grip);
            drag(stage, grip, new Vector2(grip.x, grip.y - 70), 7);
            frames(stage, .5f);
            assertEquals(rest.get(1).y, bounds(rows.rows.get(2)).y, .01f, "the third row made room");
            assertTrue(rows.list.cancel());
            assertFalse(rows.list.cancel(), "nothing is left to cancel");
            drag(stage, new Vector2(grip.x, grip.y - 70), new Vector2(grip.x, grip.y - 150), 4);
            release(stage, new Vector2(grip.x, grip.y - 150));
            settle(stage);
            assertEquals(rest, rows.bounds(), "every row back in its place");

            // A drop in place, and a press on a row's text, which is no handle, move nothing either.
            grip = centre(rows.grips.get(3));
            press(stage, grip);
            drag(stage, grip, new Vector2(grip.x, grip.y - 12), 3);
            release(stage, new Vector2(grip.x, grip.y - 12));
            settle(stage);
            Vector2 text = centre(rows.rows.get(2));
            press(stage, text);
            drag(stage, text, new Vector2(text.x, text.y - 90), 6);
            assertEquals(rest.get(2), bounds(rows.rows.get(2)), "the text drags nothing");
            release(stage, new Vector2(text.x, text.y - 90));
            settle(stage);
            assertEquals(rest, rows.bounds());
            assertTrue(rows.moves.isEmpty(), "no move reported: " + rows.moves);
            assertEquals(0, rows.clicks.get(), "and no drag was a click");

            // A press on a grip without a move is the row's click.
            press(stage, centre(rows.grips.get(4)));
            release(stage, centre(rows.grips.get(4)));
            assertEquals(1, rows.clicks.get());
            assertTrue(rows.moves.isEmpty());
        });
    }

    @Test
    void aKeyboardMoveFliesLikeADropAndAMoveTheViewRefusesGoesBack() {
        UiTestStage.run(ui -> {
            // The last row has no handle: it stays put, though other rows pass it.
            Rows rows = new Rows(ui.kit, 5, 4);
            place(ui.window, rows.panel(ui.kit), 100, 100);
            ui.draw();
            List<Rectangle> rest = rows.bounds();
            Stage stage = ui.stage;
            UiList list = rows.list;

            assertFalse(list.move(4, -1), "a row without a handle stays put");
            assertFalse(list.move(0, -1), "the first row cannot go higher");
            assertTrue(list.move(1, 1), "Alt+Down on the second row");
            stage.act(1 / 30f);
            float flying = bounds(rows.rows.get(1)).y;
            assertTrue(flying < rest.get(1).y - 1 && flying > rest.get(2).y + 1, "under way: " + flying);
            assertTrue(list.move(1, 1), "pressed again before it lands, the same row flies on");
            assertFalse(list.move(0, 1), "another row waits for it");
            settle(stage);
            assertEquals(List.of("1>3"), rows.moves, "one move for both presses");
            assertEquals(List.of(rest.get(0).y, rest.get(1).y, rest.get(2).y, rest.get(3).y, rest.get(4).y),
                  List.of(bounds(rows.rows.get(0)).y, bounds(rows.rows.get(2)).y, bounds(rows.rows.get(3)).y,
                        bounds(rows.rows.get(1)).y, bounds(rows.rows.get(4)).y));
            assertFalse(list.move(0, 1), "a move waits for its view's answer");

            // No answer comes (the view's model refused the move): the rows glide back to the view's order.
            frames(stage, 1.5f);
            assertEquals(rest, rows.bounds());
            assertTrue(list.move(3, -3), "and move again");
            settle(stage);
            assertEquals(List.of("1>3", "3>0"), rows.moves);
        });
    }

    @Test
    void inAScrollPaneTheHeldRowScrollsTheListAndNoClipCutsIt() {
        UiTestStage.run(ui -> {
            Rows rows = new Rows(ui.kit, 20, -1);
            ScrollPane scroll = ui.kit.scrollList(rows.list);
            Table frame = new Table();
            frame.add(scroll).size(300, 200);
            place(ui.window, frame, 100, 100);
            ui.draw();
            Stage stage = ui.stage;
            Rectangle view = bounds(scroll);

            // The second row held 6 units above the pane's bottom: the list scrolls under it, the slot goes along.
            Vector2 grip = centre(rows.grips.get(1));
            Vector2 low = new Vector2(grip.x, view.y + 6);
            press(stage, grip);
            drag(stage, grip, low, 8);
            frames(stage, .05f);
            float scrolled = scroll.getScrollY();
            assertTrue(scrolled > 0, "the list scrolls");
            frames(stage, .5f);
            assertTrue(scroll.getScrollY() > scrolled + 40, "on and on while the row stays there");

            // Held below the pane, the row stays in the pane's view of the list,
            // whole at its bottom edge, where no clip cuts it.
            drag(stage, low, new Vector2(grip.x, view.y - 12), 2);
            ui.draw();
            Pixmap image = ui.capture("ui-list-scroll");
            try {
                Rectangle row = bounds(rows.rows.get(1));
                assertEquals(view.y, row.y, .5f, "the row stops at the pane's bottom: " + row + " " + view);
                Color face = new Color(image.getPixel(Math.round(row.x + row.width / 2), Math.round(row.y + 6)));
                assertTrue(face.g < .3f, "the row's face, not the background: " + face);
            } finally {
                image.dispose();
            }
            release(stage, new Vector2(grip.x, view.y + 30));
            settle(stage);
            assertEquals(1, rows.moves.size());
            assertTrue(rows.moves.getFirst().startsWith("1>") && Integer.parseInt(rows.moves.getFirst()
                  .substring(2)) > 5, "dropped further down than the pane showed at the press: " + rows.moves);
        });
    }

    /** A list of rows 40 units high, each a grip and a label; a click on a row counts, each move is noted. */
    private static final class Rows {
        final UiList list;
        final List<Table> rows = new ArrayList<>();
        final List<Actor> grips = new ArrayList<>();
        final List<String> moves = new ArrayList<>();
        final AtomicInteger clicks = new AtomicInteger();

        /** {@code count} rows; the row at {@code fixed} (-1: none) has no handle. */
        Rows(UiKit kit, int count, int fixed) {
            list = new UiList(kit).reorderable((from, to) -> moves.add(from + ">" + to));
            for (int index = 0; index < count; index++) {
                Table row = new Table();
                row.setTouchable(Touchable.enabled);
                row.pad(0, 12, 0, 12);
                UiKit.Icon grip = kit.icon("grip", 12, UiTheme.MUTED);
                row.add(grip).size(12, 16);
                row.add(kit.label("Row " + (index + 1), "hud-body", 13, UiTheme.TEXT)).growX().left().height(ROW)
                      .padLeft(10);
                row.addListener(new ClickListener() {
                    @Override
                    public void clicked(InputEvent event, float x, float y) {
                        clicks.incrementAndGet();
                    }
                });
                rows.add(row);
                grips.add(grip);
                list.add(row, index == fixed ? null : grip);
            }
        }

        /** The list in a 300-unit panel under a header. */
        Table panel(UiKit kit) {
            Table panel = kit.panel();
            panel.add(kit.header("Reorderable list", null)).growX().row();
            panel.add(list).growX();
            panel.setWidth(300);
            return panel;
        }

        /** Gives the list its rows again in another order, as a view answers a move. */
        void reorder(int... order) {
            List<Table> before = List.copyOf(rows);
            List<Actor> handles = List.copyOf(grips);
            list.clearChildren();
            for (int index : order) {
                list.add(before.get(index), handles.get(index));
            }
        }

        List<Rectangle> bounds() {
            return rows.stream().map(UiTestStage::bounds).toList();
        }
    }

    private static Vector2 centre(Actor actor) {
        Rectangle area = bounds(actor);
        return new Vector2(area.x + area.width / 2, area.y + area.height / 2);
    }

    /** Acts the stage for {@code seconds} in 60 Hz frames, as a window does. */
    private static void frames(Stage stage, float seconds) {
        for (float time = 0; time < seconds; time += 1 / 60f) {
            stage.act(1 / 60f);
        }
    }

    private static void press(Stage stage, Vector2 point) {
        Vector2 screen = stage.stageToScreenCoordinates(point.cpy());
        stage.touchDown(Math.round(screen.x), Math.round(screen.y), 0, Input.Buttons.LEFT);
    }

    /** Moves the held pointer from one stage point to another in {@code steps} steps. */
    private static void drag(Stage stage, Vector2 from, Vector2 to, int steps) {
        for (int step = 1; step <= steps; step++) {
            Vector2 screen = stage.stageToScreenCoordinates(from.cpy().lerp(to, step / (float) steps));
            stage.touchDragged(Math.round(screen.x), Math.round(screen.y), 0);
        }
    }

    private static void release(Stage stage, Vector2 point) {
        Vector2 screen = stage.stageToScreenCoordinates(point.cpy());
        stage.touchUp(Math.round(screen.x), Math.round(screen.y), 0, Input.Buttons.LEFT);
    }
}
