/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static megamek.client.ui.gdx.UiTestStage.place;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import megamek.client.ui.gdx.UiKit.Tone;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The toolkit's gallery, the proof that a view needs nothing but the toolkit: every hud-v3 button style and state,
 * badge and dot, segments and tabs, selects, chips, checkboxes, meters, the search field, the icon sheet, panels built
 * from the base components (header and close, list, footer, empty state), a reorderable list with a row held mid-drag,
 * a dialog, and shot 13's context menu as a popover with a menu list, on a bare theme without any board or battle
 * object, beside crops of the hud-v3 shots 08 and 13; and the contracts of buttons, checkboxes, the search field, the
 * dialog's frame, the menu list's keys and the popover's placement and closing.
 */
@Tag("on-demand")
class UiGallerySmokeTest {
    /**
     * Characters no shipped face has, which texts from MegaMek use (the midline ellipsis U+22EF, the white diamond with
     * a centred dot U+27D0), draw as their lookalike instead of a blank: the same ink box and advance.
     */
    @Test
    void charactersNoFaceHasDrawAsTheirLookalike() {
        UiTestStage.run(ui -> {
            BitmapFont.BitmapFontData font = ui.theme.skin.getFont("hud-small").getData();
            for (char[] pair : new char[][] { { '⋯', '…' }, { '⟐', '◈' } }) {
                BitmapFont.Glyph glyph = font.getGlyph(pair[0]);
                BitmapFont.Glyph lookalike = font.getGlyph(pair[1]);
                String name = Integer.toHexString(pair[0]);
                assertTrue(glyph != null && glyph != font.missingGlyph, name + " has a glyph of its own");
                assertEquals(List.of(lookalike.width, lookalike.height, lookalike.xadvance),
                      List.of(glyph.width, glyph.height, glyph.xadvance), name + " looks like its lookalike");
            }
        });
    }

    @Test
    void everyBaseComponentBesideTheMock() {
        UiTestStage.run(ui -> {
            UiKit kit = ui.kit;
            addEnemyIcon(ui.theme);
            Table log = place(ui.window, log(kit), 20, 20);
            UiPopover menu = menu(kit);
            ui.window.addActor(menu);
            Table utilities = place(ui.window, utilities(kit), 1348, 18);
            place(ui.window, styles(kit), 1348, 110);
            Table dock = place(ui.window, dock(kit), 20, 300);
            place(ui.window, states(kit), 740, 20);
            place(ui.window, meters(kit), 640, 560);
            place(ui.window, listPanel(kit), 20, 500);
            place(ui.window, emptyPanel(kit), 330, 500);
            place(ui.window, icons(kit), 20, 900);
            place(ui.window, tooltip(kit), 1348, 300);
            place(ui.window, dialog(kit), 1120, 400);
            Table reorderable = place(ui.window, reorderable(kit), 1120, 660);
            ui.draw();
            // The third row held by its grip a row and a half higher: the second slid down past the open slot. The
            // press comes before the popover opens, which a press outside would close.
            holdMidDrag(ui.stage, reorderable.findActor("gallery-grip-2"), 1.4f * 41);
            menu.showAt(440, ui.window.getHeight() - 20);
            ui.draw();
            Pixmap gallery = ui.capture("ui-gallery");
            try {
                UiTestStage.compare("ui-gallery-log", gallery, log, "08-weapon-playback.jpg", 1520, 318);
                UiTestStage.compare("ui-gallery-utilities", gallery, utilities, "08-weapon-playback.jpg", 1535, 18);
                UiTestStage.compare("ui-gallery-dock", gallery, dock, "08-weapon-playback.jpg", 670, 885);
                UiTestStage.compare("ui-gallery-menu", gallery, menu, "13-context-menu.jpg", 784, 193);
            } finally {
                gallery.dispose();
            }
        });
    }

    @Test
    void buttonsReportClicksWithoutTogglingAndSearchClears() {
        UiTestStage.run(ui -> {
            UiKit kit = ui.kit;
            UiButton walk = kit.button("hud", null, "Walk", "3 MP");
            AtomicInteger clicks = new AtomicInteger();
            walk.addListener(changed(clicks));
            UiKit.SearchField search = kit.search("Search units...");
            AtomicInteger edits = new AtomicInteger();
            search.field.addListener(changed(edits));
            Table root = new Table();
            root.add(walk).width(120).padRight(20);
            root.add(search).width(280);
            place(ui.window, root, 100, 100);
            ui.draw();

            click(ui.stage, walk);
            assertEquals(1, clicks.get(), "A click fires one ChangeEvent");
            assertFalse(walk.isChecked(), "A click never presses the button; its view does");
            walk.pressed(true);
            click(ui.stage, walk);
            assertEquals(2, clicks.get());
            assertTrue(walk.isChecked(), "A click never releases a pressed button either");
            walk.setChecked(false);
            assertFalse(walk.isChecked());
            assertEquals(2, clicks.get(), "A view's own change fires no event");

            assertFalse(search.clear.isVisible(), "No clear button while the field is empty");
            search.field.setText("atlas");
            ui.draw();
            assertTrue(search.clear.isVisible(), "The clear button shows while the field holds text");
            int before = edits.get();
            click(ui.stage, search.clear);
            assertEquals("", search.field.getText());
            assertEquals(before + 1, edits.get(), "Clearing reaches the field's listeners as one change");
            ui.draw();
            assertFalse(search.clear.isVisible());
        });
    }

