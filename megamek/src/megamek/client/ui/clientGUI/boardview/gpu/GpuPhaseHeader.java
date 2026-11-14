/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;
import java.util.Locale;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;
import megamek.client.ui.Messages;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.enums.GamePhase;

/**
 * Phase header, top left: round, phase ring, phase name, who line and the movement activation ribbon, and the
 * playback speeds in the frame's top right corner, one pill of sections from 0.5x to Instant, while the playback runs
 * or another player acts (to hurry their moves); the local player's own turn hides them.
 */
final class GpuPhaseHeader implements GpuHud.Component {
    /** The phase name's size (#phase .name), and at W <= 1350. */
    private static final float NAME_SIZE = 23;
    private static final float NARROW_NAME_SIZE = 20;
    /** The ribbon shows at most this many activations, starting this many before the current one. */
    private static final int RIBBON_SLOTS = 48;
    private static final int RIBBON_BEFORE = 8;
    private static final float RIBBON_GAP = 3;
    private static final float RIBBON_HEIGHT = 5;
    private static final float RIBBON_ALPHA = .75f;
    private static final float DONE_ALPHA = .3f;
    /** The deployment strip's phase name, the room between its parts and the runs of the order it shows. */
    private static final float STRIP_NAME_SIZE = 17;
    private static final float STRIP_GAP = 12;
    private static final int STRIP_RUNS = 4;

    private final Table root;
    private final UiKit ui;
    private final GpuHudState state;
    /** The playback's speeds, Instant last as "I". */
    private static final List<UnitMotion.Speed> SPEEDS = List.of(UnitMotion.Speed.HALF, UnitMotion.Speed.NORMAL,
          UnitMotion.Speed.DOUBLE, UnitMotion.Speed.QUADRUPLE, UnitMotion.Speed.INSTANT);
    /**
     * The speeds' distance from the frame's top and right edges, in its top right corner above the text's lines,
     * and the room the round keeps from them.
     */
    private static final float SPEEDS_TOP = 5;
    private static final float SPEEDS_RIGHT = 6;
    private static final float ROUND_GAP = 8;
    private final Ring ring = new Ring();
    private final UiKit.Segmented speeds;
    /** The round line, which the speeds keep clear of. */
    private final Table top = new Table();
    /** The round and the line's width the round text was chosen for. */
    private int shownRound = -1;
    private float shownWidth = -1;
    private boolean shownSpeeds = true;
    private final Label round;
    private final Label name;
    private final Label who;
    private final Ribbon ribbon = new Ribbon();
    private final Cell<Label> nameCell;
    private final Cell<Actor> ribbonCell;
    private final float nameScale;
    private boolean narrow;
    /** The ring, texts and ribbon of every phase but deployment. */
    private final Table full = new Table();
    /** Deployment's one line: the phase, whose turn it is and the coming turn order. */
    private final Table strip = new Table();
    private final Cell<Table> content;
    private final Label stripName;
    private final Label stripWho;
    private final Table order = new Table();
    private GpuTurnOrder.Upcoming shownOrder;
    private boolean slim;

