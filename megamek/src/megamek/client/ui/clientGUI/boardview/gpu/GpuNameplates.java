/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;
import static megamek.client.ui.gdx.UiTheme.rgba;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.clientGUI.boardview.BoardElectronicWarfare;
import megamek.client.ui.clientGUI.boardview.NameplateMode;
import megamek.client.ui.clientGUI.boardview.UnitStatusWords;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.UnitStatus;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.gdx.UiTheme.EdgeBox;
import megamek.common.units.Entity;

/**
 * Unit nameplates and pips over the board, the lowest HUD layer
 * Every unit with a head anchor gets a team pip, or a tag while it is the focus unit, hovered,
 * inspected, the physical target or a sensor contact, and every unit while the nameplate key shows them all or the
 * client's nameplate setting ({@link NameplateMode}) always does. A tag names the
 * unit as the client's label style does (the Legacy 2D board label's name) before its status words; a tag with neither
 * stays a pip (no phase words, no model repeated after the name).
 * Every unit but a sensor contact or a destroyed unit also gets its board label's marks on its head, under the pip or
 * tag: the Legacy 2D board's damage tile, armor and structure bars ({@link GpuBattleStatus.Marks}), and an E(C)CM
 * source its "ECM"/"ECCM" chip beside them. A tag carries the unit's weight class letter and its gunnery and piloting
 * skills. In the Tactical View the units' bases show the pips, marks and E(C)CM badges, so a unit has a tag or
 * nothing. The weapon target cards replace their targets' plates, and a unit whose head lies behind the camera has
 * none. The hovered unit's plate and the plate of the unit the card shows are drawn over the others, and the board
 * labels' TN badges stand on the plates ({@link #top}). It reads the presented snapshots only, on the render thread,
 * and takes no presses.
 */
final class GpuNameplates implements GpuHud.Component {
    /** Beyond this many units an unhovered sensor contact shows only "?". */
    private static final int MANY_UNITS = 40;
    private static final String CONTACT_UNKNOWN = "?";
    /** .pip: a down-pointing triangle 10 wide and 7 tall. */
    private static final float PIP_WIDTH = 10;
    private static final float PIP_HEIGHT = 7;
    /**
     * .tag: 600 11px Roboto (Medium, the nearest shipped weight) on its normal line of 13 px, as the browser lays it
     * out, padding 3 7 inside a 1 px border, gap 6.
     */
    private static final float TEXT_SIZE = 11;
    private static final float LINE_HEIGHT = 13;
    private static final float PAD_Y = 4;
    private static final float PAD_X = 8;
    private static final float GAP = 6;
    /** .tag.blip: a 1 px dashed border. */
    private static final float DASH = 3;
    /** .tag .sub: weight 400 at 78 % opacity. */
    private static final float SUB_OPACITY = .78f;
    /** .pip.done (moved in the movement phase) and a destroyed unit's pip. */
    private static final float MOVED_OPACITY = .45f;
    private static final float DESTROYED_OPACITY = .3f;
    /**
     * .pip filter drop-shadow(0 1px 2px rgba(0, 0, 0, .8)) as wider layers one unit lower, by spread: .6 within one
     * unit of the pip, .2 at two units.
     */
    private static final float[] SHADOW = { .5f, .2f };
    private static final Color FILL = rgba(23, 35, 35, .88f);
    /**
     * The marks: the Legacy 2D label bars, 24 long and 3 tall, the structure bar 1 under the armor bar, 3 right of the
     * damage tile, all 2 inside the tags' fill; the pip or tag stands 2 above them.
     */
    private static final float BAR_LENGTH = 24;
    private static final float BAR_HEIGHT = 3;
    private static final float BAR_GAP = 1;
    private static final float TILE_GAP = 3;
    private static final float MARKS_PAD = 2;
    private static final float MARKS_GAP = 2;
    private static final Color SELECTED_TEXT = Color.valueOf("EAF9F1");
    private static final String BEST = "GpuBoard.hud.plate.best";
    private static final String DISTANCE = "GpuBoard.hud.plate.distance";
    private static final String STATUS = "GpuBoard.hud.plate.status";
    private static final String DESTROYED = "GpuBoard.hud.plate.destroyed";
    private static final String SENSOR_CONTACT = "GpuBoard.hud.common.sensorContact";