    @Test
    void checkboxFlipsOnAClickWithOneChangeAndItsViewTicksItWithout() {
        UiTestStage.run(ui -> {
            UiKit.Checkbox box = ui.kit.checkbox("An option", false);
            AtomicInteger changes = new AtomicInteger();
            box.addListener(changed(changes));
            place(ui.window, box, 100, 100);
            ui.draw();

            click(ui.stage, box);
            assertTrue(box.isTicked(), "A click on the checkbox ticks it");
            assertEquals(1, changes.get(), "and fires one ChangeEvent");
            click(ui.stage, box.getChildren().first());
            assertFalse(box.isTicked(), "A click on the box itself clears it");
            assertEquals(2, changes.get());
            box.ticked(true);
            assertTrue(box.isTicked());
            assertEquals(2, changes.get(), "A view's own change fires no event");
            box.toggle();
            assertFalse(box.isTicked(), "toggle() flips it as a click does");
            assertEquals(3, changes.get());
        });
    }

    @Test
    void menuKeysSkipDisabledItemsWrapAndEnterChoosesTheHighlight() {
        UiTestStage.run(ui -> {
            List<String> chosen = new ArrayList<>();
            UiMenuList list = new UiMenuList(ui.kit);
            UiButton first = list.item("First", null, null, false, true, () -> chosen.add("First"));
            UiButton off = list.item("Off", "unavailable", null, false, false, () -> chosen.add("Off"));
            UiButton second = list.item("Second", null, null, false, true, () -> chosen.add("Second"));
            list.separator();
            UiButton last = list.item("Last", "L", true, false, true, () -> chosen.add("Last"));
            UiPopover popover = new UiPopover(ui.kit).header("Menu", null).content(list);
            ui.window.addActor(popover);
            popover.showAt(400, 700);
            ui.draw();
            Stage stage = ui.stage;
            assertSame(list, stage.getKeyboardFocus(), "an open popover gives its list the keyboard");
            assertFalse(stage.keyDown(Input.Keys.ENTER), "Enter without a highlight stays with the view");
            List<UiButton> items = List.of(first, off, second, last);
            assertTrue(stage.keyDown(Input.Keys.DOWN));
            assertEquals(List.of(first), pressed(items), "Down starts at the first item");
            stage.keyDown(Input.Keys.DOWN);
            assertEquals(List.of(second), pressed(items), "the disabled item is skipped");
            stage.keyDown(Input.Keys.END);
            assertEquals(List.of(last), pressed(items));
            stage.keyDown(Input.Keys.DOWN);
            assertEquals(List.of(first), pressed(items), "Down wraps to the first item");
            stage.keyDown(Input.Keys.UP);
            assertEquals(List.of(last), pressed(items), "Up wraps to the last item");
            stage.keyDown(Input.Keys.HOME);
            assertEquals(List.of(first), pressed(items));
            assertFalse(stage.keyDown(Input.Keys.A), "other keys stay with the view");
            assertTrue(stage.keyDown(Input.Keys.ENTER));
            assertEquals(List.of("First"), chosen, "Enter chooses the highlighted item once");
            click(stage, off);
            click(stage, second);
            assertEquals(List.of("First", "Second"), chosen, "a click chooses an enabled item only");
            assertTrue(popover.isVisible(), "presses inside the popover keep it open");

            UiMenuList fresh = new UiMenuList(ui.kit);
            UiButton only = fresh.item("Only", null, null, false, true, () -> { });
            fresh.item("Never", null, null, false, false, () -> { });
            popover.content(fresh);
            assertSame(fresh, stage.getKeyboardFocus(), "new content of an open popover takes the keyboard");
            stage.keyDown(Input.Keys.UP);
            assertTrue(only.isChecked(), "Up from no highlight starts at the last enabled item");
        });
    }

