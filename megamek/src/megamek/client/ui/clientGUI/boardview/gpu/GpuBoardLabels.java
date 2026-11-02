/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudKit.facing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudKit.percent;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudKit.shown;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuHudKit.signed;
import static megamek.client.ui.gdx.UiKit.text;
import static megamek.client.ui.gdx.UiTheme.rgba;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.gdx.UiTheme.EdgeBox;
import megamek.common.board.Coords;
import megamek.common.units.Entity;

/**
 * Board labels, the HUD's lowest layer with the nameplates: the fire preview's TN badges and flowing guides,
 * the selected weapon's TN badges, the traces of the queued attacks with the target cards' leaders, the destination
 * tip, the waypoint numbers and the playback pop-ups. The guides, traces and leaders are the prototype's #fx layer,
 * {@link #fx()}, which lies under every board label; the rest is {@link #actor()}. The badges of the hovered unit and
 * of the unit the card shows are drawn over the others. Units are placed by the frame's HudView and hexes by the board
 * camera; the component presents snapshots only and posts no command.
 */
final class GpuBoardLabels implements GpuHud.Component {
    /** overlay.js mid(): a unit's middle at .55 of its height; the tip at .9 of the unit's height over its hex. */
    private static final float MIDDLE = .55f;
    private static final float TIP_HEIGHT = .9f;
    /** The Tactical View anchors a unit's labels at its base ring's north point, half up its screen square. */
    private static final float RING_NORTH = .5f;
    /** In the Tactical View lines between units end on a circle just outside their base rings (r .88 apothems). */
    private static final float RING_GAP = .88f / GpuUnitBases.RING;
    /**
     * .tnb: bottom-centred 4 units above the unit's nameplate (on its head without one); 3 8 padding inside a 1-unit
     * border; 6 between TN and odds.
     */
    private static final float BADGE_RISE = 4;
    private static final float BADGE_GAP = 6;
    /** .tip3d: (+56, -34) CSS from the destination's anchor, flipped left over the HUD, 8 inside the window. */
    private static final float TIP_DX = 56;
    private static final float TIP_DY = 34;
    private static final float EDGE = 8;
    /** overlay.js refreshHudRects: the HUD rectangles labels keep clear of are 6 units larger on every side. */
    private static final float HUD_MARGIN = 6;
    /** .pinno: a 20-unit disc 22 units above its hex, 700 11 Roboto, shadow 0 2px 6px rgba(0, 0, 0, .5). */
    private static final float PIN_SIZE = 20;
    private static final float PIN_RISE = 22;
    private static final float PIN_TEXT = 11;
    /** flat.js: the Tactical View's waypoint number, max(10, .26 of the hex radius). */
    private static final float FLAT_PIN_TEXT = 10;
    /** Guides: 1.8 (open row 2.6) wide, dash 7 7 flowing 14 units every 1.1 s; a two-way pair sits 3 apart. */
    private static final float GUIDE_WIDTH = 1.8f;
    private static final float GUIDE_OPEN_WIDTH = 2.6f;
    private static final float GUIDE_DASH = 7;
    private static final float GUIDE_SECONDS = 1.1f;
    private static final float PAIR = 3;
    /**
     * Guides of each direction at most, the preview's best first, besides the open row's: every shot is badged, but a
     * crowded battle keeps its guides legible.
     */
    private static final int GUIDES = 50;
    /** Guides and traces keep this much space around both units' screen rectangles. */
    private static final float UNIT_GAP = 3;
    /** Traces: 1.6 wide, dash 2 5 with round caps; the Tactical View's are 2 wide, dash 5 5 (flat.js:55). */
    private static final float TRACE_WIDTH = 1.6f;
    private static final float TRACE_DASH = 2;
    private static final float TRACE_GAP = 5;
    private static final float FLAT_TRACE_WIDTH = 2;
    private static final float FLAT_TRACE_DASH = 5;
    /** Leaders: 1.2 wide to an 8-unit square diamond with a 1-unit white stroke. */
    private static final float LEADER_WIDTH = 1.2f;
    private static final float DIAMOND = 8;
    private static final float SQRT2 = (float) Math.sqrt(2);
    /** .pop3d: +18 / -40 from its anchor, rising 30 units a second and fading over 1.6 s; gone at 1.8 s. */
    private static final float POP_DX = 18;
    private static final float POP_RISE = 40;
    private static final float POP_SPEED = 30;
    private static final float POP_FADE = 1.6f;
    /** Gone at 1.8 s of 1x playback; faster playback shortens it, by half at most (overlay.js showShot). */
    private static final float POP_LIFE = 1.8f;
    private static final double POP_PACE = 2;
    /** overlay.js frozenFx: while the playback is paused or reviewing, the newest pop-up is held at .35 s at most. */
    private static final float POP_FROZEN_AGE = .35f;
    /** Pop-ups of one unit stack: an older one rides this far over the next newer one (stackPops). */
    private static final float POP_GAP = 4;
    /** The stroke ramp's texel centres: clear outside, opaque inside. */
    private static final float CLEAR = .25f;
    private static final float OPAQUE = .75f;

    private static final Color GUIDE_OUT = UiTheme.alpha(UiTheme.MINT, .85f);
    private static final Color GUIDE_IN = rgba(240, 110, 100, .85f);
    private static final Color TRACE = UiTheme.alpha(Color.WHITE, .7f);
    private static final Color TRACE_PRIMARY = UiTheme.alpha(Color.WHITE, .95f);
    private static final Color FLAT_TRACE = UiTheme.alpha(Color.WHITE, .9f);
    private static final Color FLAT_TRACE_SECONDARY = UiTheme.alpha(UiTheme.CORAL, .9f);
    /** Spotting and TAG links: dotted light blue, 2.5 on and 4.5 off, with the link's icon on a disc halfway. */
    private static final Color LINK = Color.valueOf("8CC4FF");
    private static final float LINK_WIDTH = 1.6f;
    private static final float LINK_DASH = 2.5f;
    private static final float LINK_GAP = 4.5f;
    private static final float LINK_DISC = 16;
    private static final Color LEADER = UiTheme.alpha(Color.WHITE, .8f);
    private static final Color DIAMOND_FILL = Color.valueOf("EC6F64");
    private static final Color PIN_FILL = Color.valueOf("F2F5F1");
    private static final Color PIN_INK = Color.valueOf("17201E");
    private static final Color POP_HIT = Color.valueOf("FFD27A");
    private static final Color POP_MISS = Color.valueOf("E4E4E0");
    private static final Color POP_EDGE = Color.valueOf("6B4200");
    private static final Color POP_GLOW = rgba(255, 160, 40, .15f);
    private static final Color POP_SHADOW = rgba(0, 0, 0, .3f);