    GpuPhaseHeader(GpuHudKit kit, GpuBoardSource source, GpuHudState state) {
        ui = kit.ui;
        this.state = state;
        root = ui.panel(new Table() {
            @Override
            public void layout() {
                super.layout();
                speeds.setSize(speeds.getPrefWidth(), speeds.getPrefHeight());
                speeds.setPosition(getWidth() - SPEEDS_RIGHT - speeds.getWidth(),
                      getHeight() - SPEEDS_TOP - speeds.getHeight());
            }
        });
        root.setName("phase-header");
        // #phase padding 12 14, inside the 2-unit rails and the transparent side borders. The texts may run into
        // the right padding, as the prototype's grid column grows into it for a long phase name.
        root.pad(14, 16, 14, 2);
        round = ui.label("", "hud-caption", 12, UiTheme.MUTED);
        name = ui.label("", "hud-phase", NAME_SIZE, UiTheme.TEXT);
        nameScale = name.getFontScaleX();
        who = ui.label("", "hud-button", 12, Color.WHITE);
        name.setEllipsis(true);
        who.setEllipsis(true);
        Table text = new Table();
        text.defaults().left().growX().minWidth(0);
        // The CSS line boxes: 12 units at line-height normal, 23 (20 when narrow) at 1.05 and 12 again, 1 and 3
        // units apart; the name's glyphs sit a unit lower in its box than the browser's, so its gap moves below it.
        speeds = ui.segmented("hud-pill", false, SPEEDS.stream().map(GpuPhaseHeader::speedLabel)
              .toArray(String[]::new));
        for (int i = 0; i < SPEEDS.size(); i++) {
            UnitMotion.Speed speed = SPEEDS.get(i);
            UiButton button = speeds.buttons.get(i);
            button.setName("phase-speed-" + speed.name().toLowerCase(Locale.ROOT));
            button.pad(0, 5, 0, 5);
            UiKit.size(button.getLabel(), "hud-small", 10);
            UiKit.onChange(button, () -> state.history.speed(speed));
        }
        round.setName("phase-round");
        round.setEllipsis(true);
        top.add(round).growX().minWidth(0).left();
        text.add(top).height(14).row();
        nameCell = text.add(name).height(24);
        text.row();
        text.add(who).height(14).padTop(4);
        // A 46-unit ring in the grid's 48-unit column, 12 units before the text.
        full.add(ring).size(46).padRight(14);
        full.add(text).growX().minWidth(0);
        full.row();
        ribbonCell = full.add((Actor) null).colspan(2).growX().padRight(14);
        content = root.add(full).grow();
        root.addActor(speeds);

        stripName = ui.label("", "hud-phase", STRIP_NAME_SIZE, UiTheme.TEXT);
        stripWho = ui.label("", "hud-button", 12, UiTheme.MINT);
        stripWho.setName("phase-strip-who");
        order.setName("phase-strip-order");
        strip.add(stripName).height(20);
        strip.add(divider()).width(1).height(18).padLeft(STRIP_GAP).padRight(STRIP_GAP);
        strip.add(stripWho).height(16);
        strip.add(order).padLeft(STRIP_GAP);
    }

    /** The strip's thin rule between its parts. */
    private Actor divider() {
        Actor divider = new Actor() {
            @Override
            public void draw(Batch batch, float parentAlpha) {
                ui.fill(batch, UiTheme.LINE, getColor().a * parentAlpha, getX(), getY(), getWidth(), getHeight());
            }
        };
        divider.setTouchable(Touchable.disabled);
        return divider;
    }

    /** Whether the header shows as the deployment's one-line strip, which the HUD sizes to its content. */
    boolean slim() {
        return slim;
    }

    /** The strip's preferred width, the header's whole width while it is {@link #slim()}. */
    float stripWidth() {
        return root.getPadLeft() + root.getPadRight() + strip.getPrefWidth();
    }

    @Override
    public Actor actor() {
        return root;
    }

    /** A speed's section: its multiplier ("2×"), or "I" for Instant. */
    private static String speedLabel(UnitMotion.Speed speed) {
        return speed == UnitMotion.Speed.INSTANT ? Messages.getString("GpuBoard.hud.phase.instant")
              : Messages.getString("GpuBoard.hud.phase.speed", speed.rate / UnitMotion.Speed.NORMAL.rate);
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        GamePhase phase = status.phase();
        boolean deployment = phase.isDeployment();
        if (deployment != slim) {
            slim = deployment;
            content.setActor(slim ? strip : full);
            // The strip's last label ends the panel, which needs its right padding back.
            root.padRight(slim ? 16 : 2);
            speeds.setVisible(false);
        }
        if (slim) {
            updateStrip(status, inputs);
            return;
        }
        ring.progress = progress(phase) / 6f;
        // The speeds pace the other players' moves and the playback; on the local player's own turn they only crowd
        // the header.
        boolean speedsShown = !status.myTurn() || inputs.view().playbackBusy();
        speeds.setVisible(speedsShown);
        // No number before the first combat round (the start-of-game deployment and the setup phases before it); only
        // the number where the round line has no room for the word.
        if (status.round() != shownRound || top.getWidth() != shownWidth || speedsShown != shownSpeeds) {
            shownRound = status.round();
            shownWidth = top.getWidth();
            shownSpeeds = speedsShown;
            round.setText(status.round() > 0
                  ? UiTheme.upper(Messages.getString("GpuBoard.hud.phase.round", status.round())) : "");
            float room = shownWidth - (speedsShown ? speeds.getPrefWidth() + SPEEDS_RIGHT + ROUND_GAP : 0);
            if (status.round() > 0 && shownWidth > 0 && round.getPrefWidth() > room) {
                round.setText(Messages.getString("GpuBoard.hud.phase.roundShort", status.round()));
            }
        }
        if (narrow != inputs.metrics().narrow()) {
            narrow = inputs.metrics().narrow();
            name.setFontScale(nameScale * (narrow ? NARROW_NAME_SIZE / NAME_SIZE : 1));
            nameCell.height(narrow ? 21 : 24);
        }
        name.setText(UiTheme.upper(name(status)));
        speeds.select(SPEEDS.indexOf(state.history.speed()));
        GpuBattleStatus.Slot current = status.turnIndex() >= 0 && status.turnIndex() < status.turns().size()
              ? status.turns().get(status.turnIndex()) : null;
        // A turn without a player (UnloadStrandedTurn) is nobody's: it shows the status line, as turnColor dims it.
        boolean foe = !status.myTurn() && current != null && current.side() == GpuBattleStatus.Side.ENEMY
              && !current.playerName().isEmpty() && (phase.isMovement() || phase.isFiring() || phase.isPhysical());
        who.setText(UiTheme.upper(who(status, current, foe, inputs)));
        who.setColor(foe ? UiTheme.CORAL : UiTheme.MINT);
        // The activation ribbon: movement only, never under double blind (plan B4, D4).
        boolean ribbonShown = phase.isMovement() && !status.turnOrderHidden() && !status.turns().isEmpty();
        ribbon.turns = status.turns();
        ribbon.current = status.turnIndex();
        if ((ribbonCell.getActor() != null) != ribbonShown) {
            ribbonCell.setActor(ribbonShown ? ribbon : null).height(ribbonShown ? RIBBON_HEIGHT : 0)
                  .padTop(ribbonShown ? 11 : 0);
        }
    }