    @Test
    void aHighlightAboveOrBelowTheViewOfAScrollingListComesFullyIntoView() {
        UiTestStage.run(ui -> {
            UiMenuList list = new UiMenuList(ui.kit);
            List<UiButton> items = new ArrayList<>();
            for (int item = 0; item < 30; item++) {
                items.add(list.item("Item " + item, null, null, false, true, () -> { }));
            }
            ScrollPane scroll = ui.kit.scrollList(list);
            Table frame = new Table();
            frame.add(scroll).size(240, 200);
            place(ui.window, frame, 100, 100);
            ui.draw();
            assertTrue(list.keyDown(Input.Keys.END));
            showScrolled(ui, scroll);
            assertInView(scroll, items.getLast(), "End: the last item, below the view");
            assertTrue(list.keyDown(Input.Keys.HOME));
            showScrolled(ui, scroll);
            assertInView(scroll, items.getFirst(), "Home: the first item, above the view");
            assertTrue(list.keyDown(Input.Keys.UP));
            showScrolled(ui, scroll);
            assertInView(scroll, items.getLast(), "Up wraps to the last item, below the view");
            assertTrue(list.keyDown(Input.Keys.DOWN));
            showScrolled(ui, scroll);
            assertInView(scroll, items.getFirst(), "Down wraps to the first item, above the view");
        });
    }

    /**
     * A scrolling list holds the stage's wheel (its scroll focus) only while the pointer is over it, so that the wheel
     * reaches a view's own use of it elsewhere, such as a board's zoom: entering the list takes it, moving onto another
     * row or pressing one keeps it, and leaving the list gives it back.
     */
    @Test
    void aScrollingListHoldsTheWheelOnlyWhileThePointerIsOverIt() {
        UiTestStage.run(ui -> {
            Table list = new Table();
            List<UiButton> rows = new ArrayList<>();
            for (int item = 0; item < 30; item++) {
                rows.add(row(ui.kit, "Row " + item));
                list.add(rows.getLast()).growX().row();
            }
            ScrollPane scroll = ui.kit.scrollList(list);
            Table frame = new Table();
            frame.add(scroll).size(240, 200);
            place(ui.window, frame, 100, 100);
            ui.draw();
            Stage stage = ui.stage;
            Rectangle area = UiTestStage.bounds(scroll);
            Vector2 inside = new Vector2(area.x + area.width / 2, area.y + area.height - 10);
            Vector2 below = new Vector2(area.x + area.width / 2, area.y + 10);
            Vector2 outside = new Vector2(area.x + area.width + 200, area.y + area.height / 2);
            move(ui, inside);
            assertSame(scroll, stage.getScrollFocus(), "entering the list takes the wheel");
            assertTrue(stage.scrolled(0, 1) && scroll.getScrollY() > 0, "the wheel scrolls the list");
            move(ui, below);
            assertSame(scroll, stage.getScrollFocus(), "another row keeps it");
            move(ui, outside);
            assertNull(stage.getScrollFocus(), "leaving the list gives the wheel back");
            float scrolled = scroll.getScrollY();
            assertFalse(stage.scrolled(0, 1), "so no widget takes the wheel there");
            assertEquals(scrolled, scroll.getScrollY());

            move(ui, inside);
            click(stage, rows.get(3));
            ui.draw();
            assertSame(scroll, stage.getScrollFocus(), "a press on a row keeps it");
            move(ui, outside);
            assertNull(stage.getScrollFocus(), "and leaving gives it back");
        });
    }

    /**
     * A list inside another scrolling pane, such as a tree in a dialog's body, takes the wheel anywhere over its rows,
     * not the pane round it; a list with nothing to scroll leaves the wheel to the pane round it.
     */
    @Test
    void aListInsideAScrollingPaneTakesTheWheelOverItsRows() {
        UiTestStage.run(ui -> {
            Table list = new Table();
            for (int item = 0; item < 30; item++) {
                list.add(row(ui.kit, "Row " + item)).growX().row();
            }
            ScrollPane inner = ui.kit.scrollList(list, true);
            Table shortList = new Table();
            shortList.add(row(ui.kit, "Only row")).growX();
            ScrollPane still = ui.kit.scrollList(shortList, true);
            Table body = new Table();
            body.add(inner).size(240, 200).row();
            body.add(still).size(240, 80).row();
            body.add().height(400);
            ScrollPane outer = ui.kit.scrollList(body, true, false);
            Table frame = new Table();
            frame.add(outer).size(300, 360);
            place(ui.window, frame, 100, 100);
            ui.draw();
            Stage stage = ui.stage;

            Rectangle area = UiTestStage.bounds(inner);
            move(ui, new Vector2(area.x + area.width / 3, area.y + area.height - 12));
            assertSame(inner, stage.getScrollFocus(), "a row of the inner list takes the wheel");
            assertTrue(stage.scrolled(0, 1) && inner.getScrollY() > 0, "the wheel scrolls the inner list");
            move(ui, new Vector2(area.x + area.width / 3, area.y + 12));
            assertSame(inner, stage.getScrollFocus(), "another row keeps it");

            Rectangle shortArea = UiTestStage.bounds(still);
            move(ui, new Vector2(shortArea.x + shortArea.width / 3, shortArea.y + shortArea.height - 12));
            assertSame(outer, stage.getScrollFocus(), "a list that cannot scroll leaves the wheel to the pane round it");
        });
    }