    private static final String TARGET_NUMBER = "GpuBoard.hud.common.targetNumber";
    private static final String HIT = "GpuBoard.hud.common.hit";
    private static final String MISS = "GpuBoard.hud.common.miss";

    private final UiKit ui;
    private final GpuHudState state;
    private final BoardCamera camera;
    private final GpuContactsPanel contacts;
    private final GpuNameplates nameplates;
    private final Texture white;
    /** Two texels, clear and opaque: every stroke is feathered across one unit on each side. */
    private final Texture ramp;
    /** An antialiased white disc for the waypoint numbers. */
    private final Texture disc;
    private final Drawable badgeBox;
    private final Drawable openBadgeBox;
    private final Drawable offBadgeBox;
    private final Table root = new Table();
    /** The #fx layer: guides, traces and leaders. */
    private final Strokes strokes = new Strokes();
    private final Table tip = new Table();
    /** The Tactical View's "REAR" chip two hexes behind the enemy whose arcs the ground layer shades. */
    private final Table rear = new Table();
    private final Group pins = new Group();
    private final Group badges = new Group();
    /** The playback pop-ups, oldest first: they age, then stack. */
    private final Group pops = new Group() {
        @Override
        public void act(float delta) {
            super.act(delta);
            stackPops();
        }
    };
    private final List<Pin> pinActors = new ArrayList<>();
    private final Map<Integer, Badge> badgeActors = new HashMap<>();
    /** The anchors of the targets that get a card this frame, for the leaders. */
    private final Map<TargetKey, Vector2> cardHeads = new HashMap<>();
    private List<String> tipLines = List.of();
    private Map<TargetKey, Rectangle> cards = Map.of();
    private GpuHud.Inputs inputs;
    private boolean frozen;
    private float popLife = POP_LIFE;

    /**
     * {@code camera} places the labels that belong to hexes (the destination tip, the waypoint numbers, guides from
     * the destination); {@code contacts} owns the guide toggles (F3), and the open preview row is the enemy the card
     * shows (F9); the pop-ups come from the state's playback history. The TN badges stand on the {@code nameplates},
     * which update first.
     */
    GpuBoardLabels(GpuHudKit kit, GpuBoardSource source, GpuHudState state, BoardCamera camera,
          GpuContactsPanel contacts, GpuNameplates nameplates) {
        ui = kit.ui;
        this.state = state;
        this.camera = camera;
        this.contacts = contacts;
        this.nameplates = nameplates;
        white = ui.skin.get("white", Texture.class);
        ramp = ramp();
        disc = disc(Math.round(2 * PIN_SIZE));
        badgeBox = box(rgba(23, 33, 33, .92f), UiTheme.alpha(UiTheme.CORAL, .7f), 4, 9);
        openBadgeBox = box(rgba(60, 30, 28, .95f), Color.WHITE, 4, 9);
        offBadgeBox = box(rgba(23, 33, 33, .92f), rgba(174, 187, 180, .35f), 4, 9);
        root.setName("board-labels");
        strokes.setName("board-fx");
        // Labels never take a press: the board below gets it, as the prototype's label layer lets it through.
        root.setTouchable(Touchable.disabled);
        tip.setName("route-tip");
        rear.setName("rear-chip");
        rear.setBackground(box(rgba(255, 196, 84, .92f), rgba(255, 214, 140, 1), 2, 7));
        rear.add(ui.label(UiTheme.upper(text("GpuBoard.hud.labels.rear")), "hud-medium", 11, Color.valueOf("2A2112")));
        rear.pack();
        tip.setBackground(box(rgba(20, 28, 29, .94f), rgba(232, 241, 235, .6f), 9, 12));
        for (Group layer : List.of(pins, badges, pops)) {
            layer.setTransform(false);
        }
        root.addActor(tip);
        root.addActor(rear);
        root.addActor(pins);
        root.addActor(badges);
        root.addActor(pops);
    }

    @Override
    public Actor actor() {
        return root;
    }

    /**
     * The prototype's #fx layer: the fire preview's guides, the attack traces and the target cards' leaders. It spans
     * the window as {@link #actor()} does and lies under every board label, the nameplates included (r1 section 1.3),
     * so GpuHud gives it the labels layer's first slot.
     */
    Actor fx() {
        return strokes;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        this.inputs = inputs;
        GpuHud.HudView view = inputs.view();
        GpuHudData panels = inputs.frame().panels();
        BoardScene scene = inputs.frame().scene();
        strokes.reset();
        cardHeads.clear();
        // The badged units in the order of the preview's and the orders' lists.
        Set<Integer> badged = new LinkedHashSet<>();
        strokes.round = view.tactical();
        rear(scene, inputs.frame().status(), view);
        plan(scene, panels.move(), view, inputs.panelBounds());
        preview(scene, panels.preview(), panels.move(), view, badged);
        fire(panels.fire(), view, badged);
        if (inputs.preferences().overlays().unitLinks()) {
            // View > Unit Links: the spotting and TAG lines, as the boards' C3 lines.
            links(inputs.frame().status().links(), view);
        }
        badgeActors.entrySet().removeIf(entry -> {
            boolean gone = !badged.contains(entry.getKey());
            if (gone) {
                entry.getValue().remove();
            }
            return gone;
        });
        GpuHudKit.stack(badged.stream().map(badgeActors::get).toList(), badgeActors.get(view.hoveredUnit()),
              badgeActors.get(state.cardUnit()));
        stackPops();
        // The playback's pop-ups: each step the history presented since the last frame, a shot as it lands.
        for (GpuPlaybackHistory.Step step : state.history.takeShown()) {
            if (step.attack() != null) {
                show(step.attack());
            } else if (step.roll() != null) {
                show(step.roll());
            } else {
                show(step.notFired());
            }
        }
        freeze(state.history.frozen());
        double pace = state.history.speed().rate / UnitMotion.Speed.NORMAL.rate;
        popLife = (float) (POP_LIFE / (pace > 0 ? Math.min(POP_PACE, pace) : 1));
    }

