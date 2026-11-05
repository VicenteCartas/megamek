/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudFixtures.ATLAS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.EventListener;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.unitDisplay.WeaponDisplayData;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiMenuList;
import megamek.client.ui.gdx.UiPopover;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.WeaponMounted;
import megamek.common.rolls.TargetRoll;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.Targetable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The weapons panel and the solution card (G10) in the component harness: beside the hud-v3 shots 05, 06, 13 and 15
 * where the HUD places them, their layout at the three window sizes, a newly selected row scrolled into view and kept
 * there, the read-only draft (H33), the commands their controls post, Esc and the card's close button; and over a
 * real FiringDisplay with E3b's services: target-first and weapon-first assignment, the slot buttons, letters that
 * stay, and the ammunition select, which picks the second of two bins with one label. The mock orders are display
 * values read off the shots, as E3b would publish them.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Tag("on-demand")
class GpuWeaponsPanelSmokeTest {
    private static final GpuBattleStatus.Snapshot MOCK = GpuHudFixtures.status();
    private static final int TIMBER_WOLF = 6;
    private static final int KING_CRAB = 7;
    private static final int BATTLEMASTER = 8;
    /** The Atlas's weapons in the Unit Display's order (shot 05), by equipment number. */
    private static final int AC20 = 1;
    private static final int LRM = 2;
    private static final int SRM = 3;
    private static final int LASER_LA = 4;
    private static final int LASER_RA = 5;
    private static final int REAR = 6;
    private static final int REAR_2 = 7;
    /** The Atlas's heat ticks under the standard heat table (E3b's literal), and its scale. */
    private static final List<Integer> TICKS = List.of(5, 8, 10, 13, 14, 15, 17, 18, 19, 20, 22, 23, 24, 25, 26, 27,
          28, 29, 30);
    /** Shots 05, 06 and 13 (1920 x 1080): the column at x 1590 from the rails at y 312; the card ends 70 above. */
    private static final int COLUMN_X = 1590;
    private static final int COLUMN_TOP = 312;
    /** Shot 15 (1280 x 720): the narrow column at x 994 from y 282, the card beside it at x 712, y 90. */
    private static final int COMPACT_X = 994;
    private static final int COMPACT_TOP = 282;
    private static final int COMPACT_BESIDE = 712;
    /** The HUD's minimap above the column: right gap, top 90, 208 tall (178 at a height of 800 or less). */
    private static final int MINIMAP_TOP = 90;
    private static final String DASH = "—";

    /** The panel and the card in their HUD slots, with the HUD state, the services and the context menu. */
    private static final class Column {
        final GpuHudState state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
        final GpuBoardSource source;
        final GpuContextMenu menu;
        final GpuWeaponsPanel weapons;
        final GpuSolutionCard solution;
        final Container<Actor> weaponsSlot;
        final Container<Actor> solutionSlot;

        Column(GpuHudTestStage hud, GpuBoardSource source) {
            this.source = source;
            menu = new GpuContextMenu(hud.kit, source, state, id -> { });
            weapons = new GpuWeaponsPanel(hud.kit, source, state, menu);
            solution = new GpuSolutionCard(hud.kit, source, state);
            weaponsSlot = slot(hud, weapons.actor());
            solutionSlot = slot(hud, solution.actor());
            Actor popover = menu.actor();
            popover.setBounds(0, 0, hud.width(), hud.height());
            hud.window.addActor(popover);
        }

        /** One frame: the status through the HUD state's rules, then the menu, the panel and the card. */
        Column update(GpuHudTestStage hud, GpuBattleStatus.Snapshot status, GpuFireOrders.Snapshot fire) {
            state.update(status, GpuUnitRecord.Snapshot.EMPTY, false);
            GpuHud.Inputs inputs = new GpuHud.Inputs(frame(status, fire), GpuHud.HudView.EMPTY, null,
                  GpuContextMenuSmokeTest.preferences(), GpuHud.Metrics.of(hud.width(), hud.height()), List.of());
            menu.update(inputs);
            weapons.update(inputs);
            solution.update(inputs);
            return this;
        }

        /**
         * Places both slots as GpuHud.layout places them: the column {@code width} wide at {@code x} from {@code top}
         * to 70 above the window's bottom, less the card and its 12-unit gap while the card sits under it; the card
         * beside the column at ({@code besideX}, 90) when {@code besideX} is not negative.
         */
        Column place(GpuHudTestStage hud, float x, float top, float width, float besideX) {
            float bottom = hud.height() - 70;
            float card = solution.actor().isVisible() ? solutionSlot.getPrefHeight() : 0;
            boolean under = besideX < 0 && card > 0;
            set(hud, weaponsSlot, x, top, width, (under ? bottom - card - 12 : bottom) - top);
            set(hud, solutionSlot, under ? x : besideX, under ? bottom - card : MINIMAP_TOP, width, card);
            weaponsSlot.validate();
            solutionSlot.validate();
            return this;
        }

        <T extends Actor> T find(String name) {
            T actor = ((Group) weapons.actor()).findActor(name);
            if (actor == null) {
                actor = ((Group) solution.actor()).findActor(name);
            }
            assertNotNull(actor, name);
            return actor;
        }

        /** The shown texts under the named actor, in drawing order. */
        List<String> texts(String name) {
            List<String> texts = new ArrayList<>();
            collect(find(name), texts);
            return texts;
        }

        /** A paragraph's words as one line. */
        String line(String name) {
            return String.join(" ", texts(name));
        }

        /** The card's title as one text: the part up to the target's name and the roll after it. */
        String title() {
            return String.join("", texts("solution-header"));
        }

        UiPopover popover() {
            return ((Group) menu.actor()).findActor("context-menu-popover");
        }
    }