    @Test
    void dialogPadsItsFrameAndHeaderAndItsCloseButtonRunsTheAction() {
        UiTestStage.run(ui -> {
            AtomicInteger closed = new AtomicInteger();
            Table dialog = ui.kit.dialog("Controls", closed::incrementAndGet);
            Label body = ui.kit.label("Body", "hud-body", 13, UiTheme.TEXT);
            dialog.add(body).left();
            dialog.setWidth(620);
            place(ui.window, dialog, 100, 100);
            ui.draw();
            Rectangle frame = UiTestStage.bounds(dialog);
            Table header = (Table) dialog.getCells().first().getActor();
            Label title = (Label) header.getCells().first().getActor();
            UiButton close = (UiButton) header.getCells().peek().getActor();
            Rectangle button = UiTestStage.bounds(close);
            assertEquals("CONTROLS", title.getText().toString(), "the title is upper-cased (.phd .t)");
            assertEquals(frame.x + 20, UiTestStage.bounds(title).x, .01f, "18 inside the 2-unit side border");
            assertEquals(frame.x + frame.width - 20, button.x + button.width, .01f, "the close button at the right");
            assertEquals(frame.y + frame.height - 2 - 12, button.y + button.height, .01f, "below the rail and 12");
            assertEquals(button.y - 8, UiTestStage.bounds(body).y + UiTestStage.bounds(body).height, .01f,
                  "the header's 8 below the button, then the rows");
            assertEquals(frame.y + 18, UiTestStage.bounds(body).y, .01f, "16 above the bottom rail");
            click(ui.stage, close);
            assertEquals(1, closed.get(), "the close button runs the dialog's close action");
        });
    }

    @Test
    void popoverStaysInsideOpensAboveItsAnchorAndClosesOnAPressOutside() {
        UiTestStage.run(ui -> {
            float width = ui.window.getWidth();
            float height = ui.window.getHeight();
            UiMenuList list = new UiMenuList(ui.kit);
            list.item("Short", null, null, false, true, () -> { });
            UiPopover popover = new UiPopover(ui.kit).content(list);
            ui.window.addActor(popover);
            popover.showAt(width - 5, 5);
            Rectangle area = UiTestStage.bounds(popover);
            assertEquals(240, area.width, .01f, "a popover is at least 240 units wide");
            assertEquals(width - 10 - 240, area.x, .01f, "kept 10 inside the right edge");
            assertEquals(10, area.y, .01f, "kept 10 above the bottom edge");
            popover.showAt(-50, height + 50);
            area = UiTestStage.bounds(popover);
            assertEquals(10, area.x, .01f);
            assertEquals(height - 10, area.y + area.height, .01f, "kept 10 below the top edge");
            popover.header("Header", "Short");
            popover.showAt(width - 300, 300);
            popover.header("Header", "A much longer subtitle that makes the open popover wider than before");
            area = UiTestStage.bounds(popover);
            assertTrue(area.width > 240 && area.x + area.width <= width - 10 + .01f,
                  "an open popover whose header grows stays inside: " + area);
            assertEquals(300, area.y + area.height, .01f, "and keeps its top edge");

            UiButton anchor = place(ui.window, ui.kit.button("hud", "more", null, null), 700, 900);
            Rectangle button = UiTestStage.bounds(anchor);
            popover.showAbove(anchor, -150);
            area = UiTestStage.bounds(popover);
            assertEquals(button.x - 150, area.x, .01f, "More opens 150 units left of its button");
            assertEquals(button.y + button.height + 8, area.y, .01f, "and 8 units above it");
            UiButton corner = place(ui.window, ui.kit.button("hud", "more", null, null), 60, 900);
            popover.showAbove(corner, -150);
            assertEquals(10, UiTestStage.bounds(popover).x, .01f, "kept 10 inside the left edge");

            UiMenuList tall = new UiMenuList(ui.kit);
            for (int item = 0; item < 60; item++) {
                tall.item("Item " + item, null, null, false, true, () -> { });
            }
            popover.content(tall);
            popover.showAt(500, 500);
            area = UiTestStage.bounds(popover);
            assertEquals(height - 20, area.height, .01f, "a content taller than the window scrolls");
            assertEquals(10, area.y, .01f);
            for (int step = 0; step < 59; step++) {
                tall.keyDown(Input.Keys.DOWN);
            }
            ui.draw();
            ScrollPane scroll = (ScrollPane) tall.getParent();
            assertTrue(scroll.getScrollY() > 0, "the keyboard highlight scrolls into view");

            popover.header("Header", "A subtitle that arrives later");
            assertSame(tall, ui.stage.getKeyboardFocus(), "a header change keeps the keyboard in the list");
            // The open popover covers the first anchor; the second lies outside it.
            click(ui.stage, corner);
            assertFalse(popover.isVisible(), "a press outside closes the popover");
            assertNull(ui.stage.getKeyboardFocus(), "and takes the keyboard focus with it");
            assertFalse(popover.cancel(), "a closed popover has nothing to cancel");
            popover.showAt(500, 500);
            assertTrue(popover.cancel());
            assertFalse(popover.isVisible());
        });
    }