    /**
     * The target cards' rectangles by target, in this component's units, as the target cards placed them this frame;
     * a card that does not cover its target's anchor gets a leader to it (r1 3.13).
     */
    void cards(Map<TargetKey, Rectangle> placed) {
        cards = Map.copyOf(placed);
    }

    /**
     * Shows the pop-up of one presented attack at its target (A.10 J10): the damage and the locations hit over the
     * weapon, its cluster hits or "Location destroyed", or a miss with the roll against the target number of the
     * report entry linked to it. {@link #update} calls it when the shot lands, in live play and in review.
     */
    void show(GpuReportLog.CombatEvent attack) {
        GpuReportLog.Entry entry = inputs == null ? null : inputs.frame().reports().entries().stream()
              .filter(candidate -> attack.id().equals(candidate.attack())).findFirst().orElse(null);
        if (!attack.hit()) {
            // The roll line keeps its case, as the prototype's "7 vs 8+".
            boolean rolled = entry != null && entry.roll() != null && entry.targetNumber() != null;
            pop(attack.targetId(), text(MISS), rolled
                  ? text("GpuBoard.hud.labels.missRoll", entry.roll(), entry.targetNumber()) : "", false);
            return;
        }
        // Entity.getLocationAbbr(HitData) names a rear hit "CTR".
        String locations = attack.impacts().stream()
              .map(impact -> impact.rear() ? impact.location() + "R" : impact.location()).distinct()
              .collect(Collectors.joining(" "));
        String title = attack.damage() > 0 ? text("GpuBoard.hud.labels.damage", attack.damage(), locations).strip()
              : text(HIT);
        String sub = entry != null && entry.locationDestroyed() ? text("GpuBoard.hud.labels.locationDestroyed")
              : attack.missileHits() != null && attack.missiles() > 0
              ? text("GpuBoard.hud.labels.cluster", attack.weapon(), attack.missileHits(), attack.missiles())
              : attack.weapon();
        pop(attack.targetId(), title, UiTheme.upper(sub), true);
    }

    /** Shows a piloting roll's pop-up at its unit: "PSR passed" or "Falls" (J10). */
    void show(GpuReportLog.PsrItem roll) {
        pop(roll.entityId(), text(roll.passed() ? "GpuBoard.hud.labels.psrPassed" : "GpuBoard.hud.labels.falls"), "",
              false);
    }

    /** Shows "Not fired" at the target of an attack entry that was impossible and so has no combat event (J10). */
    void show(GpuReportLog.Entry attack) {
        if (attack.notFired() != null) {
            pop(attack.targetId(), text("GpuBoard.hud.labels.notFired"), "", false);
        }
    }

    /**
     * While the playback is paused or reviewing, only the newest pop-up shows and it stays young (overlay.js
     * frozenFx); {@link #update} sets it from the playback history.
     */
    void freeze(boolean frozen) {
        this.frozen = frozen;
    }

    @Override
    public void dispose() {
        ramp.dispose();
        disc.dispose();
    }

    // The movement plan: the destination tip and the waypoint numbers (A.7 G5, G8).

    private void plan(BoardScene scene, GpuMovePlan.Snapshot move, GpuHud.HudView view, List<Rectangle> panels) {
        // Only a plotted route of a unit the HUD plans (the hover route has no tip), on the board shown.
        boolean planned = scene != null && move.active() && move.planner() && move.destination() != null
              && !move.route().isEmpty() && move.route().getLast().boardId() == scene.boardId();
        Vector2 anchor = planned ? lifted(plannedPoint(scene, move.route().getLast(), view),
              move.entityId(), TIP_HEIGHT, view) : null;
        tip.setVisible(anchor != null);
        if (anchor != null) {
            showTip(move);
            // The Tactical View's tip also keeps clear of the units' bases.
            List<Rectangle> obstacles = new ArrayList<>(panels);
            if (view.tactical()) { obstacles.addAll(view.unitRects().values()); }
            placeTip(anchor, obstacles);
        }
        List<GpuMovePlan.Step> shown = planned ? move.pins() : List.of();
        while (pinActors.size() < shown.size()) {
            pinActors.add(new Pin(pinActors.size() + 1));
        }
        for (int index = 0; index < pinActors.size(); index++) {
            Vector2 hex = index < shown.size() ? plannedPoint(scene, shown.get(index), view) : null;
            pinActors.get(index).place(hex, view);
        }
    }

    /**
     * The "REAR" chip of the enemy whose arcs the Tactical View shades: two hexes behind it, opposite its facing;
     * hidden without arcs or outside the Tactical View.
     */
    private void rear(BoardScene scene, GpuBattleStatus.Snapshot status, GpuHud.HudView view) {
        GpuBattleStatus.Arc arc = status.arc();
        GpuBattleStatus.UnitStatus unit = status.units().stream().filter(listed -> listed.id() == arc.unitId())
              .findFirst().orElse(null);
        Vector2 at = !view.tactical() || unit == null || unit.facing() < 0 || arc.origin() == null ? null
              : hex(scene, arc.origin().translated((unit.facing() + 3) % 6, 2));
        rear.setVisible(at != null);
        if (at != null) {
            rear.setPosition(Math.round(at.x - rear.getWidth() / 2), Math.round(at.y - rear.getHeight() / 2));
        }
    }