    @Test
    void besideShots05And13() {
        GpuHudTestStage.run(hud -> {
            Column column = new Column(hud, mock(GpuBoardSource.class)).update(hud, firing(), shot05());
            column.place(hud, COLUMN_X, COLUMN_TOP, 310, -1);
            hud.draw();
            assertEquals(List.of("WEAPONS", "5 / 7 queued"), column.texts("weapons-header"));
            assertEquals(List.of("A", "Timber Wolf", "B", "BattleMaster"), column.texts("weapons-pills"));
            assertEquals("Assign weapon → A · Timber Wolf", column.line("weapons-assign"));
            assertEquals(List.of("AC/20", "RT · 20 dmg · 7 heat", "[RT] AC/20  (8)", "7+", "58%", "A"),
                  column.texts("weapons-row-" + AC20));
            assertEquals(List.of("LRM 20", "LT · 1×20 dmg · 6 heat", "[LT] LRM 20  (11)", "8+", "42%",
                  "B"), column.texts("weapons-row-" + LRM));
            assertEquals(List.of("MEDIUM LASER", "CT(R) · 5 dmg · 3 heat · ", "rear arc only", DASH,
                  DASH), column.texts("weapons-row-" + REAR));
            assertEquals(List.of("HEAT IF FIRED", "0 → 24 · −20 at end = 4",
                  "No heat effects at the end of the turn"), column.texts("weapons-heat"));
            assertEquals("AC/20 → A · TIMBER WOLF · 7+", column.title());
            assertEquals(List.of("S 3", "M 6", "L 9", "Forward arc"), column.texts("solution-ranges"));
            assertEquals("Gunnery (Hayes) 3 · Attacker walk +1 · Target moved 3 hexes +1 · Medium range"
                  + " (5) +2", column.line("solution-modifiers"));
            assertEquals("Range 5 hexes · roll 2d6 ≥ 7 · 58%", column.line("solution-result"));
            UiButton slot = column.find("weapons-slot-" + REAR);
            assertTrue(slot.isDisabled());
            assertEquals("rear arc only", tooltip(slot));
            assertEquals(Messages.getString("GpuBoard.hud.weapons.nameSortTip"),
                  tooltip(column.find("weapons-name-" + AC20)));
            assertTrue(column.weapons.actor().getHeight() < column.weaponsSlot.getHeight() + .5f);

            Pixmap image = hud.capture("weapons-05");
            try {
                hud.compare("weapons-05", image, column.weapons.actor(), "05-weapon-declaration.jpg", COLUMN_X,
                      COLUMN_TOP);
                hud.compare("weapons-13", image, column.weapons.actor(), "13-context-menu.jpg", COLUMN_X, COLUMN_TOP);
                Rectangle card = GpuHudTestStage.bounds(column.solution.actor());
                hud.compare("solution-05", image, column.solution.actor(), "05-weapon-declaration.jpg", COLUMN_X,
                      Math.round(hud.height() - card.y - card.height));
            } finally {
                image.dispose();
            }
        });
    }

    @Test
    void threeTargetsBesideShot06() {
        GpuHudTestStage.run(hud -> {
            Column column = new Column(hud, mock(GpuBoardSource.class)).update(hud, firing(), shot06());
            column.place(hud, COLUMN_X, COLUMN_TOP, 310, -1);
            hud.draw();
            assertEquals(List.of("A", "Timber Wolf", "B", "BattleMaster", "C", "King Crab"),
                  column.texts("weapons-pills"));
            // The pills wrap: the third starts a second line (shot 06).
            Rectangle first = GpuHudTestStage.bounds(column.find("weapons-pill-" + TIMBER_WOLF));
            Rectangle third = GpuHudTestStage.bounds(column.find("weapons-pill-" + KING_CRAB));
            assertTrue(third.y + third.height <= first.y, "King Crab's pill opens a second line");
            assertEquals(List.of("9+", "28%", "C"), column.texts("weapons-row-" + AC20).subList(3, 6));
            assertEquals("AC/20 → C · KING CRAB · 9+", column.title());
            assertEquals("Gunnery (Hayes) 3 · Attacker walk +1 · Long range (7) +4 · Secondary target"
                  + " (front) +1", column.line("solution-modifiers"));
            assertEquals("Range 7 hexes · roll 2d6 ≥ 9 · 28%", column.line("solution-result"));
            Pixmap image = hud.capture("weapons-06");
            try {
                hud.compare("weapons-06", image, column.weapons.actor(), "06-three-targets.jpg", COLUMN_X,
                      COLUMN_TOP);
                Rectangle card = GpuHudTestStage.bounds(column.solution.actor());
                hud.compare("solution-06", image, column.solution.actor(), "06-three-targets.jpg", COLUMN_X,
                      Math.round(hud.height() - card.y - card.height));
            } finally {
                image.dispose();
            }
        });
    }

    @Test
    void compactBesideShot15() {
        GpuHudTestStage.run(hud -> {
            hud.size(1280, 720);
            Column column = new Column(hud, mock(GpuBoardSource.class)).update(hud, firing(), shot05());
            column.place(hud, COMPACT_X, COMPACT_TOP, 270, COMPACT_BESIDE);
            hud.draw();
            Pixmap image = hud.capture("weapons-15");
            try {
                hud.compare("weapons-15", image, column.weapons.actor(), "15-compact-1280x720.jpg", COMPACT_X,
                      COMPACT_TOP);
                hud.compare("solution-15", image, column.solution.actor(), "15-compact-1280x720.jpg", COMPACT_BESIDE,
                      MINIMAP_TOP);
            } finally {
                image.dispose();
            }
            // The short column scrolls its rows; the heat stays under them (shot 15).
            ScrollPane scroll = column.find("weapons-list");
            assertTrue(scroll.isScrollY(), "the rows scroll in the short column");
            Rectangle list = GpuHudTestStage.bounds(scroll);
            Rectangle heat = GpuHudTestStage.bounds(column.find("weapons-heat"));
            assertTrue(heat.y + heat.height <= list.y + .5f);
            UiButton alphaStrike = column.find("weapons-alpha-strike");
            Rectangle footer = GpuHudTestStage.bounds(alphaStrike);
            Actor footerSection = column.find("weapons-footer");
            assertEquals("ALPHA STRIKE", alphaStrike.getText().toString());
            assertNull(footerSection.firstAscendant(ScrollPane.class));
            assertTrue(alphaStrike.isDescendantOf(footerSection));
            assertTrue(column.find("weapons-heat").isDescendantOf(footerSection));
            assertTrue(footer.y >= GpuHudTestStage.bounds(column.weapons.actor()).y
                  && footer.y + footer.height <= heat.y);
            scroll.setScrollY(scroll.getMaxY());
            scroll.updateVisualScroll();
            hud.draw();
            assertEquals(footer, GpuHudTestStage.bounds(alphaStrike), "the footer stays put while weapons scroll");
            assertEquals(heat, GpuHudTestStage.bounds(column.find("weapons-heat")),
                  "HEAT IF FIRED stays in the footer while weapons scroll");
        });
    }