    // ---------------------------------------------------------------- gallery groups

    /** The combat log's top of shot 08: header tools, underline tabs, segment filters and the search field. */
    private static Table log(UiKit kit) {
        Table log = panel(kit, "panel", 2, 0);
        log.add(kit.header("Round 03 · Combat log", null, kit.button("hud-icon", "search", null, null),
              kit.button("hud-icon", "close", null, null))).growX().row();
        log.add(kit.segmented("hud-tab-caps", true, "Summary", "Full log").select(0)).growX().row();
        UiKit.Segmented filters = kit.segmented("hud-seg", true, "All events", "My force", "Critical").select(0);
        filters.pad(8, 14, 0, 14);
        log.add(filters).growX().row();
        log.add(kit.search("Search events, weapons, units…")).growX().pad(8, 14, 10, 14);
        log.setWidth(380);
        return log;
    }

    /** The top-right utilities of shot 08, with the Menu and developer Tuning utilities of the user's decisions. */
    private static Table utilities(UiKit kit) {
        Table utilities = new Table();
        utilities.defaults().padRight(8);
        utilities.add(kit.button("hud-utility", "tactical", "Tactical view", null));
        utilities.add(kit.button("hud-utility", "map", "Map", null).pressed(true));
        utilities.add(kit.button("hud-utility", "report", "Log", null).badge("4"));
        utilities.add(kit.button("hud-utility", "help", "Help", null));
        utilities.add(kit.button("hud-utility", "menu", "Menu", null));
        utilities.add(kit.button("hud-utility", "tune", "Tuning", null)).padRight(0);
        return utilities;
    }

    /** The dock of shot 08: transport, speeds, Replay, the commit button and the foot line. */
    private static Table dock(UiKit kit) {
        Table dock = panel(kit, "panel", 11, 12);
        dock.padBottom(10);
        Table head = new Table();
        head.add(kit.label(UiTheme.upper("Weapon fire · 4 / 29 resolved"), "hud-name", 13, UiTheme.TEXT))
              .expandX().left();
        head.add(kit.label("Paused", "hud-small", 11.5f, UiTheme.AMBER));
        dock.add(head).growX().pad(0, 2, 7, 2).row();
        Table first = new Table();
        first.defaults().padRight(6).minHeight(36);
        first.add(kit.button("hud", null, "‹", null)).minWidth(42);
        first.add(kit.button("hud", "play", "Play", null)).growX();
        first.add(kit.button("hud", null, "›", null)).minWidth(42);
        first.add(kit.label("4 / 4", "hud-medium", 12, UiTheme.MUTED)).pad(0, 6, 0, 12);
        first.add(kit.segmented("hud", true, "0.5×", "1×", "2×", "4×").select(1)).growX()
              .padRight(0);
        dock.add(first).growX().padBottom(6).row();
        Table second = new Table();
        UiButton replay = kit.button("hud", "rewind", "Replay", null);
        replay.pad(0, 12, 0, 12);
        second.add(replay).minHeight(46).padRight(6);
        second.add(kit.button("hud-main", null, "Skip to results", null)).growX().minHeight(46);
        dock.add(second).growX().row();
        Table foot = new Table();
        foot.add(kit.label("Atlas › King Crab · Medium Laser LA · HIT · 5 damage", "hud-small",
              11.5f, UiTheme.MUTED)).expandX().left();
        foot.add(kit.button("hud-mini", null, "Follow camera", null).pressed(true));
        dock.add(foot).growX().pad(6, 2, 0, 2);
        dock.setWidth(580);
        return dock;
    }