    /**
     * Deployment's one line: the phase, whose turn it is and the turns after it, in order ("Then Princess › You ›
     * Princess +11"), as {@link GpuTurnOrder} names them.
     */
    private void updateStrip(GpuBattleStatus.Snapshot status, GpuHud.Inputs inputs) {
        stripName.setText(UiTheme.upper(status.phase().localizedName()));
        GpuBattleStatus.Slot current = status.turnIndex() >= 0 && status.turnIndex() < status.turns().size()
              ? status.turns().get(status.turnIndex()) : null;
        String who;
        Color color = UiTheme.MINT;
        if (status.myTurn()) {
            who = Messages.getString("GpuBoard.hud.deploy.yourTurn");
        } else if (current != null && !current.playerName().isEmpty()) {
            who = Messages.getString("GpuBoard.hud.deploy.theirTurn", current.playerName());
            color = sideColor(current.side());
        } else {
            who = inputs.frame().panels().phase().status().lines().findFirst().orElse("");
        }
        stripWho.setText(UiTheme.upper(who));
        stripWho.setColor(color);
        GpuTurnOrder.Upcoming upcoming = status.turnOrderHidden() ? GpuTurnOrder.Upcoming.EMPTY
              : GpuTurnOrder.upcoming(status.turns(), status.turnIndex(), STRIP_RUNS);
        if (upcoming.equals(shownOrder)) {
            return;
        }
        shownOrder = upcoming;
        order.clearChildren();
        if (upcoming.next().isEmpty()) {
            return;
        }
        order.add(ui.label(UiTheme.upper(Messages.getString("GpuBoard.hud.deploy.order")), "hud-caption", 11,
              UiTheme.MUTED)).padRight(8);
        for (int i = 0; i < upcoming.next().size(); i++) {
            GpuTurnOrder.Run run = upcoming.next().get(i);
            if (i > 0) {
                order.add(ui.label("›", "hud-button", 12, UiTheme.MUTED)).padLeft(6).padRight(6);
            }
            Label label = ui.label(playerName(run.slot(), status) + (run.count() > 1 ? " ×" + run.count() : ""),
                  "hud-button", 12, turnColor(run.slot()));
            label.setName("phase-strip-run");
            order.add(label);
        }
        if (upcoming.more() > 0) {
            Label more = ui.label(Messages.getString("GpuBoard.hud.deploy.more", upcoming.more()), "hud-caption", 11,
                  UiTheme.MUTED);
            more.setName("phase-strip-more");
            order.add(more).padLeft(STRIP_GAP);
        }
    }

    /** The order's name for a turn's player: "You" for the local player. */
    private static String playerName(GpuBattleStatus.Slot slot, GpuBattleStatus.Snapshot status) {
        return slot.playerId() == status.localPlayerId() ? Messages.getString("GpuBoard.hud.initiative.you")
              : slot.playerName();
    }