    private void showTip(GpuMovePlan.Snapshot move) {
        List<String> lines = new ArrayList<>(List.of(
              UiTheme.upper(text(move.auto() ? "GpuBoard.hud.tip.titleAuto" : "GpuBoard.hud.tip.title",
                    move.typeLabel(), move.cost(), move.budget())),
              text("GpuBoard.hud.tip.heat", signed(move.heat()), signed(move.tmm())),
              text("GpuBoard.hud.tip.facing", facing(move.facing()), move.pins().size())));
        if (!move.warnings().isEmpty()) {
            lines.add(text("GpuBoard.hud.tip.pilotingRoll", String.join(", ", move.warnings())));
        }
        if (lines.equals(tipLines)) {
            return;
        }
        tipLines = lines;
        tip.clearChildren();
        // .tip3d b: 700 13 Condensed white, 2 above the 11.5-unit ink2 lines.
        tip.add(ui.label(lines.getFirst(), "hud-name", 13, Color.WHITE)).left().padBottom(2).row();
        for (String line : lines.subList(1, lines.size())) {
            tip.add(ui.label(line, "hud-small", 11.5f, UiTheme.ACCENT)).left().row();
        }
        tip.pack();
    }

    /**
     * overlay.js placeLabels for the tip: right of the anchor and above it; flipped to its left when it would cover a
     * HUD panel; kept 8 units inside the window.
     */
    private void placeTip(Vector2 anchor, List<Rectangle> panels) {
        float width = tip.getWidth();
        float height = tip.getHeight();
        float x = anchor.x + TIP_DX;
        float top = anchor.y + TIP_DY;
        Rectangle area = new Rectangle(x, top - height, width, height);
        for (Rectangle panel : panels) {
            Rectangle keepOut = new Rectangle(panel.x - HUD_MARGIN, panel.y - HUD_MARGIN,
                  panel.width + 2 * HUD_MARGIN, panel.height + 2 * HUD_MARGIN);
            if (keepOut.overlaps(area)) {
                x = anchor.x - TIP_DX - width;
                break;
            }
        }
        x = MathUtils.clamp(x, EDGE, Math.max(EDGE, root.getWidth() - width - EDGE));
        top = Math.min(top, root.getHeight() - EDGE);
        tip.setPosition(Math.round(x), Math.round(top - height));
    }

    // The movement fire preview: TN badges and guides (A.6 F9).

    private void preview(BoardScene scene, GpuFirePreview.Snapshot preview, GpuMovePlan.Snapshot move,
          GpuHud.HudView view, Set<Integer> badged) {
        if (!preview.active()) {
            return;
        }
        // The open row is the enemy the card shows (GpuContactsPanel).
        int open = state.cardUnit();
        boolean outgoing = contacts == null || contacts.outgoingGuides();
        boolean incoming = contacts == null || contacts.incomingGuides();
        // Use the same destination level as the movement ghost, including floors and flight height.
        Rectangle body = preview.fromDestination() && !move.route().isEmpty() ? ghost(scene, preview, move, view)
              : view.unitRects().get(preview.unitId());
        Vector2 source = body == null ? null : body.getCenter(new Vector2());
        int outgoingGuides = 0;
        int incomingGuides = 0;
        for (GpuFirePreview.Contact contact : preview.contacts()) {
            if (contact.sensor()) {
                continue;
            }
            // Every shot and threat; the open row's stand out.
            boolean opened = contact.id() == open;
            GpuFirePreview.Side out = contact.outgoing();
            boolean shot = out.available() > 0;
            boolean threat = contact.incoming().available() > 0;
            if (shot) {
                badge(contact.id(), view, text(TARGET_NUMBER, shown(out.best())), percent(out.odds()), "",
                      opened ? openBadgeBox : badgeBox, badged);
            }
            Rectangle enemy = view.unitRects().get(contact.id());
            Vector2 target = enemy == null ? null : enemy.getCenter(new Vector2());
            boolean drawOut = outgoing && shot && (opened || outgoingGuides < GUIDES);
            boolean drawIn = incoming && threat && (opened || incomingGuides < GUIDES);
            if (source == null || target == null || !drawOut && !drawIn) {
                continue;
            }
            float width = opened ? GUIDE_OPEN_WIDTH : GUIDE_WIDTH;
            // A pair sits side by side: the outgoing guide right of the line from the shooter to the target.
            Vector2 aside = new Vector2(target).sub(source).nor().rotate90(-1).scl(drawOut && drawIn ? PAIR : 0);
            if (drawOut) {
                outgoingGuides++;
                strokes.add(new Vector2(source).add(aside), new Vector2(target).add(aside), body, enemy,
                      width, GUIDE_OUT, GUIDE_DASH, GUIDE_DASH, true, false);
            }
            if (drawIn) {
                incomingGuides++;
                strokes.add(new Vector2(target).sub(aside), new Vector2(source).sub(aside), enemy, body,
                      width, GUIDE_IN, GUIDE_DASH, GUIDE_DASH, true, false);
            }
        }
    }

    /** The spotting and TAG links between units, with an eye or a crosshair halfway. */
    private void links(List<GpuBattleStatus.Link> links, GpuHud.HudView view) {
        for (GpuBattleStatus.Link link : links) {
            Rectangle from = view.unitRects().get(link.fromId());
            Rectangle to = view.unitRects().get(link.toId());
            if (from == null || to == null) {
                continue;
            }
            Vector2 start = from.getCenter(new Vector2());
            Vector2 end = to.getCenter(new Vector2());
            strokes.add(start, end, from, to, LINK_WIDTH, LINK, LINK_DASH, LINK_GAP, false, false);
            strokes.mark(start.lerp(end, .5f), link.kind() == GpuBattleStatus.Link.Kind.SPOT ? "spotting" : "locate");
        }
    }

    // Weapon declaration: TN badges on other enemies, traces and the anchors of the carded targets (A.8 H23, H32).