    /** The context menu of shot 13, a popover with its header and a menu list with a separator. */
    private static UiPopover menu(UiKit kit) {
        UiMenuList list = new UiMenuList(kit);
        list.item("Set as attack target", null, null, false, true, () -> { });
        list.separator();
        list.item("Inspect unit", null, null, false, true, () -> { });
        list.item("Center camera", null, null, false, true, () -> { });
        list.item("Line of sight from Atlas", null, null, false, true, () -> { });
        return new UiPopover(kit).header("King Crab KGC-000", "Visual contact · 7 hex").content(list);
    }

    /**
     * The states the shots do not show: pressed, disabled, icon and sub-label buttons, a row utility's dot, selects,
     * chips, checkboxes ticked and clear, and menu items checked, highlighted by the keyboard, opening a group and
     * disabled.
     */
    private static Table states(UiKit kit) {
        Table states = new Table();
        states.defaults().left().padBottom(8);
        Table buttons = new Table();
        buttons.defaults().padRight(6);
        buttons.add(kit.button("hud", null, "Walk", "3 MP"));
        buttons.add(kit.button("hud", null, "Jump", "5 MP").pressed(true));
        UiButton disabled = kit.button("hud", null, "Run", "0 MP");
        disabled.setDisabled(true);
        buttons.add(disabled);
        buttons.add(kit.button("hud", "twist-left", null, "Left")).minWidth(42).minHeight(44);
        buttons.add(kit.button("hud-icon", "grid", null, null).pressed(true));
        buttons.add(kit.button("hud-icon", "search", null, null));
        buttons.add(kit.button("hud-mini", "locate", "Locate", null));
        states.add(buttons).row();
        Table row = new Table();
        row.add(kit.button("hud-utility-row", "chat", "Chat", null).dot(true)).padRight(12);
        row.add(kit.button("hud-utility-small", null, "Back to 3D", null)).padRight(12);
        row.add(kit.select("Group by formation", false)).width(170).padRight(12);
        row.add(kit.select("Standard ammo", true));
        states.add(row).row();
        Table chips = new Table();
        chips.defaults().padRight(6);
        chips.add(kit.chip("Walk · 2 hex · TMM +0", Tone.NORMAL));
        chips.add(kit.chip("Heat +4", Tone.WARN));
        chips.add(kit.chip("Prone", Tone.BAD));
        states.add(chips).row();
        Table checkboxes = new Table();
        checkboxes.defaults().padRight(18);
        checkboxes.add(kit.checkbox("Ticked checkbox", true));
        checkboxes.add(kit.checkbox("Clear checkbox", false));
        states.add(checkboxes).row();
        Table popover = panel(kit, "panel-pop", 8, 2);
        UiMenuList list = new UiMenuList(kit);
        list.item("Select unit", "Space", true, false, true, () -> { });
        list.item("Show nameplates", "Alt", false, false, true, () -> { });
        list.item("View", null, null, true, true, () -> { });
        list.item("Line of sight", "no acting unit", null, false, false, () -> { });
        list.keyDown(Input.Keys.DOWN);
        popover.add(list).growX();
        states.add(popover).width(260);
        return states;
    }

    /** Other styles: normal-case buttons (.brow .b), a disabled commit button, natural segments, underline tabs. */
    private static Table styles(UiKit kit) {
        Table styles = new Table();
        styles.defaults().left().padBottom(8);
        Table plain = new Table();
        plain.defaults().minHeight(34).padRight(8);
        plain.add(kit.button("hud-plain", "report", "Unit record", null));
        plain.add(kit.button("hud-plain", "locate", "Locate", null).pressed(true));
        UiButton ghost = kit.button("hud-main", null, "Choose an attack", null);
        ghost.setDisabled(true);
        plain.add(ghost).minHeight(46).width(200);
        styles.add(plain).row();
        styles.add(kit.segmented("hud-seg", false, "Formation", "Weight", "Status").select(2)).row();
        Table tabs = kit.segmented("hud-tab", false, "Friendly 5", "Contacts 5").select(0);
        tabs.left().pad(2, 14, 0, 14);
        styles.add(tabs).width(300);
        return styles;
    }

