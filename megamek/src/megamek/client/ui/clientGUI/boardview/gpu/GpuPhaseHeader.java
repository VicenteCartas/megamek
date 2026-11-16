/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;
import megamek.client.ui.Messages;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.enums.GamePhase;

/**
 * The phase header, top centre over the board as subtitles, in every phase: the phase, the turn order
 * as one block per turn in its side's colour (the current one framed in white), and whose turn it is. Each text line
 * sits on a faint rounded plate that hugs it. Each
 * block is {@link #BLOCK_MIN} to {@link #BLOCK_MAX} units wide; an order longer than its room scrolls to keep the
 * current block in view and fades out at a side where it goes on. Presses pass through to the board.
 */
final class GpuPhaseHeader implements GpuHud.Component {
    /** The phase name's size (#phase .name), and at W <= 1350. */
    private static final float NAME_SIZE = 23;
    private static final float NARROW_NAME_SIZE = 20;
    /** The turn order's blocks: their least and greatest width, the room between them and their height. */
    static final float BLOCK_MIN = 8;
    static final float BLOCK_MAX = 32;
    private static final float BLOCK_GAP = 3;
    private static final float BLOCK_HEIGHT = 6;
    /** The current block's white frame and its shadow outside the block, which the row keeps inside its ends. */
    private static final float FRAME = 3;
    private static final float BLOCK_ALPHA = .85f;
    private static final float DONE_ALPHA = .3f;
    /** How far the order fades out at a side where it goes on past its room. */
    private static final float FADE = 36;
    /** How quickly the order scrolls to its new place, per second. */
    private static final float GLIDE = 8;
    /** The texts' and the blocks' shadow over the board (the hint line's 0 1px 3px rgba(0,0,0,.8)). */
    private static final Color SHADOW = new Color(0, 0, 0, .8f);

    private final UiKit ui;
    private final Table root = new Table();
    private final Label name;
    private final Label who;
    private final Container<Label> nameLine;
    private final Container<Label> whoLine;
    private final Order order = new Order();
    private final float nameScale;
    private boolean narrow;
    private GamePhase shownPhase;

    GpuPhaseHeader(GpuHudKit kit) {
        ui = kit.ui;
        root.setName("phase-header");
        // Text over the board: presses on it and around it reach the board.
        root.setTouchable(Touchable.disabled);
        name = label("hud-phase", NAME_SIZE, UiTheme.TEXT);
        name.setName("phase-name");
        nameScale = name.getFontScaleX();
        who = label("hud-button", 12, Color.WHITE);
        who.setName("phase-who");
        order.setName("phase-order");
        // A name or who line longer than the header's room ends in an ellipsis.
        name.setEllipsis(true);
        who.setEllipsis(true);
        // Each line sits on its own subtitle plate, hugging the text, so it reads over snow as over night.
        nameLine = plate(name, 24, 2, 12);
        whoLine = plate(who, 14, 2, 10);
        root.add(nameLine).minWidth(0).row();
        // The order keeps its row while it has no turns, so the lines never move.
        root.add(order).growX().height(BLOCK_HEIGHT).padTop(7).padBottom(7).row();
        root.add(whoLine).minWidth(0);
    }

    /** A line's plate: the label at {@code height}, padded, on the rounded subtitle backing. */
    private Container<Label> plate(Label label, float height, float vertical, float horizontal) {
        Container<Label> plate = new Container<>(label);
        plate.setBackground(ui.skin.getDrawable("subtitle"));
        plate.height(height).pad(vertical, horizontal, vertical, horizontal);
        return plate;
    }

    /** A label drawn over its shadow, one unit lower. */
    private Label label(String font, float size, Color color) {
        Label label = new Label("", new Label.LabelStyle(ui.skin.getFont(font), color)) {
            @Override
            public void draw(Batch batch, float parentAlpha) {
                Color own = getColor();
                float r = own.r, g = own.g, b = own.b, a = own.a;
                moveBy(0, -1);
                setColor(SHADOW.r, SHADOW.g, SHADOW.b, SHADOW.a * a);
                super.draw(batch, parentAlpha);
                moveBy(0, 1);
                setColor(r, g, b, a);
                super.draw(batch, parentAlpha);
            }
        };
        UiKit.size(label, font, size);
        return label;
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        GamePhase phase = status.phase();
        if (narrow != inputs.metrics().narrow()) {
            narrow = inputs.metrics().narrow();
            name.setFontScale(nameScale * (narrow ? NARROW_NAME_SIZE / NAME_SIZE : 1));
        }
        name.setText(UiTheme.upper(name(status)));
        GpuBattleStatus.Slot current = status.turnIndex() >= 0 && status.turnIndex() < status.turns().size()
              ? status.turns().get(status.turnIndex()) : null;
        who.setText(UiTheme.upper(who(status, current, inputs)));
        // An empty line keeps its room but shows no plate.
        nameLine.setVisible(name.getText().notEmpty());
        whoLine.setVisible(who.getText().notEmpty());
        // Under double blind the server reports no turn order, and the header shows none.
        order.show(status.turnOrderHidden() ? List.of() : status.turns(), status.turnIndex(), phase != shownPhase);
        shownPhase = phase;
    }