    private void fire(GpuFireOrders.Snapshot fire, GpuHud.HudView view, Set<Integer> badged) {
        if (!fire.active()) {
            return;
        }
        Set<TargetKey> carded = fire.carded();
        for (GpuFireOrders.Badge badge : fire.badges()) {
            // Target cards replace the labels of their units.
            if (carded.contains(TargetKey.unit(badge.targetId()))) {
                continue;
            }
            boolean reason = badge.reason() != null && !badge.reason().isEmpty();
            badge(badge.targetId(), view, reason ? "" : text(TARGET_NUMBER, shown(badge.value())),
                  reason ? "" : percent(badge.odds()), reason ? badge.reason() : "",
                  reason ? offBadgeBox : badgeBox, badged);
        }
        Vector2 middle = trace(fire.actorId(), view);
        for (GpuFireOrders.Target target : fire.targets()) {
            int unit = target.key().unitId();
            // A hex, building or minefield: its hex's centre.
            Vector2 to = unit != Entity.NONE ? trace(unit, view) : view.head(target.key());
            if (middle == null || to == null) {
                continue;
            }
            if (view.tactical()) {
                strokes.add(middle, to, view.unitRects().get(fire.actorId()), view.unitRects().get(unit),
                      FLAT_TRACE_WIDTH, target.primary() ? FLAT_TRACE : FLAT_TRACE_SECONDARY, FLAT_TRACE_DASH,
                      FLAT_TRACE_DASH, false, false);
            } else {
                strokes.add(middle, to, view.unitRects().get(fire.actorId()), view.unitRects().get(unit),
                      TRACE_WIDTH, target.primary() ? TRACE_PRIMARY : TRACE, TRACE_DASH, TRACE_GAP, false, true);
            }
        }
        for (TargetKey key : carded) {
            Vector2 head = view.head(key);
            if (head != null) {
                cardHeads.put(key, head);
            }
        }
    }

    /** A TN badge bottom-centred over the unit's nameplate (.tnb); a reason instead of a roll uses the .off look. */
    private void badge(int unit, GpuHud.HudView view, String value, String percent, String reason, Drawable box,
          Set<Integer> badged) {
        Vector2 head = view.unitHeads().get(unit);
        if (head == null || !badged.add(unit)) {
            return;
        }
        Badge badge = badgeActors.computeIfAbsent(unit, id -> new Badge());
        badge.show(value, percent, reason, box);
        badge.setPosition(Math.round(head.x - badge.getWidth() / 2),
              Math.round(nameplates.top(unit, head.y) + BADGE_RISE));
    }

    // Playback pop-ups (A.10 J10).

    /** A pop-up at a unit: the title in upper case over {@code sub} as given. */
    private void pop(int unit, String title, String sub, boolean hit) {
        if (unit == Entity.NONE) {
            // An attack at a hex or a building: no unit, so the view gives the pop-up no place.
            return;
        }
        pops.addActor(new Pop(unit, UiTheme.upper(title), sub, hit));
        stackPops();
    }

    /**
     * Places the pop-ups so that those of one unit never cover each other, as a volley's hits did (the user's report
     * of 2026-10-02): the newest keeps its place and each older one rides POP_GAP over the next newer one. The lift
     * follows from their ages and heights, so it holds while they rise together.
     */
    private void stackPops() {
        if (inputs == null) {
            return;
        }
        Map<Integer, Pop> newer = new HashMap<>();
        for (int i = pops.getChildren().size - 1; i >= 0; i--) {
            Pop pop = (Pop) pops.getChildren().get(i);
            Pop next = newer.put(pop.unit, pop);
            pop.lift = next == null ? 0
                  : Math.max(0, next.lift + pop.getHeight() + POP_GAP - (pop.age - next.age) * POP_SPEED);
            pop.place(inputs.view());
        }
    }

    // Anchors, in this component's units: the HUD root spans the board camera's viewport from its lower left corner.

    /** A unit's middle: .55 up its screen rectangle; in the Tactical View its lifted head, as the prototype's. */
    private static Vector2 middle(int unit, GpuHud.HudView view) {
        Rectangle rect = view.unitRects().get(unit);
        Vector2 head = view.unitHeads().get(unit);
        if (rect == null || view.tactical() && head != null) {
            return head;
        }
        return new Vector2(rect.x + rect.width / 2, rect.y + MIDDLE * rect.height);
    }

    /** A weapon line connects the centres of the displayed units, regardless of facing or camera mode. */
    private static Vector2 trace(int unit, GpuHud.HudView view) {
        Rectangle rect = view.unitRects().get(unit);
        return rect == null ? null : rect.getCenter(new Vector2());
    }

    /** Trim a centre-to-centre segment at both units, after any paired-guide offset. Overlapping ends draw nothing. */
    static boolean clip(Vector2 from, Vector2 to, Rectangle source, Rectangle target) {
        return clip(from, to, source, target, false);
    }

    /**
     * As {@link #clip(Vector2, Vector2, Rectangle, Rectangle)}; {@code round}: the units are the Tactical View's bases,
     * whose lines end on a circle just outside the ring their square bounds.
     */
    static boolean clip(Vector2 from, Vector2 to, Rectangle source, Rectangle target, boolean round) {
        Vector2 direction = new Vector2(to).sub(from);
        float length = direction.len();
        direction.nor();
        float start = round ? ringClearance(from, direction, source) : clearance(from, direction, source);
        float end = round ? ringClearance(to, new Vector2(direction).scl(-1), target)
              : clearance(to, new Vector2(direction).scl(-1), target);
        if (length <= start + end) { return false; }
        from.mulAdd(direction, start);
        to.mulAdd(direction, -end);
        return true;
    }

    /** Distance from a point to the circle just outside a base's ring, centred in its square, along the line. */
    private static float ringClearance(Vector2 point, Vector2 direction, Rectangle body) {
        if (body == null) { return 0; }
        Vector2 offset = new Vector2(point).sub(body.getCenter(new Vector2()));
        float radius = body.width / 2 * RING_GAP;
        // The far intersection of the line from the point with the circle: t^2 + 2bt + c = 0.
        float b = offset.dot(direction);
        float root = b * b - (offset.len2() - radius * radius);
        return root < 0 ? 0 : Math.max(0, -b + (float) Math.sqrt(root));
    }

    /** Distance from a point inside the padded unit rectangle to its edge along the line. */
    private static float clearance(Vector2 point, Vector2 direction, Rectangle body) {
        if (body == null) { return 0; }
        float exit = Float.POSITIVE_INFINITY;
        if (direction.x != 0) {
            exit = ((direction.x > 0 ? body.x + body.width + UNIT_GAP : body.x - UNIT_GAP) - point.x)
                  / direction.x;
        }
        if (direction.y != 0) {
            exit = Math.min(exit, ((direction.y > 0 ? body.y + body.height + UNIT_GAP : body.y - UNIT_GAP) - point.y)
                  / direction.y);
        }
        return Math.max(0, exit);
    }