    /** The tag of a friendly unit, the focus unit (.mint), an enemy (.coral) and a sensor contact (.blip). */
    private enum Look {
        FRIEND(UiTheme.MINT, UiTheme.alpha(UiTheme.MINT, .45f), FILL, false),
        FOCUS(SELECTED_TEXT, UiTheme.MINT, FILL, false),
        ENEMY(UiTheme.CORAL, UiTheme.alpha(UiTheme.CORAL, .5f), FILL, false),
        CONTACT(UiTheme.BLIP, UiTheme.alpha(UiTheme.BLIP, .8f), rgba(40, 28, 20, .85f), true);

        final Color text;
        final Color sub;
        final Color border;
        final Color fill;
        final boolean dashed;

        Look(Color text, Color border, Color fill, boolean dashed) {
            this.text = text;
            sub = UiTheme.alpha(text, SUB_OPACITY);
            this.border = border;
            this.fill = fill;
            this.dashed = dashed;
        }
    }

    private final GpuHudKit kit;
    private final UiKit ui;
    private final GpuHudState state;
    private final Texture white;
    private final Table root = new Table();
    /** Marks, then pips, then tags, as tags carry the names the player asked to see. */
    private final Group marks = new Group();
    private final Group pips = new Group();
    private final Group tags = new Group();
    /** Each look's tag box: its fill under a one-unit border, solid or dashed (.plate .tag and .tag.blip). */
    private final Map<Look, EdgeBox> boxes = new EnumMap<>(Look.class);
    private final Map<Integer, Plate> plates = new HashMap<>();

    GpuNameplates(GpuHudKit kit, GpuBoardSource source, GpuHudState state) {
        this.kit = kit;
        ui = kit.ui;
        this.state = state;
        white = ui.skin.get("white", Texture.class);
        for (Look look : Look.values()) {
            EdgeBox box = new EdgeBox(white, look.fill, look.border, 1, 1, 1, 1);
            boxes.put(look, look.dashed ? box.dashed(DASH) : box);
        }
        root.setName("nameplates");
        for (Group layer : List.of(marks, pips, tags)) {
            layer.setTransform(false);
            root.addActor(layer);
        }
    }

    @Override
    public Actor actor() {
        return root;
    }

