/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.function.IntConsumer;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;

/**
 * The board chip that deployment ({@link GpuDeployConfirm}) and movement ({@link GpuMoveConfirm}) stand on the unit
 * they act on, so both read and stack the same way. From the unit up: the unit's height row ({@link HeightRow}: lower,
 * the height, higher), the chip's buttons, then the unit's setup tag ({@link GpuSetupTag}). The height sits on the unit
 * because it says where the unit stands; the buttons and the tag they send stay together above it. The column keeps
 * inside the window as one piece, so its order never changes. A unit whose facing matters gets turn arrows at its sides
 * ({@link TurnArrows}), below the column.
 */
final class GpuBoardChip {
    /** The column's distance above the unit, and from the window's edges. */
    static final float LIFT = 4;
    static final float MARGIN = 8;
    /** The buttons' height (and the square buttons' width), and the gap between buttons and rows. */
    static final float BUTTON = 28;
    static final float GAP = 4;
    /** The height read-out's least width, and so the least width of a row: lower, the height, higher. */
    static final float READOUT = 64;
    static final float MIN_WIDTH = READOUT + 2 * (BUTTON + GAP);
    /** The room the height row takes under the buttons. */
    static final float HEIGHT_ROOM = BUTTON + GAP;

    private GpuBoardChip() {
    }

    /** Steps a height: {@code step} up or down, {@code wrap} for a click on the read-out, which cycles. */
    @FunctionalInterface
    interface Stepper {
        void step(int step, boolean wrap);
    }

    /** The unit's height row: lower, the height (a step button, so it reads as one control with its arrows), higher. */
    static final class HeightRow extends Table {
        private final UiButton lower;
        private final UiButton readout;
        private final UiButton higher;
        private final Label lowerTip;
        private final Label readoutTip;
        private final Label higherTip;
        private final Cell<UiButton> readoutCell;

        /** A row named {@code name}, its buttons {@code name}-down, {@code name}-readout and {@code name}-up. */
        HeightRow(UiKit ui, String name, Stepper stepper) {
            setName(name);
            lower = ui.button("hud-confirm-step", "chevron-down", null, null);
            lower.setName(name + "-down");
            lowerTip = ui.tip(lower).getActor();
            UiKit.onChange(lower, () -> stepper.step(-1, false));
            readout = ui.button("hud-confirm-step", null, "", null);
            readout.setName(name + "-readout");
            readoutTip = ui.tip(readout).getActor();
            UiKit.onChange(readout, () -> stepper.step(1, true));
            higher = ui.button("hud-confirm-step", "chevron-up", null, null);
            higher.setName(name + "-up");
            higherTip = ui.tip(higher).getActor();
            UiKit.onChange(higher, () -> stepper.step(1, false));
            add(lower).size(BUTTON);
            readoutCell = add(readout).minWidth(READOUT).height(BUTTON).padLeft(GAP);
            add(higher).size(BUTTON).padLeft(GAP);
            setVisible(false);
        }

        /** Shows {@code height} with its tips, the arrows enabled as given, the row as wide as {@code width}. */
        void show(String height, String lowerText, String readoutText, String higherText, boolean canLower,
              boolean canRaise, float width) {
            readout.setText(height);
            lowerTip.setText(lowerText);
            readoutTip.setText(readoutText);
            higherTip.setText(higherText);
            lower.setDisabled(!canLower);
            higher.setDisabled(!canRaise);
            float row = Math.max(MIN_WIDTH, width);
            readoutCell.width(row - 2 * (BUTTON + GAP));
            invalidate();
            setSize(row, getPrefHeight());
            setVisible(true);
        }
    }