    /**
     * P1 H1: MegaMek's long name of shot 05's target ends in the ellipsis, and the roll after it stays whole, in the
     * column of shot 05 and beside the narrow column of shot 15.
     */
    @Test
    void aLongTargetNameEndsInTheEllipsisAndTheRollStaysWhole() {
        GpuHudTestStage.run(hud -> {
            GpuFireOrders.Snapshot fire = named(shot05(), TIMBER_WOLF, "Mad Cat (Timber Wolf) Prime");
            for (int[] size : new int[][] { { 1920, 1080 }, { 1280, 720 } }) {
                hud.size(size[0], size[1]);
                hud.window.clearChildren();
                Column column = new Column(hud, mock(GpuBoardSource.class)).update(hud, firing(), fire);
                boolean compact = size[0] == 1280;
                column.place(hud, compact ? COMPACT_X : COLUMN_X, compact ? COMPACT_TOP : COLUMN_TOP,
                      compact ? 270 : 310, compact ? COMPACT_BESIDE : -1);
                hud.draw();
                assertEquals("AC/20 → A · MAD CAT (TIMBER WOLF) PRIME · 7+", column.title());
                Label title = column.find("solution-title");
                Label roll = column.find("solution-roll");
                assertTrue(title.getWidth() < title.getPrefWidth() - .5f, "the name is cut at " + size[0]);
                assertTrue(title.getGlyphLayout().width <= title.getWidth() + .5f, "and drawn inside its cell");
                assertTrue(roll.getWidth() >= roll.getPrefWidth() - .5f, "the roll keeps its width at " + size[0]);
                Rectangle shown = GpuHudTestStage.bounds(roll);
                Rectangle card = GpuHudTestStage.bounds(column.solution.actor());
                assertTrue(shown.x + shown.width <= GpuHudTestStage.bounds(column.find("solution-close")).x
                      && shown.x >= card.x, "the roll lies between the name and the close button: " + shown);
                hud.capture("solution-long-name-" + size[0] + "x" + size[1]).dispose();
            }
        });
    }

    /** E rule 6: in the window, inside its own width, clear of the minimap and of each other at the three sizes. */
    @Test
    void layoutAtEveryWindowSize() {
        GpuHudTestStage.run(hud -> {
            int[][] sizes = { { 900, 600 }, { 1280, 720 }, { 1920, 1080 } };
            for (int[] size : sizes) {
                hud.size(size[0], size[1]);
                GpuHud.Metrics metrics = GpuHud.Metrics.of(size[0], size[1]);
                hud.window.clearChildren();
                Column column = new Column(hud, mock(GpuBoardSource.class)).update(hud, firing(), shot06());
                float x = size[0] - metrics.gap() - metrics.right();
                float minimap = metrics.lowHeight() ? 178 : 208;
                minimap = Math.min(minimap, size[1] - MINIMAP_TOP - 12 - 70 - column.weapons.minimumHeight());
                float top = MINIMAP_TOP + minimap + 12;
                float card = column.solutionSlot.getPrefHeight();
                // GpuHud.layout: beside the column when the column would keep less than 430 units under the card.
                boolean beside = size[1] - top - 70 - card - 12 < 430;
                column.place(hud, x, top, metrics.right(), beside ? x - metrics.right() - 12 : -1);
                hud.draw();
                Rectangle map = new Rectangle(x, size[1] - MINIMAP_TOP - minimap, metrics.right(), minimap);
                hud.assertLayout(column.weapons.actor(), map, GpuHudTestStage.bounds(column.solution.actor()));
                hud.assertLayout(column.solution.actor(), map, GpuHudTestStage.bounds(column.weapons.actor()));
                assertTrue(visible(column.find("weapons-list"), column.find("weapons-row-" + AC20)),
                      "the fixed footer leaves room for a complete weapon row at " + size[0] + "x" + size[1]);
                hud.capture("weapons-layout-" + size[0] + "x" + size[1]).dispose();
            }
        });
    }

    /** H39: a newly selected row scrolls into view, as little as needed; an unchanged selection keeps the scroll. */
    @Test
    void aNewlySelectedRowScrollsIntoView() {
        GpuHudTestStage.run(hud -> {
            hud.size(1280, 720);
            Column column = new Column(hud, mock(GpuBoardSource.class)).update(hud, firing(), shot05());
            column.place(hud, COMPACT_X, COMPACT_TOP, 270, COMPACT_BESIDE);
            hud.draw();
            ScrollPane scroll = column.find("weapons-list");
            assertFalse(visible(scroll, column.find("weapons-row-" + REAR_2)), "the last row starts out of view");
            column.update(hud, firing(), selected(shot05(), REAR_2)).place(hud, COMPACT_X, COMPACT_TOP, 270,
                  COMPACT_BESIDE);
            // The scroll runs before the frame is drawn; the list then moves smoothly, here at once.
            hud.draw();
            scroll.updateVisualScroll();
            hud.draw();
            assertTrue(visible(scroll, column.find("weapons-row-" + REAR_2)), "the selected last row is shown");
            assertEquals(scroll.getMaxY(), scroll.getScrollY(), .5f, "the list scrolled no further than needed");
            column.update(hud, firing(), selected(shot05(), AC20)).place(hud, COMPACT_X, COMPACT_TOP, 270,
                  COMPACT_BESIDE);
            hud.draw();
            scroll.updateVisualScroll();
            hud.draw();
            assertTrue(visible(scroll, column.find("weapons-row-" + AC20)), "scroll " + scroll.getScrollY() + " of "
                  + scroll.getMaxY() + ", row " + GpuHudTestStage.bounds(column.find("weapons-row-" + AC20))
                  + " in " + GpuHudTestStage.bounds(scroll));
            assertEquals(0, scroll.getScrollY(), .5f);
            hud.capture("weapons-scrolled").dispose();
        });
    }

    /**
     * H39: a selected row in view stays in view when a third target's pill takes a line from the list (shot 06); a
     * scroll that left the selected row keeps its place.
     */
    @Test
    void aSelectedRowInViewStaysInViewWhenThePillsWrap() {
        GpuHudTestStage.run(hud -> {
            hud.size(1280, 720);
            Column column = new Column(hud, mock(GpuBoardSource.class));
            ScrollPane scroll = column.find("weapons-list");
            Consumer<GpuFireOrders.Snapshot> frame = fire -> {
                column.update(hud, firing(), fire).place(hud, COMPACT_X, COMPACT_TOP, 270, COMPACT_BESIDE);
                hud.draw();
                scroll.updateVisualScroll();
                hud.draw();
            };
            frame.accept(selected(shot05(), REAR_2));
            float list = scroll.getHeight();
            assertTrue(visible(scroll, column.find("weapons-row-" + REAR_2)), "the selected last row is shown");
            frame.accept(selected(shot06(), REAR_2));
            assertTrue(scroll.getHeight() < list, "the third pill takes a line from the list");
            assertTrue(visible(scroll, column.find("weapons-row-" + REAR_2)), "the selected last row stays shown");
            scroll.setScrollY(0);
            frame.accept(focused(selected(shot06(), REAR_2), KING_CRAB));
            assertEquals(0, scroll.getScrollY(), .5f, "the list keeps a scroll that left the selected row");
        });
    }