    /**
     * A unit anchor's point in {@code camera}'s viewport, y up, or null when the anchor lies behind the camera, where
     * the prototype hides its label (overlay.js placeLabels). The board view projects the anchors it fills
     * {@link GpuHud.HudView#unitHeads} with through this; a unit without a head gets no plate.
     */
    static Vector2 project(Camera camera, Vector3 anchor) {
        if (new Vector3(anchor).sub(camera.position).dot(camera.direction) < camera.near) {
            return null;
        }
        Vector3 point = camera.project(new Vector3(anchor), 0, 0, camera.viewportWidth, camera.viewportHeight);
        return new Vector2(point.x, point.y);
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        GpuBattleStatus.Snapshot status = inputs.frame().status();
        GpuFireOrders.Snapshot fire = inputs.frame().panels().fire();
        GpuPhysicalOptions.Snapshot physical = inputs.frame().panels().physical();
        GpuHud.HudView view = inputs.view();
        List<UnitStatus> units = state.presentedUnits();
        UnitStatus focus = units.stream().filter(unit -> unit.id() == state.focus()).findFirst().orElse(null);
        // The target cards replace the plates of the attack targets and of the focused enemy (overlay.js:105).
        Set<TargetKey> carded = fire.carded();
        int physicalTarget = physical.active() ? physical.targetId() : Entity.NONE;
        boolean many = units.size() > MANY_UNITS;
        boolean all = state.allNameplates || inputs.preferences().nameplates() == NameplateMode.ALWAYS;
        // In the Tactical View the bases carry the pips, marks and E(C)CM badges.
        boolean based = view.tactical();
        BoardScene scene = inputs.frame().scene();
        Map<Integer, BoardElectronicWarfare.Source> sources = new HashMap<>();
        if (scene != null && scene.electronicWarfare() != null && !based) {
            scene.electronicWarfare().sources().forEach(source -> sources.putIfAbsent(source.entityId(), source));
        }
        Set<Integer> shown = new HashSet<>();
        for (UnitStatus unit : units) {
            Vector2 head = view.unitHeads().get(unit.id());
            if (head == null || carded.contains(TargetKey.unit(unit.id()))) {
                continue;
            }
            boolean hovered = view.hoveredUnit() == unit.id();
            boolean enemy = unit.side() == GpuBattleStatus.Side.ENEMY;
            boolean tagged = unit.id() == state.focus() || hovered || all || unit.id() == state.inspected
                  || unit.id() == physicalTarget;
            String sub = tagged && !unit.sensorContact() ? sub(unit, hovered && enemy ? focus : null, fire) : "";
            boolean tag = unit.sensorContact() || tagged && !(unit.label().isEmpty() && sub.isEmpty());
            if (based && !tag) {
                continue;
            }
            Plate plate = plates.computeIfAbsent(unit.id(), Plate::new);
            if (unit.sensorContact()) {
                plate.tag(Look.CONTACT, hovered || !many ? text(SENSOR_CONTACT) : CONTACT_UNKNOWN, "", "");
            } else if (tag) {
                plate.tag(unit.id() == state.focus() ? Look.FOCUS : enemy ? Look.ENEMY : Look.FRIEND, unit.label(),
                      sub, chip(unit));
            } else {
                plate.pip(enemy ? UiTheme.CORAL : UiTheme.MINT, unit.destroyed() ? DESTROYED_OPACITY
                      : unit.done() && status.phase().isMovement() ? MOVED_OPACITY : 1);
            }
            plate.marks(based ? null : unit);
            plate.ecm(unit.destroyed() ? null : sources.get(unit.id()));
            plate.place(head);
            shown.add(unit.id());
        }
        for (Iterator<Plate> iterator = plates.values().iterator(); iterator.hasNext(); ) {
            Plate plate = iterator.next();
            if (!shown.contains(plate.id)) {
                plate.tag.remove();
                plate.pip.remove();
                plate.marks.remove();
                plate.ecm.remove();
                iterator.remove();
            }
        }
        // Each layer keeps the units' order with the hovered unit's actors and then the card unit's last, so the
        // highlighted unit's plate is never buried under another.
        List<Plate> ordered = units.stream().map(unit -> plates.get(unit.id())).filter(Objects::nonNull).toList();
        Plate hovered = plates.get(view.hoveredUnit());
        Plate card = plates.get(state.cardUnit());
        List<Actor> markers = ordered.stream().map(Plate::marker).toList();
        for (Group layer : List.of(pips, tags)) {
            GpuHudKit.stack(markers.stream().filter(marker -> marker.getParent() == layer).toList(),
                  hovered == null ? null : hovered.marker(), card == null ? null : card.marker());
        }
        GpuHudKit.stack(ordered.stream().map(plate -> plate.marks).filter(Actor::hasParent).toList(),
              hovered == null ? null : hovered.marks, card == null ? null : card.marks);
    }

    /** The top of the unit's plate this frame, y up, or {@code head} when it has none. */
    float top(int unitId, float head) {
        Plate plate = plates.get(unitId);
        return plate == null ? head : plate.marker().getY() + plate.marker().getHeight();
    }

    /** The width of the unit's shown marker (its tag, else its pip), or 0 without one. */
    float width(int unitId) {
        Plate plate = plates.get(unitId);
        return plate == null ? 0 : plate.marker().getWidth();
    }