    /** Meters (.meter, and the overview card's compact .bars2), and a meter over a bar of the view's own. */
    private static Table meters(UiKit kit) {
        Table meters = panel(kit, "panel", 14, 16);
        meters.defaults().growX().uniformX();
        meters.add(kit.meter("Armor", false).set("91%", .91f, UiTheme.MINT)).padRight(12);
        meters.add(kit.meter("Structure", false).set("40%", .4f, UiTheme.AMBER)).padRight(12);
        meters.add(kit.meter("Heat", false).set("0", 0, UiTheme.AMBER)).row();
        meters.add(kit.meter("Armor", true).set("76", .76f, UiTheme.MINT)).padTop(10).padRight(12);
        meters.add(kit.meter("Struct.", true).set("100", 1, UiTheme.MINT)).padTop(10).padRight(12);
        meters.add(kit.meter("Heat", true).set("12", .4f, UiTheme.AMBER)).padTop(10).row();
        Drawable bar = kit.skin.newDrawable("white", UiTheme.CORAL);
        bar.setMinHeight(4);
        meters.add(kit.meter("Own bar", new Image(bar)).value("3 of 10")).colspan(3).padTop(10);
        meters.setWidth(420);
        return meters;
    }

    /**
     * A list panel of the base components: the panel frame, its header with a count and the close button, a scrolling
     * list of rows, and the footer with its line and an action.
     */
    private static Table listPanel(UiKit kit) {
        Table panel = kit.panel();
        panel.add(kit.header("Panel", "3", kit.closeButton(() -> { }))).growX().row();
        Table list = new Table();
        list.top();
        list.add(row(kit, "Resting row")).growX().pad(0, 10, 6, 10).row();
        list.add(row(kit, "Selected row").pressed(true)).growX().pad(0, 10, 6, 10).row();
        list.add(row(kit, "Another row")).growX().pad(0, 10, 6, 10).row();
        panel.add(kit.scrollList(list)).growX().row();
        Table foot = kit.footer(panel);
        foot.add(kit.label("3 rows", "hud-small", 11.5f, UiTheme.MUTED)).growX().left();
        foot.add(kit.button("hud-mini", null, "Next ›", null));
        panel.setWidth(300);
        return panel;
    }

    /** A list row (.row): a hud-row button with its text at the left. */
    private static UiButton row(UiKit kit, String text) {
        UiButton row = kit.button("hud-row", null, text, null);
        row.getLabel().setAlignment(Align.left);
        row.getCell(row.getLabel()).growX();
        return row;
    }

    /** The same panel with an empty list: the empty state (.empty) and a footer line. */
    private static Table emptyPanel(UiKit kit) {
        Table panel = kit.panel();
        panel.add(kit.header("Empty list", null, kit.closeButton(() -> { }))).growX().row();
        Table list = new Table();
        list.top();
        kit.empty(list, "No row matches “atlas”. Search for another name.");
        panel.add(kit.scrollList(list)).growX().row();
        kit.footer(panel).add(kit.label("0 rows", "hud-small", 11.5f, UiTheme.MUTED)).growX().left();
        panel.setWidth(290);
        return panel;
    }

    /**
     * A panel with a reorderable list (UiList): four rows, each a grip (named gallery-grip-n), which drags it, and a
     * label, over a rule.
     */
    private static Table reorderable(UiKit kit) {
        UiList list = new UiList(kit).reorderable((from, to) -> { });
        List<String> names = List.of("Alpha", "Bravo", "Charlie", "Delta");
        for (int index = 0; index < names.size(); index++) {
            Table row = new Table();
            row.pad(0, 12, 0, 12);
            UiKit.Icon grip = kit.icon("grip", 12, UiTheme.MUTED);
            grip.setName("gallery-grip-" + index);
            row.add(grip).size(12, 16);
            row.add(kit.label(names.get(index), "hud-body", 13, UiTheme.TEXT)).growX().left().height(40).padLeft(10);
            Table entry = new Table();
            entry.add(row).growX().row();
            entry.add(new Image(kit.skin.getDrawable("rule"))).growX().height(1);
            list.add(entry, grip);
        }
        Table panel = kit.panel();
        panel.add(kit.header("Reorderable list", "4")).growX().row();
        panel.add(list).growX();
        panel.setWidth(300);
        return panel;
    }

    /** Presses {@code grip} and holds it {@code rise} units higher, then lets the other rows slide into place. */
    private static void holdMidDrag(Stage stage, Actor grip, float rise) {
        Vector2 from = grip.localToStageCoordinates(new Vector2(grip.getWidth() / 2, grip.getHeight() / 2));
        Vector2 press = stage.stageToScreenCoordinates(from.cpy());
        stage.touchDown(Math.round(press.x), Math.round(press.y), 0, Input.Buttons.LEFT);
        for (int step = 1; step <= 6; step++) {
            Vector2 point = stage.stageToScreenCoordinates(new Vector2(from.x, from.y + rise * step / 6));
            stage.touchDragged(Math.round(point.x), Math.round(point.y), 0);
        }
        for (int frame = 0; frame < 24; frame++) {
            stage.act(1 / 60f);
        }
    }