    /** The mock's phase names (B2); MegaMek's own name for every other phase. */
    private static String name(GpuBattleStatus.Snapshot status) {
        GamePhase phase = status.phase();
        String key = switch (phase) {
            case INITIATIVE, INITIATIVE_REPORT -> status.round() > 0 ? "initiative" : "deploymentOrder";
            case MOVEMENT -> "movement";
            case FIRING -> "firing";
            case FIRING_REPORT -> "firingReport";
            case PHYSICAL -> "physical";
            case PHYSICAL_REPORT -> "physicalReport";
            case END, END_REPORT -> "end";
            default -> null;
        };
        return key == null ? phase.localizedName() : Messages.getString("GpuBoard.hud.phase." + key);
    }

    /**
     * Whose turn it is, and its color: "Your turn" in mint, the player's ("Princess turn") or "Opponent turn" for a
     * turn without a player's name in the side's color. The initiative, the end and the combat playbacks say what they
     * show; a phase without a turn shows the first line of the phase display's status.
     */
    private String who(GpuBattleStatus.Snapshot status, GpuBattleStatus.Slot current, GpuHud.Inputs inputs) {
        GamePhase phase = status.phase();
        boolean theirs = !status.myTurn() && current != null && !phase.isInitiative() && !phase.isInitiativeReport()
              && !phase.isReport() && !phase.isEnd();
        who.setColor(theirs ? turnColor(current) : UiTheme.MINT);
        if (phase.isInitiative() || phase.isInitiativeReport()) {
            return Messages.getString("GpuBoard.hud.phase.turnOrder");
        } else if (phase.isEnd() || phase.isEndReport()) {
            return Messages.getString("GpuBoard.hud.phase.roundComplete");
        } else if (phase == GamePhase.FIRING_REPORT || phase == GamePhase.TARGETING_REPORT
              || phase == GamePhase.OFFBOARD_REPORT || phase == GamePhase.PHYSICAL_REPORT) {
            return Messages.getString(inputs.view().playbackBusy() ? "GpuBoard.hud.phase.combatResolution"
                  : "GpuBoard.hud.phase.resolved");
        } else if (status.myTurn()) {
            return Messages.getString("GpuBoard.hud.phase.yourTurn");
        } else if (current != null) {
            return current.playerName().isEmpty() ? Messages.getString("GpuBoard.hud.phase.opponentTurn")
                  : Messages.getString("GpuBoard.hud.phase.playerTurn", current.playerName());
        }
        return inputs.frame().panels().phase().status().lines().findFirst().orElse("");
    }

    /**
     * The color of a side in the turn order: mint for the local player, ink2 for an ally, coral for an enemy (plan
     * B4).
     */
    static Color sideColor(GpuBattleStatus.Side side) {
        return switch (side) {
            case OWN -> UiTheme.MINT;
            case ALLY -> UiTheme.ACCENT;
            case ENEMY -> UiTheme.CORAL;
        };
    }

    /** The color of one turn; a turn without a player is dim. */
    static Color turnColor(GpuBattleStatus.Slot slot) {
        return slot.playerName().isEmpty() ? UiTheme.DISABLED : sideColor(slot.side());
    }

    @Override
    public void dispose() { }

    /** A block's width for {@code count} turns in a row {@code width} wide: their share, within the least and most. */
    static float blockWidth(int count, float width) {
        return MathUtils.clamp((width - BLOCK_GAP * (count - 1)) / count, BLOCK_MIN, BLOCK_MAX);
    }

    /**
     * How far a row {@code width} wide scrolls to centre turn {@code current} of {@code count}, as far as the row's
     * ends allow; 0 while every block fits.
     */
    static float scroll(int count, int current, float width) {
        float block = blockWidth(count, width);
        float total = count * (block + BLOCK_GAP) - BLOCK_GAP;
        if (total <= width) {
            return 0;
        }
        float centre = MathUtils.clamp(current, 0, count - 1) * (block + BLOCK_GAP) + block / 2;
        return MathUtils.clamp(centre - width / 2, 0, total - width);
    }