    /**
     * The unit's screen rectangle at the planned elevation, or centred on the hex in the Tactical View.
     * Hide a stale preview's guides until its destination matches the current movement ghost.
     */
    private Rectangle ghost(BoardScene scene, GpuFirePreview.Snapshot preview, GpuMovePlan.Snapshot move,
          GpuHud.HudView view) {
        if (scene == null || move.entityId() != preview.unitId() || move.route().isEmpty()) { return null; }
        GpuMovePlan.Step end = move.route().getLast();
        if (!end.coords().equals(preview.from()) || end.boardId() != preview.boardId()) { return null; }
        Rectangle rect = view.unitRects().get(preview.unitId());
        Vector2 ground = plannedPoint(scene, end, view);
        if (rect == null || ground == null) {
            return null;
        }
        return new Rectangle(ground.x - rect.width / 2, view.tactical() ? ground.y - rect.height / 2 : ground.y,
              rect.width, rect.height);
    }

    /**
     * A point over a hex at {@code fraction} of the unit's screen height, as the prototype anchors the unit's pose
     * there (the Tactical View at its base ring's north point, like a head); null off the board or behind the camera.
     */
    private Vector2 lifted(Vector2 ground, int unit, float fraction, GpuHud.HudView view) {
        Rectangle rect = view.unitRects().get(unit);
        if (ground == null) {
            return null;
        }
        return ground.add(0, rect == null ? 0 : (view.tactical() ? RING_NORTH : fraction) * rect.height);
    }

    /** Movement labels follow the exact floor; the Tactical View keeps its flat board projection. */
    private Vector2 plannedPoint(BoardScene scene, GpuMovePlan.Step step, GpuHud.HudView view) {
        BoardScene.Tile tile = scene.tile(step.coords());
        if (tile == null || step.boardId() != scene.boardId()) { return null; }
        return view.tactical() || step.level() == tile.elevation() ? hex(scene, step.coords())
              : screen(BoardGeometry.center(step.coords(), step.level()));
    }

    /** A hex's visible surface, water included, in this component's units; null without a camera or behind it. */
    private Vector2 hex(BoardScene scene, Coords coords) {
        BoardScene.Tile tile = scene == null ? null : scene.tile(coords);
        return tile == null ? null : screen(new Vector3(BoardGeometry.centerX(coords), BoardGeometry.centerY(coords),
              BoardGeometry.surfaceZ(tile)));
    }

    /** A world point in this component's units; null without a camera or behind it. */
    private Vector2 screen(Vector3 world) {
        if (camera == null || root.getWidth() <= 0) {
            return null;
        }
        Vector2 point = GpuNameplates.project(camera.camera, world);
        return point == null ? null : point.scl(root.getWidth() / camera.camera.viewportWidth,
              root.getHeight() / camera.camera.viewportHeight);
    }

    /** A label box: its fill under a one-unit border, as CSS paints a background under a translucent border. */
    private Drawable box(Color fill, Color border, float vertical, float horizontal) {
        return UiTheme.pad(new EdgeBox(white, fill, border, 1, 1, 1, 1), vertical, horizontal);
    }

    private static Texture ramp() {
        Pixmap pixels = new Pixmap(2, 1, Pixmap.Format.RGBA8888);
        pixels.setBlending(Pixmap.Blending.None);
        pixels.drawPixel(0, 0, 0xFFFFFF00);
        pixels.drawPixel(1, 0, 0xFFFFFFFF);
        Texture texture = new Texture(pixels);
        pixels.dispose();
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        return texture;
    }