    /** H33: on another player's turn the own focus unit's draft is read-only, with no pills, slots, heat or card. */
    @Test
    void theReadOnlyDraftOnAnotherPlayersTurn() {
        GpuHudTestStage.run(hud -> {
            GpuFireOrders.Snapshot draft = new GpuFireOrders.Snapshot(true, false, ATLAS, GpuFireOrders.Focus.NONE, -1,
                  List.of(), List.of(), List.of(attack(AC20, TIMBER_WOLF, "AC/20", "RT", "[RT] AC/20  (8)", 7, 58.3),
                  attack(LRM, BATTLEMASTER, "LRM 20", "LT", "[LT] LRM 20  (11)", 8, 41.7)), 0, false, false, "",
                  null, null, null, List.of(), null, Map.of(), 4, 0, null);
            GpuBattleStatus.Snapshot opponent = status(GamePhase.FIRING, false, Entity.NONE);
            Column column = new Column(hud, mock(GpuBoardSource.class)).update(hud, opponent, draft);
            column.place(hud, COLUMN_X, COLUMN_TOP, 310, -1);
            hud.draw();
            assertEquals(List.of("WEAPONS", "Atlas AS7-D · 2 drafted"), column.texts("weapons-header"));
            ScrollPane list = column.find("weapons-list");
            List<String> rows = new ArrayList<>();
            collect(list, rows);
            assertEquals(List.of("AC/20", "RT → Timber Wolf Prime", "[RT] AC/20  (8)", "7+", "58%", "LRM 20",
                  "LT → BattleMaster BLR-1G", "[LT] LRM 20  (11)", "8+", "42%"), rows);
            assertFalse(column.solution.actor().isVisible(), "a draft has no solution");
            assertNull(((Group) column.weapons.actor()).findActor("weapons-heat"));
            assertNull(((Group) column.weapons.actor()).findActor("weapons-pills"));
            assertNull(((Group) column.weapons.actor()).findActor("weapons-alpha-strike"));
            assertFalse(column.weapons.cancel(), "Esc goes on: nothing is armed or selected in a draft");
            hud.capture("weapons-read-only").dispose();
        });
    }

    /**
     * What the controls post (H15-H18, H30, H35, H36): the slots retarget, remove and assign; a name arms and selects
     * its weapon, and a pill then focuses its enemy and assigns the armed weapon once; the selected name deselects; a
     * right click opens the weapon menu; the ammunition select loads the bin chosen by its carrier and number; Esc
     * disarms, then deselects, then goes on; the card's close button disarms and deselects.
     */
    @Test
    void theControlsPostTheirCommands() {
        GpuHudTestStage.run(hud -> {
            GpuBoardSource source = mock(GpuBoardSource.class);
            GpuFireOrders fire = mock(GpuFireOrders.class);
            when(source.fire()).thenReturn(fire);
            GpuFireOrders.Snapshot crabFocused = focused(shot05(), KING_CRAB);
            Column column = new Column(hud, source).update(hud, firing(), crabFocused);
            column.place(hud, COLUMN_X, COLUMN_TOP, 310, -1);
            hud.draw();
            // The focused King Crab has no attack: a dashed "+" pill that assigns to it.
            assertEquals(List.of("+", "King Crab KGC-000"), column.texts("weapons-pill-" + KING_CRAB));
            assertEquals("Assign to King Crab KGC-000", tooltip(column.find("weapons-pill-" + KING_CRAB)));
            assertEquals("Assign weapon → King Crab KGC-000", column.line("weapons-assign"));
            UiButton laser = column.find("weapons-slot-" + LASER_LA);
            assertEquals("Retarget to King Crab KGC-000", tooltip(laser));
            click(hud, laser);
            verify(fire).retarget(LASER_LA, TargetKey.unit(KING_CRAB));
            UiButton plus = column.find("weapons-slot-" + REAR_2);
            assertEquals("+", plus.getText().toString());
            click(hud, plus);
            verify(fire).assign(REAR_2, TargetKey.unit(KING_CRAB));
            column.state.armedWeapon = SRM;
            click(hud, column.find("weapons-alpha-strike"));
            verify(fire).assignAll(TargetKey.unit(KING_CRAB));
            assertEquals(-1, column.state.armedWeapon);

            // With the Timber Wolf focused, its letter removes the attack.
            column.update(hud, firing(), shot05()).place(hud, COLUMN_X, COLUMN_TOP, 310, -1);
            assertEquals("Remove this attack", tooltip(column.find("weapons-slot-" + LASER_LA)));
            click(hud, column.find("weapons-slot-" + LASER_LA));
            verify(fire).remove(LASER_LA);

            // Weapon-first (H16): the LRM's name arms and selects it; the BattleMaster's pill assigns it once.
            click(hud, column.find("weapons-name-" + LRM));
            assertEquals(LRM, column.state.armedWeapon);
            verify(fire).selectWeapon(LRM);
            column.update(hud, firing(), shot05()).place(hud, COLUMN_X, COLUMN_TOP, 310, -1);
            // No assign line while a weapon is armed: its row shows it selected.
            Actor assignLine = ((Group) column.weapons.actor()).findActor("weapons-assign");
            assertTrue(assignLine == null || !GpuBoardTestUi.shown(assignLine), "no line for the armed weapon");
            click(hud, column.find("weapons-pill-" + BATTLEMASTER));
            verify(fire).focusTarget(TargetKey.unit(BATTLEMASTER));
            verify(fire).assign(LRM, TargetKey.unit(BATTLEMASTER));
            assertEquals(-1, column.state.armedWeapon);
            // The selected AC/20's name deselects it.
            click(hud, column.find("weapons-name-" + AC20));
            verify(fire).selectWeapon(-1);

            // A right click opens the weapon's menu (H36).
            press(hud, column.find("weapons-row-" + AC20), Input.Buttons.RIGHT);
            assertTrue(column.popover().isVisible());
            List<String> menu = new ArrayList<>();
            collect(column.popover(), menu);
            assertTrue(menu.containsAll(List.of("AC/20", "RT · 20 dmg · 7 heat", "Hide solution and arc")),
                  "the AC/20's menu: " + menu);
            column.menu.cancel();

            // H30: the select lists the bins and posts the one picked, by carrier and number.
            click(hud, column.find("weapons-ammo-" + AC20));
            List<UiButton> items = menuItems(column);
            assertEquals(List.of("[RT] AC/20  (8)", "[RT] AC/20 Armor-Piercing  (4)"), items.stream()
                  .map(item -> item.getText().toString()).toList());
            click(hud, items.get(1));
            verify(fire).setAmmo(AC20, new GpuUnitRecord.AmmoChoice(ATLAS, 12, "[RT] AC/20 Armor-Piercing  (4)"));

            // H35: Esc disarms, then deselects, then goes on down the chain while the deselection is posted.
            clearInvocations(fire);
            column.state.armedWeapon = SRM;
            assertTrue(column.weapons.cancel());
            assertEquals(-1, column.state.armedWeapon);
            verify(fire, never()).selectWeapon(anyInt());
            assertTrue(column.weapons.cancel());
            verify(fire).selectWeapon(-1);
            assertFalse(column.weapons.cancel());

            // The card's close button (Esc) disarms and deselects.
            clearInvocations(fire);
            column.state.armedWeapon = SRM;
            click(hud, column.find("solution-close"));
            verify(fire).selectWeapon(-1);
            assertEquals(-1, column.state.armedWeapon);
        });
    }