    /**
     * A tag's lighter part: "destroyed", or the board label's status words in their order and, for an enemy hovered
     * while the focus unit {@code from} acts, the actor's best roll of the weapons phase (or why there is none), else
     * the distance from the focus unit; empty without any of these.
     */
    private static String sub(UnitStatus unit, UnitStatus from, GpuFireOrders.Snapshot fire) {
        if (unit.destroyed()) {
            return text(DESTROYED);
        }
        List<String> parts = new ArrayList<>(unit.statusWords().stream().map(UnitStatusWords.StatusWord::label)
              .toList());
        if (from != null) {
            parts.add(hoverDetail(unit, from, fire));
        }
        return parts.stream().filter(part -> !part.isEmpty()).reduce((line, part) -> text(STATUS, line, part))
              .orElse("");
    }

    /**
     * A tag's chip: the unit's weight class letter (L, M, H, A; other classes their first letter) and its gunnery and
     * piloting skills, as "H 4/5"; empty for a destroyed unit.
     */
    static String chip(UnitStatus unit) {
        if (unit.destroyed()) {
            return "";
        }
        String letter = unit.weightClass().isBlank() ? "" : unit.weightClass().strip().substring(0, 1).toUpperCase();
        return text("GpuBoard.hud.plate.skills", letter, unit.gunnery(), unit.piloting()).strip();
    }

    /** The hovered enemy's part: the focus unit's best roll on it while it declares fire, else its distance, or "". */
    private static String hoverDetail(UnitStatus unit, UnitStatus from, GpuFireOrders.Snapshot fire) {
        if (fire.editable()) {
            GpuFireOrders.Badge best = fire.hoverBest();
            if (best == null || best.targetId() != unit.id()) {
                return "";
            }
            return best.reason() == null || best.reason().isEmpty()
                  ? text(BEST, GpuHudKit.shown(best.value()), Math.round(best.odds())) : best.reason();
        }
        boolean measured = from.position() != null && unit.position() != null && from.boardId() == unit.boardId();
        return measured ? text(DISTANCE, from.position().distance(unit.position())) : "";
    }

    /**
     * One unit's plate: its marker, the tag (.plate .tag) or the pip (.plate .pip), and its marks; the marks sit
     * bottom-centred on the unit's head with the marker centred above them, or the marker alone on the head.
     */
    private final class Plate {
        final int id;
        final Table tag = new Table();
        final Label name = ui.label("", "hud-medium", TEXT_SIZE, Color.WHITE);
        final Label chip = ui.label("", "hud-medium", TEXT_SIZE, Color.WHITE);
        final Cell<Label> chipCell;
        final Label sub = ui.label("", "hud-body", TEXT_SIZE, Color.WHITE);
        final Cell<Label> subCell;
        final Pip pip = new Pip();
        final UnitMarks marks = new UnitMarks();
        /** An E(C)CM source's chip, in the marks' row. */
        final Table ecm = new Table();
        final Label ecmText = ui.label("", "hud-medium", TEXT_SIZE, Color.WHITE);
        private Look look;
        private BoardElectronicWarfare.Source ecmSource;

        Plate(int id) {
            this.id = id;
            tag.setName("nameplate-" + id);
            tag.setTouchable(Touchable.disabled);
            tag.pad(PAD_Y, PAD_X, PAD_Y, PAD_X);
            tag.add(name).height(LINE_HEIGHT);
            chipCell = tag.add(chip).height(LINE_HEIGHT);
            subCell = tag.add(sub).height(LINE_HEIGHT);
            pip.setName("pip-" + id);
            marks.setName("marks-" + id);
            ecm.setName("ecm-" + id);
            ecm.setTouchable(Touchable.disabled);
            ecm.pad(1, 5, 1, 5);
            ecm.add(ecmText).height(LINE_HEIGHT);
        }