    /**
     * The ring's sixths (rebuild plan B1): the mock's phase index, plus the two in-round phases its table does not
     * list (a pointblank shot during movement, infantry combat before the end phase). Every other phase is 0.
     */
    private static int progress(GamePhase phase) {
        return switch (phase) {
            case PREMOVEMENT, MOVEMENT, MOVEMENT_REPORT, POINTBLANK_SHOT -> 1;
            case PRE_FIRING, TARGETING, OFFBOARD, FIRING -> 2;
            case TARGETING_REPORT, OFFBOARD_REPORT, FIRING_REPORT -> 3;
            case PHYSICAL -> 4;
            case PHYSICAL_REPORT, PREEND_DECLARATIONS, INFANTRY_VS_INFANTRY_COMBAT -> 5;
            case END, END_REPORT, VICTORY -> 6;
            default -> 0;
        };
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
     * The who line (B3). In the turn-based phases the local turn names the acting unit (in FIRING the number of own
     * units still to declare) and an enemy turn names the identified unit of its turn, else its player; the playback
     * phases say whether the playback still runs. Everything else shows the first line of the phase display's status.
     */
    private String who(GpuBattleStatus.Snapshot status, GpuBattleStatus.Slot current, boolean foe,
          GpuHud.Inputs inputs) {
        GamePhase phase = status.phase();
        List<GpuBattleStatus.UnitStatus> units = state.presentedUnits();
        GpuBattleStatus.UnitStatus actor = state.presented(status.actorId());
        if (phase.isInitiative() || phase.isInitiativeReport()) {
            return Messages.getString("GpuBoard.hud.phase.turnOrder");
        } else if (phase.isEnd() || phase.isEndReport()) {
            return Messages.getString("GpuBoard.hud.phase.roundComplete");
        } else if (phase.isReport() && (progress(phase) == 3 || progress(phase) == 5)) {
            return Messages.getString(inputs.view().playbackBusy() ? "GpuBoard.hud.phase.combatResolution"
                  : "GpuBoard.hud.phase.resolved");
        } else if (status.myTurn() && phase.isFiring()) {
            long pending = units.stream()
                  .filter(unit -> unit.side() == GpuBattleStatus.Side.OWN && unit.pending()).count();
            return Messages.getString("GpuBoard.hud.phase.yourTurnPending", pending);
        } else if (status.myTurn() && (phase.isMovement() || phase.isPhysical()) && actor != null) {
            return Messages.getString("GpuBoard.hud.phase.yourTurn", GpuUnitCard.unitName(actor));
        } else if (foe) {
            GpuBattleStatus.UnitStatus enemy = state.presented(current.entityId());
            return Messages.getString("GpuBoard.hud.phase.opponentTurn",
                  enemy == null || enemy.sensorContact() ? current.playerName() : GpuUnitCard.unitName(enemy));
        } else if (phase.isPhysical() && !status.myTurn() && units.stream()
              .noneMatch(unit -> unit.side() == GpuBattleStatus.Side.OWN && unit.pending())) {
            return Messages.getString("GpuBoard.hud.phase.noAdjacentEnemies");
        }
        return inputs.frame().panels().phase().status().lines().findFirst().orElse("");
    }

    /**
     * The color of a side in the ribbon and the turn order: mint for the local player, ink2 for an ally, coral for
     * an enemy (plan B4).
     */
    static Color sideColor(GpuBattleStatus.Side side) {
        return switch (side) {
            case OWN -> UiTheme.MINT;
            case ALLY -> UiTheme.ACCENT;
            case ENEMY -> UiTheme.CORAL;
        };
    }

    /** The color of one activation; a turn without a player is dim. */
    static Color turnColor(GpuBattleStatus.Slot slot) {
        return slot.playerName().isEmpty() ? UiTheme.DISABLED : sideColor(slot.side());
    }

    @Override
    public void dispose() {
        ring.dispose();
    }

    /**
     * The phase ring (#phase .ring, a 48-unit SVG drawn at 46): a faint circle, the arc of the round so far from 12
     * o'clock clockwise with round caps (at least 4 % long) and a dot at the arc's true end. Its image is rasterised
     * with analytic coverage at twice the unit size whenever the progress changes, on the render thread while
     * drawing, and freed by {@link #dispose()}.
     */
    private static final class Ring extends Widget {
        private static final int TEXELS = 92;
        private static final float RADIUS = 20;
        private static final float STROKE = 2.5f;
        private static final float DOT = 3;
        private static final Color ARC = Color.valueOf("E9EFEB");
        private static final Color TRACK = new Color(1, 1, 1, .16f);
        private float progress;
        private float drawn = -1;
        private Texture texture;

        @Override
        public float getPrefWidth() {
            return 46;
        }

        @Override
        public float getPrefHeight() {
            return 46;
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            if (texture == null || drawn != progress) {
                dispose();
                drawn = progress;
                Pixmap image = image(progress);
                texture = new Texture(image);
                texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
                image.dispose();
            }
            Color color = getColor();
            batch.setColor(color.r, color.g, color.b, color.a * parentAlpha);
            batch.draw(texture, getX(), getY(), getWidth(), getHeight());
        }

        /** The ring for a progress of 0 to 1, in the SVG's 48-unit view box, straight alpha. */
        private static Pixmap image(float progress) {
            Pixmap image = new Pixmap(TEXELS, TEXELS, Pixmap.Format.RGBA8888);
            image.setBlending(Pixmap.Blending.None);
            float texel = 48f / TEXELS;
            float sweep = Math.max(.04f, progress) * MathUtils.PI2;
            float endX = 24 + RADIUS * MathUtils.sin(sweep);
            float endY = 24 - RADIUS * MathUtils.cos(sweep);
            float dotX = 24 + RADIUS * MathUtils.sin(progress * MathUtils.PI2);
            float dotY = 24 - RADIUS * MathUtils.cos(progress * MathUtils.PI2);
            Color pixel = new Color();
            for (int row = 0; row < TEXELS; row++) {
                for (int column = 0; column < TEXELS; column++) {
                    float x = (column + .5f) * texel;
                    float y = (row + .5f) * texel;
                    float ring = Math.abs(Vector2.len(x - 24, y - 24) - RADIUS);
                    // Clockwise from 12 o'clock, with y pointing down.
                    float angle = MathUtils.atan2(x - 24, 24 - y);
                    angle = angle < 0 ? angle + MathUtils.PI2 : angle;
                    float arc = angle <= sweep ? ring
                          : Math.min(Vector2.len(x - 24, y - 4), Vector2.len(x - endX, y - endY));
                    pixel.set(1, 1, 1, 0);
                    over(pixel, TRACK, coverage(STROKE / 2 - ring, texel));
                    over(pixel, ARC, coverage(STROKE / 2 - arc, texel));
                    over(pixel, Color.WHITE, coverage(DOT - Vector2.len(x - dotX, y - dotY), texel));
                    image.drawPixel(column, row, Color.rgba8888(pixel));
                }
            }
            return image;
        }

        /** The share of a texel inside a shape whose edge is {@code inside} view-box units away. */
        private static float coverage(float inside, float texel) {
            return MathUtils.clamp(inside / texel + .5f, 0, 1);
        }

        /** Straight-alpha "source over" of {@code color} at {@code coverage} onto {@code pixel}. */
        private static void over(Color pixel, Color color, float coverage) {
            float alpha = color.a * coverage;
            float result = alpha + pixel.a * (1 - alpha);
            if (result > 0) {
                pixel.r = (color.r * alpha + pixel.r * pixel.a * (1 - alpha)) / result;
                pixel.g = (color.g * alpha + pixel.g * pixel.a * (1 - alpha)) / result;
                pixel.b = (color.b * alpha + pixel.b * pixel.a * (1 - alpha)) / result;
            }
            pixel.a = result;
        }

        void dispose() {
            if (texture != null) {
                texture.dispose();
                texture = null;
            }
        }
    }

    /**
     * The activation ribbon (#phase .ticks): one 5-unit bar per turn, at most 48 from eight before the current turn,
     * in its side's color at 75 %, faded after its turn, the current one outlined in white.
     */
    private final class Ribbon extends Widget {
        private List<GpuBattleStatus.Slot> turns = List.of();
        private int current;

        @Override
        public float getPrefHeight() {
            return RIBBON_HEIGHT;
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            int count = Math.min(turns.size(), RIBBON_SLOTS);
            if (count == 0) {
                return;
            }
            int first = Math.max(0, Math.min(turns.size() - count, current - RIBBON_BEFORE));
            float width = (getWidth() - RIBBON_GAP * (count - 1)) / count;
            float alpha = getColor().a * parentAlpha;
            float y = getY();
            float height = getHeight();
            float previous = batch.getPackedColor();
            for (int column = 0; column < count; column++) {
                int turn = first + column;
                float x = getX() + column * (width + RIBBON_GAP);
                if (turn == current) {
                    // box-shadow: 0 0 0 2px #fff
                    ui.fill(batch, Color.WHITE, alpha, x - 2, y - 2, width + 4, 2);
                    ui.fill(batch, Color.WHITE, alpha, x - 2, y + height, width + 4, 2);
                    ui.fill(batch, Color.WHITE, alpha, x - 2, y, 2, height);
                    ui.fill(batch, Color.WHITE, alpha, x + width, y, 2, height);
                }
                ui.fill(batch, turnColor(turns.get(turn)), alpha * RIBBON_ALPHA * (turn < current ? DONE_ALPHA : 1),
                      x, y, width, height);
            }
            batch.setPackedColor(previous);
        }
    }
}