    /** A white disc {@code size} texels wide with analytic edge coverage, drawn at half size and less. */
    private static Texture disc(int size) {
        Pixmap pixels = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        pixels.setBlending(Pixmap.Blending.None);
        float half = size / 2f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float coverage = MathUtils.clamp(half - (float) Math.hypot(x + .5f - half, y + .5f - half), 0, 1);
                pixels.drawPixel(x, y, 0xFFFFFF00 | Math.round(coverage * 255));
            }
        }
        Texture texture = new Texture(pixels);
        pixels.dispose();
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        return texture;
    }

    /** The prototype's SVG layer: straight and dashed strokes, feathered by one unit, and the leaders' diamonds. */
    private final class Strokes extends Actor {
        /** One stroke from {@code from} to {@code to}; a dash of 0 draws it solid. */
        private record Stroke(Vector2 from, Vector2 to, float width, Color color, float dash, float gap,
              boolean flowing, boolean round) { }

        /** A link's icon on a disc. */
        private record Mark(Vector2 at, String icon) { }

        private final List<Stroke> list = new ArrayList<>();
        private final List<Mark> marks = new ArrayList<>();
        /** The units are the Tactical View's bases: lines end on circles around their rings. */
        boolean round;
        private final float[] vertices = new float[20];
        /** Seconds of the guides' flow. */
        private float clock;

        Strokes() {
            setTouchable(Touchable.disabled);
        }

        void reset() {
            list.clear();
            marks.clear();
        }

        void add(Vector2 from, Vector2 to, Rectangle source, Rectangle target, float width, Color color,
              float dash, float gap, boolean flowing, boolean round) {
            from = new Vector2(from);
            to = new Vector2(to);
            if (clip(from, to, source, target, this.round)) {
                list.add(new Stroke(from, to, width, color, dash, gap, flowing, round));
            }
        }

        void mark(Vector2 at, String icon) {
            marks.add(new Mark(at, icon));
        }

        @Override
        public void act(float delta) {
            super.act(delta);
            clock += delta;
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            float x = getX();
            float y = getY();
            for (Stroke stroke : list) {
                line(batch, stroke, x, y, parentAlpha);
            }
            Color previous = batch.getColor().cpy();
            for (Mark mark : marks) {
                float half = LINK_DISC / 2;
                batch.setColor(.09f, .13f, .13f, .92f * parentAlpha);
                batch.draw(disc, x + mark.at().x - half, y + mark.at().y - half, LINK_DISC, LINK_DISC);
                batch.setColor(LINK.r, LINK.g, LINK.b, parentAlpha);
                ui.skin.getDrawable("icon-" + mark.icon()).draw(batch, x + mark.at().x - half + 2,
                      y + mark.at().y - half + 2, LINK_DISC - 4, LINK_DISC - 4);
            }
            batch.setColor(previous);
            // Leaders run from the nearest point of a card to its target's anchor, unless the card covers it.
            for (Map.Entry<TargetKey, Vector2> entry : cardHeads.entrySet()) {
                Rectangle card = cards.get(entry.getKey());
                Vector2 head = entry.getValue();
                if (card == null) {
                    continue;
                }
                Vector2 start = new Vector2(MathUtils.clamp(head.x, card.x, card.x + card.width),
                      MathUtils.clamp(head.y, card.y, card.y + card.height));
                if (start.equals(head)) {
                    continue;
                }
                line(batch, new Stroke(start, head, LEADER_WIDTH, LEADER, 0, 0, false, false), x, y, parentAlpha);
                // An 8-unit square turned 45 degrees, its 1-unit stroke centred on its edge.
                diamond(batch, x + head.x, y + head.y, (DIAMOND / 2 + .5f) * SQRT2, Color.WHITE,
                      parentAlpha);
                diamond(batch, x + head.x, y + head.y, (DIAMOND / 2 - .5f) * SQRT2, DIAMOND_FILL,
                      parentAlpha);
            }
        }

        /** One stroke; dashes start at its origin and flow toward its end at the guides' speed. */
        private void line(Batch batch, Stroke stroke, float x, float y, float alpha) {
            Vector2 along = new Vector2(stroke.to()).sub(stroke.from());
            float length = along.len();
            if (length < .5f) {
                return;
            }
            along.scl(1 / length);
            float color = Color.toFloatBits(stroke.color().r, stroke.color().g, stroke.color().b,
                  stroke.color().a * alpha);
            float ax = x + stroke.from().x;
            float ay = y + stroke.from().y;
            if (stroke.dash() <= 0) {
                segment(batch, ax, ay, along, 0, length, stroke.width() / 2, color);
                return;
            }
            float period = stroke.dash() + stroke.gap();
            float phase = stroke.flowing() ? clock / GUIDE_SECONDS * period % period : 0;
            // Round caps reach half the width past each dash.
            float cap = stroke.round() ? stroke.width() / 2 : 0;
            for (float start = phase - period; start < length; start += period) {
                float from = Math.max(0, start - cap);
                float to = Math.min(length, start + stroke.dash() + cap);
                if (to > from) {
                    segment(batch, ax, ay, along, from, to, stroke.width() / 2, color);
                }
            }
        }

        /** A piece of a stroke: an opaque core and a one-unit feather on each side that fades to clear. */
        private void segment(Batch batch, float x, float y, Vector2 along, float from, float to, float half,
              float color) {
            float startX = x + along.x * from;
            float startY = y + along.y * from;
            float endX = x + along.x * to;
            float endY = y + along.y * to;
            float core = Math.max(0, half - .5f);
            float outer = half + .5f;
            quad(batch, startX, startY, endX, endY, -along.y, along.x, -core, core, OPAQUE, OPAQUE, color);
            quad(batch, startX, startY, endX, endY, -along.y, along.x, core, outer, OPAQUE, CLEAR, color);
            quad(batch, startX, startY, endX, endY, -along.y, along.x, -outer, -core, CLEAR, OPAQUE, color);
        }

        /** The band between two offsets across a segment, its texture running from {@code u1} to {@code u2}. */
        private void quad(Batch batch, float startX, float startY, float endX, float endY, float acrossX,
              float acrossY, float offset1, float offset2, float u1, float u2, float color) {
            corner(0, startX + acrossX * offset1, startY + acrossY * offset1, color, u1);
            corner(5, startX + acrossX * offset2, startY + acrossY * offset2, color, u2);
            corner(10, endX + acrossX * offset2, endY + acrossY * offset2, color, u2);
            corner(15, endX + acrossX * offset1, endY + acrossY * offset1, color, u1);
            batch.draw(ramp, vertices, 0, vertices.length);
        }

        private void diamond(Batch batch, float x, float y, float radius, Color color, float alpha) {
            float packed = Color.toFloatBits(color.r, color.g, color.b, color.a * alpha);
            corner(0, x, y - radius, packed, OPAQUE);
            corner(5, x + radius, y, packed, OPAQUE);
            corner(10, x, y + radius, packed, OPAQUE);
            corner(15, x - radius, y, packed, OPAQUE);
            batch.draw(ramp, vertices, 0, vertices.length);
        }

        private void corner(int offset, float x, float y, float color, float u) {
            vertices[offset] = x;
            vertices[offset + 1] = y;
            vertices[offset + 2] = color;
            vertices[offset + 3] = u;
            vertices[offset + 4] = .5f;
        }
    }

    /** A TN badge (.tnb): the roll in 700 15 Condensed white and its odds in 500 11 ink2, or a muted reason. */
    private final class Badge extends Table {
        private final Label value = ui.label("", "hud-phase", 15, Color.WHITE);
        private final Label odds = ui.label("", "hud-medium", 11, UiTheme.ACCENT);
        private final Label reason = ui.label("", "hud-medium", 10.5f, UiTheme.MUTED);
        private List<Object> shown = List.of();

        Badge() {
            setName("tn-badge");
            setTouchable(Touchable.disabled);
            badges.addActor(this);
        }

        void show(String roll, String percent, String why, Drawable box) {
            List<Object> next = List.of(roll, percent, why, box);
            if (next.equals(shown)) {
                return;
            }
            shown = next;
            clearChildren();
            setBackground(box);
            if (why.isEmpty()) {
                value.setText(roll);
                odds.setText(percent);
                // The odds sit on the roll's baseline.
                add(value).bottom();
                add(odds).bottom().padLeft(BADGE_GAP).padBottom(1);
            } else {
                reason.setText(why);
                add(reason);
            }
            pack();
        }
    }

    /** One waypoint's number: a disc 22 units over its hex, and in the Tactical View also the number on its disc. */
    private final class Pin {
        private final Group badge = new Group() {
            @Override
            public void draw(Batch batch, float parentAlpha) {
                Color previous = batch.getColor().cpy();
                // The soft shadow 2 below, then the disc.
                for (float spread : new float[] { 6, 3 }) {
                    batch.setColor(0, 0, 0, .18f * parentAlpha);
                    batch.draw(disc, getX() - spread / 2, getY() - 2 - spread / 2, PIN_SIZE + spread,
                          PIN_SIZE + spread);
                }
                batch.setColor(PIN_FILL.r, PIN_FILL.g, PIN_FILL.b, parentAlpha);
                batch.draw(disc, getX(), getY(), PIN_SIZE, PIN_SIZE);
                batch.setColor(previous);
                super.draw(batch, parentAlpha);
            }
        };
        private final Label flat;
        private final float flatScale;

        Pin(int number) {
            badge.setName("pin-" + number);
            badge.setTransform(false);
            badge.setSize(PIN_SIZE, PIN_SIZE);
            Label label = ui.label(String.valueOf(number), "hud-medium", PIN_TEXT, PIN_INK);
            label.pack();
            label.setPosition(Math.round((PIN_SIZE - label.getWidth()) / 2),
                  Math.round((PIN_SIZE - label.getHeight()) / 2));
            badge.addActor(label);
            flat = ui.label(String.valueOf(number), "hud-medium", FLAT_PIN_TEXT, PIN_INK);
            flat.setName("pin-flat-" + number);
            flatScale = flat.getFontScaleX() / FLAT_PIN_TEXT;
            pins.addActor(badge);
            pins.addActor(flat);
        }

        void place(Vector2 hex, GpuHud.HudView view) {
            badge.setVisible(hex != null);
            flat.setVisible(hex != null && view.tactical());
            if (hex == null) {
                return;
            }
            badge.setPosition(Math.round(hex.x - PIN_SIZE / 2), Math.round(hex.y + PIN_RISE - PIN_SIZE / 2));
            if (view.tactical()) {
                // flat.js: the number on the hex's own disc, max(10, .26 of the hex radius); hexPixels is its width.
                flat.setFontScale(flatScale * Math.max(FLAT_PIN_TEXT, .13f * view.hexPixels()));
                flat.pack();
                flat.setPosition(Math.round(hex.x - flat.getWidth() / 2), Math.round(hex.y - flat.getHeight() / 2));
            }
        }
    }

    /**
     * A playback pop-up (.pop3d): 800 26 Condensed gold with a dark edge and a glow for a hit, 16 light grey
     * letter-spaced for a miss or a roll, and a 700 11 Condensed white line under it; rising and fading.
     */
    private final class Pop extends Group {
        private final int unit;
        private final boolean hit;
        private final Label title;
        private final Label sub;
        private final Vector2 anchor = new Vector2();
        private boolean anchored;
        /** The age the pop-up shows, held while frozen, and the time since it appeared, which always runs. */
        private float age;
        private float life;
        /** How far it rides over its rising place, so it clears the unit's newer pop-ups (stackPops). */
        private float lift;

        Pop(int unit, String titleText, String subText, boolean hit) {
            this.unit = unit;
            this.hit = hit;
            setName("pop-up");
            setTransform(false);
            setTouchable(Touchable.disabled);
            title = hit ? ui.label(titleText, "hud-phase", 26, Color.WHITE)
                  : ui.label(titleText, "hud-main", 16, Color.WHITE);
            sub = ui.label(subText, "hud-caption", 11, Color.WHITE);
            title.pack();
            sub.pack();
            float subHeight = subText.isEmpty() ? 0 : sub.getHeight();
            title.setPosition(0, subHeight);
            addActor(title);
            if (!subText.isEmpty()) {
                addActor(sub);
            }
            setSize(Math.max(title.getWidth(), sub.getWidth()), title.getHeight() + subHeight);
        }

        /** Follows the unit's middle while it is drawn and keeps the last place when it is not. */
        void place(GpuHud.HudView view) {
            Vector2 middle = middle(unit, view);
            if (middle != null) {
                anchor.set(middle);
                anchored = true;
            }
            float top = anchor.y + POP_RISE + age * POP_SPEED + lift;
            setPosition(Math.round(anchor.x + POP_DX), Math.round(top - getHeight()));
        }

        private boolean newest() {
            return pops.getChildren().peek() == this;
        }

        @Override
        public void act(float delta) {
            super.act(delta);
            life += delta;
            age = frozen ? Math.min(age, POP_FROZEN_AGE) : age + delta;
            // The layer places the pop-ups that stay once all of them aged.
            if (life >= popLife && !(frozen && newest())) {
                remove();
            }
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            float alpha = frozen && !newest() || !anchored ? 0 : parentAlpha * Math.max(0, 1 - age / POP_FADE);
            if (alpha <= 0) {
                return;
            }
            if (hit) {
                // text-shadow: 0 2px 0 #6b4200, 0 0 16px rgba(255, 160, 40, .6), 0 2px 8px #000; the last lowest.
                for (float[] offset : new float[][] { { -2, -3 }, { 2, -3 }, { 0, -5 } }) {
                    layer(batch, offset[0], offset[1], POP_SHADOW, POP_SHADOW, alpha);
                }
                for (float[] offset : new float[][] { { -3, 0 }, { 3, 0 }, { 0, 3 }, { 0, -3 } }) {
                    layer(batch, offset[0], offset[1], POP_GLOW, POP_GLOW, alpha);
                }
                layer(batch, 0, -2, POP_EDGE, POP_EDGE, alpha);
            } else {
                // text-shadow: 0 2px 6px #000.
                for (float[] offset : new float[][] { { -1, -2 }, { 1, -2 }, { 0, -3 } }) {
                    layer(batch, offset[0], offset[1], POP_SHADOW, POP_SHADOW, alpha);
                }
            }
            layer(batch, 0, 0, hit ? POP_HIT : POP_MISS, Color.WHITE, alpha);
        }

        /** The two lines once, moved by (dx, dy) and drawn in the given colours. */
        private void layer(Batch batch, float dx, float dy, Color titleColor, Color subColor, float alpha) {
            title.setColor(titleColor);
            sub.setColor(subColor);
            moveBy(dx, dy);
            super.draw(batch, alpha);
            moveBy(-dx, -dy);
        }
    }
}