        /** Shows the unit's marks, or none for a sensor contact (it has none), a destroyed unit or null. */
        void marks(UnitStatus unit) {
            if (unit == null || unit.destroyed() || !unit.marks().shown()) {
                marks.remove();
                return;
            }
            if (marks.getParent() == null) {
                GpuNameplates.this.marks.addActor(marks);
            }
            marks.set(unit);
        }

        /**
         * Shows the E(C)CM chip of {@code source} in its side's colour (the E(C)CM record's own or enemy field), at 40 %
         * when TacOps ECCM negated its field and with a small "A" for an Angel suite; none for null.
         */
        void ecm(BoardElectronicWarfare.Source source) {
            if (source == null) {
                ecm.remove();
                ecmSource = null;
                return;
            }
            if (ecm.getParent() == null) {
                GpuNameplates.this.marks.addActor(ecm);
            }
            if (source.equals(ecmSource)) {
                return;
            }
            ecmSource = source;
            Look side = source.own() ? Look.FRIEND : Look.ENEMY;
            String kind = text(source.ecm() ? "GpuBoard.hud.plate.ecm" : "GpuBoard.hud.plate.eccm");
            ecmText.setText(source.angel() ? text("GpuBoard.hud.plate.angel", kind) : kind);
            ecmText.setColor(side.text);
            ecm.setBackground(boxes.get(side));
            ecm.getColor().a = source.suppressed() ? .4f : 1;
            ecm.pack();
            ecm.setSize(Math.round(ecm.getWidth()), Math.round(ecm.getHeight()));
        }

        void tag(Look look, String nameText, String subText, String chipText) {
            pip.remove();
            if (tag.getParent() == null) {
                tags.addActor(tag);
            }
            if (look == this.look && name.textEquals(nameText) && sub.textEquals(subText)
                  && chip.textEquals(chipText)) {
                return;
            }
            this.look = look;
            tag.setBackground(boxes.get(look));
            name.setText(nameText);
            name.setColor(look.text);
            chip.setText(chipText);
            chip.setColor(look.text);
            chipCell.padLeft(nameText.isEmpty() || chipText.isEmpty() ? 0 : GAP);
            sub.setText(subText);
            sub.setColor(look.sub);
            subCell.padLeft(nameText.isEmpty() && chipText.isEmpty() || subText.isEmpty() ? 0 : GAP);
            tag.pack();
            // Whole units keep the one-unit border on the pixel grid, as the browser snaps the box's edges.
            tag.setSize(Math.round(tag.getWidth()), Math.round(tag.getHeight()));
        }

        void pip(Color color, float opacity) {
            tag.remove();
            if (pip.getParent() == null) {
                pips.addActor(pip);
            }
            pip.setColor(color.r, color.g, color.b, opacity);
        }

        void place(Vector2 head) {
            float bottom = Math.round(head.y);
            Actor shown = marker();
            if (ecm.hasParent()) {
                // Right of the marks' row, or of the marker without marks.
                Actor beside = marks.hasParent() ? marks : shown;
                ecm.setPosition(Math.round(head.x + beside.getWidth() / 2 + MARKS_GAP), bottom);
            }
            if (marks.hasParent()) {
                marks.setPosition(Math.round(head.x - marks.getWidth() / 2), bottom);
                bottom += marks.getHeight() + MARKS_GAP;
            }
            shown.setPosition(Math.round(head.x - shown.getWidth() / 2), bottom);
        }

        /** The shown marker: the tag, else the pip. */
        Actor marker() {
            return tag.getParent() != null ? tag : pip;
        }
    }

    /** The pip: the team-coloured triangle over a soft shadow, drawn from the skin's white texel. */
    private final class Pip extends Actor {
        private final float[] vertices = new float[20];