    /**
     * A tooltip's box as the tooltip manager shows it on hover (UiKit.tip: hud-small text on the panel surface,
     * wrapped at 340 units); no smoke test hovers, so the gallery draws the box itself.
     */
    private static Container<Label> tooltip(UiKit kit) {
        TextTooltip tip = kit.tip(new Actor());
        tip.getActor().setText("Tooltip text in hud-small on the panel surface; a long text wraps at 340 units, as"
              + " the tooltip manager lays it out.");
        Container<Label> box = tip.getContainer();
        box.setSize(Integer.MAX_VALUE, Integer.MAX_VALUE);
        box.validate();
        box.width(box.getActor().getWidth());
        box.pack();
        return box;
    }

    /**
     * A dialog (.panel.dlg, the Help, Menu and Players look): its header with the title and close button, then a body
     * with a caption (.dlg .cap) and a menu list with a shortcut, a check mark, a group and a disabled item.
     */
    private static Table dialog(UiKit kit) {
        Table dialog = kit.dialog("Dialog", () -> { });
        Table body = new Table();
        body.top().left();
        body.add(kit.caption("Caption")).left().padTop(12).padBottom(7).row();
        UiMenuList list = new UiMenuList(kit);
        list.item("Item with a shortcut", "Ctrl+K", false, false, true, () -> { });
        list.item("Checked item", null, true, false, true, () -> { });
        list.separator();
        list.item("Group", null, false, true, true, () -> { });
        list.item("Unavailable item", null, false, false, false, () -> { });
        body.add(list).growX();
        dialog.add(kit.scrollList(body)).grow().minHeight(0);
        dialog.setWidth(620);
        return dialog;
    }

    /**
     * Adds an image icon as a view does: the battle HUD's enemy icon, mm-data's black skull, which the icon font
     * lacks.
     */
    private static void addEnemyIcon(UiTheme theme) {
        Pixmap image = new Pixmap(Gdx.files.local("data/images/misc/challenge_estimate_full.png"));
        try {
            theme.addIcon("enemy", image);
        } finally {
            image.dispose();
        }
    }

    /** Every icon of the sheet with its name: the baked Material symbols and the image icons a view added. */
    private static Table icons(UiKit kit) {
        Table icons = new Table();
        icons.defaults().width(64).padBottom(6);
        int column = 0;
        List<String> names = new ArrayList<>();
        kit.skin.getAll(Drawable.class).keys().forEach(name -> {
            if (name.startsWith("icon-")) {
                names.add(name.substring("icon-".length()));
            }
        });
        for (String name : names.stream().sorted().toList()) {
            Table cell = new Table();
            cell.add(kit.icon(name, 19, UiTheme.ACCENT)).row();
            cell.add(kit.label(name, "hud-small", 10, UiTheme.MUTED)).padTop(2);
            icons.add(cell);
            if (++column % 28 == 0) {
                icons.row();
            }
        }
        return icons;
    }

    // ---------------------------------------------------------------- helpers

    private static Table panel(UiKit kit, String background, float vertical, float horizontal) {
        Table panel = new Table();
        panel.setBackground(kit.skin.getDrawable(background));
        panel.pad(vertical, horizontal, vertical, horizontal);
        panel.top().left();
        return panel;
    }

    /** The items shown pressed, the keyboard highlight. */
    private static List<UiButton> pressed(List<UiButton> items) {
        return items.stream().filter(UiButton::isChecked).toList();
    }

    /** Draws the list where its scroll goes, without the smooth scroll's frames in between. */
    private static void showScrolled(UiTestStage ui, ScrollPane scroll) {
        ui.draw();
        scroll.updateVisualScroll();
        ui.draw();
    }

    private static void assertInView(ScrollPane scroll, Actor item, String what) {
        Rectangle view = UiTestStage.bounds(scroll);
        Rectangle area = UiTestStage.bounds(item);
        assertTrue(area.y >= view.y - .5f && area.y + area.height <= view.y + view.height + .5f,
              what + " at " + area + " shows in the view " + view);
    }

    private static ChangeListener changed(AtomicInteger count) {
        return new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                count.incrementAndGet();
            }
        };
    }

    /** Moves the mouse to a stage point without a button and draws a frame, which fires its enter and exit. */
    private static void move(UiTestStage ui, Vector2 point) {
        Vector2 screen = ui.stage.stageToScreenCoordinates(point.cpy());
        ui.stage.mouseMoved(Math.round(screen.x), Math.round(screen.y));
        ui.draw();
    }

    /** A left click at the actor's center, through the stage's own input. */
    private static void click(Stage stage, Actor actor) {
        Vector2 point = stage.stageToScreenCoordinates(
              actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
        stage.touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
        stage.touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
    }
}