    /**
     * The turn order: one block per turn in its side's color, faded after its turn, the current one outlined in white
     * (#phase .ticks). The blocks share the row's width between {@link #BLOCK_MIN} and {@link #BLOCK_MAX} each,
     * centred; where even the least width does not fit, the row scrolls to centre the current block, as far as its
     * ends allow, and fades out over {@link #FADE} units at a side where more blocks lie beyond it.
     */
    private final class Order extends Widget {
        private List<GpuBattleStatus.Slot> turns = List.of();
        private int current;
        /** How far the row is scrolled, whether it jumps there next instead of gliding, and the room it was for. */
        private float offset;
        private boolean snap = true;
        private float laidWidth = -1;
        /** Whether blocks lie beyond the row's left and right ends, as last drawn. */
        private boolean moreLeft;
        private boolean moreRight;

        void show(List<GpuBattleStatus.Slot> turns, int current, boolean newPhase) {
            if (newPhase || turns.size() != this.turns.size()) {
                snap = true;
                invalidateHierarchy();
            }
            this.turns = turns;
            this.current = current;
        }

        /** As wide as all blocks at their greatest width; the header gives it less where the window has less. */
        @Override
        public float getPrefWidth() {
            return turns.isEmpty() ? 0 : turns.size() * (BLOCK_MAX + BLOCK_GAP) - BLOCK_GAP + 2 * FRAME;
        }

        /** Any width: a long order scrolls. */
        @Override
        public float getMinWidth() {
            return 0;
        }

        @Override
        public float getPrefHeight() {
            return BLOCK_HEIGHT;
        }

        @Override
        public void act(float delta) {
            super.act(delta);
            scrollBy(delta);
        }

        /**
         * Moves the scroll {@code delta} seconds towards its target: at once for a new phase, a new width or a jump of
         * more than a quarter of the row, else gliding. Drawing calls it too, without time, for a frame that had no act.
         */
        private void scrollBy(float delta) {
            if (turns.isEmpty()) {
                return;
            }
            float room = getWidth() - 2 * FRAME;
            float target = scroll(turns.size(), current, room);
            if (snap || laidWidth != room || Math.abs(target - offset) > room / 4) {
                offset = target;
                snap = false;
                laidWidth = room;
            } else {
                offset += (target - offset) * Math.min(1, delta * GLIDE);
            }
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            int count = turns.size();
            if (count == 0) {
                return;
            }
            scrollBy(0);
            // The blocks lie inside the row's ends by the current frame's width: centred while all fit, else scrolled.
            float room = getWidth() - 2 * FRAME;
            float block = blockWidth(count, room);
            float total = count * (block + BLOCK_GAP) - BLOCK_GAP;
            float start = FRAME + (total <= room ? (room - total) / 2 : -offset);
            moreLeft = start < FRAME - .5f;
            moreRight = start + total > FRAME + room + .5f;
            float previous = batch.getPackedColor();
            float alpha = getColor().a * parentAlpha;
            int first = Math.max(0, (int) Math.floor(-start / (block + BLOCK_GAP)) - 1);
            for (int turn = first; turn < count; turn++) {
                float x = start + turn * (block + BLOCK_GAP);
                if (x - FRAME >= getWidth()) {
                    break;
                }
                Color color = turnColor(turns.get(turn));
                if (turn == current) {
                    // box-shadow: 0 0 0 2px #fff, with a shadow that keeps it apart from bright ground
                    rect(batch, SHADOW, alpha * .6f, x - FRAME, x + block + FRAME, -FRAME, BLOCK_HEIGHT + 2 * FRAME);
                    rect(batch, Color.WHITE, alpha, x - 2, x + block + 2, -2, BLOCK_HEIGHT + 4);
                    rect(batch, color, alpha, x, x + block, 0, BLOCK_HEIGHT);
                } else {
                    float shade = alpha * BLOCK_ALPHA * (turn < current ? DONE_ALPHA : 1);
                    rect(batch, SHADOW, shade * .6f, x - 1, x + block + 1, -1, BLOCK_HEIGHT + 2);
                    rect(batch, color, shade, x, x + block, 0, BLOCK_HEIGHT);
                }
            }
            batch.setPackedColor(previous);
        }

        /**
         * Fills from {@code left} to {@code right} of the row (clipped to it), {@code bottom} above its foot: whole
         * where no fade reaches, else in one-unit slices, each as faded as its place.
         */
        private void rect(Batch batch, Color color, float alpha, float left, float right, float bottom,
              float height) {
            left = Math.max(0, left);
            right = Math.min(getWidth(), right);
            boolean fades = moreLeft && left < FADE || moreRight && right > getWidth() - FADE;
            float step = fades ? 1 : right - left;
            for (float slice = left; slice < right; slice += step) {
                float width = Math.min(step, right - slice);
                float middle = slice + width / 2;
                float fade = Math.min(moreLeft ? middle / FADE : 1, moreRight ? (getWidth() - middle) / FADE : 1);
                ui.fill(batch, color, alpha * MathUtils.clamp(fade, 0, 1), getX() + slice, getY() + bottom, width,
                      height);
            }
        }
    }
}