        Pip() {
            setSize(PIP_WIDTH, PIP_HEIGHT);
            setTouchable(Touchable.disabled);
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            Color color = getColor();
            float alpha = color.a * parentAlpha;
            float left = getX();
            float right = left + PIP_WIDTH;
            float top = getY() + PIP_HEIGHT;
            float middle = left + PIP_WIDTH / 2;
            for (int spread = SHADOW.length; spread > 0; spread--) {
                triangle(batch, left - spread, right + spread, top + spread - 1, middle, getY() - spread - 1,
                      Color.toFloatBits(0, 0, 0, SHADOW[spread - 1] * alpha));
            }
            triangle(batch, left, right, top, middle, getY(), Color.toFloatBits(color.r, color.g, color.b, alpha));
        }

        /** A triangle as a quad whose last two corners meet at the tip. */
        private void triangle(Batch batch, float left, float right, float top, float tipX, float tipY, float color) {
            corner(0, left, top, color);
            corner(5, right, top, color);
            corner(10, tipX, tipY, color);
            corner(15, tipX, tipY, color);
            batch.draw(white, vertices, 0, vertices.length);
        }

        private void corner(int offset, float x, float y, float color) {
            vertices[offset] = x;
            vertices[offset + 1] = y;
            vertices[offset + 2] = color;
            vertices[offset + 3] = .5f;
            vertices[offset + 4] = .5f;
        }
    }

    /**
     * A unit's marks on the tags' fill, as the Legacy 2D board label draws them (UnitAnnotations): the damage tile, if
     * any, then the armor bar over the structure bar, if the unit has one, each on the label's grey track.
     */
    private final class UnitMarks extends Actor {
        private final Color damage = new Color();
        private final Color armor = new Color();
        private final Color structure = new Color();
        private boolean tile;
        private boolean structureBar;
        private float armorShare;
        private float structureShare;

        UnitMarks() {
            setTouchable(Touchable.disabled);
        }

        void set(UnitStatus unit) {
            GpuBattleStatus.Marks marks = unit.marks();
            tile = marks.damageArgb() != 0;
            structureBar = marks.structureArgb() != 0;
            Color.argb8888ToColor(damage, marks.damageArgb());
            Color.argb8888ToColor(armor, marks.armorArgb());
            Color.argb8888ToColor(structure, marks.structureArgb());
            // A unit without armor at all (ARMOR_NA) shows an empty bar, as the Legacy 2D label does.
            armorShare = Math.max(0, (float) unit.armor());
            structureShare = Math.max(0, (float) unit.structure());
            float content = tile ? Math.max(GpuHudKit.DAMAGE_TILE, bars()) : bars();
            setSize(2 * MARKS_PAD + (tile ? GpuHudKit.DAMAGE_TILE + TILE_GAP : 0) + BAR_LENGTH,
                  2 * MARKS_PAD + content);
        }

        private float bars() {
            return structureBar ? 2 * BAR_HEIGHT + BAR_GAP : BAR_HEIGHT;
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            float previous = batch.getPackedColor();
            float alpha = getColor().a * parentAlpha;
            ui.fill(batch, FILL, alpha, getX(), getY(), getWidth(), getHeight());
            float x = getX() + MARKS_PAD;
            if (tile) {
                kit.damageTile(batch, damage, alpha, x, getY() + (getHeight() - GpuHudKit.DAMAGE_TILE) / 2);
                x += GpuHudKit.DAMAGE_TILE + TILE_GAP;
            }
            float top = getY() + (getHeight() + bars()) / 2;
            bar(batch, alpha, x, top - BAR_HEIGHT, armorShare, armor);
            if (structureBar) {
                bar(batch, alpha, x, top - 2 * BAR_HEIGHT - BAR_GAP, structureShare, structure);
            }
            batch.setPackedColor(previous);
        }

        private void bar(Batch batch, float alpha, float x, float y, float share, Color fill) {
            ui.fill(batch, GpuHudKit.LABEL_GREY, alpha, x, y, BAR_LENGTH, BAR_HEIGHT);
            ui.fill(batch, fill, alpha, x, y, BAR_LENGTH * Math.min(share, 1), BAR_HEIGHT);
        }
    }
}