    /**
     * Over a real FiringDisplay (E3b, E3c): target-first ("+" on a row after the board focused the Archer),
     * weapon-first (a name arms, then the Archer's pill or the shared target action assigns), letters that stay when the
     * primary changes, a slot that retargets, one that removes, one that cannot shoot, and the ammunition select that
     * picks the second of the AC/20's two bins with one label.
     */
    @Test
    void ordersThroughTheFiringDisplay() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            Entity crab = GpuFiringFixture.enemy(firing, "Crab CRB-20.mtf", 44, GpuFiringFixture.NORTH);
            int archer = firing.ahead.getId();
            int cannon = GpuFiringFixture.eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
            int right = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_RIGHT_ARM);
            int left = GpuFiringFixture.eqNum(firing, "Medium Laser", Mek.LOC_LEFT_ARM);
            int missiles = GpuFiringFixture.eqNum(firing, "LRM 20", Mek.LOC_LEFT_TORSO);
            WeaponMounted gun = GpuFiringFixture.weapon(firing.attacker, "AC/20", Mek.LOC_RIGHT_TORSO);
            GpuHudTestStage.run(hud -> {
                Column column = new Column(hud, firing.board.source);
                // Target-first (H15): the board's click focused the Archer; "+" on the AC/20's row assigns it.
                firing.board.source.fire().focusTarget(TargetKey.unit(archer));
                show(hud, column, settled(firing));
                assertEquals(List.of("+", "Archer ARC-2R"), column.texts("weapons-pills"));
                click(hud, column.find("weapons-slot-" + cannon));
                show(hud, column, settled(firing));
                assertEquals(List.of("AC/20 RT@42"), GpuFiringFixture.queue(firing));
                assertEquals(List.of("A", "Archer ARC-2R"), column.texts("weapons-pills"));

                // Weapon-first (H16): the right arm's laser is armed by its name and assigned by the Archer's pill.
                click(hud, column.find("weapons-name-" + right));
                assertEquals(right, column.state.armedWeapon);
                click(hud, column.find("weapons-pill-" + archer));
                show(hud, column, settled(firing));
                assertEquals(-1, column.state.armedWeapon);
                // ... and the left arm's laser by the shared target action used by board clicks and menus.
                click(hud, column.find("weapons-name-" + left));
                show(hud, column, settled(firing));
                GpuContextMenu.focusTarget(firing.board.source, column.state, TargetKey.unit(crab.getId()));
                show(hud, column, settled(firing));
                assertEquals(List.of("AC/20 RT@42", "Medium Laser RA@42", "Medium Laser LA@44"),
                      GpuFiringFixture.queue(firing));
                assertEquals(List.of("A", "Archer ARC-2R", "B", "Crab CRB-20"), column.texts("weapons-pills"));

                // H12: the Crab made primary keeps its letter; the star moves to it.
                firing.board.source.fire().setPrimary(TargetKey.unit(crab.getId()));
                show(hud, column, settled(firing));
                assertEquals(List.of("A", "Archer ARC-2R", "B", "Crab CRB-20"), column.texts("weapons-pills"));
                assertTrue(hasIcon(column.find("weapons-pill-" + crab.getId())));
                assertFalse(hasIcon(column.find("weapons-pill-" + archer)));

                // H18: with the Crab focused, the AC/20's letter retargets it and the left laser's letter removes it.
                assertEquals("Retarget to Crab CRB-20", tooltip(column.find("weapons-slot-" + cannon)));
                click(hud, column.find("weapons-slot-" + cannon));
                show(hud, column, settled(firing));
                assertTrue(GpuFiringFixture.queue(firing).contains("AC/20 RT@44"));
                click(hud, column.find("weapons-slot-" + left));
                show(hud, column, settled(firing));
                assertFalse(GpuFiringFixture.queue(firing).contains("Medium Laser LA@44"));
                // The Hachiwara behind the woods: no shot, and the slot says why.
                firing.board.source.fire().focusTarget(TargetKey.unit(firing.left.getId()));
                show(hud, column, settled(firing));
                UiButton blocked = column.find("weapons-slot-" + missiles);
                assertTrue(blocked.isDisabled());
                assertEquals("LOS blocked by terrain.", tooltip(blocked));

                // H30: the AC/20's two bins read alike; the select still loads the second, by carrier and number.
                click(hud, column.find("weapons-ammo-" + cannon));
                List<UiButton> bins = menuItems(column);
                assertEquals(2, bins.size());
                assertEquals(bins.get(0).getText().toString(), bins.get(1).getText().toString());
                click(hud, bins.get(1));
                show(hud, column, settled(firing));
                assertEquals(17, onSwing(() -> firing.attacker.getEquipmentNum(gun.getLinkedAmmo())));
                hud.capture("weapons-firing-display").dispose();
            });
        }
    }

    /**
     * The user's report of 2026-10-03: a terrain target kept "Hold fire". Over a real FiringDisplay, a click on the
     * board's light woods focuses them as it focuses a unit: the dashed "+" pill and the assign line name the hex, "+"
     * on the AC/20's row assigns the cannon there, and its attack takes the letter A.
     */
    @Test
    void aClickedWoodedHexIsAssignedAsAUnitIs() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            Coords woods = new Coords(7, 2);
            int cannon = GpuFiringFixture.eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
            GpuHudTestStage.run(hud -> {
                Column column = new Column(hud, firing.board.source);
                show(hud, column, settled(firing));
                assertTrue(((UiButton) column.find("weapons-alpha-strike")).isDisabled(), "no target yet");
                firing.board.source.fire().clickHex(woods, 0, -1);
                show(hud, column, settled(firing));
                Targetable hex = onSwing(firing.display::getTarget);
                assertEquals(List.of("+", hex.getDisplayName()), column.texts("weapons-pills"));
                assertEquals("Assign weapon \u2192 " + hex.getDisplayName(), column.line("weapons-assign"));
                click(hud, column.find("weapons-slot-" + cannon));
                show(hud, column, settled(firing));
                assertEquals(List.of("AC/20 RT@" + hex.getId()), GpuFiringFixture.queue(firing));
                assertEquals(List.of("A", hex.getDisplayName()), column.texts("weapons-pills"));
                click(hud, column.find("weapons-alpha-strike"));
                GpuBoardSource.Frame assigned = settled(firing);
                show(hud, column, assigned);
                assertEquals(5, assigned.panels().fire().attacks().size());
                assertTrue(assigned.panels().fire().attacks().stream()
                      .allMatch(attack -> attack.target().equals(TargetKey.of(hex))));
                assertTrue(((UiButton) column.find("weapons-alpha-strike")).isDisabled(),
                      "all available weapons already assigned");
            });
        }
    }

    @Test
    void draggingWeaponsSavesTheirOrderWithoutSelectingOrAssigningThem() throws Exception {
        try (GpuFiringFixture firing = GpuFiringFixture.firing()) {
            int cannon = GpuFiringFixture.eqNum(firing, "AC/20", Mek.LOC_RIGHT_TORSO);
            firing.board.source.fire().assign(cannon, TargetKey.unit(firing.ahead.getId()));
            GpuBoardSource.Frame initial = settled(firing);
            List<Integer> before = initial.panels().fire().weapons().stream()
                  .map(GpuFireOrders.WeaponRow::eqNum).toList();
            List<Integer> expected = new ArrayList<>(before);
            expected.add(2, expected.removeFirst());
            var attacks = onSwing(firing.display::getAttacks);
            GpuHudTestStage.run(hud -> {
                Column column = new Column(hud, firing.board.source);
                show(hud, column, initial);

                // Escape cancels the drag before disarming or deselecting a weapon.
                Vector2 end = dragWeapon(hud, column, before.getFirst(), before.get(2));
                assertTrue(column.weapons.cancelDrag());
                hud.stage.touchUp((int) end.x, (int) end.y, 0, Input.Buttons.LEFT);
                UiTestStage.settle(hud.stage);
                show(hud, column, settled(firing));
                assertEquals(before, weaponOrder(firing));

                // Leaving the declaration while holding a row abandons it without posting a stale move.
                end = dragWeapon(hud, column, before.getFirst(), before.get(2));
                column.update(hud, initial.status(), GpuFireOrders.Snapshot.EMPTY);
                hud.stage.touchUp((int) end.x, (int) end.y, 0, Input.Buttons.LEFT);
                UiTestStage.settle(hud.stage);
                show(hud, column, settled(firing));
                assertEquals(before, weaponOrder(firing));

                // The overview hides this panel; a lifted row must leave the stage with it.
                end = dragWeapon(hud, column, before.getFirst(), before.get(2));
                column.state.overview = true;
                column.update(hud, initial.status(), initial.panels().fire());
                hud.stage.touchUp((int) end.x, (int) end.y, 0, Input.Buttons.LEFT);
                UiTestStage.settle(hud.stage);
                column.state.overview = false;
                show(hud, column, settled(firing));
                assertEquals(before, weaponOrder(firing));

                end = dragWeapon(hud, column, before.getFirst(), before.get(2));
                // Fresh target/to-hit data must not rebuild a row out from under the pointer.
                firing.board.source.fire().focusTarget(TargetKey.unit(firing.left.getId()));
                show(hud, column, settled(firing));
                hud.stage.touchUp((int) end.x, (int) end.y, 0, Input.Buttons.LEFT);
                UiTestStage.settle(hud.stage);
                GpuBoardSource.Frame ordered = settled(firing);
                show(hud, column, ordered);
                assertEquals(expected, weaponOrder(firing));
                assertEquals(expected, ordered.panels().fire().weapons().stream()
                      .map(GpuFireOrders.WeaponRow::eqNum).toList());
                assertTrue(onSwing(() -> firing.attacker.getWeaponSortOrder().isCustom()));
                assertEquals(initial.panels().fire().selectedWeapon(), ordered.panels().fire().selectedWeapon());
                assertEquals(-1, column.state.armedWeapon, "Dragging a name must not click-to-arm it");
                assertEquals(attacks, onSwing(firing.display::getAttacks), "List order does not reorder attacks");
                for (int index = 1; index < 3; index++) {
                    assertTrue(GpuHudTestStage.bounds(column.find("weapons-row-" + expected.get(index - 1))).y
                          > GpuHudTestStage.bounds(column.find("weapons-row-" + expected.get(index))).y);
                }
                hud.capture("weapons-custom-order").dispose();

                // Rebuilding the panel and refreshing the frame both read back the unit's saved order.
                column.update(hud, initial.status(), GpuFireOrders.Snapshot.EMPTY);
                show(hud, column, settled(firing));
                assertEquals(expected, weaponOrder(firing));
            });
            onSwing(() -> {
                firing.display.ready();
                verify(firing.client).sendEntityWeaponOrderUpdate(firing.attacker);
                return null;
            });
        }
    }

    private static List<Integer> weaponOrder(GpuFiringFixture firing) throws Exception {
        return onSwing(() -> WeaponDisplayData.listedWeapons(firing.attacker).stream()
              .map(firing.attacker::getEquipmentNum).toList());
    }

    /** Drags a weapon by its name onto another row, leaving the pointer held for refresh/cancel checks. */
    private static Vector2 dragWeapon(GpuHudTestStage hud, Column column, int weapon, int destination) {
        Actor name = column.find("weapons-name-" + weapon);
        reveal(hud, name);
        Vector2 start = hud.stage.stageToScreenCoordinates(
              name.localToStageCoordinates(new Vector2(name.getWidth() / 2, name.getHeight() - 8)));
        Actor target = column.find("weapons-row-" + destination);
        Vector2 end = hud.stage.stageToScreenCoordinates(
              target.localToStageCoordinates(new Vector2(target.getWidth() / 2, target.getHeight() / 2)));
        hud.stage.touchDown((int) start.x, (int) start.y, 0, Input.Buttons.LEFT);
        for (int step = 1; step <= 10; step++) {
            hud.stage.touchDragged((int) (start.x + (end.x - start.x) * step / 10),
                  (int) (start.y + (end.y - start.y) * step / 10), 0);
        }
        return end;
    }

    // ------------------------------------------------------------------ fixtures

    /** The local declaration of shot 05, the Atlas acting in round 3. */
    private static GpuBattleStatus.Snapshot firing() {
        return status(GamePhase.FIRING, true, ATLAS);
    }

    private static GpuBattleStatus.Snapshot status(GamePhase phase, boolean myTurn, int actor) {
        return new GpuBattleStatus.Snapshot(MOCK.round(), phase, myTurn, MOCK.localPlayerId(), actor, MOCK.turns(),
              0, MOCK.units(), List.of(), false);
    }

    /**
     * Shot 05's orders: five attacks on A (Timber Wolf, primary, focused) and B (BattleMaster); the AC/20 selected
     * with its solution; heat 0 to 24, 4 at the end.
     */
    static GpuFireOrders.Snapshot shot05() {
        List<GpuFireOrders.Attack> attacks = List.of(attack(AC20, TIMBER_WOLF, "AC/20", "RT", "", 7, 58.3),
              attack(SRM, TIMBER_WOLF, "SRM 6", "LT", "", 7, 58.3),
              attack(LASER_LA, TIMBER_WOLF, "Medium Laser", "LA", "", 7, 58.3),
              attack(LASER_RA, TIMBER_WOLF, "Medium Laser", "RA", "", 7, 58.3),
              attack(LRM, BATTLEMASTER, "LRM 20", "LT", "", 8, 41.7));
        List<GpuFireOrders.Target> targets = List.of(target(TIMBER_WOLF, 'A', "Timber Wolf", true, 0),
              target(BATTLEMASTER, 'B', "BattleMaster", false, 1));
        return orders(weapons(TIMBER_WOLF, 7, 58.3), targets, attacks, solution(TIMBER_WOLF, 5, List.of(
              new GpuFireOrders.Modifier("Gunnery (Hayes)", 3), new GpuFireOrders.Modifier("Attacker walk", 1),
              new GpuFireOrders.Modifier("Target moved 3 hexes", 1),
              new GpuFireOrders.Modifier("Medium range (5)", 2)), 7, 58.3));
    }

    /** Shot 06: the AC/20 moved to C, the King Crab, at 9+; three targets. */
    private static GpuFireOrders.Snapshot shot06() {
        List<GpuFireOrders.Attack> attacks = List.of(attack(AC20, KING_CRAB, "AC/20", "RT", "", 9, 27.8),
              attack(SRM, TIMBER_WOLF, "SRM 6", "LT", "", 7, 58.3),
              attack(LASER_LA, TIMBER_WOLF, "Medium Laser", "LA", "", 7, 58.3),
              attack(LASER_RA, TIMBER_WOLF, "Medium Laser", "RA", "", 7, 58.3),
              attack(LRM, BATTLEMASTER, "LRM 20", "LT", "", 8, 41.7));
        List<GpuFireOrders.Target> targets = List.of(target(TIMBER_WOLF, 'A', "Timber Wolf", true, 0),
              target(BATTLEMASTER, 'B', "BattleMaster", false, 1), target(KING_CRAB, 'C', "King Crab", false, 1));
        List<GpuFireOrders.WeaponRow> weapons = new ArrayList<>(weapons(TIMBER_WOLF, 7, 58.3));
        weapons.set(0, weapon(AC20, "AC/20", "RT", "20", 7, List.of(bin(11, "[RT] AC/20  (8)"),
              bin(12, "[RT] AC/20 Armor-Piercing  (4)")), 8, KING_CRAB, 9, 27.8, ""));
        return orders(weapons, targets, attacks, solution(KING_CRAB, 7, List.of(
              new GpuFireOrders.Modifier("Gunnery (Hayes)", 3), new GpuFireOrders.Modifier("Attacker walk", 1),
              new GpuFireOrders.Modifier("Long range (7)", 4),
              new GpuFireOrders.Modifier("Secondary target (front)", 1)), 9, 27.8));
    }

    /**
     * The Atlas's seven weapons as the Unit Display lists them, each rolling {@code value} at {@code target} but
     * the LRM (8+ at the BattleMaster) and the first rear laser, which has no shot.
     */
    private static List<GpuFireOrders.WeaponRow> weapons(int target, int value, double odds) {
        return List.of(
              weapon(AC20, "AC/20", "RT", "20", 7, List.of(bin(11, "[RT] AC/20  (8)"),
                    bin(12, "[RT] AC/20 Armor-Piercing  (4)")), 8, target, value, odds, ""),
              weapon(LRM, "LRM 20", "LT", "1×20", 6, List.of(bin(13, "[LT] LRM 20  (11)")), 11, BATTLEMASTER,
                    8, 41.7, ""),
              weapon(SRM, "SRM 6", "LT", "2×6", 4, List.of(bin(14, "[LT] SRM 6  (9)"),
                    bin(15, "[LT] SRM 6 Inferno  (6)")), 9, target, value, odds, ""),
              weapon(LASER_LA, "Medium Laser", "LA", "5", 3, List.of(), -1, target, value, odds, ""),
              weapon(LASER_RA, "Medium Laser", "RA", "5", 3, List.of(), -1, target, value, odds, ""),
              weapon(REAR, "Medium Laser", "CT(R)", "5", 3, List.of(), -1, target, TargetRoll.IMPOSSIBLE, 0,
                    "rear arc only"),
              weapon(REAR_2, "Medium Laser", "CT(R)", "5", 3, List.of(), -1, target, value, odds, ""));
    }

    private static GpuFireOrders.WeaponRow weapon(int eqNum, String name, String location, String damage, int heat,
          List<GpuUnitRecord.AmmoChoice> ammo, int shots, int target, int value, double odds, String reason) {
        return new GpuFireOrders.WeaponRow(eqNum, name, location, "", damage, heat, "", ammo, ammo.isEmpty() ? -1 : 0,
              shots, TargetKey.unit(target), value, odds, reason, true, false);
    }

    /** A unit target in the front arc. */
    private static GpuFireOrders.Target target(int id, char letter, String name, boolean primary, int modifier) {
        return new GpuFireOrders.Target(TargetKey.unit(id), letter, name, primary, modifier, true, null);
    }

    /** One of the Atlas's own bins, by its number and Unit Display entry. */
    private static GpuUnitRecord.AmmoChoice bin(int eqNum, String label) {
        return new GpuUnitRecord.AmmoChoice(ATLAS, eqNum, label);
    }

    private static GpuFireOrders.Attack attack(int eqNum, int target, String weapon, String location, String ammo,
          int value, double odds) {
        return new GpuFireOrders.Attack(eqNum, TargetKey.unit(target), weapon, location, "", ammo,
              ammo.isEmpty() ? -1 : 8, value, odds, "");
    }

    private static GpuFireOrders.Solution solution(int target, int distance, List<GpuFireOrders.Modifier> modifiers,
          int value, double odds) {
        return new GpuFireOrders.Solution(AC20, TargetKey.unit(target), List.of(0, 3, 6, 9), "Forward arc", distance,
              modifiers, value, odds, "", null);
    }

    private static GpuFireOrders.Snapshot orders(List<GpuFireOrders.WeaponRow> weapons,
          List<GpuFireOrders.Target> targets, List<GpuFireOrders.Attack> attacks, GpuFireOrders.Solution solution) {
        return new GpuFireOrders.Snapshot(true, true, ATLAS,
              new GpuFireOrders.Focus(TargetKey.unit(TIMBER_WOLF), "Timber Wolf", null), AC20, weapons, targets,
              attacks, 0, true, true, "", new GpuFireOrders.Heat(0, 24, "20", 4, "", TICKS, 31), solution, null,
              List.of(), null, Map.of(), 5, 0, null);
    }

    /** The orders with one target, the focus too, named as MegaMek names it. */
    private static GpuFireOrders.Snapshot named(GpuFireOrders.Snapshot fire, int id, String name) {
        TargetKey key = TargetKey.unit(id);
        List<GpuFireOrders.Target> targets = fire.targets().stream().map(target -> !target.key().equals(key) ? target
              : new GpuFireOrders.Target(key, target.letter(), name, target.primary(), target.secondaryModifier(),
                    target.frontArc(), null)).toList();
        GpuFireOrders.Focus focus = fire.focus().key().equals(key) ? new GpuFireOrders.Focus(key, name, null)
              : fire.focus();
        return new GpuFireOrders.Snapshot(true, true, fire.actorId(), focus, fire.selectedWeapon(),
              fire.weapons(), targets, fire.attacks(), 0, true, true, "", fire.heat(), fire.solution(), null,
              List.of(), null, Map.of(), 5, 0, null);
    }

    /** The orders with another weapon selected (its solution left as it was). */
    private static GpuFireOrders.Snapshot selected(GpuFireOrders.Snapshot fire, int eqNum) {
        return new GpuFireOrders.Snapshot(true, true, fire.actorId(), fire.focus(), eqNum, fire.weapons(),
              fire.targets(), fire.attacks(), 0, true, true, "", fire.heat(), fire.solution(), null, List.of(), null,
              Map.of(), 5, 0, null);
    }

    /**
     * The orders with another unit focused, under its presented name; the weapons that roll at the focus roll at it.
     */
    private static GpuFireOrders.Snapshot focused(GpuFireOrders.Snapshot fire, int focus) {
        TargetKey key = TargetKey.unit(focus);
        List<GpuFireOrders.WeaponRow> weapons = fire.weapons().stream().map(row -> fire.attacks().stream()
              .anyMatch(attack -> attack.eqNum() == row.eqNum()) ? row : new GpuFireOrders.WeaponRow(row.eqNum(),
              row.name(), row.location(), row.kind(), row.damage(), row.heat(), row.mode(), row.ammo(),
              row.loadedAmmo(), row.shots(), key, row.value(), row.odds(), row.reason(), row.usable(),
              row.locationDestroyed())).toList();
        String name = GpuHudFixtures.status().units().stream().filter(unit -> unit.id() == focus).findFirst()
              .orElseThrow().name();
        return new GpuFireOrders.Snapshot(true, true, fire.actorId(), new GpuFireOrders.Focus(key, name, null),
              fire.selectedWeapon(), weapons, fire.targets(), fire.attacks(), 0, true, true, "", fire.heat(),
              fire.solution(), null, List.of(), null, Map.of(), 5, 0, null);
    }

    private static GpuBoardSource.Frame frame(GpuBattleStatus.Snapshot status, GpuFireOrders.Snapshot fire) {
        return GpuHudFixtures.frame(status, GpuHudFixtures.panels(GpuMovePlan.Snapshot.EMPTY, fire,
              GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY));
    }

    // ------------------------------------------------------------------ the real source

    /** The real source's frame once its capture saw the last command and the events it asked for ran. */
    static GpuBoardSource.Frame settled(GpuFiringFixture firing) throws Exception {
        GpuBoardSource source = firing.board.source;
        for (int pass = 0; pass < 3; pass++) {
            onSwing(() -> {
                source.refresh();
                return null;
            });
            onSwing(() -> null);
        }
        return onSwing(() -> {
            source.refresh();
            return source.takeFrame();
        });
    }

    /** The real frame's status and orders in the column at shot 05's place. */
    private static void show(GpuHudTestStage hud, Column column, GpuBoardSource.Frame frame) {
        column.update(hud, frame.status(), frame.panels().fire()).place(hud, COLUMN_X, COLUMN_TOP, 310, -1);
        hud.draw();
    }

    // ------------------------------------------------------------------ reading and pressing

    private static Container<Actor> slot(GpuHudTestStage hud, Actor actor) {
        // As GpuHud's panel slots: top-aligned, filling the width; the panel takes the presses on its area.
        actor.setTouchable(Touchable.enabled);
        Container<Actor> slot = new Container<>(actor).top().fillX();
        slot.setTouchable(Touchable.childrenOnly);
        hud.window.addActor(slot);
        return slot;
    }

    private static void set(GpuHudTestStage hud, Container<Actor> slot, float x, float top, float width,
          float height) {
        slot.setBounds(x, hud.height() - top - Math.max(0, height), width, Math.max(0, height));
    }

    private static void collect(Actor actor, List<String> texts) {
        if (!actor.isVisible()) {
            return;
        }
        if (actor instanceof Label label) {
            if (!label.getText().isEmpty()) {
                texts.add(label.getText().toString());
            }
        } else if (actor instanceof Group group) {
            group.getChildren().forEach(child -> collect(child, texts));
        }
    }

    /** The text of the actor's hud tooltip. */
    private static String tooltip(Actor actor) {
        for (EventListener listener : actor.getListeners()) {
            if (listener instanceof TextTooltip tip) {
                return tip.getActor().getText().toString();
            }
        }
        return null;
    }

    private static boolean hasIcon(Actor actor) {
        return actor instanceof Group group && group.getChildren().select(UiKit.Icon.class::isInstance).iterator()
              .hasNext();
    }

    /** Scrolls the list that holds the actor until it shows, as a player does before clicking it. */
    private static void reveal(GpuHudTestStage hud, Actor actor) {
        ScrollPane scroll = actor.firstAscendant(ScrollPane.class);
        if (scroll != null && scroll != actor) {
            // ScrollPane.scrollTo measures the rectangle's y at its top edge.
            Vector2 corner = actor.localToActorCoordinates(scroll.getActor(), new Vector2(0, actor.getHeight()));
            scroll.scrollTo(corner.x, corner.y, actor.getWidth(), actor.getHeight());
            scroll.updateVisualScroll();
            hud.draw();
        }
    }

    /** Whether the row lies inside the list's visible part. */
    private static boolean visible(ScrollPane scroll, Actor row) {
        Rectangle view = GpuHudTestStage.bounds(scroll);
        Rectangle area = GpuHudTestStage.bounds(row);
        return area.y >= view.y - .5f && area.y + area.height <= view.y + view.height + .5f;
    }

    /** The items of the context menu's open list. */
    private static List<UiButton> menuItems(Column column) {
        List<UiButton> items = new ArrayList<>();
        UiMenuList list = findList(column.popover());
        assertNotNull(list, "no open list");
        list.getChildren().forEach(child -> {
            if (child instanceof UiButton item) {
                items.add(item);
            }
        });
        return items;
    }

    private static UiMenuList findList(Actor actor) {
        if (actor instanceof UiMenuList list) {
            return list;
        } else if (actor instanceof Group group) {
            for (Actor child : group.getChildren()) {
                UiMenuList found = findList(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void click(GpuHudTestStage hud, Actor actor) {
        press(hud, actor, Input.Buttons.LEFT);
    }

    /**
     * A press and release of the button at the actor's centre through the stage, as the board view hands it on, once
     * its list shows it.
     */
    private static void press(GpuHudTestStage hud, Actor actor, int button) {
        assertNotNull(actor);
        reveal(hud, actor);
        Vector2 point = hud.stage.stageToScreenCoordinates(
              actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
        hud.stage.touchDown((int) point.x, (int) point.y, 0, button);
        hud.stage.touchUp((int) point.x, (int) point.y, 0, button);
    }
}