    /**
     * Stacks the column centred on {@code x}, from {@code base} up (y up, in {@code parent}'s coordinates): the height
     * row when shown, the {@code buttons}, then the setup tag when shown (laid out by its collapsed height, so it opens
     * over what is above it and nothing moves). The column moves as one to keep inside the window.
     */
    static void stack(Actor parent, float x, float base, Actor heights, Actor buttons, GpuSetupTag tag) {
        boolean tall = heights != null && heights.isVisible();
        boolean tagged = tag != null && tag.isVisible();
        float total = (tall ? heights.getHeight() + GAP : 0) + buttons.getHeight()
              + (tagged ? GAP + tag.collapsedHeight() : 0);
        float ceiling = parent == null ? Float.MAX_VALUE : parent.getHeight() - MARGIN;
        float y = MathUtils.clamp(base, MARGIN, Math.max(MARGIN, ceiling - total));
        if (tall) {
            centre(parent, heights, x, y);
            y += heights.getHeight() + GAP;
        }
        centre(parent, buttons, x, y);
        y += buttons.getHeight() + GAP;
        if (tagged) {
            centre(parent, tag, x, y);
        }
    }

    /** The column's bottom: the height row when shown, else the buttons. */
    static Actor bottom(Actor heights, Actor buttons) {
        return heights != null && heights.isVisible() ? heights : buttons;
    }

    /**
     * The turn arrows at the unit's sides: left and right, one hexside each. They stand at the unit's middle, a least
     * reach from it so a small unit (zoomed out) doesn't crowd them, and never rise into the column above, at any zoom.
     * Their tips name the key and teach the drag that faces any hex.
     */
    static final class TurnArrows {
        /** The arrows' distance from the unit's sides, their least reach from its middle and their gap to the column. */
        private static final float SIDE = 6;
        private static final float MIN_REACH = 14;
        private static final float CLEARANCE = 6;
        /** A unit's middle, .55 up its screen rectangle, as the board labels'. */
        static final float MIDDLE = .55f;

        private final UiButton left;
        private final UiButton right;
        private final Label leftTip;
        private final Label rightTip;

        /** Arrows named {@code name}-left and {@code name}-right in {@code parent}; {@code turn} takes -1 or 1. */
        TurnArrows(UiKit ui, Group parent, String name, IntConsumer turn) {
            left = arrow(ui, parent, "twist-left", name + "-left", () -> turn.accept(-1));
            right = arrow(ui, parent, "twist-right", name + "-right", () -> turn.accept(1));
            leftTip = ui.tip(left).getActor();
            rightTip = ui.tip(right).getActor();
        }

        private static UiButton arrow(UiKit ui, Group parent, String icon, String name, Runnable action) {
            UiButton button = ui.button("hud-confirm-step", icon, null, null);
            button.setName(name);
            UiKit.onChange(button, action);
            button.setSize(BUTTON, BUTTON);
            button.setVisible(false);
            parent.addActor(button);
            return button;
        }

        void hide() {
            left.setVisible(false);
            right.setVisible(false);
        }

        /**
         * Shows the arrows with their tips, enabled as given, at the unit's {@code middle} beside its screen
         * {@code width}, below the column's {@code lowest} row, kept inside {@code parent}.
         */
        void show(String leftText, String rightText, boolean enabled, Actor parent, Vector2 middle, float width,
              Actor lowest) {
            leftTip.setText(leftText);
            rightTip.setText(rightText);
            left.setDisabled(!enabled);
            right.setDisabled(!enabled);
            float reach = Math.max(width / 2, MIN_REACH) + SIDE;
            float y = Math.min(middle.y - BUTTON / 2, lowest.getY() - CLEARANCE - BUTTON);
            place(parent, left, middle.x - reach - BUTTON, y);
            place(parent, right, middle.x + reach, y);
            left.setVisible(true);
            right.setVisible(true);
        }

        private static void place(Actor parent, Actor actor, float x, float y) {
            float right = parent == null ? Float.MAX_VALUE : parent.getWidth() - MARGIN - actor.getWidth();
            float top = parent == null ? Float.MAX_VALUE : parent.getHeight() - MARGIN - actor.getHeight();
            actor.setPosition(Math.round(MathUtils.clamp(x, MARGIN, Math.max(MARGIN, right))),
                  Math.round(MathUtils.clamp(y, MARGIN, Math.max(MARGIN, top))));
        }
    }

    private static void centre(Actor parent, Actor actor, float x, float y) {
        float right = parent == null ? Float.MAX_VALUE : parent.getWidth() - MARGIN - actor.getWidth();
        actor.setPosition(Math.round(MathUtils.clamp(x - actor.getWidth() / 2, MARGIN, Math.max(MARGIN, right))),
              Math.round(y));
    }
}
